---
id: manga-cdn-page-referer
title: "Chapter pages go black when the CDN's numbered host gets no referer"
category: concept
status: active
tags: [manga, mangafire, reader, cdn, 403]
created: "2026-10-27T19:24:08"
updated: "2026-10-27T19:58:19"
---

<!-- compiled_truth -->
Reading a chapter from mangafire opened black: the page list arrived, but every image in it failed. The cause was how the image proxy decided which page hosts get a referer. It now decides from the URL's own host: `Manga.hostOf` parses it, `defaultReferer` gives the site referer to mangafire's site and any `mfcdn*` domain (label match, not a substring of the whole URL) and nothing to anyone else, and a host this build has never seen that answers 403 is retried once with the site referer and remembered in `REFERERS` for the rest of the process. A CDN rename therefore costs one refused page, not a black chapter.

## How it looked

On 52070e9f with 2.0.9: tap a chapter and the reader showed only its chrome over a black page (`← Ch. 10`, `Back to the title`, no error text). `adb logcat` was explicit: `page failed: https://pw6.mfcdn2.xyz/mf/.../h/p.jpg` / `page returned HTTP 403`, once per page, from several threads at once. Asking the app's own image route for the same URL answered `502` on the phone, while the same URL fetched from the PC with `-e https://mangafire.to/` answered `200` and 63 KB of JPEG, and without a referer answered `403`.

## Why it was not the reader

`MangaApi.pages()` was fine: the chapter's page list was returned and the strip was built from it. Only the page fetch behind `/manga/page`, which every reader image and every download goes through, was asking for the bytes without the header the CDN checks. A page list that looks perfect and an image route that 502s is the signature of this failure.

## The shape of the fix

- 2.0.10 matched `mfcdn` as a substring of the URL, which fixed the chapter but left the same trap: the next rename answers 403 again.
- 2.0.11 parses the host with `new URL(target).getHost()`, matches DNS labels (`mangafire.to` or a `.mangafire.to` suffix; a label starting `mfcdn`), and keeps a `ConcurrentHashMap` of hosts that have accepted the site referer. `download(target, referer)` returns the status, so `fetch` can tell a refusal from a network error and retry exactly once, only when it had sent no referer and got 403.
- Extension sources are unchanged: an unmatched host starts with no referer, which is what a script source needs, because the app cannot know which site it is reading.

## Verified

- Host classification run as plain Java on the JDK against the real shapes: `pw6.mfcdn2.xyz`, `mfcdn.to`, `mfcdn3.example.com`, `cdn.mangafire.to`, `mangafire.to`, `MANGAFIRE.TO` and `mfcdn2.xyz:8443` all resolve to the site referer; `mangafire.to.evil.example`, `evil.example` and an unparsable URL resolve to none.
- Phone 52070e9f, release 2.0.11 (211), installed over 2.0.10 in place: the same CDN URL through the app's own image route returns 200 and 63478 bytes; a chapter opened from the Manga tab logs zero `page failed` lines and draws its pages.
- A host that always answers 403 (`httpbin.org`) through the same route still answers 502 for the reader, so the retry cannot turn a real refusal into a false success, and the retry is not remembered unless it returns 200.


## Timeline

- time: 2026-10-27T19:24:08
  kind: decision
  summary: "Created this page: Chapter pages go black when the CDN's numbered host gets no referer"
  source: "Manga.java, adb on 52070e9f, 2.0.10"
  affects: [manga-cdn-page-referer]

- time: 2026-10-27T19:58:19
  kind: decision
  summary: "The page referer is decided from the URL's host, and an unknown host that refuses a page is retried once with the site referer and remembered"
  source: "Manga.java, HostCheck.java, adb on 52070e9f, 2.0.11"
  affects: [manga-cdn-page-referer]
