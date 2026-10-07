package app.hanime.shell;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.Date;

/**
 * What the last update check did.
 *
 * Two rounds of "the update check does not work" were diagnosed from logcat,
 * because the app kept no record of its own: the only evidence a user could
 * show was a dialog, and the only evidence on the device was a screenshot. One
 * check leaves one line here, so the settings screen can answer "when did it
 * last look, at what, and what came back" without anyone attaching a cable.
 *
 * Deliberately tiny and in the same private preferences as the update source:
 * it is four strings, it is overwritten rather than appended, and it survives
 * the restart that a crash log would not.
 */
final class UpdateLog {

    private static final String PREFS = "shell";
    private static final String KEY_AT = "update_last_at";
    private static final String KEY_KIND = "update_last_kind";
    private static final String KEY_TEXT = "update_last_text";
    private static final String KEY_SOURCE = "update_last_source";

    private UpdateLog() {
    }

    /**
     * Records the outcome of one check. Called by {@link Updater} itself, so
     * every path that checks — cold start, the menu, the settings screen, a
     * background worker — is covered by construction.
     */
    static void record(Context context, String kind, String text, String source) {
        try {
            prefs(context).edit()
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .putString(KEY_KIND, kind == null ? "" : kind)
                    .putString(KEY_TEXT, text == null ? "" : text)
                    .putString(KEY_SOURCE, source == null ? "" : source)
                    .apply();
        } catch (Exception ignored) {
            // A device that refuses to keep preferences must not lose the update
            // check itself: this is diagnostics, not state the app needs.
        }
    }

    /** When the last check finished, in wall-clock millis; 0 when never. */
    static long lastAt(Context context) {
        try {
            return prefs(context).getLong(KEY_AT, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** UP_TO_DATE, AVAILABLE, DOWNLOADED, FAILED, NOT_CONFIGURED, or "". */
    static String lastKind(Context context) {
        return read(context, KEY_KIND);
    }

    /** The version name, error message, or detail that went with the kind. */
    static String lastText(Context context) {
        return read(context, KEY_TEXT);
    }

    /** The source that answered, or the one that was dialled and failed. */
    static String lastSource(Context context) {
        return read(context, KEY_SOURCE);
    }

    /** "never", or a local timestamp. */
    static String lastAtLabel(Context context) {
        long at = lastAt(context);
        if (at <= 0) return null;
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        return fmt.format(new Date(at));
    }

    private static String read(Context context, String key) {
        try {
            String value = prefs(context).getString(key, null);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
