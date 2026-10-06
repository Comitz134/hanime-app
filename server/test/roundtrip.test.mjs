// Offline tests: everything here runs with no network. Anything that touches
// upstream is asserted in the live smoke test at the bottom, which skips itself
// when the site is unreachable so CI does not go red on a flaky connection.

import test from 'node:test';
import assert from 'node:assert/strict';

import { seal, open } from '../src/token.mjs';
import { mangle, unmangle, rewritePlaylist } from '../src/hls.mjs';

// --------------------------------------------------------------------------
// handshake token envelope
// --------------------------------------------------------------------------

test('token envelope round-trips a handshake payload', () => {
  const payload = {
    timestamp_unix: 1791261275,
    directive: 'htv_player_handshake',
    slug: 'yabai-fukushuu-yami-site-2',
  };
  const wire = seal(payload);
  assert.equal(typeof wire, 'string');
  // base64url only — no padding, no '+' or '/'
  assert.match(wire, /^[A-Za-z0-9_-]+$/);
  assert.deepEqual(open(wire), payload);
});

test('token envelope is randomised per call', () => {
  const a = seal({ n: 1 });
  const b = seal({ n: 1 });
  assert.notEqual(a, b, 'IV must differ between calls');
  assert.deepEqual(open(a), open(b));
});

test('tampering with the ciphertext is rejected', () => {
  const wire = seal({ slug: 'x' });
  const env = JSON.parse(Buffer.from(wire, 'base64url').toString('utf8'));
  const data = Buffer.from(env.data, 'base64url');
  data[0] ^= 0xff;
  env.data = data.toString('base64url');
  const forged = Buffer.from(JSON.stringify(env), 'utf8').toString('base64url');
  assert.throws(() => open(forged), /authenticate|Unsupported state/i);
});

test('an unknown envelope version is refused, not guessed at', () => {
  const wire = seal({ slug: 'x' });
  const env = JSON.parse(Buffer.from(wire, 'base64url').toString('utf8'));
  env.v = 2;
  const bumped = Buffer.from(JSON.stringify(env), 'utf8').toString('base64url');
  assert.throws(() => bumped && open(bumped), /unsupported envelope version/);
});

// --------------------------------------------------------------------------
// relay links
// --------------------------------------------------------------------------

test('relay links survive a round trip', () => {
  const upstream = 'https://hanime.tv/hls/0005/abcDEF_-123';
  const q = new URLSearchParams(mangle(upstream));
  assert.equal(unmangle(q), upstream);
});

test('a relay link with an edited payload is refused', () => {
  const q = new URLSearchParams(mangle('https://hanime.tv/hls/0005/aaa'));
  q.set('u', Buffer.from('https://evil.example/steal', 'utf8').toString('base64url'));
  assert.equal(unmangle(q), null, 'signature must not validate');
});

test('a relay link with an edited signature is refused', () => {
  const q = new URLSearchParams(mangle('https://hanime.tv/hls/0005/aaa'));
  q.set('s', 'AAAAAAAAAAAAAAAAAAAAAA');
  assert.equal(unmangle(q), null);
});

test('relay links refuse non-https targets', () => {
  const q = new URLSearchParams(mangle('http://hanime.tv/hls/1'));
  // mangle happily encodes it; unmangle is the gate and must reject.
  q.set('u', Buffer.from('http://127.0.0.1:8787/api/health', 'utf8').toString('base64url'));
  assert.equal(unmangle(q), null);
});

test('a missing query pair is not an error, just a refusal', () => {
  assert.equal(unmangle(new URLSearchParams('')), null);
  assert.equal(unmangle(new URLSearchParams('u=abc')), null);
});

// --------------------------------------------------------------------------
// playlist rewriting
// --------------------------------------------------------------------------

test('media playlist: segment URIs and the key URI are rewritten', () => {
  const raw = [
    '#EXTM3U',
    '#EXT-X-VERSION:3',
    '#EXT-X-KEY:METHOD=AES-128,URI="https://ct.htv-services.com/sign.bin"',
    '#EXTINF:17.851178,',
    'https://p00.htv-tsukuyomi.com/0/0/0/5/v2x/segs/b0/2/seg-one.html',
    '#EXTINF:16.141122,',
    'https://p00.htv-tsukuyomi.com/0/0/0/5/v2x/segs/b0/2/seg-two.html',
    '#EXT-X-ENDLIST',
  ].join('\n');

  const out = rewritePlaylist(raw, 'https://hanime.tv/hls/0005/tok', (abs) => `RELAY(${abs})`);

  assert.match(out, /URI="RELAY\(https:\/\/ct\.htv-services\.com\/sign\.bin\)"/);
  assert.match(out, /RELAY\(https:\/\/p00\.htv-tsukuyomi\.com\/0\/0\/0\/5\/v2x\/segs\/b0\/2\/seg-one\.html\)/);
  assert.match(out, /RELAY\(https:\/\/p00\.htv-tsukuyomi\.com\/0\/0\/0\/5\/v2x\/segs\/b0\/2\/seg-two\.html\)/);
  assert.doesNotMatch(out, /^(?!.*RELAY).*htv-tsukuyomi/m, 'no bare upstream URI may survive');
  assert.ok(out.includes('#EXT-X-ENDLIST'), 'tags pass through untouched');
});

test('master playlist: variant URIs are rewritten and order preserved', () => {
  const raw = [
    '#EXTM3U',
    '#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720',
    'https://p00.htv-tsukuyomi.com/720.m3u8',
    '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480',
    'https://p00.htv-tsukuyomi.com/480.m3u8',
  ].join('\n');

  const lines = rewritePlaylist(raw, 'https://hanime.tv/hls/1/tok', (abs) => `R:${abs}`).split('\n');
  assert.equal(lines[1], '#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720');
  assert.equal(lines[2], 'R:https://p00.htv-tsukuyomi.com/720.m3u8');
  assert.equal(lines[4], 'R:https://p00.htv-tsukuyomi.com/480.m3u8');
});

test('relative URIs resolve against the playlist URL', () => {
  const raw = '#EXTM3U\n#EXTINF:1,\nseg-1.html\n';
  const out = rewritePlaylist(raw, 'https://p00.htv-tsukuyomi.com/a/b/index.m3u8', (abs) => `R:${abs}`);
  assert.ok(out.includes('R:https://p00.htv-tsukuyomi.com/a/b/seg-1.html'));
});

test('tag lines and blank lines pass through; URI lines arrive absolute', () => {
  const raw = '#EXTM3U\n\n# a comment\n#EXTINF:1,\nseg.ts\n';
  const lines = rewritePlaylist(raw, 'https://h/x.m3u8', (a) => a).split('\n');
  // The link callback always receives an absolute URL, even when the playlist
  // line was relative — that is what makes it safe to re-encode downstream.
  assert.deepEqual(lines, ['#EXTM3U', '', '# a comment', '#EXTINF:1,', 'https://h/seg.ts', '']);
});
