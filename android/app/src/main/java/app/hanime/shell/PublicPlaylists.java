package app.hanime.shell;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The public-playlists feature, served from a dataset bundled in the APK.
 *
 * On the server this is an 82 MB crawl index (11k playlists, ~10k item files)
 * that took a long-running crawler to build. That is not reproducible on a
 * phone, so a trimmed subset ships with the app and is searched here with the
 * same rules: browse sorts by size, a title match returns the whole playlist,
 * and owner/tag filters narrow the pool first.
 *
 * A `playlists.json` asset with no dataset simply yields an empty result, so
 * the screen degrades instead of breaking.
 */
final class PublicPlaylists {

    private static final String TAG = "ShellPlaylists";
    private static final String ASSET = "playlists.json";

    private static volatile JSONArray records;
    private static volatile boolean loaded;

    private PublicPlaylists() {
    }

    // ---------------------------------------------------------------- entry

    /** GET /api/public/playlists and /api/public/playlists/:slug. */
    static String serve(String path, Map<String, List<String>> q, Context ctx) {
        try {
            if (path.equals("/api/public/playlists")) return search(q, ctx);
            if (path.equals("/api/public/playlists/owners")) return owners(ctx);

            String slug = path.substring("/api/public/playlists/".length());
            if (!slug.isEmpty()) return playlist(slug, q, ctx);
            return search(q, ctx);
        } catch (Exception e) {
            Log.w(TAG, "playlist request failed", e);
            return "{\"ok\":false,\"error\":\"" + safe(e) + "\"}";
        }
    }

    // ---------------------------------------------------------------- query

