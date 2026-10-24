package app.hanime.shell;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The catalogue, on loopback, inside this app.
 *
 * The desktop window runs this repository's Node server as a child process and
 * points Nuvio's addon list at it. A phone cannot do that — there is no Node to
 * spawn and no process to keep — so the same job is done here in process: a
 * small HTTP server on 127.0.0.1:8787 that answers the *addon protocol Nuvio's
 * own UI speaks* (manifest, catalog, meta, stream, subtitles) out of the same
 * Java sources the WebView shell used. That is why the app a reader installed
 * from this repository reports "Failed to connect to /127.0.0.1:8787" before
 * this class exists: the account's addon row already names this address, and
 * something has to be listening on it.
 *
 * Which addresses are the app's business, not ours: an account's addon list is
 * shared with the desktop window, so the row that reaches a phone is
 * `http://127.0.0.1:8787/addon/manifest.json` and this server is what answers
 * it. Binding is deliberately loopback-only — nothing here is reachable from
 * the network, so the phone exposes no catalogue to the LAN and needs no
 * permission it did not already have.
 *
 * Ids are the account's own, the ones the desktop writes: `ha:ad:<slug>` for a
 * video from the 18+ shelf (and `ha:an:…`, `ha:fm:…`, `ha:ft:…` for the other
 * areas, which the desktop and the web client build). They are parsed, not
 * looked up — a series episode carries its season and episode in the tail.
 */
final class SekaiServer {

    private static final String TAG = "SekaiServer";

    /** The port the account's addon row names. Changing it strands every client. */
    static final int PORT = 8787;

    /**
     * The codes MyAnimeList's redirect dropped at this server's own root, by
     * the state the app minted (see [Mal] and MangaMal.kt). The browser and
     * the app are different programs even on one phone, so the redirect lands
     * in the browser and the app polls for it — the same handover the desktop
     * window does through its Node server. In memory only: a code is a
     * one-time credential and nothing about it outlives the login.
     */
    private static final Map<String, String> MAL_CODES = new ConcurrentHashMap<>();
    private static final Map<String, Long> MAL_CODE_EXPIRY = new ConcurrentHashMap<>();
    private static final long MAL_CODE_TTL_MS = 10 * 60_000L;
    private static final int MAL_STATE_MAX = 256;
    private static final int MAL_CODE_MAX = 4096;

    /**
     * The same handover for AniList, kept apart by the state's prefix: both
     * providers send the browser back to this server's root, so which map a
     * code belongs in is decided by the state the app minted rather than by the
     * path the browser landed on.
     */
    private static final Map<String, String> ANILIST_CODES = new ConcurrentHashMap<>();
    private static final Map<String, Long> ANILIST_CODE_EXPIRY = new ConcurrentHashMap<>();
    private static final String ANILIST_STATE_PREFIX = "anilist-";

    /** The origin covers are fetched with: the CDN checks this referer. */
    private static final String COVER_ORIGIN = "https://hanime.tv";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** One addon page, the same size the desktop's shelves ask for. */
    private static final int PAGE_SIZE = 24;

    /**
     * How long a stream request may spend opening embed pages before it answers
     * with what it has. A door costs a page load, so this is a walk, not a fan
     * out: a few seconds each, and a door that already gave up is remembered.
     */
    private static final long RESOLVE_BUDGET_MS = 30_000;

    /**
     * The film walk's own, shorter budget. A film has six doors and the first
     * that answers wins, so a reader is waiting on a page load rather than a
     * chain; the app's own request for a stream list times out before a long
     * walk would finish, and a door that did not answer is remembered anyway.
     */
    private static final long FILM_BUDGET_MS = 20_000;

    /**
     * The film door that last handed over a playlist. Doors go dark and come
     * back, so this only reorders the walk — a remembered door that fails is
     * skipped by the resolver like any other.
     */
    private static volatile String preferredDoor;

    private static final int COVER_CACHE_BYTES = 24 * 1024 * 1024;

    private static volatile boolean started;

    /**
     * The application context, handed over by the start-up hook. Two things need
     * it and neither can be done without one: reading the signer's assets, and
     * creating the WebView that runs them.
     */
    private static volatile android.content.Context application;

    private SekaiServer() {
    }

    static void setContext(android.content.Context context) {
        if (context != null) {
            application = context.getApplicationContext();
            // manga needs a signer of its own: their API is signed by a bundle
            // that only runs in a WebView (see [MangaSigner]), and building one
            // costs the main thread about a fifth of a second. It is warmed
            // here rather than on the request that needs it — the first chapter
            // list after a cold start would otherwise wait for Chromium to
            // load — but not from this provider, which runs before the first
            // frame: the warm-up is put a second out, where it lands after the
            // shell is drawn and still long before a thumb can reach the manga
            // tab. Measured on the phone: the same WebView created on the boot
            // path had the app displayed at 685 ms and finished loading *after*
            // that frame anyway.
            android.os.Handler main =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            main.postDelayed(() -> Manga.ensure(application), WARM_UP_MS);
        }
    }

    /** When the manga signer's WebView is built, in milliseconds after boot. */
    private static final long WARM_UP_MS = 1_000L;

    /** Idempotent: the app may be started twice in one process. */
    static synchronized void start() {
        if (started) return;
        started = true;
        Thread thread = new Thread(SekaiServer::acceptLoop, "sekai-http");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------- the socket

    /**
     * How long a build that lost the port waits before trying again.
     *
     * The port is the whole device's, not this package's: a release build and a
     * debug build both carry this server, and whichever started first owns
     * 127.0.0.1:8787 — so the second one silently answers from the first one's
     * process, with the first one's code and the first one's stored list. That
     * is a bug that looks like "the app needs a restart", and the restart only
     * fixes it by accident (whichever build comes back first wins the port).
     * Retrying means the build that lost the race takes the port over by itself
     * the moment the other one stops, instead of needing that restart.
     */
    private static final long BIND_RETRY_MS = 2_000L;

    private static void acceptLoop() {
        ServerSocket server = null;
        boolean told = false;
        int attempt = 0;
        while (server == null) {
            attempt++;
            try {
                server = new ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"));
            } catch (java.net.BindException held) {
                if (!told) {
                    told = true;
                    Log.e(TAG, "127.0.0.1:" + PORT + " is already in use — another install of this "
                            + "app (release, fast or debug) is running and owns it. Only one build "
                            + "can serve this port, and every request this app makes is being "
                            + "answered by that one. Keeping this thread alive so it takes the "
                            + "port over when the other build stops.", held);
                }
                // Capped backoff: quick enough to take over the moment the other
                // build exits, lazy enough not to be a spin on a phone idle in a
                // pocket for an hour.
                try {
                    Thread.sleep(Math.min(BIND_RETRY_MS * attempt, 30_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "cannot bind 127.0.0.1:" + PORT + " — the addon will not load", e);
                synchronized (SekaiServer.class) {
                    started = false;
                }
                return;
            }
        }
        Log.i(TAG, "listening on http://127.0.0.1:" + PORT);
        // Eight, not four: a stream request can sit for seconds waiting on a
        // door, and the player fetching segments from the relay must not be
        // queued behind it.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        while (true) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (Exception e) {
                Log.w(TAG, "accept failed", e);
                continue;
            }
            pool.execute(() -> serve(socket));
        }
    }

    private static void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20_000);
            // Requests here are one-shot: a Stremio client opens its own
            // connection per call, and a player fetching ranges does the same.
            // Answering with `connection: close` keeps the parser small enough
            // to read in one sitting, at the cost of a connection per request.
            Request request = Request.read(s.getInputStream());
            if (request == null) return;

