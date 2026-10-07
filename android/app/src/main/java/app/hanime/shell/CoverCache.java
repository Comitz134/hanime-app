package app.hanime.shell;

import android.content.Context;
import android.util.Log;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Covers for the titles in the library, kept as files inside the app.
 *
 * The library is the one screen that is supposed to work with no network: the
 * favourites and the history are already local, and a grid of broken images is
 * what that promise looks like when it is only half kept. WebView's own disk
 * cache is not a substitute — it is best-effort, it is evicted under pressure,
 * and it cannot be asked what it holds.
 *
 * So: when a title enters the library the page asks for its cover to be stored
 * here ({@code Shell.cacheCover}); the page then draws library cards from
 * {@code /covers/<slug>}, which this class answers from disk. Nothing about
 * browsing changes — the grid still loads its images straight from the image
 * CDN, and only the titles that were actually kept are copied.
 */
final class CoverCache {

    private static final String TAG = "ShellCovers";
    private static final String DIR = "covers";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private final Context context;
    private final File dir;

    CoverCache(Context context) {
        this.context = context.getApplicationContext();
        this.dir = new File(this.context.getFilesDir(), DIR);
    }

    /**
     * The local address of a stored cover, or "" when there is none — which is
     * the page's signal to keep using the remote URL.
     */
    String path(String slug) {
        File file = file(slug);
        if (file.exists() && file.length() > 0) return "/" + DIR + "/" + file.getName();
        return "";
    }

    /**
     * Stores a title's cover, unless it is already stored. Fire and forget: a
     * cover that cannot be fetched is not an error anywhere, because the page
     * still has the remote image to fall back on.
     */
    void fetch(String slug, String url) {
        if (slug == null || slug.isEmpty() || url == null || url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;

        final File target = file(slug);
        if (target.exists() && target.length() > 0) return;

        POOL.execute(() -> {
            File temp = new File(dir, target.getName() + ".part");
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("no covers dir");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                // The page's own origin and user agent: the image CDN sees the
                // same request it would have seen from the WebView.
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 hanime-shell/"
                                + BuildConfig.VERSION_NAME);
                conn.setRequestProperty("Referer", ApiServer.ORIGIN + "/");
                conn.setRequestProperty("Accept", "image/*");
                try {
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IllegalStateException("HTTP " + code);
                    long total = 0;
                    byte[] buffer = new byte[32 * 1024];
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(temp)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            total += n;
                            if (total > MAX_BYTES) throw new IllegalStateException("cover too large");
                            out.write(buffer, 0, n);
                        }
                        out.flush();
                    }
                    if (total == 0) throw new IllegalStateException("empty cover");
                    // Rename last, so a half-written cover is never served.
                    if (!temp.renameTo(target)) throw new IllegalStateException("rename failed");
                    Log.i(TAG, "stored cover for " + slug + " (" + total + " bytes)");
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "could not store the cover for " + slug + ": " + e);
                temp.delete();
            }
        });
    }

    /** Answers /covers/<name> from disk. */
    WebResourceResponse serve(String path) {
        String name = path.startsWith("/" + DIR + "/")
                ? path.substring(DIR.length() + 2)
                : "";
        File file = new File(dir, name);
        try {
            // name comes from the page, so it is checked rather than trusted:
            // only files directly inside the covers directory are servable.
            if (name.isEmpty() || !file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                return notFound();
            }
            if (!file.exists() || file.length() == 0) return notFound();

            Map<String, String> headers = new HashMap<>();
            headers.put("access-control-allow-origin", "*");
            headers.put("cache-control", "public, max-age=604800");
            return new WebResourceResponse(mime(file.getName()), null, 200, "OK",
                    headers, new FileInputStream(file));
        } catch (Exception e) {
            return notFound();
        }
    }

    /** Total bytes held, for the settings screen's one diagnostic line. */
    long bytes() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.length();
        return total;
    }

    private File file(String slug) {
        return new File(dir, name(slug));
    }

    /**
     * A file name this class chose, derived from the slug. Upstream data must
     * never decide a path, even when it looks like a slug.
     */
    private static String name(String slug) {
        String base = slug.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(base.length() + 4);
        for (int i = 0; i < base.length() && sb.length() < 96; i++) {
            char c = base.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            sb.append(safe ? c : '-');
        }
        return sb.append(".jpg").toString();
    }

    private static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }
}
