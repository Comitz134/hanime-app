---
slug: stack
title: Tech stack
role: tech-stack choices
updated: "2026-10-21T03:27:25"
---

# Tech stack

## Technology choices

| domain | candidates | decision | rationale |
|---|---|---|---|
| API server | Express / Fastify / zero-dep Node | zero-dependency Node, built-in `http` | one process to start, nothing to install, and the whole value is proxying plus caching, not framework features |
| Catalogue | scrape HTML per request / fetch the open JSON endpoint | fetch the site's own `search_hvs` document once, cache 10 minutes, filter and page locally | the endpoint returns the entire library (~3.4 MB, 3,429 titles) and ignores paging parameters; local filtering is faster than the site |
| Playlist extraction | parse the player page / drive a browser | borrow the machine's Chrome or Edge over CDP through Node's built-in WebSocket | the stream URL is signed by the player's JavaScript and exists only after it runs; CDP avoids a headless dependency |
| Media delivery | hand the client the upstream URL / relay it | loopback HLS relay with the door's referer attached | upstream URLs are referer-bound and expire; the relay is the only stable URL a native player can hold |
| Desktop app | keep `desktop/` / fork a finished client | fork NuvioDesktop as `shell/` (Kotlin + Compose, native mpv) | the purpose-built window was a viewer; the fork is a finished streaming app |
| Phone app | Flutter `mobile/` / Java WebView `android/` / fork NuvioMobile | fork NuvioMobile as `shell-mobile/`, published as `app.hanime.shell` signed with the old shell's key | keeps the package id, so an installed 1.0.x shell updates in place instead of being reinstalled |
| Films catalogue | f-movies.org scrape / TMDB | TMDB for catalog, details and episodes; cinehd's six doors for playback | TMDB is a documented API; the doors are the only place the playlists exist |
| Manga progress | boundary from one saved position / stored set of chapter numbers | the stored set, with the boundary only as the fallback for a title with no record | a boundary claims every chapter below the last one, including the ones a jumping reader never opened (see [[manga-read-record]]) |
| Manga | mangafire.to | scraped through `server/src` and the app's own routes | the site is the catalogue; the reader keeps its position and its read record locally |
| Anime tracking | MyAnimeList / AniList | both, each behind its own page | readers arrive with one or the other already populated |
| Distribution | installer / self-update | GitHub releases: phone on `v<version>`, desktop as prereleases tagged `desktop-v<version>` | the two clients version independently and share one repository |
| Font/UI glass, icons | - | upstream Nuvio conventions, kept in `overrides/` | diverging from upstream would make `sync.sh` a merge every time |

## Open items

- The Java film backend (`Fmovies.java`) still scrapes f-movies.org while Node
  answers TMDB. Porting it is the agreed next step for the phone.
