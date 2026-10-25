---
id: manga-reader-panel-and-migration
title: "The manga title page, reader panel and Library downloads follow the site's reader"
category: decision
status: active
tags: [manga, reader, migration, downloads, sources, ui]
created: "2026-10-25T04:30:00"
updated: "2026-10-25T05:20:00"
---

<!-- compiled_truth -->
Four changes in `shell-mobile/overrides`: the title page's fourth tile is Migrate instead of a WebView onto the site, the reader's settings sheet is the site's Layout/Image panel, the Library's Transferências screen lists downloaded manga, and a repository's picker filters to sources that are on. A script source switched off now stays listed, off, instead of being removed from its repository.

## Migrate replaces the WebView tile

`MangaActionRow` keeps four tiles: Library, the next-chapter estimate (no handler), Tracking and Migrate (swap icon). The WebView tile opened mangafire's page for a title that did not come from mangafire, so it did nothing for extensions and scripts.

`MangaMigrateSheet` searches every enabled source, drops the result whose `source` equals `mangaSourceId(id)` or whose id is the title's own, and groups the rest by source and language with the same search API the Manga tab uses. Picking a card is a move, not a copy:

- the chapter position moves by **number**, because a chapter id belongs to the catalogue that issued it; the new source's chapter list is read and the matching number becomes the new position at the same page;
- the read record moves as chapter numbers, seeded into the new title's empty record (`seedReadMarks`);
- a shelf row is re-added with the old row's MAL id and the old row removed;
- saved chapters do not move: they are files under the old id (`manga-downloads-are-files`) and stay until removed in Downloads.

`Manga.java` stores one JSON object for the reader panel (`readerPrefs` / `saveReaderPrefs`) and `SekaiServer` answers `/manga/reader.json`, so the panel is not reset by a restart. It is a GET with a `save=` parameter, like the other loopback writes.

## The reader panel is the site's

`ReaderDisplaySheet` in `MangaScreen.kt` has the site reader's two tabs: Layout (Single / Double / Long strip, margin stepper 0-48 with Reset, five progress-bar styles, Single vs Double tap for the chrome) and Image (Fit width, Fit height - the two are exclusive - Stretch small images, Limit max width/height, Grayscale, Dim screen 0-70%, plus a continuous-reading switch the site does not have). Every value writes to `MangaReaderPrefs`, which the pages already read, so a change is a redraw; the sheet saves on dismissal, debounced.

Values: layout, margin, progress, tap, fitWidth, fitHeight, stretch, maxWidth, maxHeight, grayscale, dim, continuous. `split` / `right to left` were not added: the app opens manga right to left already and the site's panel has no such control.

## Downloads in the Library

`DownloadsScreen` moved into `overrides` and lists `MangaSavedTitle` rows under a "Manga" heading, above movies and shows, from `MangaDownloads.index` - the phone's own files over loopback, so it draws with the radio off. Tapping a row calls `onOpenMangaDownload(hid, title, poster)`, which `MainAppContent` routes to `MangaNav.open`, pops the downloads route and activates the Manga tab, so back does not land on a list on top of the reader. Verified end to end.

## Only on

`OnlyOnRow` in `MangaSourcesSettings.kt` is a view filter over the opened repository: extensions are filtered by `extension.enabled`, their catalogues by `source.enabled`, and the header counts still say `N on`. It is not persisted - it is "what am I using", answered per opening.

## A switched-off script source used to disappear

`MangaSources.toggle` set `enabled = false` and then called `disable(repo, id)` for every repository format, which removes the row. For a Mihon repository that is the install model; for a script repository it made the switch a one-way door - the row is how a source is turned back on - and turning the built-in Mangafire off left "SEKAI sources: 0 sources", with only Refresh able to read the asset back. Now only `"mihon".equals(repo.format)` removes the row; a script source keeps its row with the switch off.

The old build left this phone's stored built-in repository with no sources; Refresh in the picker restored both, and `MangaSources.enabledSources()` already filters on `enabled` for search.

## Start label when a chapter has no number

