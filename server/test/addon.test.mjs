// The Stremio-shaped bridge the desktop app reads (src/addon.mjs).
//
// The bridge answers out of the server's own routes, so the upstreams stubbed
// here are the ones those routes already talk to — AniList and LunarX for the
// anime area, f-movies for films and series. The 18+ routes are shaped in
// server.mjs, not here, so they answer from the small table below: what this
// file tests is the mapping addon.mjs does — ids, shelves, metas and streams.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { handleAnime } from '../src/anime.mjs';
import { handleFmovies, clearCache } from '../src/fmovies.mjs';
import { clearCache as clearOpenSubsCache } from '../src/opensubs.mjs';
import { handleAddon, manifest } from '../src/addon.mjs';

/** A response double that records what the handler answered. */
function fakeRes() {
  return {
    status: null,
    headers: null,
    body: null,
    writeHead(status, headers) { this.status = status; this.headers = headers; },
    end(text) { this.body = text; this.json = JSON.parse(text); },
  };
}

const HERE = path.dirname(fileURLToPath(import.meta.url));
const flix = JSON.parse(fs.readFileSync(path.join(HERE, 'fixtures/flixcloud-capture.json'), 'utf8'));

/** The captured flixcloud page, as the tests below need it to answer. */
function flixPage() {
  return `<!doctype html><html><body><script>
  const data = { obfuscation_seed:"${flix.embed.seed}",
    ${flix.embed.tokenField}:"${flix.embed.token}",
    ${flix.embed.keyFrag2Field}:"${flix.embed.keyFrag2Value}",
    ${flix.embed.containerName}:{${flix.embed.arrayName}:[{${flix.embed.objectName}:{${flix.embed.keyField}:"${flix.embed.keyFieldValue}",${flix.embed.ivField}:"${flix.embed.ivFieldValue}"}}]},
    w_payload:"${flix.embed.wPayload}",
    subtitles:${flix.embed.subtitlesRaw ?? '[]'} };
</script></body></html>`;
}

const realFetch = globalThis.fetch;
let fetchLog = [];
// The paths the addon asked *this* server for (`self`): the shelf a client sees
// is a translation of one of these, and the translation is what some tests pin.
let selfLog = [];

function stubFetch(routes) {
  fetchLog = [];
  selfLog = [];
  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    fetchLog.push({ url: u, opts });
    for (const [match, reply] of routes) {
      if (u.includes(match)) return reply(u, opts);
    }
    throw new Error(`unstubbed fetch: ${u}`);
  };
}

function jsonResponse(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}
function htmlResponse(status, html) {
  return { ok: status >= 200 && status < 300, status, text: async () => html };
}
function probeResponse(status, headers = {}) {
  const lower = new Map(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k) => lower.get(String(k).toLowerCase()) ?? null },
    body: null,
  };
}

afterEach(() => { globalThis.fetch = realFetch; });

// ---------------------------------------------------------------- fixtures

const anilistCard = {
  id: 99001,
  idMal: 20,
  title: { romaji: 'Test no Hito' },
  description: 'A <b>test</b> title.',
  episodes: 12,
  averageScore: 81,
  startDate: { year: 2024 },
  format: 'TV',
  status: 'FINISHED',
  coverImage: { large: 'https://s4.anilist.co/c.jpg' },
  bannerImage: 'https://s4.anilist.co/b.jpg',
  genres: ['Action'],
  recommendations: { nodes: [] },
};

const anilistSearch = {
  data: { Page: { pageInfo: { currentPage: 1, hasNextPage: false }, media: [anilistCard] } },
};
const anilistDetails = { data: { Media: anilistCard } };
const lunarxEpisodes = {
  data: [
    { number: 1, title: 'Pilot', img: 'https://img/1.jpg', airDate: '2024-01-05', hasSub: true },
    { number: 2, title: '', img: null, airDate: null, hasSub: false },
  ],
};
const lunarxPlayer = { data: [{ server: 'sv-2', player_url: 'https://flixcloud.cc/e/abc?v=2' }] };

