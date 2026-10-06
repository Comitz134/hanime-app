package app.hanime.shell;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playable sources, resolved here instead of by the proxy.
 *
 * This is the gated path, and it needs three things to line up:
 *
 *   x-signature / x-time   from the WASM signer      -> else 401
 *   x-csrf-token           from ct.hanime.tv         -> else 422
 *   the cookie             that binds the two        -> else 422
 *
 * No login is involved: anonymous visitors get the same handshake, so playback
 * works without an account. (The account cookie is a separate, optional thing —
 * it only unlocks the user's own playlists.)
 *
 * Mirrors server/src/hanime.mjs getSources(): one session shared across
 * handshakes, a forced refresh on 401/403, and a short cache because the
 * minted m3u8 tokens are short lived.
 */
final class Streams {

    private static final String TAG = "ShellStreams";
    private static final String CSRF_URL = "https://ct.hanime.tv/csrf-token";
    private static final String API_BASE = "https://auth.hanime.tv";
    private static final String SITE_BASE = "https://hanime.tv";

    private static final long CSRF_TTL_MS = 5 * 60 * 1000;
    private static final long SOURCES_TTL_MS = 4 * 60 * 1000;
    private static final int TIMEOUT_MS = 25_000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final Map<String, String> COOKIES = new LinkedHashMap<>();
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();

    private static volatile String csrf;
    private static volatile long csrfAt;
    private static volatile Signer signer;

    private static final class Cached {
        final long at;
        final String body;
        Cached(long at, String body) { this.at = at; this.body = body; }
    }

    private Streams() {
    }

    static void setSigner(Signer s) {
        signer = s;
    }

    // -------------------------------------------------------------- resolve

    /** GET /api/videos/:slug/sources — the JSON the player consumes. */
    static String resolve(String slug) throws Exception {
        synchronized (Streams.class) {
            Cached hit = CACHE.get(slug);
            if (hit != null && System.currentTimeMillis() - hit.at < SOURCES_TTL_MS) {
                return hit.body;
            }

            JSONObject payload = new JSONObject();
            payload.put("timestamp_unix", System.currentTimeMillis() / 1000);
            payload.put("directive", "htv_player_handshake");
            payload.put("slug", slug);
            String token = Token.seal(payload);

            String body = null;
            Exception last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    body = handshake(token, attempt > 0);
                    break;
                } catch (Handshake401 e) {
                    last = e;
                    Log.i(TAG, "handshake rejected, refreshing session");
                }
            }
            if (body == null) {
                throw last != null ? last : new IllegalStateException("handshake failed");
            }

            String out = shape(slug, body);
            CACHE.put(slug, new Cached(System.currentTimeMillis(), out));
            return out;
        }
    }

    /** One POST to the handshake endpoint; returns the raw x-token envelope. */
    private static String handshake(String sealedToken, boolean forceRefresh) throws Exception {
        ensureSession(forceRefresh);

        Map<String, String> headers = signedHeaders();
        headers.put("x-csrf-token", csrf);
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) headers.put("cookie", cookie);

        byte[] body = ("{\"token\":\"" + escape(sealedToken) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = open(API_BASE + "/api/v11/handshake");
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            absorbCookies(conn);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) throw new Handshake401(code);
            if (code != 200) {
                throw new IllegalStateException("handshake failed (HTTP " + code + ")");
            }
            String header = conn.getHeaderField("x-token");
            if (header == null || header.isEmpty()) {
                throw new IllegalStateException("handshake returned no x-token");
            }
            return header;
        } finally {
            conn.disconnect();
        }
    }

    /** Session cookies + csrf token, shared and refreshed opportunistically. */
    private static void ensureSession(boolean force) throws Exception {
        if (!force && csrf != null && System.currentTimeMillis() - csrfAt < CSRF_TTL_MS) return;

        Map<String, String> headers = signedHeaders();
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) headers.put("cookie", cookie);

        HttpURLConnection conn = open(CSRF_URL);
        try {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            absorbCookies(conn);

            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("csrf-token fetch failed (" + code + ")");

            String body = readAll(conn.getInputStream());
            String token = new JSONObject(body).optString("csrf_token", "");
            if (token.isEmpty()) throw new IllegalStateException("csrf-token response was empty");

            csrf = token;
            csrfAt = System.currentTimeMillis();
            Log.i(TAG, "session ready (csrf acquired)");
        } finally {
            conn.disconnect();
        }
    }

    // --------------------------------------------------------------- shaping

    private static String shape(String slug, String sealedToken) throws Exception {
        JSONObject decoded = Token.open(sealedToken);
        JSONArray in = decoded.optJSONArray("sources");
        if (in == null) in = new JSONArray();

        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject s = in.optJSONObject(i);
            if (s == null) continue;
            String src = s.optString("src", "");
            if (src.isEmpty()) continue; // the 1080p promotion stub

            JSONObject row = new JSONObject();
            row.put("label", s.optString("label", ""));
            row.put("height", s.optInt("height", 0));
            row.put("width", s.optInt("width", 0));
            row.put("kind", s.optString("kind", "normal"));
            // Hand the player a loopback URL: every nested URI is rewritten by
            // the relay, so the player never needs upstream headers itself.
            row.put("url", HlsRelay.link(absolutize(src)));
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare(b.optInt("height", 0), a.optInt("height", 0)));

        JSONObject out = new JSONObject();
        out.put("slug", slug);
        out.put("resolved_at", new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(new java.util.Date()));
        out.put("sources", new JSONArray(rows));
        return out.toString();
    }

    private static String absolutize(String src) {
        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        return src.startsWith("/") ? SITE_BASE + src : SITE_BASE + "/" + src;
    }

    // ------------------------------------------------------------------ http

    private static Map<String, String> signedHeaders() throws Exception {
        String raw = signer != null ? signer.sign() : null;
        String[] pair = Signer.parsePair(raw);
        if (pair == null) throw new IllegalStateException("signer unavailable");

        Map<String, String> h = new LinkedHashMap<>();
        h.put("user-agent", USER_AGENT);
        h.put("accept", "application/json");
        h.put("content-type", "application/json");
        h.put("origin", SITE_BASE);
        h.put("referer", SITE_BASE + "/");
        h.put("x-signature-version", "web2");
        h.put("x-signature", pair[0]);
        h.put("x-time", pair[1]);
        return h;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static void absorbCookies(HttpURLConnection conn) {
        Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) return;
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String raw : e.getValue()) {
                int semi = raw.indexOf(';');
                String pair = semi >= 0 ? raw.substring(0, semi) : raw;
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    COOKIES.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
    }

    private static String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : COOKIES.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 14);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 401/403 from the handshake: the session is stale, refresh and retry once. */
    private static final class Handshake401 extends Exception {
        Handshake401(int code) {
            super("handshake rejected (" + code + ")");
        }
    }
}
