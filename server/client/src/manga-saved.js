// manga-saved.js — the manga titles this device keeps on purpose.
//
// The 18+ shelf has had a heart and its own favorites list since the beginning;
// the manga area had only the chapters it had opened. That left "save this for
// later" with nowhere to go — and the reading rail is not that list: the
// chapters opened last night say what was being read, not what was meant to be
// read. So this is the second list, one record per title, in the same
// localStorage the rest of the device's own state lives in, with no account and
// no server anywhere in it.
//
// A card here is an ordinary card in this app's markup, addressed by mangafire's
// own title id — which is exactly what the manga area's document-level click
// listener already opens (see src/manga.js). That is why this shelf has no
// opener of its own: there is nothing for the two to disagree about.

import { $$, esc } from './core.js';

const MANGA_SAVED_KEY = 'htv:manga-saved:v1';
const MAX = 200;

/** Same guarded storage as the rest of the client: private-mode reads that
 *  throw must not take the page down, and a record with no id is not a title. */
function load() {
  try {
    const raw = localStorage.getItem(MANGA_SAVED_KEY);
    const rows = raw ? JSON.parse(raw) : [];
    return Array.isArray(rows) ? rows.filter((e) => e && e.id) : [];
  } catch (e) {
    return [];
  }
}

function save(rows) {
  try { localStorage.setItem(MANGA_SAVED_KEY, JSON.stringify(rows)); } catch (e) { /* unwritable */ }
}

/** Newest first: the list is written in that order, so reading it is the order. */
const savedList = () => load();

const isSaved = (id) => !!id && load().some((e) => String(e.id) === String(id));

function forgetSaved(id) {
  const key = String(id ?? '');
  if (!key) return;
  save(load().filter((e) => String(e.id) !== key));
}

/**
 * Files a title, or takes it off the shelf.
 *
 * Re-saving moves a title to the front rather than adding a second row, the
 * same way the reading rail behaves. An id is the one thing a record cannot do
 * without — a cover or a year is a nicety, and a card drawn from a title that
 * never had one is still a card that opens.
 *
 * @returns {boolean} true when the title is saved by the time this returns.
 */
function toggleSaved(entry) {
  const id = String(entry?.id ?? '');
  if (!id) return false;

  if (isSaved(id)) {
    forgetSaved(id);
    savedFaces();
    return false;
  }

  const record = {
    id,
    title: String(entry.title ?? ''),
    cover: String(entry.cover ?? ''),
    type: String(entry.type ?? ''),
    year: String(entry.year ?? ''),
    chapters: Number(entry.chapters) > 0 ? Number(entry.chapters) : 0,
    at: Number(entry.at) > 0 ? Number(entry.at) : Date.now(),
  };
  save([record, ...load()].slice(0, MAX));
  savedFaces();
  return true;
}

/* ------------------------------------------------------------------- cards */

/** One saved title, in the card every other shelf in the app draws. */
function savedCardHtml(e) {
  const meta = [e.type, e.year, e.chapters ? `${e.chapters} chapters` : '']
    .filter(Boolean).join(' · ');
  return `<article class="card" data-manga="${esc(e.id)}" tabindex="0" role="button" aria-label="${esc(e.title)}">
    <div class="card-frame">
      <img loading="lazy" decoding="async" src="${esc(e.cover)}" alt="">
      <div class="card-fallback" hidden>${esc(e.title)}</div>
      <div class="card-scrim"></div>
      ${e.chapters ? `<span class="card-badge">${esc(String(e.chapters))}</span>` : ''}
      <button class="card-drop" type="button" data-saved-drop="${esc(e.id)}"
        aria-label="Take ${esc(e.title)} off this shelf" title="Take off this shelf">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
      </button>
      <div class="card-play"><span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H11v16H5.5A1.5 1.5 0 0 0 4 21.5zM20 5.5A1.5 1.5 0 0 0 18.5 4H13v16h5.5A1.5 1.5 0 0 1 20 21.5z"/></svg>
      </span></div>
    </div>
    <div class="card-body">
      <h3 class="card-title">${esc(e.title)}</h3>
      <p class="card-meta">${esc(meta)}</p>
    </div>
  </article>`;
}

/* ------------------------------------------------------------------- shelf */

/** The shelf, in the three pieces every rail in the app has: a head, the cards,
 *  and the words for when there are none. */
function renderSavedManga() {
  const grid = document.getElementById('saved-grid');
  if (!grid) return;
  const rows = savedList();

  grid.innerHTML = rows.map(savedCardHtml).join('');
  grid.hidden = rows.length === 0;
  const head = document.getElementById('saved-head');
  if (head) head.hidden = rows.length === 0;
  const count = document.getElementById('saved-count');
  if (count) count.textContent = String(rows.length);
  const note = document.getElementById('saved-note');
  if (note) {
    note.hidden = rows.length > 0;
    note.textContent = rows.length
      ? ''
      : 'Nothing saved yet. The heart on a manga title page keeps a title here.';
  }
}

/**
 * Keeps every save control on screen telling the truth: the one on the title
 * page the reader is standing on, and this shelf. Called after any write, so
 * the two can never be read against each other.
 */
function savedFaces() {
  $$('[data-mangsave]').forEach((b) => {
    const on = isSaved(b.dataset.mangsave);
    b.setAttribute('aria-pressed', String(on));
    const label = on ? 'Remove from library' : 'Add to library';
    b.setAttribute('aria-label', label);
    b.title = label;
    const word = b.querySelector('.act-label');
    if (word) word.textContent = on ? 'In library' : 'Add to library';
  });
  renderSavedManga();
}

/* ------------------------------------------------------------------ wiring */

// Only the removal is this module's: opening a card is the manga area's own
// listener, on the same `data-manga` attribute the grid there uses.
document.addEventListener('click', (e) => {
  const drop = e.target.closest('[data-saved-drop]');
  if (!drop) return;
  e.preventDefault();
  forgetSaved(drop.dataset.savedDrop);
  savedFaces();
});

export {
  MANGA_SAVED_KEY, MAX, isSaved, toggleSaved, savedList,
  savedCardHtml, renderSavedManga, savedFaces,
};
