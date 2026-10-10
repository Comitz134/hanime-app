// The films & series routes, with f-movies.org stubbed: the TMDB search
// JSON for queries, its static listing/detail/episode HTML for everything
// else. The player route fetches twice over: the site's own player page, which
// names the doors it offers, and then each door itself, to say which ones
// answer — a door that went dark is a labelled button, not a black frame.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { handleFmovies, clearCache } from '../src/fmovies.mjs';

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

const realFetch = globalThis.fetch;
let fetchLog = [];

function stubFetch(routes) {
  fetchLog = [];
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
/** The player route's reachability probe reads status and framing headers. */
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

/** Run a request through the dispatcher, the way the server router does. */
async function call(path) {
  const url = new URL(path, 'http://localhost');
  const res = fakeRes();
  await handleFmovies(url, res, url.pathname);
  return res;
}

// ---------------------------------------------------------------- fixtures

// One movie row and one series row, in the shape the /api/search proxy
// returns (TMDB rows with a f-movies `href` and a w92 poster).
const tmdbSearch = {
  page: 1,
  total_results: 42,
  results: [
    {
      id: 580175, media_type: 'movie', href: '/movie/cavegirl-580175', title: 'Cavegirl',
      poster_url: 'https://image.tmdb.org/t/p/w92/cave.jpg', release_date: '2021-03-01', vote_average: 5.9,
    },
    {
      id: 1396, media_type: 'tv', href: '/tv/breaking-bad-1396', name: 'Breaking Bad',
      poster_url: 'https://image.tmdb.org/t/p/w92/bb.jpg', first_air_date: '2008-01-20', vote_average: 8.9,
    },
  ],
};

// A listing page, in the site's real card shape: the ★ score and the year
// live in the text row *after* the article, still inside the anchor.
const listingHtml = `
<div><a href="/movie/cavegirl-580175" class="group block min-w-0">
  <article><img src="https://image.tmdb.org/t/p/w500/cave.jpg" alt="Cavegirl"></article>
  <div class="mt-1.5 flex items-center gap-x-1.5 text-sm">
    <span><svg class="star"><path></path></svg>★</span><span class="text-[#ff8736]">5.9</span>
    <span>·</span><span>2021</span><span>·</span><span>EN</span>
  </div></a>
<a href="/tv/breaking-bad-1396" class="group block min-w-0">
  <article><img src="https://image.tmdb.org/t/p/w500/bb.jpg" alt="Breaking Bad"></article>
  <div class="mt-1.5 flex items-center gap-x-1.5 text-sm">
    <span><svg class="star"><path></path></svg>★</span><span class="text-[#ff8736]">8.9</span>
    <span>·</span><span>2008</span><span>·</span><span>EN</span>
  </div></a>
<a href="/movies?page=2">Next</a>`;

// The series detail page: JSON-LD in an @graph, the IMDb row, and season +
// episode anchors with entity-encoded ampersands, exactly as served.
const tvDetailHtml = `
<script type="application/ld+json">{"@context":"https://schema.org","@graph":[
{"@type":"Organization","name":"F-Movies"},
{"@type":"TVSeries","name":"Breaking Bad","description":"A <b>chemistry</b> teacher.","genre":["Crime","Drama"],"dateCreated":"2008-01-20","numberOfSeasons":5,"numberOfEpisodes":62,"image":"https://image.tmdb.org/t/p/original/bb.jpg","actor":[{"@type":"Person","name":"Bryan Cranston"},{"@type":"Person","name":"Aaron Paul"}],"creator":{"@type":"Person","name":"Vince Gilligan"}}]}</script>
<dl class="info">
  <dt class="text-[#768293]">Episodes:</dt><dd>62</dd>
  <dt class="text-[#768293]">IMDb:</dt><dd class="ml-2 text-sm font-semibold">9.0</dd>
</dl>
<a href="?season=1&#38;episode=1">S1</a>
<a href="?season=5&#38;episode=1">S5</a>
<a href="?season=5&#38;episode=1" title="Cat&#39;s in the Bag..." class="ep">1</a>
<a href="?season=5&#38;episode=2" title="...And the Bag&#39;s in the River" class="ep">2</a>
<a href="?season=5&#38;episode=16" title="Felina" class="ep">16</a>`;

const movieDetailHtml = `
<script type="application/ld+json">{"@context":"https://schema.org","@graph":[
{"@type":"Movie","name":"Cavegirl","description":"A cavegirl tale.","genre":["Fantasy"],"dateCreated":"1985-01-01","image":"https://image.tmdb.org/t/p/original/cave.jpg","actor":[{"@type":"Person","name":"Raylon"}]}]}</script>
<dl><dt>IMDb:</dt><dd class="ml-2 text-sm font-semibold">4.1</dd></dl>`;

// ---------------------------------------------------------------- routes

test('a query is answered by the TMDB search, in both types by default', async () => {
  stubFetch([['/api/search', () => jsonResponse(200, tmdbSearch)]]);

  const res = await call('/api/fmovies/search?q=breaking&page=1');
  assert.equal(res.status, 200);
  assert.equal(res.json.q, 'breaking');
  assert.equal(res.json.type, '');
  assert.equal(res.json.hasNext, false);          // 2 of 20 rows
  assert.deepEqual(res.json.items, [
    { type: 'movie', slug: 'cavegirl-580175', title: 'Cavegirl', year: 2021, score: 5.9, poster: 'https://image.tmdb.org/t/p/w500/cave.jpg' },
    { type: 'tv', slug: 'breaking-bad-1396', title: 'Breaking Bad', year: 2008, score: 8.9, poster: 'https://image.tmdb.org/t/p/w500/bb.jpg' },
  ]);
  assert.ok(fetchLog[0].url.includes('q=breaking&page=1'), fetchLog[0].url);
});

test('type= narrows the search result server-side', async () => {
  const res = await call('/api/fmovies/search?q=breaking&page=1&type=tv');   // same cached page
  assert.equal(res.status, 200);
  assert.equal(res.json.type, 'tv');
  assert.deepEqual(res.json.items.map((i) => i.type), ['tv']);
});

test('an empty query reads the /movies listing page', async () => {
  stubFetch([['/movies?page=1', () => htmlResponse(200, listingHtml)]]);

  const res = await call('/api/fmovies/search');
  assert.equal(res.status, 200);
  assert.equal(res.json.type, 'movie');
  assert.equal(res.json.hasNext, true);            // the page links ?page=2
  assert.deepEqual(res.json.items, [
    { type: 'movie', slug: 'cavegirl-580175', title: 'Cavegirl', year: 2021, score: 5.9, poster: 'https://image.tmdb.org/t/p/w500/cave.jpg' },
    { type: 'tv', slug: 'breaking-bad-1396', title: 'Breaking Bad', year: 2008, score: 8.9, poster: 'https://image.tmdb.org/t/p/w500/bb.jpg' },
  ]);
});

test('an empty query with type=tv reads /tv-series instead', async () => {
  stubFetch([['/tv-series?page=1', () => htmlResponse(200, listingHtml)]]);

  const res = await call('/api/fmovies/search?type=tv');
  assert.equal(res.status, 200);
  assert.equal(res.json.type, 'tv');
  assert.ok(fetchLog[0].url.includes('/tv-series?page=1'), fetchLog[0].url);
});

test("the detail page's JSON-LD becomes the details object", async () => {
  stubFetch([['/tv/breaking-bad-1396', () => htmlResponse(200, tvDetailHtml)]]);

  const res = await call('/api/fmovies/tv/breaking-bad-1396');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.details, {
    type: 'tv',
    slug: 'breaking-bad-1396',
    title: 'Breaking Bad',
    description: 'A chemistry teacher.',
    poster: 'https://image.tmdb.org/t/p/original/bb.jpg',
    year: 2008,
    score: 9.0,
    genres: ['Crime', 'Drama'],
    cast: ['Bryan Cranston', 'Aaron Paul'],
    creator: ['Vince Gilligan'],
    seasons: [1, 5],
    numberOfSeasons: 5,
    numberOfEpisodes: 62,
  });
});

