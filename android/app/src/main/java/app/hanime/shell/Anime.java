package app.hanime.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The normal (non-adult) anime area — /api/anime/*, ported from
 * server/src/anime.mjs so the app answers the same routes the Node server
 * does. Two upstreams, one route family:
 *
 *   AniList (graphql.anilist.co)   catalog: search, trending, details,
 *                                  recommendations. Public API, no key.
 *   LunarX  (api.lunarx.to)        episode lists and the embed player URL for
 *                                  a given anilist id + episode. It answers
 *                                  only requests whose Origin is absent or
 *                                  lunarx.to itself, so every request passes
 *                                  through here, which spoofs the origin on
 *                                  the way out.
 *
 * The two are keyed by the same id: the id in a lunarx.to/anime/&lt;id&gt;/…
 * URL is the AniList id, so one identifier carries both the metadata and the
 * streams.
 *
 * Everything is cached in memory with a TTL, bounded the same way the Node
 * version bounds it. The response bodies are byte-for-byte the same contract
 * the client already consumes, and the JVM tests in AnimeTest stub the
 * transport — nothing in this class needs a device or the network to be
 * verified.
 */
final class Anime {

    private Anime() {}

    private static final String ANILIST = "https://graphql.anilist.co";
    private static final String LUNARX = "https://api.lunarx.to";

    /**
     * lunarx.to refuses any request that carries another site's Origin (400),
     * so the proxy speaks as the site itself. The UA matches a normal browser
     * because the API sits behind the same bot rules as the site.
     */
    private static final Map<String, String> LUNARX_HEADERS = lunarxHeaders();

