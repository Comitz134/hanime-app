# NOTICE — attribution for the phone shell

The app in this directory is a fork of an existing GPL-3.0 project. This file is
the attribution required by that license. Keep it with any distribution of the
phone build.

## Upstream

| | |
| --- | --- |
| Project | **NuvioMobile** — <https://github.com/NuvioMedia/NuvioMobile> |
| Owner | NuvioMedia and its contributors |
| License | GNU General Public License v3.0 (`LICENSE-NuvioMobile-GPL-3.0`, kept verbatim) |
| Branch | `cmp-rewrite` |
| Pinned commit | `229c083cf3db7bab6fda284484f87439eb4e0eb0` (2026-10-16, "publish 0.6.1-beta") |

## What this fork changes

The files under `overrides/`, copied at their upstream paths by `sync.sh`.
Everything else in the running app is upstream's, unmodified — including the
whole of the UI (Compose, the player, the addon plumbing), which is the point of
forking rather than reimplementing it. Each file below differs from the pin by
the addition named; nothing upstream is deleted:

| file | why |
| --- | --- |
| `composeApp/build.gradle.kts` | the two runtime-config defaults that name this project's account backend. Upstream's build ships **empty** ones, so an unconfigured build reaches a sign-in screen that cannot work; `local.properties` and the environment still win over these |
| `androidApp/build.gradle.kts` | the `fast` build type — the release pipeline (minified, shrunk, non-debuggable) signed with the debug key under the debug package, so it installs in place of the debug build and keeps every preference; the **published** variant's package id, `app.hanime.shell` (the retired phone shell's, so an installed 1.0.x app updates into this one rather than beside it) and its signing identity read from `NUVIO_RELEASE_*` before `local.properties`; `isUniversalApk` so a release is one download rather than four; `BuildConfig.MAL_CLIENT_SECRET`, the MyAnimeList secret read at build time from `local.properties`, the environment or this repository's `.env` (never a tracked file — the secret is inside the APK, the same trade the phone shell made, see *MyAnimeList has to come back to the phone*); and OkHttp made compile-visible to the relay, which needs a client that can be handed a DNS resolver |
| `iosApp/Configuration/Version.xcconfig` | *new*: the version the Android build reads. Upstream is 0.6.1 (144) at the pin; the fork ships **2.0.0 (200)** because it takes over 1.0.42 (43) and both numbers have to move forward for an installed shell to be offered the update |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdaterRepository.kt` | upstream's own file, one URL changed: the in-app updater asks this repository's releases instead of NuvioMedia's. Upstream's APK is signed differently, so offering it could only fail at install; `/releases/latest` is this fork's release, and the beta channel's list parses past the `desktop-v…` tags it cannot read |
| `composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt` | one line: the manga tab's MyAnimeList store is initialized beside the app's other storage initializers, so the tracker has a preferences file to read and write |
| `androidApp/src/main/AndroidManifest.xml` | one `<provider>` — the fork's start-up hook. Upstream's `NuvioApplication` is left exactly as it is, and a provider's `onCreate` runs before it |
| `androidApp/src/main/java/app/hanime/shell/SekaiBoot.java` | *new*: the start-up hook itself, which boots the in-process server |
| `androidApp/src/main/java/app/hanime/shell/SekaiServer.java` | *new*: the catalogue, on loopback. A small HTTP server on `127.0.0.1:8787` answering the addon protocol Nuvio's UI already speaks — manifest, catalog, meta, stream, subtitles — out of the Java sources beside it |
| `androidApp/src/main/java/app/hanime/shell/Catalog.java`, `Anime.java` | carried over from this repository's own phone shell (`android/app/src/main/java/app/hanime/shell/`), unmodified: the catalogue reader and AniList + LunarX. The web client, the desktop window and this app are all reading the same upstreams |
| `androidApp/src/main/java/app/hanime/shell/Fmovies.java` | the films/series source, rewritten to be the JVM twin of `server/src/fmovies.mjs` **after** the desktop moved to cinehd: TMDB's search/trending/details/season endpoints, and cinehd's six doors (Vidpro/vixsrc, Max, V2, Premium, 4K, Vidfast) with its own URL templates and order. It used to parse f-movies' HTML, which is not what the desktop reads any more. Paths keep the fork's `/api/fmovies` names so the addon routes do not care; a `probe=0` on the player route skips the door health knocks for the in-app walk |
| `androidApp/src/main/java/app/hanime/shell/Signer.java` | carried over from the phone shell, adapted: the WebView that runs hanime.tv's signature module. Here it loads the page from `https://hanime.tv/signer.html` and answers that request out of the APK, because the signature is bound to the page's origin (see below) |
| `androidApp/src/main/java/app/hanime/shell/Token.java`, `Streams.java` | carried over from the phone shell: the handshake token envelope (AES-GCM, key in the site's own bundle) and the gated `/api/v11/handshake` call that resolves playable sources |
| `androidApp/src/main/java/app/hanime/shell/HlsRelay.java` | the loopback relay, carried over and generalised: a signed link now carries a *profile* — a referer/origin for the film doors and a playlist key for flixcloud — so one relay serves hanime.tv, every door and every embed. It also undoes the providers' wrappers there: vixsrc's plain-base64 playlists and flixcloud's XOR-wrapped playlists and image-headed segments |
| `androidApp/src/main/java/app/hanime/shell/Doors.java` | *new*: the film doors, opened the way `server/src/browser.mjs` opens them on the desktop — except the browser is the app's own. A hidden WebView loads the door page, interception catches the playlist it fetches, and a JS probe (`performance`, `video.currentSrc`) covers the players that build the URL in the page. Answers are cached per door, misses remembered, and a caller that finds an answer which will not load can `forget` it so the next ask — the player's *Refresh streams* included — opens the page again. `resolver` is the seam the JVM tests replace |
| `androidApp/src/main/java/app/hanime/shell/Vixsrc.java` | *new*: the one door that needs no browser. vixsrc's own page does two plain requests and writes the playlist into its embed markup, so `Vidpro` is resolved in about half a second over HTTP instead of a nine-second page load. Its playlist endpoint `403`s some networks; the refusal is detected and the walk moves on immediately rather than waiting out a page that would ask the same questions |
| `androidApp/src/main/java/app/hanime/shell/FlixCloud.java`, `FlixMixer.java`, `assets/mixer.html` | *new*: the JVM twin of `server/src/flixcloud.mjs` — the page's seed-derived field names, the `/api/m3u8/<token>` answer and its two SHA-256-prefixed fields — plus the one step Java cannot do: the wasm key mix. Android has no wasm runtime, so the module is run in a WebView (`FlixMixer`, whose page is served from the APK for the provider's origin, like the hanime signer) and the page's own PBKDF2/XOR/SHA-256/AES-CBC chain follows it there |
| `androidApp/src/test/java/app/hanime/shell/MangaRouteTest.java` | *new*: nine JVM tests over the manga routes' parameter resolution and upstream URL — the search/page/limit bug they exist for, the filter vocabulary, `hasNext` and trending's limit. No phone and no network: `Manga.transport` is a double |
| `androidApp/src/main/java/app/hanime/shell/MainThread.java` | *new*: a `Handler` on the main looper, used by both signers to create and start their WebViews on the UI thread — without dragging in a scheduler library for the one thing they need from one |
| `androidApp/src/main/java/app/hanime/shell/Mal.java` | *new*: the MyAnimeList relay, the JVM twin of `server/src/mal.mjs` — the PKCE token exchange (the registration's secret added here, out of `BuildConfig`, never from the UI) and a query-only passthrough of MAL's API with the caller's own Bearer token. Query-only because Android's `WebResourceRequest` has no request body to forward, so one contract both backends can honour beats two that nearly match. Within ten minutes the relay remembers the names' addresses, and it resolves MAL's hosts through DNS-over-HTTPS (Cloudflare, then Google) when the phone's resolver refuses them; every other host keeps the system resolver. A network failure is retried three times, and the exchange alone is asked twice more when MAL's edge answers `502`/`503`/`504`; a gateway that stays down is answered with a sentence instead of a bare status. Requests carry a `SEKAI-Mobile` User-Agent |
| `androidApp/src/main/assets/htv-signer.js`, `htv-signer.wasm`, `signer.html` | hanime.tv's own signature module and the page that boots it, as the phone shell already bundled them |
| `composeApp/src/androidMain/res/values/strings.xml`, `composeApp/src/androidMain/res/values-bn/strings.xml`, `composeApp/src/androidMain/res/values-es/strings.xml`, `androidApp/src/debug/res/values/strings.xml` | the app calls itself **SEKAI**, and `SEKAI Debug` for the variant that installs beside it. Each file is replaced whole at its own path: upstream names the app in the default resources *and* in the Bengali and Spanish ones, and a locale that kept the old name would be the one screen that disagreed, so those three are overridden and every other locale inherits the default. The debug source set — which would otherwise still say "Nuvio Debug" — gets one of its own |
| `composeApp/src/androidMain/res/mipmap-*/ic_launcher*.webp`, `drawable-nodpi/ic_splash_logo*.webp`, `composeApp/src/commonMain/composeResources/drawable/app_icon_*.png`, `app_logo_wordmark*.png` | the launcher icon (legacy, round, adaptive foreground, monochrome, and the five themed variants upstream's app-icon picker offers), the Android 12+ splash marks and the in-app wordmark/lockup — every file of these names, replaced with this repository's own mark |
| `tools/make-app-icons.py` | *new*: the generator that derives all of the above from art this repository already ships (the desktop fork's 1024px app icon and wordmark), at the same geometry and from the same source as the desktop fork's `_mf/make-sekai-icons.py`, so the two clients cannot drift |
| `composeApp/src/commonMain/composeResources/values/manga.xml` | *new*: the manga tab's label, in a file of its own so it cannot collide with a key upstream adds to `strings.xml` later |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/manga/`, `composeApp/src/androidMain/kotlin/com/nuvio/app/features/manga/` | *new*: the manga tab — `MangaApi` (the loopback client and its rows), `MangaScreen` (shelf, title, reader), `MangaIcon` (the tab's mark, drawn as an `ImageVector` rather than added to an icon pack) and `MangaMal` with its Android `MangaMalStorage` (the MyAnimeList tracker, see below) |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/AppScreenTab.kt`, `MainTabsDestination.kt`, `AppShellComponents.kt`, `MainAppContent.kt` | upstream's own files, each with the fifth tab added: the enum value and its native-bar mapping, the slot in both navigation bars, the branch in the tab host, and the re-tap case in the root-tab handler (which is an exhaustive `when` and so fails to compile without it) |
| `androidApp/src/main/java/app/hanime/shell/Manga.java`, `MangaSigner.java` | *new*: the `/manga/…` routes — their API, signed and shaped for this app's reader, plus the page proxy and the position store — and the WebView that runs mangafire.to's signing bundle (see below) |
| `androidApp/src/main/assets/mf-signer.html`, `mangafire-polyfill.js` | the host page for that bundle and the bundle itself, vendored from their build exactly as this repository's web client already vendors it (`server/client/src/mangafire-polyfill.js`) |
| `androidApp/src/main/java/app/hanime/shell/Subtitles.java`, `OpenSubs.java` | *new*: the addon's `subtitles` resource — where each area's tracks come from, decided by what its player actually has. Anime reads the LunarX embed's own list through `FlixCloud.subtitles` (the same evidence the desktop's `anime.mjs` reads, so the two clients offer the same tracks in the same languages); films and series resolve their TMDB slug to an IMDb id and ask OpenSubtitles by IMDb, exactly as the desktop's films area does; the 18+ shelf answers an empty list, which is its answer and not an error. Every track travels as a relay link carrying the provider's referer rather than as a CDN URL |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryMangaRow.kt` | *new*: the Library tab's **Manga** shelf — the reader's own shelf, not a receipt of what was read lately. A title added from the manga tab's title page or by a MyAnimeList import sits here; a tap hands it to the manga tab, which owns the reader. The shelf has the series library's organizing controls: a **Sort** chip (recently added / title / progress) and a **Show** chip (all / reading / not started), with a `12 of 165` count line |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryScreen.kt` | upstream's own file: the shelf row and its `onMangaOpen` parameter, and `internal` rather than upstream's `public` because the row is fed by this fork's own types |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/manga/MangaNav.kt` | *new*: the one-slot hand-off between the Library tab and the manga tab — what was tapped, and the chapter to resume from when the shelf knows one |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt` | upstream's own file with the hero's artwork chosen and sized for the band it fills: a real backdrop first, then a landscape poster, then the portrait one; TMDB URLs are asked for at `original` and AniList covers at `/cover/large/` (the `extraLarge` file). AniList's `bannerImage` is a 1900×400 strip — cropped to the hero's tall band it is 400px of height doing the work of a thousand — so anime uses its largest cover instead. A `w500` poster stretched across the screen is what looked pixelated as the carousel rotated |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/MangaTrackingSection.kt`, `TrackingProviderCards.kt`, `features/settings/TrackingSettingsPage.kt` | *new* / upstream's own file overridden twice: MyAnimeList as a full `TrackingProviderCard` in Settings → Tracking → Services, beside Trakt, Simkl and MDBList — same gradient card, expander, connect/await/disconnect cycle, with *Import my reading list* and its progress in the connected body. `TrackingProviderCards.kt` gains the `MAL` brand (its own blue brush, a drawn `M` monogram and a text wordmark), the MAL card itself and an optional `connectedContent` slot; the section that used to hold the plain text row is gone |

### Why the sources live in the app

The desktop fork runs this repository's Node server as a child process. A phone
cannot: there is no Node to spawn and no process to keep. So the same job is
done in process, and the account's addon list — which is shared with the desktop
window — keeps working, because `http://127.0.0.1:8787/addon/manifest.json`
resolves to this app itself.

The Java is deliberately *carried*, not rewritten: those three classes already
answer the same upstreams for the WebView shell, and a second implementation
would be a second thing to keep in step.

### The signer page has to live on hanime.tv's origin

hanime.tv mints its per-second signature pair in a wasm module, and the module
binds that signature to `window.top.location.origin` — upstream compares it
against the `Origin` header on the request. A page served from anywhere else
still produces a well-formed signature, for the wrong origin, and every request
it signs is refused as `401 {"error_id":"UA1"}`: a name that reads like a
user-agent refusal and is not one. (Nothing in the module reads `navigator`; it
is the page's origin that matters.)

So the signer's WebView loads `https://hanime.tv/signer.html` and a
`shouldInterceptRequest` answers that, and the module's two files, out of this
APK. No request leaves the device, and the page's origin is the one upstream
expects. The same three files are also served on loopback by `SekaiServer`, which
is how to check over `adb forward` whether the module boots on a given device.

### The manga tab's signer, and why it is a browser again

mangafire.to answers anyone, but its listing calls carry a `vrf` token minted
from the path and the query string, and the bundle that mints it decodes itself
against `navigator.appCodeName`: it does not run in Node and cannot be ported to
Java without reverse-engineering it. So the fork does what it already does for
hanime.tv — the site's own bundle runs in a WebView that loads the page from the
site's own origin (`https://mangafire.to/mf-signer.html`, answered out of this
APK by `shouldInterceptRequest`), and Java asks it to sign each request. Nothing
in that page sends a request anywhere; it signs, and answers with the token and
the query string it signed together, because what is signed and what is sent
have to be built from the same object.

Two facts about it are recorded here because both cost a build to learn, and
neither is visible from the outside:

*   **Their bundle signs only some paths.** It publishes its guards on
    `globalThis` while it boots, and the page asks them: `/titles/…` is protected,
    `/top-titles` and `/filter-options` are not. A shelf that answers an unsigned
    request is not evidence that signing is broken.
*   **An empty query string is still an answer.** A title asked for by id has no
    parameters at all, so the signer answers with a token and nothing to
    serialise; reading "no query" as "no answer" sends the request unsigned and
    upstream answers `403`, which points at their bundle instead of at the
    caller.

Their image CDN refuses a request whose referer is not the site, so reader pages
are fetched by this app's server — where the header can be set — and served from
loopback, the same way covers are. Positions (which chapter, which page) are the
app's own state, written to its preferences as pages turn.

### What the manga tab taught

Three mistakes are worth the paragraph, because all three looked like upstream
misbehaving and none of them were:

*   **A tab gets no content colour.** Every screen in this app is drawn inside a
    `NuvioScreen` on top of the theme's background; a bare `Box` in a tab
    inherits neither, and `Text` without an explicit colour then takes
    `LocalContentColor`, whose default is black. The first version of the manga
    tab did exactly that: titles, chapter names and section headings were on
    screen and unreadable on a black page — which reads as "the data never
    arrived". The fix is not a colour here and there, it is building the screen
    from the app's own components (`NuvioScreen`, `NuvioScreenHeader`,
    `NuvioInputField`, `NuvioPosterCard`, `PosterGridRow`), which is also why the
    manga search now looks like the Search tab.
*   **Crossed arguments read as an empty catalogue.** `/manga/search.json` took
    `page` and `limit`, and the route passed the caller's limit into `page`. Every
    keyword search therefore asked upstream for page 24 of 24 and got an empty
    list — which looks exactly like a search that found nothing. The tell was in
    the response: `"page": 24` for a request that asked for page 1.
*   **A shelf that keeps its filters has to outlive its own screen.** Walking into
    a title unmounts the browse composable and every `remember` inside it, so a
    reader who searched, filtered and came back found the tab reset to trending.
    The feed, the shelf row and the scroll position are held above the route
    switch for that reason; pagination state lives in one object so a tail-load
    reads the page it is on rather than the one captured when its collector
    started.
*   **The Library is two shelves, and the pills say so.** The switch above the
    content read *Saved* / *Cloud*, which named neither thing inside it: one
    word said nothing about what the list held, the other was not a shelf at
    all. It is **Series** / **Manga** now — the series the reader tracked and the
    manga they read — with *Cloud* appearing only once a cloud library is
    actually configured (`canUseCloudLibrary`) and *Servers* only when one is
    linked, so a phone with neither shows the two words that matter. The labels
    are literals rather than upstream's `library_source_saved`, whose six dozen
    translations all still say "Saved".
*   **One layout button, both shelves.** The grid/row button in the header used
    to switch the series grid and leave the manga row a row, which reads as a
    broken button because that is what it was. The manga shelf's state now lives
    in `MangaShelf` above the list (`rememberMangaShelf`), so the same `columns`
    and `layoutMode` the series use build it: row mode is the horizontal shelf as
    before, grid mode is the series grid's measurements chunked into rows by the
    `LazyColumn` — lazily, because an imported shelf can hold four hundred
    titles, and composing every poster inside one list item is how that becomes
    a freeze.
*   **An import keeps MyAnimeList's order.** `Recently added` is the shelf's
    stored order, and every row used to be stamped `now` by the route, so an
    import's titles landed in whatever order thousands of identical stamps
    happened to collide in — usually the reverse of MAL's. The list is now asked
    for with `sort=list_updated_at` (MAL's own "last read first") and each row is
    stamped a millisecond older than the one above it, carried through
    `addToLibrary(card, at = …)` to `Manga.atOr`. The stamp is parsed as a
    `long` on purpose: an epoch millisecond read with the route's `int` parser
    fails silently and becomes `0`, which is how the order could vanish with no
    error anywhere. `MangaRouteTest` holds that parse.

### MyAnimeList has to come back to the phone, not to a desktop

The web client and the desktop window share one MAL registration whose redirect
URI is `http://localhost:8787/`. On a phone that address is this app's own
server, so the same registration works unchanged — but only because the browser
and the app are on the same device: the browser follows MAL's redirect to
`localhost:8787`, the in-process server keeps the code for the state the app
minted, and the app collects it. Nothing is exposed to the LAN; the socket is
still loopback-only. A code is kept for ten minutes at most and read once.

The token exchange carries the registration's client secret, and MAL refuses a
public-client exchange for this registration (`invalid_client`): the secret has
to reach the phone. It is laid into `BuildConfig.MAL_CLIENT_SECRET` at build
time from `local.properties`' `malClientSecret`, the environment, or this
repository's `.env` — never written into a tracked file. It is therefore
*inside the APK*, which is the same trade the WebView shell made: an APK is
decompilable, so treat that secret as distributed, not private.

### A mangafire title has no MAL id

The title and chapter answers carry no `malId` (mangafire's own page shows MAL
data it assembles elsewhere), so the app resolves the entry by searching MAL for
the title and remembering the answer. The matching is deliberately strict: an
exact, prefix or containment match of a normalised title or one of MAL's
alternative titles, and nothing otherwise. Writing progress to a plausible-
looking row is worse than writing none — the reader would find somebody else's
series moved forward on their list.

### The first manga call pays for a WebView

The `vrf` signer is a WebView, and on a low-end phone a cold one takes seconds
to boot. It is started with the server at app launch rather than on the first
manga tap so that the wait overlaps the home screen, and `/manga/…` calls that
do not need a signature (trending, filters) answer immediately either way. This
is why a title can feel slow the first time after the app was killed and fast
afterwards; it is the signer, not the catalogue.

### Every shelf plays in the app, which is why there is a hidden WebView

The desktop resolves a door by borrowing a headless Chrome over CDP
(`server/src/browser.mjs`). A phone has a Chromium of its own, and it is the
WebView, so the same technique is used in-process: `Doors` loads the door page
with the app's own Chrome, captures the first playlist the page asks for, and
throws the page away. Nothing about the *playback* then needs a browser — the
player opens a loopback URL and `HlsRelay` supplies the referer, origin and key
the CDN checks.

flixcloud is the harder half, because its chain ends in WebAssembly. There is no
wasm in the JVM, so `FlixMixer` runs the provider's own 389-byte module in a
WebView whose page is served out of the APK, then runs the page's PBKDF2, XOR,
SHA-256 and AES-CBC on the same answer. The Java side does the HTTP, the page
does the arithmetic, and the result is a master playlist plus the 32-byte
playlist key the relay needs.

A door that will not open is still listed as `externalUrl`. That is the fallback
and not the shape of the list: the list is a stream whenever any door answers.

Before a resolved playlist is offered, it is asked for once by the relay. That
check exists because of what the phone taught: vixsrc's *API* answers everybody
while its *playlist* endpoint refuses some networks outright, and ythd hands out
an unsigned URL before the tokenised one. A door whose URL does not load is
passed over, its answer is left uncached so **Refresh streams** genuinely
re-resolves, and if no door can prove itself the first resolved one is still
offered in-app — the player may succeed where the probe did not, and it is the
reader's own player either way.

### What the tests hold

`MangaRouteTest` (9 tests), `PlaybackRouteTest` (12 tests), `SubtitleRouteTest`
(6 tests) and `MalRetryTest` (5 tests) run on a plain JVM with
`:androidApp:testFullDebugUnitTest` — 32 in total, all passing. The second one pins the pieces that a
phone would otherwise be the only way to check: cinehd's six doors and the walk's
order, that a door URL is cinehd's template with the TMDB id filled in, the
referer a CDN will be asked with, that a flixcloud embed is routed to the payload
resolver and nothing else is, the image-header keystream a flixcloud segment
arrives under, vixsrc's player-page shape, the two spellings its embed page uses
for its master playlist, and that a route matching nothing answers 404 without
dialling. The relays' link signing is not in there — it goes through
`android.util.Base64`, which a unit test's `android.jar` stubs — so that half is
checked on a device (`adb logcat -s ShellRelay ShellDoors` and a `curl` of the
link it hands out).

## What is not finished

Stated plainly, because a NOTICE that oversells the fork is worse than none:

*   **In-app playback is verified on one phone, with one title per area.** On a
    Redmi 23124RA7EO (`fast` build, 2026-10-19) the chain was walked end to end
    from this machine: a film (`Spider-Man: Brand New Day`) resolved through
    Vidpro and Max to a playlist and **played** (the app's own media session
    reported `state=PLAYING`, its own decoder running, no browser activity); a
    series episode resolved through Max and served a real `0x47` segment; an
    anime episode resolved through flixcloud's wasm mixer and served an 8 MB
    segment; the 18+ shelf served its master, variant and segment unchanged. A
    search pulled all twenty catalogue pages (478 titles) without scrolling.
    What is *not* verified: other titles, other networks, and the doors that go
    dark on this one — vixsrc's playlist `403`s here while its API answers, and
    ythd handed Hope an unsigned URL on the first ask and a working one on the
    second. Those are upstream behaviours the walk now detects rather than
    fixes.

*   **The manga tab's label is not translated.** Upstream's four tabs are
    localized in 27 languages; "Manga" is English on every one of them, which is
    at least honest about who translated the fifth tab.
*   **The MyAnimeList login was not completed end to end.** The browser step
    and the redirect were exercised (MAL's consent page opens; a code dropped
    at `/` is collected once by the app), and the token exchange was exercised
    against the live endpoint (a bogus code comes back `invalid_request`, which
    means the registration's secret was accepted). Completing the consent with
    real credentials, and watching a chapter move a real list — and then
    importing a real list, which is what puts an account's titles on the
    Library's Manga row — is the one check that needs an account.

*   **A sign-in can miss one DNS lookup, and used to say so as an error.** A
    phone coming back from the browser may be switching networks at exactly
    that moment; tapping *Connect MyAnimeList* once answered `unable to resolve
    host "myanimelist.net"`. The relay now retries a network failure three times
    with a short pause, and only reports one that survives all three — as a
    sentence the reader can act on rather than the resolver's. Held by
    `MalRetryTest`, which is where the answer to a phone-only failure belongs;
    the successful retry itself was not provoked on a device, because the
    network it needs to fail first has to fail on demand.

*   **The `502` during consent was a DNS block, not MAL's gateway.** The same
    *Connect MyAnimeList* was answered `MyAnimeList refused the sign-in (502)`
    after the consent succeeded. The relay's own log then showed the truth:
    `UnknownHostException: Unable to resolve host "myanimelist.net": No address
    associated with hostname` — the phone's resolver refused MAL's names while
    the browser opened the consent page (its own DoH) and `ping` still answered.
    The relay now resolves MAL's hosts through DNS-over-HTTPS (Cloudflare by
    address, then Google), caches the answer for ten minutes and leaves every
    other host on the system resolver. Verified on the phone: the token route
    now returns MAL's own `400 invalid_request` / `401 invalid_token` bodies
    instead of *unreachable*, and the API route reaches `api.myanimelist.net`.
    A genuine MAL gateway `502`/`503`/`504` is still retried twice and explained
    in words, held by `MalRetryTest` (9 tests).

*   **Subtitle tracks are verified as routes, not as text on screen.** The
    anime route answers 12 tracks for a LunarX episode in 0.66 s and the relay
    serves one of them (82,653 bytes of `.ass`, `200`); the films route answers
    40 OpenSubtitles tracks in 0.8 s and serves one (`200`). What has *not* been
    driven is the player's own subtitle picker choosing a track mid-playback —
    the client sniffs the body's content for its format rather than trusting the
    relay URL's extension, which is the code path an `.ass` track needs, but the
    proof of subtitles appearing over a playing video is still a walk to take by
    hand.

*   **The Library's Manga shelf, its organizing controls and the seamless
    chapter are verified on the device.** Three titles added to the shelf showed
    in the Library with their progress line (`Ch. 150` where a position existed,
    `Not started` where it did not) under a `3 titles` count; the **Show** chip
    opened its sheet and picking *Reading* narrowed the shelf to `1 of 3` with
    only the title that has a position, and *Recently added* is the stored order.
    A tap opened the title page in the manga tab, and scrolling to the end of a
    chapter carried on into the next without a tap (earlier walk: `Ch. 28 page
    83/119` → `Ch. 29 page 26/123`, header `Ch. 29 / 30`). What the walk did not
    cover: a title whose chapter list fails to load (mangafire answered `403`
    once, and the page says so rather than offering a chapter list it does not
    have), and a reader who reaches the *last* chapter, where there is nothing
    below to append.

*   **The hero is sharp and the MAL card is real, both seen on the phone.** Six
    carousel frames were captured: four films (`Avengers: Doomsday`, `Hope`,
    `The Odyssey`, `Masterplan`) drawing their TMDB `original` backdrop at full
    bleed, and one anime (`Tempal: Item no Chikara`) drawing its largest cover
    rather than the 1900×400 AniList banner, which was the one still soft. The
    Settings → Tracking page shows four gradient cards — Trakt, Simkl, MDBList
    and **MyAnimeList** — with the MAL card expanded to *Connect MyAnimeList* and
    *Visit MyAnimeList*, its `M` monogram and blue brush matching the others.
    What is not verified: a completed consent with real credentials and a full
    library import (needs the account), and the MAL card's *awaiting approval* /
    *connected* bodies, which only a real sign-in can reach.
*   **The Series/Manga split and the new import order are built and unit-tested,
    not yet walked on the phone.** The change that made the Library pills
    *Series* / *Manga*, moved the manga shelf into the layout button's reach and
    stamped imports by MAL's order compiles into the `fullDebug` APK
    (`assembleFullDebug`, `BUILD SUCCESSFUL`) and the JVM suite is green
    (`testFullDebugUnitTest`, 37 tests, 0 failures, including the new
    `anAddedAtStampKeepsItsMilliseconds`). What it does not have is the device
    walk: the phone was not attached when this was written (`adb devices`
    empty), so nobody has yet seen the Manga pill draw a four-column grid, nor
    seen a fresh import come back in MAL's last-read order. Those are the two
    things to check first on the next install.
*   **The manga area's tracker is manga-only.** MAL's anime list, the Library's
    MAL source and scrobbling the player are not wired; the web client's MAL
    module is the reference for that next step.
*   The hand-installed builds are the **debug** variant (`com.nuviodebug.com`)
    or the **fast** one — the release pipeline signed with the debug key under
    the same package (`--fast`). The **published** variant is `fullRelease`,
    `app.hanime.shell` signed with the shell's key; it is what the release
    scripts publish. `fast` is not debuggable either and its stack traces are
    minified; it exists because debug is visibly slower on a weak phone
    (measured here: 3.1–3.9 s cold start against 0.8–1.2 s).

## Consequences of the license

GPL-3.0 is copyleft: builds distributed from this fork must be GPL-3.0 too, with
the complete corresponding source available to whoever receives them, and with
these notices intact. If that is not acceptable for how you plan to ship an APK,
the alternative is to not distribute the shell build (keep it a local tool) or
to reimplement the UI — neither of which this repository forces on you.