test('a movie detail has no seasons', async () => {
  stubFetch([['/movie/cavegirl-580175', () => htmlResponse(200, movieDetailHtml)]]);

  const res = await call('/api/fmovies/movie/cavegirl-580175');
  assert.equal(res.status, 200);
  assert.equal(res.json.details.title, 'Cavegirl');
  assert.equal(res.json.details.year, 1985);
  assert.equal(res.json.details.score, 4.1);
  assert.deepEqual(res.json.details.seasons, []);
});

test('an upstream 404 on details propagates as 404', async () => {
  stubFetch([['/tv/nowhere-1', () => htmlResponse(404, '')]]);

  await assert.rejects(call('/api/fmovies/tv/nowhere-1'), (e) => e.status === 404);
});

test('episodes come from the season page, with entity-decoded titles', async () => {
  stubFetch([['/tv/breaking-bad-1396?season=5&episode=1', () => htmlResponse(200, tvDetailHtml)]]);

  const res = await call('/api/fmovies/tv/breaking-bad-1396/episodes?season=5');
  assert.equal(res.status, 200);
  assert.equal(res.json.season, 5);
  assert.deepEqual(res.json.data, [
    { n: 1, title: "Cat's in the Bag..." },
    { n: 2, title: "...And the Bag's in the River" },
    { n: 16, title: 'Felina' },
  ]);
});

