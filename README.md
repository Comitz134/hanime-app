# hanime client, self-hosted

Two pieces:

- **`server/`** — a zero-dependency Node proxy that owns the upstream
  authentication dance and serves a clean REST API plus an HLS relay.
  Fully working and verified against the live site.
- **`mobile/`** — a Flutter app that talks only to your proxy.
- **`server/public/`** — a web client the server serves itself. Installable as a
  PWA on a phone, so you can use the whole thing today without a toolchain.

---

## What the site actually does

The reverse engineering, in order of what mattered.

### 1. The catalog endpoint is open

```
GET https://guest.freeanimehentai.net/api/v11/search_hvs
```

No signature, no CSRF, no cookie. It ignores every query parameter we tried
(`page`, `search_text`, `tags`, `brands`, `order_by`) and returns the **entire
library** in a single ~4.5 MB JSON document — 3,429 entries at the time of
writing, each with description, cover, poster, studio, tags, view/like counts
and both timestamps.

Found by grepping the homepage HTML for API hosts. The site publishes its own
config inline:

```js
window.AppConfig = {
  authed_api_base_url: "https://auth.hanime.tv",
  guest_api_base_url: "https://guest.freeanimehentai.net",
  search_hvs_url: "https://guest.freeanimehentai.net/api/v11/search_hvs",
  csrf_token_url: "https://ct.hanime.tv/csrf-token",
  ...
}
```

Because it is one document, the proxy fetches it once, caches it for 10
minutes, and does all filtering, sorting and paging locally. That is faster than
the site itself and far lighter on their origin.

### 2. Every other API path is gated by three headers

```
x-signature-version: web2
x-signature:         <64 hex, changes every second>
x-time:              <unix seconds>
x-csrf-token:        <from ct.hanime.tv>
```

`/api/v11/*` other than `search_hvs` returns a Cloudflare 403 without them, and
`auth.hanime.tv` returns `401 {"error_id":"UA1"}`.

### 3. The signature comes out of a wasm module

`hanime-cdn.com/js/vendor.<hash>.min.js` is an Emscripten build with the wasm
**embedded as base64** in the JavaScript. Its glue code gives the whole game
away:

```js
var ASM_CONSTS = {
  17392: () => parseInt((new Date).getTime() / 1e3),
  17442: ($0, $1) => { window.ssignature = UTF8ToString($0); window.stime = $1 }
};
function window_on(ev_cstr) {
  var ev = UTF8ToString(ev_cstr);
  var handler = function (e) { Module.ccall("on_window_event", null, ["string","string"], [ev, data]) };
  window.addEventListener(ev, handler)
}
```

The module registers a listener for the event `"e"`. The site's own fetch layer
dispatches a real `CustomEvent("e")` before every request; the wasm recomputes
the signature and writes it to `window.ssignature` / `window.stime`.

So the server boots that module under a `node:vm` shim with a fake `window`, and
dispatches the same event per request. Same signature, same path, no browser.

Exports are minified to single letters — `A` is the runtime init, `B` is
`on_window_event`, `C` is `main`.

### 4. The handshake token is its own worst enemy

`hanime.tv/_astro/CwylGxhk2.js` names its scheme in a string constant:

```js
var s = "htv-insecure-handshake-v1";   // key seed
var c = "htv-insecure-v1";             // AES-GCM additional data
```

AES-256-GCM, key = `SHA-256("htv-insecure-handshake-v1")`, AAD =
`"htv-insecure-v1"`, 12-byte IV, 16-byte tag, wrapped as one base64url layer over
`{"v":1,"alg":"AES-256-GCM","iv":...,"tag":...,"data":...}`. The key ships in the
bundle, so the "encryption" only obfuscates.

The flow:

```
POST https://auth.hanime.tv/api/v11/handshake
  {"token": seal({ timestamp_unix, directive: "htv_player_handshake", slug })}
  -> 200 {"status":"OK"}
  -> response header x-token = open(...) -> { sources: [ ... ] }
```

### 5. The stream chain

`sources` is a quality-ordered array; 1080p is a promotion stub with an empty
`src`, so free accounts get 720p and below. Each `src` is a **media playlist**
already, not a master:

```
#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="https://ct.htv-services.com/sign.bin"
#EXTINF:17.851178,
https://p00.htv-tsukuyomi.com/0/0/0/5/v2x/segs/b0/2/s977iOY6shhQNvOyZveknVZT.html
```

Segments are ~2 MB MPEG-TS files served with `content-type: text/html` and an
`.html` extension. The AES key is 16 bytes, and it is literally the ASCII string
`0123456701234567`.

