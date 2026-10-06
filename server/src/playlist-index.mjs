// Query layer over the crawled public playlist index.
//
// The site publishes no playlist index, so this is the one the app runs on:
// every playlist discovered by the crawler, searchable by its own title, by
// its creator, and by what is inside it.
//
// Two match kinds, kept distinct on purpose:
//
//   title   — the query hit the playlist's own name or its creator's name.
//             The whole playlist is the result.
//   content — the query hit something inside it (an entry title or studio).
//             The result is narrowed to the matching entries with a
//             match_count, so the hit is visible instead of buried on page 4.
//
// Title matches always sort ahead of content matches, then by size. No fuzzy
// fallback: a query that matches nothing returns nothing.
//
// Content matching reads `item_text`, the lowercased blob stored alongside the
// metadata — never the per-playlist items files. Items are opened only when a
// playlist is actually shown, so search cost is independent of how large the
// crawled library gets.

import { loadIndex, readItems, indexStats, ensurePlaylist } from './playlist-crawl.mjs';

const FIELDS = ['title', 'owner_name'];

function norm(s) {
  return String(s ?? '').toLowerCase();
}

/** Strip the noise that makes text search feel broken: punctuation, spacing. */
function fold(s) {
  return norm(s).replace(/[^a-z0-9]+/g, ' ').trim();
}

function scoreField(haystack, needle) {
  const h = fold(haystack);
  if (!h) return 0;
  if (h === needle) return 100;
  if (h.startsWith(needle)) return 80;
  if (new RegExp(`\\b${needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`).test(h)) return 60;
  if (h.includes(needle)) return 40;
  return 0;
}

/** A playlist's own title and creator — the "title" match surface. */
function titleScore(rec, needle) {
  let best = 0;
  for (const f of FIELDS) best = Math.max(best, scoreField(rec[f], needle));
  return best;
}

/**
 * Does anything inside this playlist match? Cheap: a substring test over the
 * precomputed item blob, ranked by whether the playlist leads with the term.
 */
function contentScore(rec, needle) {
  const text = rec.item_text;
  if (!text) return 0;
  if (!text.includes(needle)) return 0;
  return text.startsWith(needle) ? 60 : 50;
}

/**
 * Entries to show for a content match.
 *
 * When the term matched a studio or tag rollup rather than any single entry,
 * there are no text hits — falling back to the head of the playlist keeps the
 * result from looking empty when it is in fact a good match.
 */
function contentHits(slug, needle, limit) {
  const items = readItems(slug);
  if (!items) return [];
  const hits = [];
  for (const item of items) {
    const score = Math.max(scoreField(item.name, needle), scoreField(item.brand, needle));
    if (score > 0) hits.push({ ...item, _score: score });
  }
  if (!hits.length) return items.slice(0, limit);
  hits.sort((a, b) => b._score - a._score || (b.views ?? 0) - (a.views ?? 0));
  return hits.slice(0, limit);
}

/** True when the term hit a studio or tag rollup rather than an entry title. */
function rollupMatch(rec, needle) {
  const hit = (arr, pick) =>
    (arr ?? []).some((x) => fold(pick(x)).includes(needle));
  return hit(rec.brands, (b) => b.title) || hit(rec.tags, (t) => t.text);
}

/**
 * Search the index.
 *
 * @param {string} query
 * @param {object} [opts]
 * @param {number}  [opts.limit]       max playlists returned (default 40)
 * @param {number}  [opts.itemsLimit]  max entries attached per content match (default 50)
 * @param {boolean} [opts.includeItems] attach full entry lists to title matches (default false)
 * @param {string}  [opts.owner]       restrict to one owner channel slug
 * @param {object}  [opts.index]       preloaded index
 */
