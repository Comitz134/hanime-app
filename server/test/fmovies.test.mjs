// The films & series routes, with TMDB stubbed: the catalog, details, season
// and credits JSON that cinehd.vc's own pages are built from, plus the six
// doors its player offers — which the player route asks for themselves, to say
// which ones answer. A door that went dark is a labelled button, not a black
// frame.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { handleFmovies, clearCache, inAppOrder } from '../src/fmovies.mjs';
import { clearCache as clearOpenSubsCache } from '../src/opensubs.mjs';

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

// TMDB's multi search, as its own API answers: movies and series carry
// media_type, and a person is a row too — with no title to open.
const tmdbSearch = {
  page: 1,
  total_pages: 3,
  total_results: 42,
  results: [
    {
      id: 580175, media_type: 'movie', title: 'Cavegirl', poster_path: '/cave.jpg',
      release_date: '2021-03-01', vote_average: 5.94,
    },
    {
      id: 1396, media_type: 'tv', name: 'Breaking Bad', poster_path: '/bb.jpg',
      first_air_date: '2008-01-20', vote_average: 8.9,
    },
    { id: 287, media_type: 'person', name: 'Someone Famous' },
  ],
};

const tvDetails = {
  id: 1396,
  name: 'Breaking Bad',
  overview: 'A chemistry teacher turns to crime.',
  poster_path: '/bb.jpg',
  backdrop_path: '/bbb.jpg',
  first_air_date: '2008-01-20',
  vote_average: 8.86,
  genres: [{ id: 18, name: 'Drama' }, { id: 80, name: 'Crime' }],
  number_of_seasons: 5,
  number_of_episodes: 62,
  // Season 0 is TMDB's specials bucket, and it is not a season the strip shows.
  seasons: [{ season_number: 0, name: 'Specials' }, { season_number: 1 }, { season_number: 5 }],
  // TMDB puts a series' creators on the document itself, not in the credits.
  created_by: [{ id: 66633, name: 'Vince Gilligan' }],
  credits: {
    cast: [{ name: 'Bryan Cranston' }, { name: 'Aaron Paul' }],
    crew: [],
  },
  external_ids: { imdb_id: 'tt0903747' },
};

const movieDetails = {
  id: 580175,
  title: 'Cavegirl',
  overview: 'A cavegirl tale.',
  poster_path: '/cave.jpg',
  backdrop_path: null,
  release_date: '1985-01-01',
  vote_average: 4.1,
  genres: [{ id: 14, name: 'Fantasy' }],
  credits: {
    cast: [{ name: 'Raylon' }],
    crew: [{ name: 'Someone Else', job: 'Writer' }, { name: 'A Director', job: 'Director' }],
  },
  external_ids: { imdb_id: 'tt0081234' },
};

// Deliberately out of order, with one row that has no number: the route sorts
// by the real episode number and drops what cannot be placed.
const seasonFive = {
  season_number: 5,
  episodes: [
    { episode_number: 16, name: 'Felina' },
    { episode_number: 1, name: "Live Free or Die" },
    { episode_number: 2, name: "Madrigal" },
  ],
};

// ---------------------------------------------------------------- routes

test('a query is answered by the TMDB search, in both types by default', async () => {
  stubFetch([['/search/multi', () => jsonResponse(200, tmdbSearch)]]);

  const res = await call('/api/fmovies/search?q=breaking&page=1');
  assert.equal(res.status, 200);
  assert.equal(res.json.q, 'breaking');
  assert.equal(res.json.type, '');
  assert.equal(res.json.source, 'TMDB');
  assert.equal(res.json.hasNext, true);            // 1 of 3 upstream pages
  assert.deepEqual(res.json.items, [
    { type: 'movie', slug: 'cavegirl-580175', title: 'Cavegirl', year: 2021, score: 5.9, poster: 'https://image.tmdb.org/t/p/w500/cave.jpg' },
    { type: 'tv', slug: 'breaking-bad-1396', title: 'Breaking Bad', year: 2008, score: 8.9, poster: 'https://image.tmdb.org/t/p/w500/bb.jpg' },
  ]);

  // The person row is not a title, and the key is added here, not by the caller.
  assert.equal(res.json.items.length, 2);
  const url = new URL(fetchLog[0].url);
  assert.equal(url.origin + url.pathname, 'https://api.themoviedb.org/3/search/multi');
  assert.equal(url.searchParams.get('query'), 'breaking');
  assert.ok(url.searchParams.get('api_key'), 'no TMDB key rode the request');
  assert.equal(url.searchParams.get('language'), 'en-US');
});

test('type= narrows the search result server-side', async () => {
  const res = await call('/api/fmovies/search?q=breaking&page=1&type=tv');   // same cached page
  assert.equal(res.status, 200);
  assert.equal(res.json.type, 'tv');
  assert.deepEqual(res.json.items.map((i) => i.type), ['tv']);
});

