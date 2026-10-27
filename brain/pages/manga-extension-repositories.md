---
id: manga-extension-repositories
title: "Manga sources are repositories, and a Mihon repository runs its extensions"
category: decision
status: active
tags: [manga, sources, mihon, extensions, settings]
created: "2026-10-23T22:32:36"
updated: "2026-10-27T18:42:37"
---

<!-- compiled_truth -->
The Manga tab's catalogues come from repositories, plural. Settings -> Content & discovery -> Manga sources lists them under one plus, and tapping a repository opens what it offers so the reader picks extensions and sources. Two index formats are read by `MangaRepoIndex`: a SEKAI `index.json` of JavaScript sources, and a Mihon repository (`index.pb` gzipped protobuf or `index.min.json`) whose entries are Android extension APKs.

## What a Mihon index actually holds

Decoded from keiyoushi's published `index.pb`: field 1 name, 2 shortName, 3 signingKeyFingerprint, and **101 is one wrapper message whose field 1 repeats once per extension** - not a repeated 101. Each extension is 1 name, 2 pkg, 3 urls (1 apk, 2 icon, 31 jar), 5 versionCode, 6 version, 7 contentWarning, 8 repeated sources; a source is 1 id (64-bit varint), 2 name, 3 lang, 4 baseUrl. The parser was checked against the real file: 1,426 extensions, 2,422 sources, every apk present. A hand-built fixture in `MangaSourceTest` (gzipped, same layout) keeps it that way, including a relative apk address being resolved against the index URL.

The user's example URL `github.com/yuzono/manga-repo/raw/repo/index.pb` answers 404 - that repository publishes `index.min.json` only. Both shapes work; the dead URL is a gotcha, not a format.

## Keiyoushi arrives with the app

Every install is seeded with `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb` (`MangaSources.KEIYOUSHI`), so a reader who never pastes a URL still gets the extension ecosystem a Mihon reader would have. `seedKeiyoushi()` runs from `load()`: the row is added **without** reading the index - 109 KB of protobuf naming 1,400 extensions does not belong on the thread opening the app - and `fillSeededCounts()` reads it on a daemon thread afterwards, because a row reading "0 of 0 on" cannot be judged. The picker's own read waits on the same lock and gets the same index.

The flag (`keiyoushi.v1`) means **offered**, not required: a repository the reader already added by hand is left alone (and only marked), and one they remove later stays removed. A first run with no connection leaves the row without counts; the read is retried on the next start.

Verified on 52070e9f (2.0.9): on a fresh install the row is there with `1431 extensions / 2344 sources` once the background read lands, it survives a force-stop and relaunch with no second seed line in the log, and opening it answers the index as before.

## Installed is not offered

A repository stores what exists as counts, and what the reader picked as sources. Adding a Mihon repository installs nothing: `repoDetail` fetches the session's index on open (1,400 extensions do not belong in a preferences file), an extension switch turns all its sources on, a source switch turns one off. `state()` reports `count` from `totalSources` and `extensions` from `totalExtensions`, both persisted, because after a restart the session index is empty and the row would otherwise read "2 of 2 on" for a repository of 2,422.

## Running an APK

The extension is a dex compiled against Mihon's source-api, so the app carries that API surface itself in `composeApp/androidMain/kotlin/eu/kanade/tachiyomi/**` and loads the dex from the APK with `DelegateLastClassLoader`. `MihonExtensions` installs Injekt's Application, Context, Json, AppInfo and NetworkHelper, then names the source classes from `tachiyomi.extension.class`. Extensions ship no source-api, so every call resolves against this shim: it has to keep `getMangaUpdate`/`fetchMangaUpdate`, `HttpSource.headers` as a lazy delegate (reached reflection by KeiSource) and `Page.imageUrl` as a var.

## What an extension asks Injekt for