// The films shelf reads the listing page; the score and year sit in the row
// after the article, exactly as the site renders them.
const listingHtml = `
<a href="/movie/cavegirl-580175" class="group block min-w-0">
  <article><img src="https://image.tmdb.org/t/p/w500/cave.jpg" alt="Cavegirl"></article>
  <div><span>★</span><span>5.9</span><span>·</span><span>2021</span></div></a>
<a href="/tv/breaking-bad-1396" class="group block min-w-0">
  <article><img src="https://image.tmdb.org/t/p/w500/bb.jpg" alt="Breaking Bad"></article>
  <div><span>★</span><span>8.9</span><span>·</span><span>2008</span></div></a>`;

const movieDetailHtml = `
<script type="application/ld+json">{"@context":"https://schema.org","@graph":[
{"@type":"Movie","name":"Cavegirl","description":"A cavegirl tale.","genre":["Fantasy"],"dateCreated":"1985-01-01","image":"https://image.tmdb.org/t/p/original/cave.jpg","actor":[{"@type":"Person","name":"Raylon"}]}]}</script>
<dl><dt>IMDb:</dt><dd class="ml-2 text-sm font-semibold">4.1</dd></dl>`;

const tvDetailHtml = `
<script type="application/ld+json">{"@context":"https://schema.org","@graph":[
{"@type":"TVSeries","name":"Breaking Bad","description":"A chemistry teacher.","genre":["Crime"],"dateCreated":"2008-01-20","numberOfSeasons":2,"image":"https://image.tmdb.org/t/p/original/bb.jpg","actor":[{"@type":"Person","name":"Bryan Cranston"}]}]}</script>
<dl><dt>IMDb:</dt><dd class="ml-2 text-sm font-semibold">9.0</dd></dl>
<a href="?season=1&#38;episode=1">S1</a>
<a href="?season=2&#38;episode=1">S2</a>
<a href="?season=1&#38;episode=1" title="Cat&#39;s in the Bag..." class="ep">1</a>
<a href="?season=2&#38;episode=1" title="Seven Thirty-Seven" class="ep">1</a>
<a href="?season=2&#38;episode=2" title="Grilled" class="ep">2</a>`;

const watchPageHtml = `<html><body><script>
var sources = [
  { id: 'embos', aliases: ['1'], movie: 'https://embos.top/movie/?mid={id}', tv: 'https://embos.top/tv/?mid={id}&s={season}&e={episode}' },
  { id: 'vidcore', aliases: ['2'], movie: 'https://vidcore.net/movie/{id}', tv: 'https://vidcore.net/tv/{id}/{season}/{episode}' },
];
</script></body></html>`;

// The 18+ routes live in server.mjs; these are the JSON documents they answer.
const adultRow = {
  id: 7,
  slug: 'some-slug',
  name: 'Some Video',
  description: '<p>Two <b>paragraphs</b> of text.</p>',
  cover: 'https://cdn/c.jpg',
  poster: 'https://cdn/p.jpg',
  brand: 'Studio',
  tags: ['tag one', 'tag two'],
  released_at: '2024-05-02T10:00:00+00:00',
};
const ADULT = new Map([
  ['/api/videos', { page: 1, per_page: 24, total: 1, data: [adultRow] }],
  ['/api/videos/some-slug', { ...adultRow, watch_url: 'https://hanime.tv/videos/hentai/some-slug' }],
  ['/api/videos/some-slug/sources', {
    slug: 'some-slug',
    sources: [{ label: '1080p', height: 1080, width: 1920, kind: 'normal', url: 'http://127.0.0.1:8787/relay?u=abc' }],
  }],
]);

/**
 * The `self` the server passes in: real route handlers for the areas this
 * file stubs upstreams for, and the table above for the adult routes.
 */
