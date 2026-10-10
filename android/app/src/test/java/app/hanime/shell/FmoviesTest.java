package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The /api/fmovies route family with the upstream stubbed: the TMDB search
 * JSON for queries, the site's static listing/detail/episode HTML for
 * everything else. The player route fetches twice over — the site's own player
 * page, which names the doors it offers, and then each door itself, to say
 * which ones answer. These are the JVM twins of server/test/fmovies.test.mjs;
 * both suites pin the same response contract.
 */
public class FmoviesTest {

    private Anime.Transport realTransport;
    private FakeTransport fake;
    private Fmovies.Prober realProber;
    private FakeProber knock;

    @Before
    public void setUp() {
        realTransport = Fmovies.transport;
        fake = new FakeTransport();
        Fmovies.transport = fake;
        realProber = Fmovies.prober;
        knock = new FakeProber();
        Fmovies.prober = knock;
        Fmovies.clearCache();
    }

    @After
    public void tearDown() {
        Fmovies.transport = realTransport;
        Fmovies.prober = realProber;
        Fmovies.clearCache();
    }

    /** A knock on a door, by host substring, and a recorded log of them. */
    static final class FakeProber implements Fmovies.Prober {
        final Map<String, Fmovies.Health> answers = new HashMap<>();
        final List<String> knocks = new ArrayList<>();

        @Override
        public Fmovies.Health knock(String url) {
            knocks.add(url);
            for (Map.Entry<String, Fmovies.Health> e : answers.entrySet()) {
                if (url.contains(e.getKey())) return e.getValue();
            }
            throw new AssertionError("unstubbed knock: " + url);
        }
    }

    /** A transport double that answers by URL substring, and can fail on one. */
    static final class FakeTransport implements Anime.Transport {
        int calls = 0;
        String lastUrl;
        Map<String, String> lastHeaders = new HashMap<>();
        final Map<String, String> routes = new HashMap<>();
        final Map<String, Exception> failures = new HashMap<>();

        private String route(String url) throws Exception {
            for (Map.Entry<String, Exception> e : failures.entrySet()) {
                if (url.contains(e.getKey())) throw e.getValue();
            }
            for (Map.Entry<String, String> e : routes.entrySet()) {
                if (url.contains(e.getKey())) return e.getValue();
            }
            throw new AssertionError("unstubbed fetch: " + url);
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) {
            throw new AssertionError("f-movies never posts");
        }

        @Override
        public String get(String url, Map<String, String> headers) throws Exception {
            calls++;
            lastUrl = url;
            lastHeaders = headers;
            return route(url);
        }
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    // ------------------------------------------------------------- fixtures

    // One movie row and one series row, in the shape the /api/search proxy
    // returns (TMDB rows with a f-movies href and a w92 poster).
    private static final String TMDB_SEARCH = "{\"page\":1,\"total_results\":42,\"results\":["
            + "{\"id\":580175,\"media_type\":\"movie\",\"href\":\"/movie/cavegirl-580175\","
            + "\"title\":\"Cavegirl\",\"poster_url\":\"https://image.tmdb.org/t/p/w92/cave.jpg\","
            + "\"release_date\":\"2021-03-01\",\"vote_average\":5.9},"
            + "{\"id\":1396,\"media_type\":\"tv\",\"href\":\"/tv/breaking-bad-1396\","
            + "\"name\":\"Breaking Bad\",\"poster_url\":\"https://image.tmdb.org/t/p/w92/bb.jpg\","
            + "\"first_air_date\":\"2008-01-20\",\"vote_average\":8.9}]}";

    // A listing page in the site's real card shape: the ★ score and the year
    // live in the text row *after* the article, still inside the anchor.
    private static final String LISTING = ""
            + "<div><a href=\"/movie/cavegirl-580175\" class=\"group block min-w-0\">"
            + "<article><img src=\"https://image.tmdb.org/t/p/w500/cave.jpg\" alt=\"Cavegirl\"></article>"
            + "<div class=\"mt-1.5 flex items-center gap-x-1.5 text-sm\">"
            + "<span><svg class=\"star\"><path></path></svg>★</span>"
            + "<span class=\"text-[#ff8736]\">5.9</span>"
            + "<span>·</span><span>2021</span><span>·</span><span>EN</span></div></a>"
            + "<a href=\"/tv/breaking-bad-1396\" class=\"group block min-w-0\">"
            + "<article><img src=\"https://image.tmdb.org/t/p/w500/bb.jpg\" alt=\"Breaking Bad\"></article>"
            + "<div class=\"mt-1.5 flex items-center gap-x-1.5 text-sm\">"
            + "<span><svg class=\"star\"><path></path></svg>★</span>"
            + "<span class=\"text-[#ff8736]\">8.9</span>"
            + "<span>·</span><span>2008</span><span>·</span><span>EN</span></div></a>"
            + "<a href=\"/movies?page=2\">Next</a>";

