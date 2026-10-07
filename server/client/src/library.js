// library.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, $$, state } from './core.js';
import { renderLibrary } from './positions.js';
import { shellApi, shellCall } from './shell.js';
import { views } from './views.js';

/* ------------------------------------------------------------------ library */
//
// Favorites and watch history are the only state this app keeps. They live in
// localStorage under the origin the shell serves the client from
// (https://hanime.tv/, answered out of the APK's assets), so they survive a
// restart and a self-update with no server, database or account anywhere.

const LIB_KEY = 'htv:library:v1';
const LIB_MAX_HISTORY = 240;

let libSaved = true;

function libRead() {
  const empty = { favorites: [], history: [], positions: {} };
  let raw = null;
  try { raw = localStorage.getItem(LIB_KEY); } catch { libSaved = false; return empty; }
  if (!raw) return empty;
  let data = null;
  try { data = JSON.parse(raw); } catch { data = null; }
  if (!data || typeof data !== 'object') return empty;
  const clean = (list) => (Array.isArray(list) ? list.filter((e) => e && e.slug) : []);
  return {
    favorites: clean(data.favorites),
    history: clean(data.history),
    positions: cleanPositions(data.positions),
  };
}

// A stored position is a claim about a duration, so it is checked rather than
// trusted: a corrupt entry must not be able to draw a progress bar past the
// end of a card, or a seek beyond the end of a video.
function cleanPositions(raw) {
  const out = {};
  if (!raw || typeof raw !== 'object') return out;
  Object.keys(raw).forEach((slug) => {
    const p = raw[slug];
    if (!p || typeof p !== 'object') return;
    const t = Number(p.t);
    const d = Number(p.d);
    if (!Number.isFinite(t) || !Number.isFinite(d) || d <= 0 || t < 0 || t >= d) return;
    out[slug] = { t: Math.round(t), d: Math.round(d), at: Number(p.at) || 0 };
  });
  return out;
}

let lib = libRead();
let libQuery = '';

// A slug is not enough to draw a card, so every record that passes through a
// render is remembered by slug. That is what the heart on a card stores, which
// is why filing from the grid costs no request.
const known = new Map();
function remember(v) {
  if (!v || !v.slug) return;
  known.set(v.slug, {
    slug: v.slug,
    name: v.name ?? v.title ?? v.slug,
    cover: v.cover ?? v.cover_url ?? '',
    brand: v.brand ?? '',
    views: v.views,
    released_at: v.released_at,
  });
}
function entryFor(slug) {
  const base = known.get(slug) ?? { slug, name: slug, cover: '', brand: '', views: undefined, released_at: undefined };
  return { ...base, at: Date.now() };
}

function isFav(slug) { return lib.favorites.some((e) => e.slug === slug); }
function viewIs(name) { return document.body.dataset.view === name; }

function libWrite() {
  try { localStorage.setItem(LIB_KEY, JSON.stringify(lib)); libSaved = true; }
  catch { libSaved = false; }
  libFaces();
}

// Keeps every heart already on screen telling the truth, wherever it is.
function libFaces() {
  $$('[data-fav]').forEach((b) => {
    const on = isFav(b.dataset.fav);
    b.setAttribute('aria-pressed', String(on));
    const label = on ? 'Remove from favorites' : 'Add to favorites';
    if (b.classList.contains('fav-btn')) { b.setAttribute('aria-label', label); b.title = label; }
    const word = b.querySelector('.fav-label');
    if (word) word.textContent = on ? 'In library' : 'Favorite';
  });
}

/* Asks the shell to keep a title's cover on disk. It costs nothing when the
   cover is already stored, and this is the moment it matters: the title just
   joined the library, which is the screen that has to work offline. */
function keepCover(entry) {
  if (entry && entry.slug && entry.cover) shellCall('cacheCover', entry.slug, entry.cover);
}

function toggleFav(slug) {
  if (!slug) return;
  const i = lib.favorites.findIndex((e) => e.slug === slug);
  if (i >= 0) {
    lib.favorites.splice(i, 1);
  } else {
    const entry = entryFor(slug);
    lib.favorites.unshift(entry);
    keepCover(entry);
  }
  libWrite();
  if (viewIs('library')) renderLibrary();
}

function recordHistory(slug) {
  if (!slug) return;
  const entry = entryFor(slug);
  // A title opened again keeps the position it already had: re-watching from
  // the start is a deliberate act, and opening it to check something is not.
  const wasPartWay = lib.positions[slug];
  lib.history = [entry, ...lib.history.filter((e) => e.slug !== slug)].slice(0, LIB_MAX_HISTORY);
  if (wasPartWay) lib.positions[slug] = wasPartWay;
  keepCover(entry);
  libWrite();
  if (viewIs('library')) renderLibrary();
}

