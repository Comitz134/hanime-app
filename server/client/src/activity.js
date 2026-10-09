// activity.js — what this device was last watching and reading.
//
// MyAnimeList's list knows where a title *stands*; it does not know that the
// chapter opened last night was chapter 12, nor that episode 5 of something
// else was playing half an hour ago. That is device knowledge, and until now
// it only existed for the 18+ catalog (library.js keeps a slug-addressed
// history). The anime area plays through an embed and the manga area keeps
// nothing at all — so the two rails on the shelf that answer "where was I?"
// were the two areas that could not answer it.
//
// One entry per title, rewritten as it is re-opened: the rail is a shortlist,
// not a log. `ref` is whatever the area needs to come back to the exact page —
// a mangafire chapter id — and `number` is the episode or chapter, which is
// what the card prints and what the progress bar measures.
//
// The record is written where the opening *is*: the moment an episode resolves
// a player, the moment a chapter is rendered. Both callers hand their opener
// in through setOpener, so this module never has to import a sheet.

import { ago, esc } from './core.js';
import { sorted } from './sorts.js';

const KEY = 'htv:activity:v1';
const MAX = 60;

function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch (e) {
    return fallback;
  }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* unwritable */ }
}

/**
 * Every entry, newest activity first — the list is kept in that order.
 * `number >= 0`, not `> 0`: a prologue is chapter 0 on mangafire, and a
 * reader who opened it is reading it. The read filter and the write guard in
 * noteOpen have to agree, or a record is saved and then never seen — which is
 * exactly what the phone showed for "4 Cut Hero" (Ch. 0 stored, rail empty).
 */
const all = () => load(KEY, []).filter((e) => e && e.kind && e.id && Number(e.number) >= 0);

const listFor = (kind) => all().filter((e) => e.kind === kind);

/** Where each area's "open that title, at that number" lives. */
const openers = {};
function setOpener(kind, fn) { openers[kind] = fn; }

/**
 * Records the episode or chapter being opened right now. Re-opening a title
 * moves it to the top instead of adding a second row, so the rail stays a
 * shortlist of titles rather than a diary of taps.
 */
function noteOpen(kind, { id, title, cover, number, total, ref }) {
  const theId = String(id ?? '');
  const n = Number(number);
  // Zero is a chapter: mangafire numbers prologues "Ch. 0", and a reader who
  // opened one is reading it. Only a number that is missing or not a number at
  // all is a record worth refusing.
  if (!theId || !Number.isFinite(n) || n < 0) return;
  const rows = all().filter((e) => !(e.kind === kind && String(e.id) === theId));
  rows.unshift({
    kind,
    id: theId,
    title: String(title ?? ''),
    cover: String(cover ?? ''),
    number: n,
    total: Number(total) > 0 ? Number(total) : 0,
    ref: ref === undefined || ref === null ? null : String(ref),
    at: Date.now(),
  });
  save(KEY, rows.slice(0, MAX));
}

function forget(kind, id) {
  save(KEY, all().filter((e) => !(e.kind === kind && String(e.id) === String(id))));
}
function clear(kind) {
  save(KEY, all().filter((e) => e.kind !== kind));
}

/* ------------------------------------------------------------------- cards */

/** One card, known by its kind: an episode says Ep., a chapter says Ch. — the
 *  same picture, the same frame as every other shelf in the app. */
function activityCardHtml(e) {
  const verb = e.kind === 'manga' ? 'read' : 'watched';
  // A show says which season and episode it was left on — or that it was a
  // movie — where an anime says "Ep." and a manga "Ch.".
  const showRef = e.kind === 'show' ? /^s(\d+)e(\d+)$/.exec(e.ref ?? '') : null;
  const unit = e.kind === 'manga' ? 'Ch.'
    : e.kind === 'show' ? (showRef ? `S${showRef[1]}E${showRef[2]}` : 'Movie')
      : 'Ep.';
  const where = e.kind === 'show' ? unit : `${unit} ${e.number}`;
  const when = ago(e.at);
  const pct = e.total ? Math.min(100, Math.round((e.number / e.total) * 100)) : 0;
  return `<article class="card act-card" data-act="${e.kind}" data-act-id="${esc(e.id)}"
      data-act-num="${e.number}" data-act-ref="${esc(e.ref ?? '')}" tabindex="0" role="button"
      aria-label="${esc(`${e.title} — ${where}, ${verb} ${when}`)}">
    <div class="card-frame">
      <img loading="lazy" decoding="async" src="${esc(e.cover)}" alt="">
      <div class="card-fallback" hidden>${esc(e.title)}</div>
      <div class="card-scrim"></div>
      <span class="card-badge">${esc(where)}</span>
      ${pct ? `<div class="card-progress"><span style="width:${pct}%"></span></div>` : ''}
      <button class="card-drop" type="button" data-act-drop="${e.kind}" data-act-drop-id="${esc(e.id)}"
        aria-label="Take ${esc(e.title)} off this rail" title="Take off this rail">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
      </button>
      <div class="card-play"><span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
      </span></div>
    </div>
    <div class="card-body">
      <h3 class="card-title">${esc(e.title)}</h3>
      <p class="card-meta">${esc(`${verb} ${when}`)}</p>
    </div>
  </article>`;
}

