package app.hanime.shell;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * OpenSubtitles, as a subtitle source for the films and series shelves —
 * the JVM twin of `server/src/opensubs.mjs`.
 *
 * No door hands out subtitles. Their players resolve to a plain HLS master
 * with no `#EXT-X-MEDIA` renditions and never fetch a `.srt`/`.vtt` of their
 * own, so there is nothing inside the door to relay. What every title does have
 * is an IMDb id, and the OpenSubtitles Stremio addon answers one without an
 * account of ours:
 *
 *   GET /subtitles/movie/tt0133093.json        dozens of tracks
 *   GET /subtitles/series/tt0903747:1:1.json   the episode's tracks
 *
 * The tracks it names are direct `.srt`/`.vtt` files under its own CDN, which
 * answers a plain reader — so a track's link needs no profile, and the relay
 * only exists to keep the player on loopback.
 *
 * A subtitle list moves with the release, not with the request, so answers are
 * held for hours.
 */
final class OpenSubs {

    private static final String TAG = "ShellSubs";

    private static final String ADDON_BASE = "https://opensubtitles-v3.strem.io";
    private static final int TIMEOUT_MS = 15_000;
    private static final long TTL_MS = 6 * 3600_000L;
    /** A player cannot usefully show more than this many, and the list runs to dozens. */
    private static final int MAX_TRACKS = 40;

    /** One track, in the shape the app's own `subtitles` route answers with. */
    static final class Track {
        final String id;
        final String url;
        final String lang;
        final String label;
        final String format;

        Track(String id, String url, String lang, String label, String format) {
            this.id = id;
            this.url = url;
            this.lang = lang;
            this.label = label;
            this.format = format;
        }
    }

    private static final class Entry {
        final List<Track> tracks;
        final long at;

        Entry(List<Track> tracks, long at) {
            this.tracks = tracks;
            this.at = at;
        }
    }

    /** One list per title, shared between the request threads that ask for it. */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private OpenSubs() {
    }

    /** Cleared between tests so one test's answers never seed the next. */
    static void clearCache() {
        CACHE.clear();
    }

    /**
     * The tracks an IMDb id has. A series needs the episode in the id
     * (`tt0903747:1:1`) and a film must not carry one, so season and episode
     * are read together; a title with no id is a title with no tracks.
     */
    static List<Track> tracks(String imdbId, Integer season, Integer episode) {
        String id = imdbId == null ? "" : imdbId.trim();
        if (id.isEmpty()) return new ArrayList<>();

        boolean isEpisode = season != null && episode != null && season > 0 && episode > 0;
        String type = isEpisode ? "series" : "movie";
        String key = isEpisode ? id + ":" + season + ":" + episode : id;
        String cacheKey = "subs:" + type + ":" + key;

        Entry hit = CACHE.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL_MS) return hit.tracks;

        List<Track> tracks = fetch(type, key);
        CACHE.put(cacheKey, new Entry(tracks, System.currentTimeMillis()));
        return tracks;
    }

    private static List<Track> fetch(String type, String key) {
        List<Track> out = new ArrayList<>();
        try {
            // The colon stays a colon: that is the id shape the addon's own
            // player asks with, and percent-encoding it is a different URL to
            // a route that reads it as one.
            String path = "/subtitles/" + type + "/" + encode(key).replace("%3A", ":") + ".json";
            JSONObject body = new JSONObject(get(ADDON_BASE + path));
            JSONArray list = body.optJSONArray("subtitles");
            if (list == null) return out;

            Set<String> seen = new HashSet<>();
            for (int i = 0; i < list.length() && out.size() < MAX_TRACKS; i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null) continue;
                String url = entry.optString("url", "");
                if (!url.startsWith("https://") || !seen.add(url)) continue;
                out.add(new Track(
                        entry.has("id") ? String.valueOf(entry.opt("id")) : String.valueOf(out.size()),
                        url,
                        languageCode(entry.optString("lang", "")),
                        detailOf(entry),
                        url.toLowerCase(Locale.ROOT).endsWith(".vtt") ? "vtt" : "srt"));
            }
        } catch (Exception e) {
            // A subtitle list that cannot be fetched is an empty list: the
            // player asked before it had anything, and "none" is an answer.
            Log.i(TAG, "opensubs: " + type + "/" + key + " -> " + e);
        }
        return out;
    }

    /**
     * What tells two English tracks apart: the release the subtitle was timed
     * against, else the file's own name. Kept short — it travels as the label
     * beside the language.
     */
    static String detailOf(JSONObject entry) {
        String name = entry.optString("movieReleaseName", "");
        if (name.isEmpty()) name = entry.optString("subtitleFileName", "");
        name = Pattern.compile("\\.(srt|ass|ssa|vtt)$", Pattern.CASE_INSENSITIVE).matcher(name).replaceAll("");
        name = name.replaceAll("[\\s_]+", " ").trim();
        if (name.length() > 80) name = name.substring(0, 80);
        return name;
    }

    /**
     * The addon speaks ISO 639-2 ('eng', 'ger'); a client matches a preference
     * against ISO 639-1 ('en', 'de'), which is also what the anime area's
     * tracks carry. Both spellings of the same language appear in the wild
     * (ger/deu, fre/fra), so both are listed; anything unmapped passes through
     * as it arrived, since a code the client cannot read beats a wrong one.
     */
    static String languageCode(String lang) {
        String raw = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return "unknown";
        String mapped = ISO_639_1.get(raw);
        return mapped == null ? raw : mapped;
    }

    private static final Map<String, String> ISO_639_1 = new LinkedHashMap<>();

    static {
        String[] pairs = {
                "eng", "en", "ger", "de", "deu", "de", "fre", "fr", "fra", "fr", "spa", "es",
                "por", "pt", "ita", "it", "rus", "ru", "ara", "ar", "jpn", "ja", "kor", "ko",
                "chi", "zh", "zho", "zh", "dut", "nl", "nld", "nl", "pol", "pl", "swe", "sv",
                "nor", "no", "dan", "da", "fin", "fi", "tur", "tr", "hin", "hi", "ben", "bn",
                "tam", "ta", "tel", "te", "mal", "ml", "urd", "ur", "per", "fa", "fas", "fa",
                "ind", "id", "vie", "vi", "tha", "th", "ukr", "uk", "heb", "he", "ell", "el",
                "gre", "el", "ces", "cs", "cze", "cs", "slk", "sk", "slo", "sk", "slv", "sl",
                "ron", "ro", "rum", "ro", "hun", "hu", "bul", "bg", "hrv", "hr", "srp", "sr",
                "bos", "bs", "mkd", "mk", "mac", "mk", "alb", "sq", "sqi", "sq", "lit", "lt",
                "lav", "lv", "est", "et", "cat", "ca", "glg", "gl", "eus", "eu", "baq", "eu",
                "isl", "is", "ice", "is", "gle", "ga", "cym", "cy", "wel", "cy", "afr", "af",
                "swa", "sw", "amh", "am", "nep", "ne", "sin", "si", "khm", "km", "lao", "lo",
                "mya", "my", "fil", "tl", "tgl", "tl", "may", "ms", "msa", "ms",
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) ISO_639_1.put(pairs[i], pairs[i + 1]);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;   // UTF-8 is present on every JVM
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("user-agent", "SEKAI/1.0 (+self-hosted)");
        conn.setRequestProperty("accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
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
