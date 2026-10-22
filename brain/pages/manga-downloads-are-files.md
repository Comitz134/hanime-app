---
id: manga-downloads-are-files
title: "A downloaded chapter is files on the phone, written by the server"
category: decision
status: active
tags: [manga, downloads, offline, reader]
created: "2026-10-23T00:03:56"
updated: "2026-10-23T00:04:39"
---

<!-- compiled_truth -->
The Manga tab can keep chapters on the phone. A saved chapter is a directory of page images under the app's own files (`filesDir/manga-offline/<hid>/<chapter>/0001.jpg` ...) plus one `chapter.json` row, written by `MangaOffline` on the Java side, and the reader is handed `file://` URLs for it. With the radio off, a title opened from the Downloads screen reads exactly as it does online.

## Why the server writes the pages

The image CDN refuses a request without their referer, and the image loader (Coil) cannot be told to send one - so the bytes have to be fetched by `Manga.image`, which is the same fetch the reader's page proxy already used. The Kotlin client only asks for a chapter to be kept:

- `/manga/download.json?hid=&chapter=&number=&name=&at=&title=&poster=` queues one chapter and answers at once with its state (`queued`, `downloading`, `downloaded`).
- `remove=1` with a chapter forgets that chapter, `remove=1` without one forgets the whole title.
- `/manga/downloads.json` is the whole store: titles with their chapter count and size, every chapter with its state and progress, and `active` (how many are still on their way).
- `/manga/offline?chapter=&page=` serves one saved page as bytes. The reader does **not** use it.

## Why pages are file URLs, not loopback URLs

`/manga/pages.json` answers a saved chapter with `file://` URLs to the saved images. Loopback is still a network URL to the image loader, and with no connection it will not open one - airplane mode showed a strip of grey boxes until the page list named the files. Naming the files also makes the fallback per page: a chapter half-saved shows the pages it has.

## One queue, owned by the server

A download is started, not awaited. `MangaOffline` walks a single queue in order, so leaving the title, the tab or the app does not stop it and the next chapter starts by itself; the Kotlin side only polls `downloads.json` every 1.5s while `active > 0`. The page cache is bypassed for downloads (`Manga.fetch(url, false)`): those pages are about to be on disk, and holding them in memory too would evict the pages actually being read on a long series.

A chapter counts as saved **only** when every page it names exists on disk; `chapter.json` is written last and the row is the truth. A download that fails halfway is deleted rather than left as a strip that stops. A page deleted under the row therefore makes the chapter not-downloaded, not a chapter that renders as far as the gap.

## No connection, and the title page

`/manga/chapters.json` falls back to the saved chapters of that title and marks the answer `offline: true`. The title page says *No connection - showing the chapters saved on this phone*, and that list does **not** write its length onto the shelf row: a 200-chapter title would have shrunk to "3 chapters" for as long as it stayed there.

## The door that works with the radio off

The Offline button on the Manga tab's header (with the number of saved chapters beside it) opens the Downloads screen: saved titles with size on disk, their chapters newest-first, a tap opens the reader at that chapter with the saved chapters behind it, and a bin per chapter, per title, and per everything. It is drawn from the phone's own files through loopback, so it opens in airplane mode.

Ids arrive in a query string and become path segments, so `MangaOffline.safe` accepts only a single name of `[A-Za-z0-9._-]` - `..`, `/`, and a blank are refused.

## What it does not do

- No automatic download of new chapters, no background scheduler, no resume of a half-finished chapter across app restarts (the queue is in memory; a chapter left downloading is not saved and is not listed).
- No storage cap or eviction: the size on the Downloads screen is the only accounting.


## Timeline

- time: 2026-10-23T00:03:56
  kind: decision
  summary: "Created this page: A downloaded chapter is files on the phone, written by the server"
  source: session building offline manga downloads
  affects: [manga-downloads-are-files]

- time: 2026-10-23T00:04:39
  kind: decision
  summary: "corrected the store path and the page URL the reader is handed (file://, not /manga/offline)"
  source: "MangaOffline.java, SekaiServer.java"
  affects: [manga-downloads-are-files]
