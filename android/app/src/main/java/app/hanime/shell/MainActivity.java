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
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
    private static final int REQUEST_NOTIFICATIONS = 1004;

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

    /** True while the bars are deliberately off the screen (a fullscreen view). */
    private boolean barsHidden = false;

    /**
     * True while the page is reading — the manga reader asks for this.
     *
     * Not the same thing as `barsHidden`: a fullscreen video owns the screen and
     * takes both bars, while a reader wants the status bar gone (that band is a
     * quarter of a page of art) and the navigation bar left alone, because the
     * back gesture is how you leave it.
     */
    private boolean immersive = false;

    /** Serves the bundled client and the ported API — the app's whole backend. */
    private ApiServer api;

    /** Hidden WebView hosting the WASM signature module. */
    private Signer signer;

    // ------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The page owns the whole screen: it draws under the status and
        // navigation bars, and keeps its own controls out of them with the
        // insets the shell hands it (systemBars() below). Laying the window out
        // *beside* the bars instead is what leaves a strip of nothing at the
        // bottom — the window ends above the bar, the strip shows the window
        // background, and on a dark theme that strip is a black bar. Reported
        // from a foldable as "black bars at the bottom, everywhere".
        applyEdgeToEdge();
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

        // A tap on the "update ready" notification arrives here, whether the
        // app was running or the process had to be started for it.
        handleInstallReady(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstallReady(intent);
    }

    /**
     * The notification's tap hands off to the install flow the app already
     * has: the dialog that offers to hand the verified APK to the system
     * installer. The record of what was downloaded lives in [Updater], so
     * this works even when the tap had to start a fresh process.
     */
    private void handleInstallReady(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(Updater.EXTRA_INSTALL_READY, false)) {
            return;
        }
        intent.removeExtra(Updater.EXTRA_INSTALL_READY);   // one prompt per tap
        final UpdateInfo info = Updater.pendingInfo(this);
        if (info == null) return;   // nothing on disk (or the record was stale)
        root.post(() -> promptInstall(info));   // after the first layout
    }

    /**
     * Android 13+ needs the runtime POST_NOTIFICATIONS grant before anything
     * can appear in the tray. Asked for at the only moment it matters — when
     * an update has actually been found and is about to download — rather than
     * on first launch for a feature that may never fire. A refusal costs the
     * notification and nothing else: the dialog still appears on next open.
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
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

    // ------------------------------------------------------------------ settings

    // The old floating ⋮ menu lived here. Its four items — reload, update
    // check, update source, open in browser — belong with the rest of the
    // app's controls, so they moved into the client's Settings view and call
    // the bridge below instead of a native popup over the page.

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
                                    outcome.fellBackFrom, Updater.builtIn(this)));
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
                    requestNotificationPermission();
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
    private AlertDialog installPrompt;

    private void promptInstall(UpdateInfo info) {
        // The notification tap and the download completing can both land here;
        // one dialog at a time.
        if (installPrompt != null && installPrompt.isShowing()) return;
        StringBuilder body = new StringBuilder();
        body.append(getString(R.string.update_available, info.versionName, BuildConfig.VERSION_NAME));
        if (info.size > 0) {
            body.append("\n").append(Math.round(info.size / 1024.0 / 1024.0)).append(" MB");
        }
        body.append("\n\n").append(getString(R.string.update_install_prompt));
        if (pendingFallback != null) {
            body.append("\n\n").append(getString(R.string.update_fell_back,
                    pendingFallback, Updater.builtIn(this)));
        }
        if (info.notes != null && !info.notes.isEmpty()) {
            body.append("\n\n").append(getString(R.string.update_notes, info.notes));
        }

        installPrompt = new AlertDialog.Builder(this, R.style.Theme_Shell_Dialog)
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
    // Back belongs to whatever is on screen, and only the page knows what
    // that is: a detail sheet, a reader chapter, a playlist, the search field
    // that replaced the nav — and now the history stack the page keeps, which
    // back walks one step at a time like a browser's back. The page reports
    // whether there is anything left to walk to (Shell.setBackEnabled) and
    // performs the step when asked (window.__shellBack).
    //
    // Back never leaves the app. There is no path through this method that
    // finishes the activity: at the state the app opened in, the press is
    // simply spent. What used to be here — falling through to finish() when
    // nothing wanted the press — is exactly how a single-page app used to
    // close on the second press from home.
    //
    // Two delivery paths, because Android changed its mind about back: the
    // OnBackInvokedDispatcher (API 33+, once the manifest opts in) and the
    // legacy key event. Both land in handleBack, and the same press arriving
    // twice is collapsed by the debounce below.

    /** True while the page has something for back to walk to. */
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
                        // The dispatcher never falls through to the default
                        // behaviour itself, and handleBack() never returns
                        // false — back dismisses a step or is spent on the
                        // spot, but it does not leave the app.
                        handleBack();
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

    /** @return true always — the press is used or spent, never handed to the OS. */
    private boolean handleBack() {
        long now = SystemClock.uptimeMillis();
        if (lastBackConsumed && now - lastBackAt < 120) return true;
        lastBackAt = now;
        lastBackConsumed = false;

        if (customView != null) {
            // Leave fullscreen video before anything else.
            hideFullscreenView();
            lastBackConsumed = true;
            return true;
        }
        // A page from somewhere else — the MyAnimeList sign-in, the only one
        // the shell still walks to — is its own history, and the app's stack
        // is behind it, not a step of it. Back belongs to the page on screen:
        // otherwise a reader who decides not to sign in would spend presses
        // closing sheets they cannot see.
        if (web != null && web.getUrl() != null && !web.getUrl().startsWith(currentBase())) {
            // Measured on the emulator: after the MyAnimeList sign-in page
            // arrives through its redirect, the WebView reports no history to
            // walk even though the sign-in page's own history has two entries
            // — so the press cannot be spent on canGoBack(). Walking it when
            // there is somewhere to walk, and reloading the app's own page
            // when there is not, leaves the reader in the app either way.
            Log.i(TAG, "back off a foreign page: " + web.getUrl());
            if (web.canGoBack()) web.goBack();
            else loadServer();
            lastBackConsumed = true;
            return true;
        }
        if (backEnabled && web != null) {
            // The page walks its own stack one step — closing its sheet,
            // stepping a chapter back, or returning to the previous view —
            // and answers every press, including the one at the bottom.
            web.evaluateJavascript("window.__shellBack&&window.__shellBack()", null);
            lastBackConsumed = true;
            return true;
        }
        if (web != null && web.canGoBack()) {
            // The page never grows WebView history itself, so this is only a
            // safety net for history restored from a previous process.
            web.goBack();
            lastBackConsumed = true;
            return true;
        }
        // The bottom: the state the app opened in. The press is spent here.
        lastBackConsumed = true;
        return true;
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

    /**
     * Take over the window with the page's own fullscreen view.
     *
     * Named `showFullscreenView`, not `onShowCustomView`, and that is not a
     * style choice. The chrome client below *inherits* `onShowCustomView` from
     * `WebChromeClient`, so an unqualified call from inside that class binds to
     * the inherited method — itself — and recurses until the stack is gone. It
     * did exactly that: `StackOverflowError` in the trace, every fullscreen
     * press, and a window that disappears (reported as "fullscreen closes the
     * app"). A distinct name cannot be shadowed that way; the compiler would
     * reject a call that resolved to nothing.
     */
    private void showFullscreenView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            // A second request while one is showing is a request to leave.
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // The view is Chromium's, and on the built-in video path it can arrive
        // still attached to a parent of its own. Adding it to another one throws
        // `IllegalStateException: The specified child already has a parent` —
        // the app dying at the same moment as the video was asked to go big.
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        root.addView(customView, params);
        web.setVisibility(View.GONE);
        // Fullscreen means the whole screen: the status and navigation bars go
        // with it, or the video is letterboxed inside a band of its own window.
        setSystemBarsHidden(true);
        Log.i(TAG, "fullscreen: showing the page's own video view");
    }

    /** Give the window back to the page. Safe to call when nothing is showing. */
    private void hideFullscreenView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        setSystemBarsHidden(false);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        Log.i(TAG, "fullscreen: back to the page");
    }

    // --------------------------------------------------------- system bars

    /**
     * Draw edge to edge, through any cutout, with the bars themselves
     * transparent.
     *
     * The window covers the whole display and the page decides what to do with
     * the bars' own space: it reads their size through the bridge and insets the
     * chrome that must not sit under them. Two smaller things go with it. The
     * contrast scrim is a wash the system paints behind the bars for apps that
     * draw under them, and over this app's own dark pages it reads as exactly
     * the black strip this removes — hence `set…ContrastEnforced(false)`. And
     * the cutout is used rather than avoided (`layoutInDisplayCutoutMode`), or a
     * phone with a hole in its screen would still reserve a band for it.
     */
    @SuppressWarnings("deprecation")
    private void applyEdgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }
    }

    /**
     * Take the system bars off the screen while a page-owned view has it, and
     * put them back afterwards.
     *
     * This is the other half of edge-to-edge, and the half fullscreen needs: a
     * page that goes fullscreen means the whole screen, and a status bar left
     * standing over it is a band the video is not allowed to use. Swiping still
     * brings the bars back for a moment — a phone whose clock and battery cannot
     * be recalled is worse than one whose video is briefly shorter.
     */
    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hidden) {
        barsHidden = hidden;
        applySystemBars();
    }

    /**
     * Put the bars where the two flags above say they belong.
     *
     * One function rather than three call sites, because they interact: leaving
     * fullscreen while the reader is still open must not bring the status bar
     * back, and closing the reader must not put a video's bars back mid-film.
     */
    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            if (barsHidden) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else if (immersive) {
                controller.show(WindowInsets.Type.navigationBars());
                controller.hide(WindowInsets.Type.statusBars());
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
            // The page insets its own chrome with the bars' size, so it is told
            // again once they have actually animated away: a page told at the
            // moment of the request keeps the inset of the bar that is leaving.
            MainThread.postDelayed(this::tellPageInsets, 320);
            return;
        }
        int laidOut = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        int flags = laidOut;
        if (barsHidden) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        } else if (immersive) {
            // FULLSCREEN alone is the status bar; the navigation bar stays.
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        MainThread.postDelayed(this::tellPageInsets, 320);
    }

    /**
     * A phone's bars come back by themselves, and this puts them away again.
     *
     * Several builds — One UI's in particular — restore the status bar when the
     * window regains focus even though the fullscreen view never left it: pull
     * the shade down and close it, come back from the app switcher, and the
     * video is letterboxed under a band of clock and battery with no way to get
     * rid of it short of leaving fullscreen. Re-applying the current state on
     * every focus change is what keeps a fullscreen video fullscreen; when
     * nothing is hidden it is one no-op call.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setSystemBarsHidden(barsHidden);
    }

    /**
     * A foldable unfolded, a phone rotated, a window resized — all three change
     * where the bars are without recreating the activity (the manifest claims
     * those configuration changes). The page is holding the old numbers, so it
     * is told the new ones.
     */
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        applyEdgeToEdge();
        MainThread.postDelayed(this::tellPageInsets, 120);
    }

    /** The page's own hook for re-reading the bars; see applyShellInsets in shell.js. */
    private void tellPageInsets() {
        if (web == null) return;
        try {
            web.evaluateJavascript("window.__shellInsets&&window.__shellInsets()", null);
        } catch (Exception e) {
            Log.w(TAG, "insets were not handed over", e);
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
            reclaimMalRedirect(url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            // The URL the WebView actually committed — which is not always the
            // one it was asked for, and the difference is the whole reason
            // reclaimMalRedirect exists below.
            Log.i(TAG, "visited " + url);
            reclaimMalRedirect(url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();

            boolean httpish = scheme.equals("http") || scheme.equals("https");
            if (httpish && uri.toString().startsWith(currentBase())) {
                // The MyAnimeList sign-in comes back to this origin with the
                // code in the query. Asked here, it is answered from assets in
                // one step; when the WebView never asks (the next comment), the
                // same reclaim below picks it up after the fact.
                if (isMalRedirect(uri) && reclaim(uri.toString())) return true;
                return false;
            }
            // MyAnimeList signs the user in through its own pages, and the
            // redirect back lands on currentBase — those two are the only
            // external http(s) loads this shell still performs.
            if (httpish && isMalLoginHost(uri.getHost())) {
                return false;
            }
            // The manga source page is the other page the app walks to on
            // purpose, so once it is open its own links walk within it. It is
            // reached by the title page's button through the bridge above rather
            // than by a link of our own, and it stays a page rather than a door:
            // nothing here hands a URL to the browser.
            if (httpish && MangaSource.isSource(uri.toString())) {
                return false;
            }
            // Everything else http(s) is swallowed, never handed to the
            // browser: an invisible ad overlay — or any injected link at all —
            // must not be able to pop the browser open on an ad. In-page
            // external navigation has no legitimate caller (the client renders
            // no external anchors; the ⋮ menu's "Open in browser" is an
            // explicit action with its own intent and still works).
            if (httpish) {
                Log.i(TAG, "suppressed external navigation: " + uri);
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

        /** Hosts the MyAnimeList login may arrive from (their pages live on
         *  myanimelist.net; subdomains count because redirects roam). */
        private boolean isMalLoginHost(String host) {
            return host != null
                    && (host.equals("myanimelist.net") || host.endsWith(".myanimelist.net"));
        }

        /** The redirect URL this shell has already handed back to itself. */
        private String reclaimedMalUrl;

        /** True when a URL is the MyAnimeList sign-in coming home: our own
         *  origin, and still carrying what the client's callback needs. */
        private boolean isMalRedirect(Uri uri) {
            if (uri == null || !uri.toString().startsWith(currentBase())) return false;
            try {
                return uri.getQueryParameter("code") != null
                        || uri.getQueryParameter("error") != null;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Load a MyAnimeList redirect *through the app*, once per URL.
         *
         * The redirect answers a request this shell deliberately passes
         * through — myanimelist.net — and a WebView follows a redirect without
         * asking the app again. So the code was being fetched from the network
         * and landed on the real hanime.tv: unstyled, and useless, because
         * redeeming it is the bundled client's job and it never saw the URL.
         * Loading the same URL from here makes it an app-initiated navigation,
         * which is answered from assets like every other page of this app, so
         * the client boots with the code and links the account.
         *
         * Returns true when the caller should cancel the navigation it holds.
         */
        private boolean reclaim(String url) {
            if (url == null || web == null || url.equals(reclaimedMalUrl)) return false;
            // The WebView asks us for every page it loads itself, and a page we
            // answered is already the bundled client running its own callback.
            // Reclaiming *that* one would exchange the code twice, so the
            // second — network — copy is the only one worth reloading.
            if (api != null && api.servedPage(url)) return false;
            reclaimedMalUrl = url;
            Log.i(TAG, "reclaiming the MyAnimeList redirect through the app");
            web.loadUrl(url);
            return true;
        }

        /** The safety net: run for every committed main-frame URL, in case the
         *  navigation callbacks never saw the redirect at all. */
        private void reclaimMalRedirect(String url) {
            if (url == null || web == null) return;
            if (isMalRedirect(Uri.parse(url))) reclaim(url);
        }
    }

    private final class ShellChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        /**
         * Fullscreen, handed to the activity.
         *
         * `MainActivity.this.` is load-bearing twice over: without it this call
         * binds to the method inherited right here — the one being written — and
         * recurses until the stack overflows. See `showFullscreenView`.
         */
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            MainActivity.this.showFullscreenView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            MainActivity.this.hideFullscreenView();
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

        /**
         * Where the phone's own bars are and whether they are on screen, as
         * `"<top>,<bottom>,<visible>"` in device pixels.
         *
         * The shell's half of edge-to-edge: CSS `env(safe-area-inset-*)` reports
         * only the display cutout, which is nothing at all on a phone whose
         * status bar is an ordinary band, so a page relying on it alone draws
         * its header under the clock. The page sets these as custom properties
         * and insets its fixed chrome with the larger of the two. Zero while the
         * bars are hidden, which is also how a fullscreen view proves it took
         * the screen.
         */
        @JavascriptInterface
        public String systemBars() {
            // A bar taken away for a fullscreen view is not a bar to clear, and
            // the framework's own `isVisible` is not a reliable answer here:
            // on Android 15 it reports `false` for an app drawing edge to edge
            // whose bars are plainly on screen (measured on device — 94px of
            // status bar, `isVisible` false), which zeroed every inset the page
            // asked for. The shell knows when it hid them, so that is what it
            // answers with.
            if (root == null) return "0,0,false";
            WindowInsets insets = root.getRootWindowInsets();
            if (insets == null) return "0,0,false";
            // Device pixels. The page divides by devicePixelRatio: the numbers
            // here are physical and the stylesheet is in CSS pixels, which on
            // this phone is 94px of status bar that is 34px of page.
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets status = insets.getInsets(WindowInsets.Type.statusBars());
                android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                // Asked per bar rather than as one lump, because the two are
                // hidden separately: a reader keeps the navigation bar for the
                // back gesture but takes the status bar's band for the art.
                int top = (barsHidden || immersive) ? 0 : status.top;
                int bottom = barsHidden ? 0 : nav.bottom;
                return top + "," + bottom + "," + !(barsHidden || immersive);
            }
            int top = (barsHidden || immersive) ? 0 : insets.getSystemWindowInsetTop();
            return top + "," + (barsHidden ? 0 : insets.getSystemWindowInsetBottom()) + ",true";
        }

        /**
         * Reading mode: the status bar goes, the navigation bar stays.
         *
         * The manga reader asks for this when a chapter fills the screen and
         * lets it go when the chapter is closed — the page's own header is
         * inset by systemBars(), so it follows without being told twice.
         */
        @JavascriptInterface
        public void setImmersive(final boolean on) {
            MainThread.post(() -> {
                immersive = on;
                applySystemBars();
            });
        }

        @JavascriptInterface
        public void checkForUpdate() {
            MainThread.post(() -> checkForUpdates(true));
        }

        @JavascriptInterface
        public void openServerSettings() {
            MainThread.post(() -> openSettings(false));
        }

        /** Hands the current page to the real browser — the ⋮ menu's last item. */
        @JavascriptInterface
        public void openInBrowser() {
            MainThread.post(() -> {
                String url = web == null ? null : web.getUrl();
                if (url == null) return;
                try {
                    startNow(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                    // Nothing can handle it; staying put is the least surprising.
                }
            });
        }

        /**
         * The one foreign page this app opens of its own accord: a manga title's
         * source on mangafire.to, pressed by the reader on that title's page.
         *
         * The page cannot walk there itself — an in-page foreign navigation is
         * swallowed above, which is what keeps an injected link from opening the
         * browser over a player — so the button asks this instead. The door is
         * narrow, and MangaSource is the rule: https on their domain, or nothing
         * happens at all. It opens in this app's own window, where the back press
         * already knows how to return (see handleBack).
         */
        @JavascriptInterface
        public void openSource(String url) {
            MainThread.post(() -> {
                if (web == null || !MangaSource.isSource(url)) return;
                Log.i(TAG, "opening the manga source: " + url);
                web.loadUrl(url);
            });
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
