package app.hanime.shell;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Where the proxy lives.
 *
 * Stored in SharedPreferences rather than baked into the APK, because the
 * address is different on every network: an emulator reaches the host at
 * 10.0.2.2, a phone needs the machine's LAN address, and a phone away from home
 * needs whatever the user has set up. A build-time default that cannot be
 * changed is a build that only works on one machine.
 */
final class ServerConfig {

    private static final String PREFS = "shell";
    private static final String KEY_URL = "server_url";

    private ServerConfig() {
    }

    static String get(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, null);
        if (saved != null && !saved.isEmpty()) return saved;
        // No runtime override: fall back to whatever the build was given. An
        // empty UPDATE_URL means "not configured", which the updater reports
        // rather than treating as a connection failure.
        return BuildConfig.UPDATE_URL;
    }

    /** True when an update source has actually been configured. */
    static boolean isConfigured(Context context) {
        String url = get(context);
        return url != null && !url.trim().isEmpty();
    }

    static void set(Context context, String url) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, normalize(url))
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
