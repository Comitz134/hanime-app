package app.hanime.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final String PREFS = "sekai-manga";
    /** Positions kept before the oldest is forgotten. */
    private static final int POSITIONS = 60;

    /** Ceiling on the shelf of titles the reader added (see `library()`). */
    private static final int LIBRARY = 400;

    private static Context application;
    private static MangaSigner signer;

    private Manga() {
    }

    static synchronized void ensure(Context context) {
        if (application == null) application = context.getApplicationContext();
        if (signer == null) signer = MangaSigner.ensure(context);
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

    static final class HttpTransport implements Transport {
        @Override
        public String get(String url) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Referer", SITE);
            // The default `User-Agent: Java/21` is a thing their edge has never
            // seen from a client of theirs; the fork already sends this one to
            // hanime.
            conn.setRequestProperty("User-Agent", Streams.USER_AGENT);
            try {
                int code = conn.getResponseCode();
                if (code != 200) throw new IllegalStateException("upstream HTTP " + code);
                try (InputStream in = conn.getInputStream()) {
                    return readAll(in);
                }
            } finally {
                conn.disconnect();
            }
        }
    }

    /**
     * One signed GET of their API, shaped as a JSONObject. The signer is given
     * a moment to boot on the first call; if it never does, the request is
     * still sent unsigned, because their own `403 Missing token.` is a clearer
     * failure than a timeout invented here.
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

        StringBuilder url = new StringBuilder(API).append(path);
        if (!qs.isEmpty()) url.append('?').append(qs);
        if (!vrf.isEmpty()) url.append(qs.isEmpty() ? '?' : '&').append("vrf=").append(URLEncoder.encode(vrf, "UTF-8"));

        Log.d(TAG, (vrf.isEmpty() ? "unsigned" : "signed") + " GET " + url);
        return new JSONObject(transport.get(url.toString()));
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

    /** The homepage shelf: trending titles. No paging — one shelf, one answer. */
    static JSONArray trending(int limit) throws Exception {
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

    /** The same call, from the resolved parameters — what the route uses. */
    static JSONObject search(SearchQuery search) throws Exception {
        return search(search.q, search.page, search.limit, search.type, search.status, search.genre);
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
    static JSONObject search(String q, int page, int limit, String type, String status, String genre)
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

    /** A chapter's pages, as absolute loopback URLs the reader can load. */
    static JSONObject pages(String chapterId) throws Exception {
        JSONObject body = get("/chapters/" + urlPart(chapterId), new JSONObject());
        JSONObject data = body.optJSONObject("data");
        JSONArray raw = data == null ? null : data.optJSONArray("pages");
        JSONArray out = new JSONArray();
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                JSONObject page = raw.optJSONObject(i);
                String url = page == null ? "" : page.optString("url", "");
                if (url.isEmpty()) continue;
                out.put(new JSONObject().put("u", proxied(url)));
            }
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
            out.put(new JSONObject()
                    .put("id", id)
                    .put("number", item.optString("number", ""))
                    .put("name", item.optString("name", ""))
                    .put("at", item.optString("updatedAt", "")));
        }
        return out;
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

    /**
     * One page's bytes, with the referer their CDN insists on. Cached by URL: a
     * chapter is re-read more often than it is read, and a strip of images is
     * the one thing here whose absence is visible on screen.
     */
    static Page image(String target) {
        Page hit;
        synchronized (PAGES) {
            hit = PAGES.get(target);
        }
        if (hit != null) return hit;

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Referer", SITE);
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            conn.setRequestProperty("User-Agent", Streams.USER_AGENT);
            try {
                int code = conn.getResponseCode();
                if (code != 200) throw new IllegalStateException("page returned HTTP " + code);
                String type = conn.getContentType();
                try (InputStream in = conn.getInputStream()) {
                    byte[] body = readBytes(in);
                    Page page = new Page(body, type == null || type.isEmpty() ? "image/jpeg" : type);
                    synchronized (PAGES) {
                        if (pageBytes + body.length <= PAGE_CACHE_BYTES) {
                            PAGES.put(target, page);
                            pageBytes += body.length;
                        }
                    }
                    return page;
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "page failed: " + target, e);
            return null;
        }
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
            } catch (Exception ignored) {
                // Unreadable is as good as absent: the new row replaces it.
            }
        }
        prefs.edit()
                .putStringSet("libhids", hids)
                .putString("lib:" + hid, stored.toString())
                .apply();
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
