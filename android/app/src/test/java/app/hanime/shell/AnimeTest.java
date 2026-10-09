package app.hanime.shell;

import static org.junit.Assert.assertArrayEquals;
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
        /**
         * An optional body-based answer: every AniList hop shares one URL, so
         * a walk that varies by the `id` in its variables can only be
         * answered by reading the payload. It may throw, which is how a single
         * unreachable hop is simulated.
         */
        BodyReply byBody;

        interface BodyReply {
            String reply(String body) throws Exception;
        }

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
            if (byBody != null) return byBody.reply(body);
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

    // ------------------------------------------------------------- seasons
    //
    // Every season of a series is its own AniList record, linked to the last
    // by SEQUEL/PREQUEL — the JVM twin of the walk in anime.mjs, and the only
    // part of "these are the same show" the app can say on its own.

    private static String edge(String type, int id, String mediaType) {
        return "{\"relationType\":\"" + type + "\",\"node\":{\"id\":" + id
                + ",\"type\":\"" + mediaType
                + "\",\"title\":{\"romaji\":\"Node " + id + "\"}}}";
    }

    private static String seasonMedia(int id, String title, int year, String relations) {
        return "{\"id\":" + id + ",\"title\":{\"romaji\":\"" + title + "\"},"
                + "\"episodes\":12,\"startDate\":{\"year\":" + year + "},"
                + "\"format\":\"TV\","
                + "\"coverImage\":{\"large\":\"https://s4.anilist.co/" + id + ".jpg\"},"
                + "\"relations\":{\"edges\":[" + relations + "]}}";
    }

    /** Answer /graphql by the `id` in the payload — the walk's whole world. */
    private void stubAnilistByVariable(Map<Integer, String> byId, final int[] brokenId) {
        fake.byBody = (body) -> {
            int id = new JSONObject(body).getJSONObject("variables").getInt("id");
            if (brokenId[0] == id) throw new Anime.UpstreamException(503, "");
            String media = byId.get(id);
            return "{\"data\":{\"Media\":" + (media == null ? "null" : media) + "}}";
        };
    }

    private static Map<Integer, String> fourSeasons() {
        Map<Integer, String> show = new HashMap<>();
        // The adaptation (a manga) and the source (a novel) are relations of a
        // different kind: following them would stitch a show to its source as
        // if it were the next season.
        show.put(101, seasonMedia(101, "Show", 2016,
                edge("SEQUEL", 102, "ANIME") + "," + edge("ADAPTATION", 991, "MANGA")));
        show.put(102, seasonMedia(102, "Show 2nd Season", 2018,
                edge("PREQUEL", 101, "ANIME") + "," + edge("SEQUEL", 103, "ANIME")
                        + "," + edge("SOURCE", 992, "NOVEL")));
        show.put(103, seasonMedia(103, "Show 3rd Season", 2020,
                edge("PREQUEL", 102, "ANIME") + "," + edge("SEQUEL", 104, "ANIME")));
        show.put(104, seasonMedia(104, "Show 4th Season", 2022, edge("PREQUEL", 103, "ANIME")));
        return show;
    }

    private static int[] idsIn(JSONObject response) throws Exception {
        JSONArray data = response.getJSONArray("data");
        int[] out = new int[data.length()];
        for (int i = 0; i < data.length(); i++) out[i] = data.getJSONObject(i).getInt("id");
        return out;
    }

    @Test
    public void aSeriesAnswersAsOneRunOfSeasonsInAirOrder() throws Exception {
        stubAnilistByVariable(fourSeasons(), new int[] { -1 });

        // Asked from the *middle* of the run: backwards to the first season,
        // forwards to the last — the only order an S1 · S2 · S3 strip can be
        // drawn in.
        Anime.Result res = Anime.handle("/api/anime/102/seasons", q());
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertArrayEquals(new int[] { 101, 102, 103, 104 }, idsIn(body));

        JSONArray data = body.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject season = data.getJSONObject(i);
            assertEquals("the title asked for is the one marked on screen",
                    i == 1, season.getBoolean("current"));
            assertNotEquals("a non-season relation was stitched into the run",
                    991, season.getInt("id"));
            assertNotEquals("a non-season relation was stitched into the run",
                    992, season.getInt("id"));
        }
        assertEquals(2016, data.getJSONObject(0).getInt("year"));

        // Bounded: one AniList query per hop, so a broken graph stops rather
        // than circles, and no request can run away.
        assertTrue("the walk did not visit every season", fake.calls >= 4);
        assertTrue("the chain walked " + fake.calls + " times", fake.calls <= 10);
    }

    @Test
    public void aLoneTitleAnswersOneEntrySoNothingDrawsAStrip() throws Exception {
        Map<Integer, String> lone = new HashMap<>();
        lone.put(501, seasonMedia(501, "Lone", 2020, ""));
        stubAnilistByVariable(lone, new int[] { -1 });

        Anime.Result res = Anime.handle("/api/anime/501/seasons", q());
        assertEquals(200, res.status);
        JSONObject body = new JSONObject(res.body);
        assertEquals(1, body.getJSONArray("data").length());
        assertTrue(body.getJSONArray("data").getJSONObject(0).getBoolean("current"));
    }

    @Test
    public void aFailedHopKeepsWhatWasGatheredAndIsNotCachedAsTheAnswer() throws Exception {
        Map<Integer, String> show = new HashMap<>();
        show.put(201, seasonMedia(201, "Cut", 2021, edge("SEQUEL", 202, "ANIME")));
        show.put(202, seasonMedia(202, "Cut 2nd Season", 2023,
                edge("PREQUEL", 201, "ANIME") + "," + edge("SEQUEL", 203, "ANIME")));
        show.put(203, seasonMedia(203, "Cut 3rd Season", 2025, edge("PREQUEL", 202, "ANIME")));
        int[] broken = { 203 };
        stubAnilistByVariable(show, broken);

        // First call: the third season is unreachable, so the run stops at
        // two — half a chain is worth more than none, and the strip is
        // decorative anyway.
        Anime.Result first = Anime.handle("/api/anime/201/seasons", q());
        assertEquals(200, first.status);
        assertArrayEquals(new int[] { 201, 202 }, idsIn(new JSONObject(first.body)));

        // Second call, upstream recovered: the same request must NOT be
        // answered the half-run again. A failed walk left in the cache would
        // make the failure the answer — that is the bug this asserts.
        broken[0] = -1;
        Anime.Result second = Anime.handle("/api/anime/201/seasons", q());
        assertArrayEquals("the failed hop became the cached answer",
                new int[] { 201, 202, 203 }, idsIn(new JSONObject(second.body)));
    }
}
