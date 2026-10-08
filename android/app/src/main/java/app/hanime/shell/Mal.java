package app.hanime.shell;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The MyAnimeList relay, answered inside the app — the JVM twin of
 * server/src/mal.mjs.
 *
 * MyAnimeList speaks no CORS: neither myanimelist.net/v1/oauth2/token nor
 * api.myanimelist.net answers an Origin, so the page in the WebView can only
 * reach MAL through Java. Two hops, nothing stored:
 *
 *   POST /api/mal/token?...    the PKCE exchange (and the refresh),
 *                              fields allowlisted. A confidential
 *                              registration's secret arrives from
 *                              BuildConfig — local.properties, which is not
 *                              in version control — never from a bundle.
 *   *    /api/mal/v2/<...>?..  forwards the caller's own Bearer token; GET
 *                              parameters continue as MAL's query, PUT
 *                              parameters become the form body MAL writes
 *                              list status with.
 *
 * The contract is query-only because Android never hands the interceptor a
 * request body (WebResourceRequest has no getRequestBody) — a body-based
 * pipe could not be implemented here at all. One contract both backends
 * honour beats two that nearly match.
 *
 * The transport is the seam the JVM tests replace, exactly as Anime's is.
 */
final class Mal {

    private static final String TOKEN_ENDPOINT = "https://myanimelist.net/v1/oauth2/token";
    private static final String API_BASE = "https://api.myanimelist.net";
    private static final int TIMEOUT_MS = 15000;

    private Mal() { }

    /** Carries the upstream status and body — a passthrough keeps both. */
    static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /** The seam the JVM tests replace; the app uses {@link HttpTransport}. */
    interface Transport {
        Result call(String method, String url, Map<String, String> headers, String body) throws Exception;
    }

    static Transport transport = new HttpTransport();

    static final class HttpTransport implements Transport {
        @Override
        public Result call(String method, String url, Map<String, String> headers, String body) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(method);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            if (body != null) {
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            conn.disconnect();
            return new Result(status, text);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    /** Query string to pairs: `+` is space, everything else percent-decoded. */
    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                key = URLDecoder.decode(key, "UTF-8");
                value = URLDecoder.decode(value, "UTF-8");
            } catch (Exception e) {
                // A malformed escape stays literal rather than failing the call.
            }
            out.put(key, value);
        }
        return out;
    }

    // ------------------------------------------------------------ token route

    /**
     * POST /api/mal/token?client_id=&grant_type=... — authorization_code
     * exchange and refresh_token renewal through one door. Only the fields
     * MAL documents are forwarded, so a caller cannot smuggle anything else
     * into the form.
     */
    static Result token(String encodedQuery) throws Exception {
        Map<String, String> q = parseQuery(encodedQuery);

        String clientId = q.getOrDefault("client_id", "");
        String grant = q.getOrDefault("grant_type", "");
        if (clientId.isEmpty()) return new Result(400, "{\"error\":\"client_id_required\"}");
        if (!grant.equals("authorization_code") && !grant.equals("refresh_token")) {
            return new Result(400, "{\"error\":\"unsupported_grant_type\"}");
        }

        StringBuilder form = new StringBuilder();
        append(form, "client_id", clientId);
        append(form, "grant_type", grant);

        if (grant.equals("authorization_code")) {
            String code = q.getOrDefault("code", "");
            String verifier = q.getOrDefault("code_verifier", "");
            String redirect = q.getOrDefault("redirect_uri", "");
            if (code.isEmpty() || verifier.isEmpty() || redirect.isEmpty()) {
                return new Result(400, "{\"error\":\"code_verifier_and_redirect_required\"}");
            }
            append(form, "code", code);
            append(form, "code_verifier", verifier);
            append(form, "redirect_uri", redirect);
        } else {
            String refresh = q.getOrDefault("refresh_token", "");
            if (refresh.isEmpty()) return new Result(400, "{\"error\":\"refresh_token_required\"}");
            append(form, "refresh_token", refresh);
        }

        // A confidential registration keeps its secret here, out of the APK's
        // source: Scheme 2 of MAL's token docs, credentials in the body.
        if (!BuildConfig.MAL_CLIENT_SECRET.isEmpty()) {
            append(form, "client_secret", BuildConfig.MAL_CLIENT_SECRET);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        return transport.call("POST", TOKEN_ENDPOINT, headers, form.toString());
    }

    // -------------------------------------------------------------- api route

    /**
     * /api/mal/v2/&lt;rest&gt;?... — one hop onto api.myanimelist.net with
     * the caller's Authorization header. GET parameters continue as the
     * query; PUT and POST parameters become the form body MAL writes list
     * status with.
     */
    static Result api(String rest, String encodedQuery, String method,
                      String authorization) throws Exception {
        if (rest == null || !rest.startsWith("v2/")) {
            return new Result(404, "{\"error\":\"not_found\"}");
        }
        String verb = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        if (!verb.equals("GET") && !verb.equals("POST") && !verb.equals("PUT") && !verb.equals("DELETE")) {
            return new Result(405, "{\"error\":\"method_not_allowed\"}");
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return new Result(401, "{\"error\":\"token_required\"}");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", authorization);
        headers.put("Accept", "application/json");

        String payload = null;
        String target = API_BASE + "/" + rest;
        if (verb.equals("PUT") || verb.equals("POST")) {
            StringBuilder form = new StringBuilder();
            for (Map.Entry<String, String> e : parseQuery(encodedQuery).entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                append(form, e.getKey(), e.getValue());
            }
            payload = form.toString();
            headers.put("Content-Type", "application/x-www-form-urlencoded");
        } else if (encodedQuery != null && !encodedQuery.isEmpty()) {
            target += "?" + encodedQuery;
        }

        return transport.call(verb, target, headers, payload);
    }

    private static void append(StringBuilder form, String key, String value) throws Exception {
        if (form.length() > 0) form.append('&');
        form.append(URLEncoder.encode(key, "UTF-8"));
        form.append('=');
        form.append(URLEncoder.encode(value, "UTF-8"));
    }
}
