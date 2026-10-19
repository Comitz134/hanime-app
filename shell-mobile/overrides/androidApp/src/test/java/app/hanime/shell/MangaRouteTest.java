package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * The manga routes' wiring, held to a JVM.
 *
 * The bug this exists for: `/manga/search.json` read the caller's `page` and
 * `limit` and then handed them to `Manga.search` crossed, so every keyword
 * search asked upstream for page 24 of page 24 and came back empty — which
 * looks exactly like a catalogue that has nothing, and took a build and a phone
 * to notice. Nothing about that decision needs a device, so it is held here:
 * the route's own parameter resolution (`Manga.searchQuery`), the URL that
 * resolution produces upstream, what `hasNext` reads, and that the filter
 * vocabulary reaches the screen the way upstream sent it.
 *
 * The transport is the seam, the same one the phone shell's `MalTest` uses: a
 * double that records the URL it was handed and answers a canned body. The
 * URL matters — what was signed and what was sent are built from one object,
 * so a test that could not see the URL could not pin this.
 */
public class MangaRouteTest {

    private Manga.Transport realTransport;
    private String lastUrl;

    @Before
    public void setUp() {
        realTransport = Manga.transport;
        lastUrl = null;
    }

    @After
    public void tearDown() {
        Manga.transport = realTransport;
    }

    /** A transport double: records the URL, answers the body it was given. */
    private void answer(final String body) {
        Manga.transport = new Manga.Transport() {
            @Override
            public String get(String url) {
                lastUrl = url;
                return body;
            }
        };
    }

    /** `page=2&limit=24` to a map, the way a request's query arrives. */
    private static Map<String, String> query(String encoded) {
        Map<String, String> out = new HashMap<>();
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                key = URLDecoder.decode(key, "UTF-8");
                value = URLDecoder.decode(value, "UTF-8");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            out.put(key, value);
        }
        return out;
    }

    /** The query of the URL the transport was last handed, as a map. */
    private Map<String, String> sent() {
        assertTrue("no upstream call was made", lastUrl != null);
        int at = lastUrl.indexOf('?');
        return query(at < 0 ? "" : lastUrl.substring(at + 1));
    }

    @Test
    public void theRouteDoesNotCrossThePageAndThePageSize() {
        Manga.SearchQuery search = Manga.searchQuery(query("q=solo+leveling&page=2&limit=24"));
        assertEquals(2, search.page);
        assertEquals(24, search.limit);
        assertEquals("solo leveling", search.q);
        assertEquals("", search.type);
    }

    @Test
    public void aSearchAsksUpstreamForTheCallersPage() throws Exception {
        answer("{\"items\":[],\"meta\":{\"lastPage\":5}}");
        Manga.search(Manga.searchQuery(query("q=solo&page=2&limit=24&type=manhwa&genre=1")));

        Map<String, String> sent = sent();
        assertEquals("2", sent.get("page"));
        assertEquals("24", sent.get("limit"));
        assertEquals("solo", sent.get("keyword"));
        assertEquals("manhwa", sent.get("types[]"));
        assertEquals("1", sent.get("genres_in[]"));
        assertNull(sent.get("statuses[]"));
    }

    @Test
    public void anUnnamedPageIsTheFirstAndAnUnnamedSizeIsTheAppsPage() throws Exception {
        answer("{\"items\":[]}");
        Manga.search(Manga.searchQuery(query("q=x")));
        Map<String, String> sent = sent();
        assertEquals("1", sent.get("page"));
        assertEquals("24", sent.get("limit"));
    }

    @Test
    public void aPageSizeThatIsAnOrderIsBounded() {
        assertEquals(Manga.SEARCH_LIMIT, Manga.searchQuery(query("limit=0")).limit);
        assertEquals(100, Manga.searchQuery(query("limit=5000")).limit);
        assertEquals(3, Manga.searchQuery(query("limit=3")).limit);
    }

    @Test
    public void hasNextReadsTheLastPageTheAnswerNames() throws Exception {
        answer("{\"items\":[],\"meta\":{\"lastPage\":5}}");
        JSONObject second = Manga.search(Manga.searchQuery(query("q=x&page=2")));
        assertEquals(2, second.getInt("page"));
        assertTrue(second.getBoolean("hasNext"));

        answer("{\"items\":[],\"meta\":{\"lastPage\":2}}");
        JSONObject last = Manga.search(Manga.searchQuery(query("q=x&page=2")));
        assertFalse(last.getBoolean("hasNext"));
    }

    @Test
    public void searchingWithNoKeywordIsTheLatestChapterCatalogue() throws Exception {
        answer("{\"items\":[]}");
        Manga.search(Manga.searchQuery(query("page=1")));
        Map<String, String> sent = sent();
        assertNull(sent.get("keyword"));
        assertEquals("desc", sent.get("order[chapter_updated_at]"));
    }

    @Test
    public void aFilterTheRouteDidNotNameDoesNotTravel() throws Exception {
        answer("{\"items\":[]}");
        Manga.search(Manga.searchQuery(query("q=x&type=&status=&genre=")));
        Map<String, String> sent = sent();
        assertNull(sent.get("types[]"));
        assertNull(sent.get("statuses[]"));
        assertNull(sent.get("genres_in[]"));
    }

    @Test
    public void filtersAnswerUpstreamsOwnVocabularyUntouched() throws Exception {
        answer("{\"data\":{\"types\":[{\"value\":\"manhwa\",\"label\":\"Manhwa\"}],"
                + "\"statuses\":[{\"value\":\"releasing\",\"label\":\"Releasing\"}],"
                + "\"genres\":[{\"id\":1,\"name\":\"Action\"}]}}");
        JSONObject filters = Manga.filters();
        assertEquals("manhwa", filters.getJSONArray("types").getJSONObject(0).getString("value"));
        assertEquals("Releasing", filters.getJSONArray("statuses").getJSONObject(0).getString("label"));
        assertEquals(1, filters.getJSONArray("genres").getJSONObject(0).getInt("id"));
    }

    /**
     * The shelf's "added at" stamp survives the route.
     *
     * An import brings its own order (MyAnimeList's last read first), and the
     * route carries it to `Manga.addToLibrary` in the `at` parameter. Epoch
     * milliseconds do not fit in an int, so reading that parameter with the
     * route's `number` would have turned every stamp into 0 and every import
     * into a shuffle with no error to notice. This holds the parse itself.
     */
    @Test
    public void anAddedAtStampKeepsItsMilliseconds() {
        long stamp = 1760918400123L;
        assertTrue(stamp > Integer.MAX_VALUE);
        assertEquals(stamp, Manga.atOr(Long.toString(stamp)));
        assertEquals(0L, Manga.atOr(null));
        assertEquals(0L, Manga.atOr(""));
        assertEquals(0L, Manga.atOr("yesterday"));
    }

    @Test
    public void trendingAsksTheTopTitlesForTheLimitItWasGiven() throws Exception {
        answer("{\"items\":[{\"hid\":\"ro8ro\",\"title\":\"Sip of Poison\","
                + "\"poster\":{\"medium\":\"https://x/p.jpg\"}}]}");
        JSONArray items = Manga.trending(30);
        Map<String, String> sent = sent();
        assertEquals("trending", sent.get("type"));
        assertEquals("30", sent.get("limit"));
        assertEquals("1", sent.get("days"));
        assertEquals(1, items.length());
        assertEquals("Sip of Poison", items.getJSONObject(0).getString("title"));
        assertEquals("https://x/p.jpg", items.getJSONObject(0).getString("poster"));
    }
}
