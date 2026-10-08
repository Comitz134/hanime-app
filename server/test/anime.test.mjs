// The normal-anime route family, with both upstreams stubbed: AniList for the
// catalog, LunarX for episodes and the player. Nothing here touches the
// network, and one assertion guards the detail that makes the proxy
// necessary at all — LunarX answers400 to any request carrying another
// site's Origin, so the request that leaves this server must speak as
// lunarx.to.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { handleAnime } from '../src/anime.mjs';

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

afterEach(() => { globalThis.fetch = realFetch; });

// ---------------------------------------------------------------- fixtures

const anilistCard = {
  id: 99001,
  title: { romaji: 'Test no Hito' },
  episodes: 12,
  averageScore: 81,
  startDate: { year: 2024 },
  format: 'TV',
  status: 'FINISHED',
  coverImage: { large: 'https://s4.anilist.co/c.jpg' },
  bannerImage: 'https://s4.anilist.co/b.jpg',
  genres: ['Action'],
};

const anilistPage = {
  data: { Page: { pageInfo: { currentPage: 1, hasNextPage: false }, media: [anilistCard] } },
};

const anilistDetails = {
  data: {
    Media: {
      ...anilistCard,
      description: 'A <b>description</b> with<br> a line break.',
      recommendations: {
        // AniList's real shape: `media` is the title being viewed, and
        // `mediaRecommendation` is the suggested one. A fixture that only
        // carried `media` could never catch the rail repeating itself.
        nodes: [{
          media: anilistCard,
          mediaRecommendation: { ...anilistCard, id: 99002, title: { romaji: 'Rec no Hito' } },
        }],
      },
    },
  },
};

const lunarxEpisodes = {
  data: [
    { number: 1, title: 'First', description: 'One.', img: 'https://x/1.jpg', airDate: '2024-01-01', hasSub: true, hasDub: false, runtime: 24 },
    { number: 2, title: 'Second', description: 'Two.', img: null, hasSub: true },
  ],
};

const lunarxPlayer = {
  data: [{ server: 'sv-9', episode: 1, player_url: 'https://embed.example/e/abc?v=2' }],
};

// ---------------------------------------------------------------- routes

test('an empty query answers trending, a query answers search', async () => {
  stubFetch([['graphql.anilist.co', () => jsonResponse(200, anilistPage)]]);

  const trending = fakeRes();
  await handleAnime(new URL('http://x/api/anime/search'), trending, '/api/anime/search');
  assert.equal(trending.status, 200);
  assert.equal(trending.json.items.length, 1);
  assert.equal(trending.json.items[0].title, 'Test no Hito');
  assert.equal(trending.json.items[0].score, 81);
  assert.match(fetchLog[0].opts.body, /TRENDING_DESC/, 'no query must not search');

  const search = fakeRes();
  await handleAnime(new URL('http://x/api/anime/search?q=hero'), search, '/api/anime/search');
  assert.equal(search.json.q, 'hero');
  assert.match(fetchLog[1].opts.body, /search:\s*\$search/, 'a query must search AniList');
});

test('filters ride the query as variables, and junk is dropped before it leaves', async () => {
  stubFetch([['graphql.anilist.co', () => jsonResponse(200, anilistPage)]]);

  const res = fakeRes();
  await handleAnime(
    new URL('http://x/api/anime/search?genre=Action&format=tv&status=RELEASING'),
    res, '/api/anime/search');
  assert.equal(res.status, 200);
  const sent = JSON.parse(fetchLog[0].opts.body);
  assert.match(sent.query, /genre: \$genre/, 'the genre filter never reaches the query');
  assert.match(sent.query, /format: \$format/, 'the format filter never reaches the query');
  assert.match(sent.query, /status: \$status/, 'the status filter never reaches the query');
  // Values travel as variables — never spliced into the query text — and are
  // canonicalized on the way, so upstream sees its own spelling.
  assert.equal(sent.variables.genre, 'Action');
  assert.equal(sent.variables.format, 'TV');
  assert.equal(sent.variables.status, 'RELEASING');

  // An unknown enum would fail the whole query upstream; it never gets there.
  // (A page number of its own keeps this request off the trending cache entry
  // the first test in this file already filled.)
  const junk = fakeRes();
  await handleAnime(new URL('http://x/api/anime/search?format=NOPE&genre=Nope&page=7'),
    junk, '/api/anime/search');
  assert.equal(junk.status, 200);
  const sent2 = JSON.parse(fetchLog[1].opts.body);
  assert.ok(!sent2.query.includes('$format'), 'an unknown format still reached the query');
  assert.ok(!sent2.query.includes('$genre'), 'an unknown genre still reached the query');
});

test('details carry the description text and the recommendations', async () => {
  stubFetch([['graphql.anilist.co', () => jsonResponse(200, anilistDetails)]]);

  const res = fakeRes();
  await handleAnime(new URL('http://x/api/anime/99001'), res, '/api/anime/99001');
  assert.equal(res.status, 200);
  assert.equal(res.json.details.id, 99001);
  // Markup is flattened: the client renders text, never HTML from upstream.
  assert.ok(!res.json.details.description.includes('<'));
  assert.ok(res.json.details.description.includes('line break'));
  assert.equal(res.json.details.recommendations[0].id, 99002);
  assert.equal(res.json.details.recommendations[0].title, 'Rec no Hito');
  assert.ok(res.json.details.recommendations.every((r) => r.id !== res.json.details.id),
    'the watched title must never appear in its own recommendations');
});

test('episodes are proxied as lunarx.to, which is the whole point', async () => {
  stubFetch([['api.lunarx.to/api/animes/v2/episodes', () => jsonResponse(200, JSON.parse(JSON.stringify(lunarxEpisodes)))]]);

  const res = fakeRes();
  await handleAnime(new URL('http://x/api/anime/99002/episodes'), res, '/api/anime/99002/episodes');
  assert.equal(res.status, 200);
  assert.equal(res.json.data[0].title, 'First');
  assert.equal(res.json.data[0].length, 24);

  const sent = fetchLog[0].opts.headers ?? {};
  assert.equal(sent.Origin, 'https://lunarx.to',
    'a foreign Origin is answered with 400 — the proxy must speak as lunarx');
});

test('the player route answers the embed url the site itself would use', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, lunarxPlayer)]]);

  const res = fakeRes();
  await handleAnime(new URL('http://x/api/anime/99003/player?ep=1'), res, '/api/anime/99003/player');
  assert.equal(res.status, 200);
  assert.equal(res.json.sources[0].url, 'https://embed.example/e/abc?v=2');
  assert.match(fetchLog[0].url, /anilist=99003&episode=1/);
});

test('an episode with no player is a404, not an empty page', async () => {
  stubFetch([['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, { data: [] })]]);

  const res = fakeRes();
  await handleAnime(new URL('http://x/api/anime/99004/player?ep=3'), res, '/api/anime/99004/player');
  assert.equal(res.status, 404);
  assert.equal(res.json.error, 'no_player');
});

test('an id that is not a number never reaches an upstream', async () => {
  stubFetch([]);

  const res = fakeRes();
  await handleAnime(new URL('http://x/api/anime/abc'), res, '/api/anime/abc');
  assert.equal(res.status, 404);
  assert.equal(fetchLog.length, 0, 'the route must not ask anything of the network');
});
