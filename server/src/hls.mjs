// HLS relay.
//
// Upstream layout, confirmed live:
//
//   master playlist  https://hanime.tv/hls/<id>/<opaque>            (no extension)
//     -> variant URIs are absolute, pointing at p00.htv-tsukuyomi.com
//   media playlist   https://p00.htv-tsukuyomi.com/.../segs/...html (no extension)
//     -> #EXT-X-KEY:METHOD=AES-128,URI="https://ct.htv-services.com/sign.bin"
//     -> segments are ~2 MB files served as text/html
//
// So three things have to be relayed: the playlist chain, the AES key, and the
// segments. Every upstream read needs the hanime referer/origin pair, and every
// URI inside a playlist has to be rewritten to a loopback URL or the player
// will talk to upstream directly (and get 403'd).
//
// Playlists are rewritten link-by-link. Segment and key bodies are piped
// straight through — never buffered whole, since a season is multiple GB.

import crypto from 'node:crypto';
import { USER_AGENT, SITE_BASE } from './hanime.mjs';

const FETCH_TIMEOUT_MS = 60_000;

// Per-boot signing key. Tokens are not meant to survive a restart; a client
// holding a stale one simply re-resolves from /api.
const LINK_KEY = crypto.randomBytes(32);

/** Upstream URL -> opaque, tamper-proof query pair for our own relay. */
export function mangle(absoluteUrl) {
  const u = Buffer.from(absoluteUrl, 'utf8').toString('base64url');
  const sig = crypto.createHmac('sha256', LINK_KEY).update(u).digest('base64url').slice(0, 22);
  return `u=${u}&s=${sig}`;
}

/** Verify and unwrap a relay query pair. Returns null if the pair is not ours. */
export function unmangle(query) {
  const u = query.get('u');
  const s = query.get('s');
  if (!u || !s) return null;
  const expect = crypto.createHmac('sha256', LINK_KEY).update(u).digest('base64url').slice(0, 22);
  const a = Buffer.from(s);
  const b = Buffer.from(expect);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const url = Buffer.from(u, 'base64url').toString('utf8');
    return url.startsWith('https://') ? url : null;
  } catch {
    return null;
  }
}

function upstreamHeaders(extra = {}) {
  return {
    'user-agent': USER_AGENT,
    origin: SITE_BASE,
    referer: `${SITE_BASE}/`,
    accept: '*/*',
    ...extra,
  };
}

export async function fetchUpstream(url, init = {}) {
  return fetch(url, {
    ...init,
    headers: upstreamHeaders(init.headers),
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
}

/**
 * Rewrite one playlist. Handles both master (#EXT-X-STREAM-INF + URI line) and
 * media (#EXT-X-KEY URI, segment URIs) forms, since both appear in this chain.
 *
 * @param {string} text    raw playlist
 * @param {string} baseUrl upstream URL it came from (for relative URIs)
 * @param {(abs:string) => string} link resolves an absolute upstream URL to our relay path
 */
export function rewritePlaylist(text, baseUrl, link) {
  const out = [];
  const lines = text.split(/\r?\n/);

  for (const line of lines) {
    const trimmed = line.trim();

    if (!trimmed) {
      out.push(line);
      continue;
    }

    // Detached attributes (key, media, map) carry URIs inside the tag.
    if (trimmed.startsWith('#')) {
      out.push(
        trimmed.replace(/URI="([^"]+)"/g, (_m, uri) => `URI="${link(new URL(uri, baseUrl).href)}"`),
      );
      continue;
    }

    // Bare line: either a variant playlist or a segment.
    out.push(link(new URL(trimmed, baseUrl).href));
  }

  return out.join('\n');
}

export function isPlaylistResponse(res, body) {
  const type = (res.headers.get('content-type') ?? '').toLowerCase();
  if (type.includes('mpegurl')) return true;
  return body.trimStart().startsWith('#EXTM3U');
}

export async function relayPlaylist(url, link, res, text) {
  const rewritten = rewritePlaylist(text, url, link);
  res.writeHead(200, {
    'content-type': 'application/vnd.apple.mpegurl',
    'content-length': Buffer.byteLength(rewritten),
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
  });
  res.end(rewritten);
}

/** Stream a non-playlist body (segment, key) straight through, no buffering. */
export async function relayBinary(upstream, res, { contentType = 'application/octet-stream', cacheable = true } = {}) {
  const headers = {
    'content-type': upstream.headers.get('content-type') ?? contentType,
    'access-control-allow-origin': '*',
    'cache-control': cacheable ? 'public, max-age=3600' : 'no-store',
  };
  const len = upstream.headers.get('content-length');
  if (len) headers['content-length'] = len;

  res.writeHead(upstream.status, headers);

  if (!upstream.body) {
    res.end();
    return;
  }
  // Node >=18 exposes the web ReadableStream; pipe with backpressure intact.
  const { Readable } = await import('node:stream');
  const nodeStream = Readable.fromWeb(upstream.body);
  nodeStream.on('error', () => res.destroy());
  res.on('close', () => nodeStream.destroy());
  nodeStream.pipe(res);
}
