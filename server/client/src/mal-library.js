// mal-library.js — the Library's three shelves, filled from MyAnimeList.
//
// The Library used to be one shelf: what this device saved. Linking an
// account adds a second source with a different shape — everything that
// account already watches and reads — so the view is split the way the app
// already is: Anime, Manga, 18+. The device's own records (Continue watching,
// Favorites, History) stay under 18+, where they were born; the two MAL lists
// land on the shelves they name.
//
// A MAL entry is not one of our ids. The app plays AniList ids, mangafire
// hids and hanime slugs, and MAL knows none of them — so a tap resolves the
// entry against the area's own catalog by title (exact first, then a title
// that starts with it, then the top hit the search ranked) and opens the
// sheet that area already knows how to open. The answer is remembered per
// title, so the second visit is a straight open rather than another search.
//
// Nothing here is a second tracking surface: writes still happen in the
// detail sheets' `.mal-slot`, through mal.js. These shelves only read.

import { api, ago, esc } from './core.js';
import { openAnime } from './anime.js';
import { configured, linked, malCache, malLists } from './mal.js';
import { openManga } from './manga.js';
import { mangaSearch } from './manga-api.js';
import { openVideo } from './sheet.js';
import { remember, remembered, sorted } from './sorts.js';

/* ---------------------------------------------------------------- storage */

// The same guarded reads the rest of the client uses: a storage that throws
// (private mode, a full quota) must not take the page down.
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

/** Where each title was matched in an area's catalog, so it is searched once. */
const OPEN_KEY = 'mal.open';

/* ------------------------------------------------------------------ panels */

const PANELS = {
  anime: { grid: 'mal-anime-grid', note: 'mal-anime-note', count: 'mal-anime-count' },
  manga: { grid: 'mal-manga-grid', note: 'mal-manga-note', count: 'mal-manga-count' },
  adult: { grid: 'mal-adult-grid', note: 'mal-adult-note', count: 'mal-adult-count' },
};
const TABS = Object.keys(PANELS);
/** The media kinds a card can belong to — the areas, not the shelves. */
const KINDS = ['anime', 'manga'];

/** MAL's own status spellings, as the card prints them. */
const STATUS_LABEL = {
  watching: 'Watching',
  completed: 'Completed',
  on_hold: 'On hold',
  dropped: 'Dropped',
  plan_to_watch: 'Plan to watch',
  reading: 'Reading',
  plan_to_read: 'Plan to read',
};

let pulling = false;
let painted = false;

const noteFor = (kind) => document.getElementById(PANELS[kind].note);

function note(kind, text) {
  const el = noteFor(kind);
  if (!el) return;
  el.hidden = !text;
  el.textContent = text || '';
}

/**
 * What belongs on one shelf. Anime and Manga are the two lists as they are;
 * the 18+ shelf is everything adult on the account, both kinds — a list that
 * tracks adult manga is exactly the list this shelf exists for, and a title
 * still opens in its own area because each card carries its kind.
 *
 * The order is not decided here: each shelf asks sorts.js for the one the
 * reader picked, so "last read" on the manga shelf means what it says on the
 * rail above it.
 */
function entriesFor(shelf, lists) {
  const anime = lists?.anime ?? [];
  const manga = lists?.manga ?? [];
  const pick = shelf === 'anime' ? anime.filter((e) => !e.adult)
    : shelf === 'manga' ? manga
      : [...anime.filter((e) => e.adult), ...manga.filter((e) => e.adult)];
  return sorted(`mal-${shelf}`, pick);
}

/* ------------------------------------------------------------------- cards */