test('a season the page does not offer is a 404, not the first season', async () => {
  stubFetch([['/tv/breaking-bad-1396?season=9&episode=1', () => htmlResponse(200, tvDetailHtml)]]);

  const res = await call('/api/fmovies/tv/breaking-bad-1396/episodes?season=9');
  assert.equal(res.status, 404);
  assert.equal(res.json.error, 'season_not_found');
});

// The site's own player page: the table of doors it offers, in its real shape
// (verified against the live page on 2026-10-10). Everything the player route
// knows about hosts comes from here.
const watchPageHtml = `<!DOCTYPE html><html><head><title>Player</title></head><body>
  <script>
    (function () {
      var params = new URLSearchParams(location.search);
      var sources = [
        { id: 'embos', aliases: ['1', 'server1', 's1'], movie: 'https://embos.top/movie/?mid={id}', tv: 'https://embos.top/tv/?mid={id}&s={season}&e={episode}' },
        { id: 'vidcore', aliases: ['2', 'server2', 's2'], movie: 'https://vidcore.net/movie/{id}', tv: 'https://vidcore.net/tv/{id}/{season}/{episode}' },
        { id: 'vidapi', aliases: ['3', 'server3', 's3'], movie: 'https://vidapi.xyz/embed/movie/{id}', tv: 'https://vidapi.xyz/embed/tv/{id}/{season}/{episode}' },
      ];
    })();
  </script>
</body></html>`;

test('the player names the doors the site itself names, and says which answer', async () => {
  // The door list and each door's health are cached module-wide, so this case
  // starts from nothing and pays for every knock itself.
  clearCache();
  stubFetch([
    ['/watch/index.html', () => htmlResponse(200, watchPageHtml)],
    ['embos.top', () => probeResponse(200)],
    // A door that is up but refuses to be framed is as black as a dead one.
    ['vidcore', () => probeResponse(200, { 'X-Frame-Options': 'SAMEORIGIN' })],
    // And a door whose host is gone (the vidsrc.cc of the day) says so.
    ['vidapi.xyz', () => probeResponse(522)],
  ]);

  const movie = await call('/api/fmovies/movie/cavegirl-580175/player');
  assert.equal(movie.status, 200);
  assert.deepEqual(movie.json.sources.map((s) => s.label), ['Server 1', 'Server 2', 'Server 3']);
  assert.equal(movie.json.sources[0].url, 'https://vidcore.net/movie/580175');
  assert.equal(movie.json.sources[1].url, 'https://vidapi.xyz/embed/movie/580175');
  assert.equal(movie.json.sources[2].url, 'https://embos.top/movie/?mid=580175');
  assert.deepEqual(movie.json.sources.map((s) => s.ok), [false, false, true]);
  assert.deepEqual(movie.json.sources.map((s) => s.note), ['frames_refused', 'http_522', null]);

  // The knock carries the site's own referer, because that is the door's
  // front desk — an embed asked for with no referer is turned away.
  const knock = fetchLog.find((f) => f.url.includes('embos.top'));
  assert.equal(knock.opts.headers.referer, 'https://www.f-movies.org/');

  const tv = await call('/api/fmovies/tv/breaking-bad-1396/player?season=5&episode=16');
  assert.equal(tv.json.sources[0].url, 'https://vidcore.net/tv/1396/5/16');
  assert.equal(tv.json.sources[2].url, 'https://embos.top/tv/?mid=1396&s=5&e=16');
});

