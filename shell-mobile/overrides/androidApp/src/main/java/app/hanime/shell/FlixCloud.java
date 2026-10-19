package app.hanime.shell;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flixcloud — the anime area's embed provider — ported from
 * server/src/flixcloud.mjs.
 *
 * Their client never hands a player a stream: it hands it an obfuscated payload
 * and a wasm module. Steps 1–3 of the desktop twin are ordinary HTTP, so they
 * are ordinary Java here:
 *
 *   1. the embed page carries a seed, the module and three key fragments under
 *      field names *derived* from the seed, so the names differ every load;
 *   2. `/api/m3u8/<token>` answers the ciphertext and the third fragment, keyed
 *      by two SHA-256 prefixes of the token;
 *   3. the module mixes the three fragments under a seed integer — and this is
 *      the one step Java cannot do, so it is asked of {@link FlixMixer}, which
 *      runs the same module in a WebView.
 *
 * Steps 4 and 5 (the playlist key and the body wrappers) are the relay's
 * business: a stream travels to the player as a link carrying the key, and
 * {@link HlsRelay} undoes the wrapping per request.
 *
 * A provider that is *not* flixcloud is not a failure: its embed stays an
 * external URL, which is where a page this app cannot open belongs.
 */
final class FlixCloud {

    private static final String TAG = "ShellFlix";

    /** What every request to their CDN has to look like. */
    static final String REFERER = "https://flixcloud.cc/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 30_000;
    /** Long enough that one playback does not re-resolve per segment. */
    private static final long CACHE_TTL_MS = 15 * 60_000L;

    /** Their hosts, subdomains included; matched with `find`, not `matches`. */
    private static final Pattern FLIXCLOUD_HOST =
            Pattern.compile("(^|\\.)flixcloud\\.(cc|to|com)$");
    /** An embed id: URL-safe base64, and the whole path. */
    private static final Pattern EMBED_PATH = Pattern.compile("^/e/[A-Za-z0-9_-]+$");

    static final class Result {
        final boolean ok;
        final String url;
        /** base64url of the 32-byte playlist key, or null when none was found. */
        final String key;
        final String referer;
        final String code;
        /**
         * The page's own subtitle files. They are named on the embed page
         * itself, so they cost nothing beyond the fetch the stream already
         * made — and they are the reason the anime area has subtitles while
         * the film doors have none of their own.
         */
        final List<Subtitle> subtitles;

        Result(boolean ok, String url, String key, String referer, String code,
               List<Subtitle> subtitles) {
            this.ok = ok;
            this.url = url;
            this.key = key;
            this.referer = referer;
            this.code = code;
            this.subtitles = subtitles == null ? new ArrayList<>() : subtitles;
        }
    }

    /** One track the page names: a file, the words for its language, the code. */
    static final class Subtitle {
        final String url;
        /** ISO 639-1 where the page's words could be read, else the words. */
        final String lang;
        final String label;
        final String format;
        final boolean preferred;

        Subtitle(String url, String lang, String label, String format, boolean preferred) {
            this.url = url;
            this.lang = lang;
            this.label = label;
            this.format = format;
            this.preferred = preferred;
        }
    }

    private static final class Entry {
        final Result result;
        final long at;

