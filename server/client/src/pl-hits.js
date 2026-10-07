// pl-hits.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, api } from './core.js';
import { plCard } from './playlists.js';
import { ppCard } from './public-playlists.js';

/* ------------------------------------------------- playlists, found by name */
//
// A playlist is found by its own name, which is a different index from the
// catalog: the crawled public playlists, plus the account's own when one is
// connected. Both endpoints rank a title match above a content match; this
// puts the result beside the grid, so typing a playlist's name answers with
// the playlist instead of a wall of unrelated titles.

let ownIndex = null;
let plHitsSeq = 0;

async function loadOwnIndex() {
  if (ownIndex) return ownIndex;
  try {
    const body = await api('/api/playlists');
    ownIndex = Array.isArray(body.playlists) ? body.playlists : [];
  } catch { ownIndex = []; }
  return ownIndex;
}

async function loadPlaylistHits(q) {
  const box = $('#pl-hits');
  const rail = $('#pl-hits-rail');
  const count = $('#pl-hits-count');
  if (!box) return;

  const needle = String(q ?? '').trim();
  const seq = ++plHitsSeq;
  if (!needle) { box.hidden = true; rail.innerHTML = ''; count.textContent = ''; return; }

  let mine = [];
  let pub = [];
  try {
    const lower = needle.toLowerCase();
    const [own, body] = await Promise.all([
      loadOwnIndex(),
      api('/api/public/playlists?' + new URLSearchParams({ q: needle, limit: '14', items: '0' })),
    ]);
    mine = own.filter((p) => String(p.title ?? '').toLowerCase().includes(lower));
    pub = body.playlists ?? [];
  } catch {
    // An index that will not load must not disturb the grid beside it.
  }
  if (seq !== plHitsSeq) return;   // a later keystroke already answered this one

  const html = [...mine.map(plCard), ...pub.map(ppCard)].join('');
  box.hidden = !html;
  count.textContent = html ? `${mine.length + pub.length} found` : '';
  rail.innerHTML = html;
}

export { ownIndex, plHitsSeq, loadOwnIndex, loadPlaylistHits };
