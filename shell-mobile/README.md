# The phone shell — a NuvioMobile fork, pointed at this catalogue

This directory holds the phone app: **NuvioMobile's own UI and player (Kotlin +
Compose), rewired to serve this project's library.** What you see is Nuvio's
app — the hero carousel, the rails, the detail pages, the stream picker, the
player, the bottom navigation. What it *shows* is this repository's catalogue,
because the app now carries that catalogue inside itself.

```bash
./shell-mobile/sync.sh            # fetch upstream at the pinned commit, apply overrides
./shell-mobile/install-mobile.sh  # apply overrides, build, adb install on the phone
```

```
shell-mobile/
  overrides/            our changes, laid over the upstream tree at their real paths
  sync.sh               fetch upstream at the pinned commit, apply overrides, print how to run
  install-mobile.sh     rebuild the app and put it on the attached phone
  NOTICE.md             what upstream is, which commit, what we changed and why
  LICENSE-NuvioMobile-GPL-3.0   upstream's license — read it before shipping anything
```

## Run it

Requirements: **JDK 17** (`JAVA_HOME`), the **Android SDK** (`ANDROID_HOME`, or
the `sdk.dir` this repo's `android/local.properties` already records), and
**adb** (on `PATH`, or in `$ANDROID_HOME/platform-tools`). A first fetch of
upstream is a few hundred MB, and the first build of it took **10m 23s** on this
machine; later builds are incremental.

```bash
./shell-mobile/install-mobile.sh                # build + install (debug, com.nuviodebug.com)
./shell-mobile/install-mobile.sh --fast         # minified build, same package — the one for a slow phone
./shell-mobile/install-mobile.sh --no-build     # install the last build as it stands
./shell-mobile/install-mobile.sh --device 52070e9f
```

The default build is the **debug** variant: same code, `com.nuviodebug.com` as
its application id, signed with the debug keystore — so it installs beside the
app you already have instead of replacing it. On a weak phone, though, debug is
what lag feels like: no R8, no shrinking, debug hooks on. `--fast` builds the
`fast` type — the release pipeline (minified, shrunk, non-debuggable) signed
with the **debug key** and installed under the same `com.nuviodebug.com`, so it
replaces the debug build in place, keeps every preference and changes nothing
visible. Switching back is the same command without `--fast`.

The **published** build is a third variant, `fullRelease`: `app.hanime.shell`,
signed with this repository's own release key. Both of those are deliberate.
`app.hanime.shell` is the WebView shell's package id and the key is the one its
1.0.x releases were signed with, so the app published from here is not a second
SEKAI that installs beside the old one — it is the update the old one's updater
already looks for. An installed shell is replaced in place by 2.0.0, and every
release after that updates the same install. The version comes from
[`overrides/iosApp/Configuration/Version.xcconfig`](overrides/iosApp/Configuration/Version.xcconfig)
(2.0.0 / 200), which the Android build reads at configuration time.

Its in-app updater asks **this** repository's releases rather than upstream's
(`AppUpdaterRepository` is overridden for exactly that): upstream's APK cannot
be installed over this one, and before 2.0.0 its package id was upstream's too,
so the wrong source would have offered an update that could only fail.

## Shipping it

The release key is `android/keystore/release.jks` (alias `shell`), read from
`android/keystore.properties` — which is gitignored, so the passwords never
enter a tracked file. Gradle takes them from the environment (`NUVIO_RELEASE_*`)
and the APK's own digest, package and signer are read back afterwards rather
than trusted:

```bash
ks=android/keystore.properties
export NUVIO_RELEASE_STORE_FILE="$PWD/android/$(sed -n 's/^storeFile=//p' "$ks" | tr -d '\r')"
export NUVIO_RELEASE_STORE_PASSWORD="$(sed -n 's/^storePassword=//p' "$ks" | tr -d '\r')"
export NUVIO_RELEASE_KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$ks" | tr -d '\r')"
export NUVIO_RELEASE_KEY_PASSWORD="$(sed -n 's/^keyPassword=//p' "$ks" | tr -d '\r')"
(cd shell-mobile/.upstream && "$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :androidApp:assembleFullRelease --no-daemon --console=plain)
```

The ABI splits are written beside one `app-universal-release.apk`; the
universal file is the one published, because a release is one download that
installs on every phone (the updater prefers an ABI-named asset when a future
release carries several, and falls back to the single APK).

Publishing is the repository's usual two steps — the local manifest, then the
GitHub release that phones actually update from:

```bash
cd server
node scripts/publish-apk.mjs ../shell-mobile/.upstream/androidApp/build/outputs/apk/full/release/app-universal-release.apk --notes "..."
node --env-file=../.env scripts/publish-github.mjs --repo Comitz134/hanime-app --notes "..."
```

`version.json`'s `version_code` is read from the APK with `aapt2`, and the
release is tagged `v<versionName>` (so 2.0.0 is `v2.0.0`) and published
**non-prerelease**: `/releases/latest` is what both the old shell's updater and
this app's stable channel read, and it has to resolve to this app. Desktop
releases stay prereleases for that reason.

What goes out is GPL-3.0 (see [NOTICE.md](NOTICE.md)). A distributed build owes
its recipients the corresponding source, which is this repository at the commit
the APK was built from — name that commit in the release notes.

### On a Xiaomi phone (MIUI/HyperOS): "Install via USB"

MIUI refuses every `adb install` — `INSTALL_FAILED_USER_RESTRICTED: Install
canceled by user` — until **Developer options → "Install via USB"** is on. It
also refuses `adb shell input` (taps, keys) until **"USB debugging (Security
settings)"** is on, which is why the script cannot flip either for you: the
switch that would let it tap is the switch that is off. The script names this
failure and exits non-zero rather than reporting a success it did not get.

