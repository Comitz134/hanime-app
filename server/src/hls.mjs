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

function encode(value) {
  return Buffer.from(value, 'utf8').toString('base64url');
}

function decode(value) {
  try {
    return Buffer.from(value, 'base64url').toString('utf8');
  } catch {
    return null;
  }
}

/**
 * The signature covers every part of the link, so neither the target nor the
 * headers it is read with can be swapped after the fact. The join is on a
 * character that cannot appear in a URL or a base64url token.
 */
function signatureFor(parts) {
  return crypto.createHmac('sha256', LINK_KEY).update(parts.join('\n')).digest('base64url').slice(0, 22);
}

/** Upstream URL -> opaque, tamper-proof query pair for our own relay. */
export function mangle(absoluteUrl) {
  const u = encode(absoluteUrl);
  return `u=${u}&s=${signatureFor([u, '', ''])}`;
}

/**
 * The same link, for an upstream that only answers a page on its own site.
 * The film doors are like that: their CDN returns 403 unless the request
 * carries their referer, so the link has to carry it too — signed, because a
 * header the client can rewrite is not a header worth sending.
 */
export function mangleVia(absoluteUrl, { referer = null, origin = null } = {}) {
  const u = encode(absoluteUrl);
  const parts = [`u=${u}`];
  if (referer) parts.push(`r=${encode(referer)}`);
  if (origin) parts.push(`o=${encode(origin)}`);
  parts.push(`s=${signatureFor([u, referer ?? '', origin ?? ''])}`);
  return parts.join('&');
}

/**
 * Verify and unwrap a relay link. Returns `{ url, referer, origin }`, or null
 * when the pair is not ours. Nothing about the caller's query is trusted: the
 * target and the headers both come out of the signed payload.
 */
export function unmangleLink(query) {
  const u = query.get('u');
  const s = query.get('s');
  if (!u || !s) return null;

  const referer = query.get('r') ? decode(query.get('r')) : null;
  const origin = query.get('o') ? decode(query.get('o')) : null;
  if (query.get('r') && referer === null) return null;
  if (query.get('o') && origin === null) return null;

  const expect = signatureFor([u, referer ?? '', origin ?? '']);
  const a = Buffer.from(s);
  const b = Buffer.from(expect);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;

  const url = decode(u);
  if (!url || !url.startsWith('https://')) return null;
  return { url, referer, origin };
}

/** Just the target of a relay link, or null. */
export function unmangle(query) {
  return unmangleLink(query)?.url ?? null;
}

/**
 * Headers for reading an upstream. Defaults are hanime's, because that is the
 * upstream with the strictest expectations; a link that carries its own
 * referer/origin replaces them outright rather than blending the two sites'
 * identities together.
 */
function upstreamHeaders(profile, extra = {}) {
  const base = profile?.referer || profile?.origin
    ? {
        'user-agent': USER_AGENT,
        ...(profile.origin ? { origin: profile.origin } : {}),
        ...(profile.referer ? { referer: profile.referer } : {}),
      }
    : {
        'user-agent': USER_AGENT,
        origin: SITE_BASE,
        referer: `${SITE_BASE}/`,
      };
  return { ...base, accept: '*/*', ...extra };
}

export async function fetchUpstream(url, init = {}, profile = null) {
  return fetch(url, {
    ...init,
    headers: upstreamHeaders(profile, init.headers),
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
