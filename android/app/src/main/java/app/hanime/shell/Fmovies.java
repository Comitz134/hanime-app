package app.hanime.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The films &amp; series area — /api/fmovies/*, ported from
 * server/src/fmovies.mjs so the app answers the same routes the Node server
 * does. One upstream, two halves:
 *
 *   f-movies.org/api/search   the TMDB-backed catalog: exact JSON search,
 *                             its /movies and /tv-series pages for the
 *                             browse shelves.
 *   f-movies.org pages        static detail and episode HTML: a JSON-LD
 *                             block, one anchor per season and episode.
 *
 * Playback is the site's own player page, and the doors it offers are read
 * from the site rather than hard-coded: /watch/index.html names its servers
 * (`sources = [...]`: embos, vidcore, vidapi) as URL templates filled with the
 * numeric id in the slug, the season and the episode. Those hosts rotate — the
 * three recorded here first (vidsrc.cc, vidsrc.xyz, vidapi.to) all died within
 * a day, and every one of them is a black frame in the player — so the names,
 * and whether each door answers, are asked of the site, with the built-in list
 * as the fallback for the day its page cannot be read.
 *
 * Everything is parsed from the site's real markup shape (verified against
 * live pages on 2026-10-10), cached in memory with the same TTLs as the Node
 * twin, and answer-for-answer identical: the JVM tests in FmoviesTest stub the
 * transport and the knock on each door — nothing here needs a device or the
 * network to be verified.
 */
final class Fmovies {

    private Fmovies() {}

    private static final String SITE = "https://www.f-movies.org";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/131.0.0.0 Safari/537.36";
    private static final String TEXT_HTML = "text/html,application/xhtml+xml";
    private static final int SEARCH_PAGE_SIZE = 20;   // upstream's fixed limit

    // ------------------------------------------------------------- cache

    private static final long TTL_SEARCH = 15 * 60_000L;     // results move with the catalog
    private static final long TTL_CATALOG = 15 * 60_000L;    // the listing pages
    private static final long TTL_DETAILS = 6 * 60 * 60_000L;   // near-static
    private static final long TTL_EPISODES = 6 * 60 * 60_000L;
    private static final long TTL_SERVERS = 30 * 60_000L;        // the doors rotate, but not every minute
    // A player page that could not be read is asked again soon: a blip must
    // not freeze the fallback list in place for half an hour.
    private static final long TTL_SERVERS_RETRY = 2 * 60_000L;
    private static final long TTL_HEALTH = 10 * 60_000L;         // a host that is down is down for a while

    // The doors to fall back on when the site's player page cannot be read,
    // verified against the live page on 2026-10-10 — in our own order, for the
    // reason DOOR_ORDER carries.
    private static final String WATCH_PAGE =
            "/watch/index.html?type=tv&id=1&server=1&season=1&episode=1";
    private static final List<Door> FALLBACK_DOORS = List.of(
            new Door("https://vidcore.net/movie/{id}",
                    "https://vidcore.net/tv/{id}/{season}/{episode}"),
            new Door("https://vidapi.xyz/embed/movie/{id}",
                    "https://vidapi.xyz/embed/tv/{id}/{season}/{episode}"),
            new Door("https://embos.top/movie/?mid={id}",
                    "https://embos.top/tv/?mid={id}&s={season}&e={episode}"));

    /**
     * Which door leads, when the site's own page does not get to say.
     *
     * The site lists embos first, and embos is not a player: it is a picker
     * page that frames one of several providers of its own choosing, and it
     * does not say which. On the phone it landed on one that answers 404, twice
     * in a row, for the same episode that plays through the other two doors — a
     * door that answers our knock and still leaves the reader with nothing. The
     * two that render their own player therefore lead, and the picker comes
     * last. A door the site names that is not in this list keeps its own
     * relative order, after these; a list that names none of them keeps the
     * site's order entirely. (The Node twin orders by the same table.)
     */
    private static final List<String> DOOR_ORDER = List.of("vidcore", "vidapi", "embos");

    private static int doorRank(String id) {
        int i = DOOR_ORDER.indexOf(id);
        return i == -1 ? DOOR_ORDER.size() : i;
    }

