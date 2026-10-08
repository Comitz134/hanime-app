// wiring.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, $$, api, state } from './core.js';
import { searchAnime } from './anime.js';
import { loadGrid } from './data.js';
import { toggleFav, viewIs } from './library.js';
import { setMenuOpen } from './menu.js';
import { loadPlaylistHits } from './pl-hits.js';
import { loadPlaylists, loadSession, openPlaylist } from './playlists.js';
import { closeSheet, openVideo } from './sheet.js';
import { menuIsOpen, updateBackState } from './shell.js';
import { showView } from './views.js';

let debounce;
/* ------------------------------------------------------------------ wiring */

function refresh() {
  state.page = 0;
  loadGrid();
  loadPlaylistHits(state.q).catch(() => {});
}

// One query, two boxes: the field that the pill turns into, and the one in the
// Browse toolbar. Typing in either mirrors into the other, so they can never
// disagree about what is on screen.
function applyQuery(val, from) {
  if (from !== $('#q')) $('#q').value = val;
  if (from !== $('#nav-q')) $('#nav-q').value = val;
  $('#search-shell').dataset.filled = String(!!val);
  clearTimeout(debounce);
  debounce = setTimeout(() => {
    state.q = val.trim();
    // The pill searches whatever area is on screen: in the anime area the
    // answer is that area's grid, and typing must not yank the reader into
    // the 18+ catalog.
    if (viewIs('anime')) { searchAnime(state.q); return; }
    // The answer is the grid, and the grid is in Browse. Searching from the
    // pill therefore goes there — as a view change, never as a scroll to a
    // search box.
    if (state.q && !viewIs('browse')) showView('browse');
    refresh();
  }, 240);
}

/** Swaps the pill's links for the search field, and back. Moves nothing else. */
function setSearchOpen(open) {
  // Searching and browsing behind the menu are two states of the same pill:
  // one of it at a time.
  if (open && menuIsOpen()) setMenuOpen(false);
  $('#search-toggle').setAttribute('aria-expanded', String(open));
  $('#nav').dataset.searching = String(open);
  // preventScroll is the point: focusing an input normally makes the browser
  // scroll it into view, which is how tapping the search icon used to yank the
  // page down to the toolbar.
  if (open) $('#nav-q').focus({ preventScroll: true });
  else $('#nav-q').blur();
  updateBackState();
}

$('#q').addEventListener('input', (e) => applyQuery(e.target.value, e.target));
$('#nav-q').addEventListener('input', (e) => applyQuery(e.target.value, e.target));
$('#search-clear').onclick = () => {
  applyQuery('', null);
  $('#q').focus({ preventScroll: true });
};
$('#nav-q-close').onclick = () => {
  // With text in it, the × clears the query; empty, it puts the links back.
  if ($('#nav-q').value) { applyQuery('', null); $('#nav-q').focus({ preventScroll: true }); }
  else setSearchOpen(false);
};
$('#sort').addEventListener('change', (e) => { state.sort = e.target.value; refresh(); });

$('#tag-rail').addEventListener('click', (e) => {
  const chip = e.target.closest('.chip');
  if (!chip) return;
  const tag = chip.dataset.tag;
  const i = state.tags.indexOf(tag);
  if (i >= 0) state.tags.splice(i, 1); else state.tags.push(tag);
  chip.setAttribute('aria-pressed', String(i < 0));
  $('#clear-tags').hidden = state.tags.length === 0;
  refresh();
  showView('browse');   // the filtered grid is the answer, so go there
});
$('#clear-tags').onclick = () => {
  state.tags = [];
  $$('#tag-rail .chip').forEach((c) => c.setAttribute('aria-pressed', 'false'));
  $('#clear-tags').hidden = true;
  refresh();
};