test('a player page that cannot be read leaves the built-in doors, cached as an answer', async () => {
  // Without this the previous case's door list is still cached, and the table
  // this case is about would never be asked for.
  clearCache();
  stubFetch([
    // The watch page is deliberately unstubbed: asking for it throws.
    ['embos.top', () => probeResponse(200)],
    ['vidcore', () => probeResponse(200)],
    ['vidapi.xyz', () => probeResponse(200)],
  ]);

  const first = await call('/api/fmovies/tv/breaking-bad-1396/player?season=1&episode=1');
  // The built-in list is in our own order: the two doors that render their own
  // player lead, and the picker that can land on a 404 comes last.
  assert.deepEqual(first.json.sources.map((s) => s.url), [
    'https://vidcore.net/tv/1396/1/1',
    'https://vidapi.xyz/embed/tv/1396/1/1',
    'https://embos.top/tv/?mid=1396&s=1&e=1',
  ]);
  assert.deepEqual(first.json.sources.map((s) => s.ok), [true, true, true]);
  assert.equal(fetchLog.filter((f) => f.url.includes('/watch/index.html')).length, 1,
    'the fallback list is an answer: it must not cost a request per open');

  // A second open inside the same TTL pays nothing for the list.
  await call('/api/fmovies/movie/cavegirl-580175/player');
  assert.equal(fetchLog.filter((f) => f.url.includes('/watch/index.html')).length, 1);
});

test('a slug without a numeric id has no player', async () => {
  stubFetch([]);
  const res = await call('/api/fmovies/tv/serial-in-name-only/player');
  assert.equal(res.status, 404);
  assert.deepEqual(fetchLog, []);
});

test('identical requests hit the upstream once', async () => {
  stubFetch([['/api/search', () => jsonResponse(200, tmdbSearch)]]);

  await call('/api/fmovies/search?q=cave&page=1');
  await call('/api/fmovies/search?q=cave&page=1');
  assert.equal(fetchLog.filter((f) => f.url.includes('/api/search')).length, 1);
});

test('an upstream failure answers 502 and is not cached', async () => {
  stubFetch([]);
  await assert.rejects(call('/api/fmovies/search?q=flaky&page=1'),
    (e) => e.status === 502 && e.message.includes('f-movies'));

  // The same request again, with the upstream back up, must succeed: the
  // failure was evicted instead of becoming the answer for the whole TTL.
  stubFetch([['/api/search', () => jsonResponse(200, tmdbSearch)]]);
  const res = await call('/api/fmovies/search?q=flaky&page=1');
  assert.equal(res.status, 200);
  assert.equal(res.json.items.length, 2);
});

test('episodes on a movie and unknown paths answer 404', async () => {
  stubFetch([]);
  const onMovie = await call('/api/fmovies/movie/cavegirl-580175/episodes');
  assert.equal(onMovie.status, 404);
  const nowhere = await call('/api/fmovies/nope');
  assert.equal(nowhere.status, 404);
  assert.equal(nowhere.json.error, 'not_found');
  assert.deepEqual(fetchLog, []);
});