// Library: one box filters both lists, and history can be emptied on its own.
let libDebounce;
$('#lib-q')?.addEventListener('input', (e) => {
  libQuery = e.target.value.trim();
  $('#lib-search-shell').dataset.filled = String(!!e.target.value);
  clearTimeout(libDebounce);
  libDebounce = setTimeout(renderLibrary, 180);
});
$('#lib-clear')?.addEventListener('click', () => {
  $('#lib-q').value = '';
  $('#lib-search-shell').dataset.filled = 'false';
  libQuery = '';
  renderLibrary();
});
$('#hist-clear')?.addEventListener('click', () => {
  lib.history = [];
  // Once nothing is watched, nothing is part-way through either: leaving the
  // positions behind would keep a Continue watching rail for a history the
  // reader just emptied.
  lib.positions = {};
  libWrite();
  renderLibrary();
});

/* ---- getting the library off the device, and back onto it --------------- */
//
// Favourites and history are the only thing this app owns, and they live in one
// place. A file the reader keeps turns that into a backup and a way to move to
// a new phone; both directions go through the shell, which owns the document
// picker, and the file never passes through a server.

function libMessage(text) {
  const msg = $('#lib-msg');
  if (!msg) return;
  msg.hidden = !text;
  msg.textContent = text || '';
}

$('#lib-export')?.addEventListener('click', () => {
  if (!shellApi()) { libMessage('Exporting a file needs the Android app.'); return; }
  shellCall('exportLibrary', JSON.stringify({
    ...lib,
    app: 'hanime-shell',
    savedAt: new Date().toISOString(),
  }));
  libMessage('Choose where to keep the file.');
});

$('#lib-import')?.addEventListener('click', () => {
  if (!shellApi()) { libMessage('Importing a file needs the Android app.'); return; }
  libMessage('Choose a library file to merge in.');
  shellCall('importLibrary');
});

// Called by the shell with the chosen file's text. The format belongs to this
// file, so the parsing and the merging happen here rather than in Java.
window.__shellLibraryImport = (text) => {
  let data = null;
  try { data = JSON.parse(text); } catch (e) { data = null; }
  if (!data || typeof data !== 'object') {
    libMessage('That file is not a library export — nothing was imported.');
    return;
  }

  const favs = Array.isArray(data.favorites) ? data.favorites : [];
  const hist = Array.isArray(data.history) ? data.history : [];

  // A merge, never a replace: an import must not be able to lose what is
  // already on the device, because the file could be older than the app.
  const haveFav = new Set(lib.favorites.map((e) => e.slug));
  favs.forEach((e) => {
    if (!e || !e.slug || haveFav.has(e.slug)) return;
    haveFav.add(e.slug);
    lib.favorites.push(e);
    keepCover(e);
  });

  // The newer record of a title wins, and the list is re-sorted by it: merging
  // by concatenation would push a title watched today behind one from last
  // year, which is precisely the order that matters here.
  const byslug = new Map(lib.history.map((e) => [e.slug, e]));
  hist.forEach((e) => {
    if (!e || !e.slug) return;
    const existing = byslug.get(e.slug);
    if (!existing || Number(e.at) > Number(existing.at)) { byslug.set(e.slug, e); keepCover(e); }
  });
  lib.history = [...byslug.values()]
    .sort((a, b) => Number(b.at) - Number(a.at))
    .slice(0, LIB_MAX_HISTORY);

  const pos = data.positions && typeof data.positions === 'object' ? data.positions : {};
  Object.keys(pos).forEach((slug) => {
    const incoming = pos[slug];
    if (!incoming || typeof incoming !== 'object') return;
    const existing = lib.positions[slug];
    if (existing && Number(existing.at) > Number(incoming.at)) return;
    const t = Number(incoming.t);
    const d = Number(incoming.d);
    if (!Number.isFinite(t) || !Number.isFinite(d) || d <= 0 || t < 0 || t >= d) return;
    lib.positions[slug] = { t: Math.round(t), d: Math.round(d), at: Number(incoming.at) || 0 };
  });

  libWrite();
  renderLibrary();
  libMessage(`Read ${favs.length} favorites and ${hist.length} history entries. `
    + `This device now holds ${lib.favorites.length} favorites and ${lib.history.length} watched.`);
};

export { LIB_KEY, LIB_MAX_HISTORY, libSaved, libRead, cleanPositions, lib, libQuery, known, remember, entryFor, isFav, viewIs, libWrite, libFaces, keepCover, toggleFav, recordHistory, libDebounce, libMessage };