## What the app does now

Everything below was checked on the device, not inferred from a build:

*   **It signs in to this project's account.** The same email and password as
    the desktop window and the phone's web client. The library, watch positions
    and history are the account's, so a title left half-watched on the desktop
    is on the phone's Home rail with its progress — and vice versa.
*   **Five shelves, from this catalogue**, all under an addon manifest the app
    serves itself: *Anime — trending now* (AniList + LunarX), *Films — popular
    now* and *Series — popular now* (cinehd's catalogue: TMDB search, trending
    and details, the same six doors the desktop window frames), and the two 18+
    shelves from this project's own catalogue reader. Ids are the account's own
    (`ha:an:`, `ha:fm:`, `ha:ft:`, `ha:ad:`), which is what makes a title the
    same title on every screen.
*   **Detail pages and streams** for anime, films and series: an anime page
    answers with its episode list (`World Trigger`, 73 episodes), a series page
    with its seasons, and a stream request with **something the app's own player
    can decode** — every door and embed is opened inside the app first (see
    *In-app playback* below), so tapping a film, a series episode, an anime
    episode or a 18+ title never leaves the app for a browser.
*   **Covers render**, including the ones the CDN refuses: posters and
    backgrounds are rewritten to the app's own `/covers` route, which fetches
    them with the referer the CDN insists on. A player or image loader cannot be
    told to send one; the server can.
*   **The hero carousel is sharp.** Films and series carry their TMDB backdrop
    in the catalogue card, and hero URLs are asked for at full size — TMDB's
    `original`, AniList's largest cover — instead of the `w500`/`medium` card
    sizes. AniList's `bannerImage` is a 1900x400 strip: cropped to the hero's
    tall band it is only 400 pixels of height doing the work of a thousand, so
    anime uses the portrait cover there and the carousel stops blurring.
*   **The 18+ shelf plays.** A poster in *Hentai — newest* resolves to the real
    media playlists (720p, 480p, 360p — 1080p is a promotion stub with no src)
    and plays in the app's own player. Those streams sit behind hanime.tv's
    per-second signature, so the fork carries the phone shell's signer WebView,
    handshake token and HLS relay — see *The 18+ shelf* below.
*   **A Manga tab.** A fifth tab of this fork's own making, because the addon
    protocol can describe a video and nothing about a page of panels. Trending
    and search with their covers, a title's chapters, a reader that runs on into
    the next chapter seamlessly — and positions, so a title reopens where it was
    left. Kept titles live in the Library tab's Manga row. See *The manga tab*
    below.

### In-app playback, for every shelf

A stream picker that hands the player a *page* is a stream picker that opens a
browser. Everything this app lists as a source is a URL ExoPlayer can decode:

*   **Films and series** go through cinehd's six doors (Vidpro/vixsrc, Max, V2,
    Premium, 4K, Vidfast). Each is a player page, so `Doors` loads it in a
    WebView that is never shown, watches the playlist the page itself fetches
    (interception, plus a JS probe for the players that assemble the URL in the
    page), and hands back the first `.m3u8`. The walk stops at the first door
    that answers and remembers it, so the next title starts there.