// A card carries two things that are not the same: the shelf it is drawn on
// (where its messages belong) and the entry's own kind (which catalog a tap
// resolves against and which sheet opens). The 18+ shelf holds both kinds, so
// mixing them sent a match failure to a panel nobody was looking at.
function malCardHtml(e, shelf) {
  const label = STATUS_LABEL[e.status] ?? e.status;
  const unit = e.kind === 'manga' ? 'ch' : 'ep';
  const done = e.total ? `${e.progress}/${e.total}` : (e.progress ? String(e.progress) : '');
  // When the list last moved — the number the "last read / last watched"
  // orders are actually sorting on, printed so the order is never a mystery.
  const moved = e.updatedAt ? `updated ${ago(e.updatedAt)}` : null;
  const meta = [label, done && `${done} ${unit}`, e.score ? `★ ${e.score}` : null, moved]
    .filter(Boolean).join(' · ');
  const pct = e.total && e.progress ? Math.min(100, Math.round((e.progress / e.total) * 100)) : 0;

  return `<article class="card mal-card" data-mal-open="${shelf}" data-mal-kind="${e.kind}"
      data-mal-id="${e.malId}" data-mal-title="${esc(e.title)}" tabindex="0" role="button"
      aria-label="${esc(`${e.title} — ${label}, opens in this app`)}">
    <div class="card-frame">
      <img loading="lazy" decoding="async" src="${esc(e.cover)}" alt="">
      <div class="card-fallback" hidden>${esc(e.title)}</div>
      <div class="card-scrim"></div>
      ${e.adult && shelf !== 'adult' ? '<span class="card-badge">18+</span>' : ''}
      ${pct ? `<div class="card-progress"><span style="width:${pct}%"></span></div>` : ''}
      <div class="card-play"><span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
      </span></div>
    </div>
    <div class="card-body">
      <h3 class="card-title">${esc(e.title)}</h3>
      <p class="card-meta">${esc(meta)}</p>
    </div>
  </article>`;
}

/* ------------------------------------------------------------------ states */

/** No account, or a build with no client id: say so, and ask MAL nothing. */
function sayUnlinked() {
  const line = configured()
    ? 'Link MyAnimeList in Settings and everything you already watch and read appears here.'
    : 'This build has no MyAnimeList client id, so this shelf stays empty.';
  for (const kind of TABS) {
    const grid = document.getElementById(PANELS[kind].grid);
    if (grid) grid.innerHTML = '';
    const count = document.getElementById(PANELS[kind].count);
    if (count) count.textContent = '';
    note(kind, line);
  }
  document.querySelectorAll('[data-mal-refresh]').forEach((b) => { b.hidden = true; });
}

function paint(lists) {
  if (!lists) return;   // nothing pulled yet: an order change has nothing to redraw
  painted = true;
  document.querySelectorAll('[data-mal-refresh]').forEach((b) => { b.hidden = false; });
  for (const kind of TABS) {
    const list = entriesFor(kind, lists);
    const grid = document.getElementById(PANELS[kind].grid);
    if (grid) grid.innerHTML = list.map((e) => malCardHtml(e, kind)).join('');
    const count = document.getElementById(PANELS[kind].count);
    if (count) count.textContent = list.length ? `${list.length} tracked` : '';
    if (list.length) { note(kind, ''); continue; }
    note(kind, kind === 'adult'
      ? 'Nothing flagged 18+ on your MyAnimeList list. Titles flagged there land on this shelf.'
      : 'Nothing on your MyAnimeList list yet. It fills as you add titles there, '
        + 'or from a title page in the app.');
  }
}

/* ------------------------------------------------------------------ render */

/**
 * The device's copy paints first — the shelves are useful the moment the view
 * opens, not after a round trip — and the network is consulted behind it. A
 * fetch that fails over a usable copy keeps the copy; a fetch with nothing to
 * fall back on says so on the shelf.
 *
 * An order change redraws from the copy on the device, never from the network:
 * re-reading a list of titles to sort a list of titles is a request nobody
 * asked for.
 */
async function renderMalShelves({ force = false } = {}) {
  const cached = malCache();
  if (cached) paint(cached);

  if (!configured() || !linked()) { sayUnlinked(); return; }
  if (pulling) return;
  pulling = true;
  try {
    const lists = await malLists({ force });
    if (lists) paint(lists);
    else sayUnlinked();
  } catch (e) {
    if (!painted) {
      for (const kind of TABS) {
        note(kind, `Could not read your MyAnimeList list — ${e.message}. `
          + 'Open Refresh to try again.');
      }
    }
    document.querySelectorAll('[data-mal-refresh]').forEach((b) => { b.hidden = false; });
  } finally {
    pulling = false;
  }
}

/** One shelf on screen, the other two panels hidden. Pure markup: it pulls
 *  nothing, which is what lets boot restore the tab you left without a fetch. */
function showLibPanel(name) {
  const tab = TABS.includes(name) ? name : TABS[0];
  for (const t of TABS) {
    const btn = document.querySelector(`[data-lib-tab="${t}"]`);
    if (btn) btn.setAttribute('aria-selected', String(t === tab));
    const panel = document.querySelector(`[data-lib-panel="${t}"]`);
    if (!panel) continue;
    if (t === tab) panel.removeAttribute('hidden');
    else panel.setAttribute('hidden', '');
  }
  return tab;
}