    // The series detail page: JSON-LD in an @graph, the IMDb row, and season
    // + episode anchors with entity-encoded ampersands, exactly as served.
    private static final String TV_DETAIL = ""
            + "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@graph\":["
            + "{\"@type\":\"Organization\",\"name\":\"F-Movies\"},"
            + "{\"@type\":\"TVSeries\",\"name\":\"Breaking Bad\","
            + "\"description\":\"A <b>chemistry</b> teacher.\","
            + "\"genre\":[\"Crime\",\"Drama\"],\"dateCreated\":\"2008-01-20\","
            + "\"numberOfSeasons\":5,\"numberOfEpisodes\":62,"
            + "\"image\":\"https://image.tmdb.org/t/p/original/bb.jpg\","
            + "\"actor\":[{\"@type\":\"Person\",\"name\":\"Bryan Cranston\"},"
            + "{\"@type\":\"Person\",\"name\":\"Aaron Paul\"}],"
            + "\"creator\":{\"@type\":\"Person\",\"name\":\"Vince Gilligan\"}}]}</script>"
            + "<dl class=\"info\">"
            + "<dt class=\"text-[#768293]\">Episodes:</dt><dd>62</dd>"
            + "<dt class=\"text-[#768293]\">IMDb:</dt>"
            + "<dd class=\"ml-2 text-sm font-semibold\">9.0</dd></dl>"
            + "<a href=\"?season=1&#38;episode=1\">S1</a>"
            + "<a href=\"?season=5&#38;episode=1\">S5</a>"
            + "<a href=\"?season=5&#38;episode=1\" title=\"Cat&#39;s in the Bag...\" class=\"ep\">1</a>"
            + "<a href=\"?season=5&#38;episode=2\" title=\"...And the Bag&#39;s in the River\""
            + " class=\"ep\">2</a>"
            + "<a href=\"?season=5&#38;episode=16\" title=\"Felina\" class=\"ep\">16</a>";

    private static final String MOVIE_DETAIL = ""
            + "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@graph\":["
            + "{\"@type\":\"Movie\",\"name\":\"Cavegirl\",\"description\":\"A cavegirl tale.\","
            + "\"genre\":[\"Fantasy\"],\"dateCreated\":\"1985-01-01\","
            + "\"image\":\"https://image.tmdb.org/t/p/original/cave.jpg\","
            + "\"actor\":[{\"@type\":\"Person\",\"name\":\"Raylon\"}]}]}</script>"
            + "<dl><dt>IMDb:</dt>"
            + "<dd class=\"ml-2 text-sm font-semibold\">4.1</dd></dl>";

    // ---------------------------------------------------------------- routes

    @Test
    public void aQueryIsAnsweredByTheTmdbSearchInBothTypesByDefault() throws Exception {
        fake.routes.put("/api/search", TMDB_SEARCH);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/search", q("q", "breaking", "page", "1"));
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("breaking", body.getString("q"));
        assertEquals("", body.getString("type"));
        assertFalse(body.getBoolean("hasNext"));              // 2 of 20 rows
        JSONArray items = body.getJSONArray("items");
        assertEquals(2, items.length());
        JSONObject first = items.getJSONObject(0);
        assertEquals("movie", first.getString("type"));
        assertEquals("cavegirl-580175", first.getString("slug"));
        assertEquals("Cavegirl", first.getString("title"));
        assertEquals(2021, first.getInt("year"));
        assertEquals(5.9, first.getDouble("score"), 1e-9);
        assertEquals("https://image.tmdb.org/t/p/w500/cave.jpg", first.getString("poster"));
        assertTrue("the search must carry the query and the page: " + fake.lastUrl,
                fake.lastUrl.contains("q=breaking&page=1&limit=20"));
        assertTrue(fake.lastHeaders.get("User-Agent").contains("Mozilla"));
    }

