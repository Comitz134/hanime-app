package app.hanime.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

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
 * Signing happens immediately before the request it is for, because the
 * signature is keyed to the current second.
 */
@SuppressLint("SetJavaScriptEnabled")
final class Signer {

    private static final String TAG = "ShellSigner";
    private static final long DEFAULT_TIMEOUT_MS = 5_000;

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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private WebView web;
    private volatile boolean booted = false;

    Signer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Must be called on the UI thread. */
    @SuppressLint("AddJavascriptInterface")
    void start(ApiServer api) {
        if (web != null) return;

        web = new WebView(context);
        web.getSettings().setJavaScriptEnabled(true);
        // Sits off to the side at 1x1; it exists only to run the module.
        web.setLayoutParams(new FrameLayout.LayoutParams(1, 1));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    return api.handle(request);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "signer page loaded: " + url);
            }
        });
        web.loadUrl(ApiServer.ORIGIN + "/signer.html");
        Log.i(TAG, "signer booting");
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
