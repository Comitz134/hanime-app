# Android build, install and update

Everything needed to reproduce the APK this tree ships and to cut a new release.
Toolchain versions and hashes below were read back from the machine that
produced them.

---

## 1. What the app is

The app is **self-contained**: it needs internet, but no proxy, no Node, and no
machine of yours running anything.

```
android/app/src/main/
├── assets/            the whole backend, shipped in the APK
│   ├── index.html         the web client — generated bundle (see §3)
│   ├── htv-signer.js      hanime's signature module, syntax-downgraded
│   ├── htv-signer.wasm
│   ├── signer.html        host page that boots the module
│   ├── playlists.json     250 of the biggest crawled playlists
│   └── app.webmanifest
└── java/app/hanime/shell/
    ├── ApiServer.java       routes every request the page makes
    ├── Catalog.java         open catalogue: fetch, filter, paginate, shape
    ├── Streams.java         anonymous session + signed stream handshake
    ├── Signer.java          hidden WebView hosting the WASM signer
    ├── HlsRelay.java        signed HLS relay: rewrite playlists, pipe bytes
    ├── Token.java           AES-256-GCM handshake envelope
    ├── PublicPlaylists.java search over the bundled dataset
    └── MainActivity.java    WebView host + cold-start update check
```

`MainActivity` loads `https://hanime.tv/` and `shouldInterceptRequest` answers
everything:

| Request | Answered by |
|---|---|
| `https://hanime.tv/**` (page, JS, wasm) | APK assets |
| `/api/videos`, `/api/tags`, `/api/brands`, `/api/videos/:slug` | Java, straight from the open catalogue |
| `/api/videos/:slug/sources` | Java: handshake → `x-token` → relay links |
| `/relay?u=&s=` | Java: signed upstream fetch, playlist rewrite, byte passthrough |
| `/api/public/playlists*` | Java, over `assets/playlists.json` |
| `/api/anime/*` | Java: AniList catalog + LunarX episodes/player (`Anime.java`) |
| `/api/session`, `/api/app/version` | session / update source (see §6) |
| fonts, `hls.js` from jsdelivr, image CDN | left to the WebView (returns null) |
| client state: favorites, watch history | the WebView's `localStorage`, no request at all (see §7) |

Three consequences worth stating:

- The page's origin is `https://hanime.tv`, which is exactly the origin the
  upstream auth services echo in `access-control-allow-origin`, so nothing
  fights CORS.
- Favorites and history are device-local by construction. There is no account
  to own them, so they are `localStorage` on that origin, and the app touches no
  server to read or write them.
- The Node proxy still exists and is unchanged — it is the dev/prod server for
  the browser, the crawler, and the test suite. The app does not need it.

---

## 2. Toolchain (exact, as measured)

| Component | Version | Path |
|---|---|---|
| JDK | Temurin **17.0.20.1+1** | `/f/atc/jdk17` |
| Android SDK | cmdline-tools `latest` | `/f/atc/android-sdk` |
| build-tools | **34.0.0** | `/f/atc/android-sdk/build-tools/34.0.0` |
| aapt2 | 2.19-10229193 | `…/build-tools/34.0.0/aapt2.exe` |
| apksigner | **0.9** | `…/build-tools/34.0.0/lib/apksigner.jar` |
| platform | **android-34** | `/f/atc/android-sdk/platforms/android-34` |
| platform-tools / adb | **37.0.1** | `/f/atc/android-sdk/platform-tools` |
| emulator | 37.2.12 | `/f/atc/android-sdk/emulator` |
| system image | **android-35;google_apis_playstore;x86_64** (WebView 124) | `…/system-images/android-35/…` |
| AVD | `shell35` (pixel_5) | `~/.android/avd/shell35.avd` |
| Gradle | **8.9** (Kotlin 1.9.23) | `/f/atc/gradle-8.9` |
| Android Gradle Plugin | **8.6.1** | `android/build.gradle` |
| Node | v26.4.0 | `PATH` |

The toolchain lives at `/f/atc` because the repo path contains a space
(`F:\Reverse eng and scrape`), and the SDK's `.bat` wrappers fail on a spaced
path while Node refuses to spawn them (`EINVAL`). `local.properties` points at
`sdk.dir=F\:\\atc\\android-sdk`, and the publish script invokes apksigner as
`java -jar …/lib/apksigner.jar` for the same reason.

**Why Android 35 for testing:** the first emulator image (Android 11) shipped
Chromium **83**, which cannot parse `??=` in the signer bundle *and* whose WASM
engine rejects the module with `wasm function signature contains illegal type`.
WebView 124 runs it unmodified. Test on an image whose WebView is current, or
you are testing a browser the app does not ship.

---

## 3. Regenerate the bundled assets

Required after changing the client sources under `server/client/` or the
vendored signer. Guards in `server/test/web-client.test.mjs` fail if you
forget: the committed bundle must match a fresh build of the sources (both
copies of it), the signer must stay parseable by
WebView 83, the nav's five targets must match five views, and the library's
storage calls must stay wrapped in `try`. (154 tests. The updater's
source/channel selection and the rest of the JVM side have 29 more as JVM
tests: `gradle :app:testReleaseUnitTest`.)

The client no longer ships as one hand-edited file. Its sources live in
`server/client/` — `template.html` (markup), `styles/*.css` (15 sections),
`src/*.js` (29 modules) — and esbuild bundles them into the single inline
script in `index.html`:

```bash
cd hanime-app

# 1. the client — bundles server/client/ and writes BOTH copies at once
#    (server/public/index.html and android/.../assets/index.html), so the two
#    can never drift; the test suite calls the same render() and fails if
#    either committed copy is stale.
cd server && npm ci && npm run build:client && cd ..
#    the webmanifest still rides along by copy
cp server/public/app.webmanifest android/app/src/main/assets/

# 2. the signer — downgrades 3 uses of ??= (Chrome 85+) for older WebViews
node android/tools/make-signer-asset.mjs

# 3. the playlist subset — top 250 crawled playlists with their entries
node android/tools/make-playlists-asset.mjs

# 4. hls.js — fetched once, then bundled beside the client in both places.
#    Playback used to load it from jsdelivr on every start, which made a third
#    party responsible for whether this app plays video at all. Pin the version
#    deliberately: 1.5.17, sha256 below, ES5 output so WebView 83 can parse it.
curl -sL -o server/public/hls.min.js \
  https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js
cp server/public/hls.min.js android/app/src/main/assets/
```

---

## 4. Build and verify

```bash
cd hanime-app/android

JAVA_HOME=/f/atc/jdk17 \
ANDROID_HOME=/f/atc/android-sdk \
PATH="/f/atc/jdk17/bin:$PATH" \
/f/atc/gradle-8.9/bin/gradle --no-daemon :app:assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk`

```bash
APK=app/build/outputs/apk/release/app-release.apk
/f/atc/android-sdk/build-tools/34.0.0/aapt2.exe dump badging "$APK" | grep -E "^package|launchable-activity|uses-permission"
/f/atc/jdk17/bin/java -jar /f/atc/android-sdk/build-tools/34.0.0/lib/apksigner.jar verify --verbose "$APK"
sha256sum "$APK"
```

Current build:

