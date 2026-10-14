// The flixcloud media path, checked against one real session rather than
// against this module's own assumptions.
//
// test/fixtures/flixcloud-capture.json holds what the site handed its own client
// on a real load: the obfuscated payload fields (with the names that load derived
// from its seed), the /api/m3u8 answer, the master URL and playlist key that
// client produced from them, the wrapped playlist body as the CDN served it, and
// the head of one real segment. So these assertions are "does this server reach
// the same bytes the site's own player did", which is the only question that
// matters for playback — a self-consistent decrypt that disagrees with the site
// is still broken.
//
// Nothing here touches the network: the fixture is a capture, and fetch is
// stubbed with it.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  resolveStream,
  subtitleTracks,
  parseSubtitles,
  languageCodeOf,
  decodePlaylist,
  unsealSegment,
  isFlixcloudEmbed,
  resetCache,
  FLIX_REFERER,
} from '../src/flixcloud.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const capture = JSON.parse(fs.readFileSync(path.join(HERE, 'fixtures/flixcloud-capture.json'), 'utf8'));

const realFetch = globalThis.fetch;
let fetchLog = [];

/**
 * The embed page as the site renders it: a JS object literal with bare keys,
 * carrying exactly the fields the capture recorded — the payload is the page's,
 * this is just the frame around it.
 */
function embedPage(embed) {
  return `<!doctype html><html><head><title>Episode</title></head><body>
<script>
  const data = { obfuscation_seed:"${embed.seed}",
    ${embed.tokenField}:"${embed.token}",
    ${embed.keyFrag2Field}:"${embed.keyFrag2Value}",
    ${embed.containerName}:{${embed.arrayName}:[{${embed.objectName}:{${embed.keyField}:"${embed.keyFieldValue}",${embed.ivField}:"${embed.ivFieldValue}"}}]},
    w_payload:"${embed.wPayload}",
    subtitles:${embed.subtitlesRaw ?? '[]'},
    preconnect_origin:"https://fetch8.flixcloud.cc" };
</script></body></html>`;
}

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

const textResponse = (body) => ({ ok: true, status: 200, text: async () => body });
const jsonResponse = (body) => ({ ok: true, status: 200, json: async () => body });

afterEach(() => {
  globalThis.fetch = realFetch;
  resetCache();
});

// --------------------------------------------------------------- the embed

test('only their own embed pages are treated as openable', () => {
  assert.equal(isFlixcloudEmbed('https://flixcloud.cc/e/bo2qdw3m3kjf?v=2'), true);
  assert.equal(isFlixcloudEmbed('https://fetch8.flixcloud.cc/e/abc'), true);
  // A page this server has no reader for is not a page it should try to open,
  // and neither is anything that is not their embed route at all.
  assert.equal(isFlixcloudEmbed('https://flixcloud.cc/watch/bo2qdw3m3kjf'), false);
  assert.equal(isFlixcloudEmbed('https://notflixcloud.cc.evil.example/e/abc'), false);
  assert.equal(isFlixcloudEmbed('http://127.0.0.1:8787/e/abc'), false);
  assert.equal(isFlixcloudEmbed('nonsense'), false);
});

// --------------------------------------------------------- the whole chain

test('the embed payload opens to the same master URL the site\'s client used', async () => {
  stubFetch([
    ['flixcloud.cc/e/', () => textResponse(embedPage(capture.embed))],
    ['/api/m3u8/', () => jsonResponse(capture.api)],
  ]);

  const found = await resolveStream(capture.embed.source);
  assert.equal(found.ok, true, `resolve failed: ${found.code}`);
  assert.equal(found.url, capture.expected.masterUrl);
  // The playlist key travels in the relay link, so it has to survive as bytes.
  assert.equal(Buffer.from(found.key, 'base64url').toString('hex'), capture.expected.playlistKey);

  // Subtitles are on the same page, so a resolve carries them for free — and
  // the resolver must not lose them between the fetch and the cache.
  assert.equal(found.subtitles.length, capture.parsed.subtitles.length);
  assert.deepEqual(found.subtitles[0], capture.parsed.subtitles[0]);

  // The token API is keyed by two SHA-256 prefixes of the token: asking for the
  // wrong pair is how this silently returns "no fields" instead of failing.
  assert.ok(fetchLog.some((c) => c.url === `https://flixcloud.cc/api/m3u8/${capture.embed.token}`));
});

