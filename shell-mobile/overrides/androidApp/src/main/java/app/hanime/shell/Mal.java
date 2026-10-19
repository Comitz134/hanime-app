package app.hanime.shell;

import com.nuvio.android.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /**
     * What this app calls itself upstream. MAL sits behind a gateway that is
     * shown to answer the sign-in exchange with a 502 of its own now and then;
     * a request from a named client is treated no worse than a browser's, and
     * a named client is the only way the log on MAL's side can say who called.
     */
    private static final String USER_AGENT = "SEKAI-Mobile/1.0 (Android; MyAnimeList sign-in)";

    /**
     * A phone that has just come back from the browser is a phone that may be
     * switching networks, and the first lookup after the switch can miss
     * (`unable to resolve host "myanimelist.net"`) even though the network is
     * up a second later. That blip is not a failed login, so a network error is
     * retried a few times with a short pause before it is reported.
     */
    private static final int RETRIES = 3;
    private static final long RETRY_PAUSE_MS = 400L;

    /**
     * A gateway error is MAL's edge, not MAL's answer. It is the one status
     * worth asking twice about: the sign-in exchange is idempotent until the
     * code is spent, and the 502 seen on a real phone was gone a second later.
     */
    private static final int GATEWAY_RETRIES = 2;
    private static final long GATEWAY_PAUSE_MS = 900L;

    /** What the reader is told when MAL cannot be reached at all. */
    static final String UNREACHABLE =
            "MyAnimeList could not be reached. Check the connection and try again.";

    /** What the reader is told when MAL's own edge failed the sign-in. */
    static final String GATEWAY =
            "MyAnimeList's sign-in service had a gateway error. Try Connect again in a moment.";

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

    /**
     * OkHttp, because it is the one client here that can be handed a resolver.
     *
     * The phone this was built for has a resolver that answers
     * `myanimelist.net` with *no address* — the domain is filtered on the
     * network while the browser still opens it (Chrome resolves over its own
     * DNS-over-HTTPS). MAL was then reported as unreachable even though the
     * connection was fine. So MAL's hosts are resolved through Cloudflare's
     * DoH endpoint by the relay itself, with the system resolver as the
     * fallback and every other host left alone.
     */
    static final class HttpTransport implements Transport {
        @Override
        public Result call(String method, String url, Map<String, String> headers, String body) throws Exception {
            okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(url);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                request.header(h.getKey(), h.getValue());
            }
            request.header("User-Agent", USER_AGENT);

            String verb = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            okhttp3.MediaType form = okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");
            if (body != null) {
                request.method(verb, okhttp3.RequestBody.create(body, form));
            } else if (verb.equals("GET")) {
                request.get();
            } else {
                request.method(verb, okhttp3.RequestBody.create(new byte[0], null));
            }

            try (okhttp3.Response response = CLIENT.newCall(request.build()).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                return new Result(response.code(), text);
            }
        }
    }

    private static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dns(Mal::lookup)
            .build();

    /** The DoH lookups themselves must not depend on the DoH resolver. */
    private static final okhttp3.OkHttpClient DOH_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(6000, TimeUnit.MILLISECONDS)
            .readTimeout(6000, TimeUnit.MILLISECONDS)
            .build();

    // Two public JSON DNS endpoints, both addressed in a way that cannot be
    // blocked by filtering the DoH service's own name: Cloudflare by address,
    // Google by name. One failing is normal; either answering is enough.
    private static final String[] DOH_ENDPOINTS = {
            "https://1.1.1.1/dns-query",
            "https://dns.google/resolve",
    };
    private static final long DNS_TTL_MS = 10 * 60_000L;
    private static final Map<String, Resolved> DNS = new ConcurrentHashMap<>();

    private static final class Resolved {
        final List<InetAddress> addresses;
        final long at;

        Resolved(List<InetAddress> addresses, long at) {
            this.addresses = addresses;
            this.at = at;
        }
    }

    /**
     * MAL's hosts through DoH, everything else through Android. A failure of
     * the DoH call is not fatal: the system answer is tried, and its exception
     * (if any) is the one reported.
     */
    static List<InetAddress> lookup(String hostname) throws UnknownHostException {
        String name = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);
        if (name.equals("myanimelist.net") || name.endsWith(".myanimelist.net")) {
            List<InetAddress> resolved = resolveOverDoh(name);
            if (!resolved.isEmpty()) return resolved;
        }
        return okhttp3.Dns.SYSTEM.lookup(hostname);
    }

    /** A and AAAA answers from Cloudflare's JSON DNS API, cached for ten minutes. */
    static List<InetAddress> resolveOverDoh(String hostname) {
        Resolved hit = DNS.get(hostname);
        if (hit != null && hit.at > System.currentTimeMillis()) return hit.addresses;

        List<InetAddress> addresses = new ArrayList<>();
        for (String endpoint : DOH_ENDPOINTS) {
            for (String type : new String[] {"A", "AAAA"}) {
                try {
                    okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(endpoint)
                            .newBuilder()
                            .addQueryParameter("name", hostname)
                            .addQueryParameter("type", type)
                            .build();
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(url)
                            .header("Accept", "application/dns-json")
                            .build();
                    try (okhttp3.Response response = DOH_CLIENT.newCall(request).execute()) {
                        if (!response.isSuccessful() || response.body() == null) {
                            android.util.Log.w("ShellMal", "DoH " + endpoint + " " + hostname
                                    + " " + type + " -> HTTP " + response.code());
                            continue;
                        }
                        JSONObject body = new JSONObject(response.body().string());
                        JSONArray answers = body.optJSONArray("Answer");
                        if (answers == null) continue;
                        for (int i = 0; i < answers.length(); i++) {
                            JSONObject answer = answers.optJSONObject(i);
                            if (answer == null) continue;
                            int record = answer.optInt("type", 0);
                            if (record != 1 && record != 28) continue;
                            String data = answer.optString("data", "");
                            if (data.isEmpty()) continue;
                            // A literal address: getByName cannot recurse into DNS.
                            addresses.add(InetAddress.getByName(data));
                        }
                    }
                } catch (Exception failure) {
                    // The next endpoint, record type or the system resolver
                    // gets a turn.
                    android.util.Log.w("ShellMal", "DoH " + endpoint + " " + hostname + " "
                            + type + " failed: " + failure.getClass().getName()
                            + ": " + failure.getMessage());
                }
            }
        }
        if (!addresses.isEmpty()) {
            DNS.put(hostname, new Resolved(addresses, System.currentTimeMillis() + DNS_TTL_MS));
        }
        return addresses;
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
        // source: Scheme 2 of MAL's token docs, credentials in the body. The
        // value is laid into BuildConfig at build time (local.properties'
        // `malClientSecret`, or MAL_CLIENT_SECRET in the environment), never
        // written into a file this repository tracks.
        if (!BuildConfig.MAL_CLIENT_SECRET.isEmpty()) {
            append(form, "client_secret", BuildConfig.MAL_CLIENT_SECRET);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");

        // One exchange, and a second and third only when MAL's edge answered
        // with a gateway error. The code is not spent by a 502, so asking
        // again with the same form is the same request, not a second sign-in.
        Result result = call("POST", TOKEN_ENDPOINT, headers, form.toString());
        for (int attempt = 0; attempt < GATEWAY_RETRIES && upstreamGateway(result); attempt++) {
            try {
                Thread.sleep(GATEWAY_PAUSE_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            result = call("POST", TOKEN_ENDPOINT, headers, form.toString());
        }
        if (upstreamGateway(result)) {
            // The reader gets the sentence; the log keeps MAL's own page short
            // enough to read in logcat, which is the only way to tell an edge
            // failure from a rejected registration.
            android.util.Log.w("ShellMal", "token gateway " + result.status + ": "
                    + result.body.substring(0, Math.min(300, result.body.length())));
            return new Result(result.status,
                    "{\"error\":\"mal_unavailable\",\"message\":\"" + GATEWAY + "\"}");
        }
        return result;
    }

    /** Whether a status is MAL's edge failing, not MAL answering the call. */
    static boolean isGatewayError(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    /**
     * A gateway error that came from MAL — not the 502 this relay writes when
     * its own network call failed. Only the former is worth asking again.
     */
    static boolean upstreamGateway(Result result) {
        return isGatewayError(result.status) && !result.body.contains("\"unreachable\"");
    }

    // ------------------------------------------------------------- transport

    /**
     * One call upstream, retried while the failure is one the network will
     * recover from on its own.
     *
     * Only the network layer is retried: a 400 MAL answered with is MAL's own
     * answer and would be the same answer twice, so it is passed straight
     * through. A failure that survives every attempt is answered here rather
     * than thrown — a DNS miss on a phone is not a bug in the sign-in, and the
     * reader is owed a sentence they can act on instead of the resolver's.
     */
    static Result call(String method, String url, Map<String, String> headers, String body) {
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return transport.call(method, url, headers, body);
            } catch (Exception e) {
                if (!transientNetworkError(e)) {
                    android.util.Log.w("ShellMal", method + " failed: "
                            + e.getClass().getName() + ": " + e.getMessage());
                    return new Result(502, "{\"error\":\"unreachable\",\"message\":\"" + UNREACHABLE + "\"");
                }
                if (attempt + 1 == RETRIES) {
                    android.util.Log.w("ShellMal", method + " still failing after " + RETRIES
                            + " attempts: " + e.getClass().getName() + ": " + e.getMessage());
                }
                if (attempt + 1 < RETRIES) {
                    try {
                        Thread.sleep(RETRY_PAUSE_MS * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return new Result(502, "{\"error\":\"unreachable\",\"message\":\"" + UNREACHABLE + "\"");
    }

    /** Whether a failure is the network being down for a moment, not MAL. */
    static boolean transientNetworkError(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.UnknownHostException) return true;
            if (cause instanceof java.net.SocketTimeoutException) return true;
            if (cause instanceof java.net.ConnectException) return true;
        }
        return false;
    }

    // -------------------------------------------------------------- api route

    /**
     * POST /api/mal/v2/&lt;rest&gt;?... — one hop onto api.myanimelist.net with
     * the caller's Authorization header. GET parameters continue as the
     * query; PUT and POST parameters become the form body MAL writes list
     * status with.
     */
    static Result api(String rest, String encodedQuery, String method,
                      String authorization) throws Exception {
        return api(rest, parseQuery(encodedQuery), method, authorization);
    }

    /**
     * The same hop with the query already parsed — what [SekaiServer] has in
     * hand, since it reads a request's head and never its body. One contract,
     * two shapes of the same argument.
     */
    static Result api(String rest, Map<String, String> query, String method,
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
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                append(form, e.getKey(), e.getValue());
            }
            payload = form.toString();
            headers.put("Content-Type", "application/x-www-form-urlencoded");
        } else if (!query.isEmpty()) {
            StringBuilder encoded = new StringBuilder();
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (encoded.length() > 0) encoded.append('&');
                encoded.append(URLEncoder.encode(e.getKey(), "UTF-8"));
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    encoded.append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
                }
            }
            target += "?" + encoded;
        }

        return call(verb, target, headers, payload);
    }

    private static void append(StringBuilder form, String key, String value) throws Exception {
        if (form.length() > 0) form.append('&');
        form.append(URLEncoder.encode(key, "UTF-8"));
        form.append('=');
        form.append(URLEncoder.encode(value, "UTF-8"));
    }
}