*   **Anime** is LunarX's embeds. flixcloud is resolved by `FlixCloud` + `FlixMixer`
    (their obfuscated payload needs a wasm module, which is why a WebView runs it),
    and every other embed goes through the same door resolver as a film.
*   **The 18+ shelf** already did this through `Signer` + `Streams`.

All three end in `HlsRelay`, which serves the playlist and every URI inside it
from loopback with the referer, origin and (for flixcloud) the playlist key the
CDN expects — rewritten into a signed link, so the player needs no headers of
its own. vixsrc's base64 playlists and flixcloud's XOR-wrapped ones are undone
there too.

Two details are worth knowing because they are what makes the walk honest:

*   **Vidpro is resolved without a browser.** vixsrc's page does two plain
    requests — `GET /api/movie/<id>` (or `/api/tv/<id>/<s>/<e>`), then the embed
    page it names, whose markup carries `window.masterPlaylist.url` — so that
    door is answered by `Vixsrc` in about half a second instead of a nine-second
    page load. Measured on the phone: **0.5 s** to a playlist link, against 2.3 s
    for Max and 9 s for a door that has to be opened.
*   **A playlist is checked before it is offered.** A door can name a perfectly
    well-formed URL and have its own CDN refuse it — vixsrc's playlist endpoint
    answers `403` to some networks, and ythd sometimes hands out an unsigned URL
    before the tokenised one. The walk asks the URL for itself, and moves to the
    next door when it will not load; an answer that fails is *not* cached, so
    the player's own **Refresh streams** really does re-open the door. Measured
    here: a film that answered badly on the first ask resolved to a working
    playlist in **1.8 s** on the second.

A door that yields nothing is still listed, as `externalUrl` — a browser is
worse than a stream and better than a title claiming it has no sources — but it
is the fallback, not the shape of the list. When every door names a URL that
refuses to load, the first of those is still offered as an in-app stream: the
player's request may succeed where the probe did not, and a black screen the
reader can refresh beats a browser they cannot play in.

### Identity

The app is **SEKAI**, not Nuvio. The launcher label (`SEKAI`, and `SEKAI Debug`
for the debug variant beside it), the launcher icon with its monochrome variant
and the five themed ones upstream's picker offers, the Android 12+ splash mark
and the in-app wordmark are this repository's own — derived from art it already
ships by `tools/make-app-icons.py`, the same way the desktop fork derives its
own. Upstream's files are replaced file for file, at their own paths, and nothing
of theirs is deleted.

## How the catalogue gets into the app

The desktop window starts this repository's Node server as a child process. A
phone cannot, so the fork does the same job **in process**: an HTTP server on
`127.0.0.1:8787` that answers the addon protocol Nuvio's UI already speaks, out
of the Java sources in `overrides/androidApp/src/main/java/`.

Nothing is exposed to the network — the socket is bound to loopback, so the
phone shows no catalogue to the LAN and needs no permission it did not already
have. The address is not a choice this app makes: the account's addon list is
shared with the desktop window and names `http://127.0.0.1:8787/addon/manifest.json`,
so the fork is what has to answer it.

