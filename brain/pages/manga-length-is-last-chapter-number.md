---
id: manga-length-is-last-chapter-number
title: "A manga is as long as its last chapter number, not its row count"
category: decision
status: active
tags: [manga, shelf, title-page]
created: "2026-10-25T17:49:15"
updated: "2026-10-25T17:49:16"
---

<!-- compiled_truth -->
The length of a manga, everywhere the app states one, is the highest **whole** chapter number: the integer part of the largest number in the chapter list. It is not the number of rows the deduped list draws.

## Why

Catalogues list extras as `167.1` and `167.2` - a bonus, a second scanlation, a half chapter. Counting rows told a reader that a 167-chapter series had 169 chapters, and the shelf said "Ch. 12 of 169". A reader stated the rule: fractional chapters must not lengthen the series.

## Where it is computed

`mangaChapterTotal` in `MangaApi.kt` (overrides). It parses a number only when the whole string is one (`Ch. 12`, `#12.5`, `12`); a label such as `Extra` is not a number and is ignored for the maximum. No numbered rows at all (a oneshot, a label-only extension) falls back to the row count, which is the only answer there.

It answers three places, so they cannot disagree: the title page heading, the read fraction (`readChapterWholeCount`, which counts distinct whole numbers so `167` and `167.1` are one chapter), and the shelf line through `MangaLibraryItem.chapters`, written by `recordChapterCount`.

## Stored counts written under the old rule

A row stored `chapters` as an int under either rule, and nothing can tell which. `Manga.migrateChapterCounts` (prefs key `countsVersion` = 2) removes `chapters` from every shelf row once per install, and `fillMissingCounts` refills fifteen visible rows per visit. Until a row is refilled it shows "Ch. 12" with no denominator, which is honest; the old number was not.

## Seen on the phone

"The Last Adventurer" (hid `mv71z`): 123 deduped rows, highest number 121 (with a 26.5), title page showed **121 chapters**, stored row `chapters=121`. The library migration cleared 106 rows and refilled 16 on the first pass.


## Timeline

- time: 2026-10-25T17:49:15
  kind: decision
  summary: "Created this page: A manga is as long as its last chapter number, not its row count"
  source: session fixing chapter totals
  affects: [manga-length-is-last-chapter-number]

- time: 2026-10-25T17:49:16
  kind: decision
  summary: "the length is the highest whole chapter number; the title page, fraction and shelf all use it"
  source: "MangaApi.kt, Manga.java, LibraryMangaRow.kt, phone 52070e9f"
  affects: [manga-length-is-last-chapter-number]

- time: 2026-10-25T17:49:16
  kind: decision
  summary: "chapter length is the last whole chapter number; stored row counts written under the row-count rule are reset once and refilled"
  source: session fixing manga chapter totals
  affects: [manga-length-is-last-chapter-number]
