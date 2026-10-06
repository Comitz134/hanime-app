// Public playlist crawler.
//
// Correcting an earlier wrong conclusion: playlists ARE public. The mistake was
// probing `/playlists/<guessed-slug>` (404) and `/playlists` (404) and stopping
// there. Real slugs are opaque 20-character ids and there is no index page —
// but a playlist page renders for anyone:
//
//   GET /playlists/e6c0fz4fzjpzl46u9hpc -> 200, 427 KB of SSR HTML
//   island payload: playlist{title, visibility:"public", count:263},
//                   playlist_owner{name, slug, user_channel}, 263 items
//
// Reading is server-rendered only. Every playlist endpoint the page's own
// chunk calls is a *write* (PUT/POST/DELETE with csrf), and the guest API
// answers 403 for every playlist path. So discovery means crawling.
//
// Three seed sources, all public:
//
//   1. video pages    — each lists playlists containing that video, with the
//                       owner's name, avatar and channel link in data attributes
//   2. playlist pages — the full record plus the owner's channel
//   3. channel pages  — that owner's other public playlists
//
// Storage is split, because a full crawl is large:
//
//   data/playlist-index.json   metadata for every playlist, plus a lowercased
//                              text blob of its item titles for content
//                              search. Parsed once and cached in memory.
//   data/playlist-items/<slug>.json
//                              the items of one playlist, read on demand.
//
// Keeping items in the metadata file instead would put a whole-library crawl
// at roughly 680 MB in a single JSON document, re-parsed on every request.
// Split, the metadata file stays small enough to hold resident and searching
// never touches a per-playlist file.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { USER_AGENT, SITE_BASE } from './hanime.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = process.env.DATA_DIR ?? path.resolve(HERE, '../data');
const INDEX_FILE = path.join(DATA_DIR, 'playlist-index.json');
const ITEMS_DIR = path.join(DATA_DIR, 'playlist-items');

const LOCK_FILE = path.join(DATA_DIR, '.crawl.lock');

const CONCURRENCY = Number(process.env.CRAWL_CONCURRENCY ?? 4);
const DELAY_MS = Number(process.env.CRAWL_DELAY_MS ?? 220);
const TIMEOUT_MS = 25_000;

// --------------------------------------------------------------------------
// lock
//
// saveIndex rewrites the whole metadata file, so two passes running at once
// would clobber each other. A pid-based lock file enforces one pass per
// machine, whether it was started from the CLI or from POST /api/public/crawl.
// --------------------------------------------------------------------------

function pidAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

export function crawlLock() {
  try {
    const raw = JSON.parse(fs.readFileSync(LOCK_FILE, 'utf8'));
    if (pidAlive(raw.pid)) return raw;
    return { ...raw, stale: true };
  } catch {
    return null;
  }
}

function acquireLock() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const held = crawlLock();
  if (held && !held.stale) {
    throw Object.assign(
      new Error(`a crawl is already running (pid ${held.pid}, started ${held.started_at})`),
      { code: 'CRAWL_LOCKED' },
    );
  }
  fs.writeFileSync(LOCK_FILE, JSON.stringify({ pid: process.pid, started_at: new Date().toISOString() }));
}

function releaseLock() {
  try {
    const raw = JSON.parse(fs.readFileSync(LOCK_FILE, 'utf8'));
    if (raw.pid === process.pid) fs.unlinkSync(LOCK_FILE);
  } catch {
    /* already gone */
  }
}

// --------------------------------------------------------------------------
// store
// --------------------------------------------------------------------------

function emptyIndex() {
  return {
    version: 2,
    updated_at: null,
    playlists: {},      // slug -> metadata (never carries items)
    channels: {},       // channel slug -> { title, playlists, fetched_at }
    videos_scanned: [], // catalog slugs whose page has been harvested
  };
}

let cache = { at: 0, mtime: 0, value: null };

/**
 * Read the metadata index, cached by file mtime.
 *
 * The file only changes when a crawl writes it, so this costs one read per
 * crawl rather than one per request.
 */
