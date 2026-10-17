// The film resolver, tested where it can be tested without a browser: which
// binary it would borrow, which request counts as "the video", and how a relay
// link carries the door's referer without letting anyone edit it.
//
// Nothing here spawns a process. The part that does — CDP over a headless
// Chrome — is exercised by hand against a real door, because a test that needs
// a browser installed is a test that fails on the machine that has none.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  browserCandidates,
  browserAvailable,
  isPlaylistUrl,
  looksLikeMedia,
  playlistBody,
  refererFromHeaders,
} from '../src/browser.mjs';
import { mangle, mangleVia, unmangle, unmangleLink } from '../src/hls.mjs';

// --------------------------------------------------------------------------
// choosing a browser
// --------------------------------------------------------------------------

test('HANIME_BROWSER wins when it names a real file', () => {
  const previous = process.env.HANIME_BROWSER;
  process.env.HANIME_BROWSER = import.meta.filename;   // this test file exists
  try {
    assert.equal(browserCandidates()[0], import.meta.filename);
    assert.equal(browserAvailable(), true);
  } finally {
    if (previous === undefined) delete process.env.HANIME_BROWSER;
    else process.env.HANIME_BROWSER = previous;
  }
});

test('a path that does not exist is not a browser', () => {
  const previous = process.env.HANIME_BROWSER;
  process.env.HANIME_BROWSER = '/definitely/not/here/chrome';
  try {
    assert.ok(!browserCandidates().includes('/definitely/not/here/chrome'));
  } finally {
    if (previous === undefined) delete process.env.HANIME_BROWSER;
    else process.env.HANIME_BROWSER = previous;
  }
});

// --------------------------------------------------------------------------
// what counts as the video
// --------------------------------------------------------------------------

test('playlist URLs are recognised, whatever they hang off', () => {
  assert.ok(isPlaylistUrl('https://moon.zenoak.top/vd/AAAA/master.m3u8'));
  assert.ok(isPlaylistUrl('https://cdn.example/video/index.m3u8?token=1'));
  assert.ok(isPlaylistUrl('https://cdn.example/video/index.m3u8#frag'));
  // Case matters in URLs, not in the extension people write.
  assert.ok(isPlaylistUrl('https://cdn.example/video/MASTER.M3U8'));
});

test('a segment or a page is not the video', () => {
  assert.ok(!isPlaylistUrl('https://zenglobe.top/vd/AAAA/seg-1-s1080p.m4s'));
  assert.ok(!isPlaylistUrl('https://vidcore.io/movie/911430'));
  // "m3u8" as part of a longer word is not a playlist.
  assert.ok(!isPlaylistUrl('https://example.com/notes.m3u8x'));
});

test('the first playlist request is the one worth keeping', () => {
  // A master playlist names its variants; those arrive later and are also
  // playlists. Order is the only thing that tells them apart, which is why the
  // resolver resolves on the first match and stops.
  const seen = [
    'https://cdn.example/index.m3u8',
    'https://cdn.example/sd/41/index-s1080p-v1-a1.m3u8',
  ];
  const first = seen.find(isPlaylistUrl);
  assert.equal(first, 'https://cdn.example/index.m3u8');
});

test('a playlist is recognised by what it holds, not by its name', () => {
  const plain = '#EXTM3U\n#EXTINF:1,\n';
  assert.equal(playlistBody(plain), plain);

  // Vidpro's whole playlist chain arrives base64-encoded, because its own
  // loader decodes it in the page. A resolver that only watched for `.m3u8`
  // never saw it, which is why the door looked unplayable.
  const master = '#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="https://vixsrc.to/playlist/1?type=audio"\n';
  const encoded = Buffer.from(master, 'latin1').toString('base64');
  assert.equal(playlistBody(encoded), master);

  // What is not a playlist stays not a playlist: their API's own JSON, an
  // empty body, base64 of some other text, and a body too big to be one.
  assert.equal(playlistBody('{"src":"/embed/214325?token=x"}'), null);
  assert.equal(playlistBody(''), null);
  assert.equal(playlistBody(null), null);
  assert.equal(playlistBody(Buffer.from('hello world').toString('base64')), null);
  assert.equal(playlistBody(Buffer.from(`#EXTM3U\n${'x'.repeat(600 * 1024)}`).toString('base64')), null);
});