    private static String search(Map<String, List<String>> q, Context ctx) throws Exception {
        String query = fold(first(q, "q"));
        String owner = first(q, "owner");
        String tag = first(q, "tag");
        int limit = clamp(intOf(first(q, "limit"), 40), 1, 200);
        boolean includeItems = !"0".equals(first(q, "items"));

        JSONArray all = records(ctx);
        List<JSONObject> scoped = new ArrayList<>(all.length());
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p == null) continue;
            if (owner != null && !owner.isEmpty()
                    && !owner.equals(p.optString("owner_channel_slug", ""))) continue;
            if (tag != null && !tag.isEmpty() && !hasTag(p, tag)) continue;
            scoped.add(p);
        }

        List<JSONObject> rows = new ArrayList<>();
        if (query.isEmpty()) {
            // Browse: no query means "what is big and public".
            scoped.sort((a, b) -> Integer.compare(size(b), size(a)));
            for (int i = 0; i < scoped.size() && i < limit; i++) {
                rows.add(summarize(scoped.get(i), "browse", 0, new JSONArray()));
            }
        } else {
            for (JSONObject rec : scoped) {
                int ts = titleScore(rec, query);
                if (ts > 0) {
                    JSONArray items = includeItems ? rec.optJSONArray("items") : null;
                    rows.add(summarize(rec, "title", ts, items == null ? new JSONArray() : items));
                } else if (contentScore(rec, query) > 0) {
                    JSONArray hits = contentHits(rec, query, 50);
                    JSONObject row = summarize(rec, "content", 50, hits);
                    row.put("matched_on", rollupMatch(rec, query) ? "studio_or_tag" : "entries");
                    rows.add(row);
                }
                if (rows.size() >= limit) break;
            }
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("query", first(q, "q") == null ? "" : first(q, "q"));
        out.put("total", scoped.size());
        out.put("returned", rows.size());
        out.put("playlists", new JSONArray(rows));
        out.put("stats", stats(all));
        return out.toString();
    }

    private static String playlist(String slug, Map<String, List<String>> q, Context ctx)
            throws Exception {
        JSONArray all = records(ctx);
        for (int i = 0; i < all.length(); i++) {
            JSONObject rec = all.optJSONObject(i);
            if (rec == null || !slug.equals(rec.optString("slug", ""))) continue;

            JSONArray items = rec.optJSONArray("items");
            if (items == null) items = new JSONArray();

            // Mark which entries the bundled catalogue can actually play, so an
            // unplayable card is never rendered as a live button.
            JSONArray outItems = new JSONArray();
            int playable = 0, unresolved = 0;
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item == null) continue;
                JSONObject copy = new JSONObject(item.toString());
                boolean resolvable = false;
                try {
                    resolvable = Catalog.get().bySlug.containsKey(copy.optString("slug", ""));
                } catch (Exception ignored) {
                    // Catalogue unavailable: null means "unknown", not "dead".
                }
                copy.put("resolved", resolvable);
                if (resolvable) playable++;
                else if (!copy.isNull("resolved")) unresolved++;
                outItems.put(copy);
            }

            JSONObject out = summarize(rec, "browse", 0, outItems);
            out.put("ok", true);
            out.put("items", outItems);
            out.put("playable", playable);
            out.put("unresolved", unresolved);
            return out.toString();
        }
        return "{\"ok\":false,\"error\":\"not_found\",\"slug\":\"" + slug + "\"}";
    }

    private static String owners(Context ctx) throws Exception {
        JSONArray all = records(ctx);
        Map<String, JSONObject> owners = new HashMap<>();
        Map<String, Integer> tagCounts = new HashMap<>();

        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p == null) continue;
            String ownerSlug = p.optString("owner_channel_slug", "");
            String ownerName = p.optString("owner_name", "");
            if (!ownerSlug.isEmpty() && !ownerName.isEmpty()) {
                JSONObject o = owners.get(ownerSlug);
                if (o == null) {
                    o = new JSONObject();
                    o.put("channel_slug", ownerSlug);
                    o.put("name", ownerName);
                    o.put("avatar", p.optString("owner_avatar_url", ""));
                    o.put("playlists", 0);
                    o.put("items", 0);
                    owners.put(ownerSlug, o);
                }
                o.put("playlists", o.optInt("playlists", 0) + 1);
                o.put("items", o.optInt("items", 0) + size(p));
            }
            JSONArray tags = p.optJSONArray("tags");
            if (tags != null) {
                for (int t = 0; t < tags.length(); t++) {
                    JSONObject tag = tags.optJSONObject(t);
                    String text = tag == null ? tags.optString(t, "") : tag.optString("text", "");
                    if (!text.isEmpty()) tagCounts.put(text, tagCounts.getOrDefault(text, 0) + 1);
                }
            }
        }

        JSONArray ownerRows = new JSONArray(new ArrayList<>(owners.values()));
        JSONArray tagRows = new JSONArray();
        for (Map.Entry<String, Integer> e : tagCounts.entrySet()) {
            JSONObject row = new JSONObject();
            row.put("name", e.getKey());
            row.put("count", e.getValue());
            tagRows.put(row);
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("owners", ownerRows);
        out.put("tags", tagRows);
        return out.toString();
    }

    // -------------------------------------------------------------- shaping

    private static JSONObject summarize(JSONObject p, String matchedOn, int score,
                                        JSONArray items) throws Exception {
        JSONObject out = new JSONObject();
        out.put("slug", p.optString("slug", ""));
        out.put("title", p.optString("title", ""));
        out.put("cover_url", p.optString("cover_url", ""));
        out.put("video_count", p.optInt("video_count", p.optInt("count", 0)));
        out.put("item_count", size(p));
        out.put("views", p.optLong("views", 0));
        out.put("age_days", p.optLong("age_days", 0));
        out.put("owner_name", p.optString("owner_name", ""));
        out.put("owner_avatar_url", p.optString("owner_avatar_url", ""));
        out.put("owner_channel_slug", p.optString("owner_channel_slug", ""));
        out.put("visibility", p.optString("visibility", "public"));
        out.put("matched_on", matchedOn);
        if (score > 0) out.put("score", score);
        out.put("items", items);
        out.put("total_duration_ms", p.optLong("total_duration_ms", 0));
        return out;
    }

    private static int size(JSONObject p) {
        int n = p.optInt("item_count", -1);
        if (n >= 0) return n;
        JSONArray items = p.optJSONArray("items");
        if (items != null) return items.length();
        return p.optInt("video_count", p.optInt("count", 0));
    }

    private static boolean hasTag(JSONObject p, String want) {
        JSONArray tags = p.optJSONArray("tags");
        if (tags == null) return false;
        for (int i = 0; i < tags.length(); i++) {
            JSONObject t = tags.optJSONObject(i);
            String text = t == null ? tags.optString(i, "") : t.optString("text", "");
            if (fold(text).equals(fold(want))) return true;
        }
        return false;
    }

    private static final String[] TITLE_FIELDS = {
            "title", "owner_name", "owner_channel_slug", "slug",
    };

    private static int titleScore(JSONObject rec, String needle) {
        int best = 0;
        for (String f : TITLE_FIELDS) best = Math.max(best, scoreField(rec.optString(f, ""), needle));
        return best;
    }

    private static int scoreField(String value, String needle) {
        String h = fold(value);
        if (h.isEmpty()) return 0;
        if (h.equals(needle)) return 100;
        if (h.startsWith(needle)) return 80;
        if (h.contains(needle)) return 40;
        return 0;
    }

    private static int contentScore(JSONObject rec, String needle) {
        String text = rec.optString("item_text", "");
        if (text.isEmpty()) return 0;
        String h = text.toLowerCase(Locale.ROOT);
        if (!h.contains(needle)) return 0;
        return h.startsWith(needle) ? 60 : 50;
    }

    private static JSONArray contentHits(JSONObject rec, String needle, int limit) throws Exception {
        JSONArray items = rec.optJSONArray("items");
        if (items == null) return new JSONArray();
        List<JSONObject> hits = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            int s = Math.max(
                    scoreField(item.optString("name", ""), needle),
                    scoreField(item.optString("brand", ""), needle));
            if (s > 0) {
                JSONObject hit = new JSONObject(item.toString());
                hit.put("_score", s);
                hits.add(hit);
            }
        }
        if (hits.isEmpty()) {
            for (int i = 0; i < items.length() && i < limit; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) hits.add(item);
            }
        } else {
            hits.sort((a, b) -> {
                int c = Integer.compare(b.optInt("_score", 0), a.optInt("_score", 0));
                return c != 0 ? c : Long.compare(b.optLong("views", 0), a.optLong("views", 0));
            });
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < hits.size() && i < limit; i++) out.put(hits.get(i));
        return out;
    }

    private static boolean rollupMatch(JSONObject rec, String needle) {
        JSONArray brands = rec.optJSONArray("brands");
        if (brands != null) {
            for (int i = 0; i < brands.length(); i++) {
                JSONObject b = brands.optJSONObject(i);
                String t = b == null ? brands.optString(i, "") : b.optString("title", "");
                if (fold(t).contains(needle)) return true;
            }
        }
        JSONArray tags = rec.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                JSONObject t = tags.optJSONObject(i);
                String text = t == null ? tags.optString(i, "") : t.optString("text", "");
                if (fold(text).contains(needle)) return true;
            }
        }
        return false;
    }

    private static JSONObject stats(JSONArray all) throws Exception {
        int items = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p != null) items += size(p);
        }
        JSONObject out = new JSONObject();
        out.put("playlists", all.length());
        out.put("items", items);
        return out;
    }

    // ----------------------------------------------------------------- data

    private static JSONArray records(Context ctx) throws Exception {
        JSONArray hit = records;
        if (hit != null && loaded) return hit;
        synchronized (PublicPlaylists.class) {
            if (records == null) {
                JSONArray arr;
                try (InputStream in = ctx.getAssets().open(ASSET)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    JSONObject json = new JSONObject(
                            new String(out.toByteArray(), StandardCharsets.UTF_8));
                    arr = json.optJSONArray("playlists");
                    if (arr == null) arr = new JSONArray();
                } catch (Exception e) {
                    Log.w(TAG, "no bundled playlist dataset: " + e);
                    arr = new JSONArray();
                }
                records = arr;
                Log.i(TAG, "bundled playlists: " + arr.length());
            }
            loaded = true;
            return records;
        }
    }

    // ---------------------------------------------------------------- utils

    private static String fold(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String first(Map<String, List<String>> q, String key) {
        List<String> v = q.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static int intOf(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return (int) Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String safe(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        return m.replace("\\", "").replace("\"", "'").replace("\n", " ");
    }
}
