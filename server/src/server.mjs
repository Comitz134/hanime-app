// Self-hosted hanime proxy. Zero runtime dependencies — node:http only.
//
//   GET /                          web client (public/index.html)
//   GET /api/health                signer + catalog status
//   GET /api/videos                paged/filtered catalog
//   GET /api/videos/:slug          one entry, metadata only
//   GET /api/videos/:slug/sources  resolved HLS master playlists
//   GET /api/tags                  tags with counts
//   GET /api/brands                studios with counts
//   GET /api/public/playlists      search crawled public playlists
//   GET /api/public/playlists/:slug one public playlist, all items
//   GET /api/public/playlists/owners owners present in the index
//   GET /api/public/videos/:slug/playlists which public playlists hold a video
//   GET|POST /api/public/crawl     crawl status / start a discovery pass
//   GET /api/app/version           latest published app release (updater source)
//   GET /api/app/apk/:file         the published APK itself
//   GET /relay?u=&s=               signed HLS relay (playlists, segments, keys)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getCatalog, getSources, warm, SITE_BASE, USER_AGENT } from './hanime.mjs';
import { mangle, unmangle, fetchUpstream, relayPlaylist, relayBinary } from './hls.mjs';
import { account, setCookie, clear as clearSession, sessionInfo, raw as rawSession } from './session.mjs';
import { buildIndex, searchPlaylists, allItems } from './playlists.mjs';
import { crawl, crawlLock, loadIndex as loadCrawlIndex, indexStats as crawlStats } from './playlist-crawl.mjs';
import { versionPayload, streamApk } from './app-release.mjs';
import { handleAnime } from './anime.mjs';
import { handleMalApi, handleMalToken } from './mal.mjs';
import {
  searchPlaylists as searchPublicPlaylists,
  getPlaylist as getPublicPlaylist,
  getPlaylistFilled as getPublicPlaylistFilled,
  listOwners as listPublicOwners,
  playlistsContaining,
  listTags as listPublicTags,
} from './playlist-index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.resolve(HERE, '../public');

// Crawl state for /api/public/crawl. One pass at a time — two concurrent
// passes would race on the same index file.
const CRAWL = { running: false, started_at: null, progress: [], last: null };

// Opt-in access log. Off by default so normal runs stay quiet; set
// LOG_REQUESTS=1 when you need to see what a client is actually asking for —
// which is the only way to tell a blank page from a page that never loaded.
const LOG_REQUESTS = process.env.LOG_REQUESTS === '1';

const PORT = Number(process.env.PORT ?? 8787);
const HOST = process.env.HOST ?? '0.0.0.0';
const API_TOKEN = process.env.API_TOKEN ?? '';

// Support upstream entries that omit the scheme (the handshake returns
// root-relative "/hls/..." paths).
const absolutize = (src) => (src.startsWith('http') ? src : `${SITE_BASE}${src}`);

// --------------------------------------------------------------------------
// helpers
// --------------------------------------------------------------------------

function json(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
  });
  res.end(text);
}

function text(res, status, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'content-type': type, 'access-control-allow-origin': '*' });
  res.end(body);
}

function baseUrl(req) {
  const proto = req.headers['x-forwarded-proto'] ?? 'http';
  const host = req.headers['x-forwarded-host'] ?? req.headers.host ?? `localhost:${PORT}`;
  return `${proto}://${host}`;
}

/** Normalize one catalog record into the shape our clients consume. */
function shape(item) {
  return {
    id: item.id,
    slug: item.slug,
    name: item.name,
    description: item.description ?? null,
    cover: item.cover_url ?? null,
    poster: item.poster_url ?? null,
    brand: item.brand ?? null,
    brand_id: item.brand_id ?? null,
    tags: item.tags ?? [],
    views: item.views ?? 0,
    likes: item.likes ?? 0,
    dislikes: item.dislikes ?? 0,
    downloads: item.downloads ?? 0,
    released_at: item.released_at ?? null,
    released_at_unix: item.released_at_unix ?? 0,
  };
}

