---
id: manga-global-suggestions
title: "Manga recommendations are one shared list, not another per-device shelf"
category: decision
status: active
tags: [manga, suggestions, account, supabase, ui, feed]
created: "2026-10-26T20:57:48"
updated: "2026-10-26T21:20:19"
---

<!-- compiled_truth -->
The Manga tab has a global suggestion list: any signed-in reader can put a title in front of everybody, and every client - signed in or not - reads the same list. It lives in the `sekai-accounts` Supabase project (`hijiurhppikjgammgdmg`) as `public.manga_suggestions`, with `public.manga_suggestion_feed` as the read shape. The migration is `accounts/sql/0008_manga_suggestions.sql`; the live project has it as migrations `20261026203454 manga_suggestions` and `20261026203556 manga_suggestions_grants`.

## Why it is not another shelf row

The private shelf already exists twice: `MangaApi.library()` on the phone and `manga_library` in the account's per-profile settings blob ([[account-carries-manga-shelf-and-mal]]). Neither can carry a list for everybody: the shelf is one reader's, and the blob is one profile's. A recommendation belongs to an account and is read by other accounts, so it needs its own table with a read policy that is not owner-scoped. This is the app's first deliberately cross-account dataset.

## The rules

- **Read is public.** `select` is granted to `anon` and `authenticated`, and the table's read policy is `using (true)`. A signed-out phone still sees what the community recommends.
- **Write is own.** Insert, update and delete are granted only to `authenticated`, and the policy checks `user_id = auth.uid()`. `user_id` also defaults to `auth.uid()`, so the client does not send an account id and cannot substitute one that the policy would not also check.
- **One recommendation per reader, title and source.** The primary key is `(user_id, manga_id, source)`. The app upserts on those three columns, so tapping Recommend twice refreshes the row instead of drawing the title twice.
- **Withdrawing names the title, not the row owner.** The client deletes by `manga_id` and `source`; the policy is what limits the statement to the caller's own row.
- **The feed hides user ids.** `manga_suggestion_feed` groups by `(manga_id, source)`, counts readers, and answers `mine` with `coalesce(bool_or(user_id = auth.uid()), false)`. The app never receives another reader's id, which is why the same answer can be served to everyone.
- **The view runs as the caller.** `security_invoker = on` keeps the table's RLS in force; the view adds grouping, not privilege.

## What the phone draws

- `MangaSuggestions` (in `features/manga/MangaSuggestions.kt`) reads the view, caches it for 45 seconds, and exposes `recommend` and `withdraw`. A write forces a re-read so the reader sees their own action.
- Manga home gets a `Reader suggestions` shelf, when the list is not empty, above the catalogue and below Continue reading. Its arrow, and a star in the Manga header, open the full `Suggestions` screen.
- The full screen lists every suggestion with poster, title, source, reader count and a `Your recommendation` marker; the reader's own rows carry a delete action. It works with every source switched off because the data is not a catalogue.
- The title page has a `Recommend to everyone` row under the four action tiles. It toggles to `Recommended to everyone` / `Remove`, says how many readers are behind the title, and shows the sign-in hint when the profile is anonymous. The row is separate from the four tiles because the count sentence does not fit in a fifth tile.
- Opening a title from the full list carries `fromSuggestions` through the title and reader routes, so back returns to Suggestions rather than dropping the reader on Manga home.

## Evidence

- The migration was applied through the Supabase MCP. A transaction as `authenticated` with a real user id inserted a row without `user_id`, read the feed as `readers: 1, mine: true`, and rolled back. An anonymous REST read of `/rest/v1/manga_suggestion_feed?order=readers.desc` answered `200 []`; an anonymous REST insert answered `401 permission denied`, and `has_table_privilege('anon', ..., 'INSERT')` is false after the grants migration.
- `MangaSuggestionsTest` passed with `:composeApp:testAndroidHostTest --tests "com.nuvio.app.features.manga.MangaSuggestionsTest"` (`BUILD SUCCESSFUL in 7m 22s`, 5 tests, 0 failures). It holds the snake_case feed mapping, the singular/plural count wording and the "You and N others" sentence.
- The final debug APK was built with `:androidApp:assembleFullDebug` after the route change (`BUILD SUCCESSFUL in 6m 40s`): `androidApp-full-debug.apk`, 169,683,866 bytes, sha256 `c55052253051ed2040016d1e9b2c5e0e079921ce735fcafb4b80d04ed29d3c3f`. The synced build copy matches the override sources. The phone was not attached to adb at build time, so the APK was not installed and no on-phone claim is made here.

