// manga.js — the manga area.
//
// The catalog comes straight from mangafire.to's own JSON API (their CORS is
// wide open, and listing requests are signed by the vendored signer — see
// manga-api.js), so neither backend carries a copy of it; the only thing that
// goes through this server or Java is reader pages, because their image CDN
// refuses hotlinks (see /api/manga/page).
//
// Opening a title renders into the same sheet the anime and 18+ areas use —
// one surface, one back contract: the title page lists its chapters, a
// chapter replaces it with the reader, and every one of those steps is a
// line on the history stack the back button walks.

import { $, $$, esc, fmtCount } from './core.js';
import { noteOpen, renderActivity } from './activity.js';
import { autoAdvance, configured, onTrackedProgress } from './mal.js';
import { goBack, pushNav } from './nav-history.js';
import { estimateChip, estimateDetail, estimateTitle, nextChapterEstimate } from './manga-eta.js';
import { duplicateCount, stepIndex, visibleChapters } from './manga-dupes.js';
import { dropSpot, noteSpot, spotFor } from './manga-pos.js';
import { isRead, markRead, setProgress } from './manga-read.js';
import { prefs, savePrefs } from './prefs.js';
import { isSaved, toggleSaved } from './manga-saved.js';
import { mangaChapters, mangaDetail, mangaFilterOptions, mangaPages, mangaSearch, mangaTrending, posterFor, proxied, sourceUrl } from './manga-api.js';
import { closeSheet, openSheet, setSheetOrigin } from './sheet.js';
import { shellCall, shellCan } from './shell.js';
import { showView } from './views.js';

/* ------------------------------------------------------------------ grid */

const mState = { q: '', page: 1, hasNext: false, type: '', status: '', genre: '' };
let mDebounce;
let mLoaded = false;

/** True when anything narrows the shelf — a search box or a filter select. */
function mFiltered() {
  return !!(mState.q || mState.type || mState.status || mState.genre);
}

function mangaCardHtml(m) {
  const meta = [m.type, m.year, m.latestChapter ? `Ch. ${m.latestChapter}` : null]
    .filter(Boolean).map(esc).join(' · ');
  return `<article class="card" data-manga="${esc(m.hid)}" tabindex="0" role="button" aria-label="${esc(m.title)}">
    <div class="card-frame">
      <img loading="lazy" decoding="async" src="${esc(posterFor(m, 'medium'))}" alt="">
      <div class="card-fallback" hidden>${esc(m.title)}</div>
      <div class="card-scrim"></div>
      <div class="card-play"><span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H11v16H5.5A1.5 1.5 0 0 0 4 21.5zM20 5.5A1.5 1.5 0 0 0 18.5 4H13v16h5.5A1.5 1.5 0 0 1 20 21.5z"/></svg>
      </span></div>
    </div>
    <div class="card-body">
      <h3 class="card-title">${esc(m.title)}</h3>
      <p class="card-meta">${meta}</p>
    </div>
  </article>`;
}

function mangaNote(msg) {
  const note = $('#manga-note');
  if (!note) return;
  note.hidden = !msg;
  note.textContent = msg || '';
}

async function loadManga(page) {
  const grid = $('#manga-grid');
  const pager = $('#manga-pager');
  const listMode = mFiltered();
  if (page > 1) grid.innerHTML = '<div class="center-spin"><div class="spinner"></div></div>';
  try {
    let items = [];
    let hasNext = false;
    if (!listMode) {
      // No query and no filters: their homepage shelf — trending titles, one
      // page of them, and a shelf that does not even need the signer to load.
      const data = await mangaTrending(30);
      items = data.items ?? [];
      mState.page = 1;
    } else {
      // Filters ride the query upstream, so a page of 24 is a page of matches.
      const data = await mangaSearch({
        q: mState.q, page, limit: 24,
        type: mState.type, status: mState.status, genre: mState.genre,
      });
      items = data.items ?? [];
      mState.page = data.meta?.page ?? page;
      hasNext = !!data.meta?.hasNext;
    }
    mState.hasNext = hasNext;

    grid.innerHTML = items.map(mangaCardHtml).join('');
    $('#manga-count').textContent = `${items.length}${listMode ? ' found' : ' trending now'}`;
    mangaNote(items.length ? '' : (listMode
      ? 'Nothing matched — try another title, or clear the filters.'
      : 'Nothing came back from the catalog — try again in a moment.'));
    pager.hidden = !listMode || (!hasNext && mState.page <= 1);
    $('#manga-prev').disabled = mState.page <= 1;
    $('#manga-next').disabled = !hasNext;
    $('#manga-pageinfo').textContent = `page ${mState.page}`;
  } catch (e) {
    grid.innerHTML = '';
    $('#manga-count').textContent = '';
    pager.hidden = true;
    mangaNote(`Could not load the manga catalog (${e.message}). `
      + 'The catalog comes from mangafire.to — check the connection and try again.');
  }
}

function searchManga(q) {
  mState.q = (q ?? '').trim();
  loadManga(1);
}

/* ---------------------------------------------------------------- detail */

/** Open titles, kept so the back stack can reopen them without a new fetch. */
const detailCache = new Map();
const pagesCache = new Map();

const dState = { hid: null, detail: null, chapters: [] };
/** Where the tracking slot sat before the title page moved it, so the tile can
 *  put it back. Null until a title page is rendered. */
let slotHome = null;
/** Which chapter the reader is on, and a token so a slow fetch cannot land
 *  its pages on a chapter the reader has already left. */
const rState = { idx: -1 };
let rSeq = 0;

/* ------------------------------------------------------- the reader's chrome
   What MangaFire's reader puts on screen when you tap the middle of a page,
   and the reason it works on a phone: the controls are something you bring
   up, not a frame the pages sit inside. The chapter's position is a pill at
   the top with a step either side of it, and the things a reader actually
   reaches for hang off the right edge — the shelf, the title page, a jump to
   any chapter, how pages are sized, what the controls do, and what to do
   about a chapter whose pages did not arrive.

   Every button is one of this app's own actions, so the rail is a second way
   to the same places rather than a second app: the pill steps through the
   same chapter walk the "Newer/Older" rows use (manga-dupes.js, so a twin
   upload is never a step), the title icon is the screen this reader was
   opened from, and the jump list is the title's own chapter list. */
const RAIL_ICONS = {
  // Home: the way out of the reader, back to the shelf.
  home: '<path d="M3.5 10.6 12 3.8l8.5 6.8"/><path d="M5.6 9.4V20h12.8V9.4"/><path d="M10 20v-5.4h4V20"/>',
  // An open book: the title page, whose chapter list is the spine of a title.
  book: '<path d="M12 6.4C10.2 5 8 4.3 5.5 4.3c-.9 0-1.7.1-2.5.3v13c.8-.2 1.6-.3 2.5-.3 2.5 0 4.7.7 6.5 2.1"/><path d="M12 6.4c1.8-1.4 4-2.1 6.5-2.1.9 0 1.7.1 2.5.3v13c-.8-.2-1.6-.3-2.5-.3-2.5 0-4.7.7-6.5 2.1"/><path d="M12 6.4v15"/>',
  // A list: jump straight to a chapter without leaving the reader.
  list: '<path d="M4 6.5h.01M4 12h.01M4 17.5h.01"/><path d="M8.5 6.5H20M8.5 12H20M8.5 17.5H20"/>',
  // Reading settings: how the pages are sized, and whether they touch.
  gear: '<circle cx="12" cy="12" r="3.1"/><path d="M19.4 14.5a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.03 1.56V21a2 2 0 1 1-4 0v-.11a1.7 1.7 0 0 0-1.1-1.55 1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.7 1.7 0 0 0 .34-1.87 1.7 1.7 0 0 0-1.56-1.03H3a2 2 0 1 1 0-4h.11A1.7 1.7 0 0 0 4.65 8.9a1.7 1.7 0 0 0-.34-1.87l-.06-.06A2 2 0 1 1 7.08 4.14l.06.06a1.7 1.7 0 0 0 1.87.34H9.1a1.7 1.7 0 0 0 1.03-1.56V3a2 2 0 1 1 4 0v.11a1.7 1.7 0 0 0 1.03 1.56 1.7 1.7 0 0 0 1.87-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.7 1.7 0 0 0-.34 1.87v.01a1.7 1.7 0 0 0 1.56 1.03H21a2 2 0 1 1 0 4h-.11a1.7 1.7 0 0 0-1.49 1.02z"/>',
  // A question mark: what the controls do.
  help: '<circle cx="12" cy="12" r="8.6"/><path d="M9.6 9.3a2.5 2.5 0 1 1 3.5 2.3c-.7.35-1.1.8-1.1 1.6v.4"/><path d="M12 16.6h.01"/>',
  // A flag: a chapter whose pages did not arrive.
  flag: '<path d="M5.5 21V4.2"/><path d="M5.5 4.7c3.4-1.2 6.6 1.4 10 0v8.2c-3.4 1.4-6.6-1.2-10 0z"/>',
};

