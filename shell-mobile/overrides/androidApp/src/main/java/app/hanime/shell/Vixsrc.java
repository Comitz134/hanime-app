package app.hanime.shell;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vidpro's door — vixsrc — without a browser.
 *
 * Every other door has to be opened in a WebView, because its player builds the
 * media URL in JavaScript and only the page ever knows it. vixsrc is not like
 * that: the page's own work is two requests, both of which answer plain text or
 * JSON, and the playlist URL it ends up playing is written into the embed page's
 * markup before any script runs.
 *
 *   * `GET /api/{movie|tv}/{id}[/{s}/{e}]` answers `{"src":"/embed/<n>?token=…"}`;
 *   * `GET <that src>` answers a page whose `window.masterPlaylist` carries
 *     `url: 'https://vixsrc.to/playlist/<n>?b=1'`.
 *
 * So the door is resolved here in about a second, with no page load and no
 * Chromium, and the WebView path is never paid for it. When vixsrc refuses —
 * its playlist endpoint answers `403` to some networks — the walk moves on to
 * the next door immediately instead of waiting out a nine-second browser
 * attempt that would ask the same two questions and be refused the same way.
 *
 * The playlist this returns is served base64 by vixsrc, which is why the link
 * handed to {@link HlsRelay} needs nothing but the site's referer: the relay
 * already knows how to unwrap that body.
 */
final class Vixsrc {

    private static final String TAG = "ShellDoors";

    private static final String BASE = "https://vixsrc.to";
    /** The referer its CDN insists on, for the playlist and every segment. */
    static final String REFERER = BASE + "/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final int TIMEOUT_MS = 12_000;

    /** `/movie/1396` or `/tv/1396/2/7`, and nothing else is this door. */
    private static final Pattern DOOR_PATH = Pattern.compile("^/(movie|tv)/(\\d+)(?:/(\\d+)/(\\d+))?$");

    /** The master playlist the embed page names for itself. */
    private static final Pattern MASTER_URL = Pattern.compile("url:\\s*'([^']+)'");

    /** The same URL where it appears inside `window.streams`. */
    private static final Pattern PLAYLIST_URL =
            Pattern.compile("(https://vixsrc\\.to/playlist/\\d+\\?[^\"'\\s<]*)");

    /** Just the id, for a page that writes the URL in pieces. */
    private static final Pattern PLAYLIST_ID = Pattern.compile("playlist/(\\d+)");

    private Vixsrc() {
    }

    /** Whether this door URL is one of vixsrc's own player pages. */
    static boolean isDoor(String doorUrl) {
        try {
            URL parsed = new URL(doorUrl);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(java.util.Locale.ROOT);
            return (host.equals("vixsrc.to") || host.endsWith(".vixsrc.to"))
                    && DOOR_PATH.matcher(parsed.getPath() == null ? "" : parsed.getPath()).matches();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The door URL in, the master playlist out — or null when vixsrc has
     * nothing for this id, or refuses this network. Never throws: a caller is
     * listing sources, and this door failing is a door being skipped.
     */
    static Doors.Found resolve(String doorUrl) {
        try {
            Matcher path = DOOR_PATH.matcher(new URL(doorUrl).getPath());
            if (!path.matches()) return null;
            String kind = path.group(1);
            String id = path.group(2);
            // A series page without a season and episode is the site's own
            // first-episode view, so that is what it is asked for.
            String season = path.group(3) == null ? "1" : path.group(3);
            String episode = path.group(4) == null ? "1" : path.group(4);

            String api = BASE + "/api/" + kind + "/" + id
                    + ("tv".equals(kind) ? "/" + season + "/" + episode : "");
            String src = new JSONObject(get(api)).optString("src", "");
            if (src.isEmpty()) {
                Log.i(TAG, "vixsrc: no src for " + doorUrl);
                return null;
            }

            String embed = src.startsWith("http") ? src : BASE + (src.startsWith("/") ? src : "/" + src);
            String playlist = playlistIn(get(embed));
            if (playlist == null) {
                Log.i(TAG, "vixsrc: no playlist in " + embed);
                return null;
            }
            return new Doors.Found(playlist, REFERER);
        } catch (Exception e) {
            Log.i(TAG, "vixsrc: " + doorUrl + " -> " + e);
            return null;
        }
    }

    /**
     * The playlist URL an embed page names. The page writes it twice — once as
     * `window.masterPlaylist.url`, once among `window.streams` — so either
     * spelling answers, and the first is preferred because it is the master
     * rather than one of the labelled servers below it.
     *
     * Both spellings are JSON-escaped in their own way (`\/`, `\u0026`, and in
     * the streams array a doubled backslash), and the escaping is what makes a
     * regex awkward, so it is undone first and the flattened page is read.
     */
    static String playlistIn(String html) {
        if (html == null || html.isEmpty()) return null;
        // Every backslash goes, then the two escapes that stand for something:
        // `\/` is a slash that was escaped and `\u0026` is an `&`. Removing the
        // backslashes first means one rule covers single- and double-escaped
        // pages alike, which is what the two spellings disagree about.
        String flat = html.replace("\\", "").replace("u0026", "&");

        Matcher master = MASTER_URL.matcher(flat);
        while (master.find()) {
            String url = master.group(1);
            if (url.contains("/playlist/")) return absolutize(url);
        }

        Matcher full = PLAYLIST_URL.matcher(flat);
        if (full.find()) return full.group(1);

        Matcher id = PLAYLIST_ID.matcher(flat);
        if (id.find()) return BASE + "/playlist/" + id.group(1) + "?b=1";
        return null;
    }

    private static String absolutize(String url) {
        if (url.startsWith("http")) return url;
        return BASE + (url.startsWith("/") ? url : "/" + url);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("user-agent", USER_AGENT);
        conn.setRequestProperty("referer", REFERER);
        conn.setRequestProperty("accept", "*/*");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " from " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 15);
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