```
package: name='app.hanime.shell' versionCode='24' versionName='1.0.23'
sdkVersion:'26'   targetSdkVersion:'34'
launchable-activity: name='app.hanime.shell.MainActivity'
permissions: INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES, POST_NOTIFICATIONS
Verifies / v2 scheme: true / signer CN=hanime shell (unchanged)  [cert sha256 13c01040…]
1,432,842 bytes / sha256 635d97255ebbc96cdbbca36c9d3658d59f7829af670905cc66636b66cf64b0e9
  assets/index.html 458,701 bytes sha256 66a9f9ea087f8f9f752994e2aaf13114b2037e6deb613f5086af70e5a2095dcf
  assets/hls.min.js 413,952 bytes sha256 484054e8cd03d3f6d1781fb7f402bdc318d8a4c527f933a95c624e27cc9a9470
```

The bundled client's digest above is the one read back out of the APK built
from this tree — not assumed — and it is byte-identical to
`server/public/index.html` in this tree. 1.0.23 carries the films & series
area (f-movies.org routes, new navbar icon) plus that area's own device
library and continue-watching rail; it is signed with the same key as
1.0.22, so installing it over the phone's copy keeps the account and the
device's data. Publication of a release is a separate step and its own
digests live in §7's history. The digest changes with every client edit; the freshness
guard in the test suite is what keeps the server and APK copies in step,
rather than any recorded hash.

Release signing comes from `android/keystore.properties` + `android/keystore/`.
**Reuse the same key across releases** — a different signer turns every update
into a non-installable package.

---

## 5. Run it

```bash
ADB=/f/atc/android-sdk/platform-tools/adb.exe
$ADB install -r hanime-app/android/app/build/outputs/apk/release/app-release.apk
$ADB shell am start -n app.hanime.shell/.MainActivity
$ADB logcat -s Shell ShellSigner ShellCatalog ShellPage
```

No server to start. The app fetches the catalogue directly from upstream the
first time it opens (`ShellCatalog: catalog warm: 3429 entries in …`).

### 5.1 The emulator, for testing a build before it ships

The same AVD every check above was run on: `shell35` (pixel_5, API 35,
WebView 124). Boot it headless — about 30 seconds:

```bash
rm -f ~/.android/avd/shell35.avd/*.lock
/f/atc/android-sdk/emulator/emulator -avd shell35 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
export PATH="/f/atc/android-sdk/platform-tools:$PATH"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 1; done

# install the build under test and open it
adb install -r hanime-app/android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n app.hanime.shell/.MainActivity

# read what the app is doing
adb logcat -s Shell ShellUpdater ShellApi ShellCatalog
```

Fresh machine: create the AVD once first —
`avdmanager create avd -n shell35 -k "system-images;android-35;google_apis_playstore;x86_64" -d pixel_5`.

To drive the page itself (menu, themes, player, back — anything in the
client), attach to the WebView over DevTools. `MainActivity` enables WebView
debugging unconditionally, so this works on release builds, not just debug:

```bash
PID=$(adb shell pidof -s app.hanime.shell | tr -d '\r')
adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
node hanime-app/tools/webview-probe.mjs your-page-script.js
```

The page script must evaluate to a string (usually `JSON.stringify({...})`);
the probe prints it plus any console output the page produced, and exits
non-zero when the script throws or the page navigates away.

Before cutting a release, the same flow exercises the updater end to end:
install the **previous** version, cold-start it, and watch for `update check:
AVAILABLE` → download → `sha256 …` → "ready to install" in logcat — exactly
what the 1.0.11 release produced from the v11 install on this AVD.

---

## 6. Updates

The shell checks on **every cold start**, then at most once every **six hours**
of use (`MainActivity.CHECK_INTERVAL_MS`), so an app that is never closed still
notices a new build. Only one check runs at a time (`checking`): on a cold start
the create-time check and the resume-time one would otherwise both fire, because
the record each reads is from the previous session.

Where it looks is configurable:

- default: this project's own GitHub release channel —
  `https://github.com/Comitz134/hanime-app/releases/latest/download/version.json`
- channel: **stable** (the default) or **beta**, chosen with the Channel row on
  the Update source screen. The channel picks *which built-in manifest* is
  consulted: stable is the `/releases/latest/` route above (GitHub skips
  prereleases there), beta is the rolling
  `…/releases/download/channel-beta/version.json` that `publish-github.mjs`
  refreshes onto one fixed prerelease on every publish — so a device switched
  to beta always asks a URL that exists, never a phantom. Build-time override:
  `-PbetaUrl=…` → `BuildConfig.BETA_URL`.
- build time: `-PupdateUrl=…` → `BuildConfig.UPDATE_URL` (the stable default)
- runtime: **Settings → Update source** (stored in `SharedPreferences`, wins over
  both —
  unless it cannot be reached, in which case §6.1 applies)

The default matters: an app that has to be handed an update URL before it will
update itself is not self-updating. A plain `gradle :app:assembleRelease` with no
properties at all produces a build that can already reach the next release.

Both accept either shape:

| Value | Requested URL |
|---|---|
| `https://example.com/updates/app.json` | that file directly |
| `https://example.com/updates` | `https://example.com/updates/api/app/version` |

Passing `-PupdateUrl=` explicitly means **not configured**, which the app reports
in ~100 ms instead of dialling a dead host for ten seconds.

### 6.1 A saved source can no longer strand a device

The runtime setting is an override, and overrides go stale. The screen used to
be described as *"the machine running the proxy"* and offered `10.0.2.2` as its
default, so a phone could end up saving an address that only answers inside an
emulator — after which every check failed with `failed to connect to /10.0.2.2`
and the device was stuck on its installed build for ever, silently.

What the app does now:

1. The check tries the effective source (saved override, else built-in).
2. If that attempt **fails**, it tries the selected channel's built-in source
   once more. A source that *answers* is never second-guessed: `UP_TO_DATE`,
   `AVAILABLE` and `NOT_CONFIGURED` all end the check where they are.
3. An outcome that came from the fallback remembers the address that failed, so
   the reply names both the address that did not answer and the one that did:

   ```
   You are on the latest version (1.0.7). Updates now come from
   https://github.com/Comitz134/hanime-app/releases/latest/download/version.json,
   because the saved source http://10.0.2.2:8799/version.json could not be reached.
   ```

4. The download resolves a relative `apk_url` against the source that actually
   answered (`Updater.lastManifestBase`), not against the dead one.