```
GET /addon/manifest.json                      the shelves and the types they carry
GET /addon/catalog/<type>/<id>.json           one shelf (+ ?skip= / ?search=)
GET /addon/meta/<type>/<id>.json              one title, with its episode rows
GET /addon/stream/<type>/<id>.json            what can play it
GET /addon/subtitles/<type>/<id>.json         subtitle tracks (anime: the LunarX
                                              embed's own list; films/series: OpenSubtitles
                                              by IMDb; the 18+ shelf: none, and that is
                                              the answer, not an error)
GET /covers?u=<upstream url>                  artwork, fetched with the right referer
GET /manga/trending.json                      the manga shelf (their own, unsigned)
GET /manga/search.json?q=&page=&type=&status=&genre=
                                              search, or browse latest-first with no q
GET /manga/filters.json                       the types, statuses and genres to browse by
GET /manga/title.json?hid=<id>                one title, signed
GET /manga/chapters.json?hid=<id>             its chapters, newest first
GET /manga/pages.json?chapter=<id>            a chapter's pages, rewritten to /manga/page
GET /manga/pos.json                           where each title was left
GET /manga/pos.json?hid=…&chapter=…&page=…    the same route writes one
GET /manga/library.json                        the shelf of titles kept in the library
GET /manga/library.json?hid=…&title=…&poster=… the same route adds one
GET /manga/library.json?hid=…&remove=1         the same route takes it off again
GET /manga/page?u=<upstream url>              one reader page, with the referer their CDN wants
GET /api/anime/search / <id> / seasons / episodes / player
                                              the JVM twin of the Node server's anime routes
GET /api/fmovies/search / movie/<slug> / tv/<slug> / episodes / player
                                              the JVM twin of the Node server's cinehd routes
GET /relay?u=&r=&o=&k=&s=                     signed HLS relay (doors, flixcloud, hanime.tv)
GET /manga/mal/callback?state=…               the MyAnimeList code the browser dropped (once)
POST /api/mal/token?…                         the MAL PKCE exchange (the secret stays in the build)
*    /api/mal/v2/<…>                          MAL's API, with the caller's own Bearer token
GET /?code=…&state=…                          where MAL's redirect lands in the browser
GET /health                                   is it up
```

Run against a phone that has the app installed, from this machine:

```bash
adb forward tcp:8787 tcp:8787
curl -s http://127.0.0.1:8787/addon/manifest.json
curl -s "http://127.0.0.1:8787/addon/catalog/series/anime-trending.json" | head -c 300
```

## The 18+ shelf, and the one thing that has to be exactly right

Everything else in this app reads an API that answers to anyone. hanime.tv's
stream endpoints do not: they want a signature pair minted by the site's own wasm
module, a csrf token from `ct.hanime.tv`, and the proof cookie that binds the two.
Three pieces of the phone shell are carried over for that — `Signer` (a WebView
that runs the module), `Token` (the handshake envelope) and `HlsRelay` (which
rewrites every playlist URI to loopback, because the upstream playlist host
refuses a player's requests outright).

The part worth knowing: **the module binds its signature to
`window.top.location.origin`,** and upstream checks that against the `Origin`
header on the request. A page served from anywhere else produces a signature
that looks perfect and is signed for the wrong origin, and everything it signs
comes back `401 {"error_id":"UA1"}` — a name that reads like a user-agent
refusal and is not one. Nothing in the module reads `navigator`.

So the signer's WebView loads `https://hanime.tv/signer.html`, and
`shouldInterceptRequest` answers that request — plus the module's `.js` and
`.wasm` — from this APK. The bytes are ours, the origin is the site's, and no
request leaves the device. The same three files stay reachable on loopback at
`/signer.html`, purely so `adb forward` can tell you whether the module boots on
a given device.

To check the whole chain from this machine:

```bash
adb forward tcp:8787 tcp:8787
curl -s "http://127.0.0.1:8787/addon/stream/ad/ha:ad:furachi-1.json"
# -> {"streams":[{..."url":"http://127.0.0.1:8787/relay?u=...","description":"720p"}, 480p, 360p]}
curl -s "<that url>" | head -5      # #EXTM3U, and every URI in it on 127.0.0.1
```

`logcat -s ShellSigner ShellStreams` shows the signer booting and each
handshake; a refusal prints its upstream body
(`{"error_id":"UA1","message":"Unauthorized"}` and friends) rather than just a
status code.

## The manga tab

Nuvio has four tabs; this fork has five. The fifth is not an addon — the addon
protocol can describe a video and nothing about a page of panels — so it is a
port of the web client's manga area into the app
(`overrides/composeApp/…/features/manga/`), reading the same in-process server's
`/manga/…` routes that the rest of the app reads its catalogue from.

What it does, all of it checked on the device: trending and search with their
covers, browsing filtered by type, status and genre, a title's chapters (254 of
them for the first title tried), a reader that scrolls one chapter as one strip —
and carries on into the next chapter without a tap: within a few pages of the
end of a chapter the chapter below is fetched and appended to the same list, the
chapter label follows the page being read, and the position and the tracker both
follow *the visible page's* chapter rather than the one the strip started on.

