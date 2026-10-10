# The shell — a Nuvio Desktop fork, pointed at this catalogue

This directory holds the desktop app: **NuvioDesktop's own UI and player
(Kotlin + Compose Multiplatform), rewired to serve this project's library.**
The window is Nuvio's — hero carousel, icon rail, Home/Search/Library tabs,
detail pages, stream picker, and the native mpv video surface. What it *shows*
is `server/`'s catalogue, because the six files in `overrides/` repoint the app
at `http://127.0.0.1:8787` instead of Nuvio's Supabase account backend.

It is deliberately not a lookalike: it is the upstream app, with the smallest
set of changes that makes it ours. Reimplementing that UI in this repo would
have meant maintaining a pale copy of 100k+ lines of Compose.

```
shell/
  overrides/            our changes, laid over the upstream tree at their real paths
  sync.sh               fetch upstream at the pinned commit, apply overrides, print how to run
  NOTICE.md             what upstream is, which commit, what we changed and why
  LICENSE-NuvioDesktop-GPL-3.0   upstream's license — read it before shipping anything
```

## Run it

Requirements: **JDK 17** (`JAVA_HOME` set), **Node 20+** on `PATH`. Windows,
Linux and macOS are all supported by upstream; the player runtimes for all three
are checked into the upstream repository, so a first fetch is a few hundred MB.

```bash
./shell/sync.sh                 # clone upstream @ pinned commit into shell/.upstream, apply overrides
cd shell/.upstream
JAVA_HOME=/path/to/jdk17 ./gradlew :composeApp:run
```

The window starts this repo's server itself (see `overrides/.../HanimeServer.kt`):
it looks for `server/src/server.mjs` relative to the working directory, starts it
with `node`, waits until it actually answers, and shuts down **only** the process
it started. If something is already answering on `127.0.0.1:8787` — a dev server
in a terminal, the phone's shell — it is left alone.

To build an installable bundle instead of running from Gradle:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew :composeApp:packageDistributionForCurrentOS
# -> composeApp/build/compose/binaries/main/{msi,dmg,deb}/...
```

### Smoke the player without clicking

Upstream ships a harness that boots straight into the native player with one URL
— useful to prove decoding works on a machine, and what was used to verify this
fork:

```bash
./gradlew :composeApp:run -Pnuvio.desktop.smokePlayerUrl="http://127.0.0.1:8787/<a stream url>"
```

## What we changed (and what that costs)

| file | change |
| --- | --- |
| `HanimeServer.kt` *(new)* | brings up `server/`, waits for a real answer, stops only its own child |
| `Main.kt` | calls it on boot; window title `Hanime`; opens past Nuvio's account gate |
| `AddonPlatform.desktop.kt` | seeds this server's addon bridge as the built-in source |
| `DesktopStorage.kt` | profile/cache directories named `Hanime`, so an installed Nuvio on the same machine never shares state |
| `NetworkStatusRepository.kt` | "servers reachable" probes this project's `/api/videos`, not Nuvio's Supabase |
| `gradle.properties` | heap lowered to build on a 16 GB machine |

The seeded addon (`server/src/addon.mjs`) is what feeds the shell its shelves and
streams — Stremio addon protocol, which is the interface Nuvio's UI already
speaks.

## Licensing — read this before you publish a build

- Upstream **NuvioDesktop is GPL-3.0**. This fork is a derivative work, so
  anything you distribute from it (installers, store listings, app bundles)
  must be **GPL-3.0**, with source available, and must keep upstream's notices.
  `LICENSE-NuvioDesktop-GPL-3.0` is upstream's own file, kept verbatim.
- Keep `NOTICE.md` next to any distribution, and don't strip Nuvio's copyright
  headers from the files in `overrides/` or anywhere else.
- The upstream project and this fork are unaffiliated; the name is theirs, and
  nothing here claims to be an official Nuvio build.

## Why an overlay instead of vendoring the whole tree

`composeApp/src/desktopMain/native/windows/runtime/libmpv-2.dll` is **110 MB**
and tracked upstream. GitHub refuses pushes containing files over 100 MB, so a
plain copy of the tree could never be pushed to this repository. An overlay
keeps our changes reviewable in diff-sized files while the 300+ MB of upstream
runtimes stay where they already live (in upstream's own history).

## Known cosmetics

The window title says Hanime, but the app icons and the About screen are still
upstream's. Swapping the icon set is a resource change in `composeApp/src/**/composeResources`
if you want it.
