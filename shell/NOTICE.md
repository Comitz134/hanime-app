# NOTICE — attribution for the desktop shell

The desktop app in this repository is a fork of an existing GPL-3.0 project.
This file is the attribution required by that license. Keep it with any
distribution of the shell.

## Upstream

| | |
| --- | --- |
| Project | **NuvioDesktop** — <https://github.com/NuvioMedia/NuvioDesktop> |
| Owner | NuvioMedia and its contributors |
| License | GNU General Public License v3.0 (`LICENSE-NuvioDesktop-GPL-3.0`, kept verbatim) |
| Branch | `Dev` |
| Pinned commit | `bc4566a5c474f5f90c6dc40062723f37f823c59e` (2026-10-09) |

## What this fork changes

Six files, copied verbatim into `overrides/` at their upstream paths by
`sync.sh`. Everything else in the running app is upstream's, unmodified.

| file | why |
| --- | --- |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/HanimeServer.kt` | new file: starts and supervises this repo's `server/` |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/Main.kt` | calls it on boot, brands the window, opens past the account gate |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/addons/AddonPlatform.desktop.kt` | seeds this server's addon bridge as the built-in source |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/core/storage/DesktopStorage.kt` | `Hanime` profile/cache directories, so an installed Nuvio keeps its own state |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/core/network/NetworkStatusRepository.kt` | probes this project's server instead of Nuvio's Supabase endpoint |
| `gradle.properties` | heap size lowered to build on a 16 GB machine |

## What was removed

Nothing. No upstream functionality is deleted; the account/sync features simply
never authenticate here, because this fork does not talk to Nuvio's backend.

## Consequences of the license

GPL-3.0 is copyleft: builds distributed from this fork must be GPL-3.0 too, with
the complete corresponding source available to whoever receives them, and with
these notices intact. If that is not acceptable for how you plan to ship an
installer, the alternative is to not distribute the shell build (keep it a
local tool) or to reimplement the UI — neither of which this repository forces
on you.