    static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class Entry {
        final String value;
        final long expires;

        Entry(String value, long expires) {
            this.value = value;
            this.expires = expires;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    /** Cleared between tests so one test's answers never seed the next. */
    static void clearCache() {
        CACHE.clear();
        doorCache = null;
        doorCacheExpires = 0;
    }

    interface Producer {
        String get() throws Exception;
    }

    private static String cached(String key, long ttlMs, Producer produce) throws Exception {
        long now = System.currentTimeMillis();
        Entry hit = CACHE.get(key);
        if (hit != null && hit.expires > now) return hit.value;
        // A failure is not an answer: `produce` reaches the cache only when it
        // returns, so one upstream blip costs a single retry instead of the
        // whole TTL. (The Node twin deletes its key on rejection for the same
        // reason — this is the same discipline without promises.)
        String value = produce.get();
        if (CACHE.size() > 500) {
            CACHE.entrySet().removeIf(e -> e.getValue().expires <= now);
            if (CACHE.size() > 500) CACHE.clear();
        }
        CACHE.put(key, new Entry(value, now + ttlMs));
        return value;
    }

    // --------------------------------------------------------- transport

    /** The seam the JVM tests replace; the app shares Anime's HTTP transport. */
    static Anime.Transport transport = new Anime.HttpTransport();

    /** An upstream 404: handle() answers 404 with the reason, like the Node twin. */
    static final class NotFound extends Exception {
        NotFound() {
            super("f-movies: status 404");
        }
    }

    private static String get(String path, String accept) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BROWSER_UA);
        headers.put("Accept", accept);
        headers.put("Accept-Language", "en-US,en;q=0.9");
        Anime.UpstreamException up;
        try {
            return transport.get(SITE + path, headers);
        } catch (Anime.UpstreamException e) {
            up = e;
        } catch (Exception e) {
            throw new Exception("f-movies: " + e.getMessage());
        }
        if (up.status == 404) throw new NotFound();
        throw new Exception("f-movies: status " + up.status);
    }

    // ----------------------------------------------------------- parsing

    static String decode(String s) {
        if (s == null) return "";
        String out = s
                .replace("&amp;", "&").replace("&#38;", "&")
                .replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        out = replaceNumeric(out, "#(\\d+);", 10);
        out = replaceNumeric(out, "#x([0-9a-f]+);", 16);
        return out;
    }

    /** `&#NN;` / `&#xNN;` → the character, in the order the Node twin does. */
    private static String replaceNumeric(String s, String regex, int radix) {
        Matcher m = Pattern.compile(regex).matcher(s);
        if (!m.find()) return s;
        StringBuffer sb = new StringBuffer();
        do {
            int cp = Integer.parseInt(m.group(1), radix);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) cp)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * The JSON-LD block the detail page ships (a Movie or TVSeries node,
     * possibly inside an @graph or a top-level array). Null when absent or
     * malformed — a missing field, never a crash.
     */
    static JSONObject parseJsonLd(String html) {
        Matcher scripts = Pattern
                .compile("<script type=\"application/ld\\+json\">([\\s\\S]*?)</script>")
                .matcher(html);
        while (scripts.find()) {
            String text = scripts.group(1).trim();
            try {
                List<JSONObject> nodes = new ArrayList<>();
                if (text.startsWith("[")) {
                    JSONArray arr = new JSONArray(text);
                    for (int i = 0; i < arr.length(); i++) nodes.add(arr.optJSONObject(i));
                } else {
                    JSONObject obj = new JSONObject(text);
                    JSONArray graph = obj.optJSONArray("@graph");
                    if (graph != null) {
                        for (int i = 0; i < graph.length(); i++) nodes.add(graph.optJSONObject(i));
                    } else {
                        nodes.add(obj);
                    }
                }
                for (JSONObject n : nodes) {
                    if (n == null) continue;
                    String t = n.optString("@type", "");
                    if ("Movie".equals(t) || "TVSeries".equals(t)) return n;
                }
            } catch (Exception ignored) {
                // Not the block we want.
            }
        }
        return null;
    }

    /** `?season=N&episode=…` occurrences → the season numbers the page offers. */
    static List<Integer> parseSeasons(String html) {
        Set<Integer> set = new TreeSet<>();
        Matcher m = Pattern.compile("\\?season=(\\d+)(?:&#38;|&amp;|&)episode=\\d+").matcher(html);
        while (m.find()) set.add(Integer.parseInt(m.group(1)));
        return new ArrayList<>(set);
    }

