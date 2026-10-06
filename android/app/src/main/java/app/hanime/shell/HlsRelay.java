package app.hanime.shell;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signed HLS relay — port of server/src/hls.mjs.
 *
 * Upstream serves playlists, the AES key and ~2 MB segments, every one of them
 * requiring the hanime referer/origin pair, and every URI inside a playlist has
 * to be rewritten or the player talks to upstream directly and gets 403'd.
 *
 * Links are opaque: `u` is the base64url upstream URL and `s` is an HMAC over
 * it with a key generated at process start, so nothing can be edited or pointed
 * at a non-https target. As on the server, tokens are not meant to outlive the
 * process — a stale link simply makes the player re-resolve from /api.
 */
final class HlsRelay {

    private static final String TAG = "ShellRelay";
    private static final String SITE_BASE = "https://hanime.tv";
    private static final int TIMEOUT_MS = 60_000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** Per-process signing key; see the class note on why that is fine. */
    private static final byte[] LINK_KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(LINK_KEY);
    }

    private HlsRelay() {
    }

    // ---------------------------------------------------------------- links

    /**
     * Upstream URL -> opaque, tamper-proof relay path.
     *
     * The `/relay?` prefix is part of the link on purpose: it is emitted from
     * two places (the sources response and every URI inside a playlist), and
     * a bare `u=..&s=..` resolves against the page root and falls through to
     * the client's catch-all route instead of reaching the relay.
     */
    static String link(String absoluteUrl) {
        String u = b64url(absoluteUrl.getBytes(StandardCharsets.UTF_8));
        return "/relay?u=" + u + "&s=" + hmacShort(u);
    }

    /** Verify and unwrap a relay query pair. Returns null if the pair is not ours. */
    static String unmangle(Map<String, String> params) {
        String u = params.get("u");
        String s = params.get("s");
        if (u == null || u.isEmpty() || s == null || s.isEmpty()) return null;
        if (!constantTimeEquals(s, hmacShort(u))) return null;
        try {
            String url = new String(Base64Urls.decode(u), StandardCharsets.UTF_8);
            return url.startsWith("https://") ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hmacShort(String u) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(LINK_KEY, "HmacSHA256"));
            byte[] d = mac.doFinal(u.getBytes(StandardCharsets.UTF_8));
            // base64url, truncated to 22 characters — same as the server.
            return b64url(d).substring(0, 22);
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

    /** GET /relay?u=&s= — rewrite playlists, pass segments straight through. */
    static android.webkit.WebResourceResponse handle(Map<String, String> params) {
        String target = unmangle(params);
        if (target == null) {
            return ApiServer.json(400, "bad relay link");
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("user-agent", USER_AGENT);
            conn.setRequestProperty("origin", SITE_BASE);
            conn.setRequestProperty("referer", SITE_BASE + "/");
            conn.setRequestProperty("accept", "*/*");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                // Pass the status through so the player surfaces it honestly
                // instead of treating a 403 as a decode error.
                conn.disconnect();
                return text(502, "upstream " + code);
            }

            String type = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase();

            // Segments arrive as text/html despite being ~2 MB of binary
            // transport stream, so content-type classifies nothing. Sniff the
            // first bytes for the playlist signature instead.
            InputStream raw = conn.getInputStream();
            byte[] head = readHead(raw, 64);
            String headText = new String(head, StandardCharsets.ISO_8859_1).trim();

            if (headText.startsWith("#EXTM3U")) {
                ByteArrayOutputStream all = new ByteArrayOutputStream(1 << 16);
                all.write(head);
                copy(raw, all);
                raw.close();
                conn.disconnect();

                String playlist = new String(all.toByteArray(), StandardCharsets.ISO_8859_1);
                String rewritten = rewritePlaylist(playlist, target);
                return textBody("application/vnd.apple.mpegurl", rewritten);
            }

            // Not a playlist: stream it through without buffering the whole body.
            String mime = type.contains("octet-stream") ? "application/octet-stream" : "video/mp2t";
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("content-type", mime);
            headers.put("cache-control", "public, max-age=3600");
            headers.put("access-control-allow-origin", "*");
            String len = conn.getHeaderField("content-length");
            if (len != null) headers.put("content-length", len);

            InputStream body = new SequenceInputStream(new ByteArrayInputStream(head), raw);
            int status = 200;
            String reason = "OK";
            return new android.webkit.WebResourceResponse(mime, "UTF-8",
                    status, reason, headers, body);
        } catch (Exception e) {
            Log.w(TAG, "relay failed for " + target, e);
            if (conn != null) conn.disconnect();
            return text(502, "relay error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ playlists

    /**
     * Rewrite one playlist. Handles both the master form (#EXT-X-STREAM-INF plus
     * a URI line) and the media form (#EXT-X-KEY URI, segment URIs), since both
     * appear in this chain.
     */
    static String rewritePlaylist(String text, String baseUrl) {
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (String line : text.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("#")) {
                // Detached attributes (key, media, map) carry URIs inside the tag.
                out.append(replaceUris(trimmed, baseUrl)).append('\n');
                continue;
            }
            // Bare line: a variant playlist or a segment.
            out.append(link(resolve(trimmed, baseUrl))).append('\n');
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern URI_ATTR =
            java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");

    private static String replaceUris(String line, String baseUrl) {
        java.util.regex.Matcher m = URI_ATTR.matcher(line);
        if (!m.find()) return line;

        StringBuilder sb = new StringBuilder(line.length() + 128);
        int last = 0;
        do {
            sb.append(line, last, m.start());
            sb.append("URI=\"")
              .append(link(resolve(m.group(1), baseUrl)))
              .append('"');
            last = m.end();
        } while (m.find());
        sb.append(line, last, line.length());
        return sb.toString();
    }

    /** Equivalent of `new URL(ref, base).href`. */
    private static String resolve(String ref, String base) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    // ---------------------------------------------------------------- utils

    private static android.webkit.WebResourceResponse text(int status, String body) {
        String clean = body.replace("\\", "/").replace("\"", "'").replace("\n", " ");
        return ApiServer.json(status, "{\"error\":\"relay\",\"message\":\"" + clean + "\"}");
    }

    private static android.webkit.WebResourceResponse textBody(String mime, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("content-type", mime + "; charset=utf-8");
        headers.put("content-length", String.valueOf(bytes.length));
        headers.put("cache-control", "no-store");
        headers.put("access-control-allow-origin", "*");
        return new android.webkit.WebResourceResponse(mime, "UTF-8", 200, "OK", headers,
                new ByteArrayInputStream(bytes));
    }

    private static byte[] readHead(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) break;
            got += r;
        }
        if (got == n) return buf;
        byte[] exact = new byte[got];
        System.arraycopy(buf, 0, exact, 0, got);
        return exact;
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String b64url(byte[] raw) {
        return android.util.Base64.encodeToString(raw,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                        | android.util.Base64.NO_WRAP);
    }

    /** Minimal base64url decoder that rejects invalid input. */
    private static final class Base64Urls {
        static byte[] decode(String s) {
            return android.util.Base64.decode(s, android.util.Base64.URL_SAFE);
        }
    }
}