test('an empty query reads the week’s trending shelf', async () => {
  clearCache();
  stubFetch([['/trending/all/week', () => jsonResponse(200, {
    page: 1, total_pages: 2, results: tmdbSearch.results,
  })]]);

  const res = await call('/api/fmovies/search');
  assert.equal(res.status, 200);
  assert.equal(res.json.type, '');
  assert.equal(res.json.hasNext, true);
  assert.deepEqual(res.json.items.map((i) => i.slug), ['cavegirl-580175', 'breaking-bad-1396']);
  assert.ok(fetchLog[0].url.includes('/3/trending/all/week'), fetchLog[0].url);
});

test('an empty query with type= asks trending for that type alone', async () => {
  clearCache();
  stubFetch([['/trending/tv/week', () => jsonResponse(200, {
    page: 1, total_pages: 1, results: tmdbSearch.results,
  })]]);

  const res = await call('/api/fmovies/search?type=tv');
  assert.equal(res.status, 200);
  assert.equal(res.json.type, 'tv');
  assert.deepEqual(res.json.items.map((i) => i.type), ['tv']);
  assert.ok(fetchLog[0].url.includes('/3/trending/tv/week'), fetchLog[0].url);
});

test('the details object comes from one TMDB request with credits appended', async () => {
  clearCache();
  stubFetch([['/3/tv/1396', () => jsonResponse(200, tvDetails)]]);

  const res = await call('/api/fmovies/tv/breaking-bad-1396');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.details, {
    type: 'tv',
    slug: 'breaking-bad-1396',
    title: 'Breaking Bad',
    description: 'A chemistry teacher turns to crime.',
    poster: 'https://image.tmdb.org/t/p/w500/bb.jpg',
    backdrop: 'https://image.tmdb.org/t/p/original/bbb.jpg',
    year: 2008,
    score: 8.9,
    genres: ['Drama', 'Crime'],
    cast: ['Bryan Cranston', 'Aaron Paul'],
    creator: ['Vince Gilligan'],
    seasons: [1, 5],
    numberOfSeasons: 5,
    numberOfEpisodes: 62,
    imdb_id: 'tt0903747',
  });
  // Credits and external ids cost nothing extra: they ride the same request.
  assert.equal(fetchLog.length, 1);
  assert.ok(fetchLog[0].url.includes('append_to_response=credits%2Cexternal_ids'), fetchLog[0].url);
});

test('a movie detail has no seasons, and its director is its creator', async () => {
  clearCache();
  stubFetch([['/3/movie/580175', () => jsonResponse(200, movieDetails)]]);

  const res = await call('/api/fmovies/movie/cavegirl-580175');
  assert.equal(res.status, 200);
  assert.equal(res.json.details.title, 'Cavegirl');
  assert.equal(res.json.details.year, 1985);
  assert.equal(res.json.details.score, 4.1);
  assert.equal(res.json.details.backdrop, null);
  assert.deepEqual(res.json.details.creator, ['A Director']);
  assert.deepEqual(res.json.details.seasons, []);
});

test('an upstream 404 on details propagates as 404', async () => {
  clearCache();
  stubFetch([['/3/tv/1', () => jsonResponse(404, { status_message: 'Not found.' })]]);

  await assert.rejects(call('/api/fmovies/tv/nowhere-1'), (e) => e.status === 404);
});

test('episodes are the season TMDB actually holds, sorted by number', async () => {
  clearCache();
  stubFetch([['/3/tv/1396/season/5', () => jsonResponse(200, seasonFive)]]);

  const res = await call('/api/fmovies/tv/breaking-bad-1396/episodes?season=5');
  assert.equal(res.status, 200);
  assert.equal(res.json.season, 5);
  assert.deepEqual(res.json.data, [
    { n: 1, title: 'Live Free or Die' },
    { n: 2, title: 'Madrigal' },
    { n: 16, title: 'Felina' },
  ]);
});

test('a season the title does not have is a 404, not the first season', async () => {
  clearCache();
  stubFetch([['/3/tv/1396/season/9', () => jsonResponse(404, { status_message: 'Not found.' })]]);

  const res = await call('/api/fmovies/tv/breaking-bad-1396/episodes?season=9');
  assert.equal(res.status, 404);
  assert.equal(res.json.error, 'season_not_found');
});

