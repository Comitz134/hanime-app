package app.hanime.shell;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-update, in three steps: ask the server what it publishes, download the
 * APK if it is newer, verify it, then hand it to Android's installer.
 *
 * Two things are deliberately not done here:
 *
 *   - Nothing installs silently. Android requires user consent for the install
 *     and for the "install unknown apps" grant, and this code goes through the
 *     normal system installer so those prompts appear as intended.
 *   - Nothing is trusted that fails its checksum. The server publishes the
 *     sha256 of the APK it is serving; a mismatch aborts the install.
 */
final class Updater {

    private static final String TAG = "ShellUpdater";

    // ---------------------------------------------------------------- channels

    static final String CHANNEL_STABLE = "stable";
    static final String CHANNEL_BETA = "beta";

    /** Which channel a device is on. Stored beside the other update state. */
    static String channel(Context app) {
        String value = app.getSharedPreferences("shell", Context.MODE_PRIVATE)
                .getString("update_channel", CHANNEL_STABLE);
        return CHANNEL_BETA.equals(value) ? CHANNEL_BETA : CHANNEL_STABLE;
    }

    static void setChannel(Context app, String channel) {
        String value = CHANNEL_BETA.equals(channel) ? CHANNEL_BETA : CHANNEL_STABLE;
        app.getSharedPreferences("shell", Context.MODE_PRIVATE)
                .edit()
                .putString("update_channel", value)
                .apply();
        // An override that merely repeats a built-in URL is not an override —
        // it is what prefill-and-save leaves behind. Dropping it here is what
        // makes switching the channel actually switch what gets consulted; a
        // genuinely custom address is kept and keeps winning, as §6 says.
        String saved = ServerConfig.stored(app);
        if (saved.equals(BuildConfig.UPDATE_URL) || saved.equals(betaUrl())) {
            ServerConfig.clear(app);
        }
    }

    /** The built-in source for a channel. Pure — unit-tested. */
    static String builtInFor(String channel, String stableUrl, String betaUrl) {
        return CHANNEL_BETA.equals(channel) ? betaUrl : stableUrl;
    }

    /**
     * The source a check consults: a saved override wins, else the channel's
     * built-in URL. Pure — unit-tested.
     */
    static String selectSource(String savedOverride, String channel,
                               String stableUrl, String betaUrl) {
        String saved = savedOverride == null ? "" : savedOverride.trim();
        if (!saved.isEmpty()) return saved;
        return builtInFor(channel, stableUrl, betaUrl);
    }

    private static String betaUrl() {
        // Compiled in by build.gradle (a rolling asset URL that always exists
        // because publish-github.mjs refreshes it on every publish).
        return BuildConfig.BETA_URL.trim();
    }

    /** The built-in source this device ships with — the selected channel's. */
    static String builtIn(Context app) {
        return builtInFor(channel(app), BuildConfig.UPDATE_URL.trim(), betaUrl());
    }

    /** The address a check will actually use — override or channel built-in. */
    static String address(Context app) {
        return selectSource(ServerConfig.stored(app), channel(app),
                BuildConfig.UPDATE_URL, betaUrl());
    }

    // ---------------------------------------------------------------- install-ready notification

    /** Set on the intent a downloaded-update notification carries. */
    static final String EXTRA_INSTALL_READY = "app.hanime.shell.INSTALL_READY";
    private static final String NOTIFY_CHANNEL_ID = "updates";
    private static final String KEY_PENDING = "pending_update";

