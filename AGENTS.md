# Working in this repository

## Standing instructions

**Refresh the installed desktop app after every change.**

The desktop app (SEKAI) is installed on this machine at
`C:\Users\micro\AppData\Local\Programs\SEKAI`, and it is a jpackage *app-image*:
a folder, not something an installer keeps in step. So the installed app only
changes if it is rebuilt and re-mirrored, and the rule is that it is never left
behind the tree. After every change — source, server, build config, or
documentation — run:

```bash
./shell/install-desktop.sh
```

It applies `shell/overrides/` over the build tree (`shell/.upstream`), runs
`:composeApp:createDistributable`, mirrors the result into the install folder,
and verifies the copy against the build file for file. `--stop` stops a running
window first (its jars are locked); `--no-build` installs the last build. It
needs JDK 17 and takes seconds when the build is already warm.

Two things that script now insists on, both learned the hard way. A build that
calls itself something other than what
`overrides/composeApp/Configuration/DesktopVersion.properties` declares is a
failed install, not a warning: the version is read at configuration time, Gradle
caches configurations here, and an app stamped with the previous release's
number offers itself the same update forever. And a `node/` folder found in the
install is left alone rather than mirrored away — that runtime belongs to a
published release (a window that has updated itself once has one), not to the
Gradle output, so it is excluded from the mirror *and* from the file-for-file
check.

Details, including what the script deliberately does not regenerate, are in
[`shell/README.md`](shell/README.md).

That script installs a *build*. A reader's copy is not installed that way at
all — the window checks this repository's releases on every start and replaces
its own folder when a newer one is published. So a change reaches a reader by
bumping `shell/overrides/composeApp/Configuration/DesktopVersion.properties` and
running `cd server && node scripts/publish-desktop.mjs --pack`, which is
documented in [`shell/README.md`](shell/README.md) along with why those releases
are published as prereleases and tagged `desktop-v<version>`.

**Desktop tags are namespaced, and the phone's are not.** The two clients
version themselves independently, so a bare `v<version>` tag belongs to
whichever line reached that number first: phone 1.0.9 already existed when the
desktop line reached 1.0.9, and `publish-desktop.mjs` merged its zip and
`desktop.json` into that phone release and replaced its notes. Desktop releases
are tagged `desktop-v<version>` for that reason, and the window strips the
prefix before comparing versions (`releaseVersionTag` in the updater). The
prerelease flag is a separate protection: `/releases/latest` is the phone's
manifest URL, and it resolves by position, not by tag.

**The phone app is `shell-mobile/`, and `android/` is retired.** The Kotlin
fork is the one that ships; the WebView shell is frozen at 1.0.42 (see
[`android/DEPRECATED.md`](android/DEPRECATED.md)). A phone release still takes
two publishes, and neither script is a release on its own: `publish-apk.mjs`
writes the manifest this checkout's own server answers with
(`/api/app/version`) and copies the APK into `server/data/apk/` — reachable from
a phone only while this PC is up and only if the phone is pointed at it. The
release a phone actually updates from is the GitHub one, and that is
`publish-github.mjs`: it uploads the APK and `version.json` to a
`v<versionName>` release (and refreshes the rolling `channel-beta` prerelease).
Running the first without the second leaves every installed phone on the
previous build while both logs read as published — it has happened.

The version is read from the fork's own xcconfig, not passed by hand:
bump `CURRENT_PROJECT_VERSION` and `MARKETING_VERSION` in
`shell-mobile/overrides/iosApp/Configuration/Version.xcconfig`. The published
build keeps the **shell's** package id and signing key (the fork's
`build.gradle.kts` sets `app.hanime.shell` for the release variant), which is
what makes `/releases/latest` the old app's upgrade path; desktop releases stay
prereleases so they never take that slot. The release key lives in
`android/keystore.properties` (gitignored) and is handed to Gradle through the
environment, so no password enters a tracked file:

```bash
ks=android/keystore.properties
export NUVIO_RELEASE_STORE_FILE="$PWD/android/$(sed -n 's/^storeFile=//p' "$ks" | tr -d '\r')"
export NUVIO_RELEASE_STORE_PASSWORD="$(sed -n 's/^storePassword=//p' "$ks" | tr -d '\r')"
export NUVIO_RELEASE_KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$ks" | tr -d '\r')"
export NUVIO_RELEASE_KEY_PASSWORD="$(sed -n 's/^keyPassword=//p' "$ks" | tr -d '\r')"
(cd shell-mobile/.upstream && "$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :androidApp:assembleFullRelease --no-daemon --console=plain)

cd server
node scripts/publish-apk.mjs ../shell-mobile/.upstream/androidApp/build/outputs/apk/full/release/app-universal-release.apk --notes "..."
node --env-file=../.env scripts/publish-github.mjs --repo Comitz134/hanime-app --notes "..."
```

The token lives in the checkout-root `.env` (`GITHUB_TOKEN`), which
`publish-desktop.mjs` loads itself and `publish-github.mjs` does not — hence the
`--env-file`. Verify the phone's own view afterwards:
`https://github.com/Comitz134/hanime-app/releases/latest/download/version.json`
must name the version code just built.

## The films & series area speaks TMDB