test('the player offers cinehd’s six doors, and says which ones answer', async () => {
  clearCache();
  stubFetch([
    ['ythd.org', () => probeResponse(200)],
    // A door that is up but refuses to be framed is as black as a dead one.
    ['vixsrc.to', () => probeResponse(200, { 'X-Frame-Options': 'SAMEORIGIN' })],
    // A host with no DNS left, one edge returning 522, one CSP refusal.
    ['player2.vidplus.pro', () => { throw new Error('getaddrinfo ENOTFOUND'); }],
    ['player.vidplus.pro', () => probeResponse(522)],
    ['player.videasy.to', () => probeResponse(200, { 'Content-Security-Policy': "frame-ancestors 'none'" })],
    ['vidfast.vc', () => probeResponse(200)],
  ]);

  const movie = await call('/api/fmovies/movie/cavegirl-580175/player');
  assert.equal(movie.status, 200);
  assert.deepEqual(movie.json.sources.map((s) => s.label),
    ['Max', 'Vidpro', 'V2', 'Premium', '4K', 'Vidfast']);
  assert.deepEqual(movie.json.sources.map((s) => s.url), [
    'https://ythd.org/embed/580175',
    'https://vixsrc.to/movie/580175',
    'https://player2.vidplus.pro/embed/movie/580175?autoplay=true',
    'https://player.vidplus.pro/embed/movie/580175?autoplay=true&download=true',
    'https://player.videasy.to/movie/580175',
    'https://vidfast.vc/movie/580175?autoplay=true',
  ]);
  assert.deepEqual(movie.json.sources.map((s) => s.ok), [true, false, false, false, false, true]);
  assert.deepEqual(movie.json.sources.map((s) => s.note),
    [null, 'frames_refused', 'unreachable', 'http_522', 'frames_refused', null]);

  // The knock is a knock: no referer to a site this is not.
  const knock = fetchLog.find((f) => f.url.includes('ythd.org'));
  assert.equal(knock.opts.headers.referer, undefined);

  const tv = await call('/api/fmovies/tv/breaking-bad-1396/player?season=5&episode=16');
  assert.equal(tv.json.sources[0].url, 'https://ythd.org/embed/1396/5-16');
  assert.equal(tv.json.sources[1].url, 'https://vixsrc.to/tv/1396/5/16');
  assert.equal(tv.json.sources[5].url, 'https://vidfast.vc/tv/1396/5/16?autoplay=true');
});

test('the in-app walk leads with Vidpro, or with the door the reader named', () => {
  // The in-app player needs a playlist URL, not an embed: Vidpro is the door
  // that hands one over, so it leads — while the framed player keeps cinehd's
  // own order (its own test above pins that).
  assert.equal(inAppOrder()[0].id, 'vidpro');
  assert.deepEqual(inAppOrder().map((s) => s.id),
    ['vidpro', 'max', 'v2', 'premium', '4k', 'vidfast']);

  // A named door leads, by label or by id, whatever case it arrives in.
  assert.equal(inAppOrder('Vidfast')[0].id, 'vidfast');
  assert.equal(inAppOrder('4K')[0].id, '4k');
  assert.equal(inAppOrder('vidfast')[0].id, 'vidfast');

  // A name no door answers to is not an error: the default order stands, and
  // no door is lost or repeated either way.
  assert.deepEqual(inAppOrder('nonsense').map((s) => s.id), inAppOrder().map((s) => s.id));
  assert.equal(new Set(inAppOrder('max').map((s) => s.id)).size, 6);
  assert.equal(inAppOrder('max').length, 6);
});

test('a door’s health is cached: a second open does not knock again', async () => {
  clearCache();
  stubFetch([
    ['ythd.org', () => probeResponse(200)],
    ['vixsrc.to', () => probeResponse(200)],
    ['player2.vidplus.pro', () => probeResponse(200)],
    ['player.vidplus.pro', () => probeResponse(200)],
    ['player.videasy.to', () => probeResponse(200)],
    ['vidfast.vc', () => probeResponse(200)],
  ]);

  await call('/api/fmovies/tv/breaking-bad-1396/player?season=1&episode=1');
  const first = fetchLog.length;
  assert.equal(first, 6);

  await call('/api/fmovies/tv/breaking-bad-1396/player?season=1&episode=1');
  assert.equal(fetchLog.length, first, 'the second open paid for the same knocks again');
});

test('a slug without a numeric id has no player', async () => {
  clearCache();
  stubFetch([]);
  const res = await call('/api/fmovies/tv/serial-in-name-only/player');
  assert.equal(res.status, 404);
  assert.deepEqual(fetchLog, []);
});

test('identical requests hit the upstream once', async () => {
  clearCache();
  stubFetch([['/search/multi', () => jsonResponse(200, tmdbSearch)]]);

  await call('/api/fmovies/search?q=cave&page=1');
  await call('/api/fmovies/search?q=cave&page=1');
  assert.equal(fetchLog.length, 1);
});