test('media is never read looking for a manifest', () => {
  // Their segments wear a page's extension and a start-time counter: the shape
  // is what gives them away, not the suffix.
  assert.ok(looksLikeMedia('https://sc-b1-24.gentlefalcon17.boats/hls/7/2/78/uuid/video/720p/0000-0750.html'));
  assert.ok(looksLikeMedia('https://sc-b1-24.gentlefalcon17.boats/hls/7/2/78/uuid/audio/eng/0001-0100.m4s?token=x'));
  assert.ok(looksLikeMedia('https://cdn.example/seg.ts'));
  assert.ok(looksLikeMedia('https://cdn.example/hls/thumbnails/thumbnails.vtt'));
  assert.ok(looksLikeMedia('https://cdn.example/still.jpg'));
  assert.ok(looksLikeMedia('https://cdn.example/app.css'));

  // The endpoint the manifest actually hides behind looks like nothing at all,
  // and it is read: that is the whole point of the second look.
  assert.ok(!looksLikeMedia('https://vixsrc.to/playlist/214325?b=1&token=x'));
  assert.ok(!looksLikeMedia('https://vixsrc.to/api/movie/603'));
  assert.ok(!looksLikeMedia('https://vixsrc.to/embed/214325?token=x&t=VGhlIE1hdHJpeA%3D%3D'));
});

// --------------------------------------------------------------------------
// the referer that makes the CDN answer
// --------------------------------------------------------------------------

test('the referer is read whatever case the header arrived in', () => {
  assert.equal(refererFromHeaders({ Referer: 'https://vidcore.io/movie/1' }), 'https://vidcore.io/movie/1');
  assert.equal(refererFromHeaders({ referer: 'https://vidcore.io/' }), 'https://vidcore.io/');
  assert.equal(refererFromHeaders({ 'user-agent': 'x' }), null);
  assert.equal(refererFromHeaders(), null);
});

// --------------------------------------------------------------------------
// relay links that remember where they came from
// --------------------------------------------------------------------------

test('a relay link can carry a referer, and it survives the round trip', () => {
  const url = 'https://moon.zenoak.top/vd/AAAA/master.m3u8';
  const q = new URLSearchParams(mangleVia(url, { referer: 'https://vidcore.io/' }));
  const link = unmangleLink(q);
  assert.equal(link.url, url);
  assert.equal(link.referer, 'https://vidcore.io/');
  assert.equal(unmangle(q), url, 'the plain accessor still returns just the target');
});

test('editing the referer on a link invalidates it', () => {
  const q = new URLSearchParams(mangleVia('https://moon.zenoak.top/vd/AAAA/master.m3u8', {
    referer: 'https://vidcore.io/',
  }));
  q.set('r', Buffer.from('https://evil.example/', 'utf8').toString('base64url'));
  assert.equal(unmangleLink(q), null);
});

test('adding a referer to a plain link invalidates it', () => {
  const q = new URLSearchParams(mangle('https://moon.zenoak.top/vd/AAAA/master.m3u8'));
  q.set('r', Buffer.from('https://vidcore.io/', 'utf8').toString('base64url'));
  assert.equal(unmangleLink(q), null, 'the signature covers the empty profile too');
});

test('a link whose target is not https is refused, referer or not', () => {
  const q = new URLSearchParams(mangleVia('http://127.0.0.1:8787/api/health', { referer: 'https://x/' }));
  assert.equal(unmangleLink(q), null);
});

// A key on a link is a decryption key for the body behind it, so it is signed
// like everything else: a link that could be re-pointed at another body, or have
// its key swapped, would be an oracle for unwrapping anything flixcloud serves.

test('a relay link can carry a playlist key, and it survives the round trip', () => {
  const key = Buffer.alloc(32, 5).toString('base64url');
  const q = new URLSearchParams(mangleVia('https://fetch8.flixcloud.cc/v/master.m3u8', {
    referer: 'https://flixcloud.cc/',
    key,
  }));
  const link = unmangleLink(q);
  assert.equal(link.key, key);
  assert.equal(link.referer, 'https://flixcloud.cc/');
});

test('editing the key on a link invalidates it', () => {
  const q = new URLSearchParams(mangleVia('https://fetch8.flixcloud.cc/v/master.m3u8', {
    referer: 'https://flixcloud.cc/',
    key: Buffer.alloc(32, 5).toString('base64url'),
  }));
  q.set('k', Buffer.alloc(32, 9).toString('base64url'));
  assert.equal(unmangleLink(q), null);
});

test('adding a key to a plain link invalidates it', () => {
  const q = new URLSearchParams(mangle('https://fetch8.flixcloud.cc/v/master.m3u8'));
  q.set('k', Buffer.alloc(32, 5).toString('base64url'));
  assert.equal(unmangleLink(q), null, 'the signature covers the empty key too');
});
