package app.hanime.shell;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signed HLS relay — port of server/src/hls.mjs.
 *
 * Upstream serves playlists, the AES key and ~2 MB segments, every one of them
 * requiring the hanime referer/origin pair, and every URI inside a playlist has
 * to be rewritten or the player talks to upstream directly and gets 403'd. The
 * film doors are the same shape with their own referer, and flixcloud wraps
 * everything so a plain player would read a wall of base64 — so a link carries
 * a *profile*: the referer, the origin and, where the provider has one, the key
 * its playlists are XORed with. Nothing about the caller's query is trusted:
 * the target, the headers and the key all come out of the signed payload, or
 * the link is refused.
 *
 * Links are opaque: `u` is the base64url upstream URL, `r`/`o`/`k` are the
 * base64url profile, and `s` is an HMAC over all of it with a key generated at
 * process start, so nothing can be edited or pointed at a non-https target. As
 * on the server, tokens are not meant to outlive the process — a stale link
 * simply makes the player re-resolve.
 *
 * Three wrappers are undone here, all of them the provider's own:
 *
 *   * flixcloud's playlists are base64 of the playlist XORed with a 32-byte
 *     key, and its segments are an image header plus the bytes XORed with a
 *     fixed key (the link carries the first; the second is a constant);
 *   * vixsrc — the player behind the Vidpro door — serves plain base64 of its
 *     playlists, no key at all;
 *   * everything else is ordinary HLS behind a referer.
 */
final class HlsRelay {

    private static final String TAG = "ShellRelay";
    private static final String SITE_BASE = "https://hanime.tv";
    private static final int TIMEOUT_MS = 60_000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /**
     * A ceiling on one buffered body. Upstream's segments are ~2 MB; this is
     * far above that and far below anything that could exhaust the heap, so a
     * response that crosses it is an upstream surprise rather than a big video.
     */
    private static final int MAX_BODY_BYTES = 24 * 1024 * 1024;

    /** The fixed 16-byte key a flixcloud segment's bytes are XORed with. */
    private static final byte[] SEGMENT_KEY = {
            (byte) 157, 42, (byte) 241, 71, (byte) 179, (byte) 142, 92, 112,
            (byte) 166, 25, (byte) 228, 59, (byte) 216, 98, 15, (byte) 197,
    };
    private static final byte[] PNG_SIGNATURE = {
            (byte) 137, 80, 78, 71, 13, 10, 26, 10,
    };

