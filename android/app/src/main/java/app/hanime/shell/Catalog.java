package app.hanime.shell;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The catalogue, fetched straight from upstream and filtered here.
 *
 * This is the part of the proxy that never needed a proxy: the guest catalogue
 * endpoint is open — no signature, no CSRF, no cookie — and sends
 * `access-control-allow-origin: *`. It answers with the *entire* library in one
 * ~4.5 MB document, so, exactly like the server, we fetch it once and filter
 * locally rather than pretending there is a query API upstream.
 *
 * The handlers mirror server/src/server.mjs (handleVideos / handleVideo /
 * handleTags / handleBrands) so the web client cannot tell which one answered.
 */
final class Catalog {

    private static final String TAG = "ShellCatalog";
    private static final String CATALOG_URL =
            "https://guest.freeanimehentai.net/api/v11/search_hvs";
    private static final long TTL_MS = 10 * 60 * 1000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** The same allow-list the server uses; anything else falls back. */
    private static final String[] SORTS = {
            "released_at_unix", "created_at_unix", "views", "likes", "name",
    };

    private static volatile Snapshot cache;

    private Catalog() {
    }

    static final class Snapshot {
        final long at;
        /** Raw upstream rows, in catalogue order. */
        final JSONArray items;
        /** slug -> raw row. */
        final Map<String, JSONObject> bySlug;
        /** slug -> already-shaped row, built once so each request is cheap. */
        final Map<String, String> shapedBySlug;

        Snapshot(long at, JSONArray items, Map<String, JSONObject> bySlug,
                 Map<String, String> shapedBySlug) {
            this.at = at;
            this.items = items;
            this.bySlug = bySlug;
            this.shapedBySlug = shapedBySlug;
        }

        boolean fresh() {
            return System.currentTimeMillis() - at < TTL_MS;
        }
    }