5. **Update source** now shows the source in use — prefilled from
   `ServerConfig.get()`, not from the app's bundled origin, which is what
   pressing Save used to store by accident. The reset button ("Use the built-in
   source") is always available, and an empty field saved is a reset too.
6. The shape of the answer matches the outcome. A check that **succeeded**
   answers with a message (`Toast`), never with a dialog: a dialog is what this
   app shows when something went wrong, and a modal box reading "could not be
   reached" after a check that worked is exactly what made a working check look
   broken. (v1.0.5 did that — and it is why "check for updates doesn't work"
   arrived twice.) A dialog is reserved for a check that actually failed, which
   names every source that was tried, and for an update that is ready to
   install.

A fallback is not silent: it is logged (`saved source failed (…), trying the
built-in one`), it is named in the message that reports the check, and it is
stated again in the dialog that leads to an install.

7. **Every check leaves a record, and the screen shows it.** `Updater.check`
   writes the outcome through `UpdateLog` before anyone is told about it, so a
   check from any path is recorded. **Settings → Update source** now opens on three
   lines — installed build, the source actually in use, and when the last check
   ran and what it said — with **Check now** (which hands a found update back to
   the app to download, rather than duplicating that flow) and **Copy
   diagnostics** (versions, effective source, saved override, last result, the
   WebView's user agent, and how much the cover cache is holding). Both rounds of
   "check for updates doesn't work" were diagnosed over logcat; this is the
   screen that replaces that.
8. **The channel selector always lands.** An override that merely repeats a
   built-in URL — what prefill-and-save leaves behind — is dropped when the
   channel is switched, so choosing Beta cannot be silently ignored by an
   address that says what stable would have. A genuinely custom address is
   kept, and still wins, as §6 says.

Flow: detect (`version_code` strictly greater) → **auto-download** → verify
sha256 **and** byte count → hand to Android's installer through the FileProvider
URI. Nothing installs silently; the system consent screen always appears. A
mismatched checksum deletes the file and aborts.

A finished, verified download also earns a **system notification** ("Version X
ready to install") — the one moment a user shouldn't have to open the app to
learn something. Requirements are handled the modern way: a notification
declared in the manifest, and `POST_NOTIFICATIONS` requested on Android 13+ at
the only moment it matters — when an update is actually found, not on first
launch. Tapping the notification hands off to the same install dialog the app
already shows (`Updater.EXTRA_INSTALL_READY` → `promptInstall`), with the
downloaded manifest kept in the updater's own preferences, so the tap still
lands correctly after the process has died. No download, no notification: an
up-to-date check posts nothing and retracts anything already in the tray, so
the app never claims an update is waiting when it isn't.

### 6.2 Publishing the update source to GitHub Releases

The app's check has to answer from a phone that is nowhere near your machine,
so a GitHub Release is the update source: two assets on a `v<versionName>`
release, the APK and `version.json`, plus a rolling `channel-beta` manifest the
beta channel reads.

**Required environment:**

| Variable | Value |
|---|---|
| `GITHUB_TOKEN` | personal access token with **`contents: write`** (classic `repo`, or fine-grained *Contents: Read and write*) |
| `GITHUB_REPOSITORY` | `owner/repo`, e.g. `octo/hanime-app` |

**Optional overrides:**

| Variable | Default |
|---|---|
| `GITHUB_API_URL` | `https://api.github.com` |
| `GITHUB_UPLOADS_URL` | `https://uploads.github.com` |
| `GITHUB_SERVER_URL` | `https://github.com` |

```bash
cd server
export GITHUB_TOKEN=github_pat_…
export GITHUB_REPOSITORY=you/hanime-app

node scripts/publish-github.mjs --dry-run     # prints the URLs, uploads nothing, needs no token
node scripts/publish-github.mjs               # creates the release and uploads both assets
```

Re-running replaces both assets on the same tag, so the manifest can never
outlive its APK. The same run also upserts `version.json` onto a rolling
prerelease tagged `channel-beta` — the beta channel's manifest — so the URL the
app's channel switch points at exists from the first publish onwards and is
refreshed every time. Pass `--prerelease` to mark *this* release beta-only:
GitHub's `/releases/latest/` route (the stable channel) skips prereleases,
while `channel-beta` carries them.

The script prints **two** URLs, and the one to configure on devices is the
second:

| URL | Use |
|---|---|
| `…/releases/latest/download/version.json` | **the one to use** — always resolves to the newest release |
| `…/releases/download/channel-beta/version.json` | the **beta** channel — rolling `channel-beta` prerelease, refreshed on every publish |
| `…/releases/download/v<versionName>/version.json` | per-release copy, for pinning or auditing |

Never configure the pinned one: a phone pointed at `…/v1.0.3/version.json`
would check that exact version on every cold start and never learn about
v1.0.4. The stable form is why one setting survives every future publish, and
why it is the compiled-in default.

That stable URL is already the compiled-in default (`build.gradle`), so a plain
release build needs nothing passed to it. Override with `-PupdateUrl=<other>`
only to point a build somewhere else, or set it at runtime in the app.

The version number is read from `data/apk/release.json`, which
`publish-apk.mjs` wrote by parsing the APK with `aapt2` — it is never passed in
by hand, so GitHub and your server cannot disagree about what is current.

Note the two publishers deliberately differ in one field: `versionPayload()`
serves a **relative** `apk_url` (one server, reached as `10.0.2.2` from an
emulator and a LAN address from a phone), while the GitHub manifest serves an
**absolute** one (a release asset has exactly one address, and the manifest is
fetched from a different host than it serves).

---

## 7. Library, history and playlists by name

Favorites and watch history are the only state the app keeps. They live in the
WebView's `localStorage`, under the page's own origin (`https://hanime.tv/`,
served out of the APK): no account, no server, no database.
`setDomStorageEnabled(true)` in `MainActivity` is what makes it durable —
Android keeps that store in the app's data directory, so it survives a restart
and a self-update.

- one key: `htv:library:v1` → `{ favorites: [...], history: [...] }`
- an entry is `{ slug, name, cover, brand, views, released_at, at }`, recorded
  by `remember()` as records pass through a render — which is why the heart on a
  grid card costs no request
- history is deduped by slug, newest first, capped at 240 entries
- a title enters the history when a stream for it **resolves**, not when the
  sheet opens, so a page that never played is not left behind
- the read and the write are both wrapped: a storage that refuses (private
  mode, full quota) leaves the lists session-only and unhides `#lib-warn`
  instead of taking the page down

**Navigation** switches views in place via `showView()`. This is what the
navbar used to do with anchors: `body[data-view]` is marked, exactly one `.view`
carries `data-active`, the active item in the menu is marked, and nothing
scrolls to a section any more. The pill itself carries buttons only — the five
sections moved behind a three-line menu (`#menu-toggle` → `#nav-menu`), because
the strip that preceded it scrolled sideways on a phone: Studios, Playlists and
Library were only reachable by dragging the navbar, which nobody discovers.
Choosing an item switches the view and shuts the menu; a tap anywhere else
shuts it too; back closes it before it is allowed to leave the app, the same
contract as the sheet and the search field. Appearance (§7.2) and the update
controls live in the Settings view the menu opens (v15 / 1.0.15), which also
took over the floating ⋮ button that used to overlay the top-right of the page.

**Playlists by name.** Catalog search still answers with titles, and now also
asks the playlist indexes for a title match (`/api/public/playlists?q=`, plus
`/api/playlists` when an account is connected) and shows the result in the
`#pl-hits` rail beside the grid. A playlist's own name is a different index from
an entry title, which is why it is asked separately: typing `pandora` returns
`Pandora's Box …` while the catalog reports 0 matching titles.

---

### 7.1 Continue watching, and the library as a file

The library knows where a title was left. `lib.positions` lives in the same
`htv:library:v1` record as the favourites and the history, written at most once
every five seconds while playing plus on pause and on leaving the sheet, and
never for a title that is less than five seconds in or within ten seconds of the
end (a finished title has nothing to resume). History cards draw a progress bar
from it, a **Continue watching** rail at the top of the library lists the titles
that are part-way through, and opening one seeks back to the stored second and
says so: `#player-note` = `resumed from 2:17`. A position is re-validated on
read (`cleanPositions`), so a corrupt entry cannot draw a bar past the end of a
card or seek past the end of a video.

The library is also now a file. **Export library** hands the whole record to the
shell, which writes it through the system document picker
(`ACTION_CREATE_DOCUMENT`) — no storage permission, and nothing passes through a
server. **Import library** reads a chosen file back (`ACTION_OPEN_DOCUMENT`) and
hands the text to the page, which owns the format. An import **merges**: a title
already on the device is kept, the newest record of a title wins, history is
re-sorted by its timestamp, and the newest position wins — a file from last month
can never erase what is on the phone today.

---

### 7.2 Appearance: themes, accents, motion

The menu also holds how the app looks, because that used to be the build's
business: wanting a light screen meant rebuilding the app. Three choices, all
stored in the WebView's `localStorage` under `htv:prefs:v1`, guarded like the
library:

- **Theme** — dark (the measured palette), light, or *system*. System is a
  preference, not a palette: what reaches the DOM is always one of the two, so
  every rule has exactly one thing to match, and a device that flips to light
  mid-session is followed (`prefers-color-scheme` listener).
- **Accent** — amber (default), rose, violet, sky, mint. An accent is a hue
  swap on the tokens the stylesheet already composes against; a theme is a
  token swap on `body`. Neither rewrites a component. Light restates the five
  surfaces painted with literal colours and deepens the primary, because a
  pastel button on a white page is unreadable.
- **Reduce motion** — the bargain `prefers-reduced-motion` already strikes,
  but on the reader's word rather than the OS's.

Applied as `body[data-theme]`, `body[data-accent]` and `body[data-motion]`.
The choices survive a restart and a self-update because they live in
app-owned storage, and a storage that refuses simply keeps the defaults.

### 7.3 Tracking on MyAnimeList

One list, three areas: the anime shelf (AniList's own `idMal`), the manga shelf
(mangafire's `malId` when the title carries one, a one-time title search when
it does not) and the 18+ shelf (title search) all render the same tracking row
inside a detail sheet, and all of them write to the same MyAnimeList list.
Settings → MyAnimeList holds the account: link, unlink, and the reason when
something failed.

**Two credentials, and only one of them is a secret.**

| Piece | Where it lives | Why there |
|---|---|---|
| client id | `server/client/src/mal.js` (`MAL_CLIENT_ID`), compiled into both copies of `index.html` | a client id is public — it rides in every authorize URL |
| client secret | `.env` as `MAL_CLIENT_SECRET` (server), `local.properties` as `malClientSecret` (app → `BuildConfig`) | never in version control; both are gitignored |

A **public** (PKCE) registration issues no secret and the token route simply
omits the field. A **web service** registration does issue one, MAL then refuses
the exchange without it (`401 invalid_client`), and that secret ends up inside
the APK — readable by anyone who downloads the release. Nothing of the reader's
own leaks with it: the tokens stay on the device, and redeeming a code still
needs the pre-registered redirect URI to come back to the app. Registering a
PKCE client instead is the way to ship no secret at all.

**Redirect URIs — MAL matches them exactly, and both are registered:**

| URI | Which copy uses it |
|---|---|
| `https://hanime.tv/` | the app: `MainActivity` loads the bundled client at that origin from assets |
| `http://localhost:8787/` | the server copy, on whatever port `PORT` says |

The flow is authorization-code with PKCE, `code_challenge_method=plain` — the
only method MAL supports. The login page opens as a normal navigation, and
`shouldOverrideUrlLoading` lets exactly two things through: the app's own origin
and `*.myanimelist.net`. Everything else http(s) is still swallowed, so an
injected ad overlay cannot borrow the same door.

**The redirect comes home over the network, and that has to be handled.** MAL
answers the sign-in with a redirect to `https://hanime.tv/?code=…`, and a
WebView follows a redirect without offering the app the new URL — measured: on a
chain to MAL's own login page, whose *final* URL is what arrives, the first URL
is never offered at all. The chain begins on `myanimelist.net`, which this shell
deliberately passes through, so the code used to land on the **real** hanime.tv:
site-served rather than APK-served, unstyled, and with nothing to redeem the
code — which is how a *successful* sign-in still ended with "not linked". The
shell now watches the URL it actually committed (`doUpdateVisitedHistory`, with
`onPageFinished` behind it) and, when that URL is this origin and still carries
`code` or `error` while the page did **not** come from the APK
(`ApiServer.servedPage`), loads it once from here: an app-initiated navigation is
answered from assets like any other page, so the client boots with the code and
links the account. Both log lines name themselves — `visited <url>` and
`reclaiming the MyAnimeList redirect through the app`.

Neither backend stores anything: `/api/mal/token` performs the exchange (fields
allowlisted, the secret added server-side) and `/api/mal/v2/…` forwards the
caller's own bearer token. Both are query-only, because Android's WebView
interceptor is never handed a request body, and both refuse a foreign browser
origin — this server must not be borrowable as an open relay.

Measured against the live endpoints, no account needed:

- the authorize URL answers `303 → login.php?from=/dialog/authorization` for
  this client id and both redirect URIs, while a bogus client id or an
  unregistered redirect URI answers `401`. That is how the registration was
  checked without a login.
- through the **server** relay: the exchange reached MAL's own complaint about
  the code (`400 … "Cannot decrypt the authorization code"`) — client
  authentication passed — and `/api/mal/v2/users/@me` with a bogus token came
  back `401 {"error":"invalid_token"}`.
- through the **in-app** relay, driven from the page: the same two answers,
  plus `401 "The refresh token is invalid."` — which is the check that the
  secret actually reached `BuildConfig`.
- **back out of the sign-in works.** Pressing back on MAL's login page returns
  to the app. Worth stating because the WebView reports *no* history for that
  page even though the page's own `history.length` is 2, so the shell walks the
  history when there is one and reloads its own page when there is not.

Not verified here: a completed sign-in and a written list entry. Both need the
reader's MAL account, so the last mile is theirs — everything up to the login
page, and both pipes, are what is measured above.

### 7.4 The Library's three shelves

Linking the account feeds the Library too, and the Library is three tabs now —
**Anime**, **Manga**, **18+** — split the way the app itself is. The first two
are the account's own MyAnimeList lists: everything already watched and read is
there without retyping it, each card carrying its status, its progress
(episodes for anime, chapters for manga) and its score, and an order control of
its own (see §7.6). The 18+ shelf is everything adult on the account — **both
kinds**: a list that tracks adult manga is exactly the list this shelf exists
for, and a card still opens in its own area because every card carries its
kind. Which entries are adult is MAL's own answer
twice over: the black NSFW flag, or the `Hentai` genre — either one files the
entry under 18+ (badged `18+` when it is drawn on a shelf that is not 18+). The device's own records (Continue watching, Favorites,
History, export/import) did not move: they are the 18+ panel's floor.

**Nothing on a shelf is one of our ids.** MAL knows AniList ids, mangafire hids
and hanime slugs not at all, so a tap resolves the title against the area's own
catalog first — exact title, then a title that starts with it, then the search's
own top hit — and opens the sheet that area already opens: the anime player, the
manga reader, or the 18+ title page. The match is remembered per title (and per
shelf — the 18+ shelf once used the entry's own media kind for its messages,
which sent a failed match to the Anime panel's note, where nobody would see it).

**Two things about the list call are load-bearing.**

- `nsfw=true` is not decoration: MAL withholds black-flagged entries from list
  answers without it, and those are exactly the shelf that exists to show them.
- `fields` names the handful of keys the cards draw, twice: both as the
  `list_status{…}` / `node{…}` sub-sections a list entry is made of, and as the
  keys inside them. Belt and braces on purpose — **MAL answers an unknown field
  name with `200` and no key**, so a silent miss would empty every shelf instead
  of failing loudly. Measured against the live API with the app's own client id
  and no account: `fields=id,title,bogus_field_xyz,num_episodes` came back `200`
  carrying the valid keys, and a made-up `bogus{a,b}` sub-section was flattened
  to `bogusa` rather than refused. The anime and manga strings differ in exactly
  one field name, because asking the wrong kind for its progress field is asking
  for something that media does not have.

The lists are kept on the device for ten minutes, so switching tabs is not a
round trip, and the copy **paints first** — a phone with no signal, or a token
that died, still shows the list it read this morning. Unlinking drops the copy
with the tokens. Measured on the emulator with the release APK built from these
sources (sha256 `ea4c2623…`) and a seeded account copy in storage: the tabs read
`2 tracked`, `2 tracked`, `1 tracked`; the anime shelf drew
`Watching · 12/220 ep · ★ 8` and `Completed · 366/366 ep`; the manga shelf drew
Berserk plus the badged adult entry; the 18+ shelf drew the adult anime entry
and the device's own filter, tools, favorites and history underneath it. A tap
on NARUTO resolved to AniList id 20 through the app's own `/api/anime/search`
and opened the detail sheet with `220 episodes` and the embed
(`flixcloud.cc/e/bo2qdw3m3kjf?v=2`); a tap on a seeded adult title opened the 18+
page (`overflow-season-1`); a tap on a title the catalog does not have answered
on the panel the tap happened in. With `mal.auth` cleared, all three shelves said
how to fill themselves and the page made **no** `/api/mal` request at all. The
stale path was measured live too: an expired token with a twenty-minute-old copy
painted its cards before the network was consulted, refreshed once through
`/api/mal/token`, and — when the refresh was refused — kept every card and said
nothing.

**Two paths this client had wrong, found by reading a real account.** The first
build's shelves said *"Could not read your MyAnimeList list — not_found"*, and
two separate mistakes were behind it:

- the list was requested from `/v2/animelist`, which is not an endpoint at all —
  MAL's own path is `/v2/users/@me/animelist`. Both kinds are now asserted by
  path in the suite, not by the word `animelist`;
- **every** status read and write used `/mylist_status`, and the route MAL serves
  is `/my_list_status` (underscores). Both halves had therefore been failing
  silently since tracking shipped: a title could be added from a detail sheet and
  nothing landed. Reads also moved off that route entirely — a `GET` on it is
  `405 method_not_allowed`, so a reader's own status is read from
  `/anime/{id}?fields=my_list_status,…`, which is where MAL puts it.

Measured on the reader's own phone (release 1.0.20 installed over 1.0.19, same
signer, linked account intact): the Manga shelf drew **148 tracked** titles with
their real statuses and progress; the Anime shelf said plainly that nothing is on
the anime list yet (that account tracks manga only) rather than looking broken;
the 18+ shelf said the account has nothing flagged adult; and a title's detail
sheet showed *"on your list"* with its status select and a progress field of
**3** — read back from MAL, which this row had never managed before.

### 7.5 The list keeps itself current

Opening an episode writes episodes watched; opening a chapter writes chapters
read. It happens where the thing actually opens — a resolved player for an
episode, a rendered reader for a chapter — and three rules keep it from being a
nuisance:

- **forward only.** A number already reached is never rewritten, and one this
  session already sent costs no request at all, so re-opening episode 1 of a
  series you are on episode 40 of changes nothing.
- **their status is kept.** Only a *plan to watch/read* is moved to
  watching/reading, and a title that is not on the list yet is added that way —
  that is what "the list keeps itself current without me touching MAL" means.
- **reaching the total finishes it.** The last episode or chapter seen marks the
  entry completed, the same rule MAL's own clients apply.

Every failure is swallowed on purpose: playback must never depend on
MyAnimeList, and the detail sheet's row is where a reader goes to see what
actually happened. Measured live on that account, end to end: a manga reading
*2/100 chapters* was opened from the Library at chapter 3, and the account read
back *3/100* after — written by the app, with the status left as it was.

Not verified here: an **anime** write against a real account (that account tracks
no anime, and adding one to a public list is not something a test should decide).
Its call site and rules are asserted, and the manga path shares all of them.

### 7.6 Organizing: every shelf, in an order the reader picks

The Library had one order — newest list activity — and no way to ask for
another. It has eight orders now, and one table owns what they are called:
`server/client/src/sorts.js` holds **Last read**, **Last watched**, **Last
updated**, **Recently added**, **Title A–Z**, **Progress**, **Score**,
**Status**, plus the comparators behind them (progress is furthest-along first,
falling back to the raw count where a total is unknown; status is grouped
watching/reading, on hold, completed, plan, dropped). The words are shared on
purpose: "last read" on the manga shelf and "last watched" on the anime shelf
must read as one idea, not two inventions.

A shelf **declares** which of those orders it can honour — the first is its
default — and gets a `<select data-sort-for="…">` for it. Eight shelves do:
`mal-anime`, `mal-manga`, `mal-adult`, `watch-anime`, `read-manga`, and the
18+ panel's own `cont`, `fav`, `hist`. The suite asserts that control and
section are in step in both directions: a control with no section is a
dropdown that never fills, a shelf with no control is an order nobody can pick.

**A sort never reaches the network.** The choice is saved under
`htv:sorts:v1` and announced as an `htv:sort` event; the shelf that owns the
rows listens and redraws from what it already has — the account's shelves from
`malCache()` (a re-read of a list of titles in order to sort that list is a
request nobody asked for), the 18+ lists from localStorage. The catalog's own
`#sort` is remembered the same way and restored only if the stored value is
one the select still offers, so a value from an older build is never sent as
an unknown column.

**What "last read" reads from.** The account shelves sort on MAL's own
`list_status.updated_at` — the moment the list moved — which is now printed
next to the status, so the order is never a mystery:
`Reading · 3/100 ch · updated 46m ago`. Past thirty days it prints a date
instead of a count.

**The device's own record, and the two rails.** MyAnimeList knows where a list
stands; it does not know which chapter was on screen last night.
`activity.js` keeps one entry per title under `htv:activity:v1` (capped at
60), written at the moment the thing actually opens — a resolved player for an
episode, a rendered reader for a chapter — carrying the number, the total and,
for manga, the chapter's own id. That feeds two new rails: **Continue
watching** above the anime shelf and **Continue reading** above the manga
shelf, each with its own order, a × on every card and a Clear chip in the
header. Tapping one opens the title at the recorded chapter or episode; the
rails call the areas' own openers (`setOpener` is handed `openAnime` and
`openManga` by boot) so no second copy of a sheet's logic exists. The Library
also remembers which tab you left it on (`!libTab`), because the app opens on
Anime and someone who lives on their manga list should not have to say so on
every visit.

Measured on the reader's own phone — release **1.0.21** (versionCode 22,
sha256 `cd79b850…`, v2 signature, signer unchanged) installed over 1.0.20 with
the account and the device's data intact:

- the Manga shelf read **148 tracked** with the new meta, and its first card
  was the one updated 46 minutes earlier, because the shelf's default order is
  *Last read*; the Anime shelf reported no anime (that account tracks manga);
  `htv:sorts:v1` came back with `{"!libTab":"manga"}` after one visit and the
  Library re-opened on that tab.
- all eight selects came up filled with their own words on a first run —
  `watch-anime=watched(3)`, `mal-manga=last(5)`, `mal-adult=updated(4)`,
  `fav=added(2)` — and on the seeded web copy, changing a control re-ordered
  that shelf alone (`Progress` put 380-ch, 327-ch, 12-ch, 3-ch in order) while
  the network log showed **no** `/api/mal` request for it.
- **end to end, on a real title:** a manga at **12 chapters read** was opened
  at its oldest chapter — a prologue, **Ch. 0** — and 10 pages rendered. The
  record landed as `{number: 0, total: 522, ref: "4793357"}` and the rail drew
  one card, `Ch. 0 · read just now`. Leaving the sheet and tapping that card
  re-opened the same `Chapter 0 · Prologue` with its 10 pages. MyAnimeList was
  **not touched**: `num_chapters_read` stayed 12 and `updated_at` stayed
  `2025-11-07T20:29:01+00:00` — the forward-only rule skipped the write,
  because 12 ≥ 0.

**What the phone caught that no suite had:** chapter 0 was written to storage
and then filtered out on read — `noteOpen` accepted it while `all()` re-read
with `> 0`, so the rail stayed empty with the record sitting right there.
Both ends now say `>= 0`, and the pair is asserted in the suite: a guard and a
filter that disagree about what counts look exactly like a feature that does
nothing.

One layout fix under all of it: a poster card inside a rail had **no width**.
A flex row has no column template to impose one, so a card sized itself to its
own title and a shelf of them came out ragged — already true of Continue
watching and Recommended, made visible by two new rails. `.rail .card` now
carries the width of the grid's smallest column at each breakpoint.

---

## 8. Verified on a device

Measured on the `shell35` emulator (Android 15, WebView 124) with **no Node
process running anywhere**:

```json
{ "total": "3429", "gridCards": 30, "plCards": 60, "notes": [],
  "playback": { "mse": true, "t1": 1.853, "t2": 4.009, "advanced": true,
                "w1": 1280, "w2": 1280, "rs1": 4, "buffered": 44.61,
                "paused": false, "mediaError": null } }
```

That run is the v4 / 1.0.3 release APK (`1101875` bytes, sha256
`c83d34ad…`), installed over the previous build with the same signer.

The library build was probed the same way (`tools/library-probe.js`), on a
fresh install with `pm clear` beforehand, and reproduced twice:

```json
{ "url": "https://hanime.tv/", "gridCards": 30, "hearts": 30,
  "nav": { "bodyView": "library", "libraryDisplay": "block",
           "browseDisplay": "none", "current": ["Library"], "scrollY": 27.6 },
  "sameTab": { "scrolledTo": 27.6, "scrollY": 27.6 },
  "favorite": { "slug": "kanojo-saimin-1", "pressed": "true", "sheetStayedClosed": true },
  "library": { "count": "1 favorite · 0 watched", "favTitles": ["Kanojo Saimin 1"] },
  "byName": { "heading": "Playlists by name", "count": "1 found",
              "names": ["Pandora's Box 2 (wholesome to completely fucked)"],
              "gridTotal": "0" },
  "history": { "title": "Kanojo Saimin 1", "hasPlayer": true,
               "favPill": "In library", "saved": ["Kanojo Saimin 1"] } }
```

That run is the v5 / 1.0.4 release APK (`1106031` bytes, sha256 `90e8e7be…`,
same signer, installs as an update over 1.0.3). It is the
[released asset](https://github.com/Comitz134/hanime-app/releases/tag/v1.0.4)
byte-for-byte: an anonymous `curl` of
`/releases/latest/download/version.json` → its `apk_url` returned HTTP 200,
1,106,031 bytes and the same sha256, and `assets/index.html` inside the APK has
the same digest as `server/public/index.html`.

logcat from the same launch, on the same bytes:

```
I Shell   : loading bundled client at https://hanime.tv/
I Shell   : checking for updates, installed versionCode 5
I Shell   : update check: UP_TO_DATE 1.0.4
```

The update-source fix went through the same treatment (v6 / 1.0.5, `1107307`
bytes, sha256 `cb104ea4…`, fetched anonymously to confirm). A dead address was
planted **through the app's own settings screen**, whose field was read back out
of a `uiautomator` dump and contained
`https://github.com/Comitz134/hanime-app/releases/latest/download/version.json`
— the source in use, and not the app's own origin as it used to be. Then, on a
cold start and again from **⋮ → Check for updates**:

```
I ShellUpdater: asking http://10.0.2.2:8799wnload/version.json
W ShellUpdater: update check failed against http://10.0.2.2:8799wnload/version.json
W ShellUpdater: saved source failed (http://10.0.2.2:8799wnload/version.json), trying the built-in one
I ShellUpdater: asking https://github.com/Comitz134/hanime-app/releases/latest/download/version.json
I Shell   : update check: UP_TO_DATE 1.0.5
```

and the dialog that appeared named both addresses, the failed one first and the
built-in one second. (The address in that log is mangled because `input text`
typed into a field that was not empty — see §9. The app treated it exactly like
any other unreachable source, which is the point.)

- browse: 3,429 titles, 30 cards rendered
- playlists: 60 cards from the bundled dataset, no "index is empty" note
- playback: clock advanced 1.85 s → 4.01 s, 1280-wide frames, 44.6 s buffered
- signer: 64-hex `x-signature`, `x-time` in seconds
- HLS chain: playlist `#EXTM3U` → AES key 16 bytes (`30:31:32:33…`) →
  segment 1,941,104 bytes of `video/mp2t`
- updater: `update check: UP_TO_DATE 1.0.3` — the app reached GitHub, followed
  the `/releases/latest/download/` redirect to `release-assets.githubusercontent.com`,
  parsed the manifest and compared versions, all with no configuration
  (the same check reported `NOT_CONFIGURED` before the URL was compiled in)
- nav: switching is a swap, not a scroll — clicking **Library** left Browse at
  `display: none`, Library at `block`, and the strip marked `Library`
- nav, same tab: pressing the tab you are already on left the page exactly where
  it was (`27.6` in, `27.6` out); only a real view change returns to the top
- favorites on the device's own storage: the heart wrote
  `htv:library:v1` with the full record, flipped to `pressed: true`, and did
  **not** open the title sheet; the card reappeared in the Library view under
  its own name, and `#lib-warn` stayed hidden (the app's WebView does persist
  it, so nothing is session-only)
- playlists by name on the device: typing `pandora` in the catalog search filled
  the `#pl-hits` rail with the matching public playlist while `#total-count`
  read `0` — a name match, not a content match
- history: opening a title resolved a stream (`hasPlayer: true`) and recorded
  `Kanojo Saimin 1`
- server suite: `node --test "test/*.test.mjs"` → 102/102, exit 0 (the guards
  now also pin the pill-turns-into-search behaviour, that nothing calls
  `scrollIntoView`, that every `focus()` passes `preventScroll`, and that the
  library's storage calls stay inside `try`)

The update fix was then measured the other way round — not by planting a dead
address, but against the real published release. The emulator was left on
**v7 / 1.0.6** and cold-started with no configuration at all:

```
I Shell   : checking for updates, installed versionCode 7
I ShellUpdater: asking https://github.com/Comitz134/hanime-app/releases/latest/download/version.json
I Shell   : update check: AVAILABLE null
I Shell   : downloading update 1.0.7 (versionCode 8, 1108219 bytes)
I ShellUpdater: downloading https://github.com/Comitz134/hanime-app/releases/download/v1.0.7/app.hanime.shell-8.apk
I ShellUpdater: downloaded 1108219 bytes
I ShellUpdater: sha256 78fc01ac58bf7e9668e3f7e72f5515f217d13eb05c52225623abcacb8c19998d
I Shell   : update outcome: DOWNLOADED null
```

That is the whole feature in eight lines: the app asked the channel it shipped
with, the channel answered with 1.0.7, the APK came down from the release asset
itself, and the digest the manifest published is the digest of the bytes that
landed. With "install unknown apps" not yet granted the app correctly detoured
to that permission screen instead of the installer; once granted, the run
continued into Android's own *"Do you want to update this app?"* screen, reached
through the app's `FileProvider` URI, and stopped at the tap — which is a
person's to give.

Being already current is a message, not a dialog. On v8 / 1.0.7, ⋮ →
**Check for updates** logs `update check: UP_TO_DATE 1.0.7` and the platform adds
a single `Toast` window (bottom centre, `ty=TOAST`, presented by
`com.android.systemui`, with `TOAST_WINDOW` left at `default allow`) — sampled
out of `dumpsys window windows` every 250 ms while the check ran. No dialog
window is added on that path at all. One capture caveat, recorded rather than
papered over: this headless emulator's `screencap` does not composite
SystemUI's toast layer (the notification shade *is* captured, so it is the toast
path specifically), which is why the evidence here is the window list and the
log rather than pixels.

Favorites and history now have the restart measurement the earlier version of
this file said was missing. On v8 / 1.0.7, after `pm clear`, a title was hearted
through the real UI and a stream for it resolved:

```json
{ "favorite": { "slug": "kanojo-saimin-1", "pressed": "true", "sheetStayedClosed": true },
  "library": { "count": "1 favorite · 0 watched", "favTitles": ["Kanojo Saimin 1"] },
  "history": { "title": "Kanojo Saimin 1", "hasPlayer": true, "favPill": "In library" } }
```

then the app was force-stopped and started again:

```json
{ "version": "1.0.7",
  "stored": { "favorites": ["Kanojo Saimin 1"], "history": ["Kanojo Saimin 1"] },
  "libraryCount": "1 favorite · 1 watched", "warnHidden": true }
```

So the library is durable across a real app restart rather than merely written.
It does **not** survive the emulator being killed and cold-booted: after that,
the same read returned `stored: null` (see §9). `pm clear`, an install over the
top and a graceful restart are all fine; an abrupt kill plus a cold boot is
where the AVD loses it.

Probes live in `tools/` (`webview-probe.mjs` + `*-probe.js`); they need
`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, which the
build leaves enabled so the installed app stays diagnosable.

---

The 1.0.8 build (v9, `1,245,534` bytes, sha256 `f2e7e731…`) went on over 1.0.7
on the same emulator, and every new behaviour was measured through the WebView's
own devtools socket (`tools/webview-probe.mjs`) rather than by eye:

- **One check per cold start, and what happens when it fails.**
  `checking for updates, installed versionCode 9` appears exactly once; before
  the `checking` guard it appeared twice, because `onCreate` and the throttled
  `onResume` both asked.
- **Back closes what is open.** With a sheet open
  (`{"sheetOpen":true,"hasPlayer":true}`), `input keyevent 4` produced
  `{"sheetOpen":false}` while the app stayed resumed; a second press left the
  app (`topResumedActivity=…nexuslauncher/.NexusLauncherActivity`).
- **Playback no longer needs a CDN.** `fetch('hls.min.js')` → HTTP 200,
  `text/javascript`, `window.Hls` a function, `Hls.isSupported()` true,
  `Hls.version` `1.5.17` — served out of the APK by `ApiServer`.
- **Continue watching, end to end.** The media element's clock was shadowed on
  the instance (see §9 — this AVD's decoder never reports a duration), one
  `timeupdate` at t=137/d=600 stored `{"kanojo-saimin-1":{"t":137,"d":600,…}}`,
  closing the sheet re-stamped it, the library then drew **2** progress bars and
  a Continue watching rail of **1**, and reopening the title answered
  `#player-note = "resumed from 2:17"`.
- **Covers are on disk.** `Shell.cachedCover('kanojo-saimin-1')` →
  `/covers/kanojo-saimin-1.jpg`, written by
  `ShellCovers: stored cover for kanojo-saimin-1 (43004 bytes)`.
- **Export reaches the system picker.** `Shell.exportLibrary(…)` put
  `com.google.android.documentsui/…picker.PickActivity` on top. The write itself
  needs a tap on Save, so it is **not** asserted here — see §8.1.
- **Picture in picture.** `pageToldPip: true` (the page received
  `onPictureInPictureModeChanged`) and the task itself reported `mode=pinned`,
  `mWindowingMode=pinned`, a 595×335 window.
- **The status screen, including a real failure.** A `uiautomator` dump of the
  settings screen read back:

  ```
  Installed: 1.0.8 (versionCode 9)
  Update source: https://github.com/Comitz134/hanime-app/releases/latest/download/version.json
  Last check: Oct 7, 2026 5:11 AM · failed — Unable to resolve host …
  ```

  That failure is genuine — the check ran before the emulator's DNS came up — and
  it is precisely the case the screen exists for: the reason is on screen instead
  of in logcat.

That build is published. The channel was then read back the way any phone reads
it — an anonymous `curl` of `/releases/latest/download/version.json` returned
`version_code: 9`, `version_name: 1.0.8`, and an anonymous download of its
`apk_url` came back 1,245,534 bytes with sha256 `f2e7e731…`, byte-identical
(`cmp`) to the APK in `android/app/build/outputs/apk/release/`.

### 8.0.1 The menu and the themes (v10 / 1.0.9)

The 1.0.9 build (v10, `1,248,938` bytes, sha256 `b76ca343…`) replaced the
swipeable section strip with the three-line menu and added the appearance
settings, measured on the same emulator through the same devtools socket:

```json
{ "boot": { "menuHidden": true, "expanded": "false",
            "items": ["browse","genres","studios","playlists","library"],
            "stripGone": true, "theme": "dark", "accent": "amber" },
  "open": { "hidden": false, "expanded": "true", "rect": [40, 69, 304, 401] },
  "picked": { "view": "studios", "menuHidden": true, "current": ["studios"],
              "activeViews": ["studios"] },
  "backClosesMenu": true, "backNothingLeft": true,
  "light": { "theme": "light", "bg": "rgb(247, 247, 247)",
             "fg": "rgb(31, 31, 31)" },
  "lightRose": { "accent": "rose", "primary": "350 65% 46%" },
  "stored": "{\"theme\":\"dark\",\"accent\":\"amber\",\"motion\":\"full\"}" }
```

- The menu opens 69 px below the pill, 304×401, entirely inside the 390×844
  viewport: every section is one tap away instead of a drag away.
- `input keyevent 4` with the menu open closed it and left
  `topResumedActivity=…MainActivity` — the first back press dismisses the menu,
  it does not leave the app. (A second press with nothing open does.)
- The theme switch is visible in computed styles, not just the attribute:
  background `rgb(17, 17, 17)` → `rgb(247, 247, 247)`, foreground
  `rgb(31, 31, 31)`, and light+rose resolves the *combined* rule
  (`350 65% 46%`) rather than the dark pastel.
- A cold start on v10 logged `checking for updates, installed versionCode 10`
  exactly once → `asking …version.json` → `update check: UP_TO_DATE 1.0.9`.
- Published, then read back anonymously: `version.json` reported
  `version_code: 10` and the released APK was byte-identical (`cmp`) to the
  local build.

The suite grew to 109 with two guards: the navbar is a menu (strip gone, back
closes it, picking a section shuts it), and appearance persists under
`htv:prefs:v1` with light/accent/motion rules present in the stylesheet.

### 8.0.2 Channels and the download notification (v11 / 1.0.10)

Both spec gaps closed and measured on the emulator, against a local update
source that published a fake `version_code: 99` with the real APK's bytes:

- **No notification, no permission, no noise when current.** On a fresh install
  the cold start logged `checking … versionCode 11` → `UP_TO_DATE 1.0.10` and
  `dumpsys notification` held zero records for the package.
- **The permission is asked at the only moment it matters.** With the mock
  source saved and the check returning `AVAILABLE`, the system dialog appeared
  — `Allow hanime to send you notifications?` — requested at download time
  rather than on first launch. Tapping Allow recorded
  `POST_NOTIFICATIONS: granted=true`.
- **The notification exists and carries the install handoff.** After grant, a
  second check downloaded and verified (sha256 matched), then logged
  `notified: update 9.9.9 ready to install`; `dumpsys notification` showed
  `pkg=app.hanime.shell … channel=updates … importance=4` with a
  `contentIntent` PendingIntent.
- **Tap hands off to the existing install flow, even after process death.**
  Delivering the intent extras (`INSTALL_READY`) to the running app
  (`onNewIntent`) and to a cold, freshly force-stopped process (`onCreate`)
  both produced the same dialog: *Version 9.9.9 is available (you have
  1.0.10) … What changed: notification exercise* with LATER /
  DOWNLOAD AND INSTALL — the dialog the app already had, reached through the
  record kept in the updater's own preferences.
- **The channel switch lands on a published manifest.** Tapping BETA on the
  settings screen turned the status line to `Channel: beta` with the
  `channel-beta/version.json` address; the next cold start logged
  `asking …/releases/download/channel-beta/version.json` →
  `UP_TO_DATE 1.0.10`. (The first attempt hit a transient 30 s read timeout —
  reported by name in the log and on the status screen, exactly like any other
  source failure; the retry answered. The URL itself was also fetched
  anonymously: HTTP 200, same manifest and sha256 as stable.)
- **An up-to-date check retracts the claim.** With `notifications=1` in the
  tray, clearing the override and cold-starting logged `UP_TO_DATE` and the
  count went `1 → 0`.
- Tapping STABLE restored `Channel: stable` and a final cold start asked the
  stable URL → `UP_TO_DATE 1.0.10`, tray empty.

Suites: `node --test "test/*.test.mjs"` → 112/112 and
`gradle :app:testReleaseUnitTest` → 11/11, both exit 0. The node suite gained
the channel contract: the beta URL is a rolling asset, a dry run hands out
both channel URLs, and **the URL compiled into `build.gradle` must equal the
one `publish-github.mjs` refreshes** — it caught its own regex before it
caught anything else.

### 8.1 What is not verified here

Stated plainly, because the rest of this section is measured:

- **The import half of the file round trip.** The picker opens on both sides; the
  bytes were not carried through a Save tap and back.
- **In-app update install completing.** Download, verification and the installer's
  consent screen were all observed, but an abrupt emulator kill landed mid-install
  both times it was attempted. The mechanics are unchanged from 1.0.7.
- **A real, decoded playback through the new resume path** (see §9).

---

## 9. Gotchas already hit here

- **A WebView follows a redirect without telling the app the new URL.** Only the
  committed URL arrives, through `doUpdateVisitedHistory`/`onPageFinished` — the
  URL the navigation started from is never offered to `shouldInterceptRequest`.
  So anything that must catch a redirect target (the MyAnimeList sign-in coming
  back to `https://hanime.tv/?code=…`) has to look at the commit, not at the
  request, or it will silently fetch the page from the network. See §7.3.
- **`??=` in the vendored signer** (Chrome 85+) fails to parse on Chromium 83 →
  no signature, every video 502s. Regenerate with `make-signer-asset.mjs`;
  guarded by a test.
- **WASM feature level** — the module also needs a modern engine
  (`wasm function signature contains illegal type` on Android 11's WebView).
  Test on a current image.
- **`canPlayType('application/vnd.apple.mpegurl')` lies**: this WebView answers
  `maybe` while implementing no demuxer, so the element sits at `readyState 4`
  with zero frames. The client now prefers Media Source Extensions and leaves
  native HLS to browsers without MSE (iOS).
- **Relay links must carry the `/relay?` prefix.** A bare `u=…&s=…` resolves
  against the page root and falls through to the client's catch-all route,
  which returns HTML — the player then reports a decode error.
- **`onCreate` must always load the page.** `restoreState` after the activity is
  recreated can leave a blank WebView, because there is no session to restore.
- **Emulator churn**: killed instances leave `hardware-qemu.ini.lock` behind and
  `adb shell` can then block forever — `adb kill-server` and clear the lock.
  Guard adb calls with `timeout`.
- **`server/README.md` still describes `mobile/` as a Flutter app**; Flutter was
  never installed here and the shell decision predates this work.
- **A saved update source outlives an app update.** SharedPreferences survive an
  install over the top, so a dead address keeps failing after the fix is
  installed. That is why the fallback in §6.1 exists at all.
- **`uiautomator dump` paths need `MSYS_NO_PATHCONV=1`** in Git Bash: without
  it `/sdcard/ui.xml` becomes `C:/Program Files/Git/sdcard/ui.xml` on the way to
  the device, the dump "succeeds" somewhere useless, and the pull fails.
- **`adb shell input text` appends.** It types into whatever has focus and does
  not replace: clear the field first (a device-side
  `for i in $(seq 1 70); do input keyevent 67; done`), or you will silently test
  a different address than you meant to.
- **An abruptly killed AVD loses app state; a graceful restart does not.** The
  emulator is killed by something outside this repo every few minutes. After a
  kill plus a cold boot the installed APK is still there but its `localStorage`
  is not: a library that read back fine across `am force-stop` → `am start` on
  the same boot came up empty after that. Durability has to be measured across a
  *restart*, and anything that must survive the reap has to be installed and
  asserted inside one boot.
- **On Android 12+ `dumpsys activity activities` no longer reports
  `mResumedActivity`.** It reports `topResumedActivity`, and it also prints a
  `Resumed activities in task display areas` block. Grepping for the old field
  name silently matches nothing, which reads exactly like "the app is gone" —
  that ambiguity cost a round of doubt about whether the second back press had
  worked. `dumpsys window | grep mCurrentFocus` is the second opinion.
- **`pidof` is not evidence that the app is still on screen.** It keeps
  answering for a second or two after `finish()`; the resumed activity is the
  evidence.
- **A headless AVD cannot be used to test playback, and that is not a bug in
  the app.** In `shell35` the HLS attach resolves a source and creates the
  `<video>`, but the element never reaches `loadedmetadata` (`duration` NaN,
  `currentTime` stuck at 0) — `Hls.isSupported()` is `true` and MSE exists, so
  the limit is the emulator's decoder, not the media stack. Anything that waits
  on the media clock has to be driven by shadowing `duration`/`currentTime` on
  the element instance, or measured on a real device.
- **WebView devtools is the only practical way to assert page state on the
  emulator.** `adb forward tcp:9222
  localabstract:webview_devtools_remote$(pidof app.hanime.shell)` plus
  `tools/webview-probe.mjs` evaluates in the live page and prints JSON; the
  socket closes (exit 5) the moment the page navigates or the app is killed, so a
  closed socket mid-sequence means "re-check the device", not "re-check the
  test". `WebView.setWebContentsDebuggingEnabled(true)` is compiled into release
  builds on purpose, and this is why.
- **`adb shell input keyevent 4` exercises the back path, but only if the flag
  and the dispatcher agree.** With `enableOnBackInvokedCallback="true"`, API 33+
  routes back to `OnBackInvokedDispatcher` and never to `onBackPressed`, so an
  app that opts in *must* register a callback or back stops working entirely. The
  shell registers one and keeps `onKeyDown` for older releases; both land in
  `handleBack()`, and a 120 ms debounce collapses the same press arriving twice.