Every upstream read needs `Referer: https://hanime.tv/` and
`Origin: https://hanime.tv`. The relay adds them, so players never have to.

---

## Running the server

Needs Node 20.11+ (uses `AbortSignal.timeout`, `getSetCookie`, `Readable.fromWeb`).
No `npm install` — there are no dependencies.

```bash
cd server
node src/server.mjs
# -> http://localhost:8787
```

Environment:

| Variable     | Default     | Purpose                                              |
| ------------ | ----------- | ---------------------------------------------------- |
| `PORT`       | `8787`      | listen port                                          |
| `HOST`       | `0.0.0.0`   | bind address                                         |
| `API_TOKEN`  | *(unset)*   | when set, `/api/*` requires `Authorization: Bearer`  |

Endpoints:

| Route                          | Returns                                              |
| ------------------------------ | ---------------------------------------------------- |
| `GET /api/health`              | signer + catalog status, entry count                 |
| `GET /api/videos`              | `page`, `per_page`, `q`, `tags`, `brand`, `order_by`, `ordering` |
| `GET /api/videos/:slug`        | one entry                                            |
| `GET /api/videos/:slug/sources`| resolved HLS playlists, relay URLs already rewritten |
| `GET /api/tags`                | tags with counts                                     |
| `GET /api/session`             | account connection state                             |
| `POST /api/session`            | `{cookie}` — validate and store a session cookie     |
| `DELETE /api/session`          | forget the stored cookie                             |
| `GET /api/public/playlists`    | **crawled public playlists**, `?q=` `?owner=` `?tag=`  |
| `GET /api/public/playlists/:slug` | one public playlist with its entries               |
| `GET /api/public/playlists/owners` | creators and tag vocabulary in the crawl index     |
| `GET /api/public/videos/:slug/playlists` | which public playlists carry a title       |
| `GET\|POST /api/public/crawl`   | crawl stats / start a discovery pass                 |
| `GET /api/playlists`           | *your* playlists, `?q=` searches titles *and* contents |
| `GET /api/playlists/items`     | every item across every playlist, deduped            |
| `GET /api/playlists/:slug`     | one playlist with its items, hydrated from catalog   |
| `GET /api/playlists/debug`     | raw account payload, for inspecting a shape change   |
| `GET /api/brands`              | studios with counts                                  |
| `GET /relay?u=&s=`             | signed HLS relay (playlists, segments, keys)         |
| `GET /`                        | web client                                           |

Relay links carry an HMAC-SHA256 tag over the encoded upstream URL, keyed with a
random 32-byte secret generated per boot. A link cannot be edited into an
arbitrary fetch, and stale links die on restart — the client re-resolves from
`/api`. That keeps the relay from being an open proxy.

### Verifying it works

```bash
curl -s localhost:8787/api/health
curl -s "localhost:8787/api/videos?q=yabai&per_page=2"
curl -s localhost:8787/api/videos/yabai-fukushuu-yami-site-2/sources
curl -s localhost:8787/api/session            # configured:false until a cookie is set
```

Then follow the relay by hand: take a `url` from `sources`, fetch it, confirm
every URI in the returned playlist points at `127.0.0.1`, fetch one segment and
confirm it is ~2 MB of MPEG-TS. No upstream host should appear anywhere in the
playlist the client sees.

---

## Running the mobile app

```bash
cd mobile
flutter pub get
flutter run --dart-define=API_BASE=http://10.0.2.2:8787
```

`API_BASE` defaults to `http://10.0.2.2:8787`, which is the Android emulator's
alias for your host machine. On an iOS simulator use `http://127.0.0.1:8787`. On
a physical device use your LAN address, and add that address to
`android/app/src/main/res/xml/network_security_config.xml` — cleartext is
allow-listed per host, not opened globally.

There is no `android/` or `ios/` scaffolding in this tree. Generate it once:

```bash
cd mobile
flutter create .
```

That fills in the platform folders without touching `lib/`, `pubspec.yaml`, or
the manifest and network config already provided here.

---

## Playlists

There are two unrelated things called a playlist here, and conflating them was
the single biggest mistake in the first pass at this.

### Public playlists — unlisted, not private

An earlier version of this file said playlists are private. That was wrong, and
the way it was wrong matters. The probes were real:

| Probe                                   | Result                                    |
| --------------------------------------- | ----------------------------------------- |
| `GET /playlists`                        | **404** — no index page                   |
| `GET /api/v11/playlists`                | **404** — and `playlists/<slug>`, `playlists/search`, `playlists/popular`, `user_playlists`, `channels` likewise |
| `sitemap.xml.gz`                        | 3429 video URLs, 178 brand, 61 tag, **0 playlists** |
| `GET /playlists/<guessed-slug>`         | **404**                                   |