export function loadIndex({ fresh = false } = {}) {
  try {
    if (!fs.existsSync(INDEX_FILE)) return emptyIndex();
    const mtime = fs.statSync(INDEX_FILE).mtimeMs;
    if (!fresh && cache.value && cache.mtime === mtime) return cache.value;

    const raw = JSON.parse(fs.readFileSync(INDEX_FILE, 'utf8'));
    const index = { ...emptyIndex(), ...raw };
    cache = { at: Date.now(), mtime, value: index };
    return index;
  } catch (e) {
    console.warn('playlist index unreadable, starting fresh —', e.message);
    return emptyIndex();
  }
}

/** Write the metadata index atomically; items live in their own files. */
export function saveIndex(index) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  index.updated_at = new Date().toISOString();

  const slim = {
    version: 2,
    updated_at: index.updated_at,
    playlists: {},
    channels: index.channels,
    videos_scanned: index.videos_scanned,
  };
  for (const [slug, rec] of Object.entries(index.playlists)) {
    const { items, ...meta } = rec;
    slim.playlists[slug] = meta;
  }

  const tmp = `${INDEX_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(slim));
  fs.renameSync(tmp, INDEX_FILE);
  cache = { at: Date.now(), mtime: fs.statSync(INDEX_FILE).mtimeMs, value: { ...slim } };
  return slim;
}

export function writeItems(slug, items) {
  fs.mkdirSync(ITEMS_DIR, { recursive: true });
  const file = path.join(ITEMS_DIR, `${slug}.json`);
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(items));
  fs.renameSync(tmp, file);
}

export function readItems(slug) {
  try {
    const file = path.join(ITEMS_DIR, `${slug}.json`);
    if (!fs.existsSync(file)) return null;
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

/**
 * Lowercased blob of everything searchable inside a playlist. Stored with the
 * metadata so content search never has to open a per-playlist file.
 */
export function buildItemText(items, { brands = [], tags = [] } = {}) {
  const seen = new Set();
  for (const i of items) {
    for (const field of [i.name, i.brand]) {
      const v = String(field ?? '').toLowerCase().trim();
      if (v) seen.add(v);
    }
  }
  // The playlist page ships its own studio and tag rollups with counts, so a
  // query like "bunnywalker" or "uncensored" matches a playlist that is mostly
  // that, even when no single entry title carries the word.
  for (const b of brands) {
    const v = String(b?.title ?? b?.slug ?? '').toLowerCase().trim();
    if (v) seen.add(v);
  }
  for (const t of tags) {
    const v = String(t?.text ?? '').toLowerCase().trim();
    if (v) seen.add(v);
  }
  return [...seen].join(' ');
}

// --------------------------------------------------------------------------
// parsing
// --------------------------------------------------------------------------

/**
 * Astro island props encode values as [kind, value]: 0 scalar, 1 array.
 * Undo that so the payload is plain JSON again.
 */
export function decodeAstro(value) {
  if (Array.isArray(value) && value.length === 2 && typeof value[0] === 'number') {
    const [kind, inner] = value;
    if (kind === 0) return decodeAstro(inner);
    if (kind === 1) return inner.map(decodeAstro);
    return decodeAstro(inner);
  }
  if (Array.isArray(value)) return value.map(decodeAstro);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = decodeAstro(v);
    return out;
  }
  return value;
}

function unescapeAttr(s) {
  return String(s ?? '')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

const SLUG_RE = /\/playlists\/([a-z0-9]{20})\b/g;

/** Slugs referenced anywhere on a page, deduped. */
function slugsOnPage(html) {
  const out = new Set();
  for (const m of html.matchAll(SLUG_RE)) out.add(m[1]);
  return [...out];
}

/** "522.4K views" -> 522400. Used for ranking, never for truth. */
function parseCount(text) {
  const m = /([\d.]+)\s*([KMB])?/i.exec(String(text ?? ''));
  if (!m) return null;
  const n = Number(m[1]);
  if (!Number.isFinite(n)) return null;
  const mult = { k: 1e3, m: 1e6, b: 1e9 }[(m[2] ?? '').toLowerCase()] ?? 1;
  return Math.round(n * mult);
}

/** "7 years ago" -> approximate age in days. */
function parseAge(text) {
  const m = /(\d+)\s*(year|month|week|day|hour)/i.exec(String(text ?? ''));
  if (!m) return null;
  const per = { hour: 1 / 24, day: 1, week: 7, month: 30, year: 365 }[m[2].toLowerCase()];
  return Math.round(Number(m[1]) * per);
}

/**
 * Playlist summaries advertised on a video page — the "Related Playlists"
 * rail. Each card is a self-contained block carrying cover, title, owner, view
 * count and age, so a discovered playlist is searchable by title and creator
 * before its own page is ever fetched.
 */
function playlistCardsOnPage(html) {
  const cards = [];
  const blocks = html.split('<div class="playlist-hover-card">').slice(1);

  for (const block of blocks) {
    const body = block.slice(0, 6000);
    const slug = /<a href="\/playlists\/([a-z0-9]{20})"/.exec(body)?.[1]
      ?? /data-playlist-href="\/playlists\/([a-z0-9]{20})"/.exec(body)?.[1];
    if (!slug) continue;

    const cover = /<img src="([^"]*)" alt="[^"]*cover image"/.exec(body)?.[1] ?? null;
    const countText = /<span[^>]*>\s*([\d.,]+)\s*videos?\s*<\/span>/.exec(body)?.[1] ?? null;
    const title = /line-clamp-2[^"]*">([^<]+)<\/div>/.exec(body)?.[1] ?? null;
    const avatar = /<img src="([^"]*)" alt="Playlist creator's avatar"/.exec(body)?.[1] ?? null;
    const owner = /alt="Playlist creator's avatar"[^>]*>[\s\S]{0,400}?<span class="flex truncate">([^<]*)<\/span>/.exec(body)?.[1] ?? null;
    const meta = /text-base-content\/45[^"]*">([^<]*)(?:<span[^>]*>•<\/span>([^<]*))?<\/div>/.exec(body);
    const channelHref = /data-playlist-owner-channel-href="([^"]*)"/.exec(body)?.[1] ?? null;

    cards.push({
      slug,
      title: title ? unescapeAttr(title).trim() : null,
      cover_url: cover ? unescapeAttr(cover) : null,
      video_count: countText ? Number(countText.replace(/[^\d]/g, '')) : null,
      owner_name: owner ? unescapeAttr(owner).trim() : null,
      owner_avatar_url: avatar ? unescapeAttr(avatar) : null,
      owner_channel_slug: channelHref ? unescapeAttr(channelHref).replace('/channels/', '') : null,
      views: parseCount(meta?.[1]),
      age_days: parseAge(meta?.[2]),
    });
  }

  // Fallback: a page shape with the menu button but no card wrapper.
  if (!cards.length) {
    const re = /data-playlist-href="\/playlists\/([a-z0-9]{20})"([^>]*)>/g;
    for (const m of html.matchAll(re)) {
      const attrs = m[2];
      cards.push({
        slug: m[1],
        title: null,
        cover_url: null,
        video_count: null,
        owner_name: /data-playlist-owner-name="([^"]*)"/.exec(attrs)?.[1] ?? null,
        owner_avatar_url: /data-playlist-owner-avatar-url="([^"]*)"/.exec(attrs)?.[1] ?? null,
        owner_channel_slug: (/data-playlist-owner-channel-href="([^"]*)"/.exec(attrs)?.[1] ?? '').replace('/channels/', '') || null,
        views: null,
        age_days: null,
      });
    }
  }

  const seen = new Map();
  for (const c of cards) if (!seen.has(c.slug)) seen.set(c.slug, c);
  return [...seen.values()];
}

/** The playlist island payload, or null when the page is not a playlist page. */
export function parsePlaylistPage(html) {
  const re = /astro-island[^>]*component-url="([^"]+)"[^>]*props="([^"]*)"/g;
  for (const m of html.matchAll(re)) {
    const raw = unescapeAttr(m[2]);
    if (!raw.includes('"playlist"')) continue;
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      continue;
    }
    const data = decodeAstro(parsed).initial_data;
    if (!data?.playlist) continue;
    return data;
  }
  return null;
}

/** Channel page: title plus the playlists it links. */
export function parseChannelPage(html) {
  const title = /<title>([^<]*)<\/title>/.exec(html)?.[1]?.replace(/\s*-\s*hanime\.tv\s*$/, '') ?? null;
  return { title, playlists: slugsOnPage(html) };
}

// --------------------------------------------------------------------------
// fetching
// --------------------------------------------------------------------------

async function get(url) {
  const res = await fetch(url, {
    headers: {
      'user-agent': USER_AGENT,
      accept: 'text/html,application/xhtml+xml',
      referer: `${SITE_BASE}/`,
    },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!res.ok) throw Object.assign(new Error(`${url} -> ${res.status}`), { status: res.status });
  return res.text();
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Run tasks with bounded concurrency and a floor delay between starts. */
async function pool(items, worker, { concurrency = CONCURRENCY } = {}) {
  const results = [];
  let cursor = 0;
  const runners = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    for (;;) {
      const i = cursor++;
      if (i >= items.length) return;
      results[i] = await worker(items[i], i).catch((e) => ({ error: e.message }));
      if (DELAY_MS) await sleep(DELAY_MS);
    }
  });
  await Promise.all(runners);
  return results;
}

// --------------------------------------------------------------------------
// crawl steps
// --------------------------------------------------------------------------

/** Harvest playlist links off one video page. */
export async function harvestVideo(index, videoSlug) {
  const html = await get(`${SITE_BASE}/videos/hentai/${videoSlug}`);
  const cards = playlistCardsOnPage(html);
  const slugs = new Set([...cards.map((c) => c.slug), ...slugsOnPage(html)]);

  for (const slug of slugs) {
    if (!index.playlists[slug]) index.playlists[slug] = { slug, discovered_from: `video:${videoSlug}` };
  }
  for (const card of cards) {
    const rec = index.playlists[card.slug];
    if (!rec) continue;
    // Card data is discovery-grade: it fills gaps but never overwrites what a
    // real playlist page said.
    rec.title ??= card.title;
    rec.cover_url ??= card.cover_url;
    rec.video_count ??= card.video_count;
    rec.views ??= card.views;
    rec.age_days ??= card.age_days;
    rec.owner_name ??= card.owner_name;
    rec.owner_avatar_url ??= card.owner_avatar_url;
    rec.owner_channel_slug ??= card.owner_channel_slug;
    rec.seen_on ??= [];
    if (!rec.seen_on.includes(videoSlug)) rec.seen_on.push(videoSlug);
    if (card.owner_channel_slug && !index.channels[card.owner_channel_slug]) {
      index.channels[card.owner_channel_slug] = { slug: card.owner_channel_slug, title: card.owner_name, playlists: [] };
    }
  }
  if (!index.videos_scanned.includes(videoSlug)) index.videos_scanned.push(videoSlug);
  return cards.length || slugs.size;
}

/** Fetch one playlist page and store its record plus its items file. */
export async function fetchPlaylist(index, slug) {
  const html = await get(`${SITE_BASE}/playlists/${slug}`);
  const data = parsePlaylistPage(html);
  if (!data) return { slug, ok: false, reason: 'no_payload' };

  const p = data.playlist ?? {};
  const owner = data.playlist_owner ?? {};
  const raw = Array.isArray(data.playlist_hentai_videos) ? data.playlist_hentai_videos : [];

  const brands = (Array.isArray(data.brands) ? data.brands : []).map((b) => ({
    slug: b.slug ?? null,
    title: b.title ?? null,
    count: Number(b.count ?? 0) || null,
    in_playlist_count: Number(b.in_playlist_count ?? 0) || null,
  })).filter((b) => b.title);

  const tags = (Array.isArray(data.tags) ? data.tags : []).map((t) => ({
    text: t.text ?? null,
    count: Number(t.count ?? 0) || null,
  })).filter((t) => t.text);

  const items = raw.map((v) => ({
    id: v.id ?? null,
    phv_id: v.phv_id ?? null,
    slug: v.slug ?? null,
    name: v.name ?? null,
    brand: v.brand ?? null,
    cover_url: v.cover_url ?? null,
    poster_url: v.poster_url ?? null,
    duration_in_ms: v.duration_in_ms ?? null,
    views: v.views ?? null,
    released_at: v.released_at ?? null,
    sequence: v.sequence ?? null,
  }));
  writeItems(slug, items);

  const channelSlug = owner.user_channel?.slug ?? owner.slug ?? null;
  const prev = index.playlists[slug] ?? {};
  index.playlists[slug] = {
    ...prev,
    slug,
    id: p.id ?? null,
    title: p.title ?? prev.title ?? '(untitled)',
    visibility: p.visibility ?? null,
    count: Number(p.count ?? items.length) || items.length,
    total_duration_ms: p.total_duration ?? null,
    custom_poster_url: p.custom_poster_url ?? null,
    // The playlist page carries its own poster; fall back to whatever the
    // discovery card had before reaching for an entry cover.
    cover_url: p.poster_url ?? prev.cover_url ?? items[0]?.cover_url ?? null,
    views: Number(p.views ?? 0) || (prev.views ?? null),
    brands,
    tags,
    created_at: p.created_at ?? null,
    updated_at: p.updated_at ?? null,
    owner_name: owner.name ?? prev.owner_name ?? null,
    owner_avatar_url: owner.avatar_url ?? null,
    owner_channel_slug: channelSlug,
    // Truncation is real: a large list can arrive flagged. Recorded so a
    // partial item list is never mistaken for a complete one.
    truncated: data.is_truncated === true,
    list_size: Number(data.list_size ?? 0) || null,
    video_count: Number(p.count ?? 0) || (prev.video_count ?? null),
    item_count: items.length,
    item_text: buildItemText(items, { brands, tags }),
    items_file: true,
    fetched_at: new Date().toISOString(),
    discovered_from: prev.discovered_from ?? 'direct',
    seen_on: prev.seen_on ?? [],
  };

  if (channelSlug) {
    const ch = index.channels[channelSlug] ?? { slug: channelSlug, title: null, playlists: [] };
    ch.title ??= owner.user_channel?.title ?? null;
    if (!ch.playlists.includes(slug)) ch.playlists.push(slug);
    index.channels[channelSlug] = ch;
  }

  return { slug, ok: true, items: items.length, truncated: data.is_truncated === true };
}

/** Fetch a channel page and queue that owner's other public playlists. */
export async function fetchChannel(index, channelSlug) {
  const html = await get(`${SITE_BASE}/channels/${channelSlug}`);
  const { title, playlists } = parseChannelPage(html);
  const ch = index.channels[channelSlug] ?? { slug: channelSlug, title: null, playlists: [] };
  ch.title = title;
  ch.fetched_at = new Date().toISOString();
  ch.playlists = [...new Set([...ch.playlists, ...playlists])];
  index.channels[channelSlug] = ch;

  let added = 0;
  for (const slug of playlists) {
    if (!index.playlists[slug]) {
      index.playlists[slug] = { slug, discovered_from: `channel:${channelSlug}` };
      added++;
    }
  }
  return { slug: channelSlug, ok: true, playlists: playlists.length, added };
}

// --------------------------------------------------------------------------
// on-demand fill
// --------------------------------------------------------------------------

const filling = new Map();

/**
 * Fill in one playlist if its page has never been fetched.
 *
 * A playlist discovered only from a video page's card is searchable by title
 * and creator but has no entries. Opening one should not show an empty list
 * under a card that advertised 300 titles, so the page is pulled on first open.
 *
 * Safe under a running crawl: entries always land in their own file, and the
 * metadata save is skipped while another process holds the lock, so a
 * concurrent pass cannot be clobbered. The record simply stays `fetched: false`
 * with a valid items file; the next crawl's save records it properly.
 *
 * @returns {Promise<{slug:string, items:object[]|null, filled:boolean, reason?:string}>}
 */
export async function ensurePlaylist(slug) {
  const existing = readItems(slug);
  if (existing) return { slug, items: existing, filled: false, reason: 'already_fetched' };

  // Collapse concurrent opens of the same playlist onto one upstream request.
  if (filling.has(slug)) return filling.get(slug);

  const run = (async () => {
    const index = loadIndex({ fresh: true });
    const result = await fetchPlaylist(index, slug);
    if (!result.ok) return { slug, items: null, filled: false, reason: result.reason ?? 'unavailable' };

    const held = crawlLock();
    if (held && !held.stale) {
      return { slug, items: readItems(slug), filled: true, reason: 'crawl_running_metadata_deferred' };
    }
    saveIndex(index);
    return { slug, items: readItems(slug), filled: true };
  })().finally(() => filling.delete(slug));

  filling.set(slug, run);
  return run;
}

// --------------------------------------------------------------------------
// driver
// --------------------------------------------------------------------------

/**
 * Run a crawl pass. Safe to interrupt: the index is written after each phase
 * and videos_scanned is a permanent skip list, so a rerun continues.
 *
 * @param {object} opts
 * @param {string[]} [opts.videoSlugs]   seed video slugs to harvest
 * @param {number}   [opts.maxPlaylists] ceiling on playlist pages fetched this pass
 * @param {number}   [opts.maxChannels]  ceiling on channel pages fetched this pass
 * @param {boolean}  [opts.expandChannels] follow owners into their channels
 * @param {(msg:string)=>void} [opts.onProgress]
 */
export async function crawl({
  videoSlugs = [],
  maxPlaylists = 60,
  maxChannels = 20,
  expandChannels = true,
  onProgress = () => {},
} = {}) {
  acquireLock();
  try {
    return await crawlLocked({ videoSlugs, maxPlaylists, maxChannels, expandChannels, onProgress });
  } finally {
    releaseLock();
  }
}

async function crawlLocked({
  videoSlugs = [],
  maxPlaylists = 60,
  maxChannels = 20,
  expandChannels = true,
  onProgress = () => {},
} = {}) {
  const index = loadIndex({ fresh: true });
  const started = { videos: 0, playlists: 0, channels: 0, errors: 0 };

  // 1. seed from video pages
  if (videoSlugs.length) {
    onProgress(`harvesting ${videoSlugs.length} video page(s)`);
    const res = await pool(videoSlugs, (slug) => harvestVideo(index, slug));
    started.videos = res.filter((r) => !r?.error).length;
    started.errors += res.filter((r) => r?.error).length;
    res.filter((r) => r?.error).forEach((r) => onProgress(`  video failed: ${r.error}`));
    saveIndex(index);
  }

  // 2. fetch playlist pages that have no items file yet
  const pending = Object.values(index.playlists)
    .filter((p) => !p.fetched_at && !p.items_file)
    .map((p) => p.slug)
    .slice(0, maxPlaylists);

  if (pending.length) {
    onProgress(`fetching ${pending.length} playlist page(s)`);
    // Checkpoint periodically. Each playlist's entries are written to their own
    // file as they arrive, but the metadata only lands on disk at a save — so a
    // long batch that is interrupted would otherwise lose every record it had
    // already paid for.
    let sinceSave = 0;
    const res = await pool(pending, async (slug) => {
      const out = await fetchPlaylist(index, slug);
      if (++sinceSave >= 200) { sinceSave = 0; saveIndex(index); }
      return out;
    });
    started.playlists = res.filter((r) => r?.ok).length;
    started.errors += res.filter((r) => r?.error || r?.ok === false).length;
    saveIndex(index);
  }

  // 3. expand owners into their channels
  if (expandChannels) {
    const known = new Set(Object.values(index.channels).filter((c) => c.fetched_at).map((c) => c.slug));
    const todo = Object.keys(index.channels).filter((s) => !known.has(s)).slice(0, maxChannels);
    if (todo.length) {
      onProgress(`fetching ${todo.length} channel page(s)`);
      const res = await pool(todo, (slug) => fetchChannel(index, slug));
      started.channels = res.filter((r) => r?.ok).length;
      started.errors += res.filter((r) => r?.error).length;
      saveIndex(index);
    }
  }

  const stats = indexStats(index);
  onProgress(`done — ${stats.playlists} playlists, ${stats.items} items, ${stats.owners} owners`);
  return { ...started, stats };
}

export function indexStats(index = loadIndex()) {
  const all = Object.values(index.playlists);
  const fetched = all.filter((p) => p.fetched_at);
  return {
    playlists: fetched.length,
    discovered: all.length,
    items: fetched.reduce((n, p) => n + (p.item_count ?? 0), 0),
    owners: new Set(fetched.map((p) => p.owner_channel_slug).filter(Boolean)).size,
    channels: Object.keys(index.channels).length,
    videos_scanned: index.videos_scanned.length,
    truncated_lists: fetched.filter((p) => p.truncated).length,
    updated_at: index.updated_at,
  };
}