// --------------------------------------------------------------- subtitles

test('the page\'s own subtitle list is read as it was written', () => {
  const tracks = parseSubtitles(`<script>const data = { subtitles:${capture.embed.subtitlesRaw}, seed:"x" };</script>`);

  // The captured page is the fixture, so this is the site's list, not ours:
  // every entry names a CDN file, and the arrays' own fields decide the shape.
  assert.equal(tracks.length, capture.parsed.subtitles.length);
  assert.deepEqual(tracks, capture.parsed.subtitles);
  assert.ok(tracks.every((t) => t.url.startsWith('https://')), 'every track is an https file');
  assert.ok(tracks.some((t) => t.default === true), 'the page marks its default track');
  assert.ok(tracks.every((t) => ['ass', 'srt', 'vtt'].includes(t.format)));
});

test('a page without subtitles is empty, never an error', () => {
  assert.deepEqual(parseSubtitles('<script>const data = { obfuscation_seed:"x" };</script>'), []);
  assert.deepEqual(parseSubtitles(''), []);
  // A challenge page, or one whose payload was truncated mid-array: both must
  // read as "none" rather than throwing inside a resolve.
  assert.deepEqual(parseSubtitles('<script>const data = { subtitles:[{url:"https://x/a.ass"'), []);
  assert.deepEqual(parseSubtitles('<script>const data = { subtitles:[] };</script>'), []);
});

test('a subtitle entry without a usable url is dropped, not guessed at', () => {
  const tracks = parseSubtitles(`<script>const data = { subtitles:[{url:"http://insecure/a.ass",language:"English"},{language:"English"},{url:"https://cdn/b.srt",language:"English (Dub)"}] };</script>`);
  assert.equal(tracks.length, 1);
  assert.equal(tracks[0].url, 'https://cdn/b.srt');
  // No format field on a page is not a reason to lose the track: the file's
  // own extension is what the player would have gone by anyway.
  assert.equal(tracks[0].format, 'srt');
  // The words the page used become the code a picker can match on.
  assert.equal(tracks[0].code, 'en');
  assert.equal(tracks[0].language, 'English (Dub)');
});

test('a language name becomes the code a picker matches, region kept', () => {
  assert.equal(languageCodeOf('English (Dialogues [SoM])'), 'en');
  assert.equal(languageCodeOf('Portuguese'), 'pt');
  assert.equal(languageCodeOf('Portuguese(Brazil)'), 'pt-BR');
  assert.equal(languageCodeOf('Spanish'), 'es');
  assert.equal(languageCodeOf('Spanish(Latin_America)'), 'es-419');
  assert.equal(languageCodeOf('Chinese(Traditional)'), 'zh-TW');
  assert.equal(languageCodeOf('Chinese(Simplified)'), 'zh-CN');
  assert.equal(languageCodeOf('  Arabic '), 'ar');
  // An unknown language keeps the page's own words rather than losing the track
  // behind an invented code; the caller falls back to the name.
  assert.equal(languageCodeOf('Klingon (Reconstructed)'), null);
  assert.equal(languageCodeOf(''), null);
  assert.equal(languageCodeOf(null), null);
});

