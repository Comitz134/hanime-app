package app.hanime.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs mangafire.to's signing bundle in a WebView and answers `vrf` requests.
 *
 * Their API signs every listing request — search, a title, its chapters, a
 * chapter's pages — with a token minted from the request's path and canonical
 * query. The bundle that mints it decodes itself against `navigator.appCodeName`
 * and does not run outside a browser, so Android's WebView is the only thing in
 * this process that can run it, exactly as shipped. The same arrangement as
 * [Signer] next door: ours to load, theirs to execute.
 *
 * Nothing here talks to their API. A request for a signature goes in, and
 * `{ qs, vrf }` comes out — the query string that was signed along with its
 * token, because the two have to be built from the same object.
 */
@SuppressLint("SetJavaScriptEnabled")
final class MangaSigner {

    private static final String TAG = "ShellManga";
    private static final long DEFAULT_TIMEOUT_MS = 6_000;
    private static final long POLL_MS = 60;
    private static final int POLLS = 80;

    /**
     * The bundle reads the page it runs on, so the page is served from their own
     * origin: same reason the hanime signer loads from hanime.tv's, one vendor
     * over. The bytes are still this app's — see [local].
     */
    static final String ORIGIN = "https://mangafire.to";

    /** The two files the page needs, and how each is typed. */
    private static final Map<String, String> ASSETS = new HashMap<>();

    static {
        ASSETS.put("mf-signer.html", "text/html");
        ASSETS.put("mangafire-polyfill.js", "text/javascript");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private WebView web;

    MangaSigner(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Must be called on the UI thread. */
    void start() {
        if (web != null) return;

        web = new WebView(context);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                // The bundle's own output, on Android's log: without it a module
                // that fails to boot fails silently, which is what happened.
                Log.i(TAG, "page: " + message.message() + " (" + message.sourceId()
                        + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return local(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "manga signer page loaded: " + url);
            }
        });
        web.loadUrl(ORIGIN + "/mf-signer.html");
        Log.i(TAG, "manga signer booting");
    }

    /** One request for the signing page's own origin, answered out of the APK. */
    private WebResourceResponse local(Uri url) {
        if (url == null || !"mangafire.to".equals(url.getHost())) return null;

        String name = url.getPath() == null ? "" : url.getPath().replaceFirst("^/", "");
        String type = ASSETS.get(name);
        if (type == null) return notFound();

        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new WebResourceResponse(type, "utf-8", 200, "OK",
                    new HashMap<>(), new ByteArrayInputStream(out.toByteArray()));
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

    private static volatile MangaSigner instance;

    /** The process's one manga signer, started on the UI thread on first use. */
    static MangaSigner ensure(Context context) {
        MangaSigner local = instance;
        if (local != null) return local;
        synchronized (MangaSigner.class) {
            if (instance == null) {
                MangaSigner created = new MangaSigner(context.getApplicationContext());
                MainThread.post(created::start);
                instance = created;
            }
            return instance;
        }
    }

    /** Wait for the bundle to register its interceptor, or give up. */
    boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String[] r = evaluate("(function(){return window.__mfReady===true;})()", 1_200);
            if (r != null && "true".equals(r[0])) return true;
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Sign one request. Returns `{query, token}` — the query string that was
     * signed and the `vrf` for it — or null when the bundle had nothing to give.
     *
     * Safe to call from any thread, and safe to call while another call is
     * waiting: the page answers each request under its own id, so two calls in
     * flight at once cannot be handed the same token. They used to be - a title
     * page asks for the title and its chapters together, and the shared slot
     * gave both the token minted last, which upstream refused as 403.
     */
    String[] sign(String path, String paramsJson) {
        String quotedPath = JSONObject.quote(path);
        String quotedParams = JSONObject.quote(paramsJson);

        // The interceptor may answer on a later turn of the event loop, so the
        // page parks its answer under the id it returned and this polls for it.
        String[] fired = evaluate("(function(){return String(window.__mfSign(" + quotedPath + ","
                + quotedParams + "));})()", DEFAULT_TIMEOUT_MS);
        if (fired == null) return null;
        String id = fired[0];

        for (int i = 0; i < POLLS; i++) {
            String[] value = evaluate(
                    "(function(){var v=window.__mfResultFor(" + JSONObject.quote(id) + ");"
                            + "return v===undefined||v===null?null:v;})()",
                    2_000);
            if (value != null && value[0] != null && !value[0].isEmpty()) {
                try {
                    JSONObject body = new JSONObject(value[0]);
                    if (body.optString("vrf", "").isEmpty()) {
                        Log.w(TAG, "unsigned " + path + ": " + body.optString("why", "?")
                                + " " + body.optString("debug", ""));
                    }
                    return new String[]{body.optString("qs", ""), body.optString("vrf", "")};
                } catch (Exception e) {
                    Log.w(TAG, "signer answered something unreadable", e);
                    return null;
                }
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Log.w(TAG, "signer did not answer within " + (POLLS * POLL_MS) + "ms");
        String[] value = evaluate(
                "(function(){return window.__mfDiag===undefined?'no diag':window.__mfDiag;})()",
                2_000);
        Log.w(TAG, "unsigned " + path + " — the page did not answer; it says: "
                + (value == null || value[0] == null ? "nothing" : value[0]));
        return null;
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
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out;
    }

    /** evaluateJavascript hands back a JSON literal: `"abc"`, `null`, or a bare value. */
    private static String unwrap(String jsonValue) {
        if (jsonValue == null) return null;
        try {
            Object value = new org.json.JSONTokener(jsonValue).nextValue();
            if (value == null || value == JSONObject.NULL) return null;
            if (value instanceof String) {
                String text = (String) value;
                return text.isEmpty() ? null : text;
            }
            return String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }
}
