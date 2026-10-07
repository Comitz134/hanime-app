package app.hanime.shell;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Where new builds are published — the update source, and nothing else.
 *
 * The client is bundled in the APK and answered by ApiServer, so the app has no
 * server to talk to any more; the single setting that survives is where to look
 * for a newer version of itself. A saved value overrides the compiled-in
 * default, which is why an address typed for a proxy on the local machine used
 * to be able to strand a device on an old build: the saved override wins on
 * every start. [Updater] therefore retries once against the built-in default
 * when a saved source cannot be reached.
 */
final class ServerConfig {

    private static final String PREFS = "shell";
    private static final String KEY_URL = "server_url";

    private ServerConfig() {
    }

    /** What the user typed, or "" when there is no override. */
    static String stored(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, null);
        return saved == null ? "" : saved;
    }

    /**
     * The source actually used: the saved override, or the compiled-in default.
     * The default is never "10.0.2.2" on a shipped build — see §6 of the README.
     */
    static String get(Context context) {
        String saved = stored(context);
        if (!saved.isEmpty()) return saved;
        // No runtime override: fall back to whatever the build was given. An
        // empty UPDATE_URL means "not configured", which the updater reports
        // rather than treating as a connection failure.
        return BuildConfig.UPDATE_URL;
    }

    /** The built-in default, for the settings screen's reset button. */
    static String builtIn() {
        return BuildConfig.UPDATE_URL.trim();
    }

    /** Forgets the override, so the app goes back to its own source. */
    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_URL)
                .apply();
    }

    /** True when an update source has actually been configured. */
    static boolean isConfigured(Context context) {
        String url = get(context);
        return url != null && !url.trim().isEmpty();
    }

    /**
     * Saves an override. An empty value is not an address: it means "stop
     * overriding", which is the same thing [clear] does — that way emptying the
     * field and saving is a way back to the built-in source.
     */
    static void set(Context context, String url) {
        String normalized = normalize(url);
        if (normalized.isEmpty()) {
            clear(context);
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, normalized)
                .apply();
    }

    /**
     * Accept what a person actually types: "192.168.1.20:8787", with or without
     * a scheme, with a trailing slash or not. Guessing http:// is right here —
     * a self-hosted proxy on a LAN is not going to have a certificate.
     */
    static String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return "";

        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    static boolean isValid(String raw) {
        String url = normalize(raw);
        if (url.isEmpty()) return false;
        // Host is required; a bare scheme is not an address.
        String rest = url.replaceFirst("^https?://", "");
        return rest.contains(".") || rest.contains(":") || rest.equalsIgnoreCase("localhost");
    }
}
