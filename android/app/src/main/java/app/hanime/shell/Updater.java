package app.hanime.shell;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

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

        private Outcome(Kind kind, UpdateInfo info, String message) {
            this.kind = kind;
            this.info = info;
            this.message = message;
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
        POOL.execute(() -> {
            Outcome outcome;
            try {
                String source = source(app);
                if (source == null) {
                    // No update source configured: not a failure, and not worth
                    // a ten second connection timeout on every cold start.
                    outcome = Outcome.notConfigured();
                } else {
                    String body = get(app, source);
                    UpdateInfo info = UpdateInfo.parse(body);
                    if (!info.configured) {
                        outcome = Outcome.notConfigured();
                    } else if (info.isNewerThan(BuildConfig.VERSION_CODE)) {
                        outcome = Outcome.available(info);
                    } else {
                        outcome = Outcome.upToDate(BuildConfig.VERSION_NAME);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                outcome = Outcome.failed(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final Outcome result = outcome;
            MainThread.post(() -> callback.onResult(result));
        });
    }

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
                        MainThread.post(() -> callback.onResult(bad));
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
                    MainThread.post(() -> callback.onResult(bad));
                    return;
                }

                final Outcome done = Outcome.downloaded(info);
                MainThread.post(() -> callback.onResult(done));
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                final Outcome failed = Outcome.failed(e.getMessage() == null ? e.toString() : e.getMessage());
                MainThread.post(() -> callback.onResult(failed));
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
        String configured = ServerConfig.get(app);
        if (configured == null) return null;
        String url = configured.trim();
        if (url.isEmpty()) return null;
        return url.endsWith(".json") ? url : trimSlash(url) + "/api/app/version";
    }

    /** Directory the manifest lives in — the base a relative apk_url resolves against. */
    private static String sourceBase(Context app) {
        String configured = ServerConfig.get(app);
        if (configured == null) return "";
        String url = configured.trim();
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