    @Test
    public void typeNarrowsTheSearchServerSide() throws Exception {
        fake.routes.put("/api/search", TMDB_SEARCH);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/search",
                q("q", "breaking", "page", "1", "type", "tv"));
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("tv", body.getString("type"));
        JSONArray items = body.getJSONArray("items");
        assertEquals(1, items.length());
        assertEquals("tv", items.getJSONObject(0).getString("type"));
    }

    @Test
    public void anEmptyQueryReadsTheMoviesListingPage() throws Exception {
        fake.routes.put("/movies?page=1", LISTING);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/search", q());
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("movie", body.getString("type"));
        assertTrue("the page links ?page=2", body.getBoolean("hasNext"));
        JSONArray items = body.getJSONArray("items");
        assertEquals(2, items.length());
        assertEquals("cavegirl-580175", items.getJSONObject(0).getString("slug"));
        assertEquals(2021, items.getJSONObject(0).getInt("year"));
        assertEquals(5.9, items.getJSONObject(0).getDouble("score"), 1e-9);
        assertEquals("breaking-bad-1396", items.getJSONObject(1).getString("slug"));
    }

    @Test
    public void anEmptyQueryWithTypeTvReadsTvSeriesInstead() throws Exception {
        fake.routes.put("/tv-series?page=1", LISTING);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/search", q("type", "tv"));
        assertEquals(200, res.status);
        assertEquals("tv", new JSONObject(res.body).getString("type"));
        assertTrue("the series shelf must read /tv-series: " + fake.lastUrl,
                fake.lastUrl.contains("/tv-series?page=1"));
    }

    @Test
    public void theDetailPagesJsonLdBecomesTheDetailsObject() throws Exception {
        fake.routes.put("/tv/breaking-bad-1396", TV_DETAIL);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/tv/breaking-bad-1396", q());
        assertEquals(200, res.status);
        JSONObject details = new JSONObject(res.body).getJSONObject("details");
        assertEquals("tv", details.getString("type"));
        assertEquals("Breaking Bad", details.getString("title"));
        assertEquals("A chemistry teacher.", details.getString("description"));
        assertEquals("https://image.tmdb.org/t/p/original/bb.jpg", details.getString("poster"));
        assertEquals(2008, details.getInt("year"));
        assertEquals(9.0, details.getDouble("score"), 1e-9);
        JSONArray genres = details.getJSONArray("genres");
        assertEquals("Crime", genres.getString(0));
        assertEquals("Drama", genres.getString(1));
        JSONArray cast = details.getJSONArray("cast");
        assertEquals("Bryan Cranston", cast.getString(0));
        assertEquals("Aaron Paul", cast.getString(1));
        assertEquals("Vince Gilligan", details.getJSONArray("creator").getString(0));
        JSONArray seasons = details.getJSONArray("seasons");
        assertEquals(1, seasons.getInt(0));
        assertEquals(5, seasons.getInt(1));
        assertEquals(5, details.getInt("numberOfSeasons"));
        assertEquals(62, details.getInt("numberOfEpisodes"));
    }

    @Test
    public void aMovieDetailHasNoSeasons() throws Exception {
        fake.routes.put("/movie/cavegirl-580175", MOVIE_DETAIL);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/movie/cavegirl-580175", q());
        assertEquals(200, res.status);
        JSONObject details = new JSONObject(res.body).getJSONObject("details");
        assertEquals("Cavegirl", details.getString("title"));
        assertEquals(1985, details.getInt("year"));
        assertEquals(4.1, details.getDouble("score"), 1e-9);
        assertEquals(0, details.getJSONArray("seasons").length());
    }

