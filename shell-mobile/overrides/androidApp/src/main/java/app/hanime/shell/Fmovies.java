package app.hanime.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The films &amp; series area — /api/fmovies/*, the Java twin of
 * server/src/fmovies.mjs (the cinehd-shaped one).
 *
 * cinehd is a Next.js front end with no catalogue of its own: its pages are
 * rendered from TMDB's public API — search, trending, title details, seasons
 * and episodes all arrive as JSON with the key its own bundle carries — and
 * its player is an iframe onto a list of third-party doors (Max, Vidpro, V2,
 * Premium, 4K, Vidfast), each a URL template filled with the title's TMDB id
 * (and season/episode for a series). This is that shape, ported: one request
 * for a search, one for a title, one for a season's episodes, all from TMDB,
 * and the six doors exactly as cinehd orders them.
 *
 * The *f-movies* route names stay: they are the API contract the web client,
 * the addon, the desktop window and this Java twin all speak, and a rename
 * would strand every one of them for a change that is about where the data
 * comes from, not what it looks like. What changes with the source is the slug
 * — `breaking-bad-1396` (a readable name, then the TMDB id) instead of the old
 * scraped one — so a title saved from the old source is a title that has to be
 * found again. The desktop made the same change; this is the same one, late.
 *
 * The doors rotate and go dark, so each is knocked on when a title is opened
 * (once per URL per ten minutes) and a door that did not answer is a labelled
 * entry with its reason, never a black frame the reader has to guess at. The
 * knock is a seam the JVM tests replace, so a test never dials a host.
 *
 * `source` names where the catalogue came from; the client prints it. It is
 * "TMDB" now that this twin has moved with the Node one.
 */
final class Fmovies {

    private Fmovies() {}

    private static final String TMDB_API = "https://api.themoviedb.org/3";
    private static final String TMDB_IMAGE = "https://image.tmdb.org/t/p";
    /**
     * The key cinehd's own bundle ships, which is the same one every web reader
     * of that site uses. A build with its own TMDB key would put it in
     * `local.properties`, where this checkout keeps secrets — but the twin has
     * no such plumbing, and the public key needs none: it is published by the
     * page this data comes from.
     */
    private static final String PUBLIC_TMDB_KEY = "ef311eb0b9b07b9c73e9fb0a732cc150";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/131.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 12_000;
    /** A door has to say hello inside the request a reader is waiting on. */
    private static final int HEALTH_TIMEOUT_MS = 6_000;
    // TMDB's own page size, stated so it is not assumed.
    private static final int SEARCH_PAGE_SIZE = 20;

    // ------------------------------------------------------------- cache

