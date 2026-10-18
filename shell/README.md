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
  install-desktop.sh    rebuild the app and refresh this machine's installed copy
  NOTICE.md             what upstream is, which commit, what we changed and why
  LICENSE-NuvioDesktop-GPL-3.0   upstream's license — read it before shipping anything
```

The app calls itself **SEKAI**: the window title, the installer's package and
menu names, the taskbar icon, the launcher icons and the sidebar wordmark are
this project's. The artwork for all of it is generated from one source image —
see `_mf/make-sekai-icons.py` and `NOTICE.md`.

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

On Windows without the **WiX toolset** installed, that installer step cannot run
(jpackage needs `candle`/`light` for `msi`, and the same for an `exe`
installer). The portable build needs neither — it produces the launcher itself,
with the JVM, the player bridge and libmpv inside:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew :composeApp:createDistributable
# -> composeApp/build/compose/binaries/main/app/SEKAI/SEKAI.exe   (246 MB, folder must stay together)
```

### Keep the installed app current — after every change

The installed SEKAI is that app-image, copied to
`%LOCALAPPDATA%\Programs\SEKAI` (the folder the Start-menu shortcut launches).
It is a plain folder — no installer is involved in updating it, and none is
involved in *not* updating it, which is the trap: a change built into
`composeApp/build/...` leaves the app a reader actually opens running last
week's code. One command closes that gap:

```bash
./shell/install-desktop.sh          # apply overrides → createDistributable → mirror into the install
./shell/install-desktop.sh --stop   # the same, stopping a running window first (its jars are locked)
./shell/install-desktop.sh --no-build   # install the last build as it stands
```

What it does, in order: lays `overrides/` over the build tree, runs
`:composeApp:createDistributable`, and mirrors the result into the install with
`robocopy /MIR` — a mirror, not a copy, because the app's main jar and its skiko
runtime carry their content hash in their filename and change name every build,
so the previous pair has to be *deleted*, not overwritten. It refuses to touch a
folder not named `SEKAI` (a mistyped `SEKAI_INSTALL_DIR` must not become a
deletion), refuses to run against a live window unless asked to stop it, and
finishes by `diff -rq`-ing the installed folder against the build so the command
cannot report success on a partial copy. It prints the installed version, the
main jar's hash and the commit it was built from.

The server travels inside that jar (`:composeApp:syncHanimeServer`), so the
installed app carries this checkout's `server/` and its `.env` — including a
server-side fix, which no client rebuild would otherwise deliver. What it does
*not* regenerate is `server/public/index.html`, the built web client: that is
`cd server && npm run build:client`, and the script warns when it is older than
the sources it is built from (`server/test/web-client.test.mjs` fails on that
drift, so the suite is the real guard).

A Gradle build of this tree also needs a `local.properties` next to
`settings.gradle.kts` — the runtime-config task declares it as an input, and
without the file Gradle fails before compiling anything. An Android SDK path is
enough:

```properties
sdk.dir=F:\atc\android-sdk
```

## The window updates itself

Upstream ships a whole in-app updater — a banner, release notes, a download with
progress, and a per-platform install step (`features/updater/`, `expect/actual`
per host). This fork keeps it and repoints it, because the parts that are
NuvioMedia's are exactly the parts that are not ours:

| what | fork's answer |
| --- | --- |
| where a check looks | `Comitz134/hanime-app` releases, tagged `desktop-v<version>` (`AppUpdaterPlatform.desktop.kt`) |
| what it installs | `SEKAI-Windows-x64-<version>.zip` — the portable tree |
| how it installs | extract beside the install folder, then a batch file mirrors it in place after the window exits |
| what version it is | `overrides/composeApp/Configuration/DesktopVersion.properties` |