The migrated MangaDex test title has 134 chapters and none of them numbered, so the primary button read "Start Ch. " with a blank. The button now says "Start reading" / "Continue reading" when the number is blank, and the secondary first-chapter button is hidden when its number is blank.

## Verified on the phone

Phone 52070e9f, debug package `com.nuviodebug.com` 2.0.6, debug APK sha256 `0cbeb75b3f2d2d685212fbd09dacf304cb25bfb8394f33bf500c4bdcd779c827` (`install-mobile.sh`, debug variant):

- Manga sources -> SEKAI sources: "Only on" switch at the top. MangaDex off left both rows ("2 sources · 1 on"), Only on hid the off row ("2 sources · 1 on" with one row), Only on off showed it again.
- Library -> Transferências -> "Manga" section: "Test Manga · 1 chapter · 15 KB"; the row opened the title page with "Start Ch. 1".
- Reader -> panel: Layout and Image tabs drew as in the site's screenshots. Grayscale turned the page grey; Double drew two pages side by side; the saved object came back from `/manga/reader.json` as `layout STRIP ... grayscale false`.
- Title page -> Migrate: the sheet searched and listed "Official "Test" Manga" under "MangaDex · English". Picking it moved the page to the same title from MangaDex, 134 chapters, "Start reading".

## Shipped in 2.0.7

Committed as 18d5e51 (feature) and 83fe322 (version bump), pushed to main, and published as 2.0.7 (207) from 83fe322: release APK sha256 `992c44914ab436fe985e270c0d3b21959de3e416a23515db4c0e6153f6a829a0`, `app.hanime.shell`, universal APK 128,028,111 bytes, signed with the shell key. `publish-apk.mjs` and `publish-github.mjs` agreed on the digest and `/releases/latest/download/version.json` names 207.

A clean install of that exact APK was done first: onboarding into a new profile, Home opened on its catalogues (the seeded loopback addon), the Manga tab's Trending came back from Mangafire (the R8 build still loads the source scripts), the title page showed Migrate, Ch. 385 of Berserk opened and the Layout/Image panel drew and was navigable, the sources picker showed "2 sources · 2 on" and Only on hid the one switched off, and Transferencias opened on its empty state.

Two gotchas from that clean install. HyperOS refused a fresh package with `INSTALL_FAILED_USER_RESTRICTED` even after "Install via USB" was switched on; restarting the adb server made the next install succeed, while upgrades of an already-installed package had never been blocked. And with the debug package still installed and running, the release app's loopback client talked to the debug app's server, so the clean app showed the debug app's `Test Manga` shelf until the debug package was force-stopped and the release app restarted.

## Test status

- `:androidApp:testFullDebugUnitTest` passes.
- `server`: `node --test test/manga-read.test.mjs test/manga-dupes.test.mjs test/manga-eta.test.mjs` passes, 37/37.
- `:composeApp:testAndroidHostTest` fails 7 tests that this work did not touch: RatingsVisibilityTest, DownloadSubtitlesTest (2), WatchProgressIdentityTest, AndroidDownloadLifecycleTest (Robolectric needs Java 21, this machine has 17) and RootTabHostTest (2, its expected tab set includes Manga and the host test visits four tabs). Recorded as pre-existing, not fixed here.

Committed as 18d5e51 and 83fe322, pushed to main, and shipped in the 2.0.7 release.

## Timeline

- time: 2026-10-25T04:30:00
  kind: decision
  summary: "recorded the four manga UI changes, the script-source toggle fix, and what was checked on the phone"
  source: "session on 52070e9f, install-mobile.sh builds 44f84b3 / 1409066 / 0cbeb75"
  affects: [manga-reader-panel-and-migration, manga-extension-repositories, manga-downloads-are-files]

- time: 2026-10-25T05:20:00
  kind: note
  summary: "shipped as 2.0.7 (207) from 83fe322, sha256 992c4491; the clean install confirmed Home's seeded catalogues, Trending from Mangafire under R8, the Migrate tile, the reader panel, Only on and the empty Transferencias screen"
  source: "publish-apk.mjs, publish-github.mjs, clean install on 52070e9f"
  affects: [manga-reader-panel-and-migration, phone-release-is-two-publishes]