/** Switches shelves: one tab shown, its panel visible, its list up to date. */
function openLibTab(name, { force = false } = {}) {
  // The shelf is where the reader left it next time: the app opens on Anime,
  // but someone who lives on their manga list should not have to say so again
  // on every visit.
  const tab = showLibPanel(name);
  remember('libTab', tab);
  renderMalShelves({ force });
}

/** The tab the Library was last left on. Markup only — see boot.js. */
function restoreLibTab() {
  showLibPanel(remembered('libTab', TABS[0]));
}

/* --------------------------------------------------------------- resolving */

const normTitle = (s) => String(s ?? '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

/** MAL's titles are not our ids: the catalog is asked, and the closest hit
 *  wins — an exact title, else one that starts with it, else the search's own
 *  top result, which is already ranked by relevance. */
function bestMatch(items, title, nameOf, idOf) {
  const want = normTitle(title);
  const name = (i) => normTitle(nameOf(i));
  const hit = items.find((i) => name(i) === want)
    ?? items.find((i) => name(i).startsWith(want))
    ?? items[0];
  return hit ? idOf(hit) : null;
}

async function resolveTarget(kind, title) {
  if (kind === 'anime') {
    const res = await fetch(`/api/anime/search?q=${encodeURIComponent(title)}`);
    if (!res.ok) throw new Error(`the anime catalog answered ${res.status}`);
    const items = (await res.json()).items ?? [];
    return bestMatch(items, title, (i) => i.title, (i) => i.id);
  }
  if (kind === 'manga') {
    const body = await mangaSearch({ q: title, limit: 8 });
    return bestMatch(body.items ?? [], title, (i) => i.title, (i) => i.hid);
  }
  const body = await api(`/api/videos?per_page=8&q=${encodeURIComponent(title)}`);
  return bestMatch(body.data ?? [], title, (i) => i.name ?? i.title, (i) => i.slug);
}

function openTarget(kind, id) {
  if (kind === 'anime') openAnime(id);
  else if (kind === 'manga') openManga(id);
  else openVideo(id);
}

/** One tap: the area's own sheet for that title, searching only when the
 *  title has not been matched before. `shelf` is where the message goes,
 *  `kind` is the area the title belongs to. */
async function openMalEntry(shelf, kind, title, card) {
  if (!TABS.includes(shelf) || !KINDS.includes(kind) || !title) return;
  const key = `${kind}:${normTitle(title)}`;
  const seen = load(OPEN_KEY, {});
  if (seen[key]) { openTarget(kind, seen[key]); return; }

  if (card) card.dataset.busy = 'true';
  note(shelf, `Looking “${title}” up in this app…`);
  try {
    const id = await resolveTarget(kind, title);
    if (!id) {
      note(shelf, `Nothing matches “${title}” in this area's catalog. `
        + 'Try its own search, or open the title from MyAnimeList.');
      return;
    }
    seen[key] = id;
    save(OPEN_KEY, seen);
    note(shelf, '');
    openTarget(kind, id);
  } catch (e) {
    note(shelf, `Could not open “${title}” — ${e.message}.`);
  } finally {
    if (card) card.dataset.busy = '';
  }
}

/* ----------------------------------------------------------------- wiring */

// The tabs and the refresh chips are this module's own controls: the markup
// is above the script, so — exactly like settings.js and the MAL card — they
// bind here and the shelves refill on the way into the view.
// The three account shelves are one order change away from being redrawn, and
// the redraw is local: the copy on the device is already in hand.
document.addEventListener('htv:sort', (e) => {
  if (!String(e.detail?.name ?? '').startsWith('mal-')) return;
  paint(malCache());
});

document.addEventListener('click', (e) => {
  const tab = e.target.closest('[data-lib-tab]');
  if (tab) { e.preventDefault(); openLibTab(tab.dataset.libTab); return; }

  const refresh = e.target.closest('[data-mal-refresh]');
  if (refresh) { e.preventDefault(); renderMalShelves({ force: true }); return; }

  const card = e.target.closest('[data-mal-open]');
  if (card) {
    e.preventDefault();
    openMalEntry(card.dataset.malOpen, card.dataset.malKind, card.dataset.malTitle, card);
  }
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('[data-mal-open]');
  if (card) {
    e.preventDefault();
    openMalEntry(card.dataset.malOpen, card.dataset.malKind, card.dataset.malTitle, card);
  }
});

export { openLibTab, showLibPanel, restoreLibTab, renderMalShelves, entriesFor, malCardHtml };
