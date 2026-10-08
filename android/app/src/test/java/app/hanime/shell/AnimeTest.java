package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The /api/anime route family with both upstreams stubbed: AniList for the
 * catalog, LunarX for episodes and the player. Nothing here touches the
 * network — the transport is swapped for a fake — and two assertions guard
 * the details that make the proxy necessary at all: LunarX answers 400 to
 * any request carrying another site's Origin, and a path that matches no
 * route never reaches an upstream. These are the JVM twins of
 * server/test/anime.test.mjs; both suites pin the same response contract.
 */
public class AnimeTest {

    private Anime.Transport realTransport;
    private FakeTransport fake;

    @Before
    public void setUp() {
        realTransport = Anime.transport;
        fake = new FakeTransport();
        Anime.transport = fake;
        Anime.clearCache();
    }

    @After
    public void tearDown() {
        Anime.transport = realTransport;
        Anime.clearCache();
    }

    /** A transport double that records calls and answers by URL substring. */
    static final class FakeTransport implements Anime.Transport {
        int calls = 0;
        String lastUrl;
        String lastBody;
        Map<String, String> lastHeaders = new HashMap<>();
        final Map<String, String> routes = new HashMap<>();
        Exception failOnPost;

        private String route(String url) {
            for (Map.Entry<String, String> e : routes.entrySet()) {
                if (url.contains(e.getKey())) return e.getValue();
            }
            throw new AssertionError("unstubbed fetch: " + url);
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws Exception {
            calls++;
            lastUrl = url;
            lastBody = body;
            lastHeaders = headers;
            if (failOnPost != null) throw failOnPost;
            return route(url);
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

    private static final String ANILIST_CARD = "{"
            + "\"id\": 99001,"
            // AniList's cross-reference to the MAL entry — the tracking row's
            // direct id for the anime area, no title search needed.
            + "\"idMal\": 20,"
            + "\"title\": {\"romaji\": \"Test no Hito\"},"
            + "\"episodes\": 12,"
            + "\"averageScore\": 81,"
            + "\"startDate\": {\"year\": 2024},"
            + "\"format\": \"TV\","
            + "\"status\": \"FINISHED\","
            + "\"coverImage\": {\"large\": \"https://s4.anilist.co/c.jpg\"},"
            + "\"bannerImage\": \"https://s4.anilist.co/b.jpg\","
            + "\"genres\": [\"Action\"]"
            + "}";

    private static final String ANILIST_PAGE = "{\"data\":{\"Page\":{"
            + "\"pageInfo\":{\"currentPage\":1,\"hasNextPage\":false},"
            + "\"media\":[" + ANILIST_CARD + "]}}}";

    private static final String ANILIST_DETAILS = "{\"data\":{\"Media\":"
            + ANILIST_CARD.substring(0, ANILIST_CARD.length() - 1) + ","
            + "\"description\": \"A <b>description</b> with<br> a line break.\","
            // AniList's real shape: `media` is the title being viewed,
            // `mediaRecommendation` is the suggested one. A fixture without
            // the self-reference could never catch the rail repeating itself.
            + "\"recommendations\":{\"nodes\":[{\"media\":"
            + ANILIST_CARD
            + ",\"mediaRecommendation\":"
            + ANILIST_CARD.replace("99001", "99002")
                    .replace("Test no Hito", "Rec no Hito")
            + "}]}}}}";

    private static final String LUNARX_EPISODES = "{\"data\":["
            + "{\"number\":1,\"title\":\"First\",\"description\":\"One.\","
            + "\"img\":\"https://x/1.jpg\",\"airDate\":\"2024-01-01\","
            + "\"hasSub\":true,\"hasDub\":false,\"runtime\":24},"
            + "{\"number\":2,\"title\":\"Second\",\"description\":\"Two.\",\"hasSub\":true}"
            + "]}";

    private static final String LUNARX_PLAYER = "{\"data\":["
            + "{\"server\":\"sv-9\",\"episode\":1,\"player_url\":\"https://embed.example/e/abc?v=2\"}"
            + "]}";

    // ---------------------------------------------------------------- routes

    @Test
    public void emptyQueryTrendsAndQuerySearches() throws Exception {
        fake.routes.put("graphql.anilist.co", ANILIST_PAGE);

        Anime.Result trending = Anime.handle("/api/anime/search", q());
        assertEquals(200, trending.status);
        JSONObject body = new JSONObject(trending.body);
        assertEquals(1, body.getJSONArray("items").length());
        JSONObject card = body.getJSONArray("items").getJSONObject(0);
        assertEquals("Test no Hito", card.getString("title"));
        assertEquals(81, card.getInt("score"));
        assertFalse(body.getBoolean("hasNext"));
        assertTrue("no query must not search",
                fake.lastBody.contains("TRENDING_DESC"));

        Anime.Result search = Anime.handle("/api/anime/search", q("q", "hero"));
        assertEquals(200, search.status);
        assertEquals("hero", new JSONObject(search.body).getString("q"));
        assertTrue("a query must search AniList",
                fake.lastBody.contains("search: $search"));
    }

    @Test
    public void filtersRideTheQueryAsVariablesAndJunkIsDroppedBeforeItLeaves() throws Exception {
        fake.routes.put("graphql.anilist.co", ANILIST_PAGE);

        Anime.Result res = Anime.handle("/api/anime/search",
                q("genre", "Action", "format", "tv", "status", "RELEASING"));
        assertEquals(200, res.status);
        JSONObject sent = new JSONObject(fake.lastBody);
        String query = sent.getString("query");
        assertTrue("the genre filter never reaches the query", query.contains("genre: $genre"));
        assertTrue("the format filter never reaches the query", query.contains("format: $format"));
        assertTrue("the status filter never reaches the query", query.contains("status: $status"));
        // Values travel as variables — never spliced into the query text — and
        // are canonicalized on the way, so upstream sees its own spelling.
        JSONObject vars = sent.getJSONObject("variables");
        assertEquals("Action", vars.getString("genre"));
        assertEquals("TV", vars.getString("format"));
        assertEquals("RELEASING", vars.getString("status"));

        // An unknown enum would fail the whole query upstream; it never gets there.
        Anime.handle("/api/anime/search", q("format", "NOPE", "genre", "Nope"));
        String junk = new JSONObject(fake.lastBody).getString("query");
        assertFalse("an unknown format still reached the query", junk.contains("$format"));
        assertFalse("an unknown genre still reached the query", junk.contains("$genre"));
    }

    @Test
    public void detailsCarryTheDescriptionTextAndTheRecommendations() throws Exception {
        fake.routes.put("graphql.anilist.co", ANILIST_DETAILS);

        Anime.Result res = Anime.handle("/api/anime/99001", q());
        assertEquals(200, res.status);
        JSONObject details = new JSONObject(res.body).getJSONObject("details");
        assertEquals(99001, details.getInt("id"));
        // Markup is flattened: the client renders text, never HTML upstream.
        assertFalse("description still carries markup",
                details.getString("description").contains("<"));
        assertTrue(details.getString("description").contains("line break"));
        JSONArray recs = details.getJSONArray("recommendations");
        assertEquals(99002, recs.getJSONObject(0).getInt("id"));
        assertEquals("Rec no Hito", recs.getJSONObject(0).getString("title"));
        assertEquals("the MAL id must reach the client or tracking searches by name",
                20, details.getInt("malId"));
        for (int i = 0; i < recs.length(); i++) {
            assertNotEquals("the watched title must never appear in its own recommendations",
                    details.getInt("id"), recs.getJSONObject(i).getInt("id"));
        }
    }

    @Test
    public void episodesAreProxiedAsLunarxWhichIsTheWholePoint() throws Exception {
        fake.routes.put("api.lunarx.to/api/animes/v2/episodes", LUNARX_EPISODES);

        Anime.Result res = Anime.handle("/api/anime/99002/episodes", q());
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("99002", body.getString("id"));
        JSONObject first = body.getJSONArray("data").getJSONObject(0);
        assertEquals("First", first.getString("title"));
        assertEquals(24, first.getInt("length"));
        assertTrue(body.getJSONArray("data").getJSONObject(1).isNull("length"));

        assertEquals("a foreign Origin is answered with 400 —"
                        + " the proxy must speak as lunarx",
                "https://lunarx.to", fake.lastHeaders.get("Origin"));
        assertEquals("https://lunarx.to/", fake.lastHeaders.get("Referer"));
        assertTrue(fake.lastHeaders.get("User-Agent").contains("Mozilla"));
    }

    @Test
    public void thePlayerRouteAnswersTheEmbedUrlTheSiteItselfWouldUse() throws Exception {
        fake.routes.put("api.lunarx.to/api/3rdprovider", LUNARX_PLAYER);

        Anime.Result res = Anime.handle("/api/anime/99003/player", q("ep", "1"));
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("99003", body.getString("id"));
        assertEquals(1, body.getInt("ep"));
        JSONObject source = body.getJSONArray("sources").getJSONObject(0);
        assertEquals("https://embed.example/e/abc?v=2", source.getString("url"));
        assertEquals("sv-9", source.getString("label"));
        assertTrue(fake.lastUrl.contains("anilist=99003&episode=1"));
    }

    @Test
    public void anEpisodeWithNoPlayerIsA404NotAnEmptyPage() throws Exception {
        fake.routes.put("api.lunarx.to/api/3rdprovider", "{\"data\":[]}");

        Anime.Result res = Anime.handle("/api/anime/99004/player", q("ep", "3"));
        assertEquals(404, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("no_player", body.getString("error"));
        assertEquals(3, body.getInt("ep"));
    }

    @Test
    public void anIdThatIsNotANumberNeverReachesAnUpstream() throws Exception {
        Anime.Result res = Anime.handle("/api/anime/abc", q());
        assertEquals(404, res.status);
        assertEquals("not_found", new JSONObject(res.body).getString("error"));
        assertEquals("the route must not ask anything of the network", 0, fake.calls);

        // Nor does a subroute of a non-id, nor an unknown suffix: the whole
        // family is matched, not just the four names.
        assertEquals(404, Anime.handle("/api/anime/abc/episodes", q()).status);
        assertEquals(404, Anime.handle("/api/anime/12/bogus", q()).status);
        assertEquals(0, fake.calls);
    }

    @Test
    public void anUpstreamFailureIsA502WithTheReasonAttached() throws Exception {
        fake.failOnPost = new Anime.UpstreamException(503, "");

        Anime.Result res = Anime.handle("/api/anime/search", q("q", "hero"));
        assertEquals(502, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals("upstream", body.getString("error"));
        assertTrue(body.getString("message").contains("anilist"));
    }

    @Test
    public void theCacheServesRepeatedLooksWithOneUpstreamCall() throws Exception {
        fake.routes.put("graphql.anilist.co", ANILIST_PAGE);

        Anime.handle("/api/anime/search", q("q", "hero"));
        Anime.handle("/api/anime/search", q("q", "hero"));
        Anime.handle("/api/anime/search", q("q", "hero"));
        assertEquals(1, fake.calls);

        // A different query is a different key.
        Anime.handle("/api/anime/search", q("q", "other"));
        assertEquals(2, fake.calls);
    }
}