test('subtitleTracks answers with the same tracks the resolve carried', async () => {
  stubFetch([
    ['flixcloud.cc/e/', () => textResponse(embedPage(capture.embed))],
    ['/api/m3u8/', () => jsonResponse(capture.api)],
  ]);

  const found = await subtitleTracks(capture.embed.source);
  assert.equal(found.ok, true, `subtitles failed: ${found.code}`);
  assert.deepEqual(found.tracks, capture.parsed.subtitles);
  assert.equal(fetchLog.filter((c) => c.url.includes('flixcloud.cc/e/')).length, 1);

  // Asking again does not re-read the page: the tracks travelled with the
  // resolve and are cached with it, which is what lets a player open the
  // subtitle picker mid-episode without a round trip.
  const again = await subtitleTracks(capture.embed.source);
  assert.deepEqual(again.tracks, found.tracks);
  assert.equal(fetchLog.filter((c) => c.url.includes('flixcloud.cc/e/')).length, 1);

  // ...and an embed that cannot be read reports why, instead of answering
  // "no subtitles" — a caller has to be able to tell the two apart.
  stubFetch([['flixcloud.cc/e/', () => textResponse('<html>Just a moment…</html>')]]);
  const broken = await subtitleTracks('https://flixcloud.cc/e/justamoment?v=2');
  assert.equal(broken.ok, false);
  assert.equal(broken.code, 'no_payload');
});

test('a page without the payload is refused, and not cached as an answer', async () => {
  // Cloudflare's challenge page is what a blocked request actually returns: 200,
  // no payload. It must read as "cannot open", not as a decrypt failure.
  stubFetch([['flixcloud.cc/e/', () => textResponse('<html><body>Just a moment…</body></html>')]]);

  const first = await resolveStream(capture.embed.source);
  assert.equal(first.ok, false);
  assert.equal(first.code, 'no_payload');

  // ...and the next attempt has to try again rather than remember the refusal.
  stubFetch([
    ['flixcloud.cc/e/', () => textResponse(embedPage(capture.embed))],
    ['/api/m3u8/', () => jsonResponse(capture.api)],
  ]);
  const second = await resolveStream(capture.embed.source);
  assert.equal(second.ok, true);
});

// ------------------------------------------------------- the wrapped bodies

test('a wrapped playlist decodes with the captured key, and only with it', () => {
  // The fixture keeps the key as hex because that is how it was captured; the
  // link carries it as base64url, which is the form both callers below use.
  const key = Buffer.from(capture.expected.playlistKey, 'hex').toString('base64url');
  const body = Buffer.from(capture.expected.masterBody, 'latin1');
  const decoded = decodePlaylist(body, key);

  assert.ok(decoded, 'the captured master body did not decode');
  assert.equal(decoded.slice(0, capture.expected.masterPlainHead.length), capture.expected.masterPlainHead);
  assert.ok(decoded.includes('#EXT-X-STREAM-INF'), 'no variant list in the playlist');

  // A key that is not the one the site used yields noise, and noise is refused
  // rather than rewritten into a broken playlist.
  const wrong = Buffer.alloc(32, 7);
  assert.equal(decodePlaylist(body, wrong), null);
});

test('a playlist that is already plain text is passed through', () => {
  const plain = '#EXTM3U\n#EXT-X-VERSION:3\n';
  const key = Buffer.from(capture.expected.playlistKey, 'hex').toString('base64url');
  assert.equal(decodePlaylist(Buffer.from(plain), key), plain);
});

test('a segment loses its image header and lands on MPEG-TS', () => {
  const raw = Buffer.from(capture.expected.segmentHead, 'base64');
  assert.equal(raw.subarray(0, 4).toString('latin1'), '\x89PNG', 'the fixture is not a disguised segment');

  const clean = unsealSegment(raw);
  assert.ok(clean, 'the captured segment head did not unseal');
  assert.equal(clean.length, raw.length - 8);

  // 188-byte sync bytes are what makes it transport stream rather than noise —
  // the whole point of undoing the header and the key.
  const packets = Math.floor(clean.length / 188);
  for (let i = 0; i < packets; i += 1) {
    assert.equal(clean[i * 188], 0x47, `packet ${i} does not start on a sync byte`);
  }
});

test('a body that is not a disguised segment is not claimed as one', () => {
  assert.equal(unsealSegment(Buffer.from('#EXTM3U\n#EXT-X-VERSION:3')), null);
  assert.equal(unsealSegment(Buffer.alloc(4096)), null, 'a zero-filled body is not a stream');
});
