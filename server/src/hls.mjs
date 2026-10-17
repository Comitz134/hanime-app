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
  // The empty slots are the profile this link does not carry (referer, origin,
  // key): one signature shape for every link, so `unmangleLink` has one thing to
  // verify and a link without a profile can never be mistaken for one with.
  return `u=${u}&s=${signatureFor([u, '', '', ''])}`;
}

/**
 * The same link, for an upstream that only answers a page on its own site.
 * The film doors are like that: their CDN returns 403 unless the request
 * carries their referer, so the link has to carry it too — signed, because a
 * header the client can rewrite is not a header worth sending.
 *
 * `key` is the same idea for a body rather than a header: flixcloud wraps every
 * playlist with a key that only its own page can derive, so the link that points
 * at one has to carry the key, and be signed, or anyone holding the link could
 * ask this server to unwrap whatever they liked.
 */
export function mangleVia(absoluteUrl, { referer = null, origin = null, key = null } = {}) {
  const u = encode(absoluteUrl);
  const parts = [`u=${u}`];
  if (referer) parts.push(`r=${encode(referer)}`);
  if (origin) parts.push(`o=${encode(origin)}`);
  if (key) parts.push(`k=${encode(key)}`);
  parts.push(`s=${signatureFor([u, referer ?? '', origin ?? '', key ?? ''])}`);
  return parts.join('&');
}

/**
 * Verify and unwrap a relay link. Returns `{ url, referer, origin, key }`, or
 * null when it is not ours. Nothing about the caller's query is trusted: the
 * target, the headers and the key all come out of the signed payload.
 */
export function unmangleLink(query) {
  const u = query.get('u');
  const s = query.get('s');
  if (!u || !s) return null;

  const referer = query.get('r') ? decode(query.get('r')) : null;
  const origin = query.get('o') ? decode(query.get('o')) : null;
  const key = query.get('k') ? decode(query.get('k')) : null;
  if (query.get('r') && referer === null) return null;
  if (query.get('o') && origin === null) return null;
  if (query.get('k') && key === null) return null;

  const expect = signatureFor([u, referer ?? '', origin ?? '', key ?? '']);
  const a = Buffer.from(s);
  const b = Buffer.from(expect);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;

  const url = decode(u);
  if (!url || !url.startsWith('https://')) return null;
  return { url, referer, origin, key };
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

/**
 * The content type for a subtitle body, decided from the signed target's own
 * extension — the file is the provider's, and the client never names it.
 *
 * This is not cosmetic: mpv picks a subtitle demuxer from the type before it
 * looks at the bytes, and `application/octet-stream` on an .ass file is how a
 * perfectly good track ends up in the "no subtitles" pile. A target that is
 * not a subtitle is null, so a caller can fall back to its own sniffing.
 */
export function subtitleContentType(url, upstreamType = null) {
  let path = String(url).toLowerCase();
  try {
    path = new URL(url).pathname.toLowerCase();
  } catch {
    // Not a URL: a path is all this needs.
  }
  if (path.endsWith('.ass') || path.endsWith('.ssa')) return 'text/x-ssa; charset=utf-8';
  if (path.endsWith('.srt')) return 'application/x-subrip; charset=utf-8';
  if (path.endsWith('.vtt')) return 'text/vtt; charset=utf-8';

  // A subtitle whose URL names no file — an addon's download route
  // (`…/subs5.strem.io/en/download/…/file/45899`) is one — still says what it
  // is in its own content-type. That answer is the only one there is, and
  // without it the body would be served as video transport stream and land in
  // the player's "no subtitles" pile.
  const type = String(upstreamType ?? '').toLowerCase();
  if (/subrip/.test(type)) return 'application/x-subrip; charset=utf-8';
  if (/x-ssa|text\/ssa/.test(type)) return 'text/x-ssa; charset=utf-8';
  if (/text\/vtt|webvtt/.test(type)) return 'text/vtt; charset=utf-8';
  return null;
}

export function isPlaylistResponse(res, body) {
  const type = (res.headers.get('content-type') ?? '').toLowerCase();
  if (type.includes('mpegurl')) return true;
  return body.trimStart().startsWith('#EXTM3U');
}

/**
 * A playlist that arrives base64-encoded rather than as text.
 *
 * vixsrc — the player behind the Vidpro door — serves its master, its audio
 * renditions and its variant playlists as base64 of the playlist, because its
 * own loader decodes them in the page. A player that is handed one of those
 * URLs without that loader reads a wall of base64 and reports a broken stream,
 * so the relay decodes it here and rewrites it like any other playlist.
 *
 * No key: unlike flixcloud's wrapper this is plain base64. Returns the text, or
 * null when the body is not one — an image, a segment or a subtitle is left
 * alone, and random base64 that does not decode to a playlist is refused rather
 * than passed on as a mangled one.
 */
export function decodePlainPlaylist(bytes) {
  const raw = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  const text = raw.toString('latin1').trim();
  if (!text || text.length > 512 * 1024) return null;          // playlists are small
  if (!/^[A-Za-z0-9+/=\r\n]+$/.test(text)) return null;
  const decoded = Buffer.from(text, 'base64').toString('latin1');
  return decoded.trimStart().startsWith('#EXTM3U') ? decoded : null;
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
