---
id: manga-search-sections
title: "A manga search is read per source, not as one mixed grid"
category: decision
status: active
tags: [manga, search, sources, ui]
created: "2026-10-24T00:50:30"
updated: "2026-10-24T00:50:42"
---

<!-- compiled_truth -->
A keyword search asks every enabled source and the server answers one interleaved list, each card tagged with `source`, `sourceName` and (new) `sourceLang`. The Manga tab draws that back as sections: a heading per catalogue, its language under it, a horizontal row of its posters, and a chevron that opens that catalogue's results alone. A search answered by one catalogue keeps the plain grid - a heading over one section says nothing the search field does not.

## A section is a name and a language, not a source id

Grouping by id drew two "MangaDex / English" headings one above the other, because this phone has both the MangaDex SEKAI script and the MangaDex extension switched on. A reader looking for a title does not care which of the two answered, so `mangaSearchGroups` keys on name + language: one heading, one arrow, both sources' cards. Different languages stay apart, because a heading saying "English" over a French result would be a lie. Rows with no source - only possible for a list stored before sources existed - are left out, and fewer than two groups means the caller falls back to the grid.

## The derived-state trap this hit

`rows` was first built with `remember(columns) { derivedStateOf { (focused?.cards ?: feed.cards) ... } }`, capturing the `focused` local computed in composition. A local is a *copy*: tapping a heading changed `feed.focusSource`, the heading followed it, and the grid kept showing every source's cards. The fix is reading the `groups` delegate (state) and `feed.focusSource` inside the lambda, which is what makes the grid follow the choice. Verified on the phone: the MangaDex section showed 87 titles and only MangaDex cards ("The Legend Of Mikazuchi", "Hakuryu - LEGEND", "The Legendaries"), with Mangafire's "Minor Legend" absent.

## Navigation

Both the heading row (name + language + chevron) and the chevron open the focused view; Android back returns to the mixed list rather than leaving the app (the back handler is enabled when `focusSource != null`). Opening and closing a source scrolls the list to the top, because the whole list is swapped under the offset and the heading - the way back - would otherwise be above the reader. A new query or filter change resets the focus; walking into a title and back keeps it, because the feed outlives the route.

## Verified on the phone (52070e9f, build 6466c7a+)

With four sources enabled (Mangafire, MangaDex script, MangaDex extension, ComicK): searching "legend" drew "Mangafire / English / All 96 results" and one merged "MangaDex / English / All 131 results" section, each with its own row; the arrow opened "MangaDex / English · 87 titles" in a 3-column grid of that source only; hardware back returned to the sections at the top; a section card opened its title page; returning from the title kept the search. `MangaSearchGroupTest` holds the grouping rules on the JVM (8 tests, 0 failures).


## Timeline

- time: 2026-10-24T00:50:30
  kind: decision
  summary: "Created this page: A manga search is read per source, not as one mixed grid"
  source: session adding per-source search sections
  affects: [manga-search-sections]

- time: 2026-10-24T00:50:42
  kind: decision
  summary: Recorded how a mixed search is split into source sections
  source: "MangaScreen.kt, MangaApi.kt, MangaSources.java"
  affects: [manga-search-sections]
