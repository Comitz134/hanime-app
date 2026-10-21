---
id: manga-progress-from-position
title: "Manga progress is inferred from one stored position, not recorded per chapter"
category: decision
status: active
tags: [manga, shelf, tracking]
created: "2026-10-21T02:06:18"
updated: "2026-10-21T02:06:46"
---

<!-- compiled_truth -->
The reader stores one position per title, and the shelf derives its length and progress from that plus the catalogue's deduped chapter list.

## What was decided

The reader keeps **one position per title** (`hid`, `chapter`, `number`, `page`,
`at`) and nothing else. Everything the UI says about progress is derived from
that one number plus the chapter list:

- **Total length** is the length of the catalogue's chapter list after dedupe.
  Dedupe matters: mangafire repeats rows, and an undeduped count once made a
  title claim 602 chapters when it had 301.
- **Read count** is the boundary rule: the chapter left open, plus every chapter
  older than it, because the catalogue lists newest first and readers read
  forward from the old end. A position pointing at a chapter the list no longer
  has counts as zero, not as everything.
- **Shelf line** reads `Ch. 12 of 33` with a known length, `Ch. 12` without one,
  `33 chapters` for a title never opened, `Not started` when neither is known.

## Why not per-chapter

A per-chapter read flag is the honest model, and mangafire does not publish one.
Storing one position is what the reader already did for "continue reading", so
progress cost no new state, no migration, and no new failure mode on a device
that closes apps without warning.

## Known limitation, and the fix

The boundary rule is wrong for a reader who jumps: open chapter 40 of a 100
chapter title and the shelf says "60 read". That is why the roadmap carries a
per-chapter read record as the next step, and why this page exists: the
inference is deliberate, not an oversight.

## The length backfill

The shelf cannot ask for a chapter list for every row on every render - a 123
title shelf is 123 upstream requests. So a visit measures the rows **on screen**,
fifteen per visit, a quarter-second apart, through `/manga/size.json`, which
writes the count onto the stored row only when the reader already owns it (a
measurement must never add a title to the shelf). The first attempt measured
storage order instead and put fifteen counts below the fold while the top of the
list stayed blank; ordering by what is visible is the fix, at the cost that rows
further down fill in on later visits.


## Timeline

- time: 2026-10-21T02:06:18
  kind: decision
  summary: "Created this page: Manga progress is inferred from one stored position, not recorded per chapter"
  source: session that shipped 2.0.2
  affects: [manga-progress-from-position]

- time: 2026-10-21T02:06:18
  kind: decision
  summary: "captured the shipped rule, its limitation, and why a per-chapter record is the next step"
  source: "shell-mobile 2.0.2, MangaApi.kt / Manga.java / LibraryMangaRow.kt"
  affects: [manga-progress-from-position]

- time: 2026-10-21T02:06:46
  kind: decision
  summary: index summary now reads as a sentence
  source: reworded the opening so the index summary reads as a sentence
  affects: [manga-progress-from-position]
