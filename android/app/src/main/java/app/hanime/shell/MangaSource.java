package app.hanime.shell;

import java.net.URL;
import java.util.Locale;

/**
 * The one page this app walks to of its own accord: a manga title, on
 * mangafire.to.
 *
 * The page cannot walk there by itself. Every foreign navigation is swallowed
 * (see MainActivity.ShellPage.shouldOverrideUrlLoading) — that is what keeps an
 * injected link from opening the browser over a player — so the title page's
 * own button asks the shell's bridge, and the bridge asks this. Being a door of
 * its own, it is a narrow one: https, and only mangafire's own domain. Their
 * image CDN is deliberately not here: a title page is theirs to render, and the
 * pages of a chapter stay with the reader's own route (/api/manga/page).
 *
 * Pure on purpose — a URL in, a boolean out, no device — so the rule that
 * decides what that button can reach is a test rather than a hope.
 */
final class MangaSource {

    private MangaSource() {
    }

    /** True for https on mangafire.to or a subdomain of it, and nothing else. */
    static boolean isSource(String target) {
        URL url;
        try {
            url = target == null ? null : new URL(target);
        } catch (Exception ignored) {
            return false;
        }
        if (url == null || !"https".equals(url.getProtocol())) return false;
        String host = url.getHost();
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("mangafire.to") || h.endsWith(".mangafire.to");
    }
}
