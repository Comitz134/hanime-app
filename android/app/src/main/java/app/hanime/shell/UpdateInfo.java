package app.hanime.shell;

import org.json.JSONObject;

/**
 * What the server says it publishes for the app.
 *
 * Parsed with org.json, which ships in the framework — the shell deliberately
 * carries no JSON library of its own.
 */
final class UpdateInfo {

    /** No release published by this server. */
    static final int NOT_CONFIGURED = -1;

    final boolean configured;
    final int versionCode;
    final String versionName;
    final String apkUrl;
    final long size;
    final String sha256;
    final String notes;

    private UpdateInfo(boolean configured, int versionCode, String versionName,
                       String apkUrl, long size, String sha256, String notes) {
        this.configured = configured;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.size = size;
        this.sha256 = sha256;
        this.notes = notes;
    }

    static UpdateInfo notConfigured() {
        return new UpdateInfo(false, NOT_CONFIGURED, null, null, 0, null, null);
    }

    static UpdateInfo parse(String body) throws Exception {
        JSONObject json = new JSONObject(body);

        if (!json.optBoolean("configured", false)) return notConfigured();

        int code = json.optInt("version_code", NOT_CONFIGURED);
        String url = json.optString("apk_url", "");
        if (code <= 0 || url.isEmpty()) {
            // A half-published release is worse than none: it would look like an
            // update and then fail at install time.
            throw new IllegalStateException("release metadata is incomplete");
        }

        return new UpdateInfo(
                true,
                code,
                json.optString("version_name", String.valueOf(code)),
                url,
                json.optLong("size", 0L),
                json.optString("sha256", ""),
                json.optString("notes", ""));
    }

    /** True when this describes a genuinely newer build than the one running. */
    boolean isNewerThan(int installedCode) {
        return configured && versionCode > installedCode;
    }
}
