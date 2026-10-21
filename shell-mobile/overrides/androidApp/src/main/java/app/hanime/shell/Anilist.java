package app.hanime.shell;

import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AniList, through the app's own server.
 *
 * AniList is two endpoints: an OAuth token exchange that wants the
 * registration's secret, and one GraphQL endpoint that everything else goes
 * through. The secret cannot live in the UI's code, so the exchange happens
 * here — exactly as MyAnimeList's does in {@link Mal} — and the app is handed
 * back only the token.
 *
 * GraphQL is a POST with a JSON body and the token in a header, which is why
 * {@link SekaiServer}'s request reader had to learn to read a body at all: the
 * rest of this server is a program that only ever reads a request's head. The
 * body is forwarded as it arrived; nothing here parses GraphQL.
 *
 * The redirect is the same one MyAnimeList uses — this server's own root on
 * loopback — because that is the only origin a phone can send the browser back
 * to without a second registration. The state the app minted says which
 * provider a landing code belongs to.
 */
final class Anilist {

    private static final String TOKEN_ENDPOINT = "https://anilist.co/api/v2/oauth/token";
    private static final String GRAPHQL = "https://graphql.anilist.co";

    /** The "SEKAI" application on AniList's developer settings. */
    static final String CLIENT_ID = "54045";
    private static final String CLIENT_SECRET = "4CCEIMApgzme8piZnyGtiFWfzP9G07Pi9RJlTqcp9";

    /** The redirect registered on that application, and the one MAL uses too. */
    static final String REDIRECT_URI = "http://localhost:8787/";

    private static final String USER_AGENT = "SEKAI-Mobile/1.0 (Android; AniList sign-in)";

    /**
     * How long one call to AniList may take. GraphQL answers are small — a page
     * of a list is a few hundred rows at most — so this is generous rather than
     * tight; it is the list read that is the big one.
     */
    private static final int TIMEOUT_MS = 20000;
    private static final int CONNECT_TIMEOUT_MS = 8000;

    /** A dropped connection is retried; a refused query is not. */
    private static final int RETRIES = 3;
    private static final long RETRY_PAUSE_MS = 400L;

    static final String UNREACHABLE =
            "AniList could not be reached. Check the connection and try again.";

    private Anilist() {
    }

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
     * OkHttp, sharing {@link Mal}'s resolver: AniList is reached through the
     * phone's own DNS, and the DoH fallback that keeps MyAnimeList reachable on
     * a network that filters it costs nothing here.
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
            if (body != null) {
                okhttp3.MediaType type = body.startsWith("{")
                        ? okhttp3.MediaType.parse("application/json; charset=utf-8")
                        : okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");
                request.method(verb, okhttp3.RequestBody.create(body, type));
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
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dns(Mal::lookup)
            .build();

    /**
     * POST /api/anilist/token?code=…&redirect_uri=… — the authorization-code
     * exchange, with the registration's secret added here and nowhere else.
     *
     * AniList answers `{access_token, token_type, expires_in}`. There is no
     * refresh token: the code's access token lasts a year, and the app asks the
     * person to link again when it runs out.
     */
    static Result token(String encodedQuery) throws Exception {
        Map<String, String> q = Mal.parseQuery(encodedQuery);
        String code = q.getOrDefault("code", "").trim();
        String redirect = q.getOrDefault("redirect_uri", REDIRECT_URI).trim();
        if (code.isEmpty()) {
            return new Result(400, "{\"error\":\"code_required\",\"message\":\"AniList sent no code.\"}");
        }
        String grant = q.getOrDefault("grant_type", "authorization_code");
        if (!grant.equals("authorization_code")) {
            return new Result(400, "{\"error\":\"unsupported_grant_type\"}");
        }

        StringBuilder form = new StringBuilder();
        append(form, "grant_type", "authorization_code");
        append(form, "client_id", CLIENT_ID);
        append(form, "client_secret", CLIENT_SECRET);
        append(form, "redirect_uri", redirect.isEmpty() ? REDIRECT_URI : redirect);
        append(form, "code", code);

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        return call("POST", TOKEN_ENDPOINT, headers, form.toString());
    }

    /**
     * POST /api/anilist/graphql — one hop onto graphql.anilist.co, with the
     * caller's bearer token. The body is the JSON the app wrote; this only
     * forwards it and hands back whatever AniList said.
     */
    static Result graphql(String payload, String authorization) throws Exception {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return new Result(401, "{\"error\":\"token_required\"}");
        }
        if (payload == null || payload.trim().isEmpty()) {
            return new Result(400, "{\"error\":\"query_required\"}");
        }
        // A GraphQL document for a list page is large but bounded; anything
        // bigger is not something this app writes.
        if (payload.length() > 256 * 1024) {
            return new Result(413, "{\"error\":\"query_too_large\"}");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + authorization.substring(7).trim());
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        return call("POST", GRAPHQL, headers, payload);
    }

    /** The retry loop: a dropped call is the network, not AniList's verdict. */
    private static Result call(String method, String url, Map<String, String> headers, String body)
            throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return transport.call(method, url, headers, body);
            } catch (Exception e) {
                last = e;
                if (e instanceof java.net.UnknownHostException
                        || e instanceof java.net.ConnectException) {
                    return new Result(502, "{\"error\":\"unreachable\",\"message\":\"" + UNREACHABLE + "\"}");
                }
                Log.w("ShellAnilist", method + " failed (" + (attempt + 1) + "/" + RETRIES + "): "
                        + e.getClass().getName() + ": " + e.getMessage());
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
        if (last instanceof IOException) {
            return new Result(502, "{\"error\":\"unreachable\",\"message\":\"" + UNREACHABLE + "\"}");
        }
        throw last == null ? new IOException("AniList did not answer") : last;
    }

    private static void append(StringBuilder form, String key, String value) {
        if (form.length() > 0) form.append('&');
        try {
            form.append(java.net.URLEncoder.encode(key, "UTF-8"));
            form.append('=');
            form.append(java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8"));
        } catch (Exception impossible) {
            // UTF-8 is always present on Android; a form without this field is
            // better than a crash, and AniList will say what it did not get.
        }
    }

    /** Whether a failure is the network being down for a moment, not AniList. */
    static boolean transientNetworkError(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.UnknownHostException) return true;
            if (cause instanceof java.net.SocketTimeoutException) return true;
            if (cause instanceof java.net.ConnectException) return true;
        }
        return false;
    }
}