**Desktop releases live in their own tag namespace: `desktop-v<version>`.**
This repository is also where the phone app is published, and the phone tags its
releases `v<version>` from its own version name. The two clients number
themselves independently, so the bare tag is a name both lines can reach — desktop
1.0.9 and phone 1.0.9 are the same string — and the second publish to land there
quietly merged into the first one's release page: its zip and `desktop.json`
beside the phone's APK, its notes replacing the phone's. The prefix makes that
impossible by construction. The window's check reads tags through
`releaseVersionTag`, which drops the prefix before anything compares versions:
`desktop-v1.0.10` parses as no version at all, so an unstripped tag would read as
"nothing new" for every release this project ever publishes.

**Releases are also published as *prereleases*, and that is not a label of
quality.** `releases/latest` is a position rather than a name: it resolves to the
newest non-prerelease release whatever it is called, and the phone reads its
manifest from there — a normal release of the desktop app would become "latest"
and hand the phone a manifest with no APK in it. A prerelease leaves
`/releases/latest` pointing at the phone's release while the window, which lists
prereleases, still finds its own. For the same reason a check considers *every*
release it is given rather than only the newest one (`AppUpdaterRepository`): the
newest release in a shared repository is usually the phone's, and it carries
nothing the window can install.

What a release carries is the jpackage app-image — the same folder
`install-desktop.sh` mirrors — plus a **Node runtime** (`<app>/node/node.exe`),
because the window is a front end to `server/` and a reader who downloads this
has not installed Node. `HanimeServer` looks for that runtime before it looks on
`PATH`, so the app that ships works with nothing installed. There is no MSI: the
build produces an app-image and an installer would need a WiX toolset on the
machine that builds it, so a release is a zip and updating is replacing a
folder.

Publishing one:

```bash
cd server
export GITHUB_TOKEN=… GITHUB_REPOSITORY=owner/repo
node scripts/publish-desktop.mjs --pack            # build → zip → upload
node scripts/publish-desktop.mjs --pack --dry-run  # build and zip, upload nothing
node scripts/publish-desktop.mjs                   # upload the last zip again
```

`--pack` lays this fork's `overrides/` over the build tree, runs
`:composeApp:createDistributable`, copies a Node runtime in beside the launcher,
and zips the result into `server/data/desktop/`. The version it publishes comes
from `DesktopVersion.properties` — not from the built app's own metadata, which
jpackage rewrites into an installer-shaped number that the app never compares
itself against. Bump that file, publish, and every installed window offers the
new version the next time it starts.

The published version has to be the version the *app* carries, and those two
come apart easily enough to be worth a guard. Gradle reads
`DesktopVersion.properties` while it *configures*, and this build turns the
configuration cache on — so a cached configuration stamps the previous release's
number into a freshly built app. Such an app calls itself 1.0.0 inside a v1.0.1
release, sees v1.0.1 as newer on every start, and downloads itself in a loop
that never ends. So the pack builds with `--no-configuration-cache` and settles
the release's number against the number the built app actually carries, refusing
to publish a release whose app disagrees with its own tag;
`install-desktop.sh` makes the same check before it mirrors a build into the
installed app.

