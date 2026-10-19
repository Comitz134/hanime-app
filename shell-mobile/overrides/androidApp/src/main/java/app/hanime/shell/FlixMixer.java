package app.hanime.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
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
 * The flixcloud mixer, run in the one place on Android that has a wasm runtime:
 * a WebView.
 *
 * There is no WebAssembly in the JVM, and the desktop twin's whole chain — the
 * module that mixes three key fragments, then PBKDF2, XOR, SHA-256 and
 * AES-CBC — leans on one. So the arithmetic is done by the page in
 * `assets/mixer.html`, and this class is only the bridge: load the page, hand
 * it the payload, wait for the answer.
 *
 * The page is served to the WebView under the provider's own origin, exactly
 * as the hanime signer is (see {@link Signer}): the bytes come out of the APK
 * through {@link #local}, nothing leaves the device, and the origin buys a
 * secure context — `crypto.subtle` only exists in one, and without it there is
 * no PBKDF2 to run. Unlike the hanime module, nothing on this page reads the
 * location, so the origin is a convenience rather than a requirement.
 */
@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
final class FlixMixer {

    private static final String TAG = "ShellFlix";

    /** The origin whose page this is — the provider's, with bytes from the APK. */
    private static final String MIXER_ORIGIN = "https://flixcloud.cc";
    private static final String MIXER_PAGE = "mixer.html";

    private static final long READY_TIMEOUT_MS = 6_000;
    private static final long OPEN_TIMEOUT_MS = 20_000;

    /** What the page answers: the master playlist and its key. */
    static final class Mix {
        final String url;
        final String key;

        Mix(String url, String key) {
            this.url = url;
            this.key = key;
        }
    }

    /** The page calls back here when the mix is done. */
    private static final class Bridge {
        private volatile String answer;
        private volatile CountDownLatch latch = new CountDownLatch(1);

        void reset() {
            answer = null;
            latch = new CountDownLatch(1);
        }

        @JavascriptInterface
        public void done(String value) {
            answer = value;
            latch.countDown();
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Bridge bridge = new Bridge();
    private volatile WebView web;

    private FlixMixer(Context context) {
        this.context = context.getApplicationContext();
    }

    private static volatile FlixMixer instance;

    /** The process's one mixer, started on the UI thread at app launch. */
    static FlixMixer ensure(Context context) {
        FlixMixer local = instance;
        if (local != null) return local;
        synchronized (FlixMixer.class) {
            if (instance == null) {
                FlixMixer created = new FlixMixer(context.getApplicationContext());
                MainThread.post(created::start);
                instance = created;
            }
            return instance;
        }
    }

    /** Must be called on the UI thread. */
    private void start() {
        if (web != null) return;

        WebView created = new WebView(context);
        created.getSettings().setJavaScriptEnabled(true);
        created.setLayoutParams(new android.widget.FrameLayout.LayoutParams(1, 1));
        created.addJavascriptInterface(bridge, "SekaiFlix");
        created.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return local(request.getUrl() == null ? null : request.getUrl().getPath());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "mixer page loaded: " + url);
            }
        });
        created.loadUrl(MIXER_ORIGIN + "/" + MIXER_PAGE);
        web = created;
        Log.i(TAG, "mixer booting");
    }

    /**
     * Answer the one file this origin is allowed to ask for, out of the APK.
     * Anything else asked of the provider's origin is refused rather than sent
     * to the network: this WebView is a calculator, not a browser on the site.
     */
    private WebResourceResponse local(String path) {
        if (path == null || !path.equals("/" + MIXER_PAGE)) return notFound();
        try (InputStream in = context.getAssets().open(MIXER_PAGE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new WebResourceResponse("text/html", "utf-8", 200, "OK",
                    new HashMap<>(), new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            Log.w(TAG, "could not serve " + MIXER_PAGE, e);
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    /** True once the page exists at all — it is built off this thread. */
    private boolean awaitBooting(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (web == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return web != null;
    }

    /** True once the page's own function exists, or the wait runs out. */
    boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String[] r = evaluate("(function(){return typeof window.__flixOpen==='function';})()", 1_500);
            if (r != null && "true".equals(r[0])) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * The payload the provider's page carries, in; the master playlist URL and
     * its base64url key out. Safe to call from any thread: the evaluation is
     * posted, the wait is on a latch, and one mix runs at a time.
     */
    Mix open(String wasmB64, String frag1B64, String frag2B64, String frag3B64,
             long seedInt, String seed, String ivB64, String cipherB64) {
        // The page is created on the UI thread by the first ask, so the first
        // ask is also the one that finds no WebView yet. Waiting for it here is
        // the difference between the first anime episode of a session resolving
        // and the reader being told there is no stream.
        if (!awaitBooting(READY_TIMEOUT_MS)) {
            Log.w(TAG, "mixer did not boot — no resolution this time");
            return null;
        }
        if (!awaitReady(READY_TIMEOUT_MS)) {
            Log.w(TAG, "mixer not ready — no resolution this time");
            return null;
        }

        String payload;
        try {
            payload = new JSONObject()
                    .put("wasm", wasmB64)
                    .put("frag1", frag1B64)
                    .put("frag2", frag2B64)
                    .put("frag3", frag3B64)
                    .put("seedInt", seedInt)
                    // The seed in full: the page uses it as PBKDF2's salt and
                    // folds its characters over the derived bits, so the
                    // integer alone is not enough to open the payload.
                    .put("seed", seed)
                    .put("iv", ivB64)
                    .put("ciphertext", cipherB64)
                    .toString();
        } catch (Exception e) {
            return null;
        }

        synchronized (bridge) {
            bridge.reset();
            String[] started = evaluate(
                    "(function(){try{window.__flixOpen(" + payload + ");return 'ok';}catch(e){return 'no';}})()",
                    5_000);
            if (started == null || !"ok".equals(started[0])) {
                Log.w(TAG, "mixer refused the payload");
                return null;
            }
            try {
                if (!bridge.latch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "mixer timed out after " + OPEN_TIMEOUT_MS + "ms");
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            String answer = bridge.answer;
            if (answer == null || answer.startsWith("!")) {
                Log.w(TAG, "mixer answered " + answer);
                return null;
            }
            int bar = answer.lastIndexOf('|');
            if (bar <= 0) {
                Log.w(TAG, "mixer answer was not a pair: " + answer);
                return null;
            }
            return new Mix(answer.substring(0, bar), answer.substring(bar + 1));
        }
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

    /** evaluateJavascript hands back JSON: `"ok"` or `null`. */
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
}