    @Test
    public void anUpstream404OnDetailsPropagatesAs404() throws Exception {
        fake.failures.put("/tv/nowhere-1", new Anime.UpstreamException(404, ""));

        Fmovies.Result res = Fmovies.handle("/api/fmovies/tv/nowhere-1", q());
        assertEquals(404, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("upstream_error", body.getString("error"));
        assertTrue(body.getString("message").contains("f-movies"));
    }

    @Test
    public void episodesComeFromTheSeasonPageWithDecodedTitles() throws Exception {
        fake.routes.put("/tv/breaking-bad-1396?season=5&episode=1", TV_DETAIL);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/tv/breaking-bad-1396/episodes",
                q("season", "5"));
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals(5, body.getInt("season"));
        JSONArray data = body.getJSONArray("data");
        assertEquals(3, data.length());
        assertEquals(1, data.getJSONObject(0).getInt("n"));
        assertEquals("Cat's in the Bag...", data.getJSONObject(0).getString("title"));
        assertEquals("Felina", data.getJSONObject(2).getString("title"));
        assertTrue("the episode page must be asked for its season: " + fake.lastUrl,
                fake.lastUrl.contains("season=5&episode=1"));
    }

    @Test
    public void aSeasonThePageDoesNotOfferIsA404NotTheFirstSeason() throws Exception {
        fake.routes.put("/tv/breaking-bad-1396?season=9&episode=1", TV_DETAIL);

        Fmovies.Result res = Fmovies.handle("/api/fmovies/tv/breaking-bad-1396/episodes",
                q("season", "9"));
        assertEquals(404, res.status);
        assertEquals("season_not_found", new JSONObject(res.body).getString("error"));
    }

        @Test
    public void thePlayerNamesTheDoorsTheSiteNamesAndSaysWhichAnswer() throws Exception {
        fake.routes.put("/watch/index.html", WATCH_PAGE);
        // A door that is up but refuses to be framed is as black as a dead one.
        knock.answers.put("vidcore", new Fmovies.Health(false, "frames_refused"));
        // And a door whose host is gone (the vidsrc.cc of the day) says so.
        knock.answers.put("vidapi.xyz", new Fmovies.Health(false, "http_522"));
        knock.answers.put("embos.top", new Fmovies.Health(true, null));

        Fmovies.Result movie = Fmovies.handle("/api/fmovies/movie/cavegirl-580175/player", q());
        assertEquals(200, movie.status);
        JSONObject body = new JSONObject(movie.body);
        JSONArray sources = body.getJSONArray("sources");
        assertEquals(3, sources.length());
        assertEquals("Server 1", sources.getJSONObject(0).getString("label"));
        // Our order, not the site's: the picker door (embos, which the site
        // lists first) arrives last.
        assertEquals("https://vidcore.net/movie/580175",
                sources.getJSONObject(0).getString("url"));
        assertEquals("https://vidapi.xyz/embed/movie/580175",
                sources.getJSONObject(1).getString("url"));
        assertEquals("https://embos.top/movie/?mid=580175",
                sources.getJSONObject(2).getString("url"));
        assertEquals("frames_refused", sources.getJSONObject(0).getString("note"));
        assertEquals("http_522", sources.getJSONObject(1).getString("note"));
        assertTrue(sources.getJSONObject(2).getBoolean("ok"));
        assertTrue(sources.getJSONObject(2).isNull("note"));
        // The knock carries the site's own referer, the doors' front desk.
        assertTrue(fake.lastHeaders.get("Accept").contains("text/html"));

        Fmovies.Result tv = Fmovies.handle("/api/fmovies/tv/breaking-bad-1396/player",
                q("season", "5", "episode", "16"));
        assertEquals(200, tv.status);
        JSONArray tvSources = new JSONObject(tv.body).getJSONArray("sources");
        assertEquals("https://vidcore.net/tv/1396/5/16",
                tvSources.getJSONObject(0).getString("url"));
        assertEquals("https://embos.top/tv/?mid=1396&s=5&e=16",
                tvSources.getJSONObject(2).getString("url"));
    }

    // The site's own player page: the table of doors it offers, in its real
    // shape (verified against the live page on 2026-10-10).
    private static final String WATCH_PAGE = "<!DOCTYPE html><html><head><title>Player</title>"
            + "</head><body><script>\n"
            + "      var sources = [\n"
            + "        { id: 'embos', aliases: ['1', 'server1', 's1'],"
            + " movie: 'https://embos.top/movie/?mid={id}',"
            + " tv: 'https://embos.top/tv/?mid={id}&s={season}&e={episode}' },\n"
            + "        { id: 'vidcore', aliases: ['2', 'server2', 's2'],"
            + " movie: 'https://vidcore.net/movie/{id}',"
            + " tv: 'https://vidcore.net/tv/{id}/{season}/{episode}' },\n"
            + "        { id: 'vidapi', aliases: ['3', 'server3', 's3'],"
            + " movie: 'https://vidapi.xyz/embed/movie/{id}',"
            + " tv: 'https://vidapi.xyz/embed/tv/{id}/{season}/{episode}' },\n"
            + "      ];\n"
            + "</script></body></html>";