    /**
     * Episode anchors for one season, in number order, with entity-decoded
     * titles. Episode 1 appears twice in the page — once as the active
     * season's jump button in the nav (no title) and once as the episode row
     * itself (titled) — so a titled occurrence always wins: a duplicate never
     * overwrites a title, and never shadows a row that has one.
     */
    static List<JSONObject> parseEpisodes(String html, int season) throws JSONException {
        Map<Integer, String> titles = new LinkedHashMap<>();   // n -> title, null when absent
        Matcher m = Pattern.compile(
                "\\?season=" + season + "(?:&#38;|&amp;|&)episode=(\\d+)(?:&#38;|&amp;|&)?[^\"]*\"[^>]*")
                .matcher(html);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            Matcher t = Pattern.compile("title=\"([^\"]*)\"").matcher(m.group());
            String title = t.find() ? decode(t.group(1)) : null;
            if (titles.containsKey(n) && (titles.get(n) != null || title == null)) continue;
            titles.put(n, title);
        }
        List<Integer> order = new ArrayList<>(titles.keySet());
        Collections.sort(order);
        List<JSONObject> out = new ArrayList<>();
        for (int n : order) {
            String title = titles.get(n);
            JSONObject ep = new JSONObject().put("n", n);
            ep.put("title", title == null ? JSONObject.NULL : title);
            out.add(ep);
        }
        return out;
    }

    /**
     * Cards in a listing page. Each card is an &lt;a href="/movie/slug" …&gt;
     * block whose text row carries the ★ score and the year; windows run from
     * one detail link to the next (the last capped at +4000 chars), so the
     * rating row — after the card article, still inside the anchor — belongs
     * to the right card.
     */
    static List<JSONObject> parseCards(String html) throws JSONException {
        Matcher links = Pattern
                .compile("<a href=\"/(movie|tv)/([a-z0-9-]+-\\d+)\"[^>]*>")
                .matcher(html);
        List<String[]> seen = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        while (links.find()) {
            seen.add(new String[]{ links.group(1), links.group(2) });
            starts.add(links.start());
        }
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < seen.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < seen.size() ? starts.get(i + 1) : Math.min(html.length(), from + 4000);
            String window = html.substring(from, to);
            String[] link = seen.get(i);

            Matcher alt = Pattern.compile("alt=\"([^\"]*)\"").matcher(window);
            Matcher img = Pattern.compile("src=\"(https://image\\.tmdb\\.org[^\"]+)\"").matcher(window);
            // The score sits right after the star in the title line (★ 5.9)
            // but inside the *next* span in the meta row (★</span><span>5.9)
            // — both shapes occur on the same page, so accept either.
            Matcher star = Pattern
                    .compile("★(?:</span>\\s*(?:<span[^>]*>)?)?\\s*([\\d.]+)").matcher(window);
            Matcher year = Pattern.compile(">·</span>\\s*<span>((?:19|20)\\d{2})</span>").matcher(window);

            JSONObject item = new JSONObject();
            item.put("type", link[0]);
            item.put("slug", link[1]);
            if (alt.find()) {
                item.put("title", decode(alt.group(1)));
            } else {
                item.put("title", link[1].replaceFirst("-\\d+$", "").replace('-', ' '));
            }
            item.put("year", year.find() ? Integer.parseInt(year.group(1)) : JSONObject.NULL);
            item.put("score", star.find() ? Double.parseDouble(star.group(1)) : JSONObject.NULL);
            item.put("poster", img.find() ? img.group(1) : JSONObject.NULL);
            items.add(item);
        }
        return items;
    }

    /** TMDB-proxy search row → the same card shape as parseCards. */
    static JSONObject shapeSearchItem(JSONObject raw) throws JSONException {
        Matcher m = Pattern.compile("^/(movie|tv)/([a-z0-9-]+-\\d+)$").matcher(raw.optString("href", ""));
        if (!m.find()) return null;                             // anything else is not a title
        JSONObject out = new JSONObject();
        out.put("type", m.group(1));
        out.put("slug", m.group(2));
        String title = raw.optString("title", "");
        if (title.isEmpty()) title = raw.optString("name", "");
        if (title.isEmpty()) title = "Untitled";
        out.put("title", decode(title));
        String date = raw.optString("release_date", "");
        if (date.isEmpty()) date = raw.optString("first_air_date", "");
        out.put("year", date.length() >= 4 && isDigits(date.substring(0, 4))
                ? Integer.parseInt(date.substring(0, 4)) : JSONObject.NULL);
        Object vote = raw.opt("vote_average");
        out.put("score", vote instanceof Number ? vote : JSONObject.NULL);
        String poster = raw.optString("poster_url", "");
        out.put("poster", poster.isEmpty() ? JSONObject.NULL : poster.replace("/w92/", "/w500/"));
        return out;
    }

    /** The detail page's own IMDb row (`<dt>IMDb:</dt><dd>4.1</dd>`). */
    static Double parseImdb(String html) {
        Matcher m = Pattern.compile("IMDb:</dt>\\s*<dd[^>]*>([\\d.]+)<").matcher(html);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /** LD `genre` / `actor` / `creator` accept one value or an array of them. */
    private static JSONArray nameList(Object v) {
        JSONArray out = new JSONArray();
        if (v == null || v == JSONObject.NULL) return out;
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.opt(i);
                if (el instanceof JSONObject) {
                    String name = ((JSONObject) el).optString("name", "");
                    if (!name.isEmpty()) out.put(decode(name));
                } else if (el != null && el != JSONObject.NULL) {
                    out.put(decode(String.valueOf(el)));
                }
            }
        } else if (v instanceof JSONObject) {
            String name = ((JSONObject) v).optString("name", "");
            if (!name.isEmpty()) out.put(decode(name));
        } else {
            out.put(decode(String.valueOf(v)));
        }
        return out;
    }

    private static Object numOrNull(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof Number ? v : JSONObject.NULL;
    }

    /**
     * A detail page → the details object the client renders: JSON-LD fields
     * where they exist, the IMDb row for the score, and (series only) the
     * season numbers the page offers so the strip needs no second request.
     */
    static JSONObject parseDetails(String type, String slug, String html) throws JSONException {
        JSONObject ld = parseJsonLd(html);
        if (ld == null) ld = new JSONObject();
        JSONObject out = new JSONObject();
        out.put("type", type);
        out.put("slug", slug);

        String title = ld.optString("name", "");
        if (title.isEmpty()) {
            Matcher og = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]*)\"").matcher(html);
            title = og.find() ? decode(og.group(1))
                    : slug.replaceFirst("-\\d+$", "").replace('-', ' ');
        }
        out.put("title", title);

        String desc = ld.optString("description", "");
        out.put("description", desc.isEmpty() ? JSONObject.NULL : decode(desc.replaceAll("<[^>]*>", "")));
        out.put("poster", ld.opt("image") == null || ld.isNull("image") ? JSONObject.NULL : ld.get("image"));
        String created = ld.optString("dateCreated", "");
        out.put("year", created.length() >= 4 && isDigits(created.substring(0, 4))
                ? Integer.parseInt(created.substring(0, 4)) : JSONObject.NULL);
        Double score = parseImdb(html);
        out.put("score", score == null ? JSONObject.NULL : score);
        out.put("genres", nameList(ld.opt("genre")));
        out.put("cast", nameList(ld.opt("actor")));
        out.put("creator", nameList(ld.opt("creator")));
        JSONArray seasons = new JSONArray();
        if ("tv".equals(type)) {
            for (int s : parseSeasons(html)) seasons.put(s);
        }
        out.put("seasons", seasons);
        out.put("numberOfSeasons", numOrNull(ld, "numberOfSeasons"));
        out.put("numberOfEpisodes", numOrNull(ld, "numberOfEpisodes"));
        return out;
    }

    // ----------------------------------------------------------- handlers

    /**
     * GET /api/fmovies/search?q=&page=&type=
     * With a q: the site's TMDB search, optionally narrowed to movie or tv.
     * Without one: its /movies (default) or /tv-series listing page.
     */
    private static Result search(Map<String, String> q) throws Exception {
        String needle = q.get("q") == null ? "" : q.get("q").trim();
        int page = Math.max(1, intPipeZero(q.get("page") == null ? "1" : q.get("page")));
        String typeParam = q.get("type") == null ? "" : q.get("type");
        final String type = "tv".equals(typeParam) ? "tv" : "movie".equals(typeParam) ? "movie" : "";

        if (!needle.isEmpty()) {
            String text = cached("s:" + needle + ":" + page, TTL_SEARCH, () -> get(
                    "/api/search?q=" + enc(needle) + "&page=" + page + "&limit=" + SEARCH_PAGE_SIZE,
                    "application/json"));
            JSONArray raw = new JSONObject(text).optJSONArray("results");
            JSONArray all = new JSONArray();
            if (raw != null) {
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject item = shapeSearchItem(raw.optJSONObject(i));
                    if (item != null) all.put(item);
                }
            }
            JSONArray items = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (type.isEmpty() || type.equals(item.optString("type"))) items.put(item);
            }
            JSONObject out = new JSONObject();
            out.put("q", needle);
            out.put("page", page);
            out.put("type", type);
            out.put("hasNext", all.length() == SEARCH_PAGE_SIZE);
            out.put("items", items);
            return new Result(200, out.toString());
        }

        final String path = "tv".equals(type) ? "/tv-series" : "/movies";
        String html = cached("c:" + type + ":" + page, TTL_CATALOG,
                () -> get(path + "?page=" + page, TEXT_HTML));
        JSONArray items = new JSONArray();
        for (JSONObject item : parseCards(html)) items.put(item);
        JSONObject out = new JSONObject();
        out.put("q", needle);
        out.put("page", page);
        out.put("type", type.isEmpty() ? "movie" : type);
        out.put("hasNext", html.contains("?page=" + (page + 1)));
        out.put("items", items);
        return new Result(200, out.toString());
    }

    /** GET /api/fmovies/(movie|tv)/:slug — one title. */
    private static Result details(String type, String slug) throws Exception {
        String html = cached("d:" + type + ":" + slug, TTL_DETAILS,
                () -> get("/" + type + "/" + slug, TEXT_HTML));
        JSONObject out = new JSONObject().put("details", parseDetails(type, slug, html));
        return new Result(200, out.toString());
    }

    /**
     * GET /api/fmovies/tv/:slug/episodes?season=N — the episode rows of one
     * season. The page is fetched at ?season=N&episode=1; a season the page
     * does not offer is a 404 rather than silently the first season's list.
     */
    private static Result episodes(String slug, Map<String, String> q) throws Exception {
        final int season = Math.max(1, intPipeZero(q.get("season") == null ? "1" : q.get("season")));
        String html = cached("e:" + slug + ":" + season, TTL_EPISODES,
                () -> get("/tv/" + slug + "?season=" + season + "&episode=1", TEXT_HTML));
        if (!parseSeasons(html).contains(season)) {
            return new Result(404, new JSONObject()
                    .put("error", "season_not_found").put("slug", slug).put("season", season).toString());
        }
        List<JSONObject> eps = parseEpisodes(html, season);
        if (eps.isEmpty()) {
            return new Result(404, new JSONObject()
                    .put("error", "no_episodes").put("slug", slug).put("season", season).toString());
        }
        JSONArray data = new JSONArray();
        for (JSONObject ep : eps) data.put(ep);
        return new Result(200, new JSONObject()
                .put("slug", slug).put("season", season).put("data", data).toString());
    }

    /** One door the site's player page names: a URL template per kind. */
    private static final class Door {
        final String id;
        final String movie;
        final String tv;

        Door(String movie, String tv) {
            this(null, movie, tv);
        }

        Door(String id, String movie, String tv) {
            this.id = id;
            this.movie = movie;
            this.tv = tv;
        }

        String url(String type, int id, int season, int episode) {
            return ("tv".equals(type) ? tv : movie)
                    .replace("{id}", String.valueOf(id))
                    .replace("{season}", String.valueOf(season))
                    .replace("{episode}", String.valueOf(episode));
        }
    }

    private static volatile List<Door> doorCache;
    private static volatile long doorCacheExpires;

    /**
     * Today's doors, read from the site's own player page.
     *
     * The page carries its server table as plain JavaScript — one object per
     * door, `{ id: 'embos', aliases: […], movie: 'https://embos.top/movie/?mid={id}',
     * tv: 'https://embos.top/tv/?mid={id}&s={season}&e={episode}' }` — so one
     * cached read answers with the hosts that are current today. A page that
     * cannot be read, or whose table no longer parses, is not an error the
     * reader should see: the built-in list answers instead, and is believed for
     * two minutes rather than half an hour, so a blip costs a retry.
     */
    private static synchronized List<Door> doors() {
        long now = System.currentTimeMillis();
        List<Door> hit = doorCache;
        if (hit != null && doorCacheExpires > now) return hit;

        List<Door> parsed = null;
        try {
            parsed = orderDoors(parseServers(get(WATCH_PAGE, TEXT_HTML)));
        } catch (Exception e) {
            parsed = null;      // the built-in doors answer below
        }
        if (parsed == null || parsed.isEmpty()) {
            doorCache = FALLBACK_DOORS;
            doorCacheExpires = now + TTL_SERVERS_RETRY;
        } else {
            doorCache = parsed;
            doorCacheExpires = now + TTL_SERVERS;
        }
        return doorCache;
    }

    /** The site's `sources = [...]` table, one Door per entry, in its order. */
    static List<Door> parseServers(String html) {
        List<Door> out = new ArrayList<>();
        if (html == null) return out;
        Matcher table = Pattern.compile("var\\s+sources\\s*=\\s*\\[([\\s\\S]*?)\\];").matcher(html);
        if (!table.find()) return out;
        Matcher row = Pattern.compile(
                "id:\\s*['\"]([^'\"]+)['\"][^}]*?movie:\\s*['\"]([^'\"]+)['\"][^}]*?tv:\\s*['\"]([^'\"]+)['\"]")
                .matcher(table.group(1));
        while (row.find()) out.add(new Door(row.group(1), row.group(2), row.group(3)));
        return out;
    }

    /** The site's list, cut to the order we would rather they arrived in. */
    static List<Door> orderDoors(List<Door> list) {
        List<Door> out = new ArrayList<>(list);
        // A stable sort by rank: unknown doors keep their own relative order.
        out.sort((a, b) -> Integer.compare(doorRank(a.id), doorRank(b.id)));
        return out;
    }

    /** What a knock on one door answered. */
    static final class Health {
        final boolean ok;
        final String note;

        Health(boolean ok, String note) {
            this.ok = ok;
            this.note = note;
        }
    }

    /** The knock itself: a seam the JVM tests replace, so tests never dial. */
    interface Prober {
        Health knock(String url);
    }

    static Prober prober = new HttpProber();

    /**
     * Does a door answer at all — and may it be framed?
     *
     * A dead host is the failure that actually happened: two of the three doors
     * stopped resolving and the third refused to be framed, so the player
     * showed black and said nothing. Reachability and the framing headers are
     * the two questions the site's own page cannot answer for us, so they are
     * answered here — once per door per ten minutes, so opening a player does
     * not dial three hosts every time. The body is dropped unread: this is a
     * knock on the door, not a download.
     */
    private static Health health(String url) {
        String answer;
        try {
            // The negative answer is cached too, which is the point: a door
            // that is down is known to be down, cheaply, for ten minutes.
            answer = cached("health:" + url, TTL_HEALTH, () -> {
                Health h = prober.knock(url);
                return h.ok ? "ok" : "no:" + h.note;
            });
        } catch (Exception e) {
            // A knock that throws inside the producer is answered, never
            // thrown — this branch is only here so the route cannot fail with
            // the door list already in hand.
            return new Health(false, "unreachable");
        }
        if ("ok".equals(answer)) return new Health(true, null);
        return new Health(false, answer.startsWith("no:") ? answer.substring(3) : answer);
    }

    static final class HttpProber implements Prober {
        @Override
        public Health knock(String url) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                // Shorter than the Node twin's eight: this dial happens inside
                // a request the phone is waiting on, and a door that needs
                // longer than five seconds to say hello is not one to wait for.
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                conn.setRequestProperty("User-Agent", BROWSER_UA);
                // The door's front desk: an embed asked for with no referer is
                // turned away. (The Node twin sends the same pair.)
                conn.setRequestProperty("Referer", SITE + "/");
                conn.setRequestProperty("Accept", TEXT_HTML);
                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) return new Health(false, "http_" + status);

                String xfo = conn.getHeaderField("X-Frame-Options");
                if (xfo != null) {
                    String lower = xfo.toLowerCase(Locale.ROOT);
                    if (lower.contains("deny") || lower.contains("sameorigin")) {
                        return new Health(false, "frames_refused");
                    }
                }
                String csp = conn.getHeaderField("Content-Security-Policy");
                if (csp != null) {
                    Matcher m = Pattern.compile("frame-ancestors\\s+([^;]+)", Pattern.CASE_INSENSITIVE)
                            .matcher(csp);
                    if (m.find() && m.group(1).toLowerCase(Locale.ROOT).contains("'none'")) {
                        return new Health(false, "frames_refused");
                    }
                }
                return new Health(true, null);
            } catch (Exception e) {
                return new Health(false, "unreachable");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    /**
     * GET /api/fmovies/(movie|tv)/:slug/player?season=&episode= — one URL per
     * door the site offers, filled with the numeric id in the slug, plus
     * whether that door answered. The client opens the first one that did, so a
     * door that went dark is a labelled button rather than a black player.
     */
    private static Result player(String type, String slug, Map<String, String> q) throws JSONException {
        Matcher idMatch = Pattern.compile("-(\\d+)$").matcher(slug);
        if (!idMatch.find()) {
            return new Result(404, new JSONObject()
                    .put("error", "not_found").put("slug", slug).toString());
        }
        int id = Integer.parseInt(idMatch.group(1));
        int season = Math.max(1, intPipeZero(q.get("season") == null ? "1" : q.get("season")));
        int episode = Math.max(1, intPipeZero(q.get("episode") == null ? "1" : q.get("episode")));
        List<Door> doors = doors();
        JSONArray sources = new JSONArray();
        for (int i = 0; i < doors.size(); i++) {
            String url = doors.get(i).url(type, id, season, episode);
            Health probe = health(url);
            sources.put(new JSONObject()
                    .put("label", "Server " + (i + 1))
                    .put("url", url)
                    .put("ok", probe.ok)
                    .put("note", probe.ok ? JSONObject.NULL : probe.note));
        }
        JSONObject out = new JSONObject();
        out.put("type", type);
        out.put("slug", slug);
        out.put("id", id);
        out.put("season", season);
        out.put("episode", episode);
        out.put("sources", sources);
        return new Result(200, out.toString());
    }

    /**
     * One entry point for the whole /api/fmovies family, called from
     * ApiServer with the path and the first value of each query key.
     * A path that matches no route answers 404 without touching the network.
     */
    static Result handle(String path, Map<String, String> query) {
        try {
            if (path.equals("/api/fmovies/search")) return search(query);
            if (path.startsWith("/api/fmovies/")) {
                String rest = path.substring("/api/fmovies/".length());
                Matcher m = Pattern
                        .compile("^(movie|tv)/([a-z0-9.-]+)(?:/(episodes|player))?$")
                        .matcher(rest);
                if (m.matches()) {
                    String type = m.group(1);
                    String slug = m.group(2);
                    String sub = m.group(3);
                    if ("episodes".equals(sub)) {
                        if (!"tv".equals(type)) return notFound(path);
                        return episodes(slug, query);
                    }
                    if ("player".equals(sub)) return player(type, slug, query);
                    return details(type, slug);
                }
            }
            return notFound(path);
        } catch (NotFound e) {
            // An upstream 404 stays a 404, with the reason attached — the
            // same body the Node server's catch builds for err.status 404.
            try {
                return new Result(404, new JSONObject()
                        .put("error", "upstream_error").put("message", e.getMessage()).toString());
            } catch (JSONException unwritable) {
                return new Result(404, "{\"error\":\"upstream_error\"}");
            }
        } catch (Exception e) {
            // Upstream trouble is a 502 with the reason attached, so the
            // client's note tells the reader what failed instead of blanking.
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                return new Result(502, new JSONObject()
                        .put("error", "upstream_error").put("message", message).toString());
            } catch (JSONException unwritable) {
                return new Result(502, "{\"error\":\"upstream_error\"}");
            }
        }
    }

    private static Result notFound(String path) throws JSONException {
        return new Result(404, new JSONObject()
                .put("error", "not_found").put("pathname", path).toString());
    }

    // ----------------------------------------------------------- helpers

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

    /** encodeURIComponent, spaces included. */
    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;   // UTF-8 is present on every JVM
        }
    }
}
