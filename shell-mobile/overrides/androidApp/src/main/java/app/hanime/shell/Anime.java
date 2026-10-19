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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    // Mirrors catalogQuery() in anime.mjs: the client consumes the shaped
    // fields below, the filters travel as GraphQL variables, and only the
    // variables used are declared — AniList rejects a query that declares one
    // it never reads. The sort says which of search-vs-trending this is.

    private static final String[] GENRES = {"Action", "Adventure", "Comedy", "Drama", "Ecchi",
            "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological",
            "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"};
    private static final String[] FORMATS = {"TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC"};
    private static final String[] STATUSES = {"FINISHED", "RELEASED", "RELEASING",
            "NOT_YET_RELEASED", "CANCELLED", "HIATUS"};

    /** The canonical spelling of a known value, or "" when it is not known. */
    private static String pick(String value, String[] allowed) {
        String raw = value == null ? "" : value.trim();
        for (String a : allowed) {
            if (a.equalsIgnoreCase(raw)) return a;
        }
        return "";
    }

    /**
     * The catalog query, assembled from the filters actually in play. Filters
     * are also written into {@code variables} here, so the caller builds the
     * payload in one place. An unknown filter is already "" by then and never
     * reaches the query.
     */
    static String catalogQuery(String needle, String genre, String format, String status,
                               JSONObject variables) throws JSONException {
        String decls = "$page: Int";
        String args = "type: ANIME";
        if (!needle.isEmpty()) {
            decls += ", $search: String";
            args += ", search: $search";
            variables.put("search", needle);
        }
        if (!genre.isEmpty()) {
            decls += ", $genre: String";
            args += ", genre: $genre";
            variables.put("genre", genre);
        }
        if (!format.isEmpty()) {
            decls += ", $format: MediaFormat";
            args += ", format: $format";
            variables.put("format", format);
        }
        if (!status.isEmpty()) {
            decls += ", $status: MediaStatus";
            args += ", status: $status";
            variables.put("status", status);
        }
        args += ", sort: [" + (needle.isEmpty() ? "TRENDING_DESC" : "SEARCH_MATCH")
                + ", POPULARITY_DESC]";

        return "\n  query (" + decls + ") {\n"
                + "    Page(page: $page, perPage: 24) {\n"
                + "      pageInfo { currentPage hasNextPage }\n"
                + "      media(" + args + ") {\n"
                + "        id title { romaji } episodes averageScore startDate { year }\n"
                + "        format status coverImage { large } bannerImage genres\n"
                + "      }\n"
                + "    }\n"
                + "  }";
    }

    private static final String DETAILS_QUERY =
            "\n  query ($id: Int) {\n"
            + "    Media(id: $id, type: ANIME) {\n"
            + "      id idMal title { romaji } description(asHtml: false) episodes averageScore\n"
            + "      startDate { year } format status genres\n"
            + "      coverImage { large } bannerImage\n"
            + "      recommendations(perPage: 10, sort: [RATING_DESC]) {\n"
            + "        nodes { mediaRecommendation { id title { romaji } coverImage { large }\n"
            + "                        averageScore episodes startDate { year } } }\n"
            + "      }\n"
            + "    }\n"
            + "  }";

    // A series is not one entry in AniList — every season is its own media
    // record, linked to the last by SEQUEL/PREQUEL relations, which is why a
    // grid of "anime" reads like a list of unrelated titles. The node's own
    // `type` has to be ANIME for the same reason: AniList also links the
    // adaptation (the manga) and the source (the novel), and following those
    // would stitch a show to its light novel as if it were the next season.
    // JVM twin of SEASON_REL / SEASONS_QUERY in anime.mjs.
    private static final String SEASONS_QUERY =
            "\n  query ($id: Int) {\n"
            + "    Media(id: $id, type: ANIME) {\n"
            + "      id title { romaji } episodes startDate { year } format coverImage { large }\n"
            + "      relations { edges { relationType node {\n"
            + "        id type title { romaji } episodes startDate { year } format coverImage { large }\n"
            + "      } } }\n"
            + "    }\n"
            + "  }";

    /** relationType -> the side of the run it points at. */
    private static final Map<String, String> SEASON_REL = seasonRel();

    private static Map<String, String> seasonRel() {
        Map<String, String> rel = new HashMap<>();
        rel.put("SEQUEL", "sequel");
        rel.put("PREQUEL", "prequel");
        return rel;
    }

    // The walk's budget: one AniList query per hop, forwards and backwards
    // from the title asked for. Ten round trips reach any real run of seasons,
    // and a broken or absurd chain stops instead of circling.
    private static final int MAX_SEASON_FETCHES = 10;

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
        // AniList carries MAL's own id for the title — one field, and the
        // tracking row never has to search by name for the anime area.
        Object malId = m.opt("idMal");
        out.put("malId", malId == null || malId == JSONObject.NULL ? JSONObject.NULL : malId);
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

    /**
     * GET /api/anime/search?q=&page=&genre=&format=&status=
     * AniList search, or trending when empty; the filters narrow either.
     */
    private static Result search(Map<String, String> q) throws Exception {
        String needle = q.get("q") == null ? "" : q.get("q").trim();
        int page = Math.max(1, intPipeZero(q.get("page")));
        String genre = pick(q.get("genre"), GENRES);
        String format = pick(q.get("format"), FORMATS);
        String status = pick(q.get("status"), STATUSES);

        String key = "s:" + needle + ":" + page + ":" + genre + ":" + format + ":" + status;
        return cached(key, TTL_SEARCH, () -> {
            JSONObject vars = new JSONObject().put("page", page);
            String query = catalogQuery(needle, genre, format, status, vars);
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

    /** GET /api/anime/:id/seasons — the series this title belongs to. */
    private static Result seasons(String id) throws Exception {
        final String key = "chain:" + id;
        final boolean[] partial = { false };
        Result out = cached(key, TTL_DETAILS, () -> {
            JSONArray data = seasonChain(Integer.parseInt(id), partial);
            return new Result(200, new JSONObject()
                    .put("id", Integer.parseInt(id))
                    .put("data", data).toString());
        });
        // A chain cut short by a failed hop is not an answer worth keeping: the
        // strip would stay half-grown for the whole TTL over one blip, and the
        // next visit would show the same half. Only a chain that ended normally
        // is cached — which is why `partial` travels with the walk. (A hop that
        // throws never reaches the cache at all: `cached` writes after the
        // producer returns, which is the Java answer to the promise-in-cache
        // problem the Node twin deletes its key over.)
        if (partial[0]) CACHE.remove(key);
        return out;
    }

    /**
     * One series, every season of it, in air order: backwards from the title
     * asked for until a season has no prequel left (that is the first one),
     * then forwards from there following SEQUEL links only. JVM twin of
     * seasonChain() in anime.mjs — same bounds, same order, same shapes.
     */
    private static JSONArray seasonChain(int startId, boolean[] partial) throws Exception {
        Map<Integer, JSONObject> nodes = new LinkedHashMap<>();   // id -> media
        Map<Integer, String[]> links = new LinkedHashMap<>();     // id -> [prequel, sequel]
        int[] fetches = { 0 };

        JSONObject first = loadSeasonNode(startId, nodes, links, fetches, partial);
        if (first == null) return new JSONArray();

        int head = startId;
        for (;;) {
            String[] link = links.get(head);
            String prev = link == null ? null : link[0];
            if (prev == null || nodes.containsKey(Integer.parseInt(prev))) break;
            int pid = Integer.parseInt(prev);
            if (loadSeasonNode(pid, nodes, links, fetches, partial) == null) break;
            head = pid;
        }

        List<Integer> order = new ArrayList<>();
        order.add(head);
        int cursor = head;
        for (;;) {
            String[] link = links.get(cursor);
            String next = link == null ? null : link[1];
            if (next == null) break;
            int nid = Integer.parseInt(next);
            if (order.contains(nid)) break;   // a cycle, not a run
            JSONObject known = nodes.get(nid);
            JSONObject m = known != null ? known
                    : loadSeasonNode(nid, nodes, links, fetches, partial);
            if (m == null) break;
            order.add(nid);
            cursor = nid;
        }

        JSONArray data = new JSONArray();
        for (int id : order) {
            JSONObject node = nodes.get(id);
            if (node == null) continue;
            data.put(shapeCard(node).put("current", id == startId));
        }
        return data;
    }

    /**
     * One hop: fetch a media record with its relations, remember the record
     * and the two season links it points at. Null when there is nothing to
     * follow — no such id, or the budget is spent.
     */
    private static JSONObject loadSeasonNode(int id, Map<Integer, JSONObject> nodes,
            Map<Integer, String[]> links, int[] fetches, boolean[] partial) throws Exception {
        if (fetches[0] >= MAX_SEASON_FETCHES) return null;
        try {
            Result hit = cached("s:" + id, TTL_DETAILS, () -> {
                JSONObject data = anilist(SEASONS_QUERY, new JSONObject().put("id", id));
                return new Result(200, data == null ? "{}" : data.toString());
            });
            JSONObject media = new JSONObject(hit.body).optJSONObject("Media");
            if (media == null || !media.has("id")) return null;
            fetches[0]++;
            int mid = media.getInt("id");
            nodes.put(mid, media);

            String[] link = new String[]{ null, null };   // [prequel, sequel]
            JSONObject relations = media.optJSONObject("relations");
            JSONArray edges = relations == null ? null : relations.optJSONArray("edges");
            if (edges != null) {
                for (int i = 0; i < edges.length(); i++) {
                    JSONObject edge = edges.optJSONObject(i);
                    if (edge == null) continue;
                    String kind = SEASON_REL.get(edge.optString("relationType", ""));
                    JSONObject node = edge.optJSONObject("node");
                    if (kind == null || node == null || !node.has("id")) continue;
                    if (!"ANIME".equals(node.optString("type", ""))) continue;
                    if ("prequel".equals(kind)) link[0] = String.valueOf(node.getInt("id"));
                    else link[1] = String.valueOf(node.getInt("id"));
                }
            }
            links.put(mid, link);
            return media;
        } catch (Exception e) {
            // The failure is not an answer: dropping the key stops a blip from
            // becoming this hop's cached result for the whole TTL.
            CACHE.remove("s:" + id);
            partial[0] = true;
            if (nodes.isEmpty()) throw e;   // nothing fetched yet: the caller's error
            return null;                    // mid-chain: keep what was gathered
        }
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
                if (rest.endsWith("/seasons")) {
                    String id = rest.substring(0, rest.length() - "/seasons".length());
                    if (isDigits(id)) return seasons(id);
                } else if (rest.endsWith("/episodes")) {
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
