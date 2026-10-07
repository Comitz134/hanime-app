// positions.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { cardHtml } from './cards.js';
import { $, esc } from './core.js';
import { isFav, lib, libQuery, libSaved, libWrite } from './library.js';

/* ---- where each title was left ------------------------------------------ */
//
// History says a title was opened; it does not say it was finished. The
// position is what turns the history into "continue watching", and it is
// written sparsely — every five seconds at most, plus the two moments that
// matter: pausing, and leaving the sheet.

function savePosition(slug, vid) {
  if (!slug || !vid) return;
  const d = Number(vid.duration);
  const t = Number(vid.currentTime);
  if (!Number.isFinite(d) || d <= 0 || !Number.isFinite(t)) return;
  if (t >= d - 10) { clearPosition(slug); return; }   // watched to the end
  if (t < 5) return;                                  // nothing worth keeping
  lib.positions[slug] = { t: Math.round(t), d: Math.round(d), at: Date.now() };
  libWrite();
}

function clearPosition(slug) {
  if (!lib.positions[slug]) return;
  delete lib.positions[slug];
  libWrite();
}

/** How far through a title is, as a percentage, or 0 when there is nothing. */
function progressOf(slug) {
  const p = lib.positions[slug];
  if (!p || !p.d) return 0;
  const pct = Math.round((p.t / p.d) * 100);
  return pct > 1 && pct < 98 ? pct : 0;
}

function libMatches(e) {
  if (!libQuery) return true;
  const needle = libQuery.toLowerCase();
  return String(e.name ?? '').toLowerCase().includes(needle)
    || String(e.brand ?? '').toLowerCase().includes(needle);
}

function renderLibrary() {
  const needle = libQuery.trim();
  const favs = lib.favorites.filter(libMatches);
  const hist = lib.history.filter(libMatches);

  $('#lib-count').textContent = `${lib.favorites.length} favorite${lib.favorites.length === 1 ? '' : 's'} · ${lib.history.length} watched`;
  $('#fav-count').textContent = needle ? `${favs.length} of ${lib.favorites.length}` : String(lib.favorites.length);
  $('#hist-count').textContent = needle ? `${hist.length} of ${lib.history.length}` : String(lib.history.length);
  $('#hist-clear').hidden = lib.history.length === 0;

  const warn = $('#lib-warn');
  warn.hidden = libSaved;
  warn.textContent = 'This device will not keep local data, so favorites and history are forgotten when the app closes.';

  $('#fav-grid').innerHTML = favs.map((e) => cardHtml(e, { local: true })).join('');
  const favNote = $('#fav-note');
  favNote.hidden = favs.length > 0;
  favNote.textContent = needle
    ? `No favorite matches “${libQuery}”.`
    : 'Nothing filed yet. The heart on any card puts a title here.';

  $('#hist-grid').innerHTML = hist.map((e) => cardHtml(e, { local: true, progress: true })).join('');
  const histNote = $('#hist-note');
  histNote.hidden = hist.length > 0;
  histNote.textContent = needle
    ? `Nothing you opened matches “${libQuery}”.`
    : 'Nothing opened yet. A title lands here once a stream for it resolves.';

  renderContinue(needle);
}

/**
 * The titles that are part-way through, newest first. This is the rail that
 * answers "where was I" — history alone cannot, because a finished title and an
 * abandoned one look identical in it.
 */
function renderContinue(needle) {
  const rail = $('#cont-rail');
  const head = $('#cont-head');
  if (!rail || !head) return;

  const part = lib.history
    .filter((e) => (!needle || libMatches(e)) && progressOf(e.slug) > 0)
    .slice(0, 12);

  head.hidden = part.length === 0;
  rail.hidden = part.length === 0;
  $('#cont-count').textContent = String(part.length);
  const note = $('#cont-note');
  note.hidden = part.length > 0;
  note.textContent = 'Titles you start watching appear here, so you can pick them up where you stopped.';
  rail.innerHTML = part.map((e) => cardHtml(e, { local: true, progress: true })).join('');
}

// The same toggle, as a pill, inside a title page.
function favPill(slug) {
  const on = isFav(slug);
  return `<button class="pill pill-ghost" type="button" data-fav="${esc(slug)}" aria-pressed="${on}">
      <svg viewBox="0 0 24 24" stroke-linejoin="round"><path d="M12 20.4 4.9 14.1A5.3 5.3 0 0 1 12 6.3a5.3 5.3 0 0 1 7.1 7.8Z"/></svg>
      <span class="fav-label">${on ? 'In library' : 'Favorite'}</span>
    </button>`;
}

export { savePosition, clearPosition, progressOf, libMatches, renderLibrary, renderContinue, favPill };
