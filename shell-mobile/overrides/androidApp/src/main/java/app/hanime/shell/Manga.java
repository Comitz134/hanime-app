package app.hanime.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * manga, straight from mangafire.to — the phone's side of
 * `server/client/src/manga-api.js`, one endpoint per method of theirs.
 *
 * Their API is open to any caller, but its listing calls are signed: a `vrf`
 * token minted from the path and its canonical query. The token comes from
 * their own bundle, which only runs in a browser — [MangaSigner] runs it, this
 * class asks it, and nothing else here is special. What is signed and what is
 * sent have to be built from the same object (an extra key changes the token
 * and upstream answers 403 without saying which), so the signer answers with
 * both the query string and the token together.
 *
 * Reader pages are the one thing that cannot be handed to the UI as their own
 * URLs: their image CDN refuses a request whose referer is not the site, so the
 * bytes are fetched here — where the header can be set — and served from
 * loopback by `/manga/page`.
 */
final class Manga {

    private static final String TAG = "ShellManga";
    private static final String API = "https://mangafire.to/api";
    private static final String SITE = "https://mangafire.to/";
    private static final int TIMEOUT_MS = 15_000;

    /**
     * How many times one upstream call is attempted, and how long it waits
     * between them. Three attempts over ~2.4 s: long enough to ride out the
     * resolver of a phone that has just woken up, short enough that a real
     * outage still reaches the reader as an error rather than a spinner.
     */
    private static final int NETWORK_ATTEMPTS = 3;
    private static final long NETWORK_RETRY_MS = 600L;

    private static final String PREFS = "sekai-manga";
    /** Positions kept before the oldest is forgotten. */
    private static final int POSITIONS = 60;

    /** Ceiling on the shelf of titles the reader added (see `library()`). */
    private static final int LIBRARY = 400;

    /**
     * Ceiling on the chapters one title can record as read.
     *
     * A number rather than no number because this is a preference file on a
     * phone, not a database: the set costs nothing at a few hundred entries and
     * should not be able to grow without a limit if something upstream ever
     * answers with nonsense.
     */
    private static final int READ_MARKS = 2000;

    private static Context application;
    private static MangaSigner signer;

    private Manga() {
    }

    static synchronized void ensure(Context context) {
        if (application == null) application = context.getApplicationContext();
        if (signer == null) signer = MangaSigner.ensure(context);
        // Sources are part of the manga area's state, not a separate service:
        // the built-in repository has to be installed before a search asks it
        // what it holds, and the signer only has to be up for Mangafire.
        MangaSources.ensure(context);
    }

    // ------------------------------------------------------------ their API

    /**
     * The seam the JVM tests replace; the app uses {@link HttpTransport}.
     *
     * It carries the whole request rather than a JSONObject, because what a
     * test has to see is the URL the route produced — the query string a
     * signing bundle mints and the API it is sent to are one thing, and a seam
     * that hid the URL could not pin the bug this exists for (see
     * `SearchQuery`).
     */
    interface Transport {
        /** One GET of a URL that already carries its own query; the body back. */
        String get(String url) throws Exception;
    }

    static Transport transport = new HttpTransport();

    /**
     * The catalogue's hosts through DoH when Android's own resolver refuses a
     * name, and through Android when it does not.
     *
     * On a network that hands the phone an IPv6-only address, Java's resolver
     * applies AI_ADDRCONFIG and turns a host that answers on IPv4 alone into
     * *no address associated with hostname* — while the browser and `ping`
     * still reach it, because they do not. MyAnimeList hit this first and the
     * relay looks its hosts up over DoH ([Mal.resolveOverDoh]); mangafire.to is
     * the same shape of failure, so it gets the same answer. The system is
     * always tried first: this only runs where the request would have failed.
     */
    static List<InetAddress> lookup(String hostname) throws UnknownHostException {
        UnknownHostException refused;
        try {
            return okhttp3.Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException refusedBySystem) {
            refused = refusedBySystem;
            Log.w(TAG, "lookup " + hostname + " refused, trying DoH");
        }
        List<InetAddress> resolved = Mal.resolveOverDoh(hostname);
        if (!resolved.isEmpty()) return resolved;
        throw refused;
    }

    private static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dns(Manga::lookup)
            .build();