    /** Per-process signing key; see the class note on why that is fine. */
    private static final byte[] LINK_KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(LINK_KEY);
    }

    private HlsRelay() {
    }

    // ---------------------------------------------------------------- links

    /** What a relay link carries: the target, and the profile it is read with. */
    static final class Link {
        final String url;
        final String referer;
        final String origin;
        /** base64url of the flixcloud playlist key, or null when the body is plain. */
        final String key;

        Link(String url, String referer, String origin, String key) {
            this.url = url;
            this.referer = referer;
            this.origin = origin;
            this.key = key;
        }

        byte[] keyBytes() {
            if (key == null) return null;
            try {
                byte[] bytes = Base64Urls.decode(key);
                return bytes.length == 0 ? null : bytes;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Upstream URL -> opaque, tamper-proof relay link, read as hanime's own. */
    static String link(String absoluteUrl) {
        return linkVia(absoluteUrl, null, null, null);
    }

    /**
     * The same link, for an upstream that only answers a page on its own site.
     *
     * The film doors are like that: their CDN returns 403 unless the request
     * carries their referer, so the link has to carry it too — signed, because
     * a header the client can rewrite is not a header worth sending. `key` is
     * the same idea for a body rather than a header: flixcloud wraps every
     * playlist with a key that only its own page can derive, so the link that
     * points at one has to carry the key, and be signed, or anyone holding the
     * link could ask this server to unwrap whatever they liked.
     */
    static String linkVia(String absoluteUrl, String referer, String origin, String key) {
        String u = b64url(absoluteUrl.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder("u=").append(u);
        if (referer != null) out.append("&r=").append(b64url(referer.getBytes(StandardCharsets.UTF_8)));
        if (origin != null) out.append("&o=").append(b64url(origin.getBytes(StandardCharsets.UTF_8)));
        if (key != null) out.append("&k=").append(b64url(key.getBytes(StandardCharsets.UTF_8)));
        out.append("&s=").append(sign(u, referer, origin, key));
        return "http://127.0.0.1:" + SekaiServer.PORT + "/relay?" + out;
    }

    /**
     * Verify and unwrap a relay query pair. Returns null if the pair is not
     * ours: the target, the headers and the key all come out of the signed
     * payload, and none of them may be swapped after the fact.
     */
    static Link unmangleLink(Map<String, String> params) {
        String u = params.get("u");
        String s = params.get("s");
        if (u == null || u.isEmpty() || s == null || s.isEmpty()) return null;

        String referer = decodePart(params.get("r"));
        String origin = decodePart(params.get("o"));
        String key = decodePart(params.get("k"));
        if (params.get("r") != null && referer == null) return null;
        if (params.get("o") != null && origin == null) return null;
        if (params.get("k") != null && key == null) return null;
        if (!constantTimeEquals(s, sign(u, referer, origin, key))) return null;

        try {
            String url = new String(Base64Urls.decode(u), StandardCharsets.UTF_8);
            return url.startsWith("https://") ? new Link(url, referer, origin, key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A signed part, decoded; null when absent or unreadable. */
    private static String decodePart(String encoded) {
        if (encoded == null) return null;
        try {
            String value = new String(Base64Urls.decode(encoded), StandardCharsets.UTF_8);
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sign(String u, String referer, String origin, String key) {
        String payload = u + "\n" + (referer == null ? "" : referer) + "\n"
                + (origin == null ? "" : origin) + "\n" + (key == null ? "" : key);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(LINK_KEY, "HmacSHA256"));
            // base64url, truncated to 22 characters — same as the server.
            return b64url(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))).substring(0, 22);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    // --------------------------------------------------------------- relay

    /**
     * Which referer a host is asked with, once it has said.
     *
     * The doors' CDNs disagree about this and there is no rule that covers
     * both: ythd's own media host refuses a request carrying the player's
     * referer outright and answers the same URL happily when the referer is its
     * own origin or absent, while hanime.tv's CDN wants the site's referer and
     * nothing else. So the profile is a *candidate list* — the door's identity
     * for hosts that belong to it, the host's own origin otherwise, then no
     * referer at all — and the first one that is not refused is remembered for
     * that host. One extra request per host, once.
     */
    private static final Map<String, Integer> PROFILE_CHOICE = new ConcurrentHashMap<>();

    /** GET /relay?u=&s= — rewrite playlists, pass segments straight through. */
    static SekaiServer.Resp handle(Map<String, String> params) {
        Link link = unmangleLink(params);
        if (link == null) {
            return text(400, "bad relay link");
        }

        String host = hostOf(link.url);
        List<String[]> candidates = profiles(link);
        Integer remembered = host == null ? null : PROFILE_CHOICE.get(host);
        int start = remembered != null && remembered < candidates.size() ? remembered : 0;

        String refusal = null;
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            int index = (start + attempt) % candidates.size();
            String[] profile = candidates.get(index);
            Fetch fetch = fetch(link.url, profile[0], profile[1]);
            if (!fetch.ok) {
                if (refusal == null) refusal = "upstream " + fetch.status;
                // A 401/403 is the answer a different referer can change;
                // anything else (a 404, a 500) is passed through as itself so
                // the player surfaces it honestly.
                if (fetch.status == 401 || fetch.status == 403) continue;
                return text(502, "upstream " + fetch.status);
            }
            if (host != null) PROFILE_CHOICE.put(host, index);
            return render(fetch, link);
        }
        return text(502, refusal == null ? "upstream failed" : refusal);
    }

    /**
     * Does a door's playlist actually load, under the profile it will be read
     * with?
     *
     * A door can hand over a perfectly well-formed playlist URL and still have
     * that URL refuse this network — vixsrc's playlist endpoint answers 403 to
     * some, while its API and its pages answer everyone. A walk that stopped at
     * the first URL would hand the player a black screen and call it a source,
     * so the URL is asked for once, here, before it becomes the answer. The
     * body is thrown away: the player fetches it again, and the profile that
     * worked is remembered for the host.
     */
    static boolean reachable(String target, String referer, String origin) {
        if (target == null) return false;
        Link link = new Link(target, referer, origin, null);
        String host = hostOf(target);
        List<String[]> candidates = profiles(link);
        Integer remembered = host == null ? null : PROFILE_CHOICE.get(host);
        int start = remembered != null && remembered < candidates.size() ? remembered : 0;

        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            int index = (start + attempt) % candidates.size();
            String[] profile = candidates.get(index);
            Fetch fetch = fetch(target, profile[0], profile[1]);
            if (fetch.ok) {
                if (host != null) PROFILE_CHOICE.put(host, index);
                return true;
            }
            // Only a refusal that names the requester is worth another profile;
            // a 404 is the door's answer about the title and will not change.
            if (fetch.status != 401 && fetch.status != 403) return false;
        }
        return false;
    }

    /**
     * The profiles to try for one target, best guess first: a host that belongs
     * to the door is asked as the door, every other host as itself, and last of
     * all with no referer, which some CDNs prefer to the wrong one.
     */
    private static List<String[]> profiles(Link link) {
        String referer = link.referer == null ? SITE_BASE + "/" : link.referer;
        String origin = link.origin == null ? SITE_BASE : link.origin;
        String targetHost = hostOf(link.url);
        String doorHost = hostOf(referer);
        boolean belongs = targetHost != null && doorHost != null
                && (targetHost.equals(doorHost) || targetHost.endsWith("." + doorHost));
        String own = originOf(link.url);

        List<String[]> out = new ArrayList<>(3);
        if (belongs) out.add(new String[]{referer, origin});
        if (own != null && !own.equals(referer)) out.add(new String[]{own, own});
        if (!belongs) out.add(new String[]{referer, origin});
        out.add(new String[]{null, null});
        return out;
    }

    /** One attempt against upstream, with the profile it was made under. */
    private static final class Fetch {
        final boolean ok;
        final int status;
        final String type;
        final byte[] body;

        Fetch(boolean ok, int status, String type, byte[] body) {
            this.ok = ok;
            this.status = status;
            this.type = type;
            this.body = body;
        }
    }

    private static Fetch fetch(String target, String referer, String origin) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("user-agent", USER_AGENT);
            // A profile replaces the defaults outright rather than blending two
            // sites' identities; none at all is a candidate of its own.
            if (origin != null) conn.setRequestProperty("origin", origin);
            if (referer != null) conn.setRequestProperty("referer", referer);
            conn.setRequestProperty("accept", "*/*");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return new Fetch(false, code, "", null);
            }

            String type = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase();

            // Segments arrive as text/html despite being ~2 MB of binary
            // transport stream, so content-type classifies nothing. Read the
            // body whole — the current shape buffers a segment anyway — and
            // sniff for the playlist signature (or the provider's wrapper).
            ByteArrayOutputStream all = new ByteArrayOutputStream(1 << 21);
            try (InputStream in = conn.getInputStream()) {
                copy(in, all, MAX_BODY_BYTES);
            }
            return new Fetch(true, code, type, all.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "relay fetch failed for " + target, e);
            return new Fetch(false, 502, "", null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The body of a fetch that answered: unwrap it, or rewrite it as a playlist. */
    private static SekaiServer.Resp render(Fetch fetch, Link link) {
        byte[] body = fetch.body;

        // A link carrying a playlist key came from flixcloud, where *nothing*
        // arrives as itself: playlists are base64 over a keystream, and segments
        // wear an image header. Both are undone here, before the plain-playlist
        // check below, because their wrappers are what the sniffing would
        // otherwise trip over.
        byte[] key = link.keyBytes();
        if (key != null) {
            byte[] segment = unsealSegment(body);
            if (segment != null) {
                return new SekaiServer.Resp(200, "video/mp2t", segment);
            }
            String playlist = decodePlaylist(body, key);
            if (playlist != null) return playlist(playlist, link);
        }

        String head = new String(body, 0, Math.min(body.length, 64),
                StandardCharsets.ISO_8859_1).trim();
        if (head.startsWith("#EXTM3U")) {
            return playlist(new String(body, StandardCharsets.ISO_8859_1), link);
        }

        // Plain base64 of a playlist: vixsrc hands its whole chain over
        // encoded, and a player cannot use that as-is.
        String decoded = decodePlainPlaylist(body);
        if (decoded != null) return playlist(decoded, link);

        String mime = fetch.type.contains("octet-stream") ? "application/octet-stream" : "video/mp2t";
        return new SekaiServer.Resp(200, mime, body);
    }

    /** The host of a URL, or null when it is not one. */
    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null || host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** The origin of a URL as `https://host/`, or null when it is not one. */
    private static String originOf(String url) {
        String host = hostOf(url);
        return host == null ? null : "https://" + host + "/";
    }

    // ------------------------------------------------------------ playlists

    /** One playlist, rewritten to loopback and served as a playlist. */
    private static SekaiServer.Resp playlist(String text, Link link) {
        return textBody("application/vnd.apple.mpegurl", rewritePlaylist(text, link));
    }

    /**
     * Rewrite one playlist. Handles both the master form (#EXT-X-STREAM-INF plus
     * a URI line) and the media form (#EXT-X-KEY URI, segment URIs), since both
     * appear in this chain. Every nested URI inherits the link's own profile: a
     * playlist that needs the door's referer needs it on every variant and
     * segment below it too, and a flixcloud one needs the key all the way down.
     */
    static String rewritePlaylist(String text, Link link) {
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (String line : text.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("#")) {
                // Detached attributes (key, media, map) carry URIs inside the tag.
                out.append(replaceUris(trimmed, link)).append('\n');
                continue;
            }
            // Bare line: a variant playlist or a segment.
            out.append(relink(resolve(trimmed, link.url), link)).append('\n');
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern URI_ATTR =
            java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");

    private static String replaceUris(String line, Link link) {
        java.util.regex.Matcher m = URI_ATTR.matcher(line);
        if (!m.find()) return line;

        StringBuilder sb = new StringBuilder(line.length() + 128);
        int last = 0;
        do {
            sb.append(line, last, m.start());
            sb.append("URI=\"")
              .append(relink(resolve(m.group(1), link.url), link))
              .append('"');
            last = m.end();
        } while (m.find());
        sb.append(line, last, line.length());
        return sb.toString();
    }

    /** One upstream URI, as its own relay link under the same profile. */
    private static String relink(String absoluteUrl, Link link) {
        // Every URI is wrapped, including one that happens to name the playlist
        // itself: the only URLs a player is ever given are loopback ones.
        return linkVia(absoluteUrl, link.referer, link.origin, link.key);
    }

    /** Equivalent of `new URL(ref, base).href`. */
    private static String resolve(String ref, String base) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    // --------------------------------------------------------- the wrappers

    /**
     * A flixcloud playlist body: base64 of the playlist XORed with the key.
     * Returns null when the body is neither a playlist nor the wrapped form of
     * one, so a segment or an image is never mistaken for a playlist.
     */
    static String decodePlaylist(byte[] bytes, byte[] key) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (text.trim().startsWith("#EXTM3U")) return text;
        if (key == null || key.length == 0) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512 * 1024) return null;
        if (!PLAIN_BASE64.matcher(trimmed).matches()) return null;
        byte[] binary;
        try {
            binary = Base64Urls.decodeStandard(trimmed);
        } catch (Exception e) {
            return null;
        }
        for (int i = 0; i < binary.length; i++) {
            binary[i] ^= key[i % key.length];
        }
        String decoded = new String(binary, StandardCharsets.ISO_8859_1);
        return decoded.trim().startsWith("#EXTM3U") ? decoded : null;
    }

    /** A playlist that arrives base64-encoded and nothing more (vixsrc). */
    static String decodePlainPlaylist(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1).trim();
        if (text.isEmpty() || text.length() > 512 * 1024) return null;
        if (!PLAIN_BASE64.matcher(text).matches()) return null;
        try {
            String decoded = new String(Base64Urls.decodeStandard(text), StandardCharsets.ISO_8859_1);
            return decoded.trim().startsWith("#EXTM3U") ? decoded : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern PLAIN_BASE64 =
            java.util.regex.Pattern.compile("[A-Za-z0-9+/=\\s]+");

    /**
     * A flixcloud media segment: drop the image header the CDN serves it under,
     * undo the repeating XOR, and hand back MPEG-TS. Returns null when the body
     * is already plain, so the relay can tell "disguised" from "not".
     */
    static byte[] unsealSegment(byte[] raw) {
        byte[] body = null;
        if (raw.length >= 8 && startsWith(raw, PNG_SIGNATURE)) {
            body = slice(raw, 8);
        } else if (raw.length >= 12
                && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F'
                && raw[8] == 'W' && raw[9] == 'E' && raw[10] == 'B' && raw[11] == 'P') {
            body = slice(raw, 12);
        }
        if (body == null || body.length < 188) return null;
        // A body that already starts on a sync byte was served in the clear.
        if (body[0] == 0x47) return body;
        byte[] out = body.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] ^= SEGMENT_KEY[i & 15];
        }
        return out[0] == 0x47 ? out : null;
    }

    private static boolean startsWith(byte[] haystack, byte[] needle) {
        if (haystack.length < needle.length) return false;
        for (int i = 0; i < needle.length; i++) {
            if (haystack[i] != needle[i]) return false;
        }
        return true;
    }

    private static byte[] slice(byte[] raw, int from) {
        byte[] out = new byte[raw.length - from];
        System.arraycopy(raw, from, out, 0, out.length);
        return out;
    }

    // ---------------------------------------------------------------- utils

    private static SekaiServer.Resp text(int status, String body) {
        String clean = body.replace("\\", "/").replace("\"", "'").replace("\n", " ");
        return SekaiServer.Resp.json(status,
                "{\"error\":\"relay\",\"message\":\"" + clean + "\"}");
    }

    private static SekaiServer.Resp textBody(String mime, String body) {
        return new SekaiServer.Resp(200, mime + "; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /** Copy until the stream ends, refusing to buffer more than `max` bytes. */
    private static void copy(InputStream in, java.io.OutputStream out, long max) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IllegalStateException("response too large");
            out.write(buf, 0, n);
        }
    }

    private static String b64url(byte[] raw) {
        return android.util.Base64.encodeToString(raw,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                        | android.util.Base64.NO_WRAP);
    }

    /** Minimal base64 decoders that reject invalid input. */
    private static final class Base64Urls {
        static byte[] decode(String s) {
            return android.util.Base64.decode(s, android.util.Base64.URL_SAFE);
        }

        static byte[] decodeStandard(String s) {
            return android.util.Base64.decode(s, android.util.Base64.DEFAULT);
        }
    }
}