const railButton = (what, label) => `
      <button class="rd-rail-btn" type="button" data-rd="${what}" aria-label="${label}" title="${label}">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${RAIL_ICONS[what]}</svg>
      </button>`;

/* ------------------------------------------------------- the reader's settings
   MangaFire's reader settings, in the shape they put them in: a **Layout** tab
   (how a chapter is arranged) and an **Image** tab (how a page is drawn), both
   reached from the rail's gear. Every one of them is written here as one
   attribute or custom property on the sheet, so this file has exactly one
   place to write them and 95-manga.css one place to read them.
*/

const RD_LAYOUTS = ['single', 'double', 'strip'];
const RD_BAR_SHAPES = ['off', 'hairline', 'thin', 'medium', 'thick'];
const RD_TAP_MODES = ['single', 'double'];
const RD_MARGIN_STEP = 4;
const RD_MARGIN_MAX = 40;
const RD_DIM_MAX = 80;

const onOff = (v) => (v ? 'on' : 'off');
const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, Number(n) || 0));
const marginOf = () => clamp(prefs.readerMargin, 0, RD_MARGIN_MAX);

/**
 * The scroller the reading position is measured against.
 *
 * Long strip reads down the sheet, the two paged layouts read across the pages
 * element itself, and everything that follows a reader through a chapter — the
 * progress bar, the note of where they stopped, the chrome putting itself away
 * — has to ask this rather than assume the sheet.
 */
function readScroll() {
  const pages = $('#rd-pages');
  const paged = !!pages && pages.dataset.layout !== 'strip';
  return {
    el: paged ? pages : $('#sheet'),
    paged,
    pos: paged ? 'scrollLeft' : 'scrollTop',
    size: paged ? 'scrollWidth' : 'scrollHeight',
    span: paged ? 'clientWidth' : 'clientHeight',
  };
}

/** How far through the chapter the screen is, 0..1. */
function readProgress() {
  const s = readScroll();
  if (!s.el) return 0;
  const max = s.el[s.size] - s.el[s.span];
  if (!(max > 0)) return 0;
  return clamp(s.el[s.pos] / max, 0, 1);
}

/** The progress bar, if the reader asked for one. */
function paintReadProgress() {
  const fill = $('#rd-bar-fill');
  if (!fill) return;
  fill.style.width = `${(readProgress() * 100).toFixed(1)}%`;
}

/** The reader's own settings, as they were left on this device. */
function applyReadPrefs() {
  const sheet = $('#sheet');
  const pages = $('#rd-pages');
  if (!sheet || !pages) return;
  pages.dataset.layout = RD_LAYOUTS.includes(prefs.readerLayout) ? prefs.readerLayout : 'strip';
  pages.dataset.fit = prefs.readerFit === 'height' ? 'height' : 'width';
  sheet.dataset.rdStretch = onOff(prefs.readerStretch);
  sheet.dataset.rdMaxW = onOff(prefs.readerMaxW);
  sheet.dataset.rdMaxH = onOff(prefs.readerMaxH);
  sheet.dataset.rdGray = onOff(prefs.readerGray);
  sheet.dataset.rdTap = RD_TAP_MODES.includes(prefs.readerTap) ? prefs.readerTap : 'single';
  sheet.style.setProperty('--rd-margin', `${marginOf()}px`);
  const dim = $('#rd-dim-layer');
  if (dim) dim.style.opacity = String(clamp(prefs.readerDim, 0, RD_DIM_MAX) / 100);
  const bar = $('#rd-bar');
  if (bar) bar.dataset.shape = RD_BAR_SHAPES.includes(prefs.readerBar) ? prefs.readerBar : 'off';
  paintReadProgress();
  // After the arrangement is on the element, because that is what decides which
  // element scrolls: bound any earlier and the listener lands on the pages in a
  // long strip, where nothing ever scrolls and no spot is ever written.
  bindReaderScroll();
}

/** The pages of a chapter, as the reader draws them: one column, no seams. */
const pagesHtml = (pages) => (pages.length
  ? pages.map((p, i) => `<img class="rd-page" loading="lazy" decoding="async" src="${esc(proxied(p.url))}" alt="Page ${i + 1}">`).join('')
  : '<p class="note">No pages came back for this chapter.</p>');

/* ---- where the reader stopped --------------------------------------------
   A page and a fraction of it, written as they scroll and read again when the
   chapter is reopened: a long strip is a hundred screens of art, so opening it
   at the top is opening it in the wrong place. See manga-pos.js for why the
   record is a page rather than a pixel offset. */

/**
 * The page at the top of the screen, and how far into it the reader is.
 *
 * Measured against the scroller's own edge — the part of the page the reader is
 * looking at — and not against the pixel offset it happens to be scrolled to.
 * The two are the same number only before anything has been scrolled, which is
 * why comparing a page's position with `scrollTop` named a page the reader was
 * not looking at.
 */
function currentSpot() {
  const s = readScroll();
  const pages = $$('.rd-page', $('#rd-pages'));
  if (!s.el || !pages.length) return null;
  const box = s.el.getBoundingClientRect();
  for (let i = 0; i < pages.length; i++) {
    const r = pages[i].getBoundingClientRect();
    const start = s.paged ? r.left - box.left : r.top - box.top;
    const size = s.paged ? r.width : r.height;
    if (start + size > 0) return { p: i, f: size > 0 ? clamp(-start / size, 0, 0.999) : 0 };
  }
  // No page on the screen at all: there is nothing here to come back to.
  return null;
}

function saveReadSpot() {
  const ch = dState.chapters[rState.idx];
  if (!ch || !$('#rd-ui')) return;
  // A sheet on its way out has no height left to measure against, and every
  // page then reads as "above the screen" — which is how leaving a chapter
  // halfway through used to record it as finished.
  const s = readScroll();
  if (!s.el || !s.el[s.span]) return;
  const spot = currentSpot();
  if (!spot) return;
  const record = { hid: dState.hid, p: spot.p, f: spot.f, n: $$('.rd-page', $('#rd-pages')).length };
  // A chapter read to its last page is not a chapter to come back into the
  // middle of: reopening it starts at the top, which is where a re-read begins.
  if (readToTheEnd(record)) { dropSpot(ch.id); return; }
  noteSpot(ch.id, record);
}

/** A chapter starts at its first page, whichever page the last one ended on. */
function openAtTop() {
  const pages = $('#rd-pages');
  const sheet = $('#sheet');
  if (pages) { pages.scrollLeft = 0; pages.scrollTop = 0; }
  if (sheet) sheet.scrollTop = 0;
  paintReadProgress();
}

/**
 * Puts the reader back where they stopped — and keeps them there while the
 * pages above arrive.
 *
 * The pages are lazy images with no intrinsic size declared, so at the moment
 * the position is restored everything above the target is zero pixels tall; as
 * each image loads, the strip grows from the top and pushes the reader back
 * towards the start of the chapter. So the target is fetched first, put under
 * the top of the screen again for as long as that takes, and the whole attempt
 * is abandoned the moment the reader touches the screen — a restore that fights
 * a thumb is worse than no restore at all.
 */