A title is kept on a shelf — the Library tab's **Manga** view, not a receipt of
what was read lately. The Library's pills name its two shelves, **Series** and
**Manga**, so the manga shelf is a view of its own rather than a row under the
series (Cloud keeps a pill only once a cloud library is configured, Servers
only while one is linked). The title page's *Add to library* button puts a
title there (and says *In your library — remove* once it is), a MyAnimeList
import puts every row of the account's list there, and reopening a title lands
in the chapter and on the page left off, from the shelf or from *Continue
Ch. 167*.

The shelf is organized the way the series beside it are: a **Sort** chip
(*Recently added*, *Title A–Z*, *Progress*) and a **Show** chip (*All*,
*Reading*, *Not started*) drawn with the library's own dropdown, so a shelf of
four hundred imported rows can be narrowed to the ones being read without
scrolling it. The choice is kept in saved state and the count line says what
is being shown (`12 of 165`). The header's grid/row button switches it too —
row mode is the horizontal shelf, grid mode the series grid's poster rows,
chunked by the list so a long shelf builds only what is on screen.

An import keeps the account's order: the list is read `sort=list_updated_at`
(last read first) and each row is stamped a millisecond older than the one
above it, so *Recently added* on a freshly imported shelf lists the titles in
the order they were last read on MyAnimeList rather than in the order their
stamps collided.

A search or a filter pull **every page** the catalogue has for it, not the first
one and not the next page when the grid reaches its end: each page is appended
as it lands, so results fill in instead of arriving in twenty-four-title
increments under a thumb. (Bounded at twenty pages, which is several hundred
titles — a guard against an upstream that would page forever, not a limit anyone
reaches.)

A load that is replaced — the next keystroke, another chip, walking into a
title — is a coroutine *cancellation*, and Compose's cancellation says "the
coroutine scope left the composition". The first version's `catch (Exception)`
showed that as the search's error. A cancellation is rethrown now and the error
line is only for a real refusal, which is why search survives fast typing.

### The manga routes have a test harness

`androidApp/src/test/java/app/hanime/shell/MangaRouteTest.java` drives the
manga routes on a plain JVM — no phone, no WebView:

```bash
cd shell-mobile/.upstream
./gradlew :androidApp:testFullDebugUnitTest --tests app.hanime.shell.MangaRouteTest
```

Nine tests cover the contract the screen depends on: a search asks upstream for
the page it was given, an unnamed page is the first and an unnamed size is the
app's page, a page number cannot be read as a page size or the other way round, a
page size that is really an order is bounded, a filter the route did not name
does not travel upstream, a search with no keyword is the latest-chapter
catalogue, filters answer upstream's vocabulary untouched, `hasNext` reads the
last page the answer names, and trending asks the top-titles endpoint for the
limit it was given. Upstream is a seam (`Manga.transport`, replaced by a double
that records the URL it was handed), so the tests never dial mangafire.

The shelf is drawn from the app's own furniture, not from new widgets: the same
header and search field as the Search tab, its dropdown chips as the filters
(so types, statuses and the ~50 genres come from `/manga/filters.json` rather
than from a list written here), and `PosterGridRow` for the grid. That is not
only a style choice — see the first entry under *What the manga tab taught* in
[NOTICE.md](NOTICE.md): a tab that draws itself gets no content colour, and the
first version of this screen showed titles and chapter numbers in black on
black.

### MyAnimeList, for the manga side

MyAnimeList is a card in **Settings → Tracking**, beside Trakt, Simkl and
MDBList and drawn by the same `TrackingProviderCard`: same gradient, same
expander, same connect / awaiting-approval / connected / disconnect cycle. The
account is linked and the reading list imported from there — *Import my reading
list* on the connected card, with its progress and a one-line summary — rather
than from a text row of its own. A title's page still has a MAL row for *this*
title's status, and both go through the same `MangaMal` session, so there is
one account and not two.

