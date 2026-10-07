package app.hanime.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Rational;
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
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
    private static final int REQUEST_EXPORT = 1002;
    private static final int REQUEST_IMPORT = 1003;

    /** How long a session may run before it is worth asking about updates again. */
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000L;

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

        // Predictive back (API 33+) is routed to a dispatcher instead of the key
        // event once the manifest opts in, so the same handler is registered on
        // both paths — see handleBack().
        registerBackCallback();

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

        // The check used to happen on a cold start only, so a phone whose app was
        // never closed could sit on an old build indefinitely. Silent, and
        // throttled: opening the app ten times in an hour is not ten update
        // requests.
        if (System.currentTimeMillis() - UpdateLog.lastAt(this) > CHECK_INTERVAL_MS) {
            checkForUpdates(false);
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
                    startNow(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())));
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
        // The update source — not currentBase(), which is the app's own bundled
        // origin and has nothing to do with updates.
        intent.putExtra(SettingsActivity.EXTRA_CURRENT, Updater.sourceLabel(this));
        startFor(intent, REQUEST_SETTINGS);
    }

    /**
     * Starts something else, and remembers that we did: back-to-PiP must not
     * fire when the activity leaves because the user opened the installer or a
     * settings screen from inside the app.
     */
    private void startFor(Intent intent, int requestCode) {
        startedIntentAt = SystemClock.uptimeMillis();
        startActivityForResult(intent, requestCode);
    }

    private void startNow(Intent intent) {
        startedIntentAt = SystemClock.uptimeMillis();
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_SETTINGS:
                if (resultCode == RESULT_OK) {
                    loadServer();
                    // The settings screen can find an update on its own. The
                    // download and the install consent belong here, so the app
                    // takes over from that check rather than sending the user
                    // hunting through the menu for it.
                    if (data != null
                            && data.getBooleanExtra(SettingsActivity.EXTRA_FOUND_UPDATE, false)) {
                        checkForUpdates(true);
                    }
                }
                return;
            case REQUEST_EXPORT:
                if (resultCode == RESULT_OK && data != null && data.getData() != null
                        && pendingExport != null) {
                    writeExport(data.getData(), pendingExport);
                }
                pendingExport = null;
                return;
            case REQUEST_IMPORT:
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    readImport(data.getData());
                }
                return;
            default:
        }
    }

    // ------------------------------------------------------------ library files
    //
    // Favourites and history are the only thing this app owns, and they exist in
    // exactly one place: localStorage inside the WebView. An export is a JSON
    // file the user picks a home for; an import merges one back. Both go through
    // the system document picker, so the app needs no storage permission and the
    // file never passes through a server.

    private String pendingExport = null;

    private void startExport(String json) {
        if (json == null || json.isEmpty()) return;
        pendingExport = json;
        String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "hanime-library-" + stamp + ".json");
        try {
            startFor(intent, REQUEST_EXPORT);
        } catch (Exception e) {
            pendingExport = null;
            Log.w(TAG, "no document picker to export through", e);
            toast(getString(R.string.library_export_failed));
        }
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Some providers label a .json as octet-stream, so the type is broad and
        // the file's contents are what get validated.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "application/octet-stream"});
        try {
            startFor(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            Log.w(TAG, "no document picker to import through", e);
            toast(getString(R.string.library_import_failed));
        }
    }

    /** Writes the export off the UI thread: a library is small, but not free. */
    private void writeExport(final Uri target, final String json) {
        new Thread(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                if (out != null) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    ok = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "export failed", e);
            }
            final boolean written = ok;
            MainThread.post(() -> toast(getString(
                    written ? R.string.library_export_done : R.string.library_export_failed)));
        }).start();
    }

    /** Reads an import and hands the text to the page, which owns the format. */
    private void readImport(final Uri source) {
        new Thread(() -> {
            String text = null;
            try (InputStream in = getContentResolver().openInputStream(source)) {
                if (in != null) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
                    text = buffer.toString("UTF-8");
                }
            } catch (Exception e) {
                Log.w(TAG, "import failed", e);
            }
            final String body = text;
            MainThread.post(() -> {
                if (body == null || web == null) {
                    toast(getString(R.string.library_import_failed));
                    return;
                }
                // Quoted, not concatenated: the file is user-supplied data and
                // must reach the page as a string, never as code.
                web.evaluateJavascript(
                        "window.__shellLibraryImport&&window.__shellLibraryImport("
                                + JSONObject.quote(body) + ")", null);
            });
        }).start();
    }

    // ------------------------------------------------------------- updates

    /**
     * @param interactive true when the user asked, which is the only case where
     *                    "you are up to date" and errors are worth showing.
     */
    /** True between the start of a check and its outcome. */
    private boolean checking = false;

    private void checkForUpdates(boolean interactive) {
        // One check at a time. A cold start asks on create and the throttled
        // check in onResume can ask again a moment later, because the record it
        // reads is from the previous session — which is two identical requests
        // for one answer.
        if (checking) {
            Log.i(TAG, "a check is already running");
            if (interactive) toast(getString(R.string.update_checking));
            return;
        }
        checking = true;
        Log.i(TAG, "checking for updates, installed versionCode " + BuildConfig.VERSION_CODE);
        if (interactive) {
            toast(getString(R.string.update_checking));
        }
        Updater.check(this, outcome -> {
            checking = false;
            Log.i(TAG, "update check: " + outcome.kind + " " + outcome.message);
            switch (outcome.kind) {
                case UP_TO_DATE:
                    if (interactive) {
                        // The check succeeded. It says so and stops there: a
                        // dialog — the shape this app uses for "something went
                        // wrong" — is what made a working check read as broken.
                        // A repaired source is worth a sentence, not a screen.
                        if (outcome.fellBackFrom != null) {
                            toastLong(getString(R.string.update_none, outcome.message)
                                    + " " + getString(R.string.update_fell_back,
                                    outcome.fellBackFrom, ServerConfig.builtIn()));
                        } else {
                            toast(getString(R.string.update_none, outcome.message));
                        }
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
                    pendingFallback = outcome.fellBackFrom;
                    downloadUpdate(outcome.info);
                    break;
                case FAILED:
                    if (interactive) {
                        // A bare "failed to connect to /10.0.2.2" leaves the user
                        // nowhere: name every source that was tried.
                        StringBuilder why = new StringBuilder(
                                getString(R.string.update_failed, outcome.message));
                        String source = outcome.fellBackFrom != null
                                ? outcome.fellBackFrom
                                : Updater.sourceLabel(this);
                        why.append("\n\n").append(getString(R.string.update_source_used, source));
                        if (outcome.alsoFailed != null) {
                            why.append("\n\n").append(
                                    getString(R.string.update_builtin_failed, outcome.alsoFailed));
                        }
                        dialog(why.toString());
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
        if (pendingFallback != null) {
            body.append("\n\n").append(getString(R.string.update_fell_back,
                    pendingFallback, ServerConfig.builtIn()));
        }
        if (info.notes != null && !info.notes.isEmpty()) {
            body.append("\n\n").append(getString(R.string.update_notes, info.notes));
        }

        new AlertDialog.Builder(this, R.style.Theme_Shell_Dialog)
                .setTitle(R.string.menu_update)
                .setMessage(body.toString())
                .setPositiveButton(R.string.update_download, (d, w) -> {
                    // The installer takes the screen from here. That is not the
                    // user pressing Home, so it must not shrink to the small
                    // window on the way out.
                    startedIntentAt = SystemClock.uptimeMillis();
                    Updater.installDownloaded(this, info,
                            outcome -> onUpdateOutcome(outcome, info));
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private void onStage(Updater.Stage stage) {
        toast(getString(stage == Updater.Stage.DOWNLOADING
                ? R.string.update_downloading
                : R.string.update_verifying));
    }

    /** Set when the update on screen came from the fallback source. */
    private String pendingFallback = null;

    private void dialog(String message) {
        new AlertDialog.Builder(this, R.style.Theme_Shell_Dialog)
                .setTitle(R.string.menu_update)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
                dialog(message);
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** For messages that are a sentence rather than a state. */
    private void toastLong(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // --------------------------------------------------------------- back
    //
    // Back has to dismiss what is on screen before it leaves the app, and only
    // the page knows what that is: a detail sheet, a playlist, or the search
    // field that replaced the nav. So the page reports whether it has something
    // to dismiss (Shell.setBackEnabled) and answers the dismissal when asked
    // (window.__shellBack). Without it the app exited out from under an open
    // sheet, because a single-page client never grows a WebView history to walk
    // back through.
    //
    // Two delivery paths, because Android changed its mind about back: the
    // OnBackInvokedDispatcher (API 33+, once the manifest opts in) and the
    // legacy key event. Both land in handleBack, and the same press arriving
    // twice is collapsed by the debounce below.

    /** True while the page has something for back to dismiss. */
    private volatile boolean backEnabled = false;

    private long lastBackAt = 0L;
    private boolean lastBackConsumed = false;

    /** Registered on API 33+, where the dispatcher takes precedence over the key. */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (!handleBack()) {
                            // Nothing was dismissed, so this is the app's own
                            // back. The dispatcher never falls through to the
                            // default behaviour itself.
                            finish();
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "predictive back is unavailable", e);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        return handleBack();
    }

    /** @return true when something on screen used the press. */
    private boolean handleBack() {
        long now = SystemClock.uptimeMillis();
        if (lastBackConsumed && now - lastBackAt < 120) return true;
        lastBackAt = now;
        lastBackConsumed = false;

        if (customView != null) {
            // Leave fullscreen video before anything else.
            onHideCustomView();
            lastBackConsumed = true;
            return true;
        }
        if (backEnabled && web != null) {
            // The page closes its own sheet or search field. Either way it used
            // the press, so the app does not also leave.
            web.evaluateJavascript("window.__shellBack&&window.__shellBack()", null);
            lastBackConsumed = true;
            return true;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            lastBackConsumed = true;
            return true;
        }
        return false;
    }

    // ------------------------------------------------- picture in picture

    /** True while the page reports a playing video: drives PiP and screen-on. */
    private volatile boolean playing = false;

    private int pipWidth = 16;
    private int pipHeight = 9;

    /** When this app last opened something else, so Home still means Home. */
    private long startedIntentAt = 0L;

    private boolean supportsPictureInPicture() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private void enterPipNow() {
        if (web == null || isInPictureInPictureMode()) return;
        if (!supportsPictureInPicture()) {
            toast(getString(R.string.pip_unsupported));
            return;
        }
        try {
            // The page reports the video's real aspect ratio; a PiP window sized
            // for the wrong one is the difference between a video and a
            // letterboxed thumbnail.
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(pipWidth, pipHeight))
                    .build());
        } catch (Exception e) {
            Log.w(TAG, "picture in picture was refused", e);
            toast(getString(R.string.pip_unsupported));
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        // The page hides everything except the video while this is true: left
        // alone, the whole document is squeezed into the small window and the
        // video becomes a stamp in the corner of it.
        if (web != null) {
            web.evaluateJavascript("window.__shellPip&&window.__shellPip(" + inPip + ")", null);
        }
        if (inPip) progress.setVisibility(View.GONE);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Home, or a swipe to the launcher, while a video is playing: shrinking
        // to the small window is what every video app does here, and leaving
        // used to stop playback dead.
        if (!playing) return;
        if (SystemClock.uptimeMillis() - startedIntentAt < 1500) return;
        enterPipNow();
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
         * hands a request back to the WebView, which is how the web font and the
         * image CDN still load normally.
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
                startNow(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
            // tel:, mailto:, intent: and the rest.
            try {
                startNow(new Intent(Intent.ACTION_VIEW, uri));
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

        /** True while the page has a sheet or the search field open. */
        @JavascriptInterface
        public void setBackEnabled(boolean value) {
            backEnabled = value;
        }

        @JavascriptInterface
        public void setPlaying(boolean value) {
            playing = value;
            MainThread.post(() -> {
                // A video being watched should not be interrupted by the screen
                // dimming, and the page is the only thing that knows it is on.
                if (web != null) web.setKeepScreenOn(value);
            });
        }

        @JavascriptInterface
        public boolean pictureInPictureSupported() {
            return supportsPictureInPicture();
        }

        @JavascriptInterface
        public void enterPip() {
            MainThread.post(MainActivity.this::enterPipNow);
        }

        /** The playing video's shape, so the PiP window is not letterboxed. */
        @JavascriptInterface
        public void setVideoAspect(int width, int height) {
            if (width > 0 && height > 0) {
                pipWidth = width;
                pipHeight = height;
            }
        }

        @JavascriptInterface
        public void exportLibrary(String json) {
            MainThread.post(() -> startExport(json));
        }

        @JavascriptInterface
        public void importLibrary() {
            MainThread.post(MainActivity.this::startImport);
        }

        /** The stored cover for a title, or "" when there is none yet. */
        @JavascriptInterface
        public String cachedCover(String slug) {
            return api == null ? "" : api.covers().path(slug);
        }

        /** Asks for a cover to be kept for offline library rendering. */
        @JavascriptInterface
        public void cacheCover(String slug, String url) {
            if (api != null) api.covers().fetch(slug, url);
        }
    }
}