    static final class HttpTransport implements Transport {
        @Override
        public String get(String url) throws Exception {
            // OkHttp rather than HttpURLConnection for one reason: it can be
            // handed a resolver. Everything about the request is the same —
            // the referer and user agent are what their edge checks.
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Referer", SITE)
                    // The default `User-Agent: Java/21` is a thing their edge has
                    // never seen from a client of theirs; the fork already sends
                    // this one to hanime.
                    .header("User-Agent", Streams.USER_AGENT)
                    .build();
            Exception last = null;
            for (int attempt = 0; attempt < NETWORK_ATTEMPTS; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(NETWORK_RETRY_MS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw last;
                    }
                }
                try {
                    try (okhttp3.Response response = CLIENT.newCall(request).execute()) {
                        if (response.code() != 200) {
                            throw new IllegalStateException("upstream HTTP " + response.code());
                        }
                        return response.body() == null ? "" : response.body().string();
                    }
                } catch (UnknownHostException | java.net.ConnectException
                         | java.net.SocketTimeoutException network) {
                    // A phone that has just been handed a network — a restart, a
                    // Wi-Fi switch, the screen coming on — has the radio up before
                    // its resolver is, and the first lookup after that is the one
                    // that fails. One refused name is not a catalogue that is
                    // down, so the request is asked again before the reader is
                    // told anything.
                    last = network;
                    Log.w(TAG, "upstream " + url + " attempt " + (attempt + 1) + " of "
                            + NETWORK_ATTEMPTS + " failed: " + network.getClass().getName()
                            + ": " + network.getMessage());
                }
            }
            throw last;
        }
    }

    /**
     * One signed GET of their API, shaped as a JSONObject. The signer is given
     * a moment to boot on the first call; if it never does, the request is
     * still sent unsigned, because their own `403 Missing token.` is a clearer
     * failure than a timeout invented here — but that refusal is then not the
     * reader's answer either. A cold start on a slow phone spends well past the
     * first wait loading the signer's WebView, and the title screen used to say
     * *upstream HTTP 403* over a chapter list that was one second away. So a
     * token refusal on a request that went out unsigned gets one signed retry
     * once the signer is up; a refusal of a signed request is upstream's own
     * answer and is passed straight through.
     */
    private static JSONObject get(String path, JSONObject params) throws Exception {
        MangaSigner s = signer;
        if (s != null) s.awaitReady(6_000);

        String qs = encode(params);
        String vrf = "";
        if (s != null) {
            String[] signed = s.sign(path, params.toString());
            // An empty query string is a legitimate answer — a title asked for
            // by id has no parameters — so the signer having answered is what
            // matters, not whether it had anything to serialise.
            if (signed != null) {
                qs = signed[0];
                vrf = signed[1];
            }
        }

        String url = url(path, qs, vrf);
        Log.d(TAG, (vrf.isEmpty() ? "unsigned" : "signed") + " GET " + url);
        try {
            return new JSONObject(transport.get(url));
        } catch (IllegalStateException refused) {
            if (vrf.length() > 0 || s == null || !tokenRefusal(refused)) throw refused;
            s.awaitReady(9_000);
            String[] signed = s.sign(path, params.toString());
            if (signed == null) throw refused;
            String retry = url(path, signed[0], signed[1]);
            Log.i(TAG, "signed GET " + retry + " (after a token refusal)");
            return new JSONObject(transport.get(retry));
        }
    }

    private static String url(String path, String qs, String vrf) throws Exception {
        StringBuilder url = new StringBuilder(API).append(path);
        if (!qs.isEmpty()) url.append('?').append(qs);
        if (!vrf.isEmpty()) {
            url.append(qs.isEmpty() ? '?' : '&').append("vrf=")
                    .append(URLEncoder.encode(vrf, "UTF-8"));
        }
        return url.toString();
    }

    /** Upstream's answer to a request that carried no token: not a refusal of the reader. */
    private static boolean tokenRefusal(IllegalStateException refused) {
        String text = refused.getMessage() == null ? "" : refused.getMessage();
        return text.contains("403") || text.contains("401");
    }

    /**
     * The query string the way axios serialises it — the shape the signer
     * canonicalises: `types[]=manga`, `order[chapter_updated_at]=desc`. Empty
     * values are dropped here, before signing, so the token and the URL agree.
     */
    private static String encode(JSONObject params) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String key : keys(params)) {
            Object value = params.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            if (value instanceof JSONArray) {
                JSONArray list = (JSONArray) value;
                for (int i = 0; i < list.length(); i++) {
                    put(out, key + "[]", list.optString(i, ""));
                }
            } else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                for (String sub : keys(object)) put(out, key + "[" + sub + "]", object.optString(sub, ""));
            } else {
                String text = String.valueOf(value);
                if (text.isEmpty()) continue;
                put(out, key, text);
            }
        }
        return out.toString();
    }

    private static void put(StringBuilder out, String key, String value) throws Exception {
        if (out.length() > 0) out.append('&');
        out.append(URLEncoder.encode(key, "UTF-8")).append('=')
                .append(URLEncoder.encode(value, "UTF-8").replace("+", "%20"));
    }

    private static List<String> keys(JSONObject object) {
        List<String> out = new ArrayList<>();
        java.util.Iterator<String> it = object.keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    // --------------------------------------------------------------- shelves

    /**
     * The homepage shelf: trending titles, from the first source the reader
     * left on. No paging — one shelf, one answer.
     *
     * Not every source at once: a shelf is a mood, and interleaving five
     * catalogues into it is a list rather than a shelf. Search is where sources
     * mix, because there the reader named what they are looking for.
     */
    static JSONArray trending(int limit) throws Exception {
        MangaSources.Source primary = MangaSources.primary();
        if (primary == null) return new JSONArray();
        if (primary.isNative()) return trendingMangafire(limit);
        JSONObject answer = MangaSources.browse(primary, "", 1, limit, "", "", "");
        JSONArray items = answer.optJSONArray("items");
        JSONArray out = new JSONArray();
        if (items != null) {
            for (int i = 0; i < items.length() && i < limit; i++) out.put(items.get(i));
        }
        return out;
    }

    /** The built-in shelf, for as long as Mangafire is one of the sources. */
    private static JSONArray trendingMangafire(int limit) throws Exception {
        JSONObject body = get("/top-titles", new JSONObject()
                .put("type", "trending").put("days", 1).put("limit", limit));
        return shape(body.optJSONArray("items"));
    }

    /**
     * The parameters `/manga/search.json` is asking for, resolved once and held
     * in one object.
     *
     * `page` and `limit` in particular. The two were crossed here once — the
     * caller's limit was handed to upstream as the *page*, so every keyword
     * search asked for page 24 of page 24 and came back empty, which reads as a
     * catalogue with nothing in it rather than as a bug. The resolution lives
     * in one function so the JVM tests can hold it (`MangaRouteTest`), because
     * nothing about it needs a phone.
     */
    static final class SearchQuery {
        final String q;
        final int page;
        final int limit;
        final String type;
        final String status;
        final String genre;

        SearchQuery(String q, int page, int limit, String type, String status, String genre) {
            this.q = q;
            this.page = page;
            this.limit = limit;
            this.type = type;
            this.status = status;
            this.genre = genre;
        }
    }

    /** Rows a caller gets when it names none — the page size the app asks for. */
    static final int SEARCH_LIMIT = 24;
    /** A caller's page size is a hint, not an order. */
    private static final int SEARCH_LIMIT_MAX = 100;

    /** `/manga/search.json`'s own query, as the route reads it. */
    static SearchQuery searchQuery(Map<String, String> query) {
        int page = intOr(query.get("page"));
        int limit = intOr(query.get("limit"));
        return new SearchQuery(
                trim(query.get("q")),
                page <= 0 ? 1 : page,
                limit <= 0 ? SEARCH_LIMIT : Math.min(limit, SEARCH_LIMIT_MAX),
                trim(query.get("type")),
                trim(query.get("status")),
                trim(query.get("genre")));
    }

    /**
     * The same call, from the resolved parameters — what the route uses.
     *
     * A keyword asks every source the reader enabled and returns one
     * interleaved list, each card tagged with the catalogue it came from (see
     * [MangaSources.search]). A browse, with no keyword, stays with the first
     * source: "latest chapters" is one catalogue's ordering, and mixing several
     * makes it no catalogue's.
     */
    static JSONObject search(SearchQuery search) throws Exception {
        return MangaSources.search(search.q, search.page, search.limit,
                search.type, search.status, search.genre);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int intOr(String value) {
        try {
            return Integer.parseInt(trim(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * A wall-clock instant in milliseconds from a query value, or 0 for none.
     *
     * Not `intOr`: epoch milliseconds overflow an int, and a truncated stamp
     * parses as a number in 1970 rather than as a failure — an imported shelf's
     * order would disappear with nothing to show for it, which is precisely the
     * bug this exists to keep fixed.
     */
    static long atOr(String value) {
        try {
            return Long.parseLong(trim(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * Search and browse in one call: a keyword ranks by relevance, and the same
     * call without one is the catalogue ordered by the latest chapter update,
     * which is what their own browse page shows.
     */
    static JSONObject searchMangafire(String q, int page, int limit, String type, String status, String genre)
            throws Exception {
        JSONObject params = new JSONObject().put("page", page).put("limit", limit);
        if (q != null && !q.isEmpty()) {
            params.put("keyword", q);
        } else {
            params.put("order", new JSONObject().put("chapter_updated_at", "desc"));
        }
        if (type != null && !type.isEmpty()) params.put("types", new JSONArray().put(type));
        if (status != null && !status.isEmpty()) params.put("statuses", new JSONArray().put(status));
        if (genre != null && !genre.isEmpty()) params.put("genres_in", new JSONArray().put(genre));

        JSONObject body = get("/titles", params);
        JSONObject meta = body.optJSONObject("meta");
        int last = meta == null ? 1 : meta.optInt("lastPage", 1);
        return new JSONObject()
                .put("items", shape(body.optJSONArray("items")))
                .put("page", page)
                .put("hasNext", page < last);
    }

    /** One title: synopsis, genres, ratings — everything but the chapters. */
    static JSONObject detail(String hid) throws Exception {
        MangaSources.Route route = MangaSources.route(hid);
        if (!route.source.isNative()) {
            return MangaSources.jsTitle(route.source, route.raw);
        }
        hid = route.raw;
        JSONObject body = get("/titles/" + urlPart(hid), new JSONObject());
        JSONObject data = body.optJSONObject("data");
        return shapeTitle(data == null ? new JSONObject() : data, hid);
    }

    /**
     * The chapter list, newest first, paged at their maximum of 200 and walked
     * for long series. A title with no English translation answers empty for
     * `language=en`, and the same list unfiltered is still worth showing.
     */
    static JSONArray chapters(String hid) throws Exception {
        MangaSources.Route route = MangaSources.route(hid);
        if (!route.source.isNative()) {
            return MangaSources.jsChapters(route.source, route.raw);
        }
        hid = route.raw;
        String base = "/titles/" + urlPart(hid) + "/chapters";
        JSONArray items = new JSONArray();
        int page = 1;
        int last = 1;
        do {
            JSONObject body = get(base, new JSONObject()
                    .put("sort", "number").put("order", "desc")
                    .put("page", page).put("limit", 200).put("language", "en"));
            append(items, body.optJSONArray("items"));
            JSONObject meta = body.optJSONObject("meta");
            last = meta == null ? 1 : meta.optInt("lastPage", 1);
            page += 1;
        } while (page <= last && page <= 5);

        if (items.length() == 0) {
            JSONObject body = get(base, new JSONObject()
                    .put("sort", "number").put("order", "desc").put("page", 1).put("limit", 200));
            append(items, body.optJSONArray("items"));
        }
        return shapeChapters(items);
    }

    /**
     * A chapter's page addresses, as upstream lists them — the reading order.
     *
     * Separate from [pages] because downloading wants the addresses themselves:
     * the reader is handed loopback URLs it can load, while the downloader has
     * to fetch each page from upstream, where the referer can be set. Both walk
     * the same answer, in the same order.
     */
    static JSONArray pageUrls(String chapterId) throws Exception {
        MangaSources.Route route = MangaSources.route(chapterId);
        if (!route.source.isNative()) {
            return MangaSources.jsPageUrls(route.source, route.raw);
        }
        chapterId = route.raw;
        JSONObject body = get("/chapters/" + urlPart(chapterId), new JSONObject());
        JSONObject data = body.optJSONObject("data");
        JSONArray raw = data == null ? null : data.optJSONArray("pages");
        JSONArray out = new JSONArray();
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                JSONObject page = raw.optJSONObject(i);
                String url = page == null ? "" : page.optString("url", "");
                if (url.isEmpty()) continue;
                out.put(url);
            }
        }
        return out;
    }

    /** A chapter's pages, as absolute loopback URLs the reader can load. */
    static JSONObject pages(String chapterId) throws Exception {
        JSONArray urls = pageUrls(chapterId);
        JSONArray out = new JSONArray();
        for (int i = 0; i < urls.length(); i++) {
            String url = urls.optString(i, "");
            if (url.isEmpty()) continue;
            out.put(new JSONObject().put("u", proxied(url)));
        }
        return new JSONObject().put("pages", out);
    }

    /** Types, statuses and genres with their upstream enum values. */
    static JSONObject filters() throws Exception {
        JSONObject body = get("/filter-options", new JSONObject());
        JSONObject data = body.optJSONObject("data");
        return data == null ? body : data;
    }

    // --------------------------------------------------------------- shaping

    /** Titles as the app's cards want them: one flat row per title. */
    private static JSONArray shape(JSONArray items) throws Exception {
        JSONArray out = new JSONArray();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String hid = item.optString("hid", "");
            if (hid.isEmpty()) continue;
            out.put(shapeTitle(item, hid));
        }
        return out;
    }

    private static JSONObject shapeTitle(JSONObject item, String hid) throws Exception {
        JSONObject poster = item.optJSONObject("poster");
        String art = poster == null ? "" : first(poster, "medium", "large", "small", "original");
        if (art.isEmpty()) art = item.optString("poster", "");

        JSONObject out = new JSONObject()
                .put("id", hid)
                .put("title", item.optString("title", ""))
                .put("poster", art);
        String type = item.optString("type", "");
        if (!type.isEmpty()) out.put("type", type);
        String year = item.optString("year", "");
        if (!year.isEmpty()) out.put("year", year);
        String status = item.optString("status", "");
        if (!status.isEmpty()) out.put("status", status);
        String description = item.optString("description", "");
        if (!description.isEmpty()) out.put("description", strip(description));
        String rating = item.optString("rating", item.optString("score", ""));
        if (!rating.isEmpty()) out.put("rating", rating);
        String mal = item.optString("malId", "");
        if (!mal.isEmpty()) out.put("malId", mal);
        String latest = item.optString("latestChapter", "");
        if (!latest.isEmpty()) out.put("latestChapter", latest);

        // Their genres are objects (`{id, title}`) on a title and plain strings
        // in a filter list; the app only ever draws the name.
        JSONArray genres = item.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            JSONArray names = new JSONArray();
            for (int i = 0; i < genres.length(); i++) {
                Object entry = genres.opt(i);
                if (entry instanceof JSONObject) {
                    String name = ((JSONObject) entry).optString("title", "");
                    if (!name.isEmpty()) names.put(name);
                } else if (entry != null) {
                    names.put(String.valueOf(entry));
                }
            }
            if (names.length() > 0) out.put("genres", names);
        }
        return out;
    }

    private static JSONArray shapeChapters(JSONArray items) throws Exception {
        JSONArray out = new JSONArray();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (id.isEmpty()) continue;
            // `created` is when the chapter was posted, in epoch seconds — the
            // only fact the next-chapter estimate can read a rhythm from. The
            // display date (`at`) stays a string because it is drawn as one.
            out.put(new JSONObject()
                    .put("id", id)
                    .put("number", item.optString("number", ""))
                    .put("name", item.optString("name", ""))
                    .put("at", item.optString("updatedAt", ""))
                    .put("created", stamp(item)));
        }
        return out;
    }

    /**
     * When a chapter went up, in epoch seconds, or 0.
     *
     * mangafire's listing mixes its spellings — `meta.lastPage` is camelCase and
     * the order it is asked for is `chapter_updated_at` — so both spellings of
     * the posting date are read rather than betting on one. A number is taken as
     * it is; a number written as a string is parsed; anything else (a null, an
     * ISO string this app has no parser for) stays 0, which is the estimate's
     * honest "not dated" rather than a wrong date.
     */
    private static long stamp(JSONObject item) {
        for (String key : new String[] {"createdAt", "created_at", "created", "updatedAt", "updated_at"}) {
            Object raw = item.opt(key);
            if (raw == null) continue;
            if (raw instanceof Number) {
                long value = ((Number) raw).longValue();
                if (value > 0) return value;
                continue;
            }
            try {
                long value = Long.parseLong(String.valueOf(raw).trim());
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
                // Not a stamp this reader understands; try the next spelling.
            }
        }
        return 0L;
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String strip(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private static String urlPart(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static void append(JSONArray into, JSONArray from) {
        if (from == null) return;
        for (int i = 0; i < from.length(); i++) into.put(from.opt(i));
    }

    /** A reader page as the reader loads it: through this server's own proxy. */
    static String proxied(String url) {
        try {
            return "http://127.0.0.1:" + SekaiServer.PORT + "/manga/page?u="
                    + URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    // ---------------------------------------------------------- reader pages

    private static final Map<String, Page> PAGES = new HashMap<>();
    private static final long PAGE_CACHE_BYTES = 96L * 1024 * 1024;
    private static long pageBytes;

    static final class Page {
        final byte[] body;
        final String contentType;

        Page(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }

    /** One page's bytes, with the referer their CDN insists on. */
    static Page image(String target) {
        return fetch(target, true);
    }

    /**
     * One page's bytes, with the referer their CDN insists on. Cached by URL when
     * [keep] — a chapter is re-read more often than it is read, and a strip of
     * images is the one thing here whose absence is visible on screen.
     *
     * A download passes false: its pages are about to live on disk, and holding
     * every one of them in memory as well would evict the pages actually being
     * read on a long series.
     */
    static Page fetch(String target, boolean keep) {
        Page hit;
        if (keep) {
            synchronized (PAGES) {
                hit = PAGES.get(target);
            }
            if (hit != null) return hit;
        }

        byte[] body;
        String type;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(30_000);
            if (mangafireHost(target)) conn.setRequestProperty("Referer", SITE);
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            conn.setRequestProperty("User-Agent", Streams.USER_AGENT);
            try {
                int code = conn.getResponseCode();
                if (code != 200) throw new IllegalStateException("page returned HTTP " + code);
                type = conn.getContentType();
                try (InputStream in = conn.getInputStream()) {
                    body = readBytes(in);
                }
            } finally {
                conn.disconnect();
            }
        } catch (UnknownHostException refused) {
            // A name the system resolver would not answer: the same fallback
            // the API uses, for the same reason (see [lookup]).
            try {
                okhttp3.Request.Builder request = new okhttp3.Request.Builder()
                        .url(target)
                        .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                        .header("User-Agent", Streams.USER_AGENT);
                if (mangafireHost(target)) request.header("Referer", SITE);
                try (okhttp3.Response response = CLIENT.newCall(request.build()).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Log.w(TAG, "page failed: " + target + " HTTP " + response.code());
                        return null;
                    }
                    type = response.header("Content-Type");
                    body = response.body().bytes();
                }
            } catch (Exception failure) {
                Log.w(TAG, "page failed: " + target, failure);
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "page failed: " + target, e);
            return null;
        }

        Page page = new Page(body, type == null || type.isEmpty() ? "image/jpeg" : type);
        if (keep) {
            synchronized (PAGES) {
                if (pageBytes + body.length <= PAGE_CACHE_BYTES) {
                    PAGES.put(target, page);
                    pageBytes += body.length;
                }
            }
        }
        return page;
    }

    /**
     * Their CDN wants their referer; nobody else's does.
     *
     * A page from a script source is fetched with the app's own user agent and
     * no referer at all, because the script cannot say which site it is reading
     * and inventing one (mangafire's) would be a lie another host might act on.
     */
    private static boolean mangafireHost(String url) {
        return url != null && (url.contains("mangafire.") || url.contains("mfcdn."));
    }

    // ------------------------------------------------------------- positions

    /**
     * Where each title was left. A phone closes apps without warning, so a
     * position is written as the reader turns a page rather than when a chapter
     * ends — the cost of a write is a few hundred bytes of preferences.
     */
    static JSONArray positions() {
        JSONArray out = new JSONArray();
        SharedPreferences prefs = prefs();
        if (prefs == null) return out;

        List<JSONObject> rows = new ArrayList<>();
        for (String hid : prefs.getStringSet("hids", new java.util.HashSet<>())) {
            String raw = prefs.getString("hid:" + hid, null);
            if (raw == null) continue;
            try {
                rows.add(new JSONObject(raw));
            } catch (Exception ignored) {
                // A row that will not parse is a row nobody can use.
            }
        }
        rows.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        for (JSONObject row : rows) out.put(row);
        return out;
    }

    /** Record the reader's place in one title, and forget the oldest past sixty. */
    static void savePosition(JSONObject row) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        String hid = row.optString("id", "");
        if (hid.isEmpty()) return;

        java.util.Set<String> hids = new java.util.HashSet<>(prefs.getStringSet("hids", new java.util.HashSet<>()));
        hids.add(hid);

        SharedPreferences.Editor edit = prefs.edit().putString("hid:" + hid, row.toString());
        // The shelf remembers when this title was last read, so that it can be
        // ordered by that without re-reading every position. The stamp is the
        // position's own: the reader turning a page (now), or an import writing
        // the chapter MyAnimeList says was read (the account's own time).
        // The shelf follows a page turning and an import (both carry a moment
        // worth keeping) but not a tracker sync: that one copies the *account's*
        // order of reading, and stamping it now would make every title in the
        // walk look read at the moment the walk ran — the ordering bug this
        // whole change exists for, reintroduced by its own repair button. The
        // caller says so with `quiet`, which is not part of the stored row.
        long readAt = row.optLong("at", System.currentTimeMillis());
        boolean quiet = !row.optString("quiet", "").isEmpty();
        row.remove("quiet");
        String shelved = prefs.getString("lib:" + hid, null);
        if (shelved != null && !quiet) {
            try {
                JSONObject row2 = new JSONObject(shelved);
                row2.put("read", Math.max(readAt, row2.optLong("read", 0)));
                edit.putString("lib:" + hid, row2.toString());
            } catch (Exception ignored) {
                // An unreadable shelf row is replaced the next time it is added.
            }
        }
        if (hids.size() > POSITIONS) {
            JSONArray rows = positions();
            // The row being written is not in `rows` yet, so the tail is cut one
            // short of the cap: the write is what fills the last slot.
            for (int i = POSITIONS - 1; i < rows.length(); i++) {
                JSONObject old = rows.optJSONObject(i);
                String gone = old == null ? "" : old.optString("id", "");
                if (gone.isEmpty()) continue;
                hids.remove(gone);
                edit.remove("hid:" + gone);
            }
        }
        edit.putStringSet("hids", hids).apply();
    }

    // ------------------------------------------------------------ read marks

    /**
     * Which chapters of a title the reader has actually put on screen.
     *
     * A position is where the reader *is*, and one position implies every
     * chapter below it — true of someone reading forward from the old end, and
     * wrong for someone who jumps: open chapter 40 of a hundred and a boundary
     * calls sixty chapters read, thirty-nine of which were never opened. That
     * arithmetic is what the title page's fraction, its bar and the *read* ink
     * on the list are drawn from, so a wrong boundary is a wrong answer in the
     * one place a reader looks to find out how far along they are.
     *
     * This is the record that replaces the inference. What is stored is the set
     * of chapter **numbers**, not chapter ids, because a number is what
     * survives the duplicate rule (two rows, one number), what a tracker
     * speaks, and what upstream re-issues ids around. A mark for a chapter the
     * catalogue no longer lists simply stops matching anything.
     */
    static java.util.Set<String> readMarks(String hid) {
        java.util.Set<String> out = new java.util.HashSet<>();
        SharedPreferences prefs = prefs();
        if (prefs == null || hid.isEmpty()) return out;
        out.addAll(prefs.getStringSet("read:" + hid, new java.util.HashSet<>()));
        return out;
    }

    /**
     * Write down that one or more chapters of [hid] have been read.
     *
     * [onlyIfEmpty] is the one-time migration: a title that was read before
     * this record existed has a position and no marks, so the caller offers the
     * chapters the old boundary rule claimed. It must not run over a record
     * that already exists — that record is the reader's own history, and a
     * boundary recomputed from the last position would overwrite it with a
     * guess on every visit.
     *
     * Nothing here touches the position or the shelf: a read is a fact about a
     * chapter, and moving the reader's place is a different fact written by a
     * different call.
     */
    static void markRead(String hid, java.util.Collection<String> numbers, boolean onlyIfEmpty) {
        SharedPreferences prefs = prefs();
        if (prefs == null || hid.isEmpty() || numbers == null) return;

        java.util.Set<String> marks = readMarks(hid);
        if (onlyIfEmpty && !marks.isEmpty()) return;

        java.util.Set<String> hids = new java.util.HashSet<>(
                prefs.getStringSet("readhids", new java.util.HashSet<>()));
        java.util.Set<String> next = withMarks(marks, numbers, READ_MARKS);
        boolean added = next.size() != marks.size();
        marks = next;
        if (!added) {
            // Still remember that this title has a record, so that the
            // migration does not offer the same seed on the next visit — an
            // empty record and no record are different answers.
            if (!hids.contains(hid)) {
                hids.add(hid);
                prefs.edit().putStringSet("readhids", hids).apply();
            }
            return;
        }
        hids.add(hid);
        prefs.edit()
                .putStringSet("read:" + hid, marks)
                .putStringSet("readhids", hids)
                .apply();
    }

    /**
     * The record after offering these chapters, without touching storage.
     *
     * The rule a record has to keep, and the reason this is a function rather
     * than a loop inside a write: a mark is a *trimmed number* and never a
     * blank, the set is bounded so a nonsense answer cannot grow a preference
     * file without limit, and offering the same chapter twice is offering it
     * once. A blank is skipped rather than stored because every nameless extra
     * would collide into one mark and claim a chapter nobody read.
     */
    static java.util.Set<String> withMarks(
            java.util.Set<String> marks, java.util.Collection<String> offered, int cap) {
        java.util.Set<String> out = new java.util.HashSet<>(
                marks == null ? java.util.Collections.emptySet() : marks);
        if (offered == null) return out;
        for (String raw : offered) {
            if (out.size() >= cap) break;
            String number = raw == null ? "" : raw.trim();
            if (number.isEmpty()) continue;
            out.add(number);
        }
        return out;
    }

    /** One chapter read, the shape the reader itself calls. */
    static void markRead(String hid, String number) {
        if (number == null || number.trim().isEmpty()) return;
        markRead(hid, java.util.Collections.singletonList(number), false);
    }

    /**
     * Every title's marks, for a caller that wants more than one title.
     *
     * The app asks about one title at a time (the one whose chapter list is on
     * screen); this exists so an import or a diagnostic can see the whole
     * record in one request rather than a hundred.
     */
    static JSONArray allReadMarks() {
        JSONArray out = new JSONArray();
        SharedPreferences prefs = prefs();
        if (prefs == null) return out;
        for (String hid : prefs.getStringSet("readhids", new java.util.HashSet<>())) {
            JSONArray numbers = new JSONArray();
            List<String> sorted = new ArrayList<>(readMarks(hid));
            java.util.Collections.sort(sorted);
            for (String number : sorted) numbers.put(number);
            try {
                out.put(new JSONObject().put("id", hid).put("chapters", numbers));
            } catch (Exception ignored) {
                // A row that will not build is a row nobody can use.
            }
        }
        return out;
    }

    // --------------------------------------------------------------- library

    /**
     * The titles the reader put on their shelf.
     *
     * A position is where a title was left; the library is whether the title is
     * wanted at all. They are kept apart on purpose — a position is written by
     * the reader turning pages and nothing else, while the shelf changes when
     * the reader says so (or when a MyAnimeList list is imported). The two meet
     * here: a row carries its position when there is one, so the shelf can show
     * "Ch. 12" on the titles that have been read and nothing on the ones that
     * have not.
     */
    static JSONArray library() {
        JSONArray out = new JSONArray();
        SharedPreferences prefs = prefs();
        if (prefs == null) return out;

        Map<String, JSONObject> where = new HashMap<>();
        JSONArray read = positions();
        for (int i = 0; i < read.length(); i++) {
            JSONObject row = read.optJSONObject(i);
            if (row != null) where.put(row.optString("id", ""), row);
        }

        List<JSONObject> rows = new ArrayList<>();
        for (String hid : prefs.getStringSet("libhids", new java.util.HashSet<>())) {
            String raw = prefs.getString("lib:" + hid, null);
            if (raw == null) continue;
            try {
                JSONObject row = new JSONObject(raw);
                JSONObject at = where.get(hid);
                if (at != null) {
                    row.put("chapter", at.optString("chapter", ""));
                    row.put("number", at.optString("number", ""));
                    row.put("page", at.optInt("page", 0));
                }
                // Last read is the shelf's own stamp, not the position's: a
                // position is also written *by an import* (to make "Ch. 41"
                // appear), and one stamped now made every imported title look
                // read at import time — which is exactly how a shelf ends up
                // ordered by when it was imported. `savePosition` keeps this
                // stamp current when a page actually turns, and the import
                // passes MyAnimeList's own `updated_at` when it writes one.
                //
                // No fallback to the position's stamp: for a shelf brought over
                // from MyAnimeList those positions were written *by the import*,
                // so falling back to them is what made "last read" mean "when
                // the import ran". A row with no stamp is a row nobody has read
                // here, and it sorts after the ones that have.
                rows.add(row);
            } catch (Exception ignored) {
                // A row that will not parse is a row nobody can use.
            }
        }
        rows.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        for (JSONObject row : rows) out.put(row);
        return out;
    }

    /** Put one title on the shelf. Adding a title already there is a no-op. */
    static void addToLibrary(JSONObject row) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        String hid = row.optString("id", "");
        if (hid.isEmpty()) return;

        // A shelf that only grows is a preferences file that grows: the oldest
        // row goes once the ceiling is reached, exactly as a position does.
        java.util.Set<String> hids = new java.util.HashSet<>(
                prefs.getStringSet("libhids", new java.util.HashSet<>()));
        hids.add(hid);
        JSONObject stored = row;
        if (hids.size() > LIBRARY) {
            JSONArray rows = library();
            for (int i = LIBRARY - 1; i < rows.length(); i++) {
                JSONObject old = rows.optJSONObject(i);
                String gone = old == null ? "" : old.optString("id", "");
                if (gone.isEmpty()) continue;
                hids.remove(gone);
                prefs.edit().remove("lib:" + gone).apply();
            }
        }
        // A title already on the shelf keeps the moment it was added: re-adding
        // it (an import over a title read yesterday) must not reshuffle.
        String existing = prefs.getString("lib:" + hid, null);
        if (existing != null) {
            try {
                JSONObject kept = new JSONObject(existing);
                stored.put("at", kept.optLong("at", row.optLong("at")));
                // The moment of last read *is* refreshed, unlike the moment of
                // adding, and the import's value wins rather than the newer of
                // the two. A re-import is the only way to correct a stamp this
                // app wrote wrongly (a tracker sync stamping "now" did exactly
                // that once), and `max` could never move a stamp backwards. The
                // account is the source of truth for when a title was last read
                // — the app writes every chapter it sees to it — so its word
                // replaces the local guess.
                long imported = row.optLong("read", 0);
                if (imported > 0) stored.put("read", imported);
                else stored.put("read", kept.optLong("read", 0));
                // The account's id outlives the row it was matched by: a second
                // import must not have to match the same title by name again.
                long importedMal = row.optLong("mal", 0);
                if (importedMal > 0) stored.put("mal", importedMal);
                else if (kept.optLong("mal", 0) > 0) stored.put("mal", kept.optLong("mal", 0));
                // A length already counted is kept across a re-add: an import
                // that puts a title back on the shelf knows nothing about the
                // chapter list, and dropping the count would make the row fall
                // back to "Ch. 12" until it is opened again.
                if (row.optInt("chapters", 0) <= 0 && kept.optInt("chapters", 0) > 0) {
                    stored.put("chapters", kept.optInt("chapters", 0));
                }
            } catch (Exception ignored) {
                // Unreadable is as good as absent: the new row replaces it.
            }
        }
        prefs.edit()
                .putStringSet("libhids", hids)
                .putString("lib:" + hid, stored.toString())
                .apply();
    }

    /**
     * How many chapters a title has, as the app counted them.
     *
     * The count belongs to the row rather than being fetched with the shelf: it
     * is the number of rows the *app* draws, which is what the dedupe makes it,
     * and the shelf has no business calling upstream for a hundred titles every
     * time the Library tab opens. The app sends it once it has read the chapter
     * list — which it already does to open a title — and this remembers it only
     * for a title that is on the shelf: stamping a count onto a title nobody
     * kept would be the shelf growing behind the reader's back.
     */
    static void setLibrarySize(String hid, int chapters) {
        SharedPreferences prefs = prefs();
        if (prefs == null || hid.isEmpty() || chapters <= 0) return;
        String raw = prefs.getString("lib:" + hid, null);
        if (raw == null) return;
        try {
            JSONObject row = new JSONObject(raw);
            if (row.optInt("chapters", 0) == chapters) return;
            row.put("chapters", chapters);
            prefs.edit().putString("lib:" + hid, row.toString()).apply();
        } catch (Exception ignored) {
            // A row that will not parse is one no count can be added to.
        }
    }

    /** Take one title off the shelf. Its position, if any, stays where it was. */
    static void removeFromLibrary(String hid) {
        SharedPreferences prefs = prefs();
        if (prefs == null || hid.isEmpty()) return;
        java.util.Set<String> hids = new java.util.HashSet<>(
                prefs.getStringSet("libhids", new java.util.HashSet<>()));
        hids.remove(hid);
        prefs.edit().putStringSet("libhids", hids).remove("lib:" + hid).apply();
    }

    private static SharedPreferences prefs() {
        Context context = application;
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ io

    private static String readAll(InputStream in) throws Exception {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buffer = new byte[32 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