*Connect MyAnimeList* opens the system browser at MAL's consent page — the
state is minted by the app, and MAL's redirect URI is this server's own root
(`http://localhost:8787/`, registered on the project's MAL application), so the
browser's redirect lands back on the phone's in-process server. The app polls
for the code, exchanges it through `/api/mal/token` (where the client secret is
added, never in the UI's request), and keeps the session in the app's private
preferences.

The relay resolves MAL's hosts through **DNS-over-HTTPS** when the phone's own
resolver refuses them. That is the bug a real `502` turned out to be: on the
network this was built against, `myanimelist.net` came back *no address
associated with hostname* to the app while the browser opened the consent page
(Chrome resolves over its own DoH) and `ping` still answered. OkHttp is the one
client here that can be handed a resolver, so MAL's names are looked up through
Cloudflare (by address, so the DoH host cannot itself be filtered) and Google,
the answer is cached for ten minutes, and every other host keeps the system
resolver. Once the name resolved, the live exchange answered as it should.

The token exchange is retried when MAL's own edge answers `502`/`503`/`504`:
the code is not spent by a gateway error, so the same form is asked up to twice
more before the reader is told *MyAnimeList's sign-in service had a gateway
error. Try Connect again in a moment.* — the sentence a real `502` during a
consent produced, instead of a bare status code. Network drops (a DNS miss on a
phone that just switched networks) are retried three times on their own; MAL's
own `400`/`401` is never retried.

Once linked, the row shows the account's own status and progress on that title.
Opening a chapter writes *chapters read* — never backwards, status kept unless
it was *plan to read*, and *completed* when the last chapter is reached — the
same rules the web client writes by. A title is matched to MAL by search on
first use and remembered; a miss stays a miss rather than attaching the title
to the wrong entry. The exchange is real (MAL refuses a bogus code with
`invalid_request`, not `invalid_client`), but completing a login needs an actual
MAL account, which is the one step that could not be exercised here.

### The signer, again — and which calls need it

mangafire.to's API answers anyone, but its listing calls carry a `vrf` token
minted from the path and the query string. The token comes from the site's own
obfuscated bundle, which decodes itself against `navigator.appCodeName` and does
not run outside a browser — so the fork does what it already does for hanime.tv:
`MangaSigner` keeps a WebView that runs the bundle, and Java asks it to sign each
request. The bytes are ours (the APK's assets), the origin is theirs
(`https://mangafire.to/mf-signer.html`, answered by `shouldInterceptRequest`), and
no request leaves the device from that page.

Two things about it are worth knowing, because both cost a build to learn:

*   **Not every path is signed.** Their bundle carries its own guards — a
    trusted-environment test and the list of paths it protects — and publishes
    both while it boots, so the page asks them directly and logs the answer. Of the
    paths this app uses, `/titles/…` is protected and `/top-titles` and
    `/filter-options` are not: a shelf answering an unsigned request is not a
    sign that signing is broken, and a 403 on a title is.
*   **An empty query string is an answer.** A title asked for by id has no
    parameters, so what comes back is a token and nothing to serialise. The first
    version read "no query" as "no answer", sent the request unsigned, and
    upstream's `403` looked like the bundle's fault until the request line was
    logged. It lives in the caller, not in the bundle: the signer having answered
    is what matters.

A WebView that fails to boot fails silently, so the page keeps its own account of
what it did (`window.__mfDiag`, and its console relayed into logcat), and every
upstream call is logged signed or unsigned:

```bash
adb forward tcp:8787 tcp:8787
adb logcat -s ShellManga
# D ShellManga: signed GET https://mangafire.to/api/titles/ro8ro?vrf=8sK3xtqd…
curl -s "http://127.0.0.1:8787/manga/title.json?hid=ro8ro" | head -c 200
curl -s "http://127.0.0.1:8787/manga/chapters.json?hid=ro8ro" | head -c 200
```

### What stays on the app's side

Reader pages cannot be handed to the UI as their own URLs — their image CDN
refuses a request whose referer is not the site — so the bytes are fetched by the
server, where the header can be set, and served from loopback as
`/manga/page`. The same rule as `/covers`, and the same limit: only what must be
proxied is.

Positions are written by the reader as pages turn, debounced, because a fling
through forty pages is one place and not forty writes. The route that writes one
is a GET with its parameters rather than a POST: this server reads a request's
head and no body — the shape the addon protocol gave it — and a page number is
not worth teaching it to read one.

## Overrides are laid over a pin, and reset first

`install-mobile.sh` resets the tree's tracked files to the pin *before* copying
`overrides/` in, because overrides are files laid over it: a file deleted from
`overrides/` would otherwise stay in the tree and keep being compiled. Untracked
things — the APK, `local.properties`, `build/` — are left alone, so the reset
costs seconds and never a rebuild.

A build of this tree needs a `local.properties` beside `settings.gradle.kts` (the
runtime-config task declares it as an input, and the Android plugin needs
`sdk.dir`). `sync.sh` writes it; **forward slashes on purpose**, because a
`.properties` file treats a backslash as an escape.
