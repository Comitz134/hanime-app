---
id: manga-read-record
title: Manga progress is a record of the chapters actually opened
category: decision
status: active
tags: [manga, shelf, tracking, reader]
created: "2026-10-21T03:26:52"
updated: "2026-10-21T04:00:14"
---

<!-- compiled_truth -->
The reader stores which chapter numbers it has actually opened, per title, and the
title page counts those instead of inferring a boundary from one saved position.

## What is stored

`read:<hid>` in the app's preferences: the set of chapter **numbers** (trimmed,
never blank) that have been on screen for that title. Numbers, not ids, because a
number survives the duplicate rule (two uploads, one number), is what a tracker
speaks, and is what upstream re-issues ids around. A mark for a chapter the
catalogue no longer lists simply stops matching. `/manga/read.json` reads one
title, reads all of them, and writes one (`chapter=`) or many (`chapters=`).

## The rule on the title page

- A chapter is read if its number is in the record.
- The record, once it exists, is the answer. The account's number is a
  **position** ("chapter 40"), not a set, so it is not merged with the record:
  marking chapter 7 and unioning an account at 7 would claim seven chapters.
- With no record (an import, or a title read on an older build), the old
  boundary from the saved position stands in, and the account's boundary
  replaces it only when it claims more.
- A one-time seed fills the record from that boundary when a title has a position
  and no record, so an existing shelf does not drop from "12 of 33" to "1 of 33"
  the first time a chapter is opened on this build. The server refuses the seed
  when a record already exists: it may fill a record, never rewrite one.

## What the trackers are told

The chapter **number**, not the count. MAL and AniList hold one number that every
other client writes as "I am at chapter N"; sending the record's count instead
made the account say "1 chapter" to someone sitting on chapter 40. So the app's
fraction ("2 of 17 read") and the account's position ("8 / 13") answer different
questions on the same screen, on purpose, and the read ink on the list comes from
the record alone.

## Where the record is written

The reader, when a chapter comes under the finger (the same moment the trackers
are told), through `MangaApi.markRead`. Best effort: a mark that does not land
costs one count, and the next chapter marks itself. `readRevision` makes the
title page re-read the record on return.

## Limits

- A record seeded from a boundary is still a guess for the chapters read before
  this build: it cannot know which were opened, only what the old rule claimed.
- 2000 marks per title, a ceiling no real series reaches.
- The shelf line is unchanged: it shows the position and the length
  (`Ch. 12 of 33`), because that is what a shelf row is for; the count is on the
  title page and in the ink there.


## Timeline

- time: 2026-10-21T03:26:52
  kind: decision
  summary: "Created this page: Manga progress is a record of the chapters actually opened"
  source: session building the read record
  affects: [manga-read-record]

- time: 2026-10-21T03:26:52
  kind: decision
  summary: "the record replaces the boundary inference, and the trackers keep their position semantics"
  source: "Manga.java, /manga/read.json, MangaApi.readChapterNumbers, MangaScreen"
  affects: [manga-read-record]

- time: 2026-10-21T04:00:14
  kind: note
  summary: "shipped as 2.0.3 (203): GitHub release v2.0.3 at commit 9fe24ea, APK sha256 389b2a40, verified by downloading the asset and re-checking package id, version and release-key signer"
  source: "publish-apk.mjs + publish-github.mjs, verified against releases/latest/download/version.json"
  affects: [manga-read-record]