Every one of those is true, and none of them means what it looked like. The
last row is the tell: slugs are opaque 20-character ids, so a *guessed* slug
naturally 404s. A real one does not:

```bash
curl -s https://hanime.tv/playlists/e6c0fz4fzjpzl46u9hpc | wc -c   # 427 KB, 200
```

That page is fully server-rendered and needs no account. Its island payload
carries `playlist{title, visibility:"public", count:263, poster_url, views}`,
`playlist_owner{name, slug, user_channel}`, 263 `playlist_hentai_videos`, and
precomputed `brands[]` and `tags[]` rollups with counts.

So the accurate description is **unlisted**. Readable by anyone, listed nowhere.
Every playlist *API* is still a dead end — the route the page's own chunk calls
is a write (`PUT`/`POST`/`DELETE` with csrf), and the guest API answers **403**
for every playlist path. Reading is SSR-only, and finding them means crawling.

#### How discovery works

Three public seeds, all of them just rendered pages:

1. **Video pages.** Each one has a "Related Playlists" rail — up to 12 cards,
   each with cover, title, owner, avatar, view count and age, plus a menu button
   carrying `data-playlist-href` and the owner's channel link. Harvesting one
   page takes ~200 ms and discovers a dozen playlists.
2. **Playlist pages.** The full record, the entry list, the owner's channel.
3. **Channel pages.** That owner's other public playlists.

```bash
cd server
node scripts/crawl-playlists.mjs                 # next 60 unscanned video pages
node scripts/crawl-playlists.mjs --all           # every unscanned video page
node scripts/crawl-playlists.mjs --slug a,b,c    # specific video slugs
node scripts/crawl-playlists.mjs --refetch       # re-pull pages missing rollup data
node scripts/crawl-playlists.mjs --status
```

A pass is resumable: the index is written after each phase and `videos_scanned`
is a permanent skip list, so Ctrl-C costs nothing. Repeating a command continues
rather than restarting.

#### Storage, and why it is split in two

A whole-library crawl is large. Measured on real data: 482 playlists held
67,191 entries and cost 392 bytes per entry — so 10,000+ playlists in one
`JSON.parse` would be roughly **680 MB**, re-parsed on every request.

So it is split:

| File                              | Holds                                                |
| --------------------------------- | ---------------------------------------------------- |
| `data/playlist-index.json`         | metadata for every discovered playlist, plus a lowercased `item_text` blob of its entry titles, studios and tags. Parsed once, cached by mtime. |
| `data/playlist-items/<slug>.json`  | the entries of one playlist, read only when it is opened |

Content search reads `item_text` and never opens a per-playlist file, so search
cost does not grow with the library. Entries are read only to display a playlist
you actually opened.

`item_text` is built from entry titles **and** the page's own studio and tag
rollups, so a query like `bunnywalker` or `uncensored` matches a playlist that is
*mostly* that thing even when no single entry title contains the word.

#### Public playlist endpoints

| Route                                              | Returns                                        |
| -------------------------------------------------- | ---------------------------------------------- |
| `GET /api/public/playlists`                        | search; `?q=`, `?owner=`, `?tag=`, `?limit=`, `?items=0` to skip entry lists |
| `GET /api/public/playlists/:slug`                  | one playlist with entries, each marked `resolved` against the local catalog |
| `GET /api/public/playlists/owners`                 | creators **and** tag vocabulary present in the index |
| `GET /api/public/videos/:slug/playlists`           | which public playlists carry a title            |
| `GET /api/public/crawl`                            | index stats, plus live progress while a pass runs |
| `POST /api/public/crawl`                           | start a pass; `{count, video_slugs, max_playlists, max_channels}` |

```bash
curl -s "localhost:8787/api/public/playlists?q=tuff&limit=3"
curl -s "localhost:8787/api/public/playlists?tag=uncensored&limit=5"
curl -s "localhost:8787/api/public/playlists/e6c0fz4fzjpzl46u9hpc" | head -c 400
curl -s -X POST localhost:8787/api/public/crawl -H 'content-type: application/json' -d '{"count":40}'
```

`POST /api/public/crawl` answers `202` immediately and reports through the `GET`
— a pass outlives any sensible request timeout. One pass at a time; two would
race on the same index file. The **Scan more** button in the web client drives
the same endpoint.

#### Search semantics

Two match kinds, kept distinct:

- **title** — the query hit the playlist's own name or its creator. The whole
  playlist is the result.
- **content** — the query hit something inside it. Narrowed to the matching
  entries with a `match_count`, so the hit is visible instead of buried.