            Resp response;
            try {
                response = route(request);
            } catch (Exception e) {
                Log.w(TAG, "handler failed for " + request.path, e);
                response = Resp.json(500, "{\"error\":\"internal\",\"message\":"
                        + quote(message(e)) + "}");
            }
            response.write(s.getOutputStream());
        } catch (Exception e) {
            Log.w(TAG, "connection failed", e);
        }
    }

    // ------------------------------------------------------------------ routes

    private static Resp route(Request request) throws Exception {
        String path = request.path;

        if (path.equals("/health")) {
            return Resp.json(200, "{\"ok\":true,\"server\":\"sekai\",\"port\":" + PORT + "}");
        }
        // The signed HLS relay: playlists rewritten so every nested URI comes
        // back through here, segments proxied with the headers upstream wants.
        if (path.equals("/relay")) return HlsRelay.handle(request.query);
        // The signer's host page and its module, straight out of the APK, for a
        // check over loopback (`adb forward` + `/signer.html` says whether the
        // module boots on this device). The signer's own WebView does not use
        // this route: it loads the page from hanime.tv's origin and serves it
        // from the APK there, because the signature the module mints is bound
        // to that origin. See [Signer].
        if (path.equals("/signer.html") || path.equals("/htv-signer.js")
                || path.equals("/htv-signer.wasm")) {
            return asset(path.substring(1));
        }
        // MyAnimeList's redirect URI is this server's own root, registered on
        // the project's MAL application. The browser lands here with a code;
        // the app collects it below. The same origin is why the desktop window
        // and the phone can share one registration.
        if (path.equals("/") || path.equals("/index.html")) return malLanding(request);
        if (path.equals("/manga/mal/callback")) return malCallback(request);
        if (path.equals("/api/mal/token")) return malToken(request);
        if (path.startsWith("/api/mal/")) return malApi(request, path);
        // AniList: the same shape of handover and the same one-hop relay, with a
        // GraphQL body instead of a REST path.
        if (path.equals("/anilist/callback")) return anilistCallback(request);
        if (path.equals("/api/anilist/token")) return anilistToken(request);
        if (path.equals("/api/anilist/graphql")) return anilistGraphql(request);

        if (path.equals("/addon/") || path.equals("/addon/manifest.json")) {
            return Resp.json(200, manifest().toString());
        }
        if (path.startsWith("/addon/")) return addon(request, path);
        if (path.equals("/covers")) return cover(request);
        // manga: the same job for a shelf their API does not speak — the reader's
        // own routes, shaped here rather than fetched by the UI itself, because
        // their API signs every listing call and their image CDN wants a referer.
        if (path.startsWith("/manga/")) return manga(request, path);

        return Resp.json(404, "{\"error\":\"not_found\",\"path\":" + quote(path) + "}");
    }

    // ------------------------------------------------------ myanimelist

    /**
     * The browser's half of a login, for MyAnimeList and for AniList.
     *
     * Both send the browser back to `http://localhost:8787/` — the redirect URI
     * registered on each application. On a phone that address is this server,
     * so the code arrives here and is kept for the app that minted the state,
     * for ten minutes at most. The state's prefix says which provider the code
     * belongs to, because the landing page cannot tell otherwise: AniList sends
     * `code` and `state` exactly as MAL does. A redirect carrying an `error` is
     * the provider saying the person declined; the page says so and nothing is
     * stashed.
     */
    private static Resp malLanding(Request request) {
        String state = text(request.query, "state");
        String code = text(request.query, "code");
        String error = text(request.query, "error");
        boolean anilist = state.startsWith(ANILIST_STATE_PREFIX);
        String provider = anilist ? "AniList" : "MyAnimeList";

        if (!error.isEmpty()) return Resp.html(200, malPage(
                "Not signed in",
                provider + " sent this device back with: " + escapeHtml(error)
                        + ". You can close this tab and try again in the app."));

        if (!code.isEmpty() && !state.isEmpty()) {
            if (anilist) {
                boolean believable = state.length() <= MAL_STATE_MAX && code.length() <= MAL_CODE_MAX;
                if (believable) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, Long> entry : ANILIST_CODE_EXPIRY.entrySet()) {
                        if (entry.getValue() <= now) {
                            ANILIST_CODE_EXPIRY.remove(entry.getKey());
                            ANILIST_CODES.remove(entry.getKey());
                        }
                    }
                    ANILIST_CODES.put(state, code);
                    ANILIST_CODE_EXPIRY.put(state, now + MAL_CODE_TTL_MS);
                }
                return Resp.html(200, malPage(
                        believable ? "Signed in" : "Something looked wrong",
                        believable
                                ? "SEKAI has the code. Close this tab and go back to the app — the AniList card finishes the link on its own."
                                : "That redirect did not look like one AniList produced, so the app did not take it."));
            }
            boolean believable = state.length() <= MAL_STATE_MAX && code.length() <= MAL_CODE_MAX;
            if (believable) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, Long> entry : MAL_CODE_EXPIRY.entrySet()) {
                    if (entry.getValue() <= now) {
                        MAL_CODE_EXPIRY.remove(entry.getKey());
                        MAL_CODES.remove(entry.getKey());
                    }
                }
                MAL_CODES.put(state, code);
                MAL_CODE_EXPIRY.put(state, now + MAL_CODE_TTL_MS);
            }
            return Resp.html(200, malPage(
                    believable ? "Signed in" : "Something looked wrong",
                    believable
                            ? "SEKAI has the code. Close this tab and go back to the app — the title you were on will show your MyAnimeList status."
                            : "That redirect did not look like one MyAnimeList produced, so the app did not take it."));
        }

        return Resp.json(404, "{\"error\":\"not_found\"}");
    }

    /**
     * GET /anilist/callback?state=… — the same one-read handover for AniList.
     */
    private static Resp anilistCallback(Request request) throws Exception {
        String state = text(request.query, "state");
        if (state.isEmpty()) return Resp.json(400, "{\"error\":\"missing_state\"}");
        String code = ANILIST_CODES.remove(state);
        ANILIST_CODE_EXPIRY.remove(state);
        if (code == null) return Resp.json(404, "{\"error\":\"none_pending\"}");
        return Resp.json(200, new JSONObject().put("code", code).put("state", state).toString());
    }

    /** POST /api/anilist/token?code=…&redirect_uri=… — the exchange, through [Anilist]. */
    private static Resp anilistToken(Request request) throws Exception {
        if (!"POST".equals(request.method)) {
            return Resp.json(405, "{\"error\":\"method_not_allowed\"}");
        }
        Anilist.Result result = Anilist.token(encodeQuery(request.query));
        return Resp.json(result.status, result.body);
    }

    /**
     * POST /api/anilist/graphql — one hop onto AniList's single GraphQL endpoint,
     * with the caller's bearer token. The JSON body goes over as it came.
     */
    private static Resp anilistGraphql(Request request) throws Exception {
        if (!"POST".equals(request.method)) {
            return Resp.json(405, "{\"error\":\"method_not_allowed\"}");
        }
        Anilist.Result result = Anilist.graphql(
                request.body,
                request.headers.get("authorization"));
        return Resp.json(result.status, result.body);
    }

    /**
     * GET /manga/mal/callback?state=… — the code the browser dropped, for the
     * app that minted that state. One read, then it is gone; a second poll is a
     * 404, which is also what a wrong or expired state answers.
     */
    private static Resp malCallback(Request request) throws Exception {
        String state = text(request.query, "state");
        if (state.isEmpty()) return Resp.json(400, "{\"error\":\"missing_state\"}");
        String code = MAL_CODES.remove(state);
        MAL_CODE_EXPIRY.remove(state);
        if (code == null) return Resp.json(404, "{\"error\":\"none_pending\"}");
        return Resp.json(200, new JSONObject().put("code", code).put("state", state).toString());
    }

    /** POST /api/mal/token?… — the PKCE exchange, through [Mal]. */
    private static Resp malToken(Request request) throws Exception {
        if (!"POST".equals(request.method)) {
            return Resp.json(405, "{\"error\":\"method_not_allowed\"}");
        }
        Mal.Result result = Mal.token(encodeQuery(request.query));
        return Resp.json(result.status, result.body);
    }

    /** /api/mal/v2/… — one hop onto api.myanimelist.net with the caller's token. */
    private static Resp malApi(Request request, String path) throws Exception {
        Mal.Result result = Mal.api(
                path.substring("/api/mal/".length()),
                request.query,
                request.method,
                request.headers.get("authorization"));
        return Resp.json(result.status, result.body);
    }

    /** A query string from the parsed pairs — for the one route that re-encodes. */
    private static String encodeQuery(Map<String, String> query) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                out.append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
        }
        return out.toString();
    }

    private static String malPage(String title, String message) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escapeHtml(title) + " · SEKAI</title>"
                + "<style>html,body{height:100%;margin:0}body{display:grid;place-items:center;"
                + "background:#0b0b0c;color:#f2f2f3;font:16px/1.5 system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif;"
                + "padding:24px}main{max-width:420px}h1{font-size:22px;margin:0 0 10px}p{margin:0;color:#a9a9b0}</style>"
                + "</head><body><main><h1>" + escapeHtml(title) + "</h1><p>" + escapeHtml(message)
                + "</p></main></body></html>";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ manga

    /**
     * `/manga/<what>.json` — mangafire.to, as this app's reader wants it.
     *
     * The paths are named for what the reader asks for rather than by echoing
     * their API, so the reader never learns an upstream id scheme it would have
     * to keep in step. Positions are the one piece of state here: a phone closes
     * apps without warning, and the reader's place is written as pages turn.
     */
    private static Resp manga(Request request, String path) {
        Manga.ensure(application);
        // The chapters saved on this phone: needed before anything upstream is
        // asked, because they are what a reader opens with no connection.
        MangaOffline.ensure(application);
        Map<String, String> q = request.query;
        try {
            switch (path) {
                case "/manga/trending.json":
                    return Resp.json(200, new JSONObject()
                            .put("items", Manga.trending(limit(q, 30))).toString());

                case "/manga/search.json":
                    // The caller's page, its page size and its filters are read
                    // in one place — `Manga.searchQuery` — because the page and
                    // the size were crossed here once, and the JVM tests
                    // (`MangaRouteTest`) hold that function now rather than a
                    // phone holding a search box that finds nothing.
                    return Resp.json(200, Manga.search(Manga.searchQuery(q)).toString());

                case "/manga/title.json": {
                    String hid = text(q, "hid");
                    if (hid.isEmpty()) return Resp.json(400, "{\"error\":\"missing_hid\"}");
                    return Resp.json(200, new JSONObject()
                            .put("title", Manga.detail(hid)).toString());
                }

                case "/manga/chapters.json": {
                    String hid = text(q, "hid");
                    if (hid.isEmpty()) return Resp.json(400, "{\"error\":\"missing_hid\"}");
                    try {
                        return Resp.json(200, new JSONObject()
                                .put("chapters", Manga.chapters(hid)).toString());
                    } catch (Exception offline) {
                        // With no connection the catalogue has nothing to say,
                        // but a title that has chapters saved here still has a
                        // chapter list — its own. The answer says so, so the
                        // screen can say "downloaded chapters" rather than
                        // pretending the catalogue answered.
                        JSONArray saved = MangaOffline.chapters(hid);
                        if (saved.length() > 0) {
                            return Resp.json(200, new JSONObject()
                                    .put("chapters", saved)
                                    .put("offline", true).toString());
                        }
                        throw offline;
                    }
                }

                case "/manga/pages.json": {
                    String chapter = text(q, "chapter");
                    if (chapter.isEmpty()) return Resp.json(400, "{\"error\":\"missing_chapter\"}");
                    // A saved chapter is read from this phone, not from their CDN:
                    // the page list names the saved files themselves, which is
                    // why a downloaded chapter opens with the radio off.
                    JSONObject saved = MangaOffline.pages(chapter);
                    if (saved != null) return Resp.json(200, saved.toString());
                    return Resp.json(200, Manga.pages(chapter).toString());
                }

                case "/manga/filters.json":
                    return Resp.json(200, Manga.filters().toString());

                case "/manga/sources.json":
                    // Repositories and the sources they installed, as one list:
                    // the settings section draws both from this answer, and a
                    // repository's count is its own sources rather than a
                    // number the screen has to add up.
                    MangaSources.ensure(application);
                    return Resp.json(200, MangaSources.state().toString());

                case "/manga/sources/add.json": {
                    String url = text(q, "url");
                    if (url.isEmpty()) return repoError("Enter a repository URL.");
                    try {
                        return Resp.json(200, MangaSources.addRepo(url).toString());
                    } catch (MangaSources.BadRepoException refused) {
                        return repoError(refused.getMessage());
                    }
                }

                case "/manga/sources/remove.json": {
                    String url = text(q, "url");
                    if (url.isEmpty()) return repoError("A repository URL is needed.");
                    try {
                        return Resp.json(200, MangaSources.removeRepo(url).toString());
                    } catch (MangaSources.BadRepoException refused) {
                        return repoError(refused.getMessage());
                    }
                }

                case "/manga/sources/refresh.json": {
                    String url = text(q, "url");
                    if (url.isEmpty()) return repoError("A repository URL is needed.");
                    try {
                        return Resp.json(200, MangaSources.refreshRepo(url).toString());
                    } catch (MangaSources.BadRepoException refused) {
                        return repoError(refused.getMessage());
                    } catch (Exception failed) {
                        return repoError("That repository could not be refreshed: " + mangaMessage(failed));
                    }
                }

                case "/manga/sources/repo.json": {
                    // One repository's whole index, for the sheet that picks
                    // extensions and sources: 1,400 rows do not belong in the
                    // settings answer.
                    String url = text(q, "url");
                    if (url.isEmpty()) return repoError("A repository URL is needed.");
                    try {
                        return Resp.json(200, MangaSources.repoDetail(url).toString());
                    } catch (MangaSources.BadRepoException refused) {
                        return repoError(refused.getMessage());
                    } catch (Exception failed) {
                        return repoError("That repository could not be read: " + mangaMessage(failed));
                    }
                }

                case "/manga/sources/toggleExtension.json": {
                    String url = text(q, "url");
                    String pkg = text(q, "pkg");
                    if (url.isEmpty() || pkg.isEmpty()) return repoError("An extension is needed.");
                    try {
                        return Resp.json(200, MangaSources.toggleExtension(url, pkg, number(q, "on") != 0).toString());
                    } catch (MangaSources.BadRepoException refused) {
                        return repoError(refused.getMessage());
                    } catch (Exception failed) {
                        return repoError("That extension could not be switched: " + mangaMessage(failed));
                    }
                }

                case "/manga/sources/toggle.json": {
                    String id = text(q, "id");
                    if (id.isEmpty()) return repoError("A source id is needed.");
                    try {
                        return Resp.json(200,
                                MangaSources.toggle(text(q, "url"), id, number(q, "on") != 0).toString());
                    } catch (MangaSources.BadRepoException refused) {
                        return repoError(refused.getMessage());
                    } catch (Exception failed) {
                        return repoError("That source could not be switched: " + mangaMessage(failed));
                    }
                }

                case "/manga/pos.json":
                    // `hid` and `chapter` together mean "the reader is here": a
                    // write. Either alone is not a position, and neither is a read.
                    if (!text(q, "hid").isEmpty() && !text(q, "chapter").isEmpty()) {
                        // `at` is when this chapter was read. A page turning is
                        // now; an import names the moment MyAnimeList says the
                        // chapter was read, so that a shelf ordered by last
                        // read is ordered by reading and not by importing.
                        long readAt = Manga.atOr(q.get("at"));
                        Manga.savePosition(new JSONObject()
                                .put("id", text(q, "hid"))
                                .put("title", text(q, "title"))
                                .put("poster", text(q, "poster"))
                                .put("chapter", text(q, "chapter"))
                                .put("number", text(q, "number"))
                                .put("page", number(q, "page"))
                                .put("pages", number(q, "pages"))
                                // A tracker sync writes the position, but it
                                // does not claim the title was read just now.
                                .put("quiet", text(q, "quiet"))
                                .put("at", readAt > 0 ? readAt : System.currentTimeMillis()));
                        return Resp.json(200, "{\"ok\":true}");
                    }
                    return Resp.json(200, new JSONObject()
                            .put("items", Manga.positions()).toString());

                case "/manga/library.json":
                    // The shelf of titles the reader chose: `hid` adds one,
                    // `remove=1` takes it back off, and neither is a read. The
                    // rows carry the position where there is one, so the Library
                    // tab can say "Ch. 12" on the titles that have been read.
                    if (!text(q, "hid").isEmpty() && !text(q, "remove").isEmpty()) {
                        Manga.removeFromLibrary(text(q, "hid"));
                        return Resp.json(200, "{\"ok\":true}");
                    }
                    if (!text(q, "hid").isEmpty()) {
                        // An import says when a title counts as added, because
                        // MyAnimeList's list arrives in an order (last read
                        // first) that this stamp is what preserves. Without one
                        // the title was added now.
                        long at = Manga.atOr(q.get("at"));
                        // And when it was last read, which an import knows from
                        // MyAnimeList's own `updated_at` and nothing else here
                        // does: `at` says when a title joined the shelf, and the
                        // shelf's "Last read" order needs the other one.
                        long read = Manga.atOr(q.get("read"));
                        // And which row of the account this title is, when an
                        // import is the one adding it.
                        long mal = Manga.atOr(q.get("mal"));
                        Manga.addToLibrary(new JSONObject()
                                .put("id", text(q, "hid"))
                                .put("title", text(q, "title"))
                                .put("poster", text(q, "poster"))
                                .put("read", read)
                                .put("mal", mal)
                                .put("at", at > 0 ? at : System.currentTimeMillis()));
                        return Resp.json(200, "{\"ok\":true}");
                    }
                    return Resp.json(200, new JSONObject()
                            .put("items", Manga.library()).toString());

                case "/manga/size.json": {
                    // The chapter count the app saw, remembered on the shelf row
                    // so the Library tab can say "Ch. 12 of 33" without asking
                    // upstream for every title it draws. Not a read: it writes
                    // no position and moves no stamp.
                    String hid = text(q, "hid");
                    if (hid.isEmpty()) return Resp.json(400, "{\"error\":\"missing_hid\"}");
                    Manga.setLibrarySize(hid, number(q, "chapters"));
                    return Resp.json(200, "{\"ok\":true}");
                }

                case "/manga/read.json": {
                    // Which chapters have been read. `chapter` is one chapter and
                    // `chapters` is a comma-joined list (the migration's seed),
                    // and either one is a write; a `hid` alone is a read of that
                    // title's marks, and no `hid` at all is all of them.
                    String hid = text(q, "hid");
                    String one = text(q, "chapter");
                    String many = text(q, "chapters");
                    if (hid.isEmpty()) {
                        return Resp.json(200, new JSONObject()
                                .put("items", Manga.allReadMarks()).toString());
                    }
                    if (!one.isEmpty() || !many.isEmpty()) {
                        java.util.List<String> numbers = new java.util.ArrayList<>();
                        if (!one.isEmpty()) numbers.add(one);
                        for (String number : many.split(",")) {
                            if (!number.trim().isEmpty()) numbers.add(number);
                        }
                        // `seed=1` is the one-time migration from the boundary
                        // rule: it may only fill a record that does not exist,
                        // or a visit to a title would rewrite the reader's own
                        // history with a guess made from the last position.
                        Manga.markRead(hid, numbers, "1".equals(text(q, "seed")));
                        return Resp.json(200, "{\"ok\":true}");
                    }
                    JSONArray numbers = new JSONArray();
                    java.util.List<String> sorted = new java.util.ArrayList<>(Manga.readMarks(hid));
                    java.util.Collections.sort(sorted);
                    for (String number : sorted) numbers.put(number);
                    return Resp.json(200, new JSONObject()
                            .put("chapters", numbers).toString());
                }

                case "/manga/downloads.json":
                    // Everything kept on this phone, and every chapter on its
                    // way: the Downloads screen and the reader's per-chapter
                    // buttons both draw from this one answer.
                    return Resp.json(200, MangaOffline.index().toString());

                case "/manga/download.json": {
                    // `chapter` alone asks for one chapter to be kept; with
                    // `remove=1` it forgets one, and `remove=1` without a
                    // chapter forgets the whole title.
                    String hid = text(q, "hid");
                    String chapter = text(q, "chapter");
                    if (hid.isEmpty()) return Resp.json(400, "{\"error\":\"missing_hid\"}");
                    boolean forget = !text(q, "remove").isEmpty();
                    if (forget) {
                        if (!chapter.isEmpty()) {
                            return Resp.json(200, new JSONObject()
                                    .put("ok", true)
                                    .put("removed", MangaOffline.remove(hid, chapter) ? 1 : 0)
                                    .toString());
                        }
                        return Resp.json(200, new JSONObject()
                                .put("ok", true)
                                .put("removed", MangaOffline.removeTitle(hid))
                                .toString());
                    }
                    if (chapter.isEmpty()) {
                        return Resp.json(400, "{\"error\":\"missing_chapter\"}");
                    }
                    String state = MangaOffline.queue(hid, chapter,
                            text(q, "number"), text(q, "name"), text(q, "at"),
                            text(q, "title"), text(q, "poster"));
                    return Resp.json(200, new JSONObject()
                            .put("ok", true).put("state", state).toString());
                }

                case "/manga/offline": {
                    // One saved page, straight off the phone's disk. The reader
                    // does not come through here — its page list names the files
                    // directly — but this is the same byte, for a caller that
                    // speaks http (a test, or a curl from a laptop on the link).
                    File file = MangaOffline.pageFile(text(q, "chapter"), number(q, "page"));
                    if (file == null) return Resp.json(404, "{\"error\":\"not_downloaded\"}");
                    byte[] body = MangaOffline.bytes(file);
                    if (body == null) return Resp.json(404, "{\"error\":\"not_downloaded\"}");
                    return new Resp(200, MangaOffline.contentType(file.getName()), body);
                }

                case "/manga/page":
                    return page(q.get("u"));
            }
            return Resp.json(404, "{\"error\":\"not_found\",\"path\":" + quote(path) + "}");
        } catch (Exception e) {
            Log.w(TAG, "manga " + path + " failed", e);
            return Resp.json(502, "{\"error\":\"upstream\",\"message\":"
                    + quote(mangaMessage(e)) + "}");
        }
    }

    /** A repository the reader can fix, answered as a 400 the screen can show. */
    private static Resp repoError(String message) {
        try {
            return Resp.json(400, new JSONObject()
                    .put("error", "repository")
                    .put("message", message == null ? "That repository was refused." : message)
                    .toString());
        } catch (Exception impossible) {
            return Resp.json(400, "{\"error\":\"repository\"}");
        }
    }

    /**
     * The sentence a reader sees when a catalogue call fails.
     *
     * A resolver that refuses mangafire.to arrives here as
     * `UnknownHostException("mangafire.to", "no address associated with
     * hostname")`, and that string — which names a host and not a problem, and
     * is not addressed to a person — was, for a while, exactly what the reader
     * was shown after restarting onto a network that was not up yet. It says
     * the thing that is true instead, and says what to do about it.
     */
    static String mangaMessage(Exception e) {
        String raw = message(e);
        if (e instanceof java.net.UnknownHostException || raw.contains("unable to resolve host")) {
            return "The phone could not look up mangafire.to. "
                    + "Check the connection and try again.";
        }
        return raw;
    }

    /** One reader page, with the referer their CDN insists on. */
    private static Resp page(String target) {
        if (target == null || target.isEmpty()) return Resp.json(400, "{\"error\":\"missing_u\"}");
        if (!target.startsWith("https://") && !target.startsWith("http://")) {
            return Resp.json(400, "{\"error\":\"bad_url\"}");
        }
        Manga.Page page = Manga.image(target);
        if (page == null) return Resp.json(502, "{\"error\":\"upstream\"}");
        return new Resp(200, page.contentType, page.body);
    }

    private static String text(Map<String, String> query, String key) {
        String value = query.get(key);
        return value == null ? "" : value.trim();
    }

    private static int number(Map<String, String> query, String key) {
        try {
            return Integer.parseInt(text(query, key));
        } catch (Exception ignored) {
            return 0;
        }
    }


    /** A shelf's size, bounded: a caller's page size is a hint, not an order. */
    private static int limit(Map<String, String> query, int fallback) {
        int value = number(query, "limit");
        return value <= 0 ? fallback : Math.min(value, 100);
    }

    /**
     * `/addon/<resource>/…`. The catalog resource has two spellings — the plain
     * `/addon/catalog/<type>/<id>.json` and the one clients use when they carry
     * their parameters in the path, `/addon/catalog/<type>/<id>/<skip=..&search=..>.json`
     * — and both mean the same request.
     */
    private static Resp addon(Request request, String path) throws Exception {
        String[] parts = path.replaceFirst("^/addon/?", "").split("/");
        String resource = parts[0];

        if (resource.equals("catalog") && (parts.length == 3 || parts.length == 4)) {
            String id = stripJson(parts[2]);
            Map<String, String> extra = catalogExtra(parts.length == 4 ? parts[3] : "", request);
            JSONArray metas = catalog(id, extra.get("search"), extra.get("skip"));
            return Resp.json(200, new JSONObject().put("metas", metas).toString());
        }

        if ((resource.equals("meta") || resource.equals("stream") || resource.equals("subtitles"))
                && parts.length == 3) {
            String id = stripJson(parts[2]);
            Id ref = Id.parse(id);
            if (ref == null) return Resp.json(404, "{\"error\":\"bad_id\"}");
            if (resource.equals("meta")) {
                JSONObject meta = meta(ref);
                if (meta == null) return Resp.json(404, "{\"error\":\"not_found\"}");
                return Resp.json(200, new JSONObject().put("meta", meta).toString());
            }
            if (resource.equals("stream")) {
                return Resp.json(200, new JSONObject().put("streams", streams(ref)).toString());
            }
            return Resp.json(200, new JSONObject()
                    .put("subtitles", Subtitles.tracks(ref, application)).toString());
        }

        return Resp.json(404, "{\"error\":\"not_found\",\"path\":" + quote(path) + "}");
    }

    // ------------------------------------------------------------- the manifest

    /**
     * The shelves this app offers. The names and the ids are the desktop
     * server's (`server/src/addon.mjs`), so the two screens show the same
     * catalogue under the same labels — and so a reader who knows where a shelf
     * is on one of them finds it in the same place on the other.
     */
    private static final String[][] CATALOGS = {
            {"anime-trending", "series", "Anime — trending now"},
            {"films-listing", "movie", "Films — popular now"},
            {"series-listing", "series", "Series — popular now"},
            {"adult-newest", "hentai", "Hentai — newest"},
            {"adult-views", "hentai", "Hentai — most viewed"},
    };

    static JSONObject manifest() throws Exception {
        JSONArray catalogs = new JSONArray();
        for (String[] catalog : CATALOGS) {
            JSONArray extra = new JSONArray();
            extra.put(new JSONObject().put("name", "search").put("isRequired", false));
            extra.put(new JSONObject().put("name", "skip").put("isRequired", false));
            catalogs.put(new JSONObject()
                    .put("type", catalog[1])
                    .put("id", catalog[0])
                    .put("name", catalog[2])
                    .put("extra", extra));
        }
        return new JSONObject()
                .put("id", "tv.hanime.bridge")
                .put("version", "1.0.0")
                .put("name", "SEKAI")
                .put("description", "Anime, films and series from this app, plus its own 18+ shelf.")
                .put("resources", new JSONArray()
                        .put("catalog").put("meta").put("stream").put("subtitles"))
                // `hentai` is this source's own shelf type, not a Stremio one:
                // the client builds its filters from the types a source
                // declares, so the 18+ shelves need one of their own.
                .put("types", new JSONArray().put("movie").put("series").put("hentai"))
                .put("catalogs", catalogs);
    }

    // -------------------------------------------------------------- the shelves

    private static JSONArray catalog(String id, String search, String skip) throws Exception {
        if (id.equals("adult-newest") || id.equals("adult-views")) {
            return adultShelf(id, search, skip);
        }
        if (id.equals("anime-trending")) return animeShelf(search, skip);
        if (id.equals("films-listing")) return filmShelf(false, search, skip);
        if (id.equals("series-listing")) return filmShelf(true, search, skip);
        // An unknown shelf is an empty one: a complete answer to the protocol,
        // which the client draws as a shelf with nothing in it — where a 404
        // would fail the whole Home screen.
        return new JSONArray();
    }

    // ---------------------------------------------------------------- anime

    /** AniList's catalogue, shaped as shelf rows. */
    private static JSONArray animeShelf(String search, String skip) throws Exception {
        Map<String, String> query = new HashMap<>();
        query.put("page", String.valueOf(pageFor(skip)));
        if (search != null && !search.trim().isEmpty()) query.put("q", search.trim());

        Anime.Result result = Anime.handle("/api/anime/search", query);
        if (result.status != 200) return new JSONArray();

        JSONArray items = new JSONObject(result.body).optJSONArray("items");
        JSONArray metas = new JSONArray();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject card = items.optJSONObject(i);
                if (card != null) metas.put(preview(animeMeta(card, null, null)));
            }
        }
        return metas;
    }

    /**
     * One anime card as a shelf row or a full page. `videos` is only present on
     * a page — the episode list is what turns a poster into something playable.
     */
    private static JSONObject animeMeta(JSONObject card, String description, JSONArray videos)
            throws Exception {
        JSONObject meta = new JSONObject()
                .put("id", "ha:an:" + card.opt("id"))
                .put("type", "series")
                .put("name", optText(card, "title"));
        putIfNotEmpty(meta, "poster", optText(card, "cover"));
        putIfNotEmpty(meta, "background", optText(card, "banner"));
        if (description != null && !description.isEmpty()) meta.put("description", description);
        putIfNotEmpty(meta, "releaseInfo", optText(card, "year"));
        String score = rating(card.opt("score"));
        if (score != null) meta.put("imdbRating", score);
        JSONArray genres = card.optJSONArray("genres");
        if (genres != null && genres.length() > 0) meta.put("genres", genres);
        if (videos != null) meta.put("videos", videos);
        return meta;
    }

    /** A score as the client wants it: a string out of ten, or nothing at all. */
    private static String rating(Object score) {
        double value;
        if (score instanceof Number) value = ((Number) score).doubleValue();
        else return null;
        if (value <= 0) return null;
        return String.format(Locale.ROOT, "%.1f", value > 10 ? value / 10 : value);
    }

    /** An anime page: AniList's details, then LunarX's episodes as video rows. */
    private static JSONObject animePage(Id ref) throws Exception {
        Anime.Result details =
                Anime.handle("/api/anime/" + seg(ref.key), new HashMap<>());
        if (details.status != 200) return null;
        JSONObject card = new JSONObject(details.body).optJSONObject("details");
        if (card == null) return null;

        JSONArray videos = new JSONArray();
        Anime.Result episodes =
                Anime.handle("/api/anime/" + seg(ref.key) + "/episodes", new HashMap<>());
        if (episodes.status == 200) {
            JSONArray list = new JSONObject(episodes.body).optJSONArray("data");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject episode = list.optJSONObject(i);
                    if (episode == null) continue;
                    int number = episode.optInt("number", 0);
                    if (number <= 0) continue;
                    String title = optText(episode, "title");
                    JSONObject video = new JSONObject()
                            .put("id", "ha:an:" + ref.key + ":1:" + number)
                            .put("title", title.isEmpty() ? "Episode " + number : title)
                            .put("season", 1)
                            .put("episode", number);
                    putIfNotEmpty(video, "thumbnail", optText(episode, "img"));
                    putIfNotEmpty(video, "released", optText(episode, "airDate"));
                    videos.put(video);
                }
            }
        }
        return animeMeta(card, optText(card, "description"), videos);
    }

    /**
     * What can play an anime id.
     *
     * LunarX hands out embeds, and an embed is a page, which the phone's player
     * cannot decode. So every one is opened *here* first — flixcloud by its own
     * resolver, anything else by the door resolver — and what comes back is a
     * loopback playlist link that plays in the app. An embed that will not open
     * is still listed, as `externalUrl`, because a page is better than nothing;
     * it is the exception, not the shape of the list.
     */
    private static JSONArray animeStreams(Id ref) throws Exception {
        JSONArray streams = new JSONArray();
        Map<String, String> query = new HashMap<>();
        query.put("ep", String.valueOf(ref.episode == null ? 1 : ref.episode));
        Anime.Result player =
                Anime.handle("/api/anime/" + seg(ref.key) + "/player", query);
        if (player.status != 200) return streams;

        JSONArray sources = new JSONObject(player.body).optJSONArray("sources");
        if (sources == null) return streams;

        long deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            String url = source == null ? "" : optText(source, "url");
            if (url.isEmpty()) continue;
            String label = optText(source, "label");
            String title = label.isEmpty() ? "Embed" : label;

            String relay = inAppUrl(url, deadline);
            if (relay != null) {
                streams.put(new JSONObject()
                        .put("name", "Anime")
                        .put("title", title)
                        .put("description", "In-app")
                        .put("url", relay));
            } else {
                streams.put(new JSONObject()
                        .put("name", "Anime")
                        .put("title", title)
                        .put("externalUrl", url));
            }
        }
        return streams;
    }

    /**
     * An embed page in, a loopback playlist link out — or null when it will not
     * open, in which case the caller falls back to the page itself.
     *
     * flixcloud is resolved by its own port of the provider (HTTP, then the wasm
     * mixer); every other embed is a player page, and the door resolver opens it
     * the way a browser would. Both answers are wrapped by {@link HlsRelay} with
     * the profile the CDN expects, so the player never needs a referer or a key
     * of its own.
     */
    private static String inAppUrl(String embedUrl, long deadline) {
        if (application == null || embedUrl == null || embedUrl.isEmpty()) return null;

        if (FlixCloud.isEmbed(embedUrl)) {
            FlixCloud.Result resolved = FlixCloud.resolve(application, embedUrl);
            if (!resolved.ok || resolved.url == null) return null;
            return HlsRelay.linkVia(resolved.url, resolved.referer,
                    Doors.originOf(embedUrl), resolved.key);
        }

        if (System.currentTimeMillis() > deadline) return null;
        Doors.Found found = Doors.resolve(application, embedUrl);
        if (found == null) return null;
        if (!HlsRelay.reachable(found.url, found.referer, Doors.originOf(embedUrl))) return null;
        return HlsRelay.linkVia(found.url, found.referer, Doors.originOf(embedUrl), null);
    }

    /** One path segment, percent-encoded so a colon in an id cannot split it. */
    static String seg(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    // ---------------------------------------------------------- films & series

    /**
     * One Fmovies listing page, kept to the kind the shelf is for: the page
     * carries both sorts of card, and a shelf that says Films must not hand back
     * a series — so the series shelf asks upstream for its own page with
     * `type=tv` and both are filtered here as well, because the listing's own
     * idea of a card can disagree with the shelf's.
     */
    private static JSONArray filmShelf(boolean series, String search, String skip) throws Exception {
        Map<String, String> query = new HashMap<>();
        query.put("page", String.valueOf(pageFor(skip)));
        // The shelf is one kind, so upstream is asked for that kind: a mixed
        // trending page draws both sorts of card and the shelf would have to
        // cut it down to half a page. The filter below is still applied — a
        // page can disagree with what was asked for.
        query.put("type", series ? "tv" : "movie");
        if (search != null && !search.trim().isEmpty()) query.put("q", search.trim());

        Fmovies.Result result = Fmovies.handle("/api/fmovies/search", query);
        if (result.status != 200) return new JSONArray();

        JSONArray items = new JSONObject(result.body).optJSONArray("items");
        JSONArray metas = new JSONArray();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                if ("tv".equals(item.optString("type", "")) != series) continue;
                metas.put(preview(filmMeta(item, series ? "ft" : "fm", null)));
            }
        }
        return metas;
    }

    /** One film or series card, as a shelf row or a full page. */
    private static JSONObject filmMeta(JSONObject details, String area, JSONArray videos)
            throws Exception {
        JSONObject meta = new JSONObject()
                .put("id", "ha:" + area + ":" + optText(details, "slug"))
                .put("type", area.equals("ft") ? "series" : "movie")
                .put("name", optText(details, "title"));
        putIfNotEmpty(meta, "poster", optText(details, "poster"));
        // The hero draws `background` full-bleed; a film card without one falls
        // back to its w500 poster, which is what looked pixelated there.
        putIfNotEmpty(meta, "background", optText(details, "backdrop"));
        putIfNotEmpty(meta, "description", optText(details, "description"));
        putIfNotEmpty(meta, "releaseInfo", optText(details, "year"));
        String score = rating(details.opt("score"));
        if (score != null) meta.put("imdbRating", score);
        JSONArray genres = details.optJSONArray("genres");
        if (genres != null && genres.length() > 0) meta.put("genres", genres);
        JSONArray cast = details.optJSONArray("cast");
        if (cast != null && cast.length() > 0) meta.put("cast", slice(cast, 8));
        // A film has no episodes; the client would read an absent list as
        // "nothing to pick", and an explicit empty one keeps the page from
        // guessing — the same choice the desktop server makes.
        meta.put("videos", videos == null ? new JSONArray() : videos);
        return meta;
    }

    /** A page for a film or a series, with the series' episodes as video rows. */
    private static JSONObject filmPage(Id ref) throws Exception {
        boolean series = ref.area.equals("ft");
        Fmovies.Result result = Fmovies.handle(
                "/api/fmovies/" + (series ? "tv" : "movie") + "/" + seg(ref.key), new HashMap<>());
        if (result.status != 200) return null;
        JSONObject details = new JSONObject(result.body).optJSONObject("details");
        if (details == null) return null;

        JSONArray videos = new JSONArray();
        if (series) {
            JSONArray seasons = details.optJSONArray("seasons");
            // Twenty seasons is a bound, not a rule: upstream's own page is what
            // names them, and a list longer than that is a parse gone wrong.
            int count = seasons == null ? 0 : Math.min(seasons.length(), 20);
            for (int i = 0; i < count; i++) {
                int season = seasons.optInt(i, 0);
                if (season <= 0) continue;
                Map<String, String> query = new HashMap<>();
                query.put("season", String.valueOf(season));
                Fmovies.Result episodes;
                try {
                    episodes = Fmovies.handle(
                            "/api/fmovies/tv/" + seg(ref.key) + "/episodes", query);
                } catch (Exception e) {
                    // One unreadable season does not empty the list.
                    continue;
                }
                if (episodes.status != 200) continue;
                JSONArray data = new JSONObject(episodes.body).optJSONArray("data");
                if (data == null) continue;
                for (int e = 0; e < data.length(); e++) {
                    JSONObject row = data.optJSONObject(e);
                    if (row == null) continue;
                    int number = row.optInt("n", 0);
                    if (number <= 0) number = row.optInt("episode", 0);
                    if (number <= 0) continue;
                    String title = optText(row, "title");
                    videos.put(new JSONObject()
                            .put("id", "ha:ft:" + ref.key + ":" + season + ":" + number)
                            .put("title", title.isEmpty() ? "Episode " + number : title)
                            .put("season", season)
                            .put("episode", number));
                }
            }
        }
        return filmMeta(details, ref.area, videos);
    }

    /**
     * A film's doors, opened here instead of in a browser.
     *
     * Each door hands out a *player page*, not media, and the URL a player can
     * decode only exists once that page has booted its own player. So the first
     * door that yields a playlist becomes a loopback link the app's player can
     * open, and the walk stops there — a second resolve is a second page load a
     * reader waiting for playback should not pay for. The door that answered is
     * remembered, and tried first for the next title.
     *
     * If no door yields one inside the budget, the doors are still listed as
     * `externalUrl`: a page that opens in the system browser is worse than a
     * stream, and much better than a title that claims to have no sources.
     */
    private static JSONArray filmStreams(Id ref) throws Exception {
        boolean series = ref.area.equals("ft");
        Map<String, String> query = new HashMap<>();
        query.put("season", String.valueOf(ref.season == null ? 1 : ref.season));
        query.put("episode", String.valueOf(ref.episode == null ? 1 : ref.episode));
        // No health knock: this path opens the doors itself, in order, and a
        // six-door probe would spend the budget before the first page loaded.
        query.put("probe", "0");

        Fmovies.Result result = Fmovies.handle(
                "/api/fmovies/" + (series ? "tv" : "movie") + "/" + seg(ref.key) + "/player",
                query);
        JSONArray streams = new JSONArray();
        if (result.status != 200) return streams;

        JSONArray sources = new JSONObject(result.body).optJSONArray("sources");
        if (sources == null || application == null) return streams;

        // The walk's order is the source's own rule (`Fmovies.inAppOrder`), the
        // same one the Node twin uses, with the door that last answered at the
        // front. A door is matched back to the source the route listed by id,
        // so the URL opened here is the one the picker shows.
        // The first door that named a playlist, kept in case none of them can
        // prove the playlist loads. A door that hands over a URL its own CDN
        // then refuses is a door that half-answered, and half an answer still
        // beats sending the reader to a browser — the player's own request may
        // well succeed where this probe did not.
        String stranded = null;
        String strandedDoor = null;

        long deadline = System.currentTimeMillis() + FILM_BUDGET_MS;
        for (Fmovies.Door door : Fmovies.inAppOrder(preferredDoor)) {
            JSONObject source = null;
            for (int i = 0; i < sources.length(); i++) {
                JSONObject candidate = sources.optJSONObject(i);
                if (candidate != null && door.id.equals(optText(candidate, "id"))) {
                    source = candidate;
                    break;
                }
            }
            if (source == null) continue;
            String url = optText(source, "url");
            if (url.isEmpty()) continue;
            if (System.currentTimeMillis() > deadline) break;

            Doors.Found found = Doors.resolve(application, url);
            if (found == null) continue;
            // A door that named a playlist that will not load is a door that
            // did not answer: the walk moves on instead of handing the player
            // a link that plays black.
            if (!HlsRelay.reachable(found.url, found.referer, Doors.originOf(url))) {
                Log.i(TAG, "door " + optText(source, "label")
                        + " gave a playlist that will not load — next door");
                // Not cached: the player's own *Refresh streams* asks again,
                // and a door that answers differently the second time deserves
                // the chance to. A fifteen-minute memory of a URL that 403s
                // would make that button a no-op.
                Doors.forget(url);
                if (stranded == null) {
                    stranded = HlsRelay.linkVia(found.url, found.referer, Doors.originOf(url), null);
                    strandedDoor = optText(source, "id");
                }
                continue;
            }

            String label = optText(source, "label");
            streams.put(new JSONObject()
                    .put("name", "Films")
                    .put("title", label.isEmpty() ? "In-app" : label)
                    .put("description", "In-app")
                    .put("url", HlsRelay.linkVia(found.url, found.referer,
                            Doors.originOf(url), null)));
            preferredDoor = optText(source, "id");
            break;
        }

        if (streams.length() == 0 && stranded != null) {
            streams.put(new JSONObject()
                    .put("name", "Films")
                    .put("title", "In-app")
                    .put("description", "In-app")
                    .put("url", stranded));
            preferredDoor = strandedDoor;
        }

        if (streams.length() == 0) {
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                String url = source == null ? "" : optText(source, "url");
                if (url.isEmpty()) continue;
                String label = optText(source, "label");
                streams.put(new JSONObject()
                        .put("name", "Films")
                        .put("title", label.isEmpty() ? "Embed" : label)
                        .put("externalUrl", url));
            }
        }
        return streams;
    }

    /** The first `count` entries of an array — a cast list is not a rail. */
    private static JSONArray slice(JSONArray source, int count) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(source.length(), count); i++) {
            out.put(source.opt(i));
        }
        return out;
    }

    private static String optText(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return "";
        return object.optString(key, "");
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) throws Exception {
        if (value != null && !value.isEmpty()) object.put(key, value);
    }

    private static JSONArray adultShelf(String id, String search, String skip) throws Exception {
        Map<String, List<String>> query = new HashMap<>();
        query.put("page", one(String.valueOf(pageFor(skip))));
        query.put("per_page", one(String.valueOf(PAGE_SIZE)));
        // `/api/videos` counts its pages from zero and the addon protocol from
        // one — the shelf's first page is page 0 there, which pageFor already
        // converted. The sort names are the catalogue's own (`released_at_unix`,
        // `views`), not the addon's.
        query.put("order_by", one(id.equals("adult-views") ? "views" : "released_at_unix"));
        query.put("ordering", one("desc"));
        if (search != null && !search.trim().isEmpty()) query.put("q", one(search.trim()));

        JSONObject body = new JSONObject(Catalog.videos(query));
        JSONArray rows = body.optJSONArray("data");
        JSONArray metas = new JSONArray();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) metas.put(preview(adultMeta(row)));
            }
        }
        return metas;
    }

    private static JSONObject meta(Id ref) throws Exception {
        if (ref.area.equals("ad")) {
            String body = Catalog.video(ref.key);
            if (body == null) return null;
            return adultMeta(new JSONObject(body));
        }
        if (ref.area.equals("an")) return animePage(ref);
        if (ref.area.equals("fm") || ref.area.equals("ft")) return filmPage(ref);
        return null;
    }

    /** What a play request turns into, per area. */
    private static JSONArray streams(Id ref) throws Exception {
        if (ref.area.equals("an")) return animeStreams(ref);
        if (ref.area.equals("fm") || ref.area.equals("ft")) return filmStreams(ref);
        if (ref.area.equals("ad")) return adultStreams(ref);
        return new JSONArray();
    }

    // ------------------------------------------------------------- 18+ shelf

    /**
     * The 18+ shelf's sources — the one path that needs a browser.
     *
     * Upstream accepts a request only when it carries a signature pair minted
     * by its own WASM module, plus a csrf token bound to a session cookie. The
     * pair is per-second, so it cannot be baked in: the module has to run, and
     * a WebView is the only place on Android that runs it unmodified. This is
     * therefore the one place the server touches the UI thread — to start the
     * signer, and to wait for it the first time.
     *
     * Each source comes back as a relay link (`HlsRelay.link`), so the player
     * fetches a playlist from loopback and every URI inside it is rewritten to
     * loopback too: the player never needs the referer upstream demands.
     */
    private static JSONArray adultStreams(Id ref) throws Exception {
        JSONArray streams = new JSONArray();
        if (application == null) return streams;

        Signer signer = Signer.ensure(application);
        // Five seconds is the page's own budget for the module to publish its
        // first signature; a cold start is the only request that pays it.
        if (!signer.awaitReady(6_000)) {
            Log.w(TAG, "signer not ready — 18+ sources unavailable this time");
            return streams;
        }
        Streams.setSigner(signer);

        JSONObject body = new JSONObject(Streams.resolve(ref.key));
        JSONArray sources = body.optJSONArray("sources");
        if (sources == null) return streams;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            String url = source == null ? "" : optText(source, "url");
            if (url.isEmpty()) continue;
            int height = source.optInt("height", 0);
            String size = height > 0 ? height + "p" : "";
            String label = optText(source, "label");
            if (label.equalsIgnoreCase(size)) label = "";
            String description = label.isEmpty() ? size : label + (size.isEmpty() ? "" : " · " + size);
            JSONObject stream = new JSONObject()
                    .put("name", "Hanime")
                    .put("url", url);
            if (!description.isEmpty()) stream.put("description", description);
            streams.put(stream);
        }
        return streams;
    }

    /** The APK's own assets, for the signer page and nothing else. */
    private static Resp asset(String name) throws Exception {
        if (application == null) return Resp.json(503, "{\"error\":\"no_context\"}");
        try (java.io.InputStream in = application.getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : "application/wasm";
            return new Resp(200, type, out.toByteArray());
        }
    }

    /**
     * One catalogue row as the client wants it. Posters and backgrounds are
     * rewritten to this server's own cover route: the CDN refuses a request
     * whose referer is not the site, and no player or image loader in this app
     * can be told to send one — so the bytes are fetched here, where the header
     * can be set, and handed over from loopback.
     */
    private static JSONObject adultMeta(JSONObject row) throws Exception {
        String slug = row.optString("slug", "");
        String poster = text(row, "poster");
        String cover = text(row, "cover");
        String description = text(row, "description");

        JSONObject meta = new JSONObject()
                .put("id", "ha:ad:" + slug)
                // The shelf's label is `hentai`; the entry itself is what the
                // player and the library read, and that is a film.
                .put("type", "movie")
                .put("name", row.optString("name", ""));
        // No artwork? Leave the key out rather than sending a null the client
        // has to guard against.
        String art = poster.isEmpty() ? cover : poster;
        if (!art.isEmpty()) meta.put("poster", coverUrl(art));
        if (!cover.isEmpty()) meta.put("background", coverUrl(cover));
        if (!description.isEmpty()) {
            meta.put("description", description.replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s{2,}", " ").trim());
        }
        String released = text(row, "released_at");
        if (!released.isEmpty()) meta.put("releaseInfo", released.length() >= 4 ? released.substring(0, 4) : released);
        JSONArray tags = row.optJSONArray("tags");
        if (tags != null && tags.length() > 0) meta.put("genres", tags);
        String brand = text(row, "brand");
        if (!brand.isEmpty()) meta.put("studio", brand);
        return meta;
    }

    /** The compact form a shelf row carries — a full meta would be wasted there. */
    private static JSONObject preview(JSONObject meta) throws Exception {
        JSONObject out = new JSONObject()
                .put("id", meta.optString("id", ""))
                .put("type", meta.optString("type", "movie"))
                .put("name", meta.optString("name", ""))
                .put("posterShape", "poster");
        for (String key : new String[] {"poster", "background", "releaseInfo", "imdbRating"}) {
            if (meta.has(key) && !meta.isNull(key)) out.put(key, meta.get(key));
        }
        return out;
    }

    private static String coverUrl(String target) throws Exception {
        return "http://127.0.0.1:" + PORT + "/covers?u=" + URLEncoder.encode(target, "UTF-8");
    }

    // --------------------------------------------------------------- the covers

    /** Cached bytes, keyed by upstream URL — a shelf is re-drawn on every visit. */
    private static final Map<String, Cover> COVERS = new HashMap<>();
    private static long coverBytes;

    private static final class Cover {
        final byte[] body;
        final String contentType;

        Cover(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }

    /**
     * `/covers?u=<upstream url>` — the image, with the referer the CDN insists on.
     */
    private static Resp cover(Request request) {
        String target = request.query.get("u");
        if (target == null || target.isEmpty()) return Resp.json(400, "{\"error\":\"missing_u\"}");
        if (!target.startsWith("https://") && !target.startsWith("http://")) {
            return Resp.json(400, "{\"error\":\"bad_url\"}");
        }

        Cover cached;
        synchronized (COVERS) {
            cached = COVERS.get(target);
        }
        if (cached == null) {
            try {
                cached = fetchCover(target);
            } catch (Exception e) {
                Log.w(TAG, "cover failed: " + target, e);
                return Resp.json(502, "{\"error\":\"upstream\"}");
            }
            synchronized (COVERS) {
                // A shelf is a few hundred kilobytes; the cap keeps a long
                // browsing session from growing the cache without end.
                if (coverBytes + cached.body.length <= COVER_CACHE_BYTES) {
                    COVERS.put(target, cached);
                    coverBytes += cached.body.length;
                }
            }
        }
        return new Resp(200, cached.contentType, cached.body);
    }

    private static Cover fetchCover(String target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("user-agent", BROWSER_UA);
        conn.setRequestProperty("referer", COVER_ORIGIN + "/");
        conn.setRequestProperty("accept", "image/*");
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new IllegalStateException("cover too large");
            }
        }
        String type = conn.getContentType();
        if (type == null || type.isEmpty()) type = "image/jpeg";
        return new Cover(out.toByteArray(), type);
    }

    // ------------------------------------------------------------------ helpers

    /** `skip=12` is the addon protocol's idea of a page: 24 to a page, one-based. */
    private static int pageFor(String skip) {
        int n = intOf(skip, 0);
        return n > 0 ? n / PAGE_SIZE + 1 : 1;
    }

    /** Search and skip arrive in the path segment when a client uses one. */
    private static Map<String, String> catalogExtra(String extraPart, Request request) {
        Map<String, String> extra = new HashMap<>();
        extra.put("search", request.query.getOrDefault("search", ""));
        extra.put("skip", request.query.getOrDefault("skip", "0"));
        if (extraPart == null || extraPart.isEmpty()) return extra;
        String decoded = decode(extraPart.replaceAll("\\.json$", ""));
        for (String pair : decoded.split("&")) {
            int at = pair.indexOf('=');
            if (at < 0) continue;
            String key = pair.substring(0, at);
            String value = pair.substring(at + 1);
            if (key.equals("search")) extra.put("search", value);
            if (key.equals("skip")) extra.put("skip", value);
        }
        return extra;
    }

    private static String stripJson(String value) {
        String decoded = decode(value);
        return decoded.endsWith(".json") ? decoded.substring(0, decoded.length() - 5) : decoded;
    }

    private static String text(JSONObject row, String key) {
        if (!row.has(key) || row.isNull(key)) return "";
        return row.optString(key, "");
    }

    private static List<String> one(String value) {
        List<String> list = new ArrayList<>(1);
        list.add(value);
        return list;
    }

    private static int intOf(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** A JSON string literal, so a path or an error message cannot break the body. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** One of our ids, split back into the area it names and the key inside it. */
    static final class Id {
        final String area;
        final String key;
        final Integer season;
        final Integer episode;

        Id(String area, String key, Integer season, Integer episode) {
            this.area = area;
            this.key = key;
            this.season = season;
            this.episode = episode;
        }

        static Id parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("mal:")) value = "ha:ml:" + value.substring(4);
            String[] parts = value.split(":");
            if (parts.length < 3 || !parts[0].equals("ha")) return null;
            String area = parts[1];
            String[] rest = new String[parts.length - 2];
            System.arraycopy(parts, 2, rest, 0, rest.length);
            for (String part : rest) {
                if (part.isEmpty()) return null;
            }
            boolean series = area.equals("an") || area.equals("ft") || area.equals("ml");
            if (series && rest.length >= 3) {
                Integer season = intOrNull(rest[rest.length - 2]);
                Integer episode = intOrNull(rest[rest.length - 1]);
                if (season != null && episode != null) {
                    StringBuilder key = new StringBuilder();
                    for (int i = 0; i < rest.length - 2; i++) {
                        if (i > 0) key.append(':');
                        key.append(rest[i]);
                    }
                    return new Id(area, key.toString(), season, episode);
                }
            }
            if (series || area.equals("fm") || area.equals("ad")) {
                return new Id(area, String.join(":", rest), null, null);
            }
            return null;
        }

        private static Integer intOrNull(String value) {
            try {
                return Integer.valueOf(value.trim());
            } catch (Exception e) {
                return null;
            }
        }
    }

    // -------------------------------------------------------------- http plumbing

    /** A parsed request line and its headers. */
    static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final Map<String, String> query;
        /**
         * A request body, when there was one.
         *
         * Every route here but one is read from the head alone: an addon call
         * and a manga listing are GETs with their arguments in the query. The
         * exception is AniList's GraphQL endpoint, which is a POST of a JSON
         * document — so the reader takes the declared length and stops there,
         * and a route that does not look at {@code body} is unaffected.
         */
        final String body;

        Request(String method, String path, Map<String, String> headers, Map<String, String> query) {
            this(method, path, headers, query, "");
        }

        Request(String method, String path, Map<String, String> headers, Map<String, String> query,
                String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.query = query;
            this.body = body == null ? "" : body;
        }

        static Request read(InputStream in) throws Exception {
            // Read the head a byte at a time: a reader that stopped at a
            // declared body length would still have to be told where the body
            // starts. The last four bytes are kept in a window so the
            // terminator is recognised without rescanning what has been read.
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int window = 0;
            int b;
            while ((b = in.read()) >= 0) {
                head.write(b);
                if (head.size() > 16 * 1024) throw new IllegalStateException("request head too long");
                window = (window << 8) | (b & 0xff);
                if (window == 0x0d0a0d0a) break;            // CRLF CRLF
                if ((window & 0xffff) == 0x0a0a) break;     // a bare LF LF
            }
            String text = new String(head.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n");
            if (lines.length == 0 || lines[0].isEmpty()) return null;

            String[] start = lines[0].split(" ");
            if (start.length < 2) return null;
            String method = start[0];
            String target = start[1];

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int at = lines[i].indexOf(':');
                if (at <= 0) continue;
                headers.put(lines[i].substring(0, at).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(at + 1).trim());
            }

            String path = target;
            Map<String, String> query = new HashMap<>();
            int mark = target.indexOf('?');
            if (mark >= 0) {
                path = target.substring(0, mark);
                for (String pair : target.substring(mark + 1).split("&")) {
                    int at = pair.indexOf('=');
                    String key = decode(at < 0 ? pair : pair.substring(0, at));
                    String value = at < 0 ? "" : decode(pair.substring(at + 1));
                    if (!key.isEmpty()) query.put(key, value);
                }
            }
            String body = readBody(in, headers);
            return new Request(method, path, headers, query, body);
        }

        /**
         * The request body, when the head declared one.
         *
         * Only a GraphQL POST has a body, and it is a document rather than a
         * file: 256 KB is far past anything this app writes and small enough
         * that a client cannot make the server hold a large allocation. A
         * chunked body with no declared length is not read — no caller here
         * sends one, and guessing at a terminator is how a server hangs.
         */
        private static String readBody(InputStream in, Map<String, String> headers) throws Exception {
            int length;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0").trim());
            } catch (NumberFormatException notANumber) {
                return "";
            }
            if (length <= 0) return "";
            if (length > 256 * 1024) throw new IllegalStateException("request body too large");
            byte[] buffer = new byte[length];
            int read = 0;
            while (read < length) {
                int count = in.read(buffer, read, length - read);
                if (count < 0) break;
                read += count;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** A response, small enough that it can always be built in memory first. */
    static final class Resp {
        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers = new HashMap<>();

        Resp(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Resp json(int status, String body) {
            return new Resp(status, "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
        }

        /** The browser-visible page a finished MAL login lands on. */
        static Resp html(int status, String body) {
            return new Resp(status, "text/html; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
        }

        void write(OutputStream out) throws Exception {
            BufferedOutputStream buffered = new BufferedOutputStream(out, 32 * 1024);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(status).append(' ').append(reason()).append("\r\n");
            head.append("content-type: ").append(contentType).append("\r\n");
            head.append("content-length: ").append(body.length).append("\r\n");
            head.append("cache-control: ").append(status == 200 ? "max-age=60" : "no-store").append("\r\n");
            // A Stremio client and a player both fetch from other origins, and
            // the player is a native one that does not care — but the addon
            // list's own web views do, and this costs nothing.
            head.append("access-control-allow-origin: *\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            head.append("connection: close\r\n\r\n");
            buffered.write(head.toString().getBytes(StandardCharsets.UTF_8));
            buffered.write(body);
            buffered.flush();
        }

        private String reason() {
            switch (status) {
                case 200: return "OK";
                case 400: return "Bad Request";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 502: return "Bad Gateway";
                default: return "Error";
            }
        }
    }
}
