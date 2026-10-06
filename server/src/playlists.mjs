// Playlist normalization and search.
//
// The account payload carries four collections, all of which are playlist-ish:
//
//   playlists[]                            the playlist records themselves
//   playlist_hentai_videos[]               user-made playlist membership
//   like_dislike_playlist_hentai_videos[]  the Liked Videos playlist
//   watch_later_playlist_hentai_videos[]   Watch Later
//
// Field names inside a membership row are not documented anywhere and cannot be
// observed without a live account, so every reader here is a documented fallback
// chain rather than a guess at one shape. When a row cannot be attributed to a
// playlist it is kept but bucketed under `unattributed` instead of being
// silently dropped — a wrong-but-visible count beats a silent zero.

import { getCatalog } from './hanime.mjs';

const USER_COLLECTIONS = ['playlist_hentai_videos', 'like_dislike_playlist_hentai_videos', 'watch_later_playlist_hentai_videos'];

const VIDEO_WRAPPERS = ['hentai_video', 'video'];
const PLAYLIST_WRAPPERS = ['playlist'];

/**
 * First defined value among candidate keys, at the top level or one level into
 * the given wrappers.
 *
 * The wrapper list is explicit and never widened, because a membership row can
 * carry both a playlist and a video and most shapes repeat the key `id` for
 * each. Searching every wrapper for a generic `id` reads the playlist's id as
 * the video's, which silently attributes rows to the wrong playlist.
 *
 * @param {any} row
 * @param {string[]} keys
 * @param {string[]} wrappers container keys to descend into, in priority order
 */
function pick(row, keys, wrappers) {
  if (!row || typeof row !== 'object') return null;
  for (const key of keys) {
    const direct = row[key];
    if (direct !== undefined && direct !== null && direct !== '') return direct;
    for (const wrapper of wrappers) {
      const nested = row[wrapper]?.[key];
      if (nested !== undefined && nested !== null && nested !== '') return nested;
    }
  }
  return null;
}

/** The video a membership row points at. Never reads the playlist side. */
function videoRef(row) {
  return pick(
    row,
    ['hentai_video_id', 'hentaiVideoId', 'video_id', 'videoId', 'id'],
    VIDEO_WRAPPERS,
  );
}

/** The playlist a membership row belongs to. Accepts an id or a slug. */
function playlistRef(row) {
  return pick(
    row,
    ['playlist_id', 'playlistId', 'playlist_slug', 'playlistSlug', 'id', 'slug'],
    PLAYLIST_WRAPPERS,
  );
}

/** Display fields for a video, from a video record or a membership row. */
function videoTitleOf(row) {
  return pick(row, ['name', 'title', 'hentai_video_title'], VIDEO_WRAPPERS);
}

function videoSlugOf(row) {
  return pick(row, ['slug', 'hentai_video_slug'], VIDEO_WRAPPERS);
}

function videoCoverOf(row) {
  return pick(row, ['cover_url', 'poster_url', 'cover', 'thumbnail'], VIDEO_WRAPPERS);
}

/** Display fields for a playlist record. */
function titleOf(row) {
  return pick(row, ['title', 'name', 'label'], []);
}

function slugOf(row) {
  return pick(row, ['slug', 'handle'], []);
}

/**
 * Flatten every collection into rows tagged with the playlist they belong to.
 * @param {any} payload keep-alive body
 */
function membershipRows(payload) {
  const rows = [];
  for (const key of USER_COLLECTIONS) {
    const list = payload?.[key];
    if (!Array.isArray(list)) continue;
    for (const row of list) {
      rows.push({ key, row, video: videoRef(row), playlist: playlistRef(row) });
    }
  }
  return rows;
}

/**
 * Build the normalized playlist index.
 *
 * @param {any} payload keep-alive body
 * @param {{ byId: Map<number, any>, bySlug: Map<string, any>, items: any[] }} [catalogOverride]
 *   Injected catalog, used by tests to stay offline. Production omits it and the
 *   shared cached catalog is used.
 */