A content match also reports `matched_on`:

| `matched_on`     | Meaning                                                     |
| ---------------- | ----------------------------------------------------------- |
| `entries`        | an actual entry title matched — the hits are those entries   |
| `studio_or_tag`  | only a studio/tag rollup matched. Falls back to the head of the playlist so a good match never renders as an empty result. |

Title matches sort first, then by size. No fuzzy fallback — a query matching
nothing returns nothing. A term that is both a title and a studio resolves as
the title match, because that is the stronger answer.

A playlist is also flagged `fetched: false` when only its discovery card is
known: searchable by title and creator, but not yet by content. The UI says so
rather than pretending the index is deeper than it is.

#### Where it shows up

**Web client** — a *Public Playlists* section above the account one: its own
search box (title, creator and content in one field), a live count of crawled
playlists and titles, and a **Scan more** button that drives
`POST /api/public/crawl` and polls until the pass finishes. Opening a playlist
shows its entries, its creator, and its tag and studio rollups as chips —
clicking a chip searches for it, so one playlist becomes a way into the rest of
the index. Videos that appear in public playlists grow an **In N public
playlists** rail in the detail sheet.

**Mobile** — the Playlists tab is split into **Public** and **Mine**. Public is
the crawl and needs no account; Mine is the account screen with the cookie form.
The detail screen carries the same *In N public playlists* rail.

---

### Your playlists — the account ones

These *are* private, and they need a cookie.

What exists is `/api/v11/keep-alive`, and it needs three things at once. Missing
any one of them fails differently, which is how the protocol was confirmed:

| Sent                                    | Response                          |
| --------------------------------------- | --------------------------------- |
| signature only                          | `401 {"error_id":"UA1"}`         |
| signature + csrf token, no proof cookie | `422 {"error_id":"CSRF_ERROR_1"}` |
| signature + csrf + `htv_csrf_proof`     | `401 UA1` — protocol correct, login absent |

With a real session cookie it returns the whole account payload, playlists
included. So the proxy holds **one cookie**, never a password, never logs in on
your behalf, and refuses to store a cookie that does not authenticate. The store
lives at `server/.session.json` at mode `0600` and is gitignored.

### Connecting

1. Log in on hanime.tv in a browser.
2. DevTools → Network → any request to `auth.hanime.tv`.
3. Copy that request's `Cookie` request header.
4. Paste it into the **Mine** tab of the Playlists screen in the web client, or
   send it directly:

```bash
curl -sX POST localhost:8787/api/session \
  -H 'content-type: application/json' \
  -d '{"cookie":"session=…"}'
```

A rejected cookie returns `422 session_rejected` with the upstream reason, and
nothing is stored.

### What comes back

The account payload carries four playlist-ish collections: `playlists[]`,
`playlist_hentai_videos[]`, `like_dislike_playlist_hentai_videos[]`, and
`watch_later_playlist_hentai_videos[]`. The row shape inside the last three is
not documented anywhere and cannot be observed without a live account, so the
normalizer reads each field through a documented fallback chain rather than
assuming one shape:

- video reference: `hentai_video_id` → `video_id` → nested `hentai_video.id`
- playlist reference: `playlist_id` → `playlist_slug` → nested `playlist.id`

Two rules hold no matter the shape:

- **Rows that name no playlist are not dropped.** They become a synthetic
  collection ("watch later", "like dislike") so a shape change upstream shows up
  as a visible bucket instead of a silent zero.
- **Rows whose video is not in the local catalog are not dropped.** They come
  back with `resolved: false` and render as a dashed placeholder.

Items are always hydrated from the local catalog, since the session rows are
deliberately thin. `GET /api/playlists/debug` dumps the raw payload so you can
confirm a shape change the first time one happens.

### Searching your own playlists

`GET /api/playlists?q=` searches playlist titles **and** their contents, and the
two behave differently on purpose:

- a **title** match returns the whole playlist, tagged `"match":"title"`
- a **content** match narrows to the matching items, tagged `"match":"item"` with
  a `match_count`, so the hit is actually visible instead of buried

Title matches sort ahead of content matches, and there is no fuzzy fallback.

These endpoints are account-scoped and only answer meaningfully once a cookie is
stored: `GET /api/playlists`, `/api/playlists/items`, `/api/playlists/:slug`,
`/api/playlists/debug`. The public crawl lives under `/api/public/playlists*`
and returns `configured: false`-free results with no account at all.

## Two areas

The client now carries two libraries behind one menu:

