---
id: account-carries-manga-shelf-and-mal
title: The manga shelf and the MyAnimeList session ride the settings blob
category: decision
status: active
tags: [account, sync, manga, mal]
created: "2026-10-25T17:51:25"
updated: "2026-10-25T17:51:26"
---

<!-- compiled_truth -->
The account's per-profile settings blob (v5) carries two fork fields: `manga_library`, the JSON that `MangaApi.library()` returns, and `mal_session`, the `MalSession` JSON the phone stores. `core/sync/MangaAccountSync.kt` owns both; `ProfileSettingsSync` exports and applies them.

## Why it is not in Nuvio's Library sync

The manga shelf is this fork's own store: a JSON file behind the loopback server (`/manga/library.json`) with titles, positions, last-read stamps and MAL ids. Nuvio's `sync_push_library_items` speaks films and series, not this. The MAL session was equally local (SharedPreferences), so a reader who connected it once was asked again on every install. Both now ride the settings blob, which is per profile and already private to the account.

## The two rules

- **Export only after the shelf has been read once.** `libraryPayload()` answers null until then and `ProfileSettingsSync` writes an empty field; `pushCurrentProfileToRemote` refuses the whole push while `MangaAccountSync.isLoaded` is false. Without this, the first push after a cold start would tell the account the reader has no manga and erase the remote copy. A failed loopback read leaves the cache as it was, it does not become an empty shelf.
- **Pull unions, push replaces.** Titles this device is missing are added with their `at`, `read`, `mal` and chapter length; a position moves only when there is no local position or the account's row was read more recently (`savePosition(..., readAt = 0)` so the shelf does not claim it was read now). A removal made here reaches the account with the next push. The MAL session is adopted only when this device has none, so a local sign-in is never replaced by a stale token.

## Changes that trigger it

`MangaApi.libraryChanges` emits from `addToLibrary`, `removeFromLibrary` and `savePosition`; `MangaAccountSync` debounces 1.2s, re-reads the shelf and bumps `version`, which is one more flow in the settings sync's change watcher. `MangaMal.persist` and `disconnect` call `onMalChanged()` so a connect, a token refresh and a disconnect are pushes too.

## Evidence on the phone

The settings blob had never existed (see account-settings-push-is-three-args). After the arity fix it was created from this device and the next start logged `remote matches local`. End-to-end shelf test: `yqm2p` (4 Cut Hero) was removed through the loopback API and the app was force-stopped inside the push debounce, so only the account still had it; on relaunch the log read `MangaAccountSync: restored 1 titles from the account, moved 0 positions` and `ProfileSettingsSync: applied remote settings blob`, and `/manga/library.json` listed `yqm2p` again.

The MAL adoption path was not exercised end to end: proving it would mean disconnecting the reader's real MAL session to see it come back. The export path is exercised (the blob was created while the account showed MyAnimeList connected); adoption is the same JSON through `MangaMal.adoptSession`.

## Known limit

The blob is per profile, but the manga shelf is one per device - the loopback store has no profile dimension. Two profiles on one phone therefore share a shelf, and a profile switch can pull the other profile's titles into it. That is the existing shelf model, not something this sync changes.


## Timeline

- time: 2026-10-25T17:51:25
  kind: decision
  summary: "Created this page: The manga shelf and the MyAnimeList session ride the settings blob"
  source: session linking settings to the account
  affects: [account-carries-manga-shelf-and-mal]

- time: 2026-10-25T17:51:25
  kind: decision
  summary: "the per-profile settings blob carries the manga shelf and the MAL session; pull unions titles, push replaces the shelf"
  source: "MangaAccountSync.kt, ProfileSettingsSync.kt, phone 52070e9f"
  affects: [account-carries-manga-shelf-and-mal]

- time: 2026-10-25T17:51:26
  kind: decision
  summary: "manga shelf and MAL session synced in settings blob v5; title restore verified by removing one locally and restarting"
  source: release build on 52070e9f
  affects: [account-carries-manga-shelf-and-mal]

- time: 2026-10-25T17:51:26
  kind: note
  summary: removed the temporary truth files from ../tmp after use
  source: cleanup
  affects: [account-carries-manga-shelf-and-mal]
