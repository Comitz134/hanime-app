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
are published as prereleases.

**A phone change is two publishes, not one.** `publish-apk.mjs` writes the
manifest this checkout's own server answers with (`/api/app/version`) and copies
the APK into `server/data/apk/` — reachable from a phone only while this PC is
up and only if the phone is pointed at it. The release a phone actually updates
from is the GitHub one, and that is `publish-github.mjs`: it uploads the APK and
`version.json` to a `v<versionName>` release (and refreshes the rolling
`channel-beta` prerelease). Running the first without the second leaves every
installed phone on the previous build while both logs read as published — it has
happened, so neither script is a release on its own:

```bash
cd server
node scripts/publish-apk.mjs ../android/app/build/outputs/apk/release/app-release.apk
node --env-file=../.env scripts/publish-github.mjs --repo Comitz134/hanime-app
```

The token lives in the checkout-root `.env` (`GITHUB_TOKEN`), which
`publish-desktop.mjs` loads itself and `publish-github.mjs` does not — hence the
`--env-file`. Verify the phone's own view afterwards:
`https://github.com/Comitz134/hanime-app/releases/latest/download/version.json`
must name the version code just built.

## Where things are

| path | what it is |
| --- | --- |
| `server/` | the Node catalogue server and the web client (`client/` builds `public/index.html`) |
| `shell/` | the desktop app: `overrides/` is this fork's slice of NuvioDesktop, `sync.sh` fetches upstream and applies it, `install-desktop.sh` installs the result |
| `android/`, `mobile/` | the phone clients |
| `server/scripts/publish-desktop.mjs` | builds, zips and publishes a desktop release (the window's update source) |
| `server/scripts/publish-apk.mjs`, `publish-github.mjs` | the same for the phone app |
| `tools/update-fixture.mjs` | a loopback stand-in for the release API and its downloads, for exercising the updater without publishing |
| `tools/click-at.ps1` | clicks at a point measured from a window's own corner — how the update banner's button is pressed on a build that has no harness for it |
| `README-ANDROID-BUILD.md` | the long-form build and release documentation |