/* ------------------------------------------------------------------- rails */

/** Each rail's own elements, and what it says when it has nothing to show. */
const RAILS = {
  anime: {
    sort: 'watch-anime', rail: 'watch-rail', head: 'watch-head', count: 'watch-count',
    note: 'watch-note',
    empty: 'Episodes you play on this device are listed here, newest first — '
      + 'pick one up without searching for it again.',
  },
  manga: {
    sort: 'read-manga', rail: 'read-rail', head: 'read-head', count: 'read-count',
    note: 'read-note',
    empty: 'Chapters you open are listed here, newest first — the one you were '
      + 'in the middle of stays one tap away.',
  },
  // Films & series: the rail lives in the movies view rather than in the
  // Library, because the resume this area offers is its own page's business.
  show: {
    sort: 'show-watch', rail: 'show-watch-rail', head: 'show-watch-head',
    count: 'show-watch-count', note: 'show-watch-note',
    empty: 'Episodes and movies you play on this device are listed here, newest '
      + 'first — pick one up without searching for it again.',
  },
};

/** Paints one rail in the order that rail is currently asked for. */
function renderActivity(kind) {
  const cfg = RAILS[kind];
  if (!cfg) return;
  const rail = document.getElementById(cfg.rail);
  if (!rail) return;
  const rows = sorted(cfg.sort, listFor(kind));
  rail.innerHTML = rows.map(activityCardHtml).join('');
  rail.hidden = rows.length === 0;
  const head = document.getElementById(cfg.head);
  if (head) head.hidden = rows.length === 0;
  const count = document.getElementById(cfg.count);
  if (count) count.textContent = String(rows.length);
  const note = document.getElementById(cfg.note);
  if (note) {
    note.hidden = rows.length > 0;
    note.textContent = rows.length ? '' : cfg.empty;
  }
}

/** Both rails are local, so they redraw the moment the view comes up. */
function renderRails() {
  for (const kind of Object.keys(RAILS)) renderActivity(kind);
}

/* ------------------------------------------------------------------ wiring */

document.addEventListener('htv:sort', (e) => {
  const name = e.detail?.name;
  for (const [kind, cfg] of Object.entries(RAILS)) {
    if (cfg.sort === name) renderActivity(kind);
  }
});

document.addEventListener('click', (e) => {
  const drop = e.target.closest('[data-act-drop]');
  if (drop) {
    e.preventDefault();
    forget(drop.dataset.actDrop, drop.dataset.actDropId);
    renderActivity(drop.dataset.actDrop);
    return;
  }

  const wipe = e.target.closest('[data-rail-clear]');
  if (wipe) {
    e.preventDefault();
    clear(wipe.dataset.railClear);
    renderActivity(wipe.dataset.railClear);
    return;
  }

  const card = e.target.closest('[data-act]');
  if (!card) return;
  const kind = card.dataset.act;
  const open = openers[kind];
  if (!open) return;
  e.preventDefault();
  // The chapter id when there is one: a title opened from here lands in the
  // chapter that was actually being read, not on its first page.
  const ref = card.dataset.actRef || card.dataset.actNum;
  open(card.dataset.actId, ref);
});

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('[data-act]');
  if (!card) return;
  const open = openers[card.dataset.act];
  if (!open) return;
  e.preventDefault();
  open(card.dataset.actId, card.dataset.actRef || card.dataset.actNum);
});

export {
  KEY as ACTIVITY_KEY, MAX as ACTIVITY_MAX, noteOpen, forget, clear, listFor,
  activityCardHtml, renderActivity, renderRails, setOpener,
};