    private static Map<String, String> lunarxHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Origin", "https://lunarx.to");
        h.put("Referer", "https://lunarx.to/");
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        h.put("Accept", "application/json");
        return h;
    }

    private static final int TIMEOUT_MS = 12_000;

    // ------------------------------------------------------------- queries
    // Verbatim from anime.mjs: the client consumes the shaped fields below,
    // and the query text is what decides trending-vs-search upstream.

    private static final String SEARCH_QUERY =
            "\n  query ($search: String, $page: Int) {\n"
            + "    Page(page: $page, perPage: 24) {\n"
            + "      pageInfo { currentPage hasNextPage }\n"
            + "      media(type: ANIME, search: $search, sort: [SEARCH_MATCH, POPULARITY_DESC]) {\n"
            + "        id title { romaji } episodes averageScore startDate { year }\n"
            + "        format status coverImage { large } bannerImage genres\n"
            + "      }\n"
            + "    }\n"
            + "  }";

    private static final String TRENDING_QUERY =
            "\n  query ($page: Int) {\n"
            + "    Page(page: $page, perPage: 24) {\n"
            + "      pageInfo { currentPage hasNextPage }\n"
            + "      media(type: ANIME, sort: [TRENDING_DESC, POPULARITY_DESC]) {\n"
            + "        id title { romaji } episodes averageScore startDate { year }\n"
            + "        format status coverImage { large } bannerImage genres\n"
            + "      }\n"
            + "    }\n"
            + "  }";

    private static final String DETAILS_QUERY =
            "\n  query ($id: Int) {\n"
            + "    Media(id: $id, type: ANIME) {\n"
            + "      id title { romaji } description(asHtml: false) episodes averageScore\n"
            + "      startDate { year } format status genres\n"
            + "      coverImage { large } bannerImage\n"
            + "      recommendations(perPage: 10, sort: [RATING_DESC]) {\n"
            + "        nodes { mediaRecommendation { id title { romaji } coverImage { large }\n"
            + "                        averageScore episodes startDate { year } } }\n"
            + "      }\n"
            + "    }\n"
            + "  }";

    // ---------------------------------------------------------------- cache

    private static final long TTL_SEARCH = 15 * 60_000L;
    private static final long TTL_DETAILS = 6 * 60 * 60_000L;
    private static final long TTL_EPISODES = 6 * 60 * 60_000L;
    private static final long TTL_PLAYER = 30 * 60_000L;

    static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class Entry {
        final Result result;
        final long expires;

        Entry(Result result, long expires) {
            this.result = result;
            this.expires = expires;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    /** Cleared between tests so one test's answers never seed the next. */
    static void clearCache() {
        CACHE.clear();
    }

    interface Producer {
        Result get() throws Exception;
    }

    private static Result cached(String key, long ttlMs, Producer produce) throws Exception {
        long now = System.currentTimeMillis();
        Entry hit = CACHE.get(key);
        if (hit != null && hit.expires > now) return hit.result;
        Result value = produce.get();
        // Bound the cache: a long-running app should not grow one entry per
        // episode ever looked at. Expired entries go first; past the cap the
        // whole cache clears, exactly as the Node version does.
        if (CACHE.size() > 500) {
            CACHE.entrySet().removeIf(e -> e.getValue().expires <= now);
            if (CACHE.size() > 500) CACHE.clear();
        }
        CACHE.put(key, new Entry(value, now + ttlMs));
        return value;
    }

    // ------------------------------------------------------------- transport

    /** The seam the JVM tests replace; the app uses {@link HttpTransport}. */
    interface Transport {
        String post(String url, String body, Map<String, String> headers) throws Exception;

        String get(String url, Map<String, String> headers) throws Exception;
    }

    static Transport transport = new HttpTransport();

    /** Carries the upstream status and body so callers can extract messages. */
    static final class UpstreamException extends Exception {
        final int status;
        final String body;

        UpstreamException(int status, String body) {
            super("upstream " + status);
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    static final class HttpTransport implements Transport {

        @Override
        public String post(String url, String body, Map<String, String> headers) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
                return read(conn);
            } finally {
                conn.disconnect();
            }
        }

        @Override
        public String get(String url, Map<String, String> headers) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            try {
                return read(conn);
            } finally {
                conn.disconnect();
            }
        }

        private static String read(HttpURLConnection conn) throws Exception {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code < 200 || code >= 300) throw new UpstreamException(code, text);
            return text;
        }

        private static String readAll(InputStream in) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                in.close();
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    // ---------------------------------------------------------------- fetch

    /** One AniList GraphQL call. Failure messages match anime.mjs. */
    private static JSONObject anilist(String query, JSONObject variables) throws Exception {
        JSONObject payload = new JSONObject().put("query", query).put("variables", variables);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("Accept", "application/json");

        String text;
        try {
            text = transport.post(ANILIST, payload.toString(), headers);
        } catch (UpstreamException e) {
            throw new Exception("anilist: " + anilistError(e.body, "status " + e.status));
        }
        JSONObject body = new JSONObject(text);
        if (body.has("errors")) {
            throw new Exception("anilist: " + anilistError(text, "status 200"));
        }
        JSONObject data = body.optJSONObject("data");
        if (data == null) throw new Exception("anilist: no data");
        return data;
    }

    /** First GraphQL error message, or the fallback. */
    private static String anilistError(String bodyText, String fallback) {
        try {
            JSONArray errors = new JSONObject(bodyText).optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                String msg = errors.getJSONObject(0).optString("message", "");
                if (!msg.isEmpty()) return msg;
            }
        } catch (Exception ignored) {
            // Not JSON — the status is the only message there is.
        }
        return fallback;
    }

    /** One LunarX GET. Non-2xx reads as an upstream failure, like jget(). */
    private static JSONObject jget(String url, Map<String, String> headers) throws Exception {
        try {
            return new JSONObject(transport.get(url, headers));
        } catch (UpstreamException e) {
            String path;
            try {
                path = new URL(url).getPath();
            } catch (Exception bad) {
                path = url;
            }
            throw new Exception("upstream " + e.status + " for " + path);
        }
    }

    // -------------------------------------------------------------- shaping

    private static Object val(JSONObject o, String key) throws JSONException {
        return o.has(key) && !o.isNull(key) ? o.get(key) : JSONObject.NULL;
    }

    private static Object field(JSONObject o, String... path) throws JSONException {
        JSONObject cur = o;
        for (int i = 0; i < path.length - 1 && cur != null; i++) {
            cur = cur.optJSONObject(path[i]);
        }
        if (cur == null) return JSONObject.NULL;
        return val(cur, path[path.length - 1]);
    }

    static JSONObject shapeCard(JSONObject m) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", val(m, "id"));
        Object romaji = field(m, "title", "romaji");
        out.put("title", romaji == JSONObject.NULL ? "Unknown" : romaji);
        out.put("year", field(m, "startDate", "year"));
        out.put("eps", val(m, "episodes"));
        out.put("score", val(m, "averageScore"));
        out.put("format", val(m, "format"));
        out.put("status", val(m, "status"));
        out.put("cover", field(m, "coverImage", "large"));
        out.put("banner", val(m, "bannerImage"));
        Object genres = val(m, "genres");
        out.put("genres", genres == JSONObject.NULL ? new JSONArray() : genres);
        return out;
    }

    /**
     * Upstream descriptions are HTML; the client renders text, never markup.
     * Line breaks survive as newlines, every other tag becomes a space, and
     * runs of horizontal space collapse — the same three passes anime.mjs
     * makes.
     */
    static String flattenDescription(String html) {
        if (html == null) return "";
        return html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    static JSONObject shapeDetails(JSONObject m) throws JSONException {
        JSONObject out = shapeCard(m);
        out.put("description", flattenDescription(m.optString("description", "")));
        JSONArray recs = new JSONArray();
        JSONObject recommendations = m.optJSONObject("recommendations");
        JSONArray nodes = recommendations == null ? null : recommendations.optJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                // AniList names two fields per recommendation: `media` is the
                // title you are already looking at, `mediaRecommendation` is
                // the suggested one. Selecting `media` fills the rail with the
                // watched anime itself; the watched id is dropped as well if
                // it ever arrives.
                JSONObject rec = node == null ? null : node.optJSONObject("mediaRecommendation");
                if (rec == null) continue;
                if (rec.opt("id") != null && rec.opt("id").equals(m.opt("id"))) continue;
                recs.put(shapeCard(rec));
            }
        }
        out.put("recommendations", recs);
        return out;
    }

    static JSONObject shapeEpisode(JSONObject e) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("number", val(e, "number"));
        Object title = val(e, "title");
        out.put("title", title == JSONObject.NULL ? "" : title);
        Object description = val(e, "description");
        out.put("description", description == JSONObject.NULL ? "" : description);
        out.put("img", val(e, "img"));
        out.put("airDate", val(e, "airDate"));
        Object length = val(e, "length");
        if (length == JSONObject.NULL) length = val(e, "runtime");
        out.put("length", length);
        out.put("hasSub", e.optBoolean("hasSub", false));
        out.put("hasDub", e.optBoolean("hasDub", false));
        return out;
    }

    // ------------------------------------------------------------- handlers

    /** GET /api/anime/search?q=&page= — AniList search, or trending when empty. */
    private static Result search(Map<String, String> q) throws Exception {
        String needle = q.get("q") == null ? "" : q.get("q").trim();
        int page = Math.max(1, intPipeZero(q.get("page")));

        String key = "s:" + needle + ":" + page;
        return cached(key, TTL_SEARCH, () -> {
            String query = TRENDING_QUERY;
            JSONObject vars = new JSONObject().put("page", page);
            if (!needle.isEmpty()) {
                query = SEARCH_QUERY;
                vars.put("search", needle);
            }
            JSONObject data = anilist(query, vars);
            JSONObject pageObj = data.optJSONObject("Page");
            JSONObject pageInfo = pageObj == null ? null : pageObj.optJSONObject("pageInfo");
            JSONArray media = pageObj == null ? null : pageObj.optJSONArray("media");

            JSONObject out = new JSONObject();
            out.put("q", needle);
            out.put("page", pageInfo == null ? page : pageInfo.optInt("currentPage", page));
            out.put("hasNext", pageInfo != null && pageInfo.optBoolean("hasNextPage", false));
            JSONArray items = new JSONArray();
            if (media != null) {
                for (int i = 0; i < media.length(); i++) {
                    JSONObject m = media.optJSONObject(i);
                    if (m != null) items.put(shapeCard(m));
                }
            }
            out.put("items", items);
            return new Result(200, out.toString());
        });
    }

    /** GET /api/anime/:id — one title with its description and recommendations. */
    private static Result details(String id) throws Exception {
        return cached("d:" + id, TTL_DETAILS, () -> {
            JSONObject data = anilist(DETAILS_QUERY, new JSONObject().put("id", Integer.parseInt(id)));
            JSONObject media = data.optJSONObject("Media");
            if (media == null) {
                return new Result(404, new JSONObject()
                        .put("error", "not_found").put("id", id).toString());
            }
            return new Result(200, new JSONObject()
                    .put("details", shapeDetails(media)).toString());
        });
    }

    /** GET /api/anime/:id/episodes — LunarX's episode list for the season. */
    private static Result episodes(String id) throws Exception {
        return cached("e:" + id, TTL_EPISODES, () -> {
            JSONObject body = jget(LUNARX + "/api/animes/v2/episodes?id=" + id, LUNARX_HEADERS);
            JSONArray raw = body.optJSONArray("data");
            JSONArray out = new JSONArray();
            if (raw != null) {
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject e = raw.optJSONObject(i);
                    if (e != null) out.put(shapeEpisode(e));
                }
            }
            return new Result(200, new JSONObject().put("id", id).put("data", out).toString());
        });
    }

    /** GET /api/anime/:id/player?ep=N — the embed URL LunarX itself would use. */
    private static Result player(String id, String epParam) throws Exception {
        int n = Math.max(1, intPipeZero(epParam == null ? "1" : epParam));
        return cached("p:" + id + ":" + n, TTL_PLAYER, () -> {
            JSONObject body = jget(
                    LUNARX + "/api/3rdprovider?anilist=" + id + "&episode=" + n, LUNARX_HEADERS);
            JSONArray raw = body.optJSONArray("data");
            JSONArray sources = new JSONArray();
            if (raw != null) {
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject row = raw.optJSONObject(i);
                    if (row == null) continue;
                    String url = row.optString("player_url", "");
                    if (url.isEmpty()) continue;
                    JSONObject src = new JSONObject().put("url", url);
                    String label = row.optString("server", "");
                    src.put("label", label.isEmpty() ? "auto" : label);
                    sources.put(src);
                }
            }
            JSONObject out = new JSONObject().put("id", id).put("ep", n);
            if (sources.length() == 0) {
                out.put("error", "no_player");
                return new Result(404, out.toString());
            }
            out.put("sources", sources);
            return new Result(200, out.toString());
        });
    }

    /**
     * One entry point for the whole /api/anime family, called from
     * ApiServer with the path and the first value of each query key.
     * A path that matches no route answers 404 without touching the network.
     */
    static Result handle(String path, Map<String, String> query) {
        try {
            if (path.equals("/api/anime/search")) return search(query);
            if (path.startsWith("/api/anime/")) {
                String rest = path.substring("/api/anime/".length());
                if (rest.endsWith("/episodes")) {
                    String id = rest.substring(0, rest.length() - "/episodes".length());
                    if (isDigits(id)) return episodes(id);
                } else if (rest.endsWith("/player")) {
                    String id = rest.substring(0, rest.length() - "/player".length());
                    if (isDigits(id)) return player(id, query.get("ep"));
                } else if (isDigits(rest)) {
                    return details(rest);
                }
            }
            return new Result(404, new JSONObject()
                    .put("error", "not_found").put("pathname", path).toString());
        } catch (Exception e) {
            // Upstream trouble is a 502 with the reason attached, so the
            // client's note tells the reader what failed instead of blanking.
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                return new Result(502, new JSONObject()
                        .put("error", "upstream").put("message", message).toString());
            } catch (JSONException unwritable) {
                // A fixed shape of two strings cannot fail to serialize.
                return new Result(502, "{\"error\":\"upstream\"}");
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** JavaScript's `Number(x) | 0`: floats truncate, junk becomes 0. */
    private static int intPipeZero(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) return 0;
            return (int) d;
        } catch (Exception e) {
            return 0;
        }
    }
}