export async function buildIndex(payload, catalogOverride = null) {
  const catalog = catalogOverride ??
    (await getCatalog().catch(() => ({ byId: new Map(), bySlug: new Map(), items: [] })));
  const declared = Array.isArray(payload?.playlists) ? payload.playlists : [];

  const playlists = declared.map((p) => {
    const id = playlistRef(p);
    return {
      id,
      slug: slugOf(p),
      title: titleOf(p) ?? 'Untitled playlist',
      mutable: p?.is_mutable ?? p?.isMutable ?? null,
      cover: videoCoverOf(p),
      // Any count the payload offers, else filled in from membership below.
      declared_count:
        Number(pick(p, ['hentai_videos_count', 'video_count', 'count', 'items_count'], []) ?? 0) || null,
      items: [],
      resolved: 0,
      unresolved: 0,
      synthetic: false,
    };
  });

  const byId = new Map();
  const bySlug = new Map();
  for (const p of playlists) {
    if (p.id != null) byId.set(String(p.id), p);
    if (p.slug) bySlug.set(String(p.slug), p);
  }

  // Membership rows we cannot attribute, grouped by the collection they came from.
  const orphans = new Map();
  let attributed = 0;

  for (const m of membershipRows(payload)) {
    const target = m.playlist != null
      ? byId.get(String(m.playlist)) ?? bySlug.get(String(m.playlist)) ?? null
      : null;

    const entry = {
      video_id: m.video ?? null,
      slug: videoSlugOf(m.row),
      title: videoTitleOf(m.row),
      cover: videoCoverOf(m.row),
      added_at: pick(m.row, ['created_at', 'added_at', 'created_at_unix'], []),
      source: m.key,
    };

    if (target) {
      target.items.push(entry);
      attributed++;
    } else {
      if (!orphans.has(m.key)) orphans.set(m.key, []);
      orphans.get(m.key).push(entry);
    }
  }

  // Hydrate each entry from the local catalog. Catalog lookups are the only
  // place full metadata comes from — the session rows are deliberately thin.
  const hydrate = (entry) => {
    const hit =
      (entry.video_id != null && catalog.byId.get(Number(entry.video_id))) ||
      (entry.slug && catalog.bySlug.get(entry.slug)) ||
      null;
    if (hit) {
      return {
        ...entry,
        video_id: hit.id,
        slug: hit.slug,
        title: entry.title ?? hit.name,
        cover: entry.cover ?? hit.cover_url ?? null,
        poster: hit.poster_url ?? null,
        brand: hit.brand ?? null,
        tags: hit.tags ?? [],
        views: hit.views ?? 0,
        released_at: hit.released_at ?? null,
        resolved: true,
      };
    }
    return { ...entry, resolved: false };
  };

  for (const p of playlists) {
    p.items = p.items.map(hydrate);
    p.resolved = p.items.filter((i) => i.resolved).length;
    p.unresolved = p.items.length - p.resolved;
    if (!p.cover) p.cover = p.items.find((i) => i.cover)?.cover ?? null;
  }

  const synthetic = [...orphans.entries()].map(([key, items]) => {
    const hydrated = items.map(hydrate);
    return {
      id: key,
      slug: key,
      title: key.replace(/_/g, ' ').replace(/\bhentai videos\b/i, '').replace(/\s+/g, ' ').trim() || key,
      mutable: null,
      cover: hydrated.find((i) => i.cover)?.cover ?? null,
      declared_count: hydrated.length,
      items: hydrated,
      resolved: hydrated.filter((i) => i.resolved).length,
      unresolved: hydrated.filter((i) => !i.resolved).length,
      synthetic: true,
    };
  });

  // A declared playlist with no membership rows still belongs in the list; it
  // just reports zero items rather than vanishing.
  return {
    playlists: [...playlists, ...synthetic],
    stats: {
      declared: playlists.length,
      synthetic: synthetic.length,
      attributed_rows: attributed,
      orphan_rows: synthetic.reduce((n, s) => n + s.items.length, 0),
      total_playlists: playlists.length + synthetic.length,
      catalog_size: catalog.items.length,
    },
  };
}

/**
 * Search across playlist titles and their contents.
 * @param {{ playlists: any[] }} index
 * @param {string} query
 */
export function searchPlaylists(index, query) {
  const needle = String(query ?? '').trim().toLowerCase();
  if (!needle) return { playlists: index.playlists, matched: null };

  const plays = [];
  for (const p of index.playlists) {
    const titleHit = (p.title ?? '').toLowerCase().includes(needle);
    const slugHit = String(p.slug ?? '').toLowerCase().includes(needle);
    const itemHits = p.items.filter(
      (i) =>
        (i.title ?? '').toLowerCase().includes(needle) ||
        (i.brand ?? '').toLowerCase().includes(needle) ||
        (i.tags ?? []).some((t) => String(t).toLowerCase().includes(needle)),
    );
    if (!titleHit && !slugHit && !itemHits.length) continue;

    plays.push({
      ...p,
      // A title match means the whole playlist is relevant; a content match
      // narrows the shown items so the hit is actually visible.
      items: titleHit || slugHit ? p.items : itemHits,
      match: titleHit || slugHit ? 'title' : 'item',
      match_count: titleHit || slugHit ? p.items.length : itemHits.length,
    });
  }

  // Exact title matches first, then by how much matched.
  plays.sort((a, b) => {
    if (a.match !== b.match) return a.match === 'title' ? -1 : 1;
    return (b.match_count ?? 0) - (a.match_count ?? 0);
  });

  return { playlists: plays, matched: needle };
}

/** Flatten every item across every playlist, deduped by video id. */
export function allItems(index) {
  const seen = new Map();
  for (const p of index.playlists) {
    for (const item of p.items) {
      const key = item.slug ?? String(item.video_id);
      if (!seen.has(key)) seen.set(key, { ...item, in_playlists: [] });
      seen.get(key).in_playlists.push({ slug: p.slug, title: p.title });
    }
  }
  return [...seen.values()];
}
