package app.hanime.shell;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * The one runtime override of the update source — and nothing else.
 *
 * The client is bundled in the APK and answered by ApiServer, so the app has no
 * server to talk to any more; the only address that survives is one a person
 * typed. Which source is actually *used* — the override, else the selected
 * channel's built-in URL — is resolved by {@link Updater#address(Context)},
 * because the channel is update state and lives with the updater. A saved
 * override wins on every start, which is why an address typed for a proxy on
 * the local machine used to be able to strand a device on an old build:
 * [Updater] therefore retries once against the channel's built-in source when
 * a saved source cannot be reached, and forgets an address that never answers.
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

    /** Forgets the override, so the app goes back to its own source. */
    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_URL)
                .apply();
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