function restoreReadSpot() {
  const ch = dState.chapters[rState.idx];
  if (!ch) return;
  const spot = spotFor(ch.id);
  if (!spot || (!spot.p && !spot.f)) return;
  const pages = $$('.rd-page', $('#rd-pages'));
  const target = pages[spot.p];
  if (!target) return;
  target.loading = 'eager';

  let stopped = false;
  const stop = () => { stopped = true; };

  ['pointerdown', 'wheel', 'touchstart', 'keydown'].forEach((type) =>
    document.addEventListener(type, stop, { once: true, passive: true }));

  // Absolute, not a nudge: every call works out where the page really is in the
  // content and puts the scroller there. A nudge is only right if the layout it
  // was measured against still holds, and on the first frames it does not — the
  // strip has no heights yet and the row has no widths — so a clamped first
  // attempt would leave the reader at the end of the chapter for good.
  const put = () => {
    if (stopped) return;
    const s = readScroll();
    if (!s.el) return;
    const r = target.getBoundingClientRect();
    const box = s.el.getBoundingClientRect();
    const size = s.paged ? r.width : r.height;
    const at = s.el[s.pos] + (s.paged ? r.left - box.left : r.top - box.top);
    const head = at + spot.f * size;
    s.el[s.pos] = clamp(head, 0, Math.max(0, s.el[s.size] - s.el[s.span]));
    paintReadProgress();
  };

  // A frame later: the chapter has just been moved to its first page, and the
  // rects read in that same frame still describe where it was — mixing the two
  // is how a restore lands a screen past the page it was asked for.
  requestAnimationFrame(put);

  // Nudged until the strip has stopped growing, not for a fixed number of
  // ticks: which images arrive, and in what order, is the network's business,
  // and a count guessed at here ends either too early — the reader drifts
  // forward as the pages above land — or with the restore still nudging someone
  // who has started reading again. Three still frames means settled, and the
  // ceiling is only there so nothing runs forever.
  let still = 0;
  let tries = 0;
  rdRestoring = true;
  // A page arriving is the strip growing, which moves the page the reader asked
  // for: settled is not "has not moved for three frames" but "nothing is still
  // arriving", so a load starts the count again.
  const slot = $('#rd-pages');
  const wake = () => { still = 0; };
  if (slot) slot.addEventListener('load', wake, true);
  const done = () => {
    rdRestoring = false;
    if (slot) slot.removeEventListener('load', wake, true);
    clearInterval(timer);
  };
  const timer = setInterval(() => {
    if (stopped) { done(); return; }
    const s = readScroll();
    const before = s.el ? s.el[s.pos] : 0;
    put();
    const after = s.el ? s.el[s.pos] : 0;
    still = Math.abs(after - before) < 1 ? still + 1 : 0;
    if (still >= 3 || ++tries > 60) done();
  }, 200);
}

/**
 * A chapter tried once and read: the spot goes when the chapter is finished,
 * not when it is left. There is no "finished" signal in a webtoon — a reader
 * who stopped on the last page is at 100%, and one who stopped at 40% is not.
 */
function readToTheEnd(spot) {
  return !!spot && spot.n > 1 && spot.p >= spot.n - 1 && spot.f > 0.5;
}

/* ---- the gear's sheet: the same two tabs MangaFire's reader has ---------- */

/** The three page arrangements, drawn as the arrangement they make. */
const RD_LAYOUT_ICONS = {
  single: '<rect x="7" y="3.5" width="10" height="17" rx="1.6"/><path d="M11 8.5h2"/>',
  double: '<rect x="2.6" y="5" width="8.4" height="14" rx="1.4"/><rect x="13" y="5" width="8.4" height="14" rx="1.4"/>',
  strip: '<rect x="6" y="2.6" width="12" height="5.4" rx="1.2"/><rect x="6" y="9.3" width="12" height="5.4" rx="1.2"/><rect x="6" y="16" width="12" height="5.4" rx="1.2"/>',
};
const RD_LAYOUT_WORDS = { single: 'Single', double: 'Double', strip: 'Long strip' };
const RD_BAR_WORDS = { off: 'No bar', hairline: 'Hairline', thin: 'Thin', medium: 'Medium', thick: 'Thick' };

/** A checkbox row, named by the preference it writes. */
const rdCheck = (what, on, word) => `
      <label class="rd-check">
        <input type="checkbox" data-rd-flag="${what}" ${on ? 'checked' : ''}>
        <span>${word}</span>
      </label>`;

const readerTabsHtml = () => `
    <div class="rd-tabs" role="tablist" aria-label="Reading settings">
      <button class="rd-tab" type="button" role="tab" data-rd-tab="layout" aria-selected="true">Layout</button>
      <button class="rd-tab" type="button" role="tab" data-rd-tab="image" aria-selected="false">Image</button>
    </div>`;

const readerSettingsHtml = () => `
    <div class="rd-panes">
      <div class="rd-pane" data-rd-pane="layout">
        <p class="rd-label">Layout</p>
        <div class="rd-tiles">${RD_LAYOUTS.map((what) => `
          <button class="rd-tile" type="button" data-rd-layout="${what}" aria-pressed="${prefs.readerLayout === what}">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true">${RD_LAYOUT_ICONS[what]}</svg>
            <span>${RD_LAYOUT_WORDS[what]}</span>
          </button>`).join('')}
        </div>

        <p class="rd-label">Margin</p>
        <div class="rd-step">
          <button class="rd-step-btn" type="button" data-rd-margin="-1" aria-label="Less margin">−</button>
          <span class="rd-step-value" id="rd-margin-value">${marginOf()}</span>
          <button class="rd-step-btn" type="button" data-rd-margin="1" aria-label="More margin">+</button>
          <button class="rd-reset" type="button" data-rd-margin-reset>Reset</button>
        </div>

        <p class="rd-label">Progress bar</p>
        <div class="rd-shapes">${RD_BAR_SHAPES.map((shape) => `
          <button class="rd-shape" type="button" data-rd-bar="${shape}" aria-pressed="${prefs.readerBar === shape}"
                  aria-label="${RD_BAR_WORDS[shape]}" title="${RD_BAR_WORDS[shape]}">${shape === 'off'
    ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>'
    : '<i></i>'}</button>`).join('')}
        </div>

        <p class="rd-label">Show controls</p>
        <div class="rd-two">
          <button class="rd-wide" type="button" data-rd-tap="single" aria-pressed="${prefs.readerTap !== 'double'}">Single tap</button>
          <button class="rd-wide" type="button" data-rd-tap="double" aria-pressed="${prefs.readerTap === 'double'}">Double tap</button>
        </div>
      </div>

      <div class="rd-pane" data-rd-pane="image" hidden>
        <p class="rd-label">Image sizing</p>
        <label class="rd-check">
          <input type="checkbox" data-rd-fit="width" ${prefs.readerFit === 'height' ? '' : 'checked'}>
          <span>Fit width</span>
        </label>
        <label class="rd-check">
          <input type="checkbox" data-rd-fit="height" ${prefs.readerFit === 'height' ? 'checked' : ''}>
          <span>Fit height</span>
        </label>
        ${rdCheck('readerStretch', prefs.readerStretch, 'Stretch small images')}
        ${rdCheck('readerMaxW', prefs.readerMaxW, 'Limit max width')}
        ${rdCheck('readerMaxH', prefs.readerMaxH, 'Limit max height')}

        <p class="rd-label">Image coloring</p>
        ${rdCheck('readerGray', prefs.readerGray, 'Grayscale')}

        <p class="rd-label rd-label-row">Dim screen <b id="rd-dim-value">${clamp(prefs.readerDim, 0, RD_DIM_MAX)}%</b></p>
        <input class="rd-range" id="rd-dim" type="range" min="0" max="${RD_DIM_MAX}" step="5"
               value="${clamp(prefs.readerDim, 0, RD_DIM_MAX)}" aria-label="Dim screen">
      </div>
    </div>`;

