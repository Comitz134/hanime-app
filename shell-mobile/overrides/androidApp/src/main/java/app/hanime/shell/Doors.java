package app.hanime.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The film doors, opened the way the desktop twin opens them — in a browser,
 * except this browser is the app's own.
 *
 * A door is a player page, not a stream: cinehd frames ythd/vixsrc/vidplus/
 * videasy/vidfast, and the URL a phone can actually decode only exists after
 * that page has booted its player. The desktop answers this by borrowing a
 * headless Chrome over CDP (`server/src/browser.mjs`); a phone has a Chromium of
 * its own, so the same job is done here with a WebView that is never shown:
 * load the door, watch what it asks its CDN for, and hand back the first
 * playlist it requests.
 *
 * Interception is the reliable half — every one of these players fetches a
 * `.m3u8` no matter how it is framed — and the JS probe is the belt to that
 * brace, because two of the six never put the URL in a request this class can
 * see (they assemble it in the page and hand it to a video element):
 *
 *   * `performance.getEntriesByType('resource')` names every URL the page has
 *     fetched, playlist included;
 *   * `video.currentSrc` is where a hand-assembled one ends up once the player
 *     starts.
 *
 * A URL that comes back is ordinary HLS behind the door's own referer, so it is
 * handed to {@link HlsRelay}, which serves it (and every nested URI) from
 * loopback with that referer attached. Nothing about playback then needs a
 * browser, which is the point: the phone's player is where a film belongs.
 *
 * One door does not need any of that: vixsrc writes the playlist URL into its
 * embed page's markup and answers the rest over plain HTTP, so it is resolved
 * by {@link Vixsrc} — no page, no Chromium, about a second.
 *
 * A resolution costs a page load — seconds, not milliseconds — so answers are
 * cached per door URL and a door that refused is remembered too, for less long.
 * {@link #resolver} is the seam the JVM tests replace; nothing in a test should
 * ever dial a door.
 */
final class Doors {

    private static final String TAG = "ShellDoors";

    /**
     * A WebView's own UA carries the `; wv` token, and several doors look for
     * it to refuse framing. This is a plain Chrome for Android string, which is
     * both the shape the door expects and what its mobile player is built for.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    /** One door is opened once and the answer is reused while it lasts. */
    private static final long CACHE_TTL_MS = 15 * 60_000L;
    /** A door that did not answer is not knocked on again for this long. */
    private static final long MISS_TTL_MS = 4 * 60_000L;
    /** How long one page may take before the walk moves to the next door. */
    private static final long ATTEMPT_MS = 9_000;
    /** How long the caller waits for the UI thread to say anything at all. */
    private static final long AWAIT_PAD_MS = 3_000;

    /** What a door gave up: a media URL, and the referer its CDN demands. */
    static final class Found {
        final String url;
        final String referer;

        Found(String url, String referer) {
            this.url = url;
            this.referer = referer;
        }
    }

    /** The seam: production opens a WebView, a test answers from a table. */
    interface Resolver {
        Found open(Context context, String doorUrl);
    }

    static volatile Resolver resolver = new WebViewResolver();

    private static final Map<String, Found> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> MISSES = new ConcurrentHashMap<>();

    /** One page at a time: there is one UI thread and one WebView budget. */
    private static final Object LOCK = new Object();

    private Doors() {
    }

    /** Cleared between tests so one test's answers never seed the next. */
    static void clearCache() {
        CACHE.clear();
        MISSES.clear();
    }

    /**
     * Forget one door's answer, so the next ask opens the page again.
     *
     * A door's first playlist is not always the one it plays: ythd hands out an
     * unsigned URL first and a tokenised one a moment later, and caching the
     * first for fifteen minutes would serve a link that 403s for the rest of
     * the session — including to the player's own *Refresh streams*. When a
     * caller finds an answer that will not load, this is how it says so.
     */
    static void forget(String doorUrl) {
        if (doorUrl == null) return;
        CACHE.remove(doorUrl);
        MISSES.remove(doorUrl);
    }

    /**
     * A door URL in, a playable URL out — or null when this door has nothing to
     * give right now. Never throws: a caller is listing sources, and a door that
     * failed is a door that is skipped.
     */
    static Found resolve(Context context, String doorUrl) {
        if (context == null || doorUrl == null || !doorUrl.startsWith("https://")) return null;
        Found hit = CACHE.get(doorUrl);
        if (hit != null) return hit;
        Long missAt = MISSES.get(doorUrl);
        if (missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) return null;

        synchronized (LOCK) {
            // Another request may have answered while this one waited its turn.
            hit = CACHE.get(doorUrl);
            if (hit != null) return hit;
            missAt = MISSES.get(doorUrl);
            if (missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) return null;

            // vixsrc is the one door whose page has nothing a browser adds:
            // its own two requests answer over plain HTTP, so it is asked that
            // way — a second instead of nine, and a refusal (its playlist
            // endpoint answers 403 to some networks) is answered then and
            // there rather than waited out in a page that would ask the same
            // two questions.
            boolean plain = Vixsrc.isDoor(doorUrl);
            Found found = plain ? Vixsrc.resolve(doorUrl) : null;
            if (!plain) {
                try {
                    found = resolver.open(context, doorUrl);
                } catch (Exception e) {
                    Log.w(TAG, "door failed: " + doorUrl, e);
                }
            }
            if (found == null || found.url == null) {
                // Logged, not just remembered: a door that stops answering is
                // the difference between "this film has no sources" and "the
                // first door went dark", and that is worth a line of logcat.
                Log.i(TAG, plain
                        ? "no playlist from " + doorUrl + " (asked over plain HTTP)"
                        : "no playlist from " + doorUrl + " within " + ATTEMPT_MS + "ms");
                MISSES.put(doorUrl, System.currentTimeMillis());
                if (CACHE.size() > 200) CACHE.clear();
                return null;
            }
            Log.i(TAG, doorUrl + " -> " + found.url);
            CACHE.put(doorUrl, found);
            MISSES.remove(doorUrl);
            return found;
        }
    }

    /** The origin a door URL belongs to, as `https://host/`. */
    static String originOf(String doorUrl) {
        try {
            URL parsed = new URL(doorUrl);
            String host = parsed.getHost();
            if (host == null) return null;
            int port = parsed.getPort();
            return parsed.getProtocol() + "://" + host + (port > 0 ? ":" + port : "") + "/";
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ the browser

    /**
     * One hidden page per resolution. A WebView cannot be created off the UI
     * thread, so the caller posts the whole attempt and waits on a latch: the
     * request thread is blocked either way, and the UI thread must not be.
     */
    static final class WebViewResolver implements Resolver {
        @Override
        public Found open(Context context, String doorUrl) {
            return new Attempt(context.getApplicationContext(), doorUrl).run();
        }
    }

    private static final class Attempt {

        private final Context context;
        private final String doorUrl;
        private final String referer;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final CountDownLatch done = new CountDownLatch(1);

        private volatile Found found;
        /** A bare `.mp4` is only used when no playlist ever appears. */
        private volatile String fallback;
        private volatile WebView web;

        private final Runnable poll = this::probe;
        private final Runnable expiry = this::finish;

        Attempt(Context context, String doorUrl) {
            this.context = context;
            this.doorUrl = doorUrl;
            this.referer = originOf(doorUrl);
        }

        Found run() {
            MainThread.post(this::begin);
            try {
                done.await(ATTEMPT_MS + AWAIT_PAD_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Found hit = found;
            if (hit == null && fallback != null) hit = new Found(fallback, referer);
            // Every touch of a WebView is a main-thread touch, teardown included.
            MainThread.post(this::close);
            return hit;
        }

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        private void begin() {
            try {
                WebView created = new WebView(context);
                WebSettings settings = created.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setUserAgentString(USER_AGENT);
                // These players call play() from their own boot code, not from a
                // tap, and a page that needs a gesture before it fetches its
                // playlist never fetches one.
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                CookieManager.getInstance().setAcceptThirdPartyCookies(created, true);

                created.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                     WebResourceRequest request) {
                        try {
                            if (request != null && request.getUrl() != null) {
                                capture(request.getUrl().toString());
                            }
                        } catch (Exception ignored) {
                            // An unreadable request is not a reason to fail the page.
                        }
                        return null;      // the page behaves exactly as it would in a tab
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view,
                                                           WebResourceRequest request) {
                        return false;     // a door may redirect through its own pages
                    }
                });

                web = created;
                created.loadUrl(doorUrl);
                main.postDelayed(poll, 1_200);
                main.postDelayed(expiry, ATTEMPT_MS);
            } catch (Throwable t) {
                Log.w(TAG, "no page for " + doorUrl, t);
                finish();
            }
        }

        /** The JS probe: where a hand-assembled playlist ends up. */
        private void probe() {
            WebView target = web;
            if (target == null || found != null) return;

            String probeScript = "(function(){"
                    + "try{var v=document.querySelector('video');"
                    + "if(v&&v.currentSrc&&v.currentSrc.indexOf('blob:')!==0)return v.currentSrc;"
                    + "if(v&&v.src&&v.src.indexOf('blob:')!==0)return v.src;"
                    + "}catch(e){}"
                    + "try{var s=document.querySelectorAll('source');"
                    + "for(var i=0;i<s.length;i++){if(s[i].src)return s[i].src;}"
                    + "}catch(e){}"
                    + "try{var n=performance.getEntriesByType('resource')||[];"
                    + "for(var i=0;i<n.length;i++){var u=n[i].name||'';"
                    + "if(u.indexOf('.m3u8')>=0)return u;}"
                    + "}catch(e){}"
                    + "return null;})()";

            try {
                target.evaluateJavascript(probeScript, value -> {
                    String answer = unwrap(value);
                    if (answer != null) capture(answer);
                    if (found == null) main.postDelayed(poll, 900);
                });
            } catch (Exception e) {
                // The page went away mid-probe; the deadline still ends this.
            }
        }

        /** Whatever the page asked for, kept if it is something a player can open. */
        private void capture(String url) {
            if (url == null || found != null) return;
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("blob:") || lower.startsWith("data:")) return;

            int mark = lower.indexOf('?');
            String path = mark < 0 ? lower : lower.substring(0, mark);
            if (path.endsWith(".m3u8")) {
                found = new Found(url, referer);
                done.countDown();
                // The player has what it needs; segments downloaded by the page
                // from here on are traffic nobody asked for.
                MainThread.post(this::close);
            } else if (path.endsWith(".mp4") && fallback == null) {
                fallback = url;
            }
        }

        private void finish() {
            if (found == null && fallback != null) found = new Found(fallback, referer);
            done.countDown();
        }

        private void close() {
            WebView target = web;
            web = null;
            try {
                main.removeCallbacks(poll);
                main.removeCallbacks(expiry);
            } catch (Exception ignored) {
                // A handler that lost its looper is a handler that will not fire.
            }
            if (target == null) return;
            try {
                target.stopLoading();
                target.loadUrl("about:blank");
                target.destroy();
            } catch (Exception ignored) {
                // Destroying a page that is already gone is not an error.
            }
        }

        /** evaluateJavascript answers JSON: `"https://…"` or `null`. */
        private static String unwrap(String jsonValue) {
            if (jsonValue == null || jsonValue.equals("null") || jsonValue.equals("undefined")) {
                return null;
            }
            String value = jsonValue;
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            return value.isEmpty() ? null : value;
        }
    }

    /**
     * The playlist a body contains, if it contains one — for the callers that
     * hold a page's bytes rather than its requests. Not used by the WebView path
     * (interception answers directly), but kept beside the rest of the door
     * logic so a future non-WebView resolver has one definition to share.
     */
    static String playlistIn(String body) {
        if (body == null || body.isEmpty()) return null;
        Matcher m = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)").matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