The current release is
[`desktop-v1.0.10`](https://github.com/Comitz134/hanime-app/releases/tag/desktop-v1.0.10),
a prerelease carrying `SEKAI-Windows-x64-1.0.10.zip` and `desktop.json` — the
first release published under the desktop tag namespace, and the one whose window
reads its own tags back. The zip is 230,557,631 bytes, sha256
`6eefed46a0316cf34308383ef4dee08a8cb5f0ec966a6a37c72d007b00e085a4`, and
`desktop.json` names `version_name 1.0.10`, `version_code 11`. It was packed from
this tree, the installed app was re-mirrored and verified against the same build,
and the main jar inside the published zip (`23e530f7…`) is sha256-identical to
the installed one — so the release carries this code rather than a warm build.

Earlier desktop releases were tagged `v1.0.1`–`v1.0.9`, the same namespace the
phone uses. `v1.0.9` in particular was already a phone release (code 10,
2026-10-07) when the desktop line reached 1.0.9, and that publish merged its zip
and `desktop.json` into the phone's release page and replaced its notes; the page
has since been put back to the phone's release and the desktop build moved to
`desktop-v1.0.10`. Those older tags stay where they are: an installed window only
ever read the tag as a version, so nothing needs renaming.

`v1.0.2` (`SEKAI-Windows-x64-1.0.2.zip`, 230,540,743 bytes, sha256
`b1597384d09839169ddc72b66f7aee518d7c930d4d6fccbf64f4e43fc9967d3f`) is the
release whose update stops leaving its own copies behind (above). `v1.0.1`
(230,537,627 bytes, sha256
`58ae7ef14c2c44e909ed7aac650781c30ab028254e16061946f750a169d011ca`) is the one
before that — the bytes downloaded from GitHub again, anonymously, rather than
taken from the upload's own record.

What was exercised on this machine, end to end: an installed 1.0.0 window found
v1.0.1, downloaded it, staged it, wrote its `apply-update.cmd`, exited, mirrored
itself into the install folder and came back up as 1.0.1 — with the install
folder byte-identical to the release (303 files, the same sha256 for every one)
and the relaunched window's server answering 200 from the Node that travelled
inside the zip. Two later runs measured the banner itself with a single variable
between them: the same build, pointed at a fixture offering 1.0.1, draws content
at every row of its top strip (145–210 distinct colours per row); pointed at a
fixture offering 1.0.2, rows 40–115 of that same strip are a single uniform band
— the offer, and nothing else. Neither run downloaded anything without a click.

A check can be pointed somewhere else without rebuilding, which is how the
updater was verified end to end (see `tools/update-fixture.mjs`):

```bash
node tools/update-fixture.mjs --zip server/data/desktop/SEKAI-Windows-x64-1.0.1.zip --version 1.0.1
SEKAI_UPDATE_API=http://127.0.0.1:8790 ./SEKAI.exe      # or -Dsekai.update.api=…
```

The fixture logs every request it answers, which is the only way to read what a
packaged window asked for: the launcher is a GUI executable with no console. Its
log is what showed the update check arriving at launch, and the asset request
arriving only after the banner's button was clicked.

Only the Windows path is released. macOS and Linux keep upstream's install steps
(dmg/pkg, deb/rpm/AppImage) and would need their own assets and their own end-to-end
check before a release claimed to support them; this fork's releases carry none.

### An update leaves nothing behind

An update writes two full copies of the app into the reader's app data — the
archive it downloaded (230 MB) and the tree staged out of it (~335 MB) — under
`%APPDATA%\Hanime\updates`, and for a while nothing removed either of them. The
folder was only cleared by the *next* download, which then filled it again, so
the cost of every update stayed on the reader's disk until the update after it.
Measured on the machine this was written on: **566 MB** sitting there from a
single 1.0.0 → 1.0.1 update (`SEKAI-Windows-x64-1.0.1.zip` and
`staged-SEKAI-Windows-x64-1.0.1/`), which is the ~600 MB reported.

Both sides of the update now clean up, because either one alone leaves a way to
leak:

- **the swap removes its own copies.** `windowsPortableReplaceScript` ends its
  success path with a prune — the staged tree, the archive, and a sweep of every
  `staged-*` folder under the updates directory, so a copy an interrupted update
  left there is collected with it. A *failed* robocopy keeps everything: that
  tree is the only evidence of what went wrong, and it is not the path a reader
  arrives at by updating normally.
- **the app prunes staging at startup.** `pruneStaleUpdateLeftovers()` runs in
  `main()` beside `HanimeServer.ensure()` and deletes anything in the updates
  folder that has not been touched for `updateApplyWaitSeconds + 5 min` (the
  batch file's own PID wait, so nothing a swap could still be using is ever in
  scope). That is what reclaims a failed swap, a download the reader never
  installed, a `.part` file from a process killed mid-download, and the copies
  older releases left behind — including, on this machine, the 566 MB above:
  installing 1.0.2 and starting it took `%APPDATA%\Hanime\updates` from
  `du -sm → 566` to `→ 1` (the archive, the staged tree and the previous
  `apply-update.cmd` — three entries, which is the count its own startup line
  reports: `updater: reclaimed 3 stale update leftover(s)`).

The age rule is the whole reason this is not simply "erase the folder at
startup": a download that is *in flight* is younger than the bound and is left
alone, and a swap waiting on a window to exit is inside it (`PortableUpdateInstallTest`
pins both directions, and the swap test asserts the archive, the tree, the
staging folder around it *and* an older abandoned one are all gone after the
script has run).

## For a reader who just wants the app

1. Download `SEKAI-Windows-x64-<version>.zip` from the releases page.
2. Unzip it anywhere — Desktop, Documents, a USB stick.
3. Run `SEKAI.exe`. It carries its own Java runtime and its own Node, so nothing
   else has to be installed, and no terminal is involved.

Windows will warn about an unknown publisher (the build is not code-signed):
"More info" → "Run anyway", once. Keep the folder together — updating replaces it
in place, and the app's own state (profile, addons, the server it materialises)
lives under `%APPDATA%\Hanime` and `%LOCALAPPDATA%\SEKAI`, not in the folder.

### Smoke the player without clicking

Upstream ships a harness that boots straight into the native player with one URL
— useful to prove decoding works on a machine, and what was used to verify this
fork:

```bash
./gradlew :composeApp:run -Pnuvio.desktop.smokePlayerUrl="http://127.0.0.1:8787/<a stream url>"
```

The same harness works on the built app, which is how the packaged exe is
smoke-tested without a click: the property is read as a system property, so the
bundled JVM takes it from `JAVA_TOOL_OPTIONS`.

```bash
cd composeApp/build/compose/binaries/main/app/SEKAI
JAVA_TOOL_OPTIONS="-Dnuvio.desktop.smokePlayerUrl=http://127.0.0.1:8787/play/anime/<id>?ep=1&src=sv-2" ./SEKAI.exe
```

## What we changed (and what that costs)

| file | change |
| --- | --- |
| `HanimeServer.kt` *(new)* | brings up `server/`, waits for a real answer, stops only its own child |
| `Main.kt` | calls it on boot; window title `SEKAI`; opens past Nuvio's account gate |
| `AddonPlatform.desktop.kt` | seeds this server's addon bridge as the built-in source |
| `features/addons/AddonRepository.kt` | the same file as upstream plus one rule: a profile the account holds *no* addon rows for keeps the device's list instead of being emptied, so a sign-up is not a window with no catalogue (and the account is given the seeded addon) |
| `DesktopStorage.kt` | profile/cache directories named `Hanime`, so an installed Nuvio on the same machine never shares state |
| `NetworkStatusRepository.kt` | "servers reachable" probes this project's `/api/videos`, not Nuvio's Supabase |
| `composeApp/build.gradle.kts` | package, vendor and menu name `SEKAI`, plus `syncHanimeServer` — the task that bundles `server/` and the MyAnimeList `.env` into the app — plus one default for the skip button, IntroDB's public endpoint (both below) |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsScreen.kt`, `SettingsSearch.kt` | the settings search bar, kept in view under the page's own title, and this fork's own row in upstream's hand-written search catalogue |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/SkipIntervalLookup.kt`, `SkipIntroRepository.kt`, `features/player/PlayerScreenRuntimeEffects.kt` | the skip-intro button learns this catalogue's own ids, so the providers are reached at all |
| `features/mal/`, `features/tracking/` | MyAnimeList as a tracking provider: the login the window hands to the system browser, the relay the server runs for it, the list projection into Library, the writes that keep episode counts in step and finish a title on its last episode (including the rows written before that rule existed, which a read settles), a library add written as Plan to watch, and the activity panel that shows what became of them |
| `features/nsfw/` *(new)*, `features/addons/AddonModels.kt`, `features/settings/HomescreenSettingsPage.kt` | the "Hide 18+ content" switch, the preference behind it, and the one seam (`enabledAddons()`) every catalogue reader goes through |
| `features/library/LibraryRepository.kt` | the same file as upstream plus two lines: a title *entering* the app's library is announced (`TrackingProviderRegistry.notifyLibraryItemAdded`) so the tracking accounts can record the plan |
| `composeResources/`, `desktopMain/resources/icons/` | the `SEKAI` icons and the sidebar wordmark |
| `gradle.properties` | heap lowered to build on a 16 GB machine |

The seeded addon (`server/src/addon.mjs`) is what feeds the shell its shelves and
streams — Stremio addon protocol, which is the interface Nuvio's UI already
speaks.

Because it is the app's own library rather than a third party's addon, a pull
cannot take it away. Upstream's account sync treats a profile with no addon rows
as a profile with no addons; here that is an account that has never synced (a
fresh sign-up), so the device's list — on a profile never used before, the seeded
addon — is what the profile keeps, and it is pushed up so the account holds it
from then on. A *non-empty* account list stays authoritative: deleting the addon
on another client still deletes it here.

### The MyAnimeList secret travels with the app

`server/` needs `MAL_CLIENT_SECRET` to exchange or renew a MyAnimeList token
(`invalid_client: Client authentication failed` without it), and an installed
SEKAI has no checkout to find one in. So `:composeApp:syncHanimeServer` writes a
`.env` into the bundle it packages, and the window materialises it beside the
server it extracts — the same way every other bundled file gets there, so an
installed SEKAI carries `MAL_CLIENT_SECRET` at
`%LOCALAPPDATA%\SEKAI\server\.env` (`~/Library/Application Support/SEKAI/` and
`~/.local/share/SEKAI/` elsewhere) with no checkout anywhere in sight. It is
read, in order, from:

1. `-PmalClientSecret=…` (the build's own word);
2. `MAL_CLIENT_SECRET` in the environment;
3. `local.properties` as `malClientSecret` — the key the Android build reads for
   its own BuildConfig;
4. the checkout's `.env`, the file `README-ANDROID-BUILD.md` names and the
   repo's server reads for itself.

Everything that task needs — the secret and the output directory — is read
*before* it is registered, and its action closes over them as plain values. The
reason is not style: the configuration cache refuses a task action that reaches for the
project or the script (`Invocation of 'Task.project' … is unsupported`) and that
refusal fails the whole build, not just the task. The declared input is the
secret's *length*, never its value, because Gradle stores and can print input
values.

A build with none of those still builds — the catalogue, the player and the
phone do not need MyAnimeList — but it logs a warning naming the consequence,
and the `.env` it bundles says the same thing in words, because an installed app
whose tracking silently does nothing is exactly the failure this replaced. Like
the phone's BuildConfig, the value ends up inside the artefact: it is a secret of
*this registration*, not of the reader's account, and the account's own tokens
never leave the machine.

### Tracking says what it did, and what did not take

A write that does not record is not allowed to stop playback, so it used to be
logged and forgotten — which is how a session MAL had invalidated early turned
tracking off for days without a word on screen. **Settings → Tracking →
MyAnimeList activity** is the answer: one row per title this app has tried to
write (`features/mal/MalActivity.kt`, kept per profile in the app's own data
directory, newest attempt per title, sixty titles), crossed with MAL's own
answer for the same title from the list it has read back — is it on the list,
what is it at, when did its row last move, and was that move this app's or
somebody else's. Every disagreement is printed in the error colour: a write MAL
refused (in MAL's own words), a title the list does not hold although this app
wrote to it, a count MAL never caught up with. A row MAL has an id for can be
sent again from the panel ("Send now"), and the connection card above says so
when any tracked title has not landed — a card that only speaks when something
is wrong is a card worth reading.

### The last episode finishes the title, on MAL's list as much as here

MAL keeps a status and an episode count in separate fields and derives neither
from the other: writing twelve watched episodes onto a twelve-episode series
leaves the row reading **Watching 12/12** until somebody opens MAL and changes
it by hand. So the tracker reads the title's own numbers before it writes one —
`GET /anime/{id}?fields=num_episodes,my_list_status` — and the episode that
reaches the total is written as `completed`; the count and the status travel in
the same call, which is also why the read is not optional. That read answers
the second question too, whether the row is already `completed`, and the write
then keeps it: watching episode 3 of a finished series again is not unwatching
it. A title that was only planned becomes watching, since opening an episode
says more than the list did.

These are the web client's own rules (`server/client/src/mal.js`), so one
account reads the same whichever client watched the episode. A title MAL will
not describe — a blip on the read, or no answer for it at all — is still
written, as a watched episode of a title being watched: the episode is never
lost to a read that failed, and a number MAL does not know the total for
(`num_episodes: 0`, which is what an ongoing series answers) can never be its
last episode.

Those rules decide what a *new* write carries, which leaves the rows written
before them: a title already sitting on the account as **Watching 12/12**
cannot be put right by a rule about writes that have not happened yet. So the
read settles them (`MalLibrary.settleFinishedRows`). Reading the list is the one
moment the app holds both the row and the title's total at once, and a row that
is *finished* — Watching, a known total, and a count at or past it — is written
as `completed` once: the status and nothing else, since MAL writes only the
fields it is handed and the count is already where it belongs. Three things
keep that from being a write nobody asked for: a title still in progress is
never touched, a row being **re-watched** is skipped (MAL's own
`is_rewatching`, which is the reader saying they put it back on Watching
themselves), and one read settles at most ten rows — an account that spent a
year under the old rule heals over a few reads instead of in one burst MAL
answers with refusals. A repair is filed in the activity panel like any other
write, and one MAL refuses is never allowed to take the read down with it: the
shelf still shows the list it has, and the panel shows the row that did not
land.

### The 18+ shelves can be switched off, and the switch means everywhere

**Settings → Appearance → HOME → Home Layout → Adult content → Hide 18+
content** (`features/nsfw/NsfwContent.kt`, the third section on that page, under
Hero) drops the two adult shelves this server's addon
declares — `Hentai — newest` and `Hentai — most viewed`, typed `hentai` in
`server/src/addon.mjs`. The filter is applied in exactly one place,
`enabledAddons()` in `features/addons/AddonModels.kt`, which is the function
every catalogue reader asks for its addons: home rails, search and its type
list, discover, catalog pages and collections. A hidden shelf is therefore
hidden in all of them, rather than in whichever ones remembered to filter.

What it deliberately does *not* touch is `types`. This project's adult
*entries* are `movie` — the addon has always filed them that way, and that is
what the player, a saved library row and the watch history read — so a title
the reader already saved still opens and plays with the switch on. Nothing is
deleted either: the filter answers a reader, it does not edit what was
installed, so turning the option off restores the shelves exactly as they were.
The choice is one boolean in the app's own data directory
(`%APPDATA%\Hanime\nsfw.properties`, `~/Library/Application Support/Hanime/`
and `~/.config/hanime/` elsewhere), and the catalogue re-syncs the moment it is
flipped, not on the next launch.

Measured on this tree (16 Oct 2026), with the window's own addon pointed at a
logging proxy — `HANIME_ADDON_URL` is already overridable and every catalogue,
meta and stream request is derived from that one URL, so what the window asks
for is visible from outside it:

| switch | shelves the window asked for | adult requests |
| --- | --- | --- |
| on | `series/anime-trending`, `movie/films-listing`, `series/series-listing` | 0 of 24 |
| off | all five, `hentai/adult-newest` and `hentai/adult-views` included | 6 of 32 |

One boundary worth knowing: the mainstream area is AniList's catalogue, and
AniList's own search can return a title it indexes as adult. The switch hides
this app's 18+ shelves; it is not a filter over somebody else's index.

### The settings search bar is on the page, and it knows this fork's rows

**Settings** carries its search field on the page itself: directly under the
page's own title on a wide window (the sidebar column), and as the first row of
the root list on a narrow one. Upstream had the same field, but revealed by
pulling the list down past a threshold — a gesture nobody performs on purpose,
which reads as a search that does not exist. The bar is one composable
(`SettingsSearchField`, `features/settings/SettingsSearch.kt`), and the word it
holds lives in `SettingsScreen` because the wide layout's field and its answers
render in two different columns.

The catalogue behind it is upstream's hand-written list of the rows on each
page, and this fork adds a row of its own that upstream's list could not know
about: **"Hide 18+ content"**. It is written down in the words a reader would
actually type — 18, nsfw, adult — and it lands on Home Layout, in the section it
lives in. `SettingsSearchBarTest` (4 tests, `desktopTest`) renders the real page
at both widths and types those words, so a field that only exists in the source,
or a setting the search cannot find, fails the suite.

### The skip-intro button works on this catalogue's ids

The button asks its providers by the id the *catalogue* gave the episode, and
this catalogue's ids are its own: `ha:an:<anilist id>` for anime, `ha:ft:`,
`ha:fm:` and `ha:ad:` for the other three areas. Upstream's reader understood
`mal:`, `kitsu:` and `tt…` and nothing else, so on every title this app lists the
lookup answered null before any request was made — no button, on the whole
catalogue, whatever was configured. Two things were missing:

- **the id hop.** AniSkip — the one segment source that answers without a key of
  its own — is keyed by MyAnimeList's id, and this project's server already
  answers AniList's id with MAL's (`GET /api/anime/<anilist id>` →
  `details.malId`, the same hop a scrobble or a plan write makes). The lookup
  (`features/player/skip/SkipIntervalLookup.kt`) now reads `ha:an:` ids as what
  they are — an AniList id, with the episode in the tail — and
  `SkipIntroRepository.getSkipIntervalsForAniList` resolves them through that one
  door. `ha:ml:` ids, the same title entered through its MAL id, go straight to
  the MAL path.
- **a segment source in a build with no secrets.** The second provider, IntroDB,
  is read through `IntroDbConfig.URL`, and the build read that key from
  `local.properties` and nowhere else — so every build of this tree shipped
  `URL = ""`, and a disabled provider is a provider that silently answers
  nothing. The generated value now comes from the same chain the other runtime
  configs use (local.properties, then the environment) and falls back to
  IntroDB's public endpoint, `https://api.introdb.app`, when neither names one.
  Reads are anonymous there; a key is only asked for when a reader *submits* an
  intro.

Measured on this tree (16 Oct 2026) against the live services: the addon answers
`ha:an:21:1:5` for One Piece's fifth episode, this project's server answers
AniList 21 with `malId: 21`, and AniSkip answers MAL 21 episode 5 with an opening
(0–111s) and an ending (1392–1482s) — the intervals the player is now offered.
What the button does with a segment (the show window, auto-skip, the
notification) is upstream's and untouched. `SkipIntervalLookupTest` grows from 5
cases to 8, three of them pinning this fork's ids: `ha:an:21:1:5` resolves as
AniList 21 episode 5, `ha:an:21` alone never reads its own id as an episode, and
the film and 18+ ids answer null rather than guessing.

Two things it deliberately does not do. A film's slug carries a TMDB id and an
18+ video's its own slug: neither is an id any skip provider is keyed by, so
those titles have no segments until the meta carries an IMDb id for them — the
catalogue does not emit one today. And the segments handed to an *external*
player (`ExternalPlayerLaunchCoordinator`) still resolve only for
`mal:`/`kitsu:`/`tt…` ids: the in-app player is where this fork's catalogue was
fixed.

### A title saved to your library is a plan, on MAL too

Adding a title to the Library writes it to MyAnimeList as **Plan to watch**. The
library announces every title it *gains* (`features/library/LibraryRepository.kt`
→ `TrackingProviderRegistry.notifyLibraryItemAdded`, out of
`features/tracking/TrackingProvider.kt`), and MyAnimeList is the account that
keeps a plan: `MalLibrary.planAddedItem` resolves the title's MAL id through
the same hop a scrobble makes (`ha:an:<anilist id>` → this project's own
server → MAL's id), then writes `status=plan_to_watch`.

The write is careful in the three ways that matter, and each is pinned by a
test:

- a title MAL's list **already holds keeps the status the reader gave it** — the
title is read first, and an existing row is never written over, so saving a
finished show to a library again cannot drag it back to Plan to watch;
- a title this provider does not track — a film, a series, an adult video —
writes nothing and files nothing: the reader's library holds those too, and
none of them has a MAL row;
- a title MAL **cannot be asked about** is left alone and filed in the activity
panel rather than guessed at, and with no account linked nothing is written or
filed at all.

The plan carries status and nothing else, never an episode count, so it cannot
claim episodes nobody watched. A successful write re-reads the list, so the
shelf shows the new row instead of the state before the save, and the write
appears in **MyAnimeList activity** like any other (its word: `Plan to watch`,
no progress recorded). When the Library's own source *is* MyAnimeList, an
unchecked add defaults to Plan to watch as well
(`MalLibraryProvider.toggledDefaultMembership`): a title does not become
Watching because it was saved — it becomes Watching because an episode was
played, which is the tracker's own write.

### The desktop suite has 8 host-shaped failures on Windows

`:composeApp:desktopTest` runs **1526** tests here (one skipped), and **8 fail —
every one of them upstream's and every one of them about the host** (measured on
this tree, 18 Oct 2026, after the addon-seed rule above added six tests of its
own; the count was 1518 before them).
`LinuxUpdateInstallTest` (3) asserts POSIX paths — it is handed
`F:\tmp\updates\Nuvio.rpm` where it expects `/tmp/updates/Nuvio.rpm`, and the
rpm asset pick falls over with it; `CollectionCardRemoteImageTest` (3) fails
`The focus asset should not load before hover expected:<0> but was:<1>`, an
image-load race the harness loses on this machine; `HomeHeroSectionTest` (1)
computes a hero width of 640 where it asserts 660 to 0.001; and
`WatchProgressIdentityTest` (1) asserts a boolean. None of the four classes is
in this fork's overlay, and everything the overlay does touch passes —
`MalActivityTest` 17/17, `MalTrackerTest` 42/42 (its added cases cover the
library's plan write, the seam that announces it, and the list read that settles
finished rows), `NsfwFilterTest` 6/6 (the hide-18+ filter, including that hiding
a shelf does not delete it), `SkipIntervalLookupTest` 8/8 (this fork's own ids,
three of the cases added here), `SettingsSearchBarTest` 4/4 (the search bar on
the page at both window widths, and the words that find the 18+ switch),
`HomescreenNsfwSwitchTest` 3/3, `AddonPullSeedTest` 6/6 (a fresh account keeps
the seeded addon instead of being emptied, while a non-empty account list stays
authoritative), and the rest of `features/mal`,
`features/tracking`, `features/addons`, `features/player` and
`features/settings` with them.

The updater is tested the same way, and its three classes are this fork's own:
`SharedReleaseSelectionTest` 5/5 (which release a check settles on when the
newest one in the repository is the phone's), `PortableUpdateInstallTest` 12/12
(a zip's entries cannot escape the folder they are unpacked into; the install
folder comes from the launcher jpackage recorded; the generated batch file
is *run*, against a folder that looks like an install, to prove the swap
replaces it, starts the new build and reclaims the archive and staging it was
made of — an older abandoned `staged-*` folder included; and the startup prune
is pinned in both directions, deleting what an update abandoned and leaving a
download or a swap that is still in flight exactly where it is), and
`UpdaterEndToEndTest` 1/1 — a real
HTTP server answering like the release API, a real download, a real swap by
`cmd.exe`, with only two things stood in for: the server at the far end, and the
launcher started at the end of it. (The
phone's own `MangaSourceTest` is a JVM test of the android module and runs with
`android/`'s suite, not here.)

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

The name, the icon set and the sidebar wordmark are this project's. The About
screen and the licence list still attribute Nuvio, which GPL-3.0 requires and
which is left deliberately. Upstream's other selectable icon colours
(`arctic_blue`, `emerald`, `rose_gold`, `copper`, `graphite`) are untouched:
choosing one of those in Settings swaps the fork's mark back to upstream's
artwork for that colour.
