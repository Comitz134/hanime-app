package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * A call that could not reach mangafire is asked again before the reader is told.
 *
 * The bug this exists for: restarting the app onto a network that was not up yet
 * put *unable to resolve host "mangafire.to": no address associated with
 * hostname* on the title screen, because the very first lookup after wake-up is
 * the one a phone's resolver refuses. One refused name is not a down catalogue.
 *
 * A refused connection is the same failure class as a refused name — transient,
 * and gone a moment later — and unlike DNS it can be arranged on demand: nothing
 * listens on 127.0.0.1:1, and OkHttp declines to connect there immediately, so
 * the wall time this test measures is the backoff and nothing else.
 */
public class MangaRetryTest {

    @Test
    public void aRefusedConnectionIsAskedAgainBeforeItIsAnError() {
        Manga.Transport transport = new Manga.HttpTransport();
        long started = System.currentTimeMillis();
        try {
            transport.get("https://127.0.0.1:1/api/titles");
            fail("a refused connection is not a successful call");
        } catch (Exception refused) {
            long elapsed = System.currentTimeMillis() - started;
            assertTrue("thrown type: " + refused.getClass().getName(),
                    refused instanceof ConnectException);
            // Two backoffs — 600 ms and 1200 ms — and a connect that fails at
            // once. Below this the call was not retried at all.
            assertTrue("elapsed " + elapsed + " ms — the call was not retried",
                    elapsed >= 1_100L);
            // Three attempts and no more: a real outage must reach the reader.
            assertTrue("elapsed " + elapsed + " ms — retried too long",
                    elapsed < 12_000L);
        }
    }

    @Test
    public void aRefusedNameIsExplainedRatherThanQuoted() {
        // Android's own message for a name its resolver refused, on the class
        // the refusal actually arrives as.
        String shown = SekaiServer.mangaMessage(new UnknownHostException(
                "mangafire.to: no address associated with hostname"));
        // And the same words on a class that only carries them, which is how
        // they arrive when the refusal is wrapped on the way up.
        assertEquals(shown, SekaiServer.mangaMessage(new java.io.IOException(
                "unable to resolve host \"mangafire.to\": no address associated with hostname")));
        assertTrue(shown, shown.contains("could not look up mangafire.to"));
        assertTrue(shown, shown.endsWith("Check the connection and try again."));
        assertTrue("the raw host error is not a sentence for a reader",
                !shown.contains("no address associated with hostname"));
    }

    @Test
    public void anythingElseKeepsItsOwnWords() {
        // upstream's own vocabulary is already addressed to a person, and
        // rewriting it would lose the detail that makes it diagnostic.
        assertEquals("upstream HTTP 503",
                SekaiServer.mangaMessage(new IllegalStateException("upstream HTTP 503")));
    }
}
