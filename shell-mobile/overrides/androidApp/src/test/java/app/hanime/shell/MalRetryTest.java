package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/**
 * The MyAnimeList relay's network retry, held to a JVM.
 *
 * The bug this exists for: tapping *Connect MyAnimeList* in Settings came back
 * with `unable to resolve host "myanimelist.net"` — one missed DNS lookup on a
 * phone that had just switched networks, reported as though the whole login
 * were broken. The lookup succeeds a second later, and nothing about that needs
 * a phone to pin: the retry counts its attempts and decides which failures are
 * worth retrying, and the transport here is the seam {@link Mal} already
 * exposes.
 *
 * What is held: that a DNS miss, a connection refusal and a timeout are retried
 * and can succeed on a later attempt; that a network that stays down is
 * answered with a sentence the reader can act on rather than an exception; and
 * that MAL's *own* answer — a 400, a refusal of the code — is passed straight
 * through, because asking twice would get the same answer twice.
 */
public class MalRetryTest {

    private Mal.Transport realTransport;
    private int calls;

    @Before
    public void setUp() {
        realTransport = Mal.transport;
        calls = 0;
    }

    @After
    public void tearDown() {
        Mal.transport = realTransport;
    }

    private static Map<String, String> headers() {
        return new HashMap<>();
    }

    /** An answer, counted. */
    private Mal.Result answer(final int status, final String body) {
        return new Mal.Result(status, body);
    }

    /** A transport that fails the first [failures] times, then answers. */
    private void failingThen(final int failures, final Mal.Result after) {
        Mal.transport = new Mal.Transport() {
            @Override
            public Mal.Result call(String method, String url, Map<String, String> headers, String body) throws Exception {
                calls++;
                if (calls <= failures) throw new UnknownHostException("myanimelist.net");
                return after;
            }
        };
    }

    @Test
    public void aDnsMissIsRetriedAndCanSucceed() {
        failingThen(2, answer(200, "{\"access_token\":\"live\"}"));

        Mal.Result result = Mal.call("POST", "https://myanimelist.net/v1/oauth2/token", headers(), "");

        assertEquals(3, calls);
        assertEquals(200, result.status);
        assertEquals("{\"access_token\":\"live\"}", result.body);
    }

    @Test
    public void aNetworkThatStaysDownIsAnsweredInWords() {
        Mal.transport = new Mal.Transport() {
            @Override
            public Mal.Result call(String method, String url, Map<String, String> headers, String body) throws Exception {
                calls++;
                throw new UnknownHostException("myanimelist.net");
            }
        };

        Mal.Result result = Mal.call("POST", "https://myanimelist.net/v1/oauth2/token", headers(), "");

        assertEquals(3, calls);
        assertEquals(502, result.status);
        assertTrue(result.body.contains(Mal.UNREACHABLE));
    }

    @Test
    public void aTimeoutIsAConnectionProblemToo() {
        Mal.transport = new Mal.Transport() {
            @Override
            public Mal.Result call(String method, String url, Map<String, String> headers, String body) throws Exception {
                calls++;
                if (calls == 1) throw new java.net.SocketTimeoutException("Read timed out");
                return answer(200, "{\"access_token\":\"live\"}");
            }
        };

        Mal.Result result = Mal.call("GET", "https://api.myanimelist.net/v2/manga", headers(), null);

        assertEquals(2, calls);
        assertEquals(200, result.status);
    }

    @Test
    public void aFailureWrappedInAnotherIsStillANetworkFailure() {
        assertTrue(Mal.transientNetworkError(new RuntimeException(new UnknownHostException("myanimelist.net"))));
        assertTrue(Mal.transientNetworkError(new ConnectException("Connection refused")));
        assertFalse(Mal.transientNetworkError(new IllegalStateException("bad request")));
        // A failure with no cause at all is not a network failure by default.
        assertFalse(Mal.transientNetworkError(new Exception("something else")));
    }

    @Test
    public void aGatewayErrorOnTheExchangeIsRetriedAndCanSucceed() throws Exception {
        Mal.transport = new Mal.Transport() {
            @Override
            public Mal.Result call(String method, String url, Map<String, String> headers, String body) {
                calls++;
                if (calls <= 2) return answer(502, "<html>Bad Gateway</html>");
                return answer(200, "{\"access_token\":\"live\"}");
            }
        };

        Mal.Result result = Mal.token(exchangeQuery());

        assertEquals(3, calls);
        assertEquals(200, result.status);
        assertTrue(result.body.contains("access_token"));
    }

    @Test
    public void aGatewayThatStaysDownIsExplainedNotPassedThrough() throws Exception {
        Mal.transport = new Mal.Transport() {
            @Override
            public Mal.Result call(String method, String url, Map<String, String> headers, String body) {
                calls++;
                return answer(502, "<html>Bad Gateway</html>");
            }
        };

        Mal.Result result = Mal.token(exchangeQuery());

        assertEquals(3, calls);
        assertEquals(502, result.status);
        assertTrue(result.body.contains(Mal.GATEWAY));
        assertTrue(result.body.contains("mal_unavailable"));
    }

    @Test
    public void aGatewayErrorIsRecognizedAndA400IsNot() {
        assertTrue(Mal.isGatewayError(502));
        assertTrue(Mal.isGatewayError(503));
        assertTrue(Mal.isGatewayError(504));
        assertFalse(Mal.isGatewayError(400));
        assertFalse(Mal.isGatewayError(401));
        assertFalse(Mal.isGatewayError(200));
    }

    @Test
    public void theRelaysOwnUnreachable502IsNotMistakenForMalsGateway() {
        // Both are 502, but only one is MAL's edge and only that one is asked
        // again; the relay's own answer already carries the right sentence.
        Mal.Result ours = new Mal.Result(502,
                "{\"error\":\"unreachable\",\"message\":\"" + Mal.UNREACHABLE + "\"}");
        Mal.Result mals = new Mal.Result(502, "<html>Bad Gateway</html>");
        assertFalse(Mal.upstreamGateway(ours));
        assertTrue(Mal.upstreamGateway(mals));
    }

    private static String exchangeQuery() {
        return "client_id=63e08e44f57ef1b4644322939ebd9fa6"
                + "&grant_type=authorization_code"
                + "&code=bogus"
                + "&code_verifier=abcdefghijklmnopqrstuvwxyz123456"
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A8787%2F";
    }

    @Test
    public void malOwnAnswerIsPassedThroughAndNeverRetried() {
        Mal.transport = new Mal.Transport() {
            @Override
            public Mal.Result call(String method, String url, Map<String, String> headers, String body) {
                calls++;
                return answer(400, "{\"error\":\"invalid_request\"}");
            }
        };

        Mal.Result result = Mal.call("POST", "https://myanimelist.net/v1/oauth2/token", headers(), "");

        assertEquals(1, calls);
        assertEquals(400, result.status);
        assertTrue(result.body.contains("invalid_request"));
    }
}