        Entry(Result result, long at) {
            this.result = result;
            this.at = at;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    /** Cleared between tests so one test's answers never seed the next. */
    static void clearCache() {
        CACHE.clear();
    }

    private FlixCloud() {
    }

    /**
     * Whether this URL is one of their embed pages — deliberately offline: the
     * stream picker asks this for every source on every request, and a probe
     * that fetched the page would turn listing an episode into a fan-out.
     */
    static boolean isEmbed(String url) {
        if (url == null) return false;
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            if (host == null) return false;
            // `find`, not `matches`: the host may have a subdomain in front of
            // theirs (`www.flixcloud.cc`), and `matches` anchors both ends, so
            // the pattern that reads like a suffix test would refuse it.
            return FLIXCLOUD_HOST.matcher(host).find()
                    && parsed.getPath() != null
                    && EMBED_PATH.matcher(parsed.getPath()).matches();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * An embed URL in, a master playlist and its key out. Cached per embed: a
     * player asks for the master playlist once and every segment after that
     * travels through the relay, so the resolve happens once per playback.
     */
    static Result resolve(Context context, String embedUrl) {
        Entry hit = CACHE.get(embedUrl);
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) return hit.result;

        Result result = resolveUncached(context, embedUrl);
        if (result.ok) CACHE.put(embedUrl, new Entry(result, System.currentTimeMillis()));
        return result;
    }

    private static Result resolveUncached(Context context, String embedUrl) {
        try {
            String html = get(embedUrl);
            String seed = fieldValue(html, "obfuscation_seed");
            String wasm = fieldValue(html, "w_payload");
            if (seed == null || wasm == null) return fail("no_payload");

            Fields names = fieldsFor(seed);
            String frag1 = fieldValue(html, names.keyField);
            String iv = fieldValue(html, names.ivField);
            String frag2 = fieldValue(html, names.keyFrag2Field);
            String token = fieldValue(html, names.tokenField);
            if (frag1 == null || iv == null || frag2 == null || token == null) {
                return fail("no_fragments");
            }

            JSONObject tokenBody = new JSONObject(get("https://flixcloud.cc/api/m3u8/" + token));
            String ciphertext = tokenBody.optString(sha256hex(token + "vid").substring(0, 10), "");
            String frag3 = tokenBody.optString(sha256hex(token + "key").substring(0, 10), "");
            if (ciphertext.isEmpty() || frag3.isEmpty()) return fail("no_token_fields");

            long seedInt = Long.parseLong(seed.substring(0, 8), 16);
            FlixMixer.Mix mix = FlixMixer.ensure(context)
                    .open(wasm, frag1, frag2, frag3, seedInt, seed, iv, ciphertext);
            if (mix == null) return fail("mix_failed");
            // Subtitles are named on the page itself, so a page that decrypts
            // but has none still resolves; a page that failed earlier has none
            // by definition.
            return new Result(true, mix.url, mix.key, REFERER, null, parseSubtitles(html));
        } catch (Exception e) {
            Log.w(TAG, "resolve failed for " + embedUrl, e);
            return fail("unreachable");
        }
    }

    private static Result fail(String code) {
        return new Result(false, null, null, null, code, null);
    }

    /**
     * The embed page's own subtitle list, without the wasm step.
     *
     * The tracks are named in the page's markup, so they are readable as soon
     * as the page is — no key mix, no WebView, no playlist. A resolution that
     * already happened answers from its cache, which is the common case: the
     * player asks for subtitles immediately after it asked for streams. This is
     * why anime has subtitles on this app at all: the desktop's anime area
     * reads them the same way, off the provider's own page.
     */
    static List<Subtitle> subtitles(String embedUrl) {
        Entry hit = CACHE.get(embedUrl);
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
            return hit.result.subtitles;
        }
        try {
            return parseSubtitles(get(embedUrl));
        } catch (Exception e) {
            Log.i(TAG, "no subtitles from " + embedUrl + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------- subtitles

    /**
     * The page's own subtitle list, from the same object literal as the payload:
     *
     *   subtitles:[{url:"https://…/…_eng_3.ass",language:"English (Dialogues [SoM])",
     *               format:"ass",default:true}, …]
     *
     * The array is found by scanning to its matching bracket rather than by a
     * regex over the whole page: the objects inside are the only part of the
     * page known to be a list, and a page without subtitles has to read as
     * "none", not as a failure to decrypt.
     */
    static List<Subtitle> parseSubtitles(String html) {
        List<Subtitle> tracks = new ArrayList<>();
        if (html == null) return tracks;
        int at = html.indexOf("subtitles:");
        if (at < 0) return tracks;
        int open = html.indexOf('[', at);
        if (open < 0) return tracks;

        int depth = 0;
        char quote = 0;
        int end = -1;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) return tracks;

        Matcher objects = Pattern.compile("\\{[^{}]*\\}").matcher(html.substring(open + 1, end));
        while (objects.find()) {
            String object = objects.group();
            String url = stringFieldIn(object, "url");
            if (url == null || !url.startsWith("https://")) continue;
            String language = stringFieldIn(object, "language");
            if (language == null) language = "unknown";
            String format = stringFieldIn(object, "format");
            if (format == null) {
                int dot = url.lastIndexOf('.');
                format = dot < 0 ? "srt" : url.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
            tracks.add(new Subtitle(
                    url,
                    languageCodeOf(language),
                    languageDetailOf(language),
                    format,
                    Pattern.compile("default\\s*:\\s*true").matcher(object).find()));
        }
        return tracks;
    }

    /** One string field of a JS object literal, bare or quoted key. */
    private static String stringFieldIn(String object, String name) {
        Matcher m = Pattern.compile("\"?" + Pattern.quote(name) + "\"?\\s*:\\s*\"([^\"]*)\"").matcher(object);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The ISO code for a language the page names in words.
     *
     * The page writes a track the way a person would — 'English ([SoM]
     * English)', 'Portuguese(Brazil)', 'Spanish(Latin_America)' — and a client
     * matches a preferred language against a code: 'English' matches no
     * preference at all, 'en' matches an English one. So the name is reduced to
     * its code here, and the regional names keep their region for the same
     * reason (a Brazilian track is not a Portuguese one).
     *
     * A name with no entry is left as it arrived rather than guessed at: an
     * unrecognised language should still reach the picker under the name the
     * page gave it, not disappear behind an invented code.
     */
    static String languageCodeOf(String name) {
        String text = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return "unknown";

        int at = text.indexOf('(');
        String base = (at < 0 ? text : text.substring(0, at)).replaceAll("\\s+", " ").trim();
        String detail = at < 0 ? "" : text.substring(at);

        String code = LANGUAGE_CODES.get(base);
        if (code == null) return text;
        if (code.equals("pt")) return detail.matches(".*(brazil|brasil).*") ? "pt-BR" : "pt";
        if (code.equals("es")) return detail.matches(".*(latin|latam|america|419).*") ? "es-419" : "es";
        if (code.equals("zh")) return detail.contains("traditional") ? "zh-TW" : "zh-CN";
        return code;
    }

    /** 'English (Dialogues [SoM])' -> 'Dialogues [SoM]', or null when absent. */
    static String languageDetailOf(String name) {
        String text = name == null ? "" : name;
        int at = text.indexOf('(');
        if (at < 0) return null;
        String detail = text.substring(at + 1).replaceAll("\\)\\s*$", "").trim();
        return detail.isEmpty() ? null : detail;
    }

    private static final Map<String, String> LANGUAGE_CODES = new LinkedHashMap<>();

    static {
        String[] pairs = {
                "english", "en", "japanese", "ja", "korean", "ko", "chinese", "zh",
                "cantonese", "zh-TW", "arabic", "ar", "french", "fr", "german", "de",
                "italian", "it", "spanish", "es", "portuguese", "pt", "russian", "ru",
                "polish", "pl", "dutch", "nl", "swedish", "sv", "norwegian", "no",
                "danish", "da", "finnish", "fi", "icelandic", "is", "turkish", "tr",
                "greek", "el", "hebrew", "he", "hindi", "hi", "urdu", "ur",
                "bengali", "bn", "tamil", "ta", "telugu", "te", "malayalam", "ml",
                "kannada", "kn", "marathi", "mr", "thai", "th", "vietnamese", "vi",
                "indonesian", "id", "malay", "ms", "filipino", "tl", "tagalog", "tl",
                "ukrainian", "uk", "czech", "cs", "hungarian", "hu", "romanian", "ro",
                "bulgarian", "bg", "croatian", "hr", "serbian", "sr", "slovak", "sk",
                "slovenian", "sl", "estonian", "et", "latvian", "lv", "lithuanian", "lt",
                "persian", "fa", "albanian", "sq", "georgian", "ka", "mongolian", "mn",
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) LANGUAGE_CODES.put(pairs[i], pairs[i + 1]);
    }

    // ------------------------------------------------------------ their wire

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("user-agent", UA);
        conn.setRequestProperty("referer", REFERER);
        conn.setRequestProperty("accept", "*/*");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("flixcloud HTTP " + code);
            }
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

    /**
     * The field names their client derives from the seed: three chained hashes
     * for the names, three more for the key fragment's own name. Reproduced
     * exactly, since a single step out of place finds nothing on the page.
     */
    private static final class Fields {
        final String keyField;
        final String ivField;
        final String tokenField;
        final String keyFrag2Field;

        Fields(String keyField, String ivField, String tokenField, String keyFrag2Field) {
            this.keyField = keyField;
            this.ivField = ivField;
            this.tokenField = tokenField;
            this.keyFrag2Field = keyFrag2Field;
        }
    }

    private static Fields fieldsFor(String seed) throws Exception {
        String e = seed;
        for (int i = 0; i < 3; i++) e = sha256hex(e + i);
        String a = e;
        for (int i = 0; i < 3; i++) a = sha256hex(a + i);
        return new Fields(
                "kf_" + e.substring(8, 16),
                "ivf_" + e.substring(16, 24),
                e.substring(48, 64) + "_" + e.substring(56, 64),
                a.substring(0, 16) + "_" + a.substring(16, 24));
    }

    /**
     * One field out of the page's JS payload. The payload is an object literal,
     * not JSON — keys are bare when they can be and quoted when they cannot —
     * so the lookup accepts both spellings rather than pretending it is JSON.
     */
    private static String fieldValue(String html, String name) {
        if (html == null || name == null) return null;
        Matcher quoted = Pattern.compile(
                "\"" + Pattern.quote(name) + "\":\"([^\"]*)\"").matcher(html);
        if (quoted.find()) return quoted.group(1);
        Matcher bare = Pattern.compile(
                Pattern.quote(name) + ":\"([^\"]*)\"").matcher(html);
        return bare.find() ? bare.group(1) : null;
    }

    private static String sha256hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
