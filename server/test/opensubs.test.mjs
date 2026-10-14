// The OpenSubtitles v3 addon as a subtitle source (src/opensubs.mjs).
//
// The addon is stubbed at the wire, because that is the only thing this module
// knows about it: one JSON document per title, listing tracks by IMDb id.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { clearCache, languageCode, openSubtitlesTracks } from '../src/opensubs.mjs';

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

afterEach(() => { globalThis.fetch = realFetch; clearCache(); });

// ------------------------------------------------------------------ language

test('a track language is a code a picker can match, not a word', () => {
  assert.equal(languageCode('eng'), 'en');
  // Both spellings occur in the same list, because the addon's own field is
  // derived from opensubtitles.org, which has used both over the years.
  assert.equal(languageCode('ger'), 'de');
  assert.equal(languageCode('deu'), 'de');
  assert.equal(languageCode('por'), 'pt');
  // An unknown code passes through rather than becoming a wrong language.
  assert.equal(languageCode('zzz'), 'zzz');
  assert.equal(languageCode(''), 'unknown');
  assert.equal(languageCode(undefined), 'unknown');
});

// -------------------------------------------------------------------- tracks

test('a movie asks for its own id and answers in our shape', async () => {
  stubFetch([['opensubtitles-v3.strem.io', () => jsonResponse(200, {
    subtitles: [
      { id: '37756', url: 'https://subs5.strem.io/en/download/x/file/45899', lang: 'ger', subtitleFileName: '_matrix_ger2.srt', movieReleaseName: 'Matrix, The (1999)' },
      { id: '18076', url: 'https://subs5.strem.io/en/download/x/file/20484', lang: 'eng', movieReleaseName: 'The Matrix' },
    ],
  })]]);

  const tracks = await openSubtitlesTracks({ imdbId: 'tt0133093' });
  assert.equal(fetchLog.length, 1);
  assert.ok(fetchLog[0].url.endsWith('/subtitles/movie/tt0133093.json'), fetchLog[0].url);
  assert.deepEqual(tracks.map((t) => [t.lang, t.label, t.format]), [
    ['de', 'Matrix, The (1999)', 'srt'],
    // With no release name, the file's name is what tells two tracks apart —
    // and its extension is dropped: the label is a description, not a path.
    ['en', 'The Matrix', 'srt'],
  ]);
});

test('an episode carries its season and episode in the id', async () => {
  stubFetch([['opensubtitles-v3.strem.io', () => jsonResponse(200, {
    subtitles: [{ id: '1', url: 'https://subs5.strem.io/en/download/x/file/1', lang: 'eng' }],
  })]]);

  await openSubtitlesTracks({ imdbId: 'tt0903747', season: 1, episode: 1 });
  assert.ok(fetchLog[0].url.includes('/subtitles/series/tt0903747:1:1.json'), fetchLog[0].url);

  // A film must not be asked with an episode tail, and a series must not be
  // asked without one: each is an empty list on the other's route.
  fetchLog = [];
  await openSubtitlesTracks({ imdbId: 'tt0903747', season: null, episode: null });
  assert.ok(fetchLog[0].url.endsWith('/subtitles/movie/tt0903747.json'), fetchLog[0].url);
});

test('one track per URL, and nothing that is not a file', async () => {
  stubFetch([['opensubtitles-v3.strem.io', () => jsonResponse(200, {
    subtitles: [
      { id: '1', url: 'https://subs5.strem.io/en/download/x/file/1', lang: 'eng' },
      { id: '1b', url: 'https://subs5.strem.io/en/download/x/file/1', lang: 'eng' },   // same file twice
      { id: '2', url: 'http://insecure.example/x.srt', lang: 'eng' },                  // not https
      { id: '3', lang: 'eng' },                                                        // no url at all
      { id: '4', url: 'https://subs5.strem.io/en/download/x/file/4', lang: 'por' },
    ],
  })]]);

  const tracks = await openSubtitlesTracks({ imdbId: 'tt0133093' });
  assert.deepEqual(tracks.map((t) => t.url), [
    'https://subs5.strem.io/en/download/x/file/1',
    'https://subs5.strem.io/en/download/x/file/4',
  ]);
});

test('an id with nothing behind it answers an empty list', async () => {
  stubFetch([['opensubtitles-v3.strem.io', () => jsonResponse(200, { subtitles: [] })]]);
  assert.deepEqual(await openSubtitlesTracks({ imdbId: 'tt0000000' }), []);

  // And nothing at all is asked for without an id to ask with.
  stubFetch([]);
  assert.deepEqual(await openSubtitlesTracks({ imdbId: '' }), []);
  assert.deepEqual(await openSubtitlesTracks({}), []);
  assert.deepEqual(fetchLog, []);
});

test('a list is fetched once and then answered from memory', async () => {
  stubFetch([['opensubtitles-v3.strem.io', () => jsonResponse(200, {
    subtitles: [{ id: '1', url: 'https://subs5.strem.io/x.srt', lang: 'eng' }],
  })]]);

  await openSubtitlesTracks({ imdbId: 'tt0133093' });
  await openSubtitlesTracks({ imdbId: 'tt0133093' });
  assert.equal(fetchLog.length, 1, 'a subtitle list moves with the release, not with the request');
});

test('an upstream failure is not cached as an answer', async () => {
  stubFetch([['opensubtitles-v3.strem.io', () => jsonResponse(503, {})]]);
  await assert.rejects(openSubtitlesTracks({ imdbId: 'tt0133093' }), /status 503/);

  stubFetch([['opensubtitles-v3.strem.io', () => jsonResponse(200, {
    subtitles: [{ id: '1', url: 'https://subs5.strem.io/x.srt', lang: 'eng' }],
  })]]);
  assert.equal((await openSubtitlesTracks({ imdbId: 'tt0133093' })).length, 1);
});