    @Test
    public void anUnreadablePlayerPageLeavesTheBuiltInDoorsCachedAsAnAnswer()
            throws Exception {
        // The watch page is deliberately a failure: a dead listing is not an
        // error the reader should see.
        fake.failures.put("/watch/index.html", new Exception("f-movies: status 522"));
        knock.answers.put("embos.top", new Fmovies.Health(true, null));
        knock.answers.put("vidcore", new Fmovies.Health(true, null));
        knock.answers.put("vidapi.xyz", new Fmovies.Health(true, null));

        Fmovies.Result first = Fmovies.handle("/api/fmovies/tv/breaking-bad-1396/player",
                q("season", "1", "episode", "1"));
        JSONArray sources = new JSONObject(first.body).getJSONArray("sources");
        // The built-in list in our own order: the two doors that render their
        // own player lead, and the picker that can land on a 404 comes last.
        assertEquals("https://vidcore.net/tv/1396/1/1",
                sources.getJSONObject(0).getString("url"));
        assertEquals("https://vidapi.xyz/embed/tv/1396/1/1",
                sources.getJSONObject(1).getString("url"));
        assertEquals("https://embos.top/tv/?mid=1396&s=1&e=1",
                sources.getJSONObject(2).getString("url"));
        for (int i = 0; i < 3; i++) assertTrue(sources.getJSONObject(i).getBoolean("ok"));
        assertEquals("the fallback list is an answer: one read, not one per open", 1, fake.calls);

        // A second open inside the TTL pays nothing for the list.
        Fmovies.handle("/api/fmovies/movie/cavegirl-580175/player", q());
        assertEquals(1, fake.calls);
    }

    @Test
    public void aSlugWithoutANumericIdHasNoPlayer() throws Exception {
        Fmovies.Result res = Fmovies.handle("/api/fmovies/tv/serial-in-name-only/player", q());
        assertEquals(404, res.status);
        assertEquals(0, fake.calls);
    }

    @Test
    public void identicalRequestsHitTheUpstreamOnce() throws Exception {
        fake.routes.put("/api/search", TMDB_SEARCH);

        Fmovies.handle("/api/fmovies/search", q("q", "cave", "page", "1"));
        Fmovies.handle("/api/fmovies/search", q("q", "cave", "page", "1"));
        assertEquals(1, fake.calls);
    }

    @Test
    public void anUpstreamFailureAnswers502AndIsNotCached() throws Exception {
        fake.failures.put("/api/search", new Anime.UpstreamException(500, ""));

        Fmovies.Result failed = Fmovies.handle("/api/fmovies/search", q("q", "flaky", "page", "1"));
        assertEquals(502, failed.status);
        assertTrue(new JSONObject(failed.body).getString("message").contains("f-movies"));

        // The same request again, with the upstream back up, must succeed:
        // the failure was never written to the cache.
        fake.failures.clear();
        fake.routes.put("/api/search", TMDB_SEARCH);
        Fmovies.Result ok = Fmovies.handle("/api/fmovies/search", q("q", "flaky", "page", "1"));
        assertEquals(200, ok.status);
        assertEquals(2, new JSONObject(ok.body).getJSONArray("items").length());
    }

    @Test
    public void episodesOnAMovieAndUnknownPathsAnswer404WithoutNetwork() throws Exception {
        Fmovies.Result onMovie =
                Fmovies.handle("/api/fmovies/movie/cavegirl-580175/episodes", q());
        assertEquals(404, onMovie.status);
        Fmovies.Result nowhere = Fmovies.handle("/api/fmovies/nope", q());
        assertEquals(404, nowhere.status);
        assertEquals("not_found", new JSONObject(nowhere.body).getString("error"));
        assertEquals(0, fake.calls);
    }
}