    /** Fetch (or reuse) the library. Synchronised so concurrent callers share one request. */
    static Snapshot get() throws Exception {
        Snapshot hit = cache;
        if (hit != null && hit.fresh()) return hit;
        synchronized (Catalog.class) {
            hit = cache;
            if (hit != null && hit.fresh()) return hit;

            long start = System.currentTimeMillis();
            JSONArray data = fetch();
            Map<String, JSONObject> bySlug = new HashMap<>();
            Map<String, String> shapedBySlug = new HashMap<>(data.length() * 2);
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String slug = item.optString("slug", "");
                if (slug.isEmpty()) continue;
                bySlug.put(slug, item);
                shapedBySlug.put(slug, shape(item).toString());
            }
            cache = new Snapshot(System.currentTimeMillis(), data, bySlug, shapedBySlug);
            Log.i(TAG, "catalog warm: " + data.length() + " entries in "
                    + (System.currentTimeMillis() - start) + "ms");
            return cache;
        }
    }

    private static JSONArray fetch() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(CATALOG_URL).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("catalogue returned HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                String body = readAll(in);
                JSONObject json = new JSONObject(body);
                JSONArray data = json.optJSONArray("data");
                if (data == null) throw new IllegalStateException("catalogue has no data array");
                return data;
            }
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------- handlers

    /** GET /api/videos — paged, filtered, sorted catalogue. */
    static String videos(Map<String, List<String>> q) throws Exception {
        int page = Math.max(0, intOf(first(q, "page"), 0));
        int perPage = Math.min(100, Math.max(1, intOf(first(q, "per_page"), 30)));
        String needle = lower(trim(first(q, "q")));
        List<String> tags = new ArrayList<>();
        for (String raw : all(q, "tags")) {
            for (String piece : raw.split(",")) {
                String t = lower(trim(piece));
                if (!t.isEmpty()) tags.add(t);
            }
        }
        String brand = lower(trim(first(q, "brand")));
        String orderBy = first(q, "order_by");
        if (orderBy == null || !isSort(orderBy)) orderBy = "released_at_unix";
        boolean desc = !"asc".equals(first(q, "ordering"));

        JSONArray items = get().items;
        List<JSONObject> rows = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject v = items.optJSONObject(i);
            if (v == null) continue;

            if (!brand.isEmpty() && !brand.equals(lower(v.optString("brand", "")))) continue;

            if (!tags.isEmpty()) {
                List<String> have = new ArrayList<>();
                JSONArray vt = v.optJSONArray("tags");
                if (vt != null) {
                    for (int t = 0; t < vt.length(); t++) have.add(lower(vt.optString(t, "")));
                }
                boolean all = true;
                for (String t : tags) {
                    if (!have.contains(t)) {
                        all = false;
                        break;
                    }
                }
                if (!all) continue;
            }

            if (!needle.isEmpty()) {
                String hay = lower(v.optString("name", "")) + " "
                        + lower(v.optString("search_titles", "")) + " "
                        + lower(v.optString("brand", ""));
                if (!hay.contains(needle)) continue;
            }
            rows.add(v);
        }

        // `orderBy` is only defaulted above; the comparator needs a final key.
        final String sortKey = orderBy;
        rows.sort((a, b) -> compare(a.opt(sortKey), b.opt(sortKey), desc));

        int total = rows.size();
        int start = page * perPage;
        JSONArray pageRows = new JSONArray();
        if (start < total) {
            int end = Math.min(total, start + perPage);
            Map<String, String> shaped = get().shapedBySlug;
            for (int i = start; i < end; i++) {
                JSONObject row = rows.get(i);
                String slug = row.optString("slug", "");
                String s = shaped.get(slug);
                pageRows.put(s != null ? new JSONObject(s) : shape(row));
            }
        }

        JSONObject out = new JSONObject();
        out.put("page", page);
        out.put("per_page", perPage);
        out.put("total", total);
        out.put("pages", perPage > 0 ? (int) Math.ceil((double) total / perPage) : 0);
        out.put("data", pageRows);
        return out.toString();
    }

    /** GET /api/videos/:slug — one entry plus its canonical watch URL. */
    static String video(String slug) throws Exception {
        JSONObject item = get().bySlug.get(slug);
        if (item == null) return null;
        JSONObject out = new JSONObject(get().shapedBySlug.get(slug));
        out.put("watch_url", "https://hanime.tv/videos/hentai/" + slug);
        return out.toString();
    }

    /** GET /api/tags — tag histogram over the catalogue. */
    static String tags() throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        JSONArray items = get().items;
        for (int i = 0; i < items.length(); i++) {
            JSONObject v = items.optJSONObject(i);
            JSONArray vt = v == null ? null : v.optJSONArray("tags");
            if (vt == null) continue;
            for (int t = 0; t < vt.length(); t++) {
                String tag = vt.optString(t, "");
                if (tag.isEmpty()) continue;
                counts.put(tag, counts.getOrDefault(tag, 0) + 1);
            }
        }
        return histogram(counts);
    }

    /** GET /api/brands — studio histogram over the catalogue. */
    static String brands() throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        JSONArray items = get().items;
        for (int i = 0; i < items.length(); i++) {
            JSONObject v = items.optJSONObject(i);
            String brand = v == null ? "" : v.optString("brand", "");
            if (brand.isEmpty()) continue;
            counts.put(brand, counts.getOrDefault(brand, 0) + 1);
        }
        return histogram(counts);
    }

    private static String histogram(Map<String, Integer> counts) throws Exception {
        // Highest count first; ties broken alphabetically so the ordering is
        // stable between refreshes (the server relies on insertion order here).
        TreeSet<String> names = new TreeSet<>();
        names.addAll(counts.keySet());
        List<String> ordered = new ArrayList<>(names);
        ordered.sort((a, b) -> {
            int c = Integer.compare(counts.get(b), counts.get(a));
            return c != 0 ? c : a.compareTo(b);
        });

        JSONArray data = new JSONArray();
        for (String name : ordered) {
            JSONObject row = new JSONObject();
            row.put("name", name);
            row.put("count", counts.get(name));
            data.put(row);
        }
        JSONObject out = new JSONObject();
        out.put("total", data.length());
        out.put("data", data);
        return out.toString();
    }

    // ---------------------------------------------------------------- shaping

    /** The server's shape(): only the fields the client is allowed to see. */
    static JSONObject shape(JSONObject item) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", item.opt("id"));
        out.put("slug", item.optString("slug", ""));
        out.put("name", item.optString("name", ""));
        out.put("description", item.has("description") && !item.isNull("description")
                ? item.optString("description") : JSONObject.NULL);
        out.put("cover", item.isNull("cover_url") ? JSONObject.NULL : item.optString("cover_url", ""));
        out.put("poster", item.isNull("poster_url") ? JSONObject.NULL : item.optString("poster_url", ""));
        out.put("brand", item.isNull("brand") ? JSONObject.NULL : item.optString("brand", ""));
        out.put("brand_id", item.isNull("brand_id") ? JSONObject.NULL : item.opt("brand_id"));
        JSONArray tags = item.optJSONArray("tags");
        out.put("tags", tags != null ? tags : new JSONArray());
        out.put("views", item.optLong("views", 0));
        out.put("likes", item.optLong("likes", 0));
        out.put("dislikes", item.optLong("dislikes", 0));
        out.put("downloads", item.optLong("downloads", 0));
        out.put("released_at", item.isNull("released_at") ? JSONObject.NULL
                : item.optString("released_at", ""));
        out.put("released_at_unix", item.optLong("released_at_unix", 0));
        return out;
    }

    // ------------------------------------------------------------------ utils

    /**
     * Mirrors the server's `a > b ? dir : a < b ? -dir : 0`, including its
     * tolerance for absent fields, but keeps the comparator consistent so
     * Java's sort cannot reject it as ill-formed.
     */
    private static int compare(Object a, Object b, boolean desc) {
        int c;
        if (a instanceof Number && b instanceof Number) {
            c = Long.compare(((Number) a).longValue(), ((Number) b).longValue());
        } else if (a instanceof String && b instanceof String) {
            c = ((String) a).compareTo((String) b);
        } else if (a == null || a == JSONObject.NULL) {
            c = b instanceof Number ? Long.compare(0, ((Number) b).longValue())
                    : b instanceof String ? -((String) b).compareTo("")
                    : 0;
        } else if (b == null || b == JSONObject.NULL) {
            c = a instanceof Number ? Long.compare(((Number) a).longValue(), 0)
                    : a instanceof String ? ((String) a).compareTo("")
                    : 0;
        } else {
            c = String.valueOf(a).compareTo(String.valueOf(b));
        }
        return desc ? -c : c;
    }

    private static boolean isSort(String key) {
        for (String s : SORTS) {
            if (s.equals(key)) return true;
        }
        return false;
    }

    private static String first(Map<String, List<String>> q, String key) {
        List<String> v = q.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static List<String> all(Map<String, List<String>> q, String key) {
        List<String> v = q.get(key);
        return v == null ? new ArrayList<>() : v;
    }

    private static int intOf(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return (int) Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