/**
 * Redraws every control in the sheet from the preferences.
 *
 * In place rather than by rebuilding the markup: a reader who has just pressed
 * `+` four times should still be looking at the same sheet, the same tab and
 * the same scroll position inside it.
 */
function paintReaderSheet(panel) {
  $$('[data-rd-layout]', panel).forEach((b) =>
    b.setAttribute('aria-pressed', String(b.dataset.rdLayout === prefs.readerLayout)));
  $$('[data-rd-bar]', panel).forEach((b) =>
    b.setAttribute('aria-pressed', String(b.dataset.rdBar === prefs.readerBar)));
  $$('[data-rd-tap]', panel).forEach((b) =>
    b.setAttribute('aria-pressed', String(b.dataset.rdTap === (prefs.readerTap === 'double' ? 'double' : 'single'))));
  // Fit width and fit height are one choice in two boxes: unchecking the one
  // that is on picks the other, which is what a two-state control should do.
  $$('[data-rd-fit]', panel).forEach((box) => {
    box.checked = prefs.readerFit === 'height'
      ? box.dataset.rdFit === 'height'
      : box.dataset.rdFit === 'width';
  });
  $$('[data-rd-flag]', panel).forEach((box) => { box.checked = !!prefs[box.dataset.rdFlag]; });
  const value = $('#rd-margin-value', panel);
  if (value) value.textContent = String(marginOf());
  const dim = $('#rd-dim', panel);
  if (dim) dim.value = String(clamp(prefs.readerDim, 0, RD_DIM_MAX));
  const dimValue = $('#rd-dim-value', panel);
  if (dimValue) dimValue.textContent = `${clamp(prefs.readerDim, 0, RD_DIM_MAX)}%`;
}

/** One write path: the preference is set, kept, and drawn. */
function setReadPref(key, value) {
  prefs[key] = value;
  savePrefs();
  applyReadPrefs();
}

function wireReaderSheet(panel) {
  $$('[data-rd-tab]', panel).forEach((tab) => {
    tab.onclick = () => {
      $$('[data-rd-tab]', panel).forEach((t) => t.setAttribute('aria-selected', String(t === tab)));
      $$('[data-rd-pane]', panel).forEach((p) => { p.hidden = p.dataset.rdPane !== tab.dataset.rdTab; });
    };
  });
  $$('[data-rd-layout]', panel).forEach((b) => {
    b.onclick = () => { setReadPref('readerLayout', b.dataset.rdLayout); paintReaderSheet(panel); };
  });
  $$('[data-rd-margin]', panel).forEach((b) => {
    b.onclick = () => {
      setReadPref('readerMargin', clamp(marginOf() + Number(b.dataset.rdMargin) * RD_MARGIN_STEP, 0, RD_MARGIN_MAX));
      paintReaderSheet(panel);
    };
  });
  const reset = $('[data-rd-margin-reset]', panel);
  if (reset) reset.onclick = () => { setReadPref('readerMargin', 0); paintReaderSheet(panel); };
  $$('[data-rd-bar]', panel).forEach((b) => {
    b.onclick = () => { setReadPref('readerBar', b.dataset.rdBar); paintReaderSheet(panel); };
  });
  $$('[data-rd-tap]', panel).forEach((b) => {
    b.onclick = () => { setReadPref('readerTap', b.dataset.rdTap); paintReaderSheet(panel); };
  });
  $$('[data-rd-fit]', panel).forEach((box) => {
    box.onchange = () => { setReadPref('readerFit', box.dataset.rdFit === 'height' ? 'height' : 'width'); paintReaderSheet(panel); };
  });
  $$('[data-rd-flag]', panel).forEach((box) => {
    box.onchange = () => setReadPref(box.dataset.rdFlag, box.checked);
  });
  const dim = $('#rd-dim', panel);
  if (dim) {
    dim.oninput = () => {
      const box = $('#rd-dim', panel);
      applyDim(Number(box.value));
      const value = $('#rd-dim-value', panel);
      if (value) value.textContent = `${clamp(box.value, 0, RD_DIM_MAX)}%`;
    };
    dim.onchange = () => setReadPref('readerDim', clamp(dim.value, 0, RD_DIM_MAX));
  }
}

/** The wash the slider moves, drawn as it is dragged and kept on release. */
function applyDim(percent) {
  const layer = $('#rd-dim-layer');
  prefs.readerDim = clamp(percent, 0, RD_DIM_MAX);
  if (layer) layer.style.opacity = String(prefs.readerDim / 100);
}

/**
 * Everything the controls do, wired to the chapter on screen.
 *
 * The panel is one element rewritten per choice rather than six screens: it
 * holds a chapter list hundreds of rows long for one icon and two buttons for
 * another, and only one of those is ever up. Closing it, or scrolling the
 * chapter under it, puts the reader back to pages and nothing else.
 */
function bindReaderControls({ go, chapter, atNewest, atOldest }) {
  const ui = $('#rd-ui');
  const panel = $('#rd-panel');
  if (!ui || !panel) return;
  applyReadPrefs();

  const closePanel = () => { panel.hidden = true; panel.innerHTML = ''; };
  const hideUi = () => {
    ui.hidden = true;
    ui.setAttribute('aria-hidden', 'true');
    closePanel();
  };
  /** The panel, with its own header. `raw` keeps a header that is not a title
   *  (the settings sheet's tabs, which sit where a title would). */
  const openPanel = (head, body, { raw = false } = {}) => {
    panel.innerHTML = `<div class="rd-panel-head">
        ${raw ? head : `<b>${esc(head)}</b>`}
        <button class="rd-panel-x" type="button" data-rd-close aria-label="Close">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
               stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>
        </button>
      </div>${body}`;
    panel.hidden = false;
    $('[data-rd-close]', panel).onclick = closePanel;
  };

  $('#rd-ui-prev').onclick = () => { if (!atNewest) go(-1); };
  $('#rd-ui-next').onclick = () => { if (!atOldest) go(1); };

  const jumpPanel = () => `<div class="rd-jump">${dState.chapters.map((c, i) => `
      <button class="ch-card rd-jump-row" type="button" data-rd-jump="${esc(String(c.id))}"
              ${i === rState.idx ? 'aria-current="true"' : ''}>
        <span class="ch-num ${isRead(dState.hid, c.number) ? 'ch-num--read' : ''}">Ch. ${esc(String(c.number))}</span>
        <span class="ch-title">${esc(c.name ?? '')}</span>
      </button>`).join('')}</div>`;

  const showJump = () => {
    openPanel('Chapters', jumpPanel());
    // Opened where the reader already is, not at chapter one: a title with a
    // thousand rows would otherwise always open on the wrong end of it.
    const here = $('[aria-current="true"]', panel);
    const list = $('.rd-jump', panel);
    if (here && list) list.scrollTop = Math.max(0, here.offsetTop - list.clientHeight / 2);
    $$('[data-rd-jump]', panel).forEach((b) => {
      b.onclick = () => {
        const id = Number(b.dataset.rdJump);
        hideUi();
        if (id && id !== Number(chapter.id)) openChapter(id);
      };
    });
  };

  /* ---- the gear's sheet: Layout and Image, the two tabs MangaFire has ----- */
  const showSettings = () => {
    openPanel(readerTabsHtml(), readerSettingsHtml(), { raw: true });
    paintReaderSheet(panel);
    wireReaderSheet(panel);
  };

  const showHelp = () => openPanel('Controls', `
    <ul class="rd-help">
      <li>Tap the middle of the page to bring these controls up, and again to put them away.</li>
      <li>Scroll to read — the controls hide themselves as soon as the pages move.</li>
      <li>The pill at the top steps to the newer and older chapter; a chapter posted twice is not two steps.</li>
      <li>The rail on the right: the shelf, this title's page, a jump to any chapter, the reading settings, this list, and where to go when a chapter will not load.</li>
      <li>The reading settings are two tabs — <b>Layout</b> (one page, two, or a long strip; the margin; a progress bar; whether a single or a double tap brings these controls up) and <b>Image</b> (how a page is sized, grayscale, and how far the screen is dimmed).</li>
      <li>A chapter reopens where it was left, not at its first page.</li>
    </ul>`);

  const showProblem = () => {
    openPanel('Chapter will not load?', `
      <p class="note">Pages come from the source site itself, so a page that is
        missing there is missing here too — reloading asks it again in case the
        answer was only slow.</p>
      <div class="rd-choice-row">
        <button class="chip" type="button" data-rd-reload="1">Reload this chapter</button>
        ${shellCan('openSource') ? '<button class="chip" type="button" data-rd-source="1">Open on mangafire</button>' : ''}
      </div>`);
    $('[data-rd-reload]', panel).onclick = async () => {
      hideUi();
      pagesCache.delete(chapter.id);
      const slot = $('#rd-pages');
      if (!slot) return;
      slot.innerHTML = '<div class="center-spin"><div class="spinner"></div></div>';
      try {
        const again = await mangaPages(chapter.id);
        trimCache(pagesCache, 60);
        pagesCache.set(chapter.id, again);
        slot.innerHTML = pagesHtml(again);
        applyReadPrefs();
      } catch (e) {
        slot.innerHTML = `<p class="note">Still no pages — ${esc(e.message)}</p>`;
      }
    };
    const src = $('[data-rd-source]', panel);
    if (src) src.onclick = () => shellCall('openSource', sourceUrl(dState.hid));
  };

  const actions = {
    home: () => { saveReadSpot(); hideUi(); closeSheet(); showView('manga'); },
    book: () => { saveReadSpot(); hideUi(); goBack(); },   // the title page is below
    list: showJump,
    gear: showSettings,
    help: showHelp,
    flag: showProblem,
  };
  $$('.rd-rail-btn', ui).forEach((b) => { b.onclick = () => actions[b.dataset.rd]?.(); });
}

