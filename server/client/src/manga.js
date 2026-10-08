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

import { $, esc, fmtCount } from './core.js';
import { goBack, pushNav } from './nav-history.js';
import { mangaChapters, mangaDetail, mangaFilterOptions, mangaPages, mangaSearch, mangaTrending, posterFor, proxied } from './manga-api.js';
import { openSheet, setSheetOrigin } from './sheet.js';

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
/** Which chapter the reader is on, and a token so a slow fetch cannot land
 *  its pages on a chapter the reader has already left. */
const rState = { idx: -1 };
let rSeq = 0;

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
  const d = dState.detail;
  const genres = (d.genres ?? [])
    .map((g) => `<span class="hero-tag">${esc(g.title ?? g.name ?? String(g))}</span>`).join('');
  const kicker = [d.type, d.year, label(d.status)].filter(Boolean).map(esc).join(' · ');
  const meta = [
    d.rating ? `★ ${Number(d.rating).toFixed(1)}` : null,
    d.follows ? `${fmtCount(d.follows)} follows` : null,
    dState.chapters.length ? `${dState.chapters.length} chapters` : null,
  ].filter(Boolean).map(esc).join(' · ');
  const facts = [
    ['Status', label(d.status)],
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
          </div>
          <div class="ch-list" id="manga-ch-list"></div>
        </section>
      </div>
      <aside class="detail-side">
        <div class="detail-label">Details</div>
        <div class="mf-facts">
          ${facts.map(([k, v]) => `<div class="mf-fact"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('')}
        </div>
        <div class="mal-slot" data-mal-kind="manga" data-mal-id="${d.malId ?? ''}" data-mal-title="${esc(d.title)}"></div>
      </aside>
    </div>`;

  renderChapterList();
}

function renderChapterList() {
  const list = $('#manga-ch-list');
  if (!list) return;
  if (!dState.chapters.length) {
    list.innerHTML = '<p class="note">No chapters were returned for this title.</p>';
    const count = $('#manga-ch-count');
    if (count) count.textContent = '';
    return;
  }
  list.innerHTML = dState.chapters.map((c) => `
    <button class="ch-card" type="button" data-mfch="${Number(c.id)}">
      <span class="ch-num">Ch. ${esc(String(c.number))}</span>
      <span class="ch-title">${esc(c.name || '')}</span>
      <span class="ch-date">${c.createdAt ? esc(new Date(c.createdAt * 1000).toISOString().slice(0, 10)) : ''}</span>
    </button>`).join('');
  const count = $('#manga-ch-count');
  if (count) count.textContent = `${dState.chapters.length} chapters`;
}

/** Open a title in the sheet, or — when restoring history — straight into a chapter. */
async function openManga(hid, chapter) {
  hid = String(hid);
  openSheet('manga', hid, chapter ? String(chapter) : null);
  dState.hid = hid;
  try {
    const { detail, chapters } = await loadTitle(hid);
    dState.detail = detail;
    dState.chapters = chapters;
    if (chapter != null && chapter !== '') {
      await openChapter(Number(chapter));
      return;
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
  await renderReader();
}

async function renderReader() {
  const seq = ++rSeq;
  const ch = dState.chapters[rState.idx];
  // The list is newest first: index 0 is the newest chapter, so stepping
  // toward it is "Newer" and away from it is "Older".
  const atNewest = rState.idx <= 0;
  const atOldest = rState.idx >= dState.chapters.length - 1;

  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = `Ch. ${Number(ch.number)}`;

  $('#sheet-body').innerHTML = `
    <div class="rd-head">
      <div class="rd-title">${esc(dState.detail?.title ?? '')}</div>
      <div class="rd-sub">Chapter ${esc(String(ch.number))}${ch.name ? ' · ' + esc(ch.name) : ''}</div>
    </div>
    <div class="rd-nav">
      <button class="page-btn" id="rd-prev" type="button" ${atNewest ? 'disabled' : ''}>← Newer</button>
      <button class="page-btn" id="rd-chapters" type="button">Chapters</button>
      <button class="page-btn" id="rd-next" type="button" ${atOldest ? 'disabled' : ''}>Older →</button>
    </div>
    <div class="rd-pages" id="rd-pages"><div class="center-spin"><div class="spinner"></div></div></div>
    <div class="rd-nav">
      <button class="page-btn" id="rd-prev-b" type="button" ${atNewest ? 'disabled' : ''}>← Newer</button>
      <button class="page-btn" id="rd-next-b" type="button" ${atOldest ? 'disabled' : ''}>Older →</button>
    </div>`;

  const go = (delta) => {
    const next = dState.chapters[rState.idx + delta];
    if (next) openChapter(Number(next.id));
  };
  for (const id of ['rd-prev', 'rd-prev-b']) $('#' + id).onclick = () => go(-1);
  for (const id of ['rd-next', 'rd-next-b']) $('#' + id).onclick = () => go(1);
  $('#rd-chapters').onclick = () => goBack();

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
    slot.innerHTML = pages.length
      ? pages.map((p, i) => `<img class="rd-page" loading="lazy" decoding="async" src="${esc(proxied(p.url))}" alt="Page ${i + 1}">`).join('')
      : '<p class="note">No pages came back for this chapter.</p>';
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

// Cards in the manga grid, and chapters in the list under the detail.
document.addEventListener('click', (e) => {
  const card = e.target.closest('[data-manga]');
  if (card) { e.preventDefault(); openManga(card.dataset.manga); return; }
  const ch = e.target.closest('[data-mfch]');
  if (ch && dState.hid) { e.preventDefault(); openChapter(Number(ch.dataset.mfch)); }
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
