package app.hanime.shell;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One manga reader page, fetched the way mangafire's own player fetches it.
 *
 * Their image CDN answers a hotlink with no mangafire Referer with a 403, so
 * the page can never load the pages itself: it asks this route, which adds
 * the Referer and hands the bytes straight to the WebView. The target is not
 * arbitrary — https only, on a host that belongs to their CDN — otherwise an
 * image proxy would be an open fetch hole into the device's network.
 */
final class MangaPage {

    private static final int TIMEOUT_MS = 20_000;

    private MangaPage() {
    }

    /** True for their CDN and their own domain, nothing else. */
    private static boolean allowed(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("mangafire.to") || h.endsWith(".mangafire.to")) return true;
        // static.mfcdn.nl, k99.mfcdn3.xyz, l1n.mfcdn1.xyz — the subdomain and
        // the digit after "mfcdn" both vary per file.
        return h.matches("^(?:[a-z0-9-]+\\.)*mfcdn\\d*\\.(?:nl|xyz|com)$");
    }

    static WebResourceResponse serve(String target) {
        URL url = null;
        try {
            url = target == null ? null : new URL(target);
        } catch (Exception ignored) {
            url = null;
        }
        if (url == null || !"https".equals(url.getProtocol()) || !allowed(url.getHost())) {
            return plain(403, "Forbidden", "host not allowed");
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Referer", "https://mangafire.to/");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");

            int status = conn.getResponseCode();
            if (status != 200) {
                conn.disconnect();
                return plain(502, "Bad Gateway", "upstream " + status);
            }

            String type = conn.getContentType();
            if (type == null) type = "image/jpeg";
            type = type.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!type.startsWith("image/")) {
                conn.disconnect();
                return plain(502, "Bad Gateway", "upstream did not answer an image");
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("cache-control", "public, max-age=86400");
            headers.put("access-control-allow-origin", "*");
            // No disconnect here: the WebView reads this stream afterwards,
            // and closing the connection under it would truncate the image.
            InputStream in = conn.getInputStream();
            return new WebResourceResponse(type, null, 200, "OK", headers, in);
        } catch (Exception e) {
            return plain(502, "Bad Gateway", "upstream failed: " + e.getMessage());
        }
    }

    private static WebResourceResponse plain(int status, String reason, String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain; charset=utf-8");
        return new WebResourceResponse("text/plain", "UTF-8", status, reason, headers,
                new ByteArrayInputStream(bytes));
    }
}