/**
 * A tap in the middle of the screen brings the controls up — and takes them
 * away again.
 *
 * The middle third only: the sides are where a thumb rests while a chapter is
 * scrolled, and a panel that appeared there would answer the wrong question.
 * A drag is not a tap and neither is a long press, so reading never trips it;
 * and the first scroll puts the controls away, so a chapter is never read
 * through a floating pill. Bound once, on the sheet, because the reader
 * rewrites its own markup on every chapter and a listener per render would
 * stack.
 */
let rdTapStart = null;
// Where the pages were when the chrome came up. A scroll that is not the
// reader's own — an image arriving and shifting the layout under them, or the
// browser restoring a position — is not the pages being read, and must not take
// the controls away in the same breath they appeared in.
let rdUiAt = { top: 0, left: 0 };
/** When the last middle tap landed, for the double-tap mode. */
let rdLastTap = 0;
/** True when the pages have moved far enough for the chrome to be in the way. */
function scrolledFar(el) {
  return Math.abs(el.scrollTop - rdUiAt.top) > 12 || Math.abs(el.scrollLeft - rdUiAt.left) > 12;
}
/** Puts the reader's chrome away, and whatever panel was opened from it. */
function hideReaderChrome() {
  const ui = $('#rd-ui');
  if (!ui || ui.hidden) return;
  ui.hidden = true;
  ui.setAttribute('aria-hidden', 'true');
  const panel = $('#rd-panel');
  if (panel) { panel.hidden = true; panel.innerHTML = ''; }
}
/**
 * What follows the pages as they move: the progress bar, the chrome putting
 * itself away, and the note of where the reader got to — written sparsely,
 * because scrolling is not a reason to touch storage and a long strip is
 * thousands of scrolls.
 *
 * Bound to whichever element is actually scrolling (see readScroll): the sheet
 * in a long strip, the pages element in the two paged layouts. That element is
 * new on every chapter, so this is called per render and remembers what it has
 * already bound — a listener per chapter on the sheet would stack, and the
 * oldest one would be writing the spot of a chapter nobody is reading.
 */
let rdSaveTimer = null;
// True while a spot is being put back: the nudges on the way there are not the
// reader reading, and none of them should be recorded as where they got to.
let rdRestoring = false;
const rdBoundScrollers = typeof WeakSet === 'function' ? new WeakSet() : null;

document.addEventListener('htv:sheet-closed', saveReadSpot);

function scheduleSpot(ms) {
  if (rdRestoring) return;
  clearTimeout(rdSaveTimer);
  rdSaveTimer = setTimeout(saveReadSpot, ms);
}

function onReaderScroll(e) {
  paintReadProgress();
  if (e.target && scrolledFar(e.target)) hideReaderChrome();
  scheduleSpot(700);
}

function bindReaderScroll() {
  const s = readScroll();
  if (!s.el || (rdBoundScrollers && rdBoundScrollers.has(s.el))) return;
  if (rdBoundScrollers) rdBoundScrollers.add(s.el);
  s.el.addEventListener('scroll', onReaderScroll, { passive: true });
  bindReaderPages();
}

/**
 * A page arriving is the spot moving too.
 *
 * The pages carry no height until their images arrive, so the position written
 * after a scroll is measured against a strip that is still growing — the same
 * pixel offset is a later page once the art above has loaded, and resuming to
 * it lands a screen or two past where the reader actually stopped. Each page's
 * `load` re-measures, so what is kept is the position in the finished strip.
 * `load` does not bubble, hence the capture.
 */
function bindReaderPages() {
  const pages = $('#rd-pages');
  if (!pages || pages.dataset.rdPagesBound === 'true') return;
  pages.dataset.rdPagesBound = 'true';
  pages.addEventListener('load', () => scheduleSpot(400), true);
}

function bindReaderTap() {
  const sheet = $('#sheet');
  if (!sheet || sheet.dataset.rdTapBound === 'true') return;
  sheet.dataset.rdTapBound = 'true';

  // A phone put in a pocket mid-chapter is the most ordinary way for a reading
  // position to be lost; the sheet closing has its own listener above.
  try {
    document.addEventListener('visibilitychange', () => { if (document.hidden) saveReadSpot(); });
  } catch (e) { /* older engines: the scroll itself still writes the spot */ }

  sheet.addEventListener('pointerdown', (e) => {
    rdTapStart = { x: e.clientX, y: e.clientY, at: Date.now() };
  }, { passive: true });

  sheet.addEventListener('pointerup', (e) => {
    const start = rdTapStart;
    rdTapStart = null;
    if (!start) return;
    if (Math.hypot(e.clientX - start.x, e.clientY - start.y) > 12) return;   // a scroll
    if (Date.now() - start.at > 700) return;                                 // a long press
    const ui = $('#rd-ui');
    if (!ui) return;                                                         // not the reader
    if (e.target.closest('button, a, input, select, .rd-ui')) return;         // a control
    const x = e.clientX / Math.max(window.innerWidth, 1);
    if (x < 0.33 || x > 0.67) return;
    // "Show controls: double tap" is a reader who scrolls with a thumb resting
    // mid-page — one tap must then be nothing at all, or the chrome appears
    // every time the strip is touched.
    const wantsDouble = $('#sheet').dataset.rdTap === 'double';
    const now = Date.now();
    const paired = now - rdLastTap < 350;
    rdLastTap = paired ? 0 : now;
    if (wantsDouble && !paired) return;
    const opening = ui.hidden;
    ui.hidden = !opening;
    ui.setAttribute('aria-hidden', String(!opening));
    if (opening) {
      const s = readScroll();
      if (s.el) rdUiAt = { top: s.el.scrollTop, left: s.el.scrollLeft };
    } else {
      // One gesture, one thing: putting the controls away also puts away
      // whatever panel was opened from them.
      hideReaderChrome();
    }
  }, { passive: true });
}



function trimCache(map, max = 40) {
  while (map.size > max) map.delete(map.keys().next().value);
}

async function loadTitle(hid) {
  if (detailCache.has(hid)) return detailCache.get(hid);
  const [detail, chapters] = await Promise.all([mangaDetail(hid), mangaChapters(hid)]);
  const entry = { detail, chapters };
  trimCache(detailCache);
  detailCache.set(hid, entry);
  return entry;
}

