package app.hanime.shell;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.nuvio.app.core.auth.LegacyShellAccount;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Carries the retired WebView shell's account into the Kotlin app.
 *
 * The two apps share a package id, so the update that replaces the shell
 * leaves the shell's WebView profile in place — including the localStorage
 * record the web client wrote when the reader signed in. This reads that
 * record once, from the origin it was written under (`https://hanime.tv`, the
 * origin the shell served the bundled client from), and hands its access and
 * refresh tokens to [LegacyShellAccount].
 *
 * The page is served to the WebView by interception, never fetched: a fresh
 * WebView for this origin sees the storage the old one left, and nothing
 * leaves the device. There is no other way to read a WebView's localStorage —
 * it is LevelDB inside the profile, and no Android API opens it.
 *
 * One attempt is made on the first launched activity after the update (see
 * [watch]). A failure is retried on the next launch, twice at most, because
 * the only realistic failure is the network; a profile with no record at all
 * is marked done immediately. The marker lives in this app's own preferences,
 * never the shell's.
 */
final class LegacyShellImport {

    private static final String TAG = "ShellLegacy";

    private static final String PREFS = "sekai_legacy";
    private static final String KEY_STATE = "account_import";
    private static final String KEY_ATTEMPTS = "account_import_attempts";
    private static final String DONE = "done";
    private static final int MAX_ATTEMPTS = 2;

    /** The origin the old shell served its bundled client from. */
    private static final String ORIGIN = "https://hanime.tv";
    private static final String PAGE = ORIGIN + "/__sekai_legacy.html";

    /** account.js's key in that origin's localStorage. */
    private static final String ACCOUNT_KEY = "htv:account:v1";

    private LegacyShellImport() {
    }

    /**
     * Waits for the first activity to be started, then imports once.
     *
     * The delay is not cosmetic: the Supabase client reads the storages the
     * activity initialises, so reading the old session before that would
     * import into a client with nowhere to put it. A provider (this class is
     * reached from [SekaiBoot]) runs before any activity, so it watches the
     * lifecycle instead of guessing at a delay.
     */
    static void watch(Context context) {
        final Application app = (Application) context.getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                app.unregisterActivityLifecycleCallbacks(this);
                start(activity);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }

    /** Runs once, off the launch path; safe to call on every start. */
    static void start(Context context) {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (DONE.equals(prefs.getString(KEY_STATE, ""))) return;
        if (prefs.getInt(KEY_ATTEMPTS, 0) >= MAX_ATTEMPTS) {
            markDone(prefs);
            return;
        }
        // A WebView has to be built on a thread with a Looper, and this is
        // called from a lifecycle callback — post so the frame is not held
        // behind it. The activity context is the one a WebView expects.
        new Handler(Looper.getMainLooper()).post(() -> read(context, prefs));
    }

    private static void read(Context context, SharedPreferences prefs) {
        final WebView web;
        try {
            web = new WebView(context);
        } catch (Throwable t) {
            Log.w(TAG, "no WebView to read the old shell's account from", t);
            markDone(prefs);
            return;
        }
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (PAGE.equals(request.getUrl().toString())) return page();
                // Nothing about this page needs the network; refusing every
                // other request keeps a cold WebView from dialling out.
                return new WebResourceResponse(
                        "text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(
                        "(function(){try{return localStorage.getItem('" + ACCOUNT_KEY + "')}"
                                + "catch(e){return null}})()",
                        value -> {
                            try {
                                importFrom(prefs, value);
                            } finally {
                                // destroy() is a UI-thread call; this callback is
                                // one, but not inside the evaluate itself.
                                view.post(view::destroy);
                            }
                        });
            }
        });
        web.loadUrl(PAGE);
    }

    private static WebResourceResponse page() {
        String html = "<!doctype html><meta charset=\"utf-8\"><title>legacy</title>";
        return new WebResourceResponse(
                "text/html", "utf-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private static void importFrom(SharedPreferences prefs, String evaluated) {
        String[] tokens = parse(evaluated);
        if (tokens == null) {
            Log.i(TAG, "the old shell left no account session");
            markDone(prefs);
            return;
        }
        LegacyShellAccount.importSession(tokens[0], tokens[1], outcome -> {
            if (LegacyShellAccount.FAILED.equals(outcome)) {
                int attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1;
                prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply();
                Log.w(TAG, "could not import the old shell's session (attempt " + attempts + ")");
                if (attempts >= MAX_ATTEMPTS) markDone(prefs);
            } else {
                Log.i(TAG, "old shell session: " + outcome);
                markDone(prefs);
            }
        });
    }

    /**
     * `evaluateJavascript` answers a JSON value: a quoted string holding what
     * the script returned, or the literal `null`. The string is the session
     * record; a record with no refresh token cannot be renewed and is not a
     * session worth importing.
     *
     * Package-private for the JVM test — the WebView part is the only piece
     * that needs a device.
     */
    static String[] parse(String evaluated) {
        if (evaluated == null) return null;
        try {
            Object literal = new JSONTokener(evaluated).nextValue();
            if (!(literal instanceof String)) return null;
            JSONObject session = new JSONObject((String) literal);
            String access = session.optString("access", "").trim();
            String refresh = session.optString("refresh", "").trim();
            if (access.isEmpty() || refresh.isEmpty()) return null;
            return new String[]{access, refresh};
        } catch (Throwable t) {
            return null;
        }
    }

    private static void markDone(SharedPreferences prefs) {
        prefs.edit().putString(KEY_STATE, DONE).apply();
    }
}
