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
│   ├── index.html         the web client (byte-identical to server/public/)
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
| `/api/session`, `/api/app/version` | session / update source (see §6) |
| fonts, `hls.js` from jsdelivr, image CDN | left to the WebView (returns null) |

Two consequences worth stating:

- The page's origin is `https://hanime.tv`, which is exactly the origin the
  upstream auth services echo in `access-control-allow-origin`, so nothing
  fights CORS.
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

Required after changing the client or the vendored signer. Two guards in
`server/test/web-client.test.mjs` fail if you forget.

```bash
cd hanime-app

# 1. the client — must stay byte-identical to what the server serves
cp server/public/index.html server/public/app.webmanifest android/app/src/main/assets/

# 2. the signer — downgrades 3 uses of ??= (Chrome 85+) for older WebViews
node android/tools/make-signer-asset.mjs

# 3. the playlist subset — top 250 crawled playlists with their entries
node android/tools/make-playlists-asset.mjs
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
package: name='app.hanime.shell' versionCode='2' versionName='1.0.1'
sdkVersion:'26'   targetSdkVersion:'34'
launchable-activity: name='app.hanime.shell.MainActivity'
permissions: INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES
Verifies / v2 scheme: true / signer CN=hanime shell
1,101,831 bytes
```

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

---

## 6. Updates

The shell checks on **every cold start**. Where it looks is configurable:

- build time: `-PupdateUrl=…` → `BuildConfig.UPDATE_URL`
- runtime: **⋮ menu → Update source** (stored in `SharedPreferences`)

Both accept either shape:

| Value | Requested URL |
|---|---|
| `https://example.com/updates/app.json` | that file directly |
| `https://example.com/updates` | `https://example.com/updates/api/app/version` |

Empty means **not configured**, which the app reports in ~100 ms instead of
dialling a dead host for ten seconds.

Flow: detect (`version_code` strictly greater) → **auto-download** → verify
sha256 **and** byte count → hand to Android's installer through the FileProvider
URI. Nothing installs silently; the system consent screen always appears. A
mismatched checksum deletes the file and aborts.

### 6.1 Publishing the update source to GitHub Releases

The app's check has to answer from a phone that is nowhere near your machine,
so a GitHub Release is the update source: two assets on a `v<versionName>`
release, the APK and `version.json`.

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
outlive its APK. Then paste the printed `version.json` URL into the app's
**⋮ menu → Update source** once; every later release is found from there.

The version number is read from `data/apk/release.json`, which
`publish-apk.mjs` wrote by parsing the APK with `aapt2` — it is never passed in
by hand, so GitHub and your server cannot disagree about what is current.

Note the two publishers deliberately differ in one field: `versionPayload()`
serves a **relative** `apk_url` (one server, reached as `10.0.2.2` from an
emulator and a LAN address from a phone), while the GitHub manifest serves an
**absolute** one (a release asset has exactly one address, and the manifest is
fetched from a different host than it serves).

---

## 7. Verified on a device

Measured on the `shell35` emulator (Android 15, WebView 124) with **no Node
process running anywhere**:

```json
{ "total": "3429", "gridCards": 30, "plCards": 60, "notes": [],
  "playback": { "mse": true, "t1": 0.133, "t2": 5.53, "advanced": true,
                "w2": 1280, "buffered": 23.59, "paused": false,
                "mediaError": null } }
```

- browse: 3,429 titles, 30 cards rendered
- playlists: 60 cards from the bundled dataset, no "index is empty" note
- playback: clock advanced 0.13 s → 5.53 s, 1280-wide frames, 23.6 s buffered
- signer: 64-hex `x-signature`, `x-time` in seconds
- HLS chain: playlist `#EXTM3U` → AES key 16 bytes (`30:31:32:33…`) →
  segment 1,941,104 bytes of `video/mp2t`
- updater: `update check: NOT_CONFIGURED` in ~100 ms with no source set
- server suite: `npm run selftest` → 95/95, exit 0

Probes live in `android/tools/` (`webview-probe.mjs` + `*-probe.js`); they need
`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, which the
build leaves enabled so the installed app stays diagnosable.

---

## 8. Gotchas already hit here

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