async function self(path) {
  const url = new URL(path, 'http://self');
  selfLog.push(path);
  const body = ADULT.get(url.pathname);
  if (body) return body;

  const res = fakeRes();
  if (url.pathname.startsWith('/api/anime/')) await handleAnime(url, res, url.pathname);
  else if (url.pathname.startsWith('/api/fmovies/')) await handleFmovies(url, res, url.pathname);
  else throw new Error(`unstubbed self route: ${path}`);
  if (res.status >= 400) {
    const err = new Error(`${path} answered ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.json;
}

async function call(path) {
  const url = new URL(path, 'http://local');
  const res = fakeRes();
  await handleAddon(url, res, url.pathname, self);
  return res;
}

// ----------------------------------------------------------------- manifest

test('the manifest names the shelves this server actually has', () => {
  const m = manifest();
  assert.equal(m.id, 'tv.hanime.bridge');
  // `subtitles` is a resource like the other three: the app asks this source
  // for them through the same protocol, and the anime embeds have them.
  assert.deepEqual(m.resources, ['catalog', 'meta', 'stream', 'subtitles']);
  // `hentai` is a shelf type of this source's own: the client builds its
  // search and discover filters from these, so the 18+ shelves must declare
  // themselves rather than disappear under `movie`.
  assert.deepEqual(m.types, ['movie', 'series', 'hentai']);
  assert.deepEqual(m.catalogs.map((c) => [c.type, c.id]), [
    ['series', 'anime-trending'],
    ['movie', 'films-listing'],
    ['series', 'series-listing'],
    ['hentai', 'adult-newest'],
    ['hentai', 'adult-views'],
  ]);
  // Every shelf answers a search, so one search box reaches all of them.
  assert.ok(m.catalogs.every((c) => c.extra.some((e) => e.name === 'search')));
});

test('the manifest route answers the document itself', async () => {
  const res = await call('/addon/manifest.json');
  assert.equal(res.status, 200);
  // The name the client shows for this source: the app's own, so the library
  // reads as SEKAI's rather than as one more third-party addon.
  assert.equal(res.json.name, 'SEKAI');
});

// ----------------------------------------------------------------- catalogs

test('the anime shelf reads the trend query and carries ha:an ids', async () => {
  stubFetch([['graphql.anilist.co', () => jsonResponse(200, anilistSearch)]]);

  const res = await call('/addon/catalog/series/anime-trending/anime-trending.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.metas, [{
    id: 'ha:an:99001',
    type: 'series',
    name: 'Test no Hito',
    poster: 'https://s4.anilist.co/c.jpg',
    background: 'https://s4.anilist.co/b.jpg',
    posterShape: 'poster',
    releaseInfo: '2024',
    imdbRating: '8.1',
  }]);
});

test('a search on the shelf reaches AniList as a search, not a trend', async () => {
  stubFetch([['graphql.anilist.co', () => jsonResponse(200, anilistSearch)]]);

  const res = await call('/addon/catalog/series/anime-trending/anime-trending.json?search=test');
  assert.equal(res.status, 200);
  const sent = JSON.parse(fetchLog[0].opts.body);
  assert.equal(sent.variables.search, 'test');
  assert.equal(sent.variables.page, 1);
});

test('the films shelf keeps only films off a mixed listing page', async () => {
  clearCache();
  stubFetch([['/movies?page=1', () => htmlResponse(200, listingHtml)]]);

  const res = await call('/addon/catalog/movie/films-listing/films-listing.json');
  assert.deepEqual(res.json.metas.map((m) => [m.id, m.type, m.releaseInfo]), [
    ['ha:fm:cavegirl-580175', 'movie', '2021'],
  ]);
  assert.equal(res.json.metas[0].imdbRating, '5.9');
});

test('the series shelf asks for the tv listing and keeps only series', async () => {
  clearCache();
  stubFetch([['/tv-series?page=1', () => htmlResponse(200, listingHtml)]]);

  const res = await call('/addon/catalog/series/series-listing/series-listing.json');
  assert.ok(fetchLog[0].url.includes('/tv-series?page=1'), fetchLog[0].url);
  assert.deepEqual(res.json.metas.map((m) => [m.id, m.type]), [['ha:ft:breaking-bad-1396', 'series']]);
});

test('the 18+ shelves read their own ordering', async () => {
  const res = await call('/addon/catalog/hentai/adult-newest/adult-newest.json');
  // A shelf entry is a poster and its caption: the long description and the
  // tag list belong to the title's own page, not to every row of a rail.
  assert.deepEqual(res.json.metas, [{
    id: 'ha:ad:some-slug',
    type: 'movie',
    name: 'Some Video',
    poster: 'https://cdn/p.jpg',
    background: 'https://cdn/c.jpg',
    posterShape: 'poster',
    releaseInfo: '2024',
  }]);
});

test('the 18+ shelf counts pages the way the catalogue behind it does', async () => {
  // `/api/videos` counts its pages from zero, the addon protocol from one, so
  // the shelf's first page is page 0 there. Passing the protocol's number
  // straight through asked for the second page of the catalogue: the shelf
  // never showed its newest, and a search whose matches all fit on one page
  // came back empty.
  selfLog = [];
  await call('/addon/catalog/hentai/adult-newest/adult-newest.json');
  assert.match(selfLog[0], /\/api\/videos\?page=0&per_page=24&order_by=released_at&ordering=desc$/);

  await call('/addon/catalog/hentai/adult-newest/adult-newest.json?skip=24');
  assert.match(selfLog[1], /\?page=1&per_page=24/);

  await call('/addon/catalog/hentai/adult-newest/adult-newest.json?search=Mankitsu');
  assert.match(selfLog[2], /\?page=0&/);
  assert.match(selfLog[2], /&q=Mankitsu$/);
});

test('the plain three-part catalog form is the same request', async () => {
  clearCache();
  stubFetch([['/movies?page=1', () => htmlResponse(200, listingHtml)]]);

  // Clients that carry no extra ask for /catalog/<type>/<id>.json, with no
  // repetition of the id — the form every Stremio client uses first.
  const res = await call('/addon/catalog/movie/films-listing.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.metas.map((m) => m.id), ['ha:fm:cavegirl-580175']);
});

test('extra parameters travel in the path when the client puts them there', async () => {
  stubFetch([['graphql.anilist.co', () => jsonResponse(200, anilistSearch)]]);

  const res = await call('/addon/catalog/series/anime-trending/skip=24&search=test.json');
  assert.equal(res.status, 200);
  assert.equal(res.json.metas.length, 1);
  const sent = JSON.parse(fetchLog[0].opts.body);
  assert.equal(sent.variables.search, 'test');
  assert.equal(sent.variables.page, 2);      // skip=24 lands on page 2 of 24
});

test('an unknown shelf answers empty rather than an error', async () => {
  const res = await call('/addon/catalog/movie/nope/nope.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.metas, []);
});

// --------------------------------------------------------------------- meta

test('an anime meta carries its description and one episode per row', async () => {
  stubFetch([
    ['graphql.anilist.co', () => jsonResponse(200, anilistDetails)],
    ['api.lunarx.to/api/animes/v2/episodes', () => jsonResponse(200, lunarxEpisodes)],
  ]);

  const res = await call('/addon/meta/series/ha%3Aan%3A99001.json');
  assert.equal(res.status, 200);
  assert.equal(res.json.meta.type, 'series');
  assert.equal(res.json.meta.name, 'Test no Hito');
  assert.deepEqual(res.json.meta.videos, [
    { id: 'ha:an:99001:1:1', title: 'Pilot', season: 1, episode: 1, thumbnail: 'https://img/1.jpg', released: '2024-01-05' },
    // An episode with no still and no air date simply has neither field.
    { id: 'ha:an:99001:1:2', title: 'Episode 2', season: 1, episode: 2 },
  ]);
});

test('an episode list that fails leaves the page, not the request, empty', async () => {
  // A second id on purpose: the first one's episode list is already cached,
  // and a cached answer is not a failing upstream.
  stubFetch([
    ['graphql.anilist.co', () => jsonResponse(200, anilistDetails)],
    ['api.lunarx.to/api/animes/v2/episodes', () => jsonResponse(500, {})],
  ]);

  const res = await call('/addon/meta/series/ha%3Aan%3A99002.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.meta.videos, []);
});

test('a film meta is a movie with no episode rows', async () => {
  clearCache();
  stubFetch([['/movie/cavegirl-580175', () => htmlResponse(200, movieDetailHtml)]]);

  const res = await call('/addon/meta/movie/ha%3Afm%3Acavegirl-580175.json');
  assert.equal(res.status, 200);
  assert.equal(res.json.meta.type, 'movie');
  assert.equal(res.json.meta.name, 'Cavegirl');
  assert.equal(res.json.meta.imdbRating, '4.1');
  assert.equal(res.json.meta.releaseInfo, '1985');
  assert.deepEqual(res.json.meta.videos, []);
});

test('a series meta lists every season the page names', async () => {
  clearCache();
  stubFetch([
    ['/tv/breaking-bad-1396?season=1&episode=1', () => htmlResponse(200, tvDetailHtml)],
    ['/tv/breaking-bad-1396?season=2&episode=1', () => htmlResponse(200, tvDetailHtml)],
    ['/tv/breaking-bad-1396', () => htmlResponse(200, tvDetailHtml)],
  ]);

  const res = await call('/addon/meta/series/ha%3Aft%3Abreaking-bad-1396.json');
  assert.equal(res.json.meta.type, 'series');
  assert.deepEqual(res.json.meta.videos.map((v) => v.id), [
    'ha:ft:breaking-bad-1396:1:1',
    'ha:ft:breaking-bad-1396:2:1',
    'ha:ft:breaking-bad-1396:2:2',
  ]);
  assert.equal(res.json.meta.videos[2].title, 'Grilled');
});

test('the 18+ meta carries the studio and the tags', async () => {
  const res = await call('/addon/meta/movie/ha%3Aad%3Asome-slug.json');
  assert.equal(res.json.meta.name, 'Some Video');
  assert.deepEqual(res.json.meta.genres, ['tag one', 'tag two']);
  assert.equal(res.json.meta.releaseInfo, '2024');
});

test('an id from nowhere is a 404, not an upstream call', async () => {
  const bad = await call('/addon/meta/movie/nonsense.json');
  assert.equal(bad.status, 404);
  const worse = await call('/addon/meta/movie/ha%3Azz%3A1.json');
  assert.equal(worse.status, 404);
});

// ------------------------------------------------------------------- streams

test('the 18+ shelf resolves to HLS a player can open', async () => {
  const res = await call('/addon/stream/movie/ha%3Aad%3Asome-slug.json');
  assert.equal(res.status, 200);
  // The label repeats the resolution here, so it is said once.
  assert.deepEqual(res.json.streams, [{
    name: 'Hanime',
    description: '1080p',
    url: 'http://127.0.0.1:8787/relay?u=abc',
  }]);
});

test('an anime episode leads with the stream this server can open itself', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, lunarxPlayer)]]);

  const res = await call('/addon/stream/series/ha%3Aan%3A99001%3A1%3A3.json');
  assert.equal(res.status, 200);
  assert.ok(fetchLog[0].url.includes('episode=3'), fetchLog[0].url);

  // LunarX named a flixcloud embed, and flixcloud is a provider this server
  // opens by itself, so the episode is a media URL in the app's player first —
  // the embed stays behind it, because a stream entry that plays is not a
  // reason to hide the page the provider also serves. The in-app URL names the
  // server to try first, so that a second server from LunarX cannot silently
  // take the first one's place.
  assert.deepEqual(res.json.streams, [
    {
      name: 'Hanime · in-app',
      description: 'HLS — plays in the player',
      url: 'http://local/play/anime/99001?ep=3&src=sv-2',
    },
    {
      name: 'Hanime · sv-2',
      description: 'embed — opens in your browser',
      externalUrl: 'https://flixcloud.cc/e/abc?v=2',
    },
  ]);
});

test('a second resolvable server keeps its own in-app entry', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, {
    data: [
      { server: 'sv-2', player_url: 'https://flixcloud.cc/e/aaa?v=2' },
      { server: 'sv-3', player_url: 'https://flixcloud.cc/e/bbb?v=2' },
    ],
  })]]);

  const res = await call('/addon/stream/series/ha%3Aan%3A99001%3A1%3A5.json');
  assert.equal(res.status, 200);

  // Both servers are this server's own to open, so both lead — the first as the
  // plain in-app entry, the second named, each pointing at itself through `src`.
  assert.deepEqual(res.json.streams, [
    {
      name: 'Hanime · in-app',
      description: 'HLS — plays in the player',
      url: 'http://local/play/anime/99001?ep=5&src=sv-2',
    },
    {
      name: 'Hanime · in-app (sv-3)',
      description: 'HLS — plays in the player',
      url: 'http://local/play/anime/99001?ep=5&src=sv-3',
    },
    {
      name: 'Hanime · sv-2',
      description: 'embed — opens in your browser',
      externalUrl: 'https://flixcloud.cc/e/aaa?v=2',
    },
    {
      name: 'Hanime · sv-3',
      description: 'embed — opens in your browser',
      externalUrl: 'https://flixcloud.cc/e/bbb?v=2',
    },
  ]);
});

test('a resolvable server leads even when it is not the first one named', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, {
    data: [
      { server: 'sv-9', player_url: 'https://other-provider.example/e/xyz?v=2' },
      { server: 'sv-2', player_url: 'https://flixcloud.cc/e/ccc?v=2' },
    ],
  })]]);

  const res = await call('/addon/stream/series/ha%3Aan%3A99001%3A1%3A6.json');
  assert.equal(res.status, 200);

  // Which server comes first is LunarX's order, not ours: an embed this server
  // can open leads whichever position it was handed in, and the provider it
  // cannot open keeps its place in the embeds below.
  assert.deepEqual(res.json.streams, [
    {
      name: 'Hanime · in-app',
      description: 'HLS — plays in the player',
      url: 'http://local/play/anime/99001?ep=6&src=sv-2',
    },
    {
      name: 'Hanime · sv-9',
      description: 'embed — opens in your browser',
      externalUrl: 'https://other-provider.example/e/xyz?v=2',
    },
    {
      name: 'Hanime · sv-2',
      description: 'embed — opens in your browser',
      externalUrl: 'https://flixcloud.cc/e/ccc?v=2',
    },
  ]);
});

test('an embed from a provider this server cannot open stays a browser hand-off', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, {
    data: [{ server: 'sv-9', player_url: 'https://other-provider.example/e/xyz?v=2' }],
  })]]);

  const res = await call('/addon/stream/series/ha%3Aan%3A99001%3A1%3A4.json');
  assert.equal(res.status, 200);
  // No in-app entry at all: offering one that cannot resolve would be a stream
  // that fails where the embed would have worked.
  assert.deepEqual(res.json.streams, [{
    name: 'Hanime · sv-9',
    description: 'embed — opens in your browser',
    externalUrl: 'https://other-provider.example/e/xyz?v=2',
  }]);
});

// ------------------------------------------------------------- subtitles

test('an anime episode offers the subtitles its embed names', async () => {
  stubFetch([
    ['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, lunarxPlayer)],
    ['flixcloud.cc/e/', () => htmlResponse(200, flixPage())],
    ['/api/m3u8/', () => jsonResponse(200, flix.api)],
  ]);

  const res = await call('/addon/subtitles/series/ha%3Aan%3A99001%3A1%3A8.json');
  assert.equal(res.status, 200);

  const tracks = res.json.subtitles;
  assert.equal(tracks.length, flix.parsed.subtitles.length);

  // The page names a track the way a person would; the language is the code a
  // picker matches a preference against and the label is what tells the tracks
  // apart. The word 'English' would match no preference at all.
  assert.deepEqual(tracks.map((t) => [t.lang, t.label, t.format]), [
    ['en', '[SoM] English', 'ass'],
    ['en', '[SoM] Signs & Songs', 'ass'],
    ['en', 'Dubtitle(AI)', 'srt'],
  ]);

  // The file is the CDN's, but the link the client gets is this server's — and
  // it carries the provider's referer, signed, so the player fetches the file
  // with no header of its own.
  for (const [i, track] of tracks.entries()) {
    assert.ok(track.url.startsWith('http://local/relay?'), track.url);
    const query = new URLSearchParams(track.url.split('?')[1]);
    assert.equal(Buffer.from(query.get('u'), 'base64url').toString('utf8'), flix.parsed.subtitles[i].url);
    assert.equal(Buffer.from(query.get('r'), 'base64url').toString('utf8'), 'https://flixcloud.cc/');
  }
});

test('an episode with nothing to offer answers an empty list, not an error', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, {
    data: [{ server: 'sv-9', player_url: 'https://other-provider.example/e/xyz?v=2' }],
  })]]);

  const res = await call('/addon/subtitles/series/ha%3Aan%3A99001%3A1%3A9.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.subtitles, []);
  // A provider this server cannot open is not asked to explain itself: the
  // embed page is never fetched.
  assert.equal(fetchLog.filter((c) => c.url.includes('other-provider.example')).length, 0);
});

test('a film offers the tracks the subtitle addon has for its IMDb id', async () => {
  clearCache();
  clearOpenSubsCache();
  stubFetch([
    ['data.vidsrc.sh', () => jsonResponse(200, { data: { imdb_id: 'tt0133093' } })],
    ['opensubtitles-v3.strem.io', () => jsonResponse(200, {
      subtitles: [{ id: '1', url: 'https://subs5.strem.io/en/download/x/file/1', lang: 'eng', movieReleaseName: 'The Matrix' }],
    })],
  ]);

  const res = await call('/addon/subtitles/movie/ha%3Afm%3Athe-matrix-603.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.subtitles.map((t) => [t.lang, t.label, t.format]), [
    ['en', 'The Matrix', 'srt'],
  ]);

  // The file is the addon's, but the link the client gets is this server's, so
  // the player never has to know whose CDN it is.
  const query = new URLSearchParams(res.json.subtitles[0].url.split('?')[1]);
  assert.equal(
    Buffer.from(query.get('u'), 'base64url').toString('utf8'),
    'https://subs5.strem.io/en/download/x/file/1',
  );
});

test('a series episode asks the subtitle addon as an episode', async () => {
  clearCache();
  clearOpenSubsCache();
  stubFetch([
    ['data.vidsrc.sh', () => jsonResponse(200, { data: { imdb_id: 'tt0903747' } })],
    ['opensubtitles-v3.strem.io', () => jsonResponse(200, {
      subtitles: [{ id: '1', url: 'https://subs5.strem.io/en/download/x/file/1', lang: 'por' }],
    })],
  ]);

  // An episode id carries the episode the client appended, and that is what the
  // addon is asked for — the same id shape the anime area answers with.
  const res = await call('/addon/subtitles/series/ha%3Aft%3Abreaking-bad-1396%3A1%3A1.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.subtitles.map((t) => t.lang), ['pt']);
  assert.ok(fetchLog.some((c) => c.url.includes('/subtitles/series/tt0903747:1:1.json')));
});

test('the 18+ shelf has no subtitles, and says so without an upstream call', async () => {
  stubFetch([]);

  const res = await call('/addon/subtitles/movie/ha%3Aad%3Asome-slug.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.subtitles, []);
  assert.equal(fetchLog.length, 0, 'its playlists carry no subtitle tracks, so there is nothing to ask for');
});

test('a film stream says which door is dark', async () => {
  clearCache();
  stubFetch([
    ['/watch', () => htmlResponse(200, watchPageHtml)],
    ['vidcore.net', () => probeResponse(200, {})],
    ['embos.top', () => probeResponse(200, { 'x-frame-options': 'DENY' })],
  ]);

  const res = await call('/addon/stream/movie/ha%3Afm%3Acavegirl-580175.json');
  assert.equal(res.status, 200);

  // The film opens in the app's own player: the first entry is a media URL on
  // this server, which resolves the door when the player asks for it.
  const [inApp, ...doors] = res.json.streams;
  assert.equal(inApp.name, 'Hanime · in-app');
  assert.equal(inApp.description, 'HLS — plays in the player');
  assert.equal(inApp.url, 'http://local/play/films/movie/cavegirl-580175');
  assert.equal(inApp.externalUrl, undefined);

  // Which door the server prefers is the films area's business (its own test
  // pins that); here the point is that a refused door still travels — marked,
  // not hidden — behind the one that plays.
  assert.equal(doors.length, 2);
  const descriptions = doors.map((s) => s.description);
  assert.ok(descriptions.includes('embed — frames_refused, opens in your browser'));
  assert.ok(descriptions.includes('embed — opens in your browser'));
  assert.deepEqual(doors.map((s) => s.name), ['Hanime · Server 1', 'Hanime · Server 2']);
  assert.ok(doors.every((s) => s.externalUrl && !s.url));
});

test('a stream request for a title the door cannot resolve answers empty', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, { data: [] })]]);
  const res = await call('/addon/stream/series/ha%3Aan%3A99001%3A1%3A1.json');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.streams, []);
});

test('the addon answers no route it does not own', async () => {
  const res = await call('/addon/nonsense.json');
  assert.equal(res.status, 404);
});