$('#brand-rail').addEventListener('click', (e) => {
  const chip = e.target.closest('.chip');
  if (!chip) return;
  state.brand = state.brand === chip.dataset.brand ? '' : chip.dataset.brand;
  $$('#brand-rail .chip').forEach((c) => { c.style.borderColor = c === chip ? 'hsl(var(--primary))' : ''; });
  refresh();
  showView('browse');   // the filtered grid is the answer, so go there
});

$('#prev').onclick = () => { if (state.page > 0) { state.page--; loadGrid().then(() => scrollTo({ top: 0, behavior: 'smooth' })); } };
$('#next').onclick = () => { if (state.page + 1 < state.pages) { state.page++; loadGrid().then(() => scrollTo({ top: 0, behavior: 'smooth' })); } };

// One click, several intents. The heart is tested first because it is drawn on
// top of a card and must file the title rather than open it. The menu's items
// and the hero's buttons follow. Everything else that is a card opens the sheet
// — in the grid, in the library, or inside a playlist.
document.addEventListener('click', (e) => {
  // A tap anywhere outside the menu shuts it: it is a popover, not a modal.
  if (menuIsOpen() && !e.target.closest('#nav-menu') && !e.target.closest('#menu-toggle')) {
    setMenuOpen(false);
  }

  const heart = e.target.closest('[data-fav]');
  if (heart) { e.preventDefault(); toggleFav(heart.dataset.fav); return; }

  const go = e.target.closest('[data-go]');
  if (go) {
    e.preventDefault();
    if (menuIsOpen()) setMenuOpen(false);   // choosing a section shuts the menu
    showView(go.dataset.go);
    return;
  }

  const opener = e.target.closest('[data-open]');
  if (opener) { e.preventDefault(); openVideo(opener.dataset.open); return; }

  const card = e.target.closest('.card');
  if (card?.dataset.slug) openVideo(card.dataset.slug);
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('.card');
  if (card?.dataset.slug) { e.preventDefault(); openVideo(card.dataset.slug); }
});

$('#sheet-close').onclick = closeSheet;
$('#sheet').addEventListener('click', (e) => { if (e.target === $('#sheet')) closeSheet(); });
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  if ($('#nav').dataset.searching === 'true') setSearchOpen(false);
  if (menuIsOpen()) setMenuOpen(false);
  closeSheet();
});



$('#search-toggle').onclick = () => {
  setSearchOpen($('#search-toggle').getAttribute('aria-expanded') !== 'true');
};
$('#sort-toggle').onclick = () => {
  const open = $('#sort-toggle').getAttribute('aria-expanded') !== 'true';
  $('#sort-toggle').setAttribute('aria-expanded', String(open));
  showView('browse');   // the order control is in the Browse toolbar
  $('#sort').focus({ preventScroll: true });
};

$('#brand-btn').onclick = () => showView('browse');

// Playlist search is its own box: title matches and content matches rank
// differently, and folding them into the catalog search would blur both.
let plDebounce;
$('#pl-q')?.addEventListener('input', (e) => {
  const val = e.target.value;
  $('#pl-search-shell').dataset.filled = String(!!val);
  clearTimeout(plDebounce);
  plDebounce = setTimeout(loadPlaylists, 240);
});
$('#pl-clear')?.addEventListener('click', () => {
  $('#pl-q').value = '';
  $('#pl-search-shell').dataset.filled = 'false';
  loadPlaylists();
});
$('#sess-reset')?.addEventListener('click', async () => {
  await fetch('/api/session', { method: 'DELETE' });
  await loadSession();
});
// Playlist cards now appear in two rails — your own, and the ones whose name
// matched a catalog search — so this is delegated rather than bound to one.
document.addEventListener('click', (e) => {
  const card = e.target.closest('[data-playlist]');
  if (card) { e.preventDefault(); openPlaylist(card.dataset.playlist); }
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('[data-playlist]');
  if (card) { e.preventDefault(); openPlaylist(card.dataset.playlist); }
});

export { debounce, refresh, applyQuery, setSearchOpen, plDebounce };