export function searchPlaylists(query, {
  limit = 40,
  itemsLimit = 50,
  includeItems = false,
  owner = null,
  tag = null,
  index = null,
} = {}) {
  const idx = index ?? loadIndex();
  const needle = fold(query);
  const all = Object.values(idx.playlists).filter((p) => p.fetched_at || p.title);
  const ownerScoped = owner ? all.filter((p) => p.owner_channel_slug === owner) : all;
  // Tag filtering uses the playlist page's own tag rollup, so it means "this
  // playlist is substantially this tag", not "one entry happens to have it".
  const scoped = tag
    ? ownerScoped.filter((p) => (p.tags ?? []).some((t) => fold(t.text) === fold(tag)))
    : ownerScoped;

  if (!needle) {
    // Browse: no query means "what is big and public", not "nothing".
    const rows = scoped
      .slice()
      .sort((a, b) => (b.item_count ?? b.video_count ?? 0) - (a.item_count ?? a.video_count ?? 0))
      .slice(0, limit)
      .map((p) => summarize(p, 'browse', 0, [], idx));
    return { query: '', total: scoped.length, returned: rows.length, playlists: rows, stats: indexStats(idx) };
  }

  const rows = [];
  for (const rec of scoped) {
    const ts = titleScore(rec, needle);
    if (ts > 0) {
      // A title match is the whole playlist. Entries are only read when the
      // caller asked for them, and they come from the playlist's own items
      // file — the metadata index never carries them.
      const items = includeItems ? readItems(rec.slug) ?? [] : [];
      rows.push(summarize(rec, 'title', ts, items, idx));
      continue;
    }
    const cs = contentScore(rec, needle);
    if (cs > 0) {
      const row = summarize(rec, 'content', cs, contentHits(rec.slug, needle, itemsLimit), idx);
      // Distinguishes "this playlist has entries named X" from "this playlist
      // is mostly studio/tag X" — different results, worth telling apart.
      row.matched_on = rollupMatch(rec, needle) ? 'studio_or_tag' : 'entries';
      rows.push(row);
    }
  }

  const rank = { title: 1, content: 0 };
  rows.sort((a, b) =>
    rank[b.match_kind] - rank[a.match_kind] ||
    b.match_score - a.match_score ||
    (b.item_count ?? 0) - (a.item_count ?? 0));

  return {
    query,
    total: scoped.length,
    returned: Math.min(rows.length, limit),
    matched: rows.length,
    playlists: rows.slice(0, limit),
    stats: indexStats(idx),
  };
}

function summarize(rec, kind, score, items, idx) {
  return {
    slug: rec.slug,
    title: rec.title ?? '(untitled)',
    owner_name: rec.owner_name ?? null,
    owner_avatar_url: rec.owner_avatar_url ?? null,
    owner_channel_slug: rec.owner_channel_slug ?? null,
    cover_url: rec.cover_url ?? rec.custom_poster_url ?? items[0]?.cover_url ?? null,
    // Prefer what a real fetch counted, fall back to a legacy inline list, then
    // to the count the discovery card advertised. An unfetched playlist must
    // report the card's number, not zero.
    item_count: rec.item_count ?? (rec.items ? rec.items.length : null) ?? rec.video_count ?? 0,
    video_count: rec.video_count ?? null,
    views: rec.views ?? null,
    visibility: rec.visibility ?? 'public',
    truncated: rec.truncated === true,
    fetched: Boolean(rec.fetched_at),
    updated_at: rec.updated_at ?? null,
    match_kind: kind,
    match_score: score,
    match_count: kind === 'content' ? items.length : 0,
    // Rollups straight from the playlist page: what the playlist is mostly
    // made of, weighted by how many of its entries carry each value.
    tags: (rec.tags ?? [])
      .slice()
      .sort((a, b) => (b.count ?? 0) - (a.count ?? 0))
      .slice(0, 12)
      .map((t) => ({ text: t.text, count: t.count })),
    brands: (rec.brands ?? [])
      .slice()
      .sort((a, b) => (b.in_playlist_count ?? 0) - (a.in_playlist_count ?? 0))
      .slice(0, 12)
      .map((b) => ({ title: b.title, slug: b.slug, in_playlist_count: b.in_playlist_count })),
    items: items.map((v) => ({
      slug: v.slug,
      name: v.name,
      brand: v.brand,
      cover_url: v.cover_url,
      poster_url: v.poster_url,
      duration_in_ms: v.duration_in_ms,
      views: v.views,
      sequence: v.sequence,
    })),
  };
}

