package app.hanime.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The shell: a WebView pointed at the user's proxy, plus the two things a page
 * cannot do for itself — survive as an installed app, and update itself.
 *
 * Everything the app does with content is the server's web client. Nothing is
 * reimplemented here on purpose: the page is the product, and it is the same
 * code that was verified working in a browser.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Shell";
    private static final int REQUEST_SETTINGS = 1001;

    private WebView web;
    private ProgressBar progress;
    private View errorView;
    private TextView errorBody;
    private FrameLayout root;

    /** Set while a custom (fullscreen) video view is attached by the page. */
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private boolean sawFirstError = false;

    /** Serves the bundled client and the ported API — the app's whole backend. */
    private ApiServer api;

    /** Hidden WebView hosting the WASM signature module. */
    private Signer signer;

    // ------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Expose the page over `adb forward tcp:9222` so the running app is
        // diagnosable — the same reasoning as mirroring the console into logcat
        // below. The endpoint binds to loopback on the device and is only
        // reachable through adb, so this is a debugging convenience rather than
        // an open port; the shell holds no account credential.
        WebView.setWebContentsDebuggingEnabled(true);

        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.error);
        errorBody = findViewById(R.id.error_body);
        root = findViewById(R.id.root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // The catalogue stores its own state locally; without this the client
        // re-fetches the whole thing on every navigation.
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " hanime-shell/" + BuildConfig.VERSION_NAME);

        // Dark background behind the page, so a slow load is not a white flash.
        web.setBackgroundColor(0xFF111111);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebViewClient(new ShellWebViewClient());
        web.setWebChromeClient(new ShellChromeClient());

        api = new ApiServer(this);

        // The signature module runs in its own hidden WebView; stream
        // resolution asks it for a pair immediately before each handshake.
        signer = new Signer(this);
        signer.start(api);
        Streams.setSigner(signer);

        // The page can read its own shell version and ask for an update, which
        // is what lets the web client show an "update available" affordance.
        web.addJavascriptInterface(new ShellBridge(), "Shell");

        findViewById(R.id.error_action).setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            loadServer();
        });

        ImageButton overflow = findViewById(R.id.overflow);
        overflow.setOnClickListener(this::showMenu);

        // 1x1 and off-screen: it exists only to run the signer's JS.
        root.addView(signerView(), new FrameLayout.LayoutParams(1, 1));

        // The client is served from inside the APK, so re-loading it costs
        // nothing — while restoring WebView state after the activity is
        // recreated (process death, configuration change) can leave a blank
        // page, because there is no real session to restore. Always load.
        loadServer();

        // An update check on every cold start is the point of the feature: the
        // app notices a new build without the user thinking about it.
        checkForUpdates(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from Settings with a different address must actually load it.
        if (!loadedOnce) {
            loadedOnce = true;
        } else if (!web.getUrl().startsWith(currentBase())) {
            loadServer();
        }
    }

    private boolean loadedOnce = false;

    private View signerView() {
        return signer != null ? signer.view() : new View(this);
    }

    @Override
    protected void onDestroy() {
        if (signer != null) {
            signer.stop();
            signer = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences("shell", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ load

    /**
     * The app's own origin. The client is bundled, not fetched, so this is
     * served by ApiServer from assets — there is no server to reach.
     */
    private String currentBase() {
        return ApiServer.ORIGIN;
    }

    private void loadServer() {
        sawFirstError = false;
        String url = currentBase() + "/";
        Log.i(TAG, "loading bundled client at " + url);
        web.loadUrl(url);
    }

    private void showError(String message) {
        errorBody.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.menu_reload);
        menu.getMenu().add(0, 2, 1, R.string.menu_update);
        menu.getMenu().add(0, 3, 2, R.string.menu_server);
        menu.getMenu().add(0, 4, 3, R.string.menu_browser);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    web.reload();
                    return true;
                case 2:
                    checkForUpdates(true);
                    return true;
                case 3:
                    openSettings(false);
                    return true;
                case 4:
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())));
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void openSettings(boolean firstRun) {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_FIRST_RUN, firstRun);
        intent.putExtra(SettingsActivity.EXTRA_CURRENT, currentBase());
        startActivityForResult(intent, REQUEST_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            loadServer();
        }
    }

    // ------------------------------------------------------------- updates

    /**
     * @param interactive true when the user asked, which is the only case where
     *                    "you are up to date" and errors are worth showing.
     */
    private void checkForUpdates(boolean interactive) {
        Log.i(TAG, "checking for updates, installed versionCode " + BuildConfig.VERSION_CODE);
        if (interactive) {
            toast(getString(R.string.update_checking));
        }
        Updater.check(this, outcome -> {
            Log.i(TAG, "update check: " + outcome.kind + " " + outcome.message);
            switch (outcome.kind) {
                case UP_TO_DATE:
                    if (interactive) {
                        toast(getString(R.string.update_none, outcome.message));
                    }
                    break;
                case NOT_CONFIGURED:
                    if (interactive) {
                        toast(getString(R.string.update_no_endpoint));
                    }
                    break;
                case AVAILABLE:
                    // Download on detection rather than on a tap. Installing still
                    // requires the system consent screen, so nothing happens to
                    // the device without the user agreeing to it — but the
                    // waiting is done before they are asked.
                    downloadUpdate(outcome.info);
                    break;
                case FAILED:
                    if (interactive) {
                        toast(getString(R.string.update_failed, outcome.message));
                    }
                    break;
            }
        });
    }

    private boolean downloading = false;

    private void downloadUpdate(UpdateInfo info) {
        if (downloading) return;
        downloading = true;
        Log.i(TAG, "downloading update " + info.versionName
                + " (versionCode " + info.versionCode + ", " + info.size + " bytes)");
        toast(getString(R.string.update_downloading));

        Updater.download(this, info, this::onStage, outcome -> {
            downloading = false;
            onUpdateOutcome(outcome, info);
        });
    }

    /** Shown once the APK is on disk and verified — the last step needs a tap. */
    private void promptInstall(UpdateInfo info) {
        StringBuilder body = new StringBuilder();
        body.append(getString(R.string.update_available, info.versionName, BuildConfig.VERSION_NAME));
        if (info.size > 0) {
            body.append("\n").append(Math.round(info.size / 1024.0 / 1024.0)).append(" MB");
        }
        body.append("\n\n").append(getString(R.string.update_install_prompt));
        if (info.notes != null && !info.notes.isEmpty()) {
            body.append("\n\n").append(getString(R.string.update_notes, info.notes));
        }

        new AlertDialog.Builder(this, R.style.Theme_Shell_Dialog)
                .setTitle(R.string.menu_update)
                .setMessage(body.toString())
                .setPositiveButton(R.string.update_download,
                        (d, w) -> Updater.installDownloaded(this, info,
                                outcome -> onUpdateOutcome(outcome, info)))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private void onStage(Updater.Stage stage) {
        toast(getString(stage == Updater.Stage.DOWNLOADING
                ? R.string.update_downloading
                : R.string.update_verifying));
    }

    private void onUpdateOutcome(Updater.Outcome outcome, UpdateInfo info) {
        Log.i(TAG, "update outcome: " + outcome.kind + " " + outcome.message);

        switch (outcome.kind) {
            case DOWNLOADED:
                // Verified and on disk. Ask before handing it to the installer.
                promptInstall(info);
                break;
            case INSTALLED:
                // The system installer is on screen; anything drawn here would
                // sit behind it.
                break;
            case FAILED:
            default: {
                String message = "checksum_mismatch".equals(outcome.message)
                        ? getString(R.string.update_checksum_mismatch)
                        : getString(R.string.update_failed, outcome.message);
                new AlertDialog.Builder(this, R.style.Theme_Shell_Dialog)
                        .setTitle(R.string.menu_update)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // --------------------------------------------------------------- back

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                // Leave fullscreen video before anything else.
                onHideCustomView();
                return true;
            }
            if (web.canGoBack()) {
                web.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------ fullscreen video

    private void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(customView, params);
        web.setVisibility(View.GONE);
    }

    private void onHideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    // ------------------------------------------------------------ web client

    private final class ShellWebViewClient extends WebViewClient {

        /**
         * Everything the page asks for is answered in-process. Returning null
         * hands a request back to the WebView, which is how fonts, the hls.js
         * CDN and the image CDN still load normally.
         */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                return api == null ? null : api.handle(request);
            } catch (Exception e) {
                Log.w(TAG, "intercept failed", e);
                return null;
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();

            // Anything outside the shell's own pages goes to the real browser:
            // an in-app WebView with no address bar is the wrong place to end up
            // on an external site.
            boolean httpish = scheme.equals("http") || scheme.equals("https");
            if (httpish && uri.toString().startsWith(currentBase())) {
                return false;
            }
            if (httpish) {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
            // tel:, mailto:, intent: and the rest.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                // Nothing can handle it; staying put is the least surprising.
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // Sub-resource failures are normal (a CDN image, a blocked ad) and
            // must not replace the page with an error screen. Only the main
            // document failing means the server is unreachable.
            if (!request.isForMainFrame()) return;
            if (sawFirstError) return;
            sawFirstError = true;

            CharSequence description = error.getDescription();
            Log.w(TAG, "main frame failed: " + description);
            showError(getString(R.string.webview_unreachable, currentBase()));
        }
    }

    private final class ShellChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            onShowCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            MainActivity.this.onHideCustomView();
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            // Mirror the page's console into logcat so a broken client is
            // diagnosable from a phone without a desktop inspector.
            Log.i("ShellPage", message.message() + " @" + message.lineNumber());
            return false;
        }
    }

    /**
     * The only bridge the page gets. Every method is read-only or opens a system
     * UI; nothing here executes page-supplied code or touches the filesystem.
     */
    private final class ShellBridge {

        @JavascriptInterface
        public String versionName() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public int versionCode() {
            return BuildConfig.VERSION_CODE;
        }

        @JavascriptInterface
        public String serverUrl() {
            return currentBase();
        }

        @JavascriptInterface
        public void checkForUpdate() {
            MainThread.post(() -> checkForUpdates(true));
        }

        @JavascriptInterface
        public void openServerSettings() {
            MainThread.post(() -> openSettings(false));
        }
    }
}