    /**
     * Tell the user the APK is on disk, so they don't have to open the app to
     * find out. Posted only for DOWNLOADED — a check that came back
     * UP_TO_DATE has nothing to say and says nothing. Skipped (not failed)
     * when notifications are not permitted: the in-app prompt still appears
     * on the next open.
     */
    private static void notifyDownloaded(Context app, UpdateInfo info) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        NOTIFY_CHANNEL_ID,
                        app.getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_HIGH));
            }
            Intent tap = new Intent(app, MainActivity.class)
                    .setAction(EXTRA_INSTALL_READY)
                    .putExtra(EXTRA_INSTALL_READY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, 1, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(app, NOTIFY_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(app.getString(R.string.notif_title, info.versionName))
                    .setContentText(app.getString(R.string.notif_text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify((int) info.versionCode, notification);
            Log.i(TAG, "notified: update " + info.versionName + " ready to install");
        } catch (Exception e) {
            // A missing permission or a blocked channel costs a notification,
            // never the update itself.
            Log.w(TAG, "could not post the download notification", e);
        }
    }

    /**
     * Remember what finished downloading, so tapping the notification can
     * reopen the install dialog even after the process has died. The manifest
     * is re-parsed through UpdateInfo.parse, which refuses a half-written
     * record — a corrupt entry simply means no dialog, never a crash.
     */
    private static void rememberDownloaded(Context app, UpdateInfo info) {
        try {
            JSONObject json = new JSONObject();
            json.put("configured", true);
            json.put("version_code", info.versionCode);
            json.put("version_name", info.versionName);
            json.put("apk_url", info.apkUrl);
            json.put("size", info.size);
            json.put("sha256", info.sha256 == null ? "" : info.sha256);
            json.put("notes", info.notes == null ? "" : info.notes);
            prefs(app).edit().putString(KEY_PENDING, json.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "could not remember the downloaded update", e);
        }
    }

    /** The update waiting to be installed, or null when there is none. */
    static UpdateInfo pendingInfo(Context app) {
        String raw = prefs(app).getString(KEY_PENDING, null);
        if (raw == null) return null;
        try {
            return UpdateInfo.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void clearPending(Context app) {
        prefs(app).edit().remove(KEY_PENDING).apply();
    }

    private static android.content.SharedPreferences prefs(Context app) {
        return app.getSharedPreferences("shell", Context.MODE_PRIVATE);
    }

    interface Callback {
        /** Runs on the main thread. */
        void onResult(Outcome outcome);
    }

    static final class Outcome {
        /**
         * DOWNLOADED is the important one: the APK is on disk, verified, and
         * waiting for the user to agree to the install. Nothing has been
         * installed at that point and nothing happens if they decline.
         */
        enum Kind { UP_TO_DATE, AVAILABLE, NOT_CONFIGURED, DOWNLOADED, INSTALLED, FAILED }

        final Kind kind;
        final UpdateInfo info;
        final String message;
        /**
         * Set when a saved source failed and the built-in one was tried
         * instead — the address that failed, so the UI can name it.
         */
        final String fellBackFrom;
        /** Set only when that fallback also failed, with its reason. */
        final String alsoFailed;

        private Outcome(Kind kind, UpdateInfo info, String message) {
            this(kind, info, message, null, null);
        }

        private Outcome(Kind kind, UpdateInfo info, String message, String fellBackFrom) {
            this(kind, info, message, fellBackFrom, null);
        }

        private Outcome(Kind kind, UpdateInfo info, String message, String fellBackFrom,
                        String alsoFailed) {
            this.kind = kind;
            this.info = info;
            this.message = message;
            this.fellBackFrom = fellBackFrom;
            this.alsoFailed = alsoFailed;
        }

        static Outcome upToDate(String versionName) {
            return new Outcome(Kind.UP_TO_DATE, null, versionName);
        }

        static Outcome available(UpdateInfo info) {
            return new Outcome(Kind.AVAILABLE, info, null);
        }

        static Outcome notConfigured() {
            return new Outcome(Kind.NOT_CONFIGURED, null, null);
        }

        static Outcome downloaded(UpdateInfo info) {
            return new Outcome(Kind.DOWNLOADED, info, null);
        }

        static Outcome installed(UpdateInfo info) {
            return new Outcome(Kind.INSTALLED, info, null);
        }

        static Outcome failed(String message) {
            return new Outcome(Kind.FAILED, null, message);
        }
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private Updater() {
    }

    /** Ask the server whether it has a newer build. Never runs on the UI thread. */
    static void check(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        // Every finished check is written down before anyone is told about it.
        // The record is what the settings screen reads back; a check that only
        // reports itself through a toast leaves nothing behind to look at when
        // the toast was missed.
        final Callback ui = outcome -> {
            record(app, outcome);
            callback.onResult(outcome);
        };
        POOL.execute(() -> {
            Outcome outcome = attempt(app, source(app));

            // A saved source that cannot be reached must not be able to strand
            // the device on an old build for ever. That is a real failure mode:
            // the setting was once documented as "the machine running the
            // proxy", so a phone carries addresses like http://10.0.2.2:8787
            // that answer for an emulator and for nothing else. The built-in
            // source is tried once, and the outcome says which one failed.
            if (outcome.kind == Outcome.Kind.FAILED) {
                String saved = ServerConfig.stored(app);
                String builtIn = builtIn(app);
                if (!saved.isEmpty() && !builtIn.isEmpty() && !builtIn.equals(saved)) {
                    Log.w(TAG, "saved source failed (" + saved + "), trying the built-in one");
                    Outcome second = attempt(app, manifestUrl(builtIn));
                    if (second.kind != Outcome.Kind.FAILED) {
                        // The saved address will fail again on every single
                        // start, so it is dropped rather than reported for
                        // ever: the app goes back to the source it shipped
                        // with. Re-entering the address is one tap away for
                        // anyone who really did mean to host their own.
                        ServerConfig.clear(app);
                        Log.i(TAG, "forgot the unreachable saved source " + saved);
                        outcome = new Outcome(second.kind, second.info, second.message, saved);
                    } else {
                        Log.w(TAG, "built-in source failed too: " + second.message);
                        outcome = new Outcome(outcome.kind, null, outcome.message, saved,
                                second.message);
                    }
                } else if (saved.isEmpty()) {
                    // The built-in source itself is what failed; naming it is the
                    // only thing left to say.
                    Log.w(TAG, "built-in source failed: " + outcome.message);
                }
            }

            final Outcome result = outcome;
            MainThread.post(() -> ui.onResult(result));
        });
    }

    /**
     * One line of history: what was asked, what came back, and which source
     * answered. Written here rather than by a screen, so a check from a
     * background worker is recorded exactly like one from the menu.
     */
    private static void record(Context app, Outcome outcome) {
        String detail = outcome.message;
        if ((detail == null || detail.isEmpty()) && outcome.info != null) {
            detail = outcome.info.versionName;
        }
        String source = outcome.fellBackFrom != null
                ? builtIn(app)
                : address(app);
        UpdateLog.record(app, outcome.kind.name(), detail, source);
        // Nothing newer exists (or nothing is configured), so anything still
        // remembered from a previous download is stale: the install-ready
        // notification must not outlive the update it announced — a tap on a
        // retracted update would open a dialog for something no longer there.
        if (outcome.kind == Outcome.Kind.UP_TO_DATE
                || outcome.kind == Outcome.Kind.NOT_CONFIGURED) {
            clearPending(app);
            NotificationManager nm = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            // The app posts exactly one kind of notification (an update ready
            // to install), so this retracts it wholesale rather than
            // remembering which version was announced.
            if (nm != null) nm.cancelAll();
        }
    }

    /** One request against one source. Never throws. */
    private static Outcome attempt(Context app, String source) {
        try {
            if (source == null) {
                // No update source configured: not a failure, and not worth a
                // ten second connection timeout on every cold start.
                return Outcome.notConfigured();
            }
            Log.i(TAG, "asking " + source);
            String body = get(app, source);
            UpdateInfo info = UpdateInfo.parse(body);
            if (!info.configured) return Outcome.notConfigured();
            if (!info.isNewerThan(BuildConfig.VERSION_CODE)) {
                lastManifestBase = baseOf(source);
                return Outcome.upToDate(BuildConfig.VERSION_NAME);
            }
            lastManifestBase = baseOf(source);
            return Outcome.available(info);
        } catch (Exception e) {
            Log.w(TAG, "update check failed against " + source, e);
            return Outcome.failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * The source that last answered with a manifest. [download] resolves a
     * relative apk_url against it, so a build discovered through the fallback
     * source downloads from the fallback source, not from the dead address.
     */
    private static volatile String lastManifestBase = null;

    /**
     * Download and verify, stopping short of installing.
     *
     * Split from the install on purpose: the download is the part worth doing
     * the moment an update is detected, and installing is the part that must
     * wait for a person. Reports progress text through [onStage], then
     * DOWNLOADED or FAILED through [callback].
     */
    static void download(Context context, UpdateInfo info,
                         StageCallback onStage, Callback callback) {
        Context app = context.getApplicationContext();
        final Callback ui = outcome -> {
            record(app, outcome);
            if (outcome.kind == Outcome.Kind.DOWNLOADED) {
                // The one moment worth a system notification: the APK is on
                // disk and verified, and installing it is a tap away.
                rememberDownloaded(app, info);
                notifyDownloaded(app, info);
            }
            callback.onResult(outcome);
        };
        POOL.execute(() -> {
            try {
                MainThread.post(() -> onStage.stage(Stage.DOWNLOADING));
                File dir = new File(app.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("could not create the updates cache");
                }

                // Same file name every time: the installer is handed one URI, and
                // a stale APK from a previous run must not be reused.
                File apk = new File(dir, "update.apk");
                if (apk.exists() && !apk.delete()) {
                    Log.w(TAG, "could not remove the previous download");
                }

                String absolute = resolve(sourceBase(app), info.apkUrl);
                Log.i(TAG, "downloading " + absolute);
                long written = download(absolute, apk);
                Log.i(TAG, "downloaded " + written + " bytes");

                MainThread.post(() -> onStage.stage(Stage.VERIFYING));
                if (info.sha256 != null && !info.sha256.isEmpty()) {
                    String actual = sha256(apk);
                    Log.i(TAG, "sha256 " + actual);
                    if (!actual.equalsIgnoreCase(info.sha256)) {
                        // Refuse, and remove it: leaving a bad APK on disk invites
                        // installing it by hand later.
                        Log.w(TAG, "checksum mismatch: expected " + info.sha256);
                        apk.delete();
                        final Outcome bad = Outcome.failed("checksum_mismatch");
                        MainThread.post(() -> ui.onResult(bad));
                        return;
                    }
                } else {
                    Log.w(TAG, "server published no checksum; the download is unverified");
                }

                // A truncated download with no published checksum would otherwise
                // reach the installer as a corrupt package.
                if (info.size > 0 && written != info.size) {
                    apk.delete();
                    final Outcome bad = Outcome.failed(
                            "incomplete download (" + written + " of " + info.size + " bytes)");
                    MainThread.post(() -> ui.onResult(bad));
                    return;
                }

                final Outcome done = Outcome.downloaded(info);
                MainThread.post(() -> ui.onResult(done));
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                final Outcome failed = Outcome.failed(e.getMessage() == null ? e.toString() : e.getMessage());
                MainThread.post(() -> ui.onResult(failed));
            }
        });
    }

    /**
     * Hand an already-downloaded, already-verified APK to the system installer.
     *
     * This is the step that needs consent, and on a device that has never
     * granted it the user is sent to the "install unknown apps" screen first.
     */
    static void installDownloaded(Context context, UpdateInfo info, Callback callback) {
        POOL.execute(() -> {
            try {
                File apk = new File(new File(context.getCacheDir(), "updates"), "update.apk");
                if (!apk.exists()) {
                    final Outcome gone = Outcome.failed("the download is no longer on disk");
                    MainThread.post(() -> callback.onResult(gone));
                    return;
                }

                // Re-verify at install time. Between the download and the tap the
                // file has been sitting in a cache directory that anything with
                // access to the app's storage could have rewritten.
                if (info.sha256 != null && !info.sha256.isEmpty()) {
                    String actual = sha256(apk);
                    if (!actual.equalsIgnoreCase(info.sha256)) {
                        apk.delete();
                        final Outcome bad = Outcome.failed("checksum_mismatch");
                        MainThread.post(() -> callback.onResult(bad));
                        return;
                    }
                }

                final Uri uri = FileProvider.getUriForFile(
                        context.getApplicationContext(),
                        context.getPackageName() + ".updates",
                        apk);

                MainThread.post(() -> {
                    try {
                        Log.i(TAG, "handing " + uri + " to the system installer");
                        install(context, uri);
                        callback.onResult(Outcome.installed(info));
                    } catch (Exception e) {
                        callback.onResult(Outcome.failed(e.toString()));
                    }
                });
            } catch (Exception e) {
                final Outcome failed = Outcome.failed(e.toString());
                MainThread.post(() -> callback.onResult(failed));
            }
        });
    }

    enum Stage { DOWNLOADING, VERIFYING }

    interface StageCallback {
        /** Runs on the main thread, like [Callback.onResult]. */
        void stage(Stage stage);
    }

    // ------------------------------------------------------------------ http

    private static String get(Context context, String url) throws Exception {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("server returned HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static long download(String url, File target) throws Exception {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("download returned HTTP " + code);
            }
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    total += n;
                }
                out.flush();
            }
            return total;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*");
        conn.setRequestProperty("User-Agent", "hanime-shell/" + BuildConfig.VERSION_NAME);
        return conn;
    }

    // ----------------------------------------------------------------- utils

    /**
     * The manifest URL, or null when nothing is configured.
     *
     * Two shapes are accepted so the app is not tied to this project's proxy:
     * a `*.json` file served by any static host, or a base URL the app asks
     * for `/api/app/version`.
     */
    private static String source(Context app) {
        return manifestUrl(address(app));
    }

    /** A configured address turned into the manifest URL it implies. Pure — unit-tested. */
    static String manifestUrl(String configured) {
        String url = configured == null ? "" : configured.trim();
        if (url.isEmpty()) return null;
        return url.endsWith(".json") ? url : trimSlash(url) + "/api/app/version";
    }

    /** The address a person recognises — what the settings screen shows. */
    static String sourceLabel(Context app) {
        return address(app);
    }

    /** Directory the manifest lives in — the base a relative apk_url resolves against. */
    private static String sourceBase(Context app) {
        String base = lastManifestBase;
        if (base != null && !base.isEmpty()) return base;
        return baseOf(address(app));
    }

    private static String baseOf(String configured) {
        String url = configured == null ? "" : configured.trim();
        if (url.endsWith(".json")) {
            int at = url.lastIndexOf('/');
            return at > 0 ? url.substring(0, at) : url;
        }
        return trimSlash(url);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The server may publish an absolute URL or a path relative to itself. */
    static String resolve(String serverBase, String maybeRelative) {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative;
        }
        return maybeRelative.startsWith("/")
                ? serverBase + maybeRelative
                : serverBase + "/" + maybeRelative;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    /**
     * Hand the APK to the system package installer.
     *
     * FLAG_GRANT_READ_URI_PERMISSION is required because the installer runs in a
     * different process and otherwise cannot open the content URI.
     */
    private static void install(Context context, Uri apkUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            // Route through the permission screen first: without it the installer
            // opens and immediately fails, which reads as a broken app.
            Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            settings.setData(Uri.parse("package:" + context.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            return;
        }

        context.startActivity(intent);
    }
}