/** One playlist with its full entry list, read from its items file. */
export function getPlaylist(slug, { index = null, limit = 0 } = {}) {
  const idx = index ?? loadIndex();
  const rec = idx.playlists[slug];
  if (!rec) return null;
  let items = readItems(slug) ?? [];
  if (limit > 0) items = items.slice(0, limit);
  return summarize({ ...rec, items }, 'direct', 0, items, idx);
}

/**
 * One playlist, pulling its page on demand when it was only discovered.
 *
 * Without this, a playlist found via a video page's card opens to an empty
 * list while the card that led here advertised hundreds of titles — the exact
 * silent-zero failure this index is built to avoid.
 */
export async function getPlaylistFilled(slug, { limit = 0 } = {}) {
  let idx = loadIndex();
  let rec = idx.playlists[slug];
  if (!rec) return null;

  if (!rec.fetched_at || !rec.items_file) {
    const filled = await ensurePlaylist(slug);
    if (filled.reason === 'unavailable') return { ...getPlaylist(slug, { index: idx }), unavailable: true };
    idx = loadIndex({ fresh: true });
    rec = idx.playlists[slug] ?? rec;
  }

  let items = readItems(slug) ?? [];
  if (limit > 0) items = items.slice(0, limit);
  return summarize({ ...rec, items }, 'direct', 0, items, idx);
}

/** Cross-playlist lookup: which crawled playlists contain this entry. */
export function playlistsContaining(videoSlug, { index = null, limit = 20 } = {}) {
  const idx = index ?? loadIndex();
  const out = [];
  for (const rec of Object.values(idx.playlists)) {
    if (!rec.fetched_at) continue;
    const items = readItems(rec.slug);
    if (!items) continue;
    const i = items.findIndex((v) => v.slug === videoSlug);
    if (i >= 0) out.push({ ...summarize(rec, 'contains', 0, [], idx), position: i + 1 });
    if (out.length >= limit * 4) break; // bounded read: stop once we have plenty
  }
  return out.sort((a, b) => (b.item_count ?? 0) - (a.item_count ?? 0)).slice(0, limit);
}

/** Tag vocabulary across crawled playlists, for a filter rail. */
export function listTags({ index = null, limit = 200 } = {}) {
  const idx = index ?? loadIndex();
  const map = new Map();
  for (const rec of Object.values(idx.playlists)) {
    for (const t of rec.tags ?? []) {
      if (!t.text) continue;
      const cur = map.get(t.text) ?? { text: t.text, playlists: 0 };
      cur.playlists++;
      map.set(t.text, cur);
    }
  }
  return [...map.values()].sort((a, b) => b.playlists - a.playlists).slice(0, limit);
}

/** Distinct creators present in the index, for the browse-by-creator view. */
export function listOwners({ index = null, limit = 200 } = {}) {
  const idx = index ?? loadIndex();
  const map = new Map();
  for (const rec of Object.values(idx.playlists)) {
    if (!rec.fetched_at && !rec.title) continue;
    const key = rec.owner_channel_slug ?? rec.owner_name;
    if (!key) continue;
    const cur = map.get(key) ?? {
      channel_slug: rec.owner_channel_slug ?? null,
      name: rec.owner_name ?? null,
      avatar_url: rec.owner_avatar_url ?? null,
      playlists: 0,
      items: 0,
    };
    cur.playlists++;
    cur.items += rec.item_count ?? rec.video_count ?? 0;
    cur.avatar_url ??= rec.owner_avatar_url;
    map.set(key, cur);
  }
  return [...map.values()].sort((a, b) => b.playlists - a.playlists || b.items - a.items).slice(0, limit);
}