const SORTS = new Set(['released_at_unix', 'created_at_unix', 'views', 'likes', 'name']);

// Bodies here are one cookie header, so a few KB is generous. Cap it rather
// than letting an unbounded read become the server's problem.
const MAX_BODY_BYTES = 16 * 1024;

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('request body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8').trim();
      if (!text) return resolve(null);
      try {
        resolve(JSON.parse(text));
      } catch {
        reject(Object.assign(new Error('body is not valid JSON'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

// --------------------------------------------------------------------------
// API handlers
// --------------------------------------------------------------------------

async function handleVideos(url, res) {
  const q = url.searchParams;
  const page = Math.max(0, Number(q.get('page') ?? 0) | 0);
  const perPage = Math.min(100, Math.max(1, Number(q.get('per_page') ?? 30) | 0));
  const needle = (q.get('q') ?? '').trim().toLowerCase();
  const tags = q.getAll('tags').flatMap((t) => t.split(',')).map((t) => t.trim().toLowerCase()).filter(Boolean);
  const brand = (q.get('brand') ?? '').trim().toLowerCase();
  const orderBy = SORTS.has(q.get('order_by')) ? q.get('order_by') : 'released_at_unix';
  const desc = (q.get('ordering') ?? 'desc') !== 'asc';

  const cat = await getCatalog();

  let rows = cat.items;
  if (brand) rows = rows.filter((v) => (v.brand ?? '').toLowerCase() === brand);
  if (tags.length) {
    // AND by default: every requested tag must be present.
    rows = rows.filter((v) => {
      const have = new Set((v.tags ?? []).map((t) => t.toLowerCase()));
      return tags.every((t) => have.has(t));
    });
  }
  if (needle) {
    rows = rows.filter(
      (v) =>
        (v.name ?? '').toLowerCase().includes(needle) ||
        (v.search_titles ?? '').toLowerCase().includes(needle) ||
        (v.brand ?? '').toLowerCase().includes(needle),
    );
  }

  const dir = desc ? -1 : 1;
  rows = [...rows].sort((a, b) => (a[orderBy] > b[orderBy] ? dir : a[orderBy] < b[orderBy] ? -dir : 0));

  const total = rows.length;
  const start = page * perPage;
  json(res, 200, {
    page,
    per_page: perPage,
    total,
    pages: Math.ceil(total / perPage),
    data: rows.slice(start, start + perPage).map(shape),
  });
}

async function handleVideo(slug, res) {
  const cat = await getCatalog();
  const item = cat.bySlug.get(slug);
  if (!item) return json(res, 404, { error: 'not_found', slug });
  json(res, 200, { ...shape(item), watch_url: `${SITE_BASE}/videos/hentai/${slug}` });
}

async function handleSources(slug, req, res) {
  const cat = await getCatalog();
  if (!cat.bySlug.has(slug)) return json(res, 404, { error: 'not_found', slug });

  const resolved = await getSources(slug);
  const origin = baseUrl(req);
  const relay = `${origin}/relay`;

  const sources = resolved.sources
    .filter((s) => s.src)
    .map((s) => ({
      label: s.label,
      height: s.height ?? 0,
      width: s.width ?? 0,
      kind: s.kind ?? 'normal',
      // Hand the client a loopback URL. Every nested URI is rewritten by the
      // relay, so the player never needs upstream headers of its own.
      url: `${relay}?${mangle(absolutize(s.src))}`,
    }))
    .sort((a, b) => b.height - a.height);

  json(res, 200, { slug, resolved_at: new Date(resolved.at).toISOString(), sources });
}

async function handleTags(res) {
  const cat = await getCatalog();
  const counts = new Map();
  for (const v of cat.items) {
    for (const t of v.tags ?? []) counts.set(t, (counts.get(t) ?? 0) + 1);
  }
  const data = [...counts].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count);
  json(res, 200, { total: data.length, data });
}

async function handleBrands(res) {
  const cat = await getCatalog();
  const counts = new Map();
  for (const v of cat.items) {
    if (!v.brand) continue;
    counts.set(v.brand, (counts.get(v.brand) ?? 0) + 1);
  }
  const data = [...counts].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count);
  json(res, 200, { total: data.length, data });
}

// --------------------------------------------------------------------------
// playlists (account-scoped)
// --------------------------------------------------------------------------

// The index is derived from the account payload, so it only needs rebuilding
// when that payload is refreshed. Keyed on the timestamp the payload landed at.
let indexCache = { at: 0, value: null };

async function getIndex() {
  const acct = await account();
  if (!acct.ok) return { ok: false, reason: acct.reason, index: null };
  const stamp = sessionInfo().checked_at ?? '';
  if (indexCache.value && indexCache.at === stamp) {
    return { ok: true, index: indexCache.value, cached: true };
  }
  const index = await buildIndex(acct.payload);
  indexCache = { at: stamp, value: index };
  return { ok: true, index };
}

function playlistSummary(p) {
  return {
    slug: p.slug,
    id: p.id,
    title: p.title,
    mutable: p.mutable,
    cover: p.cover,
    count: p.items.length || p.declared_count || 0,
    resolved: p.resolved,
    unresolved: p.unresolved,
    synthetic: p.synthetic,
    preview: p.items.slice(0, 4).map((i) => ({ slug: i.slug, title: i.title, cover: i.cover })),
  };
}

async function handlePlaylists(url, res) {
  const result = await getIndex();
  if (result.index === null) {
    // Not an error the client should treat as fatal — it means "connect an
    // account", so it answers 200 with the reason attached.
    return json(res, 200, {
      configured: false,
      reason: result.reason,
      playlists: [],
      stats: null,
      session: sessionInfo(),
    });
  }

  const q = url.searchParams.get('q') ?? '';
  const { playlists, matched } = searchPlaylists(result.index, q);
  json(res, 200, {
    configured: true,
    query: matched,
    total: playlists.length,
    playlists: playlists.map((p) => ({
      ...playlistSummary(p),
      match: p.match ?? null,
      match_count: p.match_count ?? null,
    })),
    stats: result.index.stats,
    session: sessionInfo(),
  });
}

async function handlePlaylistItems(slug, res) {
  const result = await getIndex();
  if (result.index === null) {
    return json(res, 409, { error: 'no_session', reason: result.reason, session: sessionInfo() });
  }
  const found = result.index.playlists.find(
    (p) => String(p.slug) === String(slug) || String(p.id) === String(slug),
  );
  if (!found) return json(res, 404, { error: 'not_found', slug });
  json(res, 200, {
    ...playlistSummary(found),
    items: found.items.map((i) => ({
      slug: i.slug ?? null,
      video_id: i.video_id ?? null,
      title: i.title ?? '(unknown title)',
      cover: i.cover ?? null,
      poster: i.poster ?? null,
      brand: i.brand ?? null,
      tags: i.tags ?? [],
      views: i.views ?? 0,
      released_at: i.released_at ?? null,
      added_at: i.added_at ?? null,
      // False means the row exists upstream but is not in the local catalog —
      // surfaced rather than hidden so a stale catalog is visible as such.
      resolved: !!i.resolved,
    })),
  });
}

async function handleSession(url, req, res) {
  const method = req.method ?? 'GET';

  if (method === 'GET') {
    const acct = await account();
    return json(res, 200, {
      ...sessionInfo(),
      live: acct.ok,
      reason: acct.ok ? null : acct.reason,
    });
  }

  if (method === 'DELETE') {
    clearSession();
    indexCache = { at: 0, value: null };
    return json(res, 200, { cleared: true, ...sessionInfo() });
  }

  if (method === 'POST') {
    const body = await readJson(req);
    if (!body?.cookie) return json(res, 400, { error: 'cookie_required' });
    const result = await setCookie(body.cookie);
    if (!result.ok) {
      return json(res, 422, {
        error: 'session_rejected',
        reason: result.reason,
        hint: 'The cookie did not authenticate. Log in on hanime.tv, then copy a fresh cookie header.',
      });
    }
    indexCache = { at: 0, value: null };
    const idx = await getIndex();
    return json(res, 200, {
      ok: true,
      user: result.user?.username ?? null,
      playlists: idx.index?.stats?.total_playlists ?? 0,
      stats: idx.index?.stats ?? null,
    });
  }

  return json(res, 405, { error: 'method_not_allowed' });
}

async function handlePlaylistSearchItems(url, res) {
  const result = await getIndex();
  if (result.index === null) {
    return json(res, 200, { configured: false, reason: result.reason, data: [] });
  }
  const q = (url.searchParams.get('q') ?? '').trim().toLowerCase();
  const items = allItems(result.index).filter((i) =>
    !q ||
    (i.title ?? '').toLowerCase().includes(q) ||
    (i.brand ?? '').toLowerCase().includes(q) ||
    (i.tags ?? []).some((t) => String(t).toLowerCase().includes(q)),
  );
  json(res, 200, { configured: true, query: q, total: items.length, data: items.slice(0, 200) });
}

// --------------------------------------------------------------------------
// public playlists
//
// Playlists on hanime.tv are public to read but have no index and no list
// endpoint — every playlist API path answers 403 for guests, and the only
// writes are behind csrf. Discovery is therefore a crawl, seeded from the
// "Related Playlists" rail on video pages and expanded through owner channels.
// These routes serve that crawl.

async function handlePublicPlaylists(url, res) {
  const index = loadCrawlIndex();
  const result = searchPublicPlaylists(url.searchParams.get('q') ?? '', {
    limit: Math.min(Number(url.searchParams.get('limit') ?? 40) || 40, 200),
    itemsLimit: Math.min(Number(url.searchParams.get('items') ?? 50) || 50, 500),
    includeItems: url.searchParams.get('items') !== '0',
    owner: url.searchParams.get('owner') || null,
    tag: url.searchParams.get('tag') || null,
    index,
  });
  json(res, 200, { ok: true, ...result });
}

async function handlePublicPlaylist(slug, url, res) {
  // Pulls the page if this playlist was only ever discovered. Cheap (one
  // request, ~300 ms) and it means a card advertising 300 titles never opens
  // onto an empty grid.
  const wantFill = url.searchParams.get('fill') !== '0';
  const playlist = wantFill
    ? await getPublicPlaylistFilled(slug, { limit: Math.min(Number(url.searchParams.get('limit') ?? 0) || 0, 2000) })
    : getPublicPlaylist(slug, { index: loadCrawlIndex() });
  if (!playlist) {
    return json(res, 404, {
      error: 'not_found',
      slug,
      hint: 'Only crawled playlists are served. Run POST /api/public/crawl to discover more.',
    });
  }

  // Mark which entries the local catalog can actually play. Done here rather
  // than in the client so an item that is not playable is never rendered as a
  // dead button.
  let bySlug = null;
  try {
    bySlug = (await getCatalog()).bySlug;
  } catch {
    bySlug = null;
  }
  const items = playlist.items.map((i) => ({
    ...i,
    resolved: bySlug ? bySlug.has(i.slug) : null,
  }));

  json(res, 200, {
    ok: true,
    ...playlist,
    items,
    playable: items.filter((i) => i.resolved).length,
    unresolved: items.filter((i) => i.resolved === false).length,
  });
}

async function handlePublicOwners(res) {
  const index = loadCrawlIndex();
  json(res, 200, {
    ok: true,
    owners: listPublicOwners({ index }),
    tags: listPublicTags({ index }),
  });
}

async function handlePublicCrawl(url, req, res) {
  const index = loadCrawlIndex();
  // The lock is machine-wide, so a pass started from the CLI is visible here
  // too — two passes would clobber the same index file.
  const lock = crawlLock();
  if ((req.method ?? 'GET') === 'GET') {
    return json(res, 200, {
      ok: true,
      stats: crawlStats(index),
      crawling: CRAWL.running || Boolean(lock && !lock.stale),
      held_by: lock && !lock.stale ? { pid: lock.pid, started_at: lock.started_at } : null,
      progress: CRAWL.progress,
      last: CRAWL.last,
    });
  }
  if (CRAWL.running) return json(res, 202, { ok: true, started: false, reason: 'already_running' });
  if (lock && !lock.stale) {
    return json(res, 202, {
      ok: true,
      started: false,
      reason: 'locked',
      held_by: { pid: lock.pid, started_at: lock.started_at },
      hint: 'A crawl started elsewhere is still running. Poll GET /api/public/crawl.',
    });
  }

  const body = await readJson(req).catch(() => ({}));
  let seeds = Array.isArray(body?.video_slugs) ? body.video_slugs.filter(Boolean) : [];

  // No explicit seeds: take the next unscanned catalog entries, so repeated
  // calls walk the library instead of re-crawling the same page.
  if (!seeds.length) {
    const count = Math.min(Number(body?.count ?? 40) || 40, 400);
    const cat = await getCatalog();
    const seen = new Set(index.videos_scanned);
    seeds = cat.items.filter((v) => !seen.has(v.slug)).slice(0, count).map((v) => v.slug);
  }

  const opts = {
    videoSlugs: seeds,
    maxPlaylists: Math.min(Number(body?.max_playlists ?? 100) || 100, 600),
    maxChannels: Math.min(Number(body?.max_channels ?? 20) || 20, 200),
    expandChannels: body?.expand_channels !== false,
  };

  CRAWL.running = true;
  CRAWL.started_at = new Date().toISOString();
  CRAWL.progress = [];
  CRAWL.last = null;

  // Fire and forget: a full pass outlives any sensible request timeout, so the
  // pass reports through GET /api/public/crawl while it runs.
  crawl({ ...opts, onProgress: (msg) => { CRAWL.progress.push(msg); if (CRAWL.progress.length > 40) CRAWL.progress.shift(); } })
    .then((out) => { CRAWL.last = { ...out, finished_at: new Date().toISOString() }; })
    .catch((e) => { CRAWL.last = { error: e.message, finished_at: new Date().toISOString() }; })
    .finally(() => { CRAWL.running = false; });

  const outstanding = Object.values(index.playlists).filter((p) => !p.fetched_at && !p.items_file).length;
  json(res, 202, {
    ok: true,
    started: true,
    // What this pass will actually touch. A pass with no video seeds is not a
    // no-op — it is the playlist queue being worked through.
    video_pages: seeds.length,
    playlist_pages: Math.min(opts.maxPlaylists, outstanding),
    outstanding_playlists: outstanding,
    expand_channels: opts.expandChannels,
    max_channels: opts.maxChannels,
    stats_before: crawlStats(index),
  });
}

async function handleVideoPlaylists(slug, res) {
  const found = playlistsContaining(slug);
  json(res, 200, { ok: true, slug, total: found.length, playlists: found });
}

async function handleHealth(res) {
  let catalog = { ok: false };
  try {
    const cat = await getCatalog();
    catalog = { ok: true, videos: cat.items.length, age_seconds: Math.round((Date.now() - cat.at) / 1000) };
  } catch (e) {
    catalog = { ok: false, error: e.message };
  }
  json(res, 200, { ok: catalog.ok, catalog, signer: fs.existsSync(path.resolve(HERE, '../vendor/htv-signer.js')) });
}

// --------------------------------------------------------------------------
// relay
// --------------------------------------------------------------------------

async function handleRelay(url, res, req) {
  const target = unmangle(url.searchParams);
  if (!target) return text(res, 400, 'bad relay link');

  const upstream = await fetchUpstream(target);
  if (!upstream.ok) {
    // Pass the upstream status through so the player can surface it honestly
    // instead of treating a 403 as a decode error.
    res.writeHead(upstream.status, { 'content-type': 'text/plain' });
    res.end(`upstream ${upstream.status}`);
    return;
  }

  const type = (upstream.headers.get('content-type') ?? '').toLowerCase();

  // Segments are served as text/html despite being ~2 MB of binary transport
  // stream, so content-type cannot classify anything here. Buffer the response
  // as raw bytes and sniff for the playlist signature instead.
  //
  // Decoding as text first would corrupt the payload: the body is encrypted
  // AES-128 CBC, and a UTF-8 round trip is lossy for arbitrary bytes.
  const bytes = Buffer.from(await upstream.arrayBuffer());
  const asText = bytes.subarray(0, 64).toString('latin1');

  if (asText.trimStart().startsWith('#EXTM3U')) {
    const origin = baseUrl(req);
    const link = (abs) => `${origin}/relay?${mangle(abs)}`;
    return relayPlaylist(target, link, res, bytes.toString('latin1'));
  }

  res.writeHead(200, {
    'content-type': type.includes('octet-stream') ? 'application/octet-stream' : 'video/mp2t',
    'content-length': bytes.length,
    'access-control-allow-origin': '*',
    'cache-control': 'public, max-age=3600',
  });
  return res.end(bytes);
}

// --------------------------------------------------------------------------
// manga reader pages
// --------------------------------------------------------------------------
//
// The manga area talks to mangafire.to directly — its JSON API answers any
// origin, so only the reader images need a server: their CDN answers a
// hotlink without a mangafire Referer with a 403. Covers are exempt (their
// static CDN allows them) and are used at their real URLs.
//
// The route is an image proxy, so the target is not arbitrary: https only,
// and a host that belongs to their CDN — anything else is refused before a
// byte is fetched.

const MANGA_IMAGE_HOST = /^(?:[\w-]+\.)*mfcdn\d*\.(?:nl|xyz|com)$|^(?:[\w-]+\.)*mangafire\.to$/;

async function handleMangaPage(url, res) {
  let target;
  try { target = new URL(url.searchParams.get('u') ?? ''); } catch { target = null; }
  if (!target || target.protocol !== 'https:') return text(res, 400, 'bad image link');
  if (!MANGA_IMAGE_HOST.test(target.hostname)) return text(res, 403, 'host not allowed');

  const upstream = await fetch(target, {
    headers: {
      'user-agent': USER_AGENT,
      referer: 'https://mangafire.to/',
      accept: 'image/*,*/*;q=0.8',
    },
    signal: AbortSignal.timeout(20_000),
  });
  if (!upstream.ok) return text(res, 502, `upstream ${upstream.status}`);

  const type = (upstream.headers.get('content-type') ?? '').split(';')[0].toLowerCase();
  if (!type.startsWith('image/')) return text(res, 502, 'upstream did not answer an image');

  const bytes = Buffer.from(await upstream.arrayBuffer());
  res.writeHead(200, {
    'content-type': type,
    'content-length': bytes.length,
    'cache-control': 'public, max-age=86400',
    'access-control-allow-origin': '*',
  });
  res.end(bytes);
}

// --------------------------------------------------------------------------
// static
// --------------------------------------------------------------------------

const STATIC_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
};

function serveStatic(pathname, res) {
  const rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const full = path.resolve(PUBLIC_DIR, rel);
  // Containment check: never serve outside public/.
  if (!full.startsWith(PUBLIC_DIR)) return text(res, 403, 'forbidden');
  if (!fs.existsSync(full) || !fs.statSync(full).isFile()) return text(res, 404, 'not found');

  const body = fs.readFileSync(full);
  res.writeHead(200, {
    'content-type': STATIC_TYPES[path.extname(full)] ?? 'application/octet-stream',
    'content-length': body.length,
    'cache-control': 'no-cache',
  });
  res.end(body);
}

// --------------------------------------------------------------------------
// router
// --------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
  const { pathname } = url;

  res.req = req;

  if (LOG_REQUESTS) {
    const started = Date.now();
    res.on('finish', () => {
      console.log(`${req.method} ${url.pathname}${url.search} -> ${res.statusCode} ${Date.now() - started}ms`);
    });
  }

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'content-type, authorization',
      'access-control-allow-methods': 'GET, POST, DELETE, OPTIONS',
    });
    return res.end();
  }

  try {
    if (pathname === '/relay') return await handleRelay(url, res, req);

    // Optional shared-secret gate. Set API_TOKEN when the server is reachable
    // from anywhere but localhost and you do not want it open.
    if (API_TOKEN && pathname.startsWith('/api/')) {
      const supplied = req.headers.authorization?.replace(/^Bearer\s+/i, '') ?? url.searchParams.get('key');
      if (supplied !== API_TOKEN) return json(res, 401, { error: 'unauthorized' });
    }

    // App self-update. The Android shell calls /api/app/version on every cold
    // start, so it must stay cheap and must never throw.
    if (pathname === '/api/app/version') return json(res, 200, versionPayload());
    const apkMatch = /^\/api\/app\/apk\/([A-Za-z0-9._-]+\.apk)$/.exec(pathname);
    if (apkMatch) return streamApk(apkMatch[1], req, res);

    // Public playlist index. Literal children before the :slug pattern.
    if (pathname === '/api/public/crawl') return await handlePublicCrawl(url, req, res);
    if (pathname === '/api/public/playlists') return await handlePublicPlaylists(url, res);
    if (pathname === '/api/public/playlists/owners') return await handlePublicOwners(res);

    const pubPlaylistMatch = /^\/api\/public\/playlists\/([a-z0-9]+)$/.exec(pathname);
    if (pubPlaylistMatch) return await handlePublicPlaylist(pubPlaylistMatch[1], url, res);

    const videoPlaylistsMatch = /^\/api\/public\/videos\/([^/]+)\/playlists$/.exec(pathname);
    if (videoPlaylistsMatch) return await handleVideoPlaylists(decodeURIComponent(videoPlaylistsMatch[1]), res);

    if (pathname === '/api/health') return await handleHealth(res);

    // Manga reader pages, proxied with the Referer their CDN demands.
    if (pathname === '/api/manga/page') return await handleMangaPage(url, res);

    // MyAnimeList: the PKCE exchange and the API pipe. MAL answers no
    // browser origin, so every tracking call crosses the server here.
    if (pathname === '/api/mal/token') return await handleMalToken(url, req, res);
    if (pathname.startsWith('/api/mal/v2/')) return await handleMalApi(url, req, res, pathname);

    // Normal (non-adult) anime: AniList catalog + LunarX episodes/player.
    if (pathname.startsWith('/api/anime/')) return await handleAnime(url, res, pathname);
    if (pathname === '/api/videos') return await handleVideos(url, res);
    if (pathname === '/api/tags') return await handleTags(res);
    if (pathname === '/api/brands') return await handleBrands(res);

    if (pathname === '/api/session') return await handleSession(url, req, res);
    if (pathname === '/api/playlists') return await handlePlaylists(url, res);
    // Literal siblings must be matched before the :slug pattern below.
    if (pathname === '/api/playlists/items') return await handlePlaylistSearchItems(url, res);
    if (pathname === '/api/playlists/debug') return json(res, 200, { session: sessionInfo(), raw: rawSession() });

    const playlistMatch = /^\/api\/playlists\/([^/]+)$/.exec(pathname);
    if (playlistMatch) return await handlePlaylistItems(decodeURIComponent(playlistMatch[1]), res);

    const sourcesMatch = /^\/api\/videos\/([^/]+)\/sources$/.exec(pathname);
    if (sourcesMatch) return await handleSources(decodeURIComponent(sourcesMatch[1]), req, res);

    const videoMatch = /^\/api\/videos\/([^/]+)$/.exec(pathname);
    if (videoMatch) return await handleVideo(decodeURIComponent(videoMatch[1]), res);

    return serveStatic(pathname, res);
  } catch (err) {
    const status = err.status ?? 500;
    if (status >= 500) console.error(`[${pathname}]`, err);
    if (res.headersSent) return res.destroy();
    return json(res, status, { error: 'upstream_error', message: err.message });
  }
});

server.listen(PORT, HOST, async () => {
  console.log(`hanime proxy listening on http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`);
  try {
    const { videos } = await warm();
    console.log(`catalog warm: ${videos} entries, signer loaded`);
  } catch (e) {
    console.error('warm-up failed (server is still up):', e.message);
  }
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log(`\n${sig} — closing`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  });
}

export { server };