test('an upstream failure answers 502 and is not cached', async () => {
  clearCache();
  stubFetch([]);
  await assert.rejects(call('/api/fmovies/search?q=flaky&page=1'),
    (e) => e.status === 502 && e.message.includes('tmdb'));

  // The same request again, with the upstream back up, must succeed: the
  // failure was evicted instead of becoming the answer for the whole TTL.
  clearCache();
  stubFetch([['/search/multi', () => jsonResponse(200, tmdbSearch)]]);
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

// ---------------------------------------------------------------- subtitles

test('a film answers the tracks its IMDb id has, language and all', async () => {
  clearCache();
  clearOpenSubsCache();
  stubFetch([
    ['/3/movie/603', () => jsonResponse(200, { ...movieDetails, id: 603, external_ids: { imdb_id: 'tt0133093' } })],
    ['opensubtitles-v3.strem.io', () => jsonResponse(200, {
      subtitles: [
        { id: '37756', url: 'https://subs5.strem.io/en/download/x/file/45899', lang: 'ger', movieReleaseName: 'Matrix, The (1999)' },
        { id: '18076', url: 'https://subs5.strem.io/en/download/x/file/20484', lang: 'eng' },
      ],
    })],
  ]);

  const res = await call('/api/fmovies/movie/the-matrix-603/subtitles');
  assert.equal(res.status, 200);
  assert.equal(res.json.imdb_id, 'tt0133093');
  assert.deepEqual(res.json.tracks.map((t) => [t.language, t.name, t.format]), [
    ['de', 'Matrix, The (1999)', 'srt'],
    ['en', null, 'srt'],
  ]);
  assert.ok(res.json.tracks[0].url.startsWith('https://subs5.strem.io/'));

  // The IMDb id is the one hop the slug cannot make itself, and it now rides
  // the same TMDB request the title page already made.
  assert.ok(fetchLog.some((f) => f.url.includes('/3/movie/603')), 'no TMDB lookup for the IMDb id');
  assert.equal(fetchLog.filter((f) => f.url.includes('data.vidsrc.sh')).length, 0);
});

test('an episode is asked as an episode', async () => {
  clearCache();
  clearOpenSubsCache();
  stubFetch([
    ['/3/tv/1396', () => jsonResponse(200, tvDetails)],
    ['opensubtitles-v3.strem.io', () => jsonResponse(200, {
      subtitles: [{ id: '1', url: 'https://subs5.strem.io/en/download/x/file/1', lang: 'eng', movieReleaseName: 'Breaking.Bad.S01E01' }],
    })],
  ]);

  const res = await call('/api/fmovies/tv/breaking-bad-1396/subtitles?season=1&episode=1');
  assert.equal(res.status, 200);
  assert.equal(res.json.season, 1);
  assert.equal(res.json.episode, 1);
  assert.deepEqual(res.json.tracks.map((t) => t.language), ['en']);
  assert.ok(fetchLog.some((f) => f.url.includes('/subtitles/series/tt0903747:1:1.json')));
});

test('when the addon has nothing, the door player’s own list is the answer', async () => {
  clearCache();
  clearOpenSubsCache();
  // The title has no IMDb id to look up with, and the door's data API names one
  // subtitle file of its own.
  stubFetch([
    ['/3/tv/4546', () => jsonResponse(200, { ...tvDetails, id: 4546, external_ids: {} })],
    ['data.vidsrc.sh', () => jsonResponse(200, {
      data: { title: 'Curb Your Enthusiasm 2000' },
      default_subs: [{ lang: 'English - eng', code: 'en', url: 'https://vidapi.cloud/subs/abc/eng.eng.srt' }],
    })],
  ]);

  const res = await call('/api/fmovies/tv/curb-your-enthusiasm-4546/subtitles?season=7&episode=9');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.tracks.map((t) => [t.language, t.name]), [['en', 'English']]);
  assert.equal(res.json.tracks[0].url, 'https://vidapi.cloud/subs/abc/eng.eng.srt');
  assert.equal(fetchLog.filter((f) => f.url.includes('opensubtitles')).length, 0,
    'with no IMDb id there is nothing to ask the addon');
});

test('a title with neither source answers an empty list, not an error', async () => {
  clearCache();
  clearOpenSubsCache();
  stubFetch([
    ['/3/movie/580175', () => jsonResponse(200, { ...movieDetails, external_ids: {} })],
    ['data.vidsrc.sh', () => jsonResponse(200, { data: {}, default_subs: [] })],
  ]);

  const res = await call('/api/fmovies/movie/cavegirl-580175/subtitles');
  assert.equal(res.status, 200);
  assert.deepEqual(res.json.tracks, []);
});

test('a slug without a numeric id has no subtitles', async () => {
  clearCache();
  stubFetch([]);
  const res = await call('/api/fmovies/movie/serial-in-name-only/subtitles');
  assert.equal(res.status, 404);
  assert.deepEqual(fetchLog, []);
});
