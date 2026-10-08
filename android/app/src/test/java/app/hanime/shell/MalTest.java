package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The MyAnimeList relay with the transport swapped for a fake — the JVM twin
 * of server/test/mal.test.mjs, pinning the same contract: everything crosses
 * the pipe as query parameters (Android never hands the interceptor a
 * request body, which is why the contract has no bodies at all), the token
 * route forwards only the fields MAL documents, the API route forwards the
 * caller's own Bearer untouched, GET parameters continue as MAL's query,
 * PUT parameters become the form body MAL writes list status with, and a
 * request that fails a precondition never reaches an upstream at all.
 */
public class MalTest {

    private Mal.Transport realTransport;
    private FakeTransport fake;

    @Before
    public void setUp() {
        realTransport = Mal.transport;
        fake = new FakeTransport();
        Mal.transport = fake;
    }

    @After
    public void tearDown() {
        Mal.transport = realTransport;
    }

    /** A transport double that records calls and answers by URL substring. */
    static final class FakeTransport implements Mal.Transport {
        int calls = 0;
        String lastMethod;
        String lastUrl;
        String lastBody;
        Map<String, String> lastHeaders = new HashMap<>();
        final Map<String, String> routes = new HashMap<>();
        int replyStatus = 200;
        String replyBody = "{}";

        @Override
        public Mal.Result call(String method, String url, Map<String, String> headers, String body) {
            calls++;
            lastMethod = method;
            lastUrl = url;
            lastBody = body;
            lastHeaders = headers;
            for (Map.Entry<String, String> e : routes.entrySet()) {
                if (url.contains(e.getKey())) return new Mal.Result(replyStatus, replyBody);
            }
            throw new AssertionError("unstubbed call: " + url);
        }
    }

    // ------------------------------------------------------------- the token

    @Test
    public void tokenForwardsOnlyTheDocumentedFields() throws Exception {
        fake.routes.put("myanimelist.net/v1/oauth2/token", "");

        Mal.Result r = Mal.token("client_id=cid123"
                + "&grant_type=authorization_code"
                + "&code=the-code"
                + "&code_verifier=" + "v".repeat(43)
                + "&redirect_uri=https%3A%2F%2Fhanime.tv%2F"
                + "&evil=must+not+pass");

        assertEquals(200, r.status);
        assertEquals("POST", fake.lastMethod);
        assertTrue(fake.lastUrl.contains("myanimelist.net/v1/oauth2/token"));
        assertTrue(fake.lastBody.contains("client_id=cid123"));
        assertTrue(fake.lastBody.contains("grant_type=authorization_code"));
        assertTrue(fake.lastBody.contains("code=the-code"));
        assertTrue(fake.lastBody.contains("code_verifier="));
        assertTrue("the redirect must be form-encoded",
                fake.lastBody.contains("redirect_uri=https%3A%2F%2Fhanime.tv%2F"));
        assertFalse("an undocumented field leaked into the form",
                fake.lastBody.contains("evil"));
        assertEquals("application/x-www-form-urlencoded",
                fake.lastHeaders.get("Content-Type"));
    }

    @Test
    public void tokenRefusesAGrantItDoesNotDocument() throws Exception {
        Mal.Result r = Mal.token("client_id=c&grant_type=password");
        assertEquals(400, r.status);
        assertTrue(r.body.contains("unsupported_grant_type"));
        assertEquals("a refusal must not reach the network", 0, fake.calls);
    }

    @Test
    public void tokenRefusesAHalfFinishedExchange() throws Exception {
        Mal.Result r = Mal.token("client_id=c&grant_type=authorization_code&code=x");
        assertEquals(400, r.status);
        assertTrue(r.body.contains("code_verifier_and_redirect_required"));
        assertEquals(0, fake.calls);
    }

    @Test
    public void tokenCarriesARefreshRound() throws Exception {
        fake.routes.put("myanimelist.net/v1/oauth2/token", "");
        fake.replyBody = "{\"access_token\":\"new\",\"refresh_token\":\"newer\"}";

        Mal.Result r = Mal.token("client_id=c"
                + "&grant_type=refresh_token&refresh_token=old");

        assertEquals(200, r.status);
        assertTrue(fake.lastBody.contains("grant_type=refresh_token"));
        assertTrue(fake.lastBody.contains("refresh_token=old"));
        assertFalse("a refresh must not resend a stale code",
                fake.lastBody.contains("code="));
    }

    // ---------------------------------------------------------------- the API

    @Test
    public void apiForwardsTheCallersBearerAndQuery() throws Exception {
        fake.routes.put("api.myanimelist.net", "");

        Mal.Result r = Mal.api("v2/users/@me", "fields=name", "GET", "Bearer abc123");

        assertEquals(200, r.status);
        assertEquals("GET", fake.lastMethod);
        assertEquals("https://api.myanimelist.net/v2/users/@me?fields=name", fake.lastUrl);
        assertEquals("Bearer abc123", fake.lastHeaders.get("Authorization"));
        assertNull("a GET carries no body", fake.lastBody);
    }

    @Test
    public void putParametersBecomeTheFormBodyMalWritesStatusWith() throws Exception {
        fake.routes.put("api.myanimelist.net", "");

        Mal.Result r = Mal.api("v2/anime/21/mylist_status",
                "status=watching&score=8&num_watched_episodes=5&blank=",
                "PUT", "Bearer t");

        assertEquals(200, r.status);
        assertEquals("PUT", fake.lastMethod);
        assertEquals("the fields must reach MAL as the body, not twice as the query",
                "https://api.myanimelist.net/v2/anime/21/mylist_status", fake.lastUrl);
        assertTrue(fake.lastBody.contains("status=watching"));
        assertTrue(fake.lastBody.contains("score=8"));
        assertTrue(fake.lastBody.contains("num_watched_episodes=5"));
        assertFalse("an empty value must be omitted", fake.lastBody.contains("blank"));
        assertEquals("application/x-www-form-urlencoded",
                fake.lastHeaders.get("Content-Type"));
    }

    @Test
    public void apiRefusesACallWithNoToken() throws Exception {
        Mal.Result r = Mal.api("v2/users/@me", null, "GET", null);
        assertEquals(401, r.status);
        assertEquals("a refusal must not reach the network", 0, fake.calls);

        Mal.Result wrong = Mal.api("v2/users/@me", null, "GET", "Basic abc");
        assertEquals(401, wrong.status);
        assertEquals(0, fake.calls);
    }

    @Test
    public void apiRefusesAnythingThatIsNotV2() throws Exception {
        Mal.Result r = Mal.api("v1/legacy", null, "GET", "Bearer t");
        assertEquals(404, r.status);
        assertEquals(0, fake.calls);
    }

    @Test
    public void apiRefusesAMethodTheRelayDoesNotCarry() throws Exception {
        Mal.Result r = Mal.api("v2/users/@me", null, "TRACE", "Bearer t");
        assertEquals(405, r.status);
        assertEquals(0, fake.calls);
    }
}