The `/api/fmovies/*` route family is the contract every client answers — the
web bundle, the addon, the desktop window and `android/…/Fmovies.java` — so the
prefix keeps its old name even though the source behind it is no longer
f-movies.org. `server/src/fmovies.mjs` reads TMDB's public API (the key comes
from `TMDB_API_KEY`, else the one cinehd.vc's own bundle ships) and offers
cinehd's six doors — Max, Vidpro, V2, Premium, 4K, Vidfast — knocking on each
so a dead one is a disabled button rather than a black frame.

**Which door the in-app player uses.** The app's own player cannot frame an
embed — it needs a playlist URL — so that walk has its own order: **Vidpro**
first (`IN_APP_FIRST` in `server/src/fmovies.mjs`), then cinehd's order behind
it. **The films stream list offers nothing else**: the doors used to travel as
`externalUrl` entries for the titles the resolver could not reach, and the
reader asked for an app that never hands them a browser tab, so every entry is
now `/play/films/…` — one per door that answered the knock
(`Hanime · in-app · Vidfast`), each carrying `door=<label>`, and
`/play/films/:type/:slug?door=` only *reorders* the walk: a door named and then
unable to resolve falls through to the others, so a stale choice is slower, not
broken.

Measured with the resolver on 2026-10-17, and the reason the preference is not
arbitrary: Vidpro resolves in ~1.6 s and Vidfast in ~2 s; **Max and 4K never ask
for a playlist at all** in a headless browser, so their in-app entries are a
detour through the fallback. Two things make Vidpro work and are easy to break
again: its player serves the whole playlist chain **base64-encoded** (the relay
decodes it — `decodePlainPlaylist` in `hls.mjs`) and it never requests a URL
ending in `.m3u8` (the resolver recognises a manifest **by content** —
`playlistBody` in `browser.mjs`, reading the bodies of page-shaped XHRs only,
never media).

**The two backends are not in step yet.** Node answers TMDB + the six doors;
the Java twin still scrapes f-movies.org. Both answer the same shapes, and the
search response's `source` field is what the client prints (the Java side omits
it, so the phone still says `f-movies` instead of inheriting the new label).
Porting `Fmovies.java` is the next step for the phone, and `FmoviesTest` moves
with it.

## Where things are

| path | what it is |
| --- | --- |
| `server/` | the Node catalogue server and the web client (`client/` builds `public/index.html`) |
| `shell/` | the desktop app: `overrides/` is this fork's slice of NuvioDesktop, `sync.sh` fetches upstream and applies it, `install-desktop.sh` installs the result |
| `shell-mobile/` | the phone app: `overrides/` is this fork's slice of NuvioMobile (GPL-3.0, see its `NOTICE.md`), `sync.sh` fetches upstream and applies it, `install-mobile.sh` builds and installs on a phone |
| `android/`, `mobile/` | the retired phone clients — `android/` is frozen at 1.0.42, see its `DEPRECATED.md` |
| `server/src/fmovies.mjs` | the films & series area: TMDB for catalog, details and episodes, the six cinehd doors for playback — see the note below |
| `server/scripts/publish-desktop.mjs` | builds, zips and publishes a desktop release (the window's update source) |
| `server/scripts/publish-apk.mjs`, `publish-github.mjs` | the same for the phone app |
| `docs/loading-surfaces/` | screenshots of every screen the client draws while it waits, taken from the client's own code (`node docs/loading-surfaces/build.mjs`) — see its README |
| `tools/update-fixture.mjs` | a loopback stand-in for the release API and its downloads, for exercising the updater without publishing |
| `tools/click-at.ps1` | clicks at a point measured from a window's own corner — how the update banner's button is pressed on a build that has no harness for it |
| `README-ANDROID-BUILD.md` | the long-form build and release documentation |

<!-- BEGIN brain.md -->
## Project Brain

This project keeps a **Project Brain**: a persistent memory layer of its durable decisions, requirements, and constraints. Read `./BRAIN.md` for the full read/write contract.

The `brain` CLI is not guaranteed to be on `PATH`. From the project root, invoke it as `node <brain-page-skill-dir>/bin/brain.mjs <subcommand> [flags]`, resolving `<brain-page-skill-dir>` to the installed `brain-page` skill directory.

Maintain the brain as part of normal coding work — not as a separate task. While discussing or implementing features:
- **Start of a task:** load relevant context with the `brain` CLI (`list-pages`, `read-page`, `read-root`). Prefer a narrow read over scanning everything.
- **When a decision, requirement, constraint, or durable insight settles** (in chat or while coding): capture it immediately via the `brain` CLI. Do not wait to be asked and do not batch it for later.
- **Pure implementation with no new decision:** do not write to the brain.
- **When overturning a prior conclusion:** update the page (`update-truth` and/or `append-timeline` with `kind: reversal`, or `archive-page`).
- Only store what will still matter in six months and is hard to reconstruct from the code alone.
- Never hand-edit brain files. If a brain MCP server is connected and authenticated, prefer it; otherwise use the `brain` CLI.

The brain skills (`brain-setup`, `brain-page`, `brain-ingest`, `brain-bootstrap`) are installed in your global skills directory. To scaffold a new project, run `node <brain-page-skill-dir>/bin/brain.mjs init` from its root.

If native notes/history are available, keep relevant brain page IDs and unresolved task state in notes; search history for earlier task evidence. After context rollover, re-read relevant pages through the CLI for current project facts. Do not copy task history into the brain.
<!-- END brain.md -->