- **Anime** (normal) — search and browse through AniList, episode lists and
  the embed player through lunarx.to, both fetched by the server under
  `/api/anime/*`:

  | Route | Answers with |
  |---|---|
  | `/api/anime/search?q=&page=` | AniList search, or trending when `q` is empty |
  | `/api/anime/:id` | one title: description, genres, recommendations |
  | `/api/anime/:id/episodes` | the season's episodes, via lunarx.to |
  | `/api/anime/:id/player?ep=` | the embed URL lunarx.to itself would play |

  The two upstreams share one key: the `:id` is the AniList id, which is also
  what lunarx.to names its anime. LunarX answers `400` to any request carrying
  another site's Origin, so a browser can never call it directly — every call
  goes through this server, which speaks as `lunarx.to` on the way out and
  caches answers (episodes and player links included) in memory. Playback is
  the same embed LunarX plays by default, so what works there works here.
- **18+ · hanime** — everything from hanime.tv, labelled in the menu and with
  an `18+` chip on the section itself.

Opening a title in either area lands on the same detail page: the title and
its facts in a header across the top, the player and the reading matter in
two columns below (the episode list lives in the sidebar for anime), and
recommendations underneath — the layout described in "Web client design"
below, not a card floating over the page it came from.

The Android shell's Java API does not answer `/api/anime/*` yet: in the APK
the Anime view reports that the area is served by the Node server instead of
loading a shelf. Everything else, both areas' detail pages included, works in
the app because it is all client code.

## Web client design

The web client's visual system is a clone of the layout and design language of
[lunarx.to](https://lunarx.to/anime), rebuilt from measurements taken off the
running page rather than from screenshots. The full extracted token sheet is in
[recon/lunarx-tokens.md](recon/lunarx-tokens.md); the short version:

| Property            | Reference                     | This client            |
| ------------------- | ----------------------------- | ---------------------- |
| Body background     | `rgb(17, 17, 17)`             | `rgb(17, 17, 17)`      |
| Foreground          | `rgb(238, 238, 238)`          | `rgb(238, 238, 238)`   |
| Card                | `rgb(25, 25, 25)`             | `rgb(25, 25, 25)`      |
| Muted foreground    | `rgb(180, 180, 180)`          | `rgb(180, 180, 180)`   |
| Border              | `rgb(32, 30, 24)`             | `rgb(32, 30, 24)`      |
| Primary             | `hsl(29.5 100% 88%)`          | `hsl(29.5 100% 88%)`   |
| Radius              | `.5rem`                       | `.5rem`                |
| UI font             | Geist Mono                    | Geist Mono             |
| Display font        | `ui-serif, Georgia, Cambria…` | same stack             |
| Card frame radius   | 8px → 12px at `sm`            | 8px → 12px at `sm`     |
| Card border         | `white/[0.08]`, hover `white/20` | same                |
| Poster aspect       | 2:3                           | 2:3                    |
| Poster sizes        | 110×165 / 150×225 / 190×285   | same breakpoints       |
| Nav pill            | `rounded-full`, `border-white/10`, `backdrop-blur-md` | same |
| Nav width (mobile)  | `80%`                         | `80%`                  |
| Container           | `max-w-[1600px]`, `px-6`      | same                   |
| Section header      | `flex items-center gap-2 mb-4` + `text-xl sm:text-2xl` | same |

Tokens are declared as bare HSL triplets consumed through `hsl(var(--x))`, the
same shape the reference uses, so the whole client re-themes by editing the
`:root` block and nothing else.

Reproduced behaviour: the fixed floating pill nav with its faded entrance and
idle logo wobble, the hero carousel with the brand-tinted hairline and a
serif display heading on a staggered entrance, drag-scrollable rails
(`cursor-grab`, `touch-action: pan-y`, click suppression after a drag), the
card hover lift with `shadow-2xl shadow-black/60`, and a glass detail overlay.

The logo mark is original geometry in the reference's brand colour, not a copy
of their glyph. The token sheet records their ramp so the palette matches;
that is a colour value, not an asset.

## Licence and scope

The reverse engineering is of a public web client: the constants and endpoints
are served to every visitor. The proxy adds no authentication bypass — it signs
requests exactly as any browser does.

The UI layout and colour tokens are derived from lunarx.to for personal use.
Their marks, imagery, fonts, and copy are theirs; none are redistributed here.
The logo glyph is original. Before publishing anything built on this, swap in
your own mark and wordmark.

This fetches from someone else's infrastructure. Keep it for your own use, keep
the catalog cache on (the proxy already does), and do not turn the relay into a
public bandwidth sink. If upstream changes its scheme, the two files that break
first are `server/src/signer.mjs` (re-extract the vendor bundle) and
`server/src/token.mjs` (re-read the new seed constant).
