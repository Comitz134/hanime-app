package app.hanime.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What the manga title page's source button is allowed to open. The page cannot
 * walk to another origin by itself — the shell swallows foreign navigations —
 * so this rule is the whole door: get it wrong in one direction and the button
 * does nothing, get it wrong in the other and the bridge is a general fetch of
 * any page a link asked for.
 */
public class MangaSourceTest {

    @Test
    public void theirOwnDomainOverHttpsIsTheSource() {
        assertTrue(MangaSource.isSource("https://mangafire.to/title/ro8ro"));
        assertTrue(MangaSource.isSource("https://mangafire.to/"));
        assertTrue(MangaSource.isSource("https://www.mangafire.to/title/ro8ro"));
    }

    @Test
    public void anythingElseIsRefused() {
        // A plain-http page is a downgrade the WebView would then hold.
        assertFalse(MangaSource.isSource("http://mangafire.to/title/ro8ro"));
        // A lookalike reads as their domain to a substring test.
        assertFalse(MangaSource.isSource("https://mangafire.to.evil.example/title/1"));
        assertFalse(MangaSource.isSource("https://notmangafire.to/title/1"));
        // Their image CDN serves scans, not pages: the reader's own route has it.
        assertFalse(MangaSource.isSource("https://static.mfcdn.nl/objects/1.jpg"));
        // The page's own origin, a scheme with no host, and nothing at all.
        assertFalse(MangaSource.isSource("https://hanime.tv/"));
        assertFalse(MangaSource.isSource("javascript:alert(1)"));
        assertFalse(MangaSource.isSource("not a url"));
        assertFalse(MangaSource.isSource(""));
        assertFalse(MangaSource.isSource(null));
    }
}
