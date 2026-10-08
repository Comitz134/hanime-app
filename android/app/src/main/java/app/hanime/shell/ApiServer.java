package app.hanime.shell;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole backend, running inside the app.
 *
 * The WebView is pointed at {@link #ORIGIN} and every request it makes is
 * answered here from shouldInterceptRequest. That does three things at once:
 *
 *   - it gives the page an origin of {@code https://hanime.tv}, which is the
 *     exact origin the upstream auth services send in
 *     `access-control-allow-origin`, so nothing has to fight CORS;
 *   - it lets us serve the bundled client instead of a server's copy;
 *   - it lets the API be answered by Java — catalogue, tags, brands, details —
 *     so no Node process has to be running anywhere for the app to work.
 *
 * Anything outside our origin (the web font, the image CDN) returns null so the
 * WebView fetches it normally.
 */
final class ApiServer {

    private static final String TAG = "ShellApi";

    /** The page's origin. Changing it changes the CORS contract upstream sees. */
    static final String ORIGIN = "https://hanime.tv";

    private final Context context;
    private final AssetManager assets;

    /** Covers of the titles in the library, kept on disk and served below. */
    private final CoverCache covers;

    ApiServer(Context context) {
        this.context = context.getApplicationContext();
        this.assets = context.getAssets();
        this.covers = new CoverCache(this.context);
    }

    CoverCache covers() {
        return covers;
    }

    /** Returns null to let the WebView perform the request itself. */
    WebResourceResponse handle(WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (!url.startsWith(ORIGIN + "/") && !url.equals(ORIGIN)) return null;

        String path = request.getUrl().getPath();
        if (path == null || path.isEmpty()) path = "/";
        Map<String, List<String>> query = parseQuery(request.getUrl().getQuery());

        try {
            if (path.equals("/relay")) return HlsRelay.handle(firsts(query));
            if (path.startsWith("/api/")) return api(path, query);
            // Library covers, answered from the app's own storage. Checked
            // before assets so the two can never be confused: nothing under
            // /covers/ is ever served out of the APK.
            if (path.startsWith("/covers/")) return covers.serve(path);
            return asset(path);
        } catch (Exception e) {
            // Never let a handler exception take the WebView down with it: an
            // error body tells the client *why* it is looking at an empty grid.
            Log.w(TAG, "handler failed for " + path, e);
            return json(500, "{\"error\":\"internal\",\"message\":\"" + safe(e) + "\"}");
        }
    }

    // ------------------------------------------------------------------ api

    private WebResourceResponse api(String path, Map<String, List<String>> q) throws Exception {
        if (path.equals("/api/videos")) return json(200, Catalog.videos(q));
        if (path.equals("/api/tags")) return json(200, Catalog.tags());
        if (path.equals("/api/brands")) return json(200, Catalog.brands());

        // /api/videos/:slug  and  /api/videos/:slug/sources
        if (path.startsWith("/api/videos/")) {
            String rest = path.substring("/api/videos/".length());
            if (rest.endsWith("/sources")) {
                String slug = rest.substring(0, rest.length() - "/sources".length());
                try {
                    return json(200, Streams.resolve(slug));
                } catch (Exception e) {
                    Log.w(TAG, "stream resolution failed", e);
                    return json(502, "{\"error\":\"upstream\",\"slug\":\"" + slug
                            + "\",\"message\":\"" + safe(e) + "\"}");
                }
            }
            String body = Catalog.video(rest);
            if (body == null) return json(404, "{\"error\":\"not_found\",\"slug\":\"" + rest + "\"}");
            return json(200, body);
        }

        // The normal-anime family: AniList catalog + LunarX episodes/player,
        // the same routes the Node server serves, answered here so the app
        // needs no proxy. Anime.handle answers every /api/anime/* path itself.
        if (path.startsWith("/api/anime/")) {
            Anime.Result r = Anime.handle(path, firsts(q));
            return json(r.status, r.body);
        }

        if (path.equals("/api/session")) {
            // No account cookie pasted yet; the client renders its connect card.
            return json(200, "{\"configured\":false,\"playlists\":[],\"stats\":{}}");
        }
        if (path.startsWith("/api/playlists")) {
            return json(200, "{\"configured\":false,\"playlists\":[],\"stats\":{}}");
        }
        if (path.startsWith("/api/public/playlists")) {
            return json(200, PublicPlaylists.serve(path, q, context));
        }
        if (path.equals("/api/app/version")) {
            return json(404, "{\"ok\":false,\"configured\":false}");
        }

        // Unknown API paths answer 404 rather than falling through to the
        // network: a leak here would silently reintroduce a proxy dependency.
        return json(404, "{\"error\":\"not_found\",\"path\":\"" + path + "\"}");
    }

    // --------------------------------------------------------------- assets

    /**
     * Parse a query string the way URLSearchParams does: repeated keys collect,
     * `+` means space, everything else is percent-decoded.
     */
    private static Map<String, List<String>> parseQuery(String query) {
        Map<String, List<String>> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            key = decode(key);
            value = decode(value);
            List<String> bucket = out.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                out.put(key, bucket);
            }
            bucket.add(value);
        }
        return out;
    }

    /** Collapse a multi-value query to the first value of each key. */
    private static Map<String, String> firsts(Map<String, List<String>> q) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : q.entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue().get(0));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Serve a bundled file. Unknown paths fall back to index.html so the
     * client's routes still load on a cold start.
     */
    private WebResourceResponse asset(String path) throws Exception {
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.isEmpty()) name = "index.html";

        InputStream in;
        try {
            in = assets.open(name);
        } catch (FileNotFoundException e) {
            if (name.equals("index.html")) throw e;
            in = assets.open("index.html");
            name = "index.html";
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("access-control-allow-origin", "*");
        return new WebResourceResponse(mimeFor(name), "UTF-8", 200, "OK", headers, in);
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".webmanifest")) return "application/manifest+json";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- http

    static WebResourceResponse json(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json; charset=utf-8");
        headers.put("content-length", String.valueOf(bytes.length));
        headers.put("cache-control", "no-store");
        headers.put("access-control-allow-origin", "*");
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found"
                : status == 502 ? "Bad Gateway" : status == 501 ? "Not Implemented"
                : status == 500 ? "Internal Server Error" : "Error";
        return new WebResourceResponse("application/json", "UTF-8",
                status, reason, headers, new ByteArrayInputStream(bytes));
    }

    private static String safe(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        return m.replace("\\", "").replace("\"", "'").replace("\n", " ");
    }
}