    private static final long TTL_SEARCH = 15 * 60_000L;      // results move with the catalog
    private static final long TTL_CATALOG = 15 * 60_000L;
    private static final long TTL_DETAILS = 6 * 3600_000L;     // synopses and cast are near-static
    private static final long TTL_EPISODES = 6 * 3600_000L;
    private static final long TTL_HEALTH = 10 * 60_000L;       // a host that is down is down for a while

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
        NotFound(String note) {
            super("tmdb: " + note);
        }
    }

    static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** One GET, answered as text; a 404 is its own exception for the callers. */
    private static String fetch(String url, String accept) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BROWSER_UA);
        headers.put("Accept", accept);
        try {
            return transport.get(url, headers);
        } catch (Anime.UpstreamException e) {
            if (e.status == 404) throw new NotFound("not found");
            throw new Exception("tmdb: status " + e.status);
        } catch (Exception e) {
            throw new Exception("tmdb: " + e.getMessage());
        }
    }

    /**
     * GET a TMDB path with the key and the language added and the answer cached
     * per URL — the same one-request-per-answer contract as the Node twin.
     */
    private static JSONObject tmdb(String path, Map<String, String> params, long ttlMs) throws Exception {
        StringBuilder url = new StringBuilder(TMDB_API).append(path);
        url.append('?').append("language=en-US").append("&api_key=").append(PUBLIC_TMDB_KEY);
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                url.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
        }
        final String href = url.toString();
        return new JSONObject(cached("tmdb:" + href, ttlMs, () -> fetch(href, "application/json")));
    }

    /** The TMDB id a slug carries, or null when it carries none. */
    static Integer tmdbIdOf(String slug) {
        return idFromSlug(slug);
    }

    /**
     * A title's IMDb id, from its TMDB id — the key the subtitle source wants.
     *
     * A film's slug carries TMDB's id and nothing else, and the OpenSubtitles
     * addon is keyed by IMDb, so the one field that is missing is asked of
     * TMDB's own `external_ids` route. Held for hours with the rest of the
     * near-static data; a title TMDB has no IMDb id for is simply a title with
     * no subtitles from that source.
     */
    static String imdbId(String type, int tmdbId) {
        try {
            JSONObject body = tmdb("/" + type + "/" + tmdbId + "/external_ids", null, TTL_DETAILS);
            String imdb = body.optString("imdb_id", "").trim();
            return imdb.isEmpty() ? null : imdb;
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------- the doors

    /** One cinehd door: a label, and a URL template per kind. */
    static final class Door {
        final String id;
        final String label;
        final String movie;
        final String tv;

        Door(String id, String label, String movie, String tv) {
            this.id = id;
            this.label = label;
            this.movie = movie;
            this.tv = tv;
        }

        String url(String type, int tmdbId, int season, int episode) {
            return ("tv".equals(type) ? tv : movie)
                    .replace("{id}", String.valueOf(tmdbId))
                    .replace("{season}", String.valueOf(season))
                    .replace("{episode}", String.valueOf(episode));
        }
    }

    /** The doors, in cinehd's own order, with its own URL formats. */
    private static final List<Door> SERVERS = List.of(
            new Door("max", "Max",
                    "https://ythd.org/embed/{id}",
                    "https://ythd.org/embed/{id}/{season}-{episode}"),
            new Door("vidpro", "Vidpro",
                    "https://vixsrc.to/movie/{id}",
                    "https://vixsrc.to/tv/{id}/{season}/{episode}"),
            new Door("v2", "V2",
                    "https://player2.vidplus.pro/embed/movie/{id}?autoplay=true",
                    "https://player2.vidplus.pro/embed/tv/{id}/{season}/{episode}"),
            new Door("premium", "Premium",
                    "https://player.vidplus.pro/embed/movie/{id}?autoplay=true&download=true",
                    "https://player.vidplus.pro/embed/tv/{id}/{season}/{episode}"),
            new Door("4k", "4K",
                    "https://player.videasy.to/movie/{id}",
                    "https://player.videasy.to/tv/{id}/{season}/{episode}"),
            new Door("vidfast", "Vidfast",
                    "https://vidfast.vc/movie/{id}?autoplay=true",
                    "https://vidfast.vc/tv/{id}/{season}/{episode}?autoplay=true"));

    /** The door the in-app player reaches for first, exactly as cinehd orders. */
    private static final String IN_APP_FIRST = "vidpro";

    /**
     * The doors in the order the in-app player should try them, with the one
     * asked for — or Vidpro — at the front. A name that matches no door is not
     * an error: the list is simply the default one, which is what the caller
     * would have got by asking for nothing. The label only reorders the walk;
     * a door the reader named that then fails still leaves the others to try.
     */
    static List<Door> inAppOrder(String wanted) {
        String key = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        Door asked = null;
        if (!key.isEmpty()) {
            for (Door door : SERVERS) {
                if (door.id.equals(key) || door.label.toLowerCase(Locale.ROOT).equals(key)) {
                    asked = door;
                    break;
                }
            }
        }
        Door first = asked;
        if (first == null) {
            for (Door door : SERVERS) {
                if (door.id.equals(IN_APP_FIRST)) first = door;
            }
        }
        if (first == null) first = SERVERS.get(0);
        List<Door> out = new ArrayList<>();
        out.add(first);
        for (Door door : SERVERS) {
            if (door != first) out.add(door);
        }
        return out;
    }

    // ----------------------------------------------------------- shaping

    /** `Breaking Bad` -> `breaking-bad`; the id is appended by slugFor. */
    static String slugify(String text) {
        String value = text == null ? "" : text;
        value = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        value = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (value.length() > 60) value = value.substring(0, 60);
        return value.isEmpty() ? "title" : value;
    }

    private static String titleOf(String type, JSONObject raw) {
        String name = "tv".equals(type)
                ? raw.optString("name", raw.optString("original_name", ""))
                : raw.optString("title", raw.optString("original_title", ""));
        return name.isEmpty() ? "Untitled" : name;
    }

    private static Integer yearOf(JSONObject raw) {
        String date = raw.optString("release_date", "");
        if (date.isEmpty()) date = raw.optString("first_air_date", "");
        if (date.length() < 4) return null;
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            return year > 0 ? year : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The slug the whole app keys a title by: readable name, then its TMDB id. */
    private static String slugFor(String type, JSONObject raw) {
        return slugify(titleOf(type, raw)) + "-" + raw.optInt("id", 0);
    }

    private static String posterUrl(String path, String size) {
        return path == null || path.isEmpty() ? null : TMDB_IMAGE + "/" + size + path;
    }

    private static Double scoreOf(JSONObject raw) {
        double vote = raw.optDouble("vote_average", 0);
        if (vote <= 0) return null;
        return Math.round(vote * 10) / 10.0;
    }

    /** A TMDB row -> the card the shelf draws. */
    private static JSONObject shapeItem(JSONObject raw) throws JSONException {
        if (raw == null) return null;
        String mediaType = raw.optString("media_type", "");
        String type = "tv".equals(mediaType) ? "tv" : "movie".equals(mediaType) ? "movie" : null;
        if (type == null) return null;                 // a person is not a title

        JSONObject out = new JSONObject()
                .put("type", type)
                .put("slug", slugFor(type, raw))
                .put("title", titleOf(type, raw));
        Integer year = yearOf(raw);
        if (year != null) out.put("year", year);
        Double score = scoreOf(raw);
        if (score != null) out.put("score", score);
        String poster = posterUrl(raw.optString("poster_path", ""), "w500");
        if (poster != null) out.put("poster", poster);
        // A card is also the Home hero's artwork, and a hero drawn from a
        // portrait `w500` poster is a blur across the whole screen. TMDB's
        // trending and search rows already carry the backdrop path, so it is
        // passed along at full size; the shelf turns it into `background`.
        String backdrop = posterUrl(raw.optString("backdrop_path", ""), "original");
        if (backdrop != null) out.put("backdrop", backdrop);
        return out;
    }

    private static JSONArray asList(Object value) {
        JSONArray out = new JSONArray();
        if (value instanceof JSONArray) return (JSONArray) value;
        if (value instanceof JSONObject) out.put(value);
        return out;
    }

    private static String personName(Object value) {
        if (value instanceof JSONObject) {
            String name = ((JSONObject) value).optString("name", "");
            return name.isEmpty() ? null : name;
        }
        if (value instanceof String) return (String) value;
        return null;
    }

    /**
     * The details object the title page renders. One TMDB request carries the
     * credits and the external ids too (`append_to_response`), which is where
     * the cast, the director/creator and the IMDb id all come from.
     */
    private static JSONObject shapeDetails(String type, String slug, JSONObject raw) throws JSONException {
        JSONObject credits = raw.optJSONObject("credits");
        if (credits == null) credits = new JSONObject();
        JSONArray crew = asList(credits.opt("crew"));

        JSONArray creator = new JSONArray();
        if ("tv".equals(type)) {
            JSONArray madeBy = asList(raw.opt("created_by"));
            if (madeBy.length() == 0) madeBy = asList(credits.opt("created_by"));
            for (int i = 0; i < madeBy.length(); i++) {
                String name = personName(madeBy.opt(i));
                if (name != null) creator.put(name);
            }
        } else {
            for (int i = 0; i < crew.length(); i++) {
                JSONObject member = crew.optJSONObject(i);
                if (member == null || !"Director".equals(member.optString("job", ""))) continue;
                String name = personName(member);
                if (name != null) creator.put(name);
            }
        }

        JSONArray genres = new JSONArray();
        JSONArray rawGenres = asList(raw.opt("genres"));
        for (int i = 0; i < rawGenres.length(); i++) {
            JSONObject genre = rawGenres.optJSONObject(i);
            String name = genre == null ? "" : genre.optString("name", "");
            if (!name.isEmpty()) genres.put(name);
        }

        JSONArray cast = new JSONArray();
        JSONArray rawCast = asList(credits.opt("cast"));
        for (int i = 0; i < rawCast.length() && cast.length() < 20; i++) {
            String name = personName(rawCast.opt(i));
            if (name != null) cast.put(name);
        }

        JSONArray seasons = new JSONArray();
        if ("tv".equals(type)) {
            JSONArray rawSeasons = asList(raw.opt("seasons"));
            List<Integer> numbers = new ArrayList<>();
            for (int i = 0; i < rawSeasons.length(); i++) {
                JSONObject season = rawSeasons.optJSONObject(i);
                // Season 0 is TMDB's bucket for specials, not a season the strip
                // offers.
                if (season == null) continue;
                int number = season.optInt("season_number", -1);
                if (number > 0) numbers.add(number);
            }
            numbers.sort(Integer::compareTo);
            for (int number : numbers) seasons.put(number);
        }

        JSONObject external = raw.optJSONObject("external_ids");

        JSONObject out = new JSONObject()
                .put("type", type)
                .put("slug", slug)
                .put("title", titleOf(type, raw))
                .put("seasons", seasons)
                .put("genres", genres)
                .put("cast", cast)
                .put("creator", creator);
        String description = raw.optString("overview", "");
        if (!description.isEmpty()) out.put("description", description);
        String poster = posterUrl(raw.optString("poster_path", ""), "w500");
        if (poster != null) out.put("poster", poster);
        String backdrop = posterUrl(raw.optString("backdrop_path", ""), "original");
        if (backdrop != null) out.put("backdrop", backdrop);
        Integer year = yearOf(raw);
        if (year != null) out.put("year", year);
        Double score = scoreOf(raw);
        if (score != null) out.put("score", score);
        if (raw.has("number_of_seasons")) out.put("numberOfSeasons", raw.optInt("number_of_seasons"));
        if (raw.has("number_of_episodes")) out.put("numberOfEpisodes", raw.optInt("number_of_episodes"));
        if (external != null) {
            String imdb = external.optString("imdb_id", "");
            if (!imdb.isEmpty()) out.put("imdb_id", imdb);
        }
        return out;
    }

    // ---------------------------------------------------------------- routes

    /**
     * GET /api/fmovies/search?q=&page=&type=
     * With a q: TMDB's multi search, optionally narrowed to movie or tv.
     * Without one: the week's trending shelf — mixed, or the one kind asked for.
     */
    private static Result search(Map<String, String> q) throws Exception {
        String needle = q.get("q") == null ? "" : q.get("q").trim();
        int page = Math.max(1, intPipeZero(q.get("page") == null ? "1" : q.get("page")));
        String typeParam = q.get("type") == null ? "" : q.get("type");
        String type = "tv".equals(typeParam) ? "tv" : "movie".equals(typeParam) ? "movie" : "";

        JSONObject body;
        if (!needle.isEmpty()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("query", needle);
            params.put("page", String.valueOf(page));
            params.put("include_adult", "false");
            body = tmdb("/search/multi", params, TTL_SEARCH);
        } else {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("page", String.valueOf(page));
            body = tmdb(type.isEmpty() ? "/trending/all/week" : "/trending/" + type + "/week",
                    params, TTL_CATALOG);
        }

        JSONArray all = new JSONArray();
        JSONArray raw = body.optJSONArray("results");
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                JSONObject item = shapeItem(raw.optJSONObject(i));
                if (item != null) all.put(item);
            }
        }
        JSONArray items = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (type.isEmpty() || type.equals(item.optString("type"))) items.put(item);
        }

        // A page of the mixed search can be narrowed to nothing; the page count
        // is what upstream says, not what survived the filter.
        int totalPages = body.optInt("total_pages", 0);
        boolean hasNext = totalPages > 0 ? page < totalPages : all.length() >= SEARCH_PAGE_SIZE;
        JSONObject out = new JSONObject()
                .put("q", needle)
                .put("page", page)
                .put("type", type)
                .put("source", "TMDB")
                .put("hasNext", hasNext)
                .put("items", items);
        return new Result(200, out.toString());
    }

    /** The id in a slug, or null when the slug carries none. */
    private static Integer idFromSlug(String slug) {
        Matcher m = Pattern.compile("-(\\d+)$").matcher(slug == null ? "" : slug);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The one details request, shared by the title page and anything else. */
    private static JSONObject detailsRaw(String type, int id) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("append_to_response", "credits,external_ids");
        return tmdb("/" + type + "/" + id, params, TTL_DETAILS);
    }

    /** GET /api/fmovies/(movie|tv)/:slug — one title. */
    private static Result details(String type, String slug) throws Exception {
        Integer id = idFromSlug(slug);
        if (id == null) return notFound("/" + type + "/" + slug);
        JSONObject raw = detailsRaw(type, id);
        return new Result(200, new JSONObject()
                .put("details", shapeDetails(type, slug, raw)).toString());
    }

    /**
     * GET /api/fmovies/tv/:slug/episodes?season=N — the episode rows of one
     * season. TMDB answers a season with its real episode numbers and names; a
     * season the title does not have is a 404, not silently the first one's.
     */
    private static Result episodes(String slug, Map<String, String> q) throws Exception {
        Integer id = idFromSlug(slug);
        if (id == null) return notFound("/tv/" + slug + "/episodes");
        int season = Math.max(1, intPipeZero(q.get("season") == null ? "1" : q.get("season")));

        JSONObject body;
        try {
            body = tmdb("/tv/" + id + "/season/" + season, null, TTL_EPISODES);
        } catch (NotFound e) {
            return new Result(404, new JSONObject()
                    .put("error", "season_not_found").put("slug", slug).put("season", season).toString());
        }

        List<JSONObject> rows = new ArrayList<>();
        JSONArray raw = body.optJSONArray("episodes");
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                JSONObject ep = raw.optJSONObject(i);
                if (ep == null) continue;
                int number = ep.optInt("episode_number", 0);
                if (number <= 0) continue;
                String name = ep.optString("name", "");
                rows.add(new JSONObject()
                        .put("n", number)
                        .put("title", name.isEmpty() ? JSONObject.NULL : name));
            }
        }
        rows.sort((a, b) -> Integer.compare(a.optInt("n"), b.optInt("n")));
        if (rows.isEmpty()) {
            return new Result(404, new JSONObject()
                    .put("error", "no_episodes").put("slug", slug).put("season", season).toString());
        }
        JSONArray data = new JSONArray();
        for (JSONObject row : rows) data.put(row);
        return new Result(200, new JSONObject()
                .put("slug", slug).put("season", season).put("data", data).toString());
    }

    /**
     * GET /api/fmovies/(movie|tv)/:slug/player?season=&episode= — one URL per
     * door, filled with the title's TMDB id, plus whether that door answered.
     * The client opens the first one that did, so a door that went dark is a
     * labelled button rather than a black player.
     *
     * `probe=0` skips the knock entirely. The in-app player does its own walk —
     * it opens each door in turn and stops at the first that hands over a
     * playlist — so a health check first would answer the same question twice,
     * six times over, and spend that many timeouts before the walk began.
     */
    private static Result player(String type, String slug, Map<String, String> q) throws Exception {
        Integer id = idFromSlug(slug);
        if (id == null) return notFound("/" + type + "/" + slug + "/player");
        int season = Math.max(1, intPipeZero(q.get("season") == null ? "1" : q.get("season")));
        int episode = Math.max(1, intPipeZero(q.get("episode") == null ? "1" : q.get("episode")));
        boolean knock = !"0".equals(q.get("probe"));

        JSONArray sources = new JSONArray();
        for (Door door : SERVERS) {
            String url = door.url(type, id, season, episode);
            Health probe = knock ? health(url) : new Health(true, null);
            sources.put(new JSONObject()
                    .put("id", door.id)
                    .put("label", door.label)
                    .put("url", url)
                    .put("ok", probe.ok)
                    .put("note", probe.ok ? JSONObject.NULL : probe.note));
        }
        return new Result(200, new JSONObject()
                .put("type", type)
                .put("slug", slug)
                .put("id", id)
                .put("season", season)
                .put("episode", episode)
                .put("sources", sources).toString());
    }

    // ---------------------------------------------------------------- health

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
     * A dead host is the failure that actually happened before: doors stopped
     * resolving or refused to be framed, so the player showed black and said
     * nothing. Reachability and the framing headers are the two questions a URL
     * template cannot answer, so they are answered here — once per URL per ten
     * minutes, so opening a player does not dial six hosts every time. The body
     * is dropped unread: this is a knock, not a download.
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
                conn.setConnectTimeout(HEALTH_TIMEOUT_MS);
                conn.setReadTimeout(HEALTH_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", BROWSER_UA);
                // No referer: the doors are asked the way the Node twin asks
                // them — as a browser that followed a link would be.
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
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

    // ---------------------------------------------------------------- routing

    /**
     * One entry point for the whole /api/fmovies family, called from
     * SekaiServer with the path and the first value of each query key.
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
            // An upstream 404 stays a 404, with the reason attached.
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