const plain = (html) => esc(String(html ?? '').replace(/<[^>]+>/g, ' ').replace(/\s{2,}/g, ' ').trim());
const label = (s) => String(s ?? '').replace(/_/g, ' ');

function renderDetail() {
  // The title page is not the reader: the phone's status bar comes back for it,
  // which is also how it comes back when the reader steps back here.
  shellCall('setImmersive', false);
  $('#sheet').dataset.reader = 'false';
  const d = dState.detail;
  // A fresh page has the slot in the side column, whatever the last one did
  // with it.
  slotHome = null;
  const genres = (d.genres ?? [])
    .map((g) => `<span class="hero-tag">${esc(g.title ?? g.name ?? String(g))}</span>`).join('');
  // The source rides at the end of the kicker the way a series page names where
  // it came from: the area is one shelf, but a title is mangafire's, and the
  // WebView button below is what opens it there.
  const kicker = [d.type, d.year, label(d.status), 'Mangafire'].filter(Boolean).map(esc).join(' · ');
  const meta = [
    d.rating ? `★ ${Number(d.rating).toFixed(1)}` : null,
    d.follows ? `${fmtCount(d.follows)} follows` : null,
    dState.chapters.length ? `${dState.chapters.length} chapters` : null,
  ].filter(Boolean).map(esc).join(' · ');
  // Read once here, shown in three places: the tile under the header, the
  // details row, and the sentence the tile is announced with. The rhythm is
  // read off one row per chapter: the same chapter posted twice would count as
  // two chapters posted, and its two dates would bend the gap between them.
  const eta = nextChapterEstimate(visibleChapters(dState.chapters, true), { status: d.status });
  // mangafire carries more than one upload of a chapter, so this is on every
  // title with a scanlation race in it — and it is the whole reason the control
  // below exists. A title with none gets no control to press (see
  // manga-dupes.js for what "the same chapter" means here).
  const dupes = duplicateCount(dState.chapters);
  const facts = [
    ['Status', label(d.status)],
    ...(eta ? [['Next chapter', estimateDetail(eta)]] : []),
    ['Type', d.type],
    ['Year', d.year],
    ['Languages', (d.languages ?? []).join(', ')],
    ['Authors', (d.authors ?? []).map((a) => a.title ?? String(a)).join(', ')],
    ['Artists', (d.artists ?? []).map((a) => a.title ?? String(a)).join(', ')],
  ].filter(([, v]) => v);

  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = dState.chapters.length
    ? `${dState.chapters.length} chapters` : label(d.status);

  $('#sheet-body').innerHTML = `
    <div class="detail-hero">
      <div class="detail-poster"><img src="${esc(posterFor(d, 'large') || posterFor(d))}" alt="" loading="lazy"></div>
      <div class="detail-hero-text">
        <div class="detail-kicker">${kicker}</div>
        <h2 class="detail-title">${esc(d.title)}</h2>
        <div class="detail-meta">${meta}</div>
        <div class="detail-tags">${genres}</div>
      </div>
    </div>
    ${mangaActionsHtml(dState.hid, eta)}
    <div class="manga-track" id="manga-track" hidden></div>
    <div class="detail-grid">
      <div class="detail-main">
        <div class="detail-label">Synopsis</div>
        <p class="detail-desc">${plain(d.synopsisHtml) || 'No synopsis was returned for this title.'}</p>
        <section id="manga-chapters">
          <div class="sec-head">
            <div class="sec-head-l">
              <h2>Chapters</h2>
              <span class="sec-count" id="manga-ch-count"></span>
            </div>
            ${dupes ? `<button class="chip ch-dupes" id="manga-hide-dupes" type="button"
              aria-pressed="${prefs.hideDuplicates ? 'true' : 'false'}"
              title="Another scanlation of the same chapter is a second row for one chapter"
              >Hide duplicates</button>` : ''}
          </div>
          <div class="ch-list" id="manga-ch-list"></div>
        </section>
      </div>
      <aside class="detail-side">
        <div class="detail-label">Details</div>
        <div class="mf-facts">
          ${facts.map(([k, v]) => `<div class="mf-fact"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('')}
        </div>
        <div class="mal-slot" data-mal-kind="manga" data-mal-id="${d.malId ?? ''}" data-mal-title="${esc(d.title)}"
             data-mal-for="${esc(dState.hid)}"></div>
      </aside>
    </div>`;

  $('#manga-hide-dupes')?.addEventListener('click', () => {
    prefs.hideDuplicates = !prefs.hideDuplicates;
    savePrefs();
    renderChapterList();
  });

  renderChapterList();
}

/**
 * The title page's own controls, in one row under the header — what can be done
 * with a title before the long read of its chapters: keep it, see when the next
 * chapter is due, track it, or walk to the site it came from.
 *
 * The estimate is a reading rather than a control, so it is a tile and not a
 * button: there is nothing to press, and what it says is in its own label. The
 * tracker is a button, and the one thing it does is bring MyAnimeList's own
 * controls up to this row — this client never scrolls the reader to a control
 * (see the guard in test/web-client.test.mjs), so the control comes to the
 * reader instead, and a second press sends it back where it was. A build with
 * no MyAnimeList client id gets three tiles, not a tracker that cannot answer.
 */
function mangaActionsHtml(hid, eta) {
  const saved = isSaved(hid);
  const word = saved ? 'In library' : 'Add to library';
  const chip = estimateChip(eta);
  const about = estimateTitle(eta);
  return `<div class="detail-actions manga-actions">
    <button class="act-btn" type="button" data-mangsave="${esc(hid)}" aria-pressed="${saved}"
            aria-label="${esc(word)}" title="${esc(word)}">
      <span class="act-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M12 20.4 4.9 14.1A5.3 5.3 0 0 1 12 6.3a5.3 5.3 0 0 1 7.1 7.8Z"/></svg>
      </span>
      <span class="act-label">${esc(word)}</span>
    </button>
    <span class="act-btn act-stat" role="note" aria-label="${esc(about)}" title="${esc(about)}">
      <span class="act-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M7 3h10M7 21h10M7.5 3c0 4 4.5 5.2 4.5 9s-4.5 5-4.5 9M16.5 3c0 4-4.5 5.2-4.5 9s4.5 5 4.5 9"/></svg>
      </span>
      <span class="act-label">${esc(chip)}</span>
    </span>
    ${configured() ? `<button class="act-btn" type="button" data-maltrack aria-expanded="false"
            aria-label="MyAnimeList — bring the list controls up here" title="MyAnimeList">
      <span class="act-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20 11.5A8 8 0 1 0 12 20a8 8 0 0 0 6.9-3.9"/><path d="M20 5.5V11h-5.5"/></svg>
      </span>
      <span class="act-label">Tracking</span>
    </button>` : ''}
    <button class="act-btn act-btn--source" type="button" data-mangasrc="${esc(sourceUrl(hid))}"
            aria-label="Open this title on mangafire.to" title="Open on mangafire.to">
      <span class="act-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><rect x="2.5" y="4.5" width="19" height="15" rx="4"/><path d="M10.2 9.3v5.4l4.8-2.7z" fill="currentColor" stroke="none"/></svg>
      </span>
      <span class="act-label">WebView</span>
    </button>
  </div>`;
}

/**
 * The account's own reading, folded into this device's.
 *
 * MyAnimeList holds how far the reader got there (`num_chapters_read`), and the
 * tracking slot on this page is where that number arrives — so the list is
 * redrawn the moment it does: a title read to chapter 48 on the list has 48
 * quiet rows, whether or not this device opened one of them. The number is kept
 * per title rather than by filing each chapter, so it survives a restart and
 * costs nothing when it has not moved (`setProgress`).
 *
 * Subscribed once, at module load: the slot can be filled while the reader is
 * in another area, and the number it brings is worth keeping either way.
 */
onTrackedProgress((kind, appId, entry) => {
  if (kind !== 'manga' || !appId) return;
  const read = Number(entry?.num_chapters_read);
  // An entry that is gone — taken off the list — clears the watermark, and the
  // chapters this device opened stay read: they are two different memories.
  setProgress(appId, Number.isFinite(read) ? read : 0);
  if (String(appId) === String(dState.hid)) renderChapterList();
});

function renderChapterList() {
  const list = $('#manga-ch-list');
  if (!list) return;
  if (!dState.chapters.length) {
    list.innerHTML = '<p class="note">No chapters were returned for this title.</p>';
    const count = $('#manga-ch-count');
    if (count) count.textContent = '';
    return;
  }
  // One row per chapter, or every row upstream sent — the reader's choice, kept
  // across titles and restarts in the same guarded prefs as the look of the
  // app. The rows are drawn from `dState.chapters` whichever way, so opening a
  // chapter still opens the copy whose row was pressed.
  const rowsOut = visibleChapters(dState.chapters, prefs.hideDuplicates);
  const hidden = dState.chapters.length - rowsOut.length;
  const toggle = $('#manga-hide-dupes');
  if (toggle) toggle.setAttribute('aria-pressed', String(!!prefs.hideDuplicates));
  // A chapter this device has already read is drawn quieter than the ones it
  // has not — the number steps back, and the row says so out loud for a reader
  // who cannot see the colour. The list is the one place the question "have I
  // read this?" is asked of every chapter of a title at once, so it is the one
  // place the answer belongs.
  list.innerHTML = rowsOut.map((c) => {
    const read = isRead(dState.hid, c.number);
    const label = `Chapter ${esc(String(c.number))}${read ? ', already read' : ''}`
      + (c.name ? ` — ${esc(c.name)}` : '');
    return `
    <button class="ch-card" type="button" data-mfch="${Number(c.id)}"${read ? ' data-read="true"' : ''}
      aria-label="${label}"${read ? ' title="Already read"' : ''}>
      <span class="ch-num${read ? ' ch-num--read' : ''}">Ch. ${esc(String(c.number))}</span>
      <span class="ch-title">${esc(c.name || '')}</span>
      <span class="ch-date">${c.createdAt ? esc(new Date(c.createdAt * 1000).toISOString().slice(0, 10)) : ''}</span>
    </button>`;
  }).join('');
  const count = $('#manga-ch-count');
  if (count) {
    // What is on screen first — that is the number the reader can check — and
    // how many rows were left out to get it.
    count.textContent = hidden
      ? `${rowsOut.length} chapters · ${hidden} duplicates hidden`
      : `${rowsOut.length} chapters`;
  }
}

/** Open a title in the sheet, or — when restoring history — straight into a
 *  chapter. */
async function openManga(hid, chapter) {
  hid = String(hid);
  openSheet('manga', hid, chapter ? String(chapter) : null);
  dState.hid = hid;
  try {
    const { detail, chapters } = await loadTitle(hid);
    dState.detail = detail;
    dState.chapters = chapters;
    if (chapter !== undefined && chapter !== null && chapter !== '') {
      const idx = chapters.findIndex((c) => Number(c.id) === Number(chapter));
      if (idx >= 0) { await openChapter(Number(chapter)); return; }
      // The remembered chapter is gone from upstream (a re-upload, a removed
      // scanlation). Falling back to the title page beats the spinner this
      // used to leave behind. A chapter *number* still finds its chapter, so
      // a rail that only kept the number is not stranded either.
      const byNumber = chapters.findIndex((c) => Number(c.number) === Number(chapter));
      if (byNumber >= 0) { await openChapter(Number(chapters[byNumber].id)); return; }
    }
    renderDetail();
  } catch (e) {
    $('#sheet-body').innerHTML = `<p class="note">Could not load this title — ${esc(e.message)}</p>`;
  }
}

/* ---------------------------------------------------------------- reader */

async function openChapter(chId) {
  const idx = dState.chapters.findIndex((c) => Number(c.id) === Number(chId));
  if (idx < 0) return;
  // The chapter replaces the title page, and back has to be able to return
  // to it — so the step is recorded before anything on screen changes.
  pushNav();
  setSheetOrigin({ kind: 'manga', ref: dState.hid, chapter: String(chId) });
  rState.idx = idx;
  // Reading takes the status bar with it: a quarter of a page of art is a bad
  // thing to spend on a clock, and MangaFire's reader spends none of it. The
  // shell hides that one bar (the back gesture still works) and the page's own
  // chrome follows, because the insets it is laid out on are re-read. Released
  // by the title page and by closeSheet.
  shellCall('setImmersive', true);
  await renderReader();
  // A chapter that opened is a chapter being read, so the account's own list is
  // told — once, never backwards, and never in the reader's way: the write is
  // fire-and-forget.
  const ch = dState.chapters[idx];
  autoAdvance('manga', dState.detail?.malId, ch?.number, dState.detail?.title);
  // A chapter that opened is a chapter read, kept on this device so the list
  // it came from can say so — the next time it is drawn, and every time after
  // that. The write is a local note, so it cannot fail the reader's way.
  markRead(dState.hid, ch?.number);
  // The device keeps its own note of it as well: which chapter, on which
  // title, and — the part that makes the rail a resume rather than a reminder
  // — the chapter's own id, so opening it later lands in this chapter.
  noteOpen('manga', {
    id: dState.hid,
    title: dState.detail?.title,
    cover: posterFor(dState.detail, 'medium'),
    number: Number(ch?.number),
    total: dState.chapters.length,
    ref: ch?.id,
  });
  renderActivity('manga');
}

async function renderReader() {
  const seq = ++rSeq;
  const ch = dState.chapters[rState.idx];
  // The list is newest first: index 0 is the newest chapter, so stepping
  // toward it is "Newer" and away from it is "Older". A step lands on the next
  // *chapter*, never on another upload of this one — and the ends are read the
  // same way, because the last row of a title can be the twin of its last
  // chapter (manga-dupes.js).
  const newer = stepIndex(dState.chapters, rState.idx, -1);
  const older = stepIndex(dState.chapters, rState.idx, 1);
  const atNewest = newer < 0;
  const atOldest = older < 0;

  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = `Ch. ${Number(ch.number)}`;
  // Reading is pages, and nothing else. The sheet's own bar (Back, the chapter
  // count) and the title block that used to sit above the pages are chrome
  // around a chapter nobody asked for: the pill says where in the title you
  // are, the rail is the way out, and the only row left is the one at the end
  // of the chapter, where stepping on is the next thing you want. This is the
  // shape MangaFire's reader has, and the reason it reads as a book.
  $('#sheet').dataset.reader = 'true';

  $('#sheet-body').innerHTML = `
    <div class="rd-pages" id="rd-pages"><div class="center-spin"><div class="spinner"></div></div></div>
    <div class="rd-end">
      <div class="rd-end-title">${esc(dState.detail?.title ?? '')}</div>
      <div class="rd-end-sub">Chapter ${esc(String(ch.number))} of ${dState.chapters.length}${ch.name ? ' · ' + esc(ch.name) : ''}</div>
      <div class="rd-nav">
        <button class="page-btn" id="rd-prev-b" type="button" ${atNewest ? 'disabled' : ''}>← Newer</button>
        <button class="page-btn" id="rd-chapters-b" type="button">Chapters</button>
        <button class="page-btn" id="rd-next-b" type="button" ${atOldest ? 'disabled' : ''}>Older →</button>
      </div>
    </div>
    <div class="rd-ui" id="rd-ui" hidden aria-hidden="true">
      <div class="rd-pill">
        <button class="rd-pill-btn" id="rd-ui-prev" type="button" aria-label="Newer chapter" ${atNewest ? 'disabled' : ''}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14.5 5.5 8 12l6.5 6.5"/></svg>
        </button>
        <span class="rd-pill-pos" id="rd-ui-pos">Ch. ${esc(String(ch.number))} / ${dState.chapters.length}</span>
        <button class="rd-pill-btn" id="rd-ui-next" type="button" aria-label="Older chapter" ${atOldest ? 'disabled' : ''}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9.5 5.5 16 12l-6.5 6.5"/></svg>
        </button>
      </div>
      <div class="rd-rail">${[
        ['home', 'Home'],
        ['book', 'Title and chapters'],
        ['list', 'Jump to a chapter'],
        ['gear', 'Reading settings'],
        ['help', 'What the controls do'],
        ['flag', 'Report a problem'],
      ].map(([what, label]) => railButton(what, label)).join('')}</div>
      <div class="rd-panel" id="rd-panel" hidden></div>
    </div>
    <div class="rd-dim" id="rd-dim-layer" aria-hidden="true"></div>
    <div class="rd-bar" id="rd-bar" data-shape="off" aria-hidden="true"><span id="rd-bar-fill"></span></div>`;

  const go = (delta) => {
    saveReadSpot();   // the chapter being left keeps where the reader got to
    const next = dState.chapters[stepIndex(dState.chapters, rState.idx, delta)];
    if (next) openChapter(Number(next.id));
  };
  $('#rd-prev-b').onclick = () => go(-1);
  $('#rd-next-b').onclick = () => go(1);
  $('#rd-chapters-b').onclick = () => { saveReadSpot(); goBack(); };
  bindReaderControls({ go, chapter: ch, atNewest, atOldest });

  try {
    let pages = pagesCache.get(ch.id);
    if (!pages) {
      pages = await mangaPages(ch.id);
      trimCache(pagesCache, 60);
      pagesCache.set(ch.id, pages);
    }
    if (seq !== rSeq) return;   // another chapter took the screen meanwhile
    const slot = $('#rd-pages');
    if (!slot) return;
    slot.innerHTML = pagesHtml(pages);
    applyReadPrefs();
    // The pages element and the sheet are the same two elements chapter after
    // chapter, so they are still wherever the last one was read: a chapter opens
    // at its first page, and a spot is the only thing that moves it.
    openAtTop();
    // Back where they stopped, once the pages are there to be found.
    restoreReadSpot();
  } catch (e) {
    if (seq !== rSeq) return;
    const slot = $('#rd-pages');
    if (slot) slot.innerHTML = `<p class="note">Could not load the pages — ${esc(e.message)}</p>`;
  }
}

/* ---------------------------------------------------------------- wiring */

function bindMangaView() {
  $('#manga-q')?.addEventListener('input', (e) => {
    const val = e.target.value;
    $('#manga-search-shell').dataset.filled = String(!!val);
    clearTimeout(mDebounce);
    mDebounce = setTimeout(() => searchManga(val), 240);
  });
  $('#manga-clear')?.addEventListener('click', () => {
    $('#manga-q').value = '';
    $('#manga-search-shell').dataset.filled = 'false';
    searchManga('');
    $('#manga-q').focus({ preventScroll: true });
  });

  // Filters change the catalog, not just the view: every change starts a new
  // first page, and the clear button only exists while something is set.
  const filters = [['#manga-type', 'type'], ['#manga-status', 'status'], ['#manga-genre', 'genre']];
  const syncFilters = () => {
    let any = false;
    for (const [sel, key] of filters) {
      const el = $(sel);
      if (!el) continue;
      el.dataset.set = String(!!mState[key]);
      if (mState[key]) any = true;
    }
    $('#manga-filter-clear').hidden = !any;
  };
  for (const [sel, key] of filters) {
    $(sel)?.addEventListener('change', (e) => {
      mState[key] = e.target.value;
      syncFilters();
      loadManga(1);
    });
  }
  $('#manga-filter-clear')?.addEventListener('click', () => {
    for (const [sel, key] of filters) {
      mState[key] = '';
      const el = $(sel);
      if (el) el.value = '';
    }
    syncFilters();
    loadManga(1);
  });

  $('#manga-prev')?.addEventListener('click', () => {
    if (mState.page > 1) loadManga(mState.page - 1).then(() => scrollTo({ top: 0, behavior: 'smooth' }));
  });
  $('#manga-next')?.addEventListener('click', () => {
    if (mState.hasNext) loadManga(mState.page + 1).then(() => scrollTo({ top: 0, behavior: 'smooth' }));
  });
}
bindMangaView();
bindReaderTap();

// Cards in the manga grid, chapters in the list under the detail, and the four
// controls on a title page. All of them are delegated from the document: the
// sheet is rewritten on every open, and handlers bound to its markup would go
// with it.
document.addEventListener('click', (e) => {
  // A shelf's own removal cross sits inside a card that opens a title. Taking a
  // title off a shelf is the cross's business and not the card's, whatever
  // order the two listeners happen to be registered in.
  if (e.target.closest('[data-saved-drop]')) return;

  const card = e.target.closest('[data-manga]');
  if (card) { e.preventDefault(); openManga(card.dataset.manga); return; }
  const ch = e.target.closest('[data-mfch]');
  if (ch && dState.hid) { e.preventDefault(); openChapter(Number(ch.dataset.mfch)); return; }

  // Keep it: the sheet's own record of the title is what the shelf draws, so
  // the card it makes is the same card the grid makes.
  const keep = e.target.closest('[data-mangsave]');
  if (keep) {
    e.preventDefault();
    toggleSaved({
      id: keep.dataset.mangsave,
      title: dState.detail?.title,
      cover: posterFor(dState.detail, 'medium'),
      type: dState.detail?.type,
      year: dState.detail?.year,
      chapters: dState.chapters.length,
    });
    return;
  }

  // The estimate names a date the chapter list is the evidence for, so the tile
  // is read-only by design: this client never scrolls the reader to a section.

  // Tracking lives in one place — the slot in the side column — so this tile
  // brings that slot up to the row rather than growing a second set of controls
  // that could disagree with it. A second press hands it back.
  const track = e.target.closest('[data-maltrack]');
  if (track) {
    e.preventDefault();
    const slot = $('#sheet-body .mal-slot');
    const dock = $('#manga-track');
    if (!slot || !dock) return;
    if (dock.contains(slot)) {
      if (slotHome?.parent) slotHome.parent.insertBefore(slot, slotHome.next);
      else return;
      dock.hidden = true;
      track.setAttribute('aria-expanded', 'false');
      track.setAttribute('aria-label', 'MyAnimeList — bring the list controls up here');
    } else {
      slotHome = { parent: slot.parentElement, next: slot.nextElementSibling };
      dock.hidden = false;
      dock.appendChild(slot);
      track.setAttribute('aria-expanded', 'true');
      track.setAttribute('aria-label', 'MyAnimeList — put the list controls back');
    }
    // preventScroll is the point of the guard in the test suite: the control is
    // already in front of the reader, and a focus that scrolled would move the
    // page out from under the press that put it there.
    slot.querySelector('select, input, button')?.focus({ preventScroll: true });
    return;
  }

  // The source page, in this app's own window. A press cannot walk there on
  // its own: the shell swallows every foreign navigation an injected link could
  // start, which is what keeps an ad from opening the browser over a player —
  // so the URL is asked for through the bridge, which answers only for the
  // source hosts and loads it in the app's own window (MainActivity.openSource,
  // MangaSource). A plain browser has no bridge and is handed the URL the
  // usual way.
  const source = e.target.closest('[data-mangasrc]');
  if (source) {
    e.preventDefault();
    const url = source.dataset.mangasrc;
    if (shellCan('openSource')) shellCall('openSource', url);
    else window.location.assign(url);
  }
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('[data-manga]');
  if (card) { e.preventDefault(); openManga(card.dataset.manga); }
});

// First landing on the view loads the shelf (and the genre list the filter
// needs); revisits keep what was searched. The genre options are upstream's
// own enums, so they are fetched rather than pasted into the markup.
function ensureManga() {
  if (mLoaded) return;
  mLoaded = true;
  loadManga(1);
  mangaFilterOptions().then((opts) => {
    const sel = $('#manga-genre');
    if (!sel || !opts.genres?.length) return;
    sel.innerHTML = '<option value="">All genres</option>'
      + opts.genres.map((g) => `<option value="${Number(g.id)}">${esc(g.name)}</option>`).join('');
  }).catch(() => {});
}
document.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go && go.dataset.go === 'manga') ensureManga();
});

export { loadManga, searchManga, openManga, openChapter, ensureManga };
