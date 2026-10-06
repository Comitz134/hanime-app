// hanime.tv client.
//
// Two upstreams, two different trust levels:
//
//   https://guest.freeanimehentai.net/api/v11/search_hvs   (catalog)
//     Open. No signature, no CSRF, no cookie. Returns the *entire* library in
//     one ~4.5 MB JSON document (~3.4k entries today); every query parameter we
//     have seen is accepted and ignored. We fetch it, cache it, and filter
//     locally — cheaper for them and far faster for us.
//
//   https://auth.hanime.tv/api/v11/handshake               (stream resolution)
//     Gated. Requires x-signature/x-time from the wasm module, an
//     x-csrf-token from ct.hanime.tv, and a matching session cookie.
//
//                  POST { token: seal({ timestamp_unix, directive, slug }) }
//                   -> 200 {"status":"OK"}
//                   -> response header `x-token` = open(...) -> { sources: [...] }
//
// `sources` is a quality-ordered array of HLS master playlists. The 1080p entry
// is a promotion stub with an empty src; free accounts get 720p and below.

import { open, seal } from './token.mjs';
import { signedHeaders, USER_AGENT } from './signer.mjs';

export { USER_AGENT };

export const CATALOG_URL = 'https://guest.freeanimehentai.net/api/v11/search_hvs';
export const API_BASE = 'https://auth.hanime.tv';
export const CSRF_URL = 'https://ct.hanime.tv/csrf-token';
export const SITE_BASE = 'https://hanime.tv';
export const IMAGE_CDN = 'https://hanime-cdn.com';

const CATALOG_TTL_MS = 10 * 60 * 1000;
const SOURCES_TTL_MS = 4 * 60 * 1000; // minted m3u8 tokens are short lived
const FETCH_TIMEOUT_MS = 25_000;

async function timedFetch(url, init = {}) {
  return fetch(url, { ...init, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
}

// --------------------------------------------------------------------------
// Catalog
// --------------------------------------------------------------------------

/** @type {{ at: number, items: any[], bySlug: Map<string, any>, byId: Map<number, any> } | null} */
let catalogCache = null;
let inflightCatalog = null;

function indexCatalog(items) {
  const bySlug = new Map();
  const byId = new Map();
  for (const item of items) {
    if (item?.slug) bySlug.set(item.slug, item);
    if (item?.id != null) byId.set(item.id, item);
  }
  return { at: Date.now(), items, bySlug, byId };
}

/** Fetch (or reuse) the full library. Single-flight: concurrent callers share one request. */
export async function getCatalog({ force = false } = {}) {
  if (!force && catalogCache && Date.now() - catalogCache.at < CATALOG_TTL_MS) {
    return catalogCache;
  }
  if (inflightCatalog) return inflightCatalog;
  inflightCatalog = (async () => {
    const res = await timedFetch(CATALOG_URL, {
      headers: {
        'user-agent': USER_AGENT,
        accept: 'application/json',
        origin: SITE_BASE,
        referer: `${SITE_BASE}/`,
      },
    });
    if (!res.ok) throw new Error(`catalog fetch failed: ${res.status}`);
    const body = await res.json();
    return indexCatalog(Array.isArray(body?.data) ? body.data : []);
  })();
  try {
    catalogCache = await inflightCatalog;
    return catalogCache;
  } finally {
    inflightCatalog = null;
  }
}

// --------------------------------------------------------------------------
// Stream resolution
// --------------------------------------------------------------------------

// Session cookies + csrf token are shared across handshakes. They expire, so we
// re-fetch opportunistically and let a 401/403 force a refresh.
let session = { csrf: null, cookies: new Map(), at: 0 };
let inflightSession = null;

function cookieHeader(jar) {
  return [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
}

function absorbCookies(jar, res) {
  const setCookies = res.headers.getSetCookie?.() ?? [];
  for (const raw of setCookies) {
    const pair = raw.split(';')[0];
    const eq = pair.indexOf('=');
    if (eq > 0) jar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
  }
}

async function ensureSession({ force = false } = {}) {
  if (!force && session.csrf && Date.now() - session.at < 5 * 60 * 1000) return session;
  if (inflightSession) return inflightSession;
  inflightSession = (async () => {
    const cookies = force ? new Map() : session.cookies;
    const headers = await signedHeaders();
    if (cookies.size) headers.cookie = cookieHeader(cookies);
    const res = await timedFetch(CSRF_URL, { headers });
    absorbCookies(cookies, res);
    const body = await res.json().catch(() => null);
    if (!body?.csrf_token) throw new Error(`csrf-token fetch failed: ${res.status}`);
    session = { csrf: body.csrf_token, cookies, at: Date.now() };
    return session;
  })();
  try {
    return await inflightSession;
  } finally {
    inflightSession = null;
  }
}

/** @type {Map<string, { at: number, value: any }>} */
const sourcesCache = new Map();

/**
 * Resolve playable HLS sources for a video slug.
 * @param {string} slug
 * @returns {Promise<{ slug: string, sources: Array<{src:string,label:string,height:number,width:number,kind:string}>, preroll: any, at: number }>}
 */
export async function getSources(slug, { force = false } = {}) {
  const hit = sourcesCache.get(slug);
  if (!force && hit && Date.now() - hit.at < SOURCES_TTL_MS) return hit.value;

  const payload = {
    timestamp_unix: Math.floor(Date.now() / 1000),
    directive: 'htv_player_handshake',
    slug,
  };

  let attempt = 0;
  for (;;) {
    const sess = await ensureSession({ force: attempt > 0 });
    const headers = await signedHeaders({ 'x-csrf-token': sess.csrf });
    if (sess.cookies.size) headers.cookie = cookieHeader(sess.cookies);

    const res = await timedFetch(`${API_BASE}/api/v11/handshake`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ token: seal(payload) }),
    });
    absorbCookies(sess.cookies, res);

    if (res.status === 401 || res.status === 403) {
      if (attempt++ < 1) continue; // stale csrf/session — refresh once and retry
      throw Object.assign(new Error(`handshake rejected (${res.status})`), { status: 502 });
    }
    if (!res.ok) {
      throw Object.assign(new Error(`handshake failed (${res.status})`), { status: 502 });
    }

    const header = res.headers.get('x-token');
    if (!header) throw Object.assign(new Error('handshake returned no x-token'), { status: 502 });

    const decoded = open(header);
    const value = {
      slug,
      sources: (decoded.sources ?? []).filter((s) => s && typeof s.src === 'string'),
      preroll: {
        enabled: !!decoded.is_preroll_enabled,
        variant: decoded.ad_variant ?? null,
      },
      at: Date.now(),
    };
    sourcesCache.set(slug, { at: value.at, value });
    return value;
  }
}

/** Warm the signer + catalog so the first user request is not the one that pays for boot. */
export async function warm() {
  const [cat] = await Promise.all([getCatalog(), ensureSession()]);
  return { videos: cat.items.length };
}
