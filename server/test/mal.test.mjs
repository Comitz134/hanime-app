// MyAnimeList, as the desktop app's tracker meets it: the desktop login
// handoff (src/mal.mjs) and the `mal:` ids the tracker's library rows carry
// (src/addon.mjs).
//
// The handoff exists because the desktop app is a native window: it opens the
// system browser at MAL's authorize URL, MAL redirects to this server's own
// origin (the registered redirect URI) with `?code=…&state=…`, and the app
// collects the code by polling for the state it minted. The state is the only
// thing that makes the handover safe, which is what these tests pin down.
//
// The id half: a library row knows a title only as `mal:21`, and every route
// in the addon is keyed by AniList's id. AniList carries MAL's id on the
// record, so one query turns one into the other and the row opens like any
// other title.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { handleAnime } from '../src/anime.mjs';
import {
  DESKTOP_STATE_PREFIX,
  clearPendingDesktopCodes,
  handleDesktopLanding,
  handleMalDesktopCallback,
  isDesktopState,
  stashDesktopCode,
} from '../src/mal.mjs';
import { handleAddon } from '../src/addon.mjs';

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

/** Like fakeRes, for handlers that answer HTML rather than JSON. */
function htmlRes() {
  return {
    status: null,
    headers: null,
    body: null,
    writeHead(status, headers) { this.status = status; this.headers = headers; },
    end(text) { this.body = text; },
  };
}

afterEach(() => {
  globalThis.fetch = realFetch;
  clearPendingDesktopCodes();
});

// ------------------------------------------------------------- the handoff

test('a code the browser dropped is collected once, by its state', async () => {
  assert.equal(stashDesktopCode('st-1', 'code-abc'), true);

  const res = fakeRes();
  await handleMalDesktopCallback(new URL('http://self/api/mal/desktop-callback?state=st-1'), res);
  assert.equal(res.status, 200);
  assert.deepEqual(res.json, { code: 'code-abc', state: 'st-1' });

  // One read, then it is gone — a replayed poll is not a second chance at
  // someone else's code.
  const again = fakeRes();
  await handleMalDesktopCallback(new URL('http://self/api/mal/desktop-callback?state=st-1'), again);
  assert.equal(again.status, 404);
  assert.equal(again.json.error, 'none_pending');
});

test('a state that was never minted answers nothing', async () => {
  stashDesktopCode('st-real', 'code-real');

  const res = fakeRes();
  await handleMalDesktopCallback(new URL('http://self/api/mal/desktop-callback?state=st-other'), res);
  assert.equal(res.status, 404);

  // The real entry is untouched: one stranger's poll does not burn it.
  const real = fakeRes();
  await handleMalDesktopCallback(new URL('http://self/api/mal/desktop-callback?state=st-real'), real);
  assert.equal(real.status, 200);

  // No state at all is the same answer, not a leak of every pending code.
  const blank = fakeRes();
  await handleMalDesktopCallback(new URL('http://self/api/mal/desktop-callback'), blank);
  assert.equal(blank.status, 404);
});

test('a redirect-shaped payload of absurd size is not believed', () => {
  // The route is reachable by anything on this machine, so the stash is
  // bounded: a state longer than any client mints, or a code longer than MAL
  // can issue, is not a redirect MAL produced. (The code bound was once 512,
  // which is *below* what MAL sends — see the next test.)
  assert.equal(stashDesktopCode('x'.repeat(300), 'code'), false);
  assert.equal(stashDesktopCode('st', 'c'.repeat(4097)), false);
  assert.equal(stashDesktopCode('', 'code'), false);
  assert.equal(stashDesktopCode('st', ''), false);
});

test('a code as long as MAL actually issues is accepted and collected', async () => {
  // A live login on 2026-10-11 arrived with a 898-character code. A bound
  // below that silently refused real logins: the browser was shown
  // "signed in" while the app polled forever for a code the server had
  // thrown away.
  const code = 'd'.repeat(898);
  assert.equal(stashDesktopCode('st-long', code), true);

  const res = fakeRes();
  await handleMalDesktopCallback(new URL('http://self/api/mal/desktop-callback?state=st-long'), res);
  assert.equal(res.status, 200);
  assert.equal(res.json.code, code);
});

test('the landing page claims a sign-in only when the code was really held', () => {
  // A refused stash used to still say "you can close this tab", which is what
  // made a refused login look like a stalled one, with nothing to say why.
  const refused = htmlRes();
  const landed = handleDesktopLanding(
    new URL(`http://self/?code=${'c'.repeat(4097)}&state=${DESKTOP_STATE_PREFIX}toolong`),
    refused,
  );
  assert.equal(landed, true);
  assert.equal(refused.status, 200);
  assert.doesNotMatch(refused.body, /close this tab/i);
  assert.match(refused.body, /try again/i);

  const held = htmlRes();
  handleDesktopLanding(
    new URL(`http://self/?code=real-code&state=${DESKTOP_STATE_PREFIX}fine`),
    held,
  );
  assert.match(held.body, /close this tab/i);
});