Injekt is Mihon's service locator, and a source resolves against it instead of building its own: Omega Scans calls `Injekt.get<Json>()` in its class initialiser to parse an answer. Missing that binding is not a failed request, it is a dead source: the initialiser throws `InjektionException`, the class stays broken for the rest of the process (`NoClassDefFoundError: q1` afterwards), and because those are `Error`s rather than `Exception`s they went straight past the per-source catch - switching Omega Scans on made every search end the process. `install` now registers `Application`, `Context` and a `Json` with Mihon's own options (`ignoreUnknownKeys`, `explicitNulls = false`, from `AppBindings.providesJson`), once, under a lock, and each route runs through `guarded`: anything a route throws becomes an `IllegalStateException` that the search reports as one failed source, with only `VirtualMachineError` left alone. A source that cannot even link is a row in `errors`, not a crash.

A page's `imageUrl` is not always absolute: MangaDex returns a path and keeps the host in `page.url`, expecting `imageRequest` to compose the address (and refresh an expiring token). `HttpSource.imageUrlFor` does that, and `pages` uses it - before that fix every MangaDex chapter answered with an empty page list.

## Verified on the phone

On 52070e9f, with keiyoushi installed from Settings: the row reads "1426 extensions · 2 of 2422 on", the picker filters to "MangaDex" ("v1.6.0 · 61 sources · AF, SQ, AR, AZ +57 · 1 on · 18+") and lists its 61 sources, the extension switch writes through (61 on, shelf 62) and back. Search returned extension cards beside Mangafire and MangaDex-script ones, `title.json` returned the extension's metadata, `chapters.json` its chapter list, and `pages.json` absolute CDN addresses that the page proxy served as a 200 image/jpeg of 454,385 bytes. A dead site (ComicK, HTTP 522) is reported per source in `errors` and does not fail the search.

After the Injekt fix, same phone and the same enabled sources (ComicK, MangaDex, Omega Scans): "the" and "love" return an Omega Scans section of 12 cards beside Mangafire and the two MangaDex, five searches in a row leave the process alive, and the Manga screen draws the Omega heading with its row. ComicK still answers HTTP 522 and is still only a row in `errors`.

## What it does not do

- No signature check against the repository's `signingKeyFingerprint`; the APK is loaded as it comes.
- No extension preferences screen, so an extension's own filters and content-rating settings stay at their defaults.
- No updates: refreshing a repository re-reads the index and keeps what was on by source id, but nothing upgrades an APK already downloaded.


## Timeline

- time: 2026-10-23T22:32:36
  kind: decision
  summary: "Created this page: Manga sources are repositories, and a Mihon repository runs its extensions"
  source: session adding multi-repository support
  affects: [manga-extension-repositories]

- time: 2026-10-23T22:32:50
  kind: decision
  summary: "Recorded how repositories, Mihon extensions and the settings picker fit together"
  source: "MangaSources.java, MangaRepoIndex.java, MihonExtensions.kt, MangaSourcesSettings.kt"
  affects: [manga-extension-repositories]

- time: 2026-10-24T03:16:15
  kind: decision
  summary: Record the Injekt Json binding extensions need and the guarded route that keeps a broken extension from ending the process
  source: "MihonExtensions.kt, crash log for Omega Scans"
  affects: [manga-extension-repositories]

- time: 2026-10-24T03:16:22
  kind: note
  summary: "Omega Scans crashed every search: its class initialiser asked Injekt for Json, which was not registered, and the resulting Error escaped the per-source catch; install now registers Application/Context/Json and every extension route is wrapped in guarded()"
  source: "MihonExtensions.kt, adb crash buffer on 52070e9f"
  affects: [manga-extension-repositories]

- time: 2026-10-25T00:44:27
  kind: note
  summary: "2.0.5 ships repositories and extensions to phones; the minified release needed three R8 keeps, recorded in [[manga-extensions-survive-r8]]"
  source: "release session, proguard-rules.pro"
  affects: [manga-extension-repositories, manga-extensions-survive-r8]

- time: 2026-10-27T18:38:00
  kind: decision
  summary: "Keiyoushi is seeded on every install, added without a blocking index read and counted in the background; the flag means offered, so a repository the reader removes stays removed"
  source: "MangaSources.java seedKeiyoushi / fillSeededCounts, 2.0.9 release, 52070e9f"
  affects: [manga-extension-repositories, manga-signer-one-answer-per-request, phone-release-is-two-publishes]

- time: 2026-10-27T18:42:37
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "MangaSources.java seedKeiyoushi / fillSeededCounts, 2.0.9 on 52070e9f"
  affects: [manga-extension-repositories]
