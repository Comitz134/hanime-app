package app.hanime.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs hanime.tv's signature module inside a WebView and answers requests for
 * a fresh signature pair.
 *
 * The module is browser code: it listens for a `window` event named "e" and
 * publishes `window.ssignature` / `window.stime`. The Node proxy has to fake an
 * entire browser window to run it; on Android there is a real one, so the
 * module runs unmodified — this class is just the bridge between "Java needs
 * two headers" and "dispatch an event and read two globals".
 *
 * The signature is not minted from the clock alone: dispatching the event makes
 * the module read `window.top.location.origin` and fold it in, and upstream
 * checks that against the request's own `Origin` header. So this page has to
 * load from {@link #SIGNER_ORIGIN} itself — a loopback URL produces a
 * perfectly valid signature for the wrong origin, and every handshake it signs
 * is refused as `401 {"error_id":"UA1"}`. The page and its module are still
 * this app's own bytes; {@link #local} serves them for that origin, and no
 * request ever leaves the device.
 *
 * Signing happens immediately before the request it is for, because the
 * signature is keyed to the current second.
 */
@SuppressLint("SetJavaScriptEnabled")
final class Signer {

    private static final String TAG = "ShellSigner";
    private static final long DEFAULT_TIMEOUT_MS = 5_000;

    /** The origin the module signs for — hanime.tv's, because upstream checks it. */
    static final String SIGNER_ORIGIN = "https://hanime.tv";

    /**
     * Fire the event and read the pair back. Kept as one expression so the
     * dispatch and the read are in the same tick.
     */
    private static final String SIGN_JS =
            "(function(){try{"
                    + "if(typeof window.ssignature==='undefined')return null;"
                    + "window.dispatchEvent(new CustomEvent('e',{detail:{}}));"
                    + "return window.ssignature+'|'+window.stime;"
                    + "}catch(e){return null;}})()";

    private static final String READY_JS =
            "(function(){return typeof window.ssignature!=='undefined';})()";

    /** The three files that boot the module, and how each is typed. */
    private static final Map<String, String> ASSETS = new HashMap<>();

    static {
        ASSETS.put("signer.html", "text/html");
        ASSETS.put("htv-signer.js", "text/javascript");
        ASSETS.put("htv-signer.wasm", "application/wasm");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private WebView web;
    private volatile boolean booted = false;

    Signer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Where the module's host page lives: the site's own origin, with the bytes
     * coming out of the APK through {@link #local}.
     */
    static String signerUrl() {
        return SIGNER_ORIGIN + "/signer.html";
    }

    /** Must be called on the UI thread. */
    @SuppressLint("AddJavascriptInterface")
    void start() {
        if (web != null) return;

        web = new WebView(context);
        web.getSettings().setJavaScriptEnabled(true);
        // The module ignores the user agent (nothing in it reads `navigator`),
        // but the requests these signatures travel with carry
        // [Streams.USER_AGENT], so the browser announcing the same identity
        // keeps the page and the calls it signs looking like one client.
        web.getSettings().setUserAgentString(Streams.USER_AGENT);
        // Sits off to the side at 1x1; it exists only to run the module.
        web.setLayoutParams(new FrameLayout.LayoutParams(1, 1));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return local(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "signer page loaded: " + url);
            }
        });
        web.loadUrl(signerUrl());
        Log.i(TAG, "signer booting");
    }

    /**
     * Answer one request for the signer's own origin out of the APK.
     *
     * Only the module's three files exist here, so anything else asked of
     * `hanime.tv` is refused rather than passed to the network: this WebView is
     * a place to run one module, never a browser on the site.
     */
    private WebResourceResponse local(Uri url) {
        if (url == null || !"hanime.tv".equals(url.getHost())) return null;

        String name = url.getPath() == null ? "" : url.getPath().replaceFirst("^/", "");
        String type = ASSETS.get(name);
        if (type == null) return notFound();

        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new WebResourceResponse(type,
                    type.equals("application/wasm") ? null : "utf-8",
                    200, "OK", new HashMap<>(), new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            Log.w(TAG, "could not serve " + name, e);
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    // ------------------------------------------------------------- lifecycle

    private static volatile Signer instance;

    /**
     * The process's one signer, started on the UI thread the first time a
     * signature is wanted. Nothing else here runs on the UI thread, and a
     * WebView cannot be built off it, so the construction is posted.
     */
    static Signer ensure(Context context) {
        Signer local = instance;
        if (local != null) return local;
        synchronized (Signer.class) {
            if (instance == null) {
                Signer created = new Signer(context.getApplicationContext());
                MainThread.post(created::start);
                instance = created;
            }
            return instance;
        }
    }

    /**
     * Wait until the module has published a signature at least once, or the
     * wait runs out. The first request after a cold start is the one that pays
     * for the page load; later ones find it ready immediately.
     */
    boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ready()) return true;
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** The hosting view, so the activity can put it in the hierarchy. */
    View view() {
        return web;
    }

    /** Must be called on the UI thread. */
    void stop() {
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    /**
     * A fresh `signature|time` string, or null if the module is not up in time.
     *
     * Safe to call from any thread: the evaluation itself is posted to the UI
     * thread, and the caller blocks only for as long as it takes to run one
     * expression. Callers are on the WebView's network thread, never the UI
     * thread, so this cannot deadlock against itself.
     */
    String sign() {
        String[] r = evaluate(SIGN_JS, DEFAULT_TIMEOUT_MS);
        return r == null ? null : r[0];
    }

    /** True once the module has published a signature at least once. */
    boolean ready() {
        String[] r = evaluate(READY_JS, 1_500);
        return r != null && "true".equals(r[0]);
    }

    private String[] evaluate(String expression, long timeoutMs) {
        final WebView target = web;
        if (target == null) return null;

        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);

        boolean posted = main.post(() -> {
            try {
                target.evaluateJavascript(expression, value -> {
                    out[0] = unwrap(value);
                    latch.countDown();
                });
            } catch (Exception e) {
                Log.w(TAG, "evaluate failed", e);
                latch.countDown();
            }
        });
        if (!posted) return null;

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "signer timed out after " + timeoutMs + "ms");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out;
    }

    /** evaluateJavascript hands back JSON: `"abc|123"` or `null`. */
    private static String unwrap(String jsonValue) {
        if (jsonValue == null || jsonValue.equals("null") || jsonValue.equals("undefined")) {
            return null;
        }
        String v = jsonValue;
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    /**
     * Parse the `signature|time` pair into {signature, time}, or null when the
     * module had nothing to give.
     */
    static String[] parsePair(String raw) {
        if (raw == null) return null;
        int bar = raw.lastIndexOf('|');
        if (bar <= 0) return null;
        String signature = raw.substring(0, bar);
        String time = raw.substring(bar + 1);
        if (signature.isEmpty() || time.isEmpty()) return null;
        return new String[]{signature, time};
    }
}