// The redirect origin is shared with the web client, so a login must say which
// client it belongs to — the state carries that, and the app is the one that
// prefixes it.

test('a desktop login lands on its own page, not the web client', async () => {
  const res = htmlRes();
  const handled = handleDesktopLanding(
    new URL(`http://self/?code=code-xyz&state=${DESKTOP_STATE_PREFIX}abc123`),
    res,
  );
  assert.equal(handled, true);
  assert.equal(res.status, 200);
  assert.match(res.headers['content-type'], /text\/html/);
  // The browser is told its part is done — it is never handed the web client,
  // whose own callback would report a mismatch for a state it never minted.
  assert.match(res.body, /Signed in to MyAnimeList/);
  assert.match(res.body, /close this tab/i);

  // And the code the browser dropped is waiting for the app's own poll.
  const poll = fakeRes();
  await handleMalDesktopCallback(
    new URL(`http://self/api/mal/desktop-callback?state=${DESKTOP_STATE_PREFIX}abc123`),
    poll,
  );
  assert.equal(poll.status, 200);
  assert.equal(poll.json.code, 'code-xyz');
});

test('the web client\'s own redirect is left to the web client', async () => {
  const res = htmlRes();
  const handled = handleDesktopLanding(new URL('http://self/?code=c&state=plain-state'), res);
  // Not ours to answer: the server falls through and serves the client, whose
  // code it consumes from the address bar.
  assert.equal(handled, false);
  assert.equal(res.status, null);
});

test('only a state the app signed is a desktop state', () => {
  assert.equal(isDesktopState(`${DESKTOP_STATE_PREFIX}abc`), true);
  assert.equal(isDesktopState('abc'), false);
  assert.equal(isDesktopState(''), false);
  assert.equal(isDesktopState(null), false);
  assert.equal(isDesktopState(undefined), false);
});

test('a desktop redirect without a code is not answered as one', () => {
  const res = htmlRes();
  // `?state=…&error=access_denied` is MAL saying no: nothing is stashed and
  // the browser is not congratulated for a login that was refused.
  assert.equal(handleDesktopLanding(new URL(`http://self/?state=${DESKTOP_STATE_PREFIX}x&error=access_denied`), res), false);
  assert.equal(res.status, null);
});

// ------------------------------------------------------ mal: ids in the addon

const anilistCard = {
  id: 99001,
  idMal: 20,
  title: { romaji: 'Test no Hito' },
  description: 'A test title.',
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

const lunarxPlayer = {
  data: [{ server: 'sv-2', player_url: 'https://flixcloud.cc/e/abc?v=2' }],
};

/** The `self` the server passes in: this file's own anime routes only. */
async function self(path) {
  const url = new URL(path, 'http://self');
  const res = fakeRes();
  if (url.pathname.startsWith('/api/anime/')) await handleAnime(url, res, url.pathname);
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

test('a MAL library row opens as the title it names', async () => {
  stubFetch([
    ['graphql.anilist.co', () => jsonResponse(200, { data: { Media: anilistCard } })],
    ['api.lunarx.to/api/animes/v2/episodes', () => jsonResponse(200, { data: [] })],
  ]);

  // The shape the desktop app's tracker asks with: `mal:21`, no `ha:` prefix.
  const res = await call('/addon/meta/series/mal%3A20.json');
  assert.equal(res.status, 200);

  // AniList was asked by MAL's id…
  assert.ok(fetchLog.some((c) => c.url.includes('graphql.anilist.co') && c.opts.body.includes('idMal')), 'asked AniList by idMal');

  // …and the answer carries AniList's own id, so every later request —
  // streams, subtitles, episodes — leaves the id space the routes speak.
  assert.equal(res.json.meta.id, 'ha:an:99001');
  assert.equal(res.json.meta.name, 'Test no Hito');
});

test('a MAL row plays its episode through the anime area', async () => {
  stubFetch([
    ['graphql.anilist.co', () => jsonResponse(200, { data: { Media: anilistCard } })],
    ['api.lunarx.to/api/3rdprovider', () => jsonResponse(200, lunarxPlayer)],
  ]);

  const res = await call('/addon/stream/series/mal%3A20%3A1%3A5.json');
  assert.equal(res.status, 200);
  // Episode five of the MAL row is episode five of the AniList title.
  assert.ok(fetchLog.some((c) => c.url.includes('api/3rdprovider') && c.url.includes('anilist=99001') && c.url.includes('episode=5')));
});

test('a MAL id the catalogue has no record of is nothing, not an error', async () => {
  stubFetch([
    ['graphql.anilist.co', () => jsonResponse(200, { data: { Media: null } })],
  ]);

  // "No such title" is the addon's normal 404 — the same answer any unknown
  // id gets — so the app shows its empty state instead of a broken row.
  const meta = await call('/addon/meta/series/mal%3A424242.json');
  assert.equal(meta.status, 404);
  assert.equal(meta.json.error, 'not_found');

  const streams = await call('/addon/stream/series/mal%3A424242%3A1%3A1.json');
  assert.equal(streams.status, 200);
  assert.deepEqual(streams.json.streams, []);
});