## Not done

- No version bump and no release. 2.0.7 remains the installed release; this work is on disk only.
- No moderation beyond "own rows only". A signed-in reader can recommend anything they can open, and there is no report or hide action yet.
- The feed groups by `(manga_id, source)`, so the same series recommended from two catalogues is two rows. That is deliberate: each row opens the catalogue it names, and merging by title would have to choose one catalogue's id.


## Timeline

- time: 2026-10-26T20:57:48
  kind: decision
  summary: "Created this page: Manga recommendations are one shared list, not another per-device shelf"
  source: session adding reader recommendations
  affects: [manga-global-suggestions]

- time: 2026-10-26T20:58:01
  kind: decision
  summary: "the global manga suggestion list lives in the account's Supabase project, readable by everyone and writable only by signed-in readers"
  source: "accounts/sql/0008_manga_suggestions.sql, MangaSuggestions.kt, MangaScreen.kt"
  affects: [manga-global-suggestions]

- time: 2026-10-26T20:58:01
  kind: decision
  summary: "A global manga suggestion list lives in the account, readable by everyone and writable only by signed-in readers"
  source: session adding reader recommendations
  affects: [manga-global-suggestions]

- time: 2026-10-26T20:58:02
  kind: note
  summary: "Applied 0008 to sekai-accounts (migrations 20261026203454 and 20261026203556); authenticated insert and feed mine=true verified in a rolled-back transaction, anonymous read 200 and anonymous insert privilege false"
  source: "Supabase MCP, accounts/sql/0008_manga_suggestions.sql"
  affects: [manga-global-suggestions]

- time: 2026-10-26T20:58:02
  kind: note
  summary: "Added the reader suggestions shelf, the full Suggestions screen and the title page Recommend row; debug APK built, phone was not attached so no device check"
  source: "MangaSuggestions.kt, MangaScreen.kt, install-mobile.sh"
  affects: [manga-global-suggestions]

- time: 2026-10-26T21:11:16
  kind: decision
  summary: "the global manga suggestion list lives in the account's Supabase project, readable by everyone and writable only by signed-in readers"
  source: "accounts/sql/0008_manga_suggestions.sql, MangaSuggestions.kt, MangaScreen.kt"
  affects: [manga-global-suggestions]

- time: 2026-10-26T21:11:17
  kind: note
  summary: "Final debug build after the wording fix: APK sha256 627951dd6de149ea66c8f9a71d82f6e8b8e8a614e542ea9ffc8d3635d194c544; MangaSuggestionsTest passed; anonymous REST insert is 401; no phone attached"
  source: "assembleFullDebug log, testAndroidHostTest log, Supabase REST"
  affects: [manga-global-suggestions]

- time: 2026-10-26T21:20:19
  kind: decision
  summary: "the global manga suggestion list lives in the account's Supabase project, readable by everyone and writable only by signed-in readers"
  source: "accounts/sql/0008_manga_suggestions.sql, MangaSuggestions.kt, MangaScreen.kt"
  affects: [manga-global-suggestions]

- time: 2026-10-26T21:20:19
  kind: note
  summary: "Final artifact after the Suggestions back-navigation fix: APK sha256 c55052253051ed2040016d1e9b2c5e0e079921ce735fcafb4b80d04ed29d3c3f, 169,683,866 bytes, assembleFullDebug BUILD SUCCESSFUL in 6m 40s"
  source: "assembleFullDebug log, synced source diff"
  affects: [manga-global-suggestions]
