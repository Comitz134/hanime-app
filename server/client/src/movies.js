// movies.js — the films & series area.
//
// Catalog comes from f-movies.org through this server's /api/fmovies routes:
// its TMDB proxy answers queries, its static pages answer details and
// episode lists, and playback is the same three embeds the site itself
// offers — built from the title's own id, so there is no stored URL to go
// stale. The browser talks only to this server.
//
// The detail page is the anime area's, reshaped: header, player, episode
// list beside it. A series carries a season strip under the player — the
// seasons arrive with the details, so the strip draws before any episode is
// fetched. A movie has none of that and plays on arrival.

import { $, $$, esc } from './core.js';
import { noteOpen, renderActivity } from './activity.js';
import { openSheet } from './sheet.js';

/* ------------------------------------------------------------------ grid */

const mState = { q: '', page: 1, hasNext: false, type: '' };
let mDebounce;

const kindLabel = (t) => (t === 'tv' ? 'Series' : 'Movie');

/** True when anything narrows the shelf — a query or the kind select. */
function mFiltered() {
  return !!(mState.q || mState.type);
}

function showCardHtml(it) {
  const meta = [kindLabel(it.type), it.year,
    it.score != null ? `★ ${Number(it.score).toFixed(1)}` : null]
    .filter(Boolean).map(esc).join(' · ');
  return `<article class="card" data-show="${esc(`${it.type}/${it.slug}`)}" tabindex="0" role="button" aria-label="${esc(it.title)}">
    <div class="card-frame">
      <img loading="lazy" decoding="async" src="${esc(it.poster ?? '')}" alt="">
      <div class="card-fallback" hidden>${esc(it.title)}</div>
      <div class="card-scrim"></div>
      <div class="card-play"><span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
      </span></div>
    </div>
    <div class="card-body">
      <h3 class="card-title">${esc(it.title)}</h3>
      <p class="card-meta">${meta}</p>
    </div>
  </article>`;
}

function showsNote(msg) {
  const note = $('#movies-note');
  if (!note) return;
  note.hidden = !msg;
  note.textContent = msg || '';
}

async function loadShows(page) {
  const grid = $('#movies-grid');
  const pager = $('#movies-pager');
  if (page > 1) grid.innerHTML = '<div class="center-spin"><div class="spinner"></div></div>';
  try {
    const params = new URLSearchParams({ q: mState.q, page: String(page) });
    if (mState.type) params.set('type', mState.type);
    const res = await fetch(`/api/fmovies/search?${params}`);
    if (!res.ok) throw new Error(`status ${res.status}`);
    const data = await res.json();
    mState.page = data.page;
    mState.hasNext = !!data.hasNext;
    grid.innerHTML = (data.items ?? []).map(showCardHtml).join('');
    $('#movies-count').textContent = `${data.items?.length ?? 0}${mFiltered() ? ' found' : ' on f-movies'}`;
    showsNote((data.items ?? []).length ? '' : (mFiltered()
      ? 'Nothing matched — try another title, or clear the filter.'
      : 'Nothing here right now — try a search.'));
    pager.hidden = !data.hasNext && mState.page <= 1;
    $('#movies-prev').disabled = mState.page <= 1;
    $('#movies-next').disabled = !data.hasNext;
    $('#movies-pageinfo').textContent = `page ${mState.page}`;
  } catch (e) {
    grid.innerHTML = '';
    $('#movies-count').textContent = '';
    pager.hidden = true;
    // Both backends answer these routes (Node in the server, Java in the app
    // shell); a failure here is the network or the upstream, not the place.
    showsNote(`Could not load the catalog (${e.message}). `
      + 'The catalog comes from f-movies — check the connection and try again.');
  }
}

function searchShows(q) {
  mState.q = (q ?? '').trim();
  loadShows(1);
}

/* ----------------------------------------------------------------- detail */

const dState = {
  ref: null, type: null, slug: null, details: null,
  season: 1, ep: 1, episodes: [], sources: [],
};
/** Bumped on every open: a slow answer that lands after the reader moved on
 *  must not repaint a page it does not belong to. */
let openSeq = 0;

/* ---------------------------------------------------------------- library */

// The device's own library for this area — favorites and everything opened —
// under its own key, so the 18+ shelf's records never see a show and these
// rows never see a video. Entries carry enough to draw a card offline:
// ref, type, slug, title, cover, year.
const SHOWS_KEY = 'htv:shows:v1';
const SHOWS_MAX = 60;

function showsLibRead() {
  const empty = { favorites: [], history: [] };
  try {
    const raw = localStorage.getItem(SHOWS_KEY);
    const data = raw ? JSON.parse(raw) : null;
    if (!data || typeof data !== 'object') return empty;
    const clean = (list) => (Array.isArray(list) ? list.filter((e) => e && e.ref) : []);
    return { favorites: clean(data.favorites), history: clean(data.history) };
  } catch (e) {
    return empty;
  }
}
let showsLib = showsLibRead();
function showsLibWrite() {
  try { localStorage.setItem(SHOWS_KEY, JSON.stringify(showsLib)); } catch (e) { /* unwritable */ }
}

/** The title on screen, as one library row. */
function showEntry() {
  return {
    ref: dState.ref,
    type: dState.type,
    slug: dState.slug,
    title: dState.details?.title ?? dState.slug,
    cover: dState.details?.poster ?? '',
    year: dState.details?.year ?? null,
    at: Date.now(),
  };
}

const isShowFav = (ref) => !!ref && showsLib.favorites.some((e) => e.ref === ref);

function showFavPill() {
  const on = isShowFav(dState.ref);
  return `<button class="pill pill-ghost" type="button" data-show-fav aria-pressed="${on}">
      <svg viewBox="0 0 24 24" stroke-linejoin="round"><path d="M12 20.4 4.9 14.1A5.3 5.3 0 0 1 12 6.3a5.3 5.3 0 0 1 7.1 7.8Z"/></svg>
      <span class="fav-label">${on ? 'In library' : 'Favorite'}</span>
    </button>`;
}

function toggleShowFav() {
  if (!dState.ref || !dState.details) return;
  const i = showsLib.favorites.findIndex((e) => e.ref === dState.ref);
  if (i >= 0) showsLib.favorites.splice(i, 1);
  else showsLib.favorites.unshift(showEntry());
  showsLibWrite();
  renderShowsLib();
  // Keeps the heart already on screen telling the truth, wherever it is.
  const pill = document.querySelector('[data-show-fav]');
  if (pill) {
    const on = isShowFav(dState.ref);
    pill.setAttribute('aria-pressed', String(on));
    const word = pill.querySelector('.fav-label');
    if (word) word.textContent = on ? 'In library' : 'Favorite';
  }
}

/** Opening a title is the moment its library row is written: one per title,
 *  newest first — a shortlist, not a diary of taps. */
function noteShowOpen() {
  if (!dState.ref || !dState.details) return;
  const entry = showEntry();
  showsLib.history = [entry, ...showsLib.history.filter((e) => e.ref !== entry.ref)]
    .slice(0, SHOWS_MAX);
  showsLibWrite();
}

const libCard = (e) => showCardHtml({
  type: e.type, slug: e.slug, title: e.title, year: e.year, score: null, poster: e.cover,
});

function renderShowsLib() {
  const favs = showsLib.favorites;
  const favGrid = $('#show-fav-grid');
  if (favGrid) {
    favGrid.innerHTML = favs.map(libCard).join('');
    $('#show-fav-count').textContent = String(favs.length);
    const note = $('#show-fav-note');
    if (note) {
      note.hidden = favs.length > 0;
      note.textContent = favs.length ? ''
        : 'Titles you favorite stay here on this device — no account, no server.';
    }
  }
  const hist = showsLib.history;
  const histGrid = $('#show-hist-grid');
  if (histGrid) {
    histGrid.innerHTML = hist.map(libCard).join('');
    $('#show-hist-count').textContent = String(hist.length);
    const clear = $('#show-hist-clear');
    if (clear) clear.hidden = hist.length === 0;
    const note = $('#show-hist-note');
    if (note) {
      note.hidden = hist.length > 0;
      note.textContent = hist.length ? ''
        : 'Every title you open is remembered here, newest first.';
    }
  }
}

/** The back stack stores `s3e5`; anything else starts at the top. */
function parseChapter(chapter) {
  const m = /^s(\d+)e(\d+)$/.exec(String(chapter ?? ''));
  return m ? { season: Number(m[1]), ep: Number(m[2]) } : { season: 1, ep: 1 };
}

function epListHtml() {
  return dState.episodes.map((ep) => `
    <button class="ep-card${ep.n === dState.ep ? ' active' : ''}" type="button" data-show-ep="${ep.n}">
      <span class="ep-text">
        <span class="ep-title">E${ep.n}${ep.title ? ' · ' + esc(ep.title) : ''}</span>
      </span>
    </button>`).join('');
}

function paintShowSide() {
  // Only a series has an episode to name: a film would otherwise carry phrases
  // like "Episode 1" and "S1 · E1" about nothing, on a page whose whole point
  // is that it is one sitting long.
  const isTv = dState.type === 'tv';
  const ep = dState.episodes.find((e) => e.n === dState.ep);
  const chip = $('#side-chip');
  if (chip) chip.textContent = isTv ? `S${dState.season} · E${dState.ep}` : '';
  const title = $('#side-ep-title');
  if (title) title.textContent = isTv ? (ep?.title || `Episode ${dState.ep}`) : '';
  const list = $('#ep-list');
  if (list) list.innerHTML = epListHtml();
  const line = $('#hero-ep-line');
  if (line) {
    line.textContent = isTv ? `Episode ${dState.ep}${ep?.title ? ' · ' + ep.title : ''}` : '';
    line.hidden = !isTv;
  }
  const prev = $('#ep-prev');
  if (prev) prev.disabled = dState.ep <= 1;
  const next = $('#ep-next');
  if (next) next.disabled = dState.ep >= dState.episodes.length;
}

async function loadShowPlayer(seq, ep) {
  const slot = $('#fx-slot');
  if (!slot || seq !== openSeq) return;
  dState.ep = ep;
  paintShowSide();
  try {
    const params = new URLSearchParams({ season: String(dState.season), episode: String(ep) });
    const res = await fetch(`/api/fmovies/${dState.type}/${encodeURIComponent(dState.slug)}/player?${params}`);
    if (!res.ok) throw new Error(`status ${res.status}`);
    const sources = (await res.json()).sources ?? [];
    if (!sources.length) throw new Error('no source');
    if (seq !== openSeq) return;
    dState.sources = sources;
    // The doors are the site's own, and the server has already knocked on each
    // one. A door that did not answer is the normal state of this world — the
    // three hosts behind these names rotate, and two of them were dead the day
    // this was written — so the first that *did* answer is what opens, and the
    // rest are offered as buttons that say why they are not pressable. The
    // alternative is what the reader saw: a black frame with nothing in it.
    const live = sources.filter((s) => s.ok !== false);
    const first = live[0] ?? sources[0];
    // The frame keeps the anime area's id on purpose: the sheet's close
    // already knows how to stop an embed playing behind the view the reader
    // came back to, and one sheet holds one player at a time.
    slot.innerHTML = `${live.length ? ''
      : '<p class="note">None of the site&rsquo;s own players answer right now.</p>'}
      <div class="lx-frame"><iframe id="lx-frame" allow="autoplay; fullscreen; picture-in-picture; encrypted-media"
        allowfullscreen referrerpolicy="origin" title="Player"></iframe></div>
      <div class="quality">${sources.map((s, i) =>
        `<button class="q-btn" type="button" data-fx-src="${i}" aria-pressed="${s === first}"${
          s.ok === false ? ` disabled title="${esc(`${s.label} is not answering on f-movies right now${s.note ? ` (${s.note})` : ''}`)}"` : ''
        }>${esc(s.label)}</button>`).join('')}</div>`;
    document.getElementById('lx-frame').src = first.url;
    // A player resolving for this episode is an episode being watched, so the
    // rail at the top of this area is told — once per title, never backwards.
    // A movie has no episode to resume, so it travels with total 0 (no bar).
    noteOpen('show', {
      id: dState.ref,
      title: dState.details?.title,
      cover: dState.details?.poster,
      number: dState.type === 'tv' ? ep : 1,
      total: dState.type === 'tv' ? dState.episodes.length : 0,
      ref: dState.type === 'tv' ? `s${dState.season}e${ep}` : null,
    });
    renderActivity('show');
  } catch (e) {
    if (seq !== openSeq) return;
    slot.innerHTML = `<p class="note">Could not resolve a player — ${esc(e.message)}</p>`;
  }
}

/** One season's episode rows, then a player for its first one. */
async function loadSeason(seq, season) {
  dState.season = season;
  dState.ep = 1;
  dState.episodes = [];
  paintShowSide();                       // the list empties while it loads
  try {
    const res = await fetch(`/api/fmovies/tv/${encodeURIComponent(dState.slug)}/episodes?season=${season}`);
    if (!res.ok) throw new Error(`status ${res.status}`);
    dState.episodes = (await res.json()).data ?? [];
  } catch (e) {
    if (seq !== openSeq) return;
    const list = $('#ep-list');
    if (list) list.innerHTML = `<p class="note">Could not load season ${season} — ${esc(e.message)}</p>`;
    return;
  }
  if (seq !== openSeq) return;
  $$('#season-rail .season-chip').forEach((c) => {
    c.setAttribute('aria-pressed', String(Number(c.dataset.showSeason) === season));
  });
  paintShowSide();
  loadShowPlayer(seq, 1);
}

async function openShow(ref, chapter = null) {
  const [type, slug] = String(ref ?? '').split('/');
  if (!type || !slug) return;
  const seq = ++openSeq;
  dState.ref = `${type}/${slug}`;
  dState.type = type;
  dState.slug = slug;
  dState.details = null;
  dState.episodes = [];
  const want = parseChapter(chapter);
  dState.season = want.season;
  dState.ep = want.ep;
  openSheet('show', dState.ref, chapter ?? null);

  let details;
  try {
    const res = await fetch(`/api/fmovies/${type}/${encodeURIComponent(slug)}`);
    if (!res.ok) throw new Error(`status ${res.status}`);
    details = (await res.json()).details;
  } catch (err) {
    if (seq !== openSeq) return;
    $('#sheet-body').innerHTML = `<p class="note">Could not load this title — ${esc(err.message)}</p>`;
    return;
  }
  if (seq !== openSeq) return;
  dState.details = details;

  const isTv = type === 'tv';
  const seasons = isTv ? (details.seasons ?? []) : [];
  // A chapter from the back stack can name a season this title does not
  // have — the strip's first entry is the answer, not a dead player.
  if (seasons.length && !seasons.includes(dState.season)) dState.season = seasons[0];

  if (isTv) {
    try {
      const res = await fetch(`/api/fmovies/tv/${encodeURIComponent(slug)}/episodes?season=${dState.season}`);
      if (!res.ok) throw new Error(`status ${res.status}`);
      dState.episodes = (await res.json()).data ?? [];
    } catch (err) {
      if (seq !== openSeq) return;
      $('#sheet-body').innerHTML = `<p class="note">Could not load the episodes — ${esc(err.message)}</p>`;
      return;
    }
    if (seq !== openSeq) return;
    if (dState.episodes.length && !dState.episodes.some((e) => e.n === dState.ep)) dState.ep = 1;
  } else {
    dState.ep = 1;
  }

  const genres = (details.genres ?? []).map((g) => `<span class="hero-tag">${esc(g)}</span>`).join('');
  const seasonCount = seasons.length || details.numberOfSeasons || null;
  const kicker = [kindLabel(type), details.year,
    isTv && seasonCount ? `${seasonCount} seasons` : null,
    details.score != null ? `★ ${Number(details.score).toFixed(1)}` : null]
    .filter(Boolean).map(esc).join(' · ');
  const cast = (details.cast ?? []).slice(0, 8).join(', ');

  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = isTv ? `${dState.episodes.length} episodes` : 'Movie';

  $('#sheet-body').innerHTML = `
    <div class="detail-hero">
      <div class="detail-poster"><img src="${esc(details.poster ?? '')}" alt="" loading="lazy"></div>
      <div class="detail-hero-text">
        <div class="detail-kicker">${kicker}</div>
        <h2 class="detail-title">${esc(details.title)}</h2>
        <div class="detail-meta" id="hero-ep-line"></div>
        <div class="detail-actions">${showFavPill()}</div>
      </div>
    </div>
    <div class="detail-grid">
      <div class="detail-main">
        <div class="player-card">
          <div id="fx-slot"><div class="center-spin"><div class="spinner"></div></div></div>
        </div>
        ${isTv ? `<div class="ep-nav">
          <button class="page-btn" id="ep-prev" type="button">← Previous</button>
          <button class="page-btn" id="ep-next" type="button">Next →</button>
        </div>
        <!-- The seasons arrive with the details, so this strip draws at once
             and the episodes follow it. A chain of one is not a strip. -->
        <div class="seasons" id="seasons" ${seasons.length < 2 ? 'hidden' : ''}>
          <div class="detail-label">Seasons</div>
          <div class="season-rail" id="season-rail">${seasons.map((n) => `
            <button class="chip season-chip" type="button" data-show-season="${n}"
              aria-pressed="${n === dState.season}" title="Season ${n}"
              aria-label="${esc(`Season ${n} of ${details.title}`)}">S${n}</button>`).join('')}
          </div>
        </div>` : ''}
      </div>
      <aside class="detail-side">
        ${isTv ? `<div class="side-row"><span class="side-chip" id="side-chip"></span></div>
        <h3 class="side-title" id="side-ep-title"></h3>` : ''}
        <div class="detail-label">Description</div>
        <p class="detail-desc" id="side-ep-desc">${esc(details.description ?? '')}</p>
        <div class="detail-tags">${genres}</div>
        ${cast ? `<div class="detail-label">Cast</div><p class="detail-desc">${esc(cast)}</p>` : ''}
        ${isTv ? `<div class="detail-label">Episodes</div><div class="ep-list" id="ep-list"></div>` : ''}
      </aside>
    </div>`;

  // Opening a title is the moment its library rows are written, so the grids
  // below the shelf are current before the reader can scroll to them.
  noteShowOpen();
  renderShowsLib();

  if (isTv) {
    $('#ep-prev').onclick = () => { if (dState.ep > 1) loadShowPlayer(seq, dState.ep - 1); };
    $('#ep-next').onclick = () => { if (dState.ep < dState.episodes.length) loadShowPlayer(seq, dState.ep + 1); };
    paintShowSide();
  }

  await loadShowPlayer(seq, dState.ep);
}

/* ----------------------------------------------------------------- wiring */

function bindMoviesView() {
  $('#movies-q')?.addEventListener('input', (e) => {
    const val = e.target.value;
    $('#movies-search-shell').dataset.filled = String(!!val);
    clearTimeout(mDebounce);
    mDebounce = setTimeout(() => searchShows(val), 240);
  });
  $('#movies-clear')?.addEventListener('click', () => {
    $('#movies-q').value = '';
    $('#movies-search-shell').dataset.filled = 'false';
    searchShows('');
    $('#movies-q').focus({ preventScroll: true });
  });

  // The kind select changes the catalog server-side, and the clear button
  // only exists while something is set.
  const syncFilters = () => {
    $('#movies-filter-clear').hidden = !mState.type;
  };
  $('#movies-type')?.addEventListener('change', (e) => {
    mState.type = e.target.value;
    syncFilters();
    loadShows(1);
  });
  $('#movies-filter-clear')?.addEventListener('click', () => {
    mState.type = '';
    const sel = $('#movies-type');
    if (sel) sel.value = '';
    syncFilters();
    loadShows(1);
  });

  $('#movies-prev')?.addEventListener('click', () => {
    if (mState.page > 1) loadShows(mState.page - 1).then(() => scrollTo({ top: 0, behavior: 'smooth' }));
  });
  $('#movies-next')?.addEventListener('click', () => {
    if (mState.hasNext) loadShows(mState.page + 1).then(() => scrollTo({ top: 0, behavior: 'smooth' }));
  });
}
bindMoviesView();

// Cards in the grid and everything on the detail page that acts on one.
document.addEventListener('click', (e) => {
  // The heart files the title on this device — the row it writes is drawn
  // by the same card the grids below use.
  const fav = e.target.closest('[data-show-fav]');
  if (fav) { e.preventDefault(); toggleShowFav(); return; }

  const wipe = e.target.closest('#show-hist-clear');
  if (wipe) {
    e.preventDefault();
    showsLib.history = [];
    showsLibWrite();
    renderShowsLib();
    return;
  }

  // A season is another list of the same title: switching it repaints the
  // episode column and starts its first episode.
  const season = e.target.closest('[data-show-season]');
  if (season && dState.ref) {
    e.preventDefault();
    loadSeason(openSeq, Number(season.dataset.showSeason));
    return;
  }
  const card = e.target.closest('[data-show]');
  if (card) { e.preventDefault(); openShow(card.dataset.show); return; }
  const ep = e.target.closest('[data-show-ep]');
  if (ep && dState.ref) { e.preventDefault(); loadShowPlayer(openSeq, Number(ep.dataset.showEp)); return; }
  // The three servers are three doors to the same episode; picking one swaps
  // the frame without touching the page around it.
  const srv = e.target.closest('[data-fx-src]');
  if (srv && dState.sources.length) {
    const frame = document.getElementById('lx-frame');
    const src = dState.sources[Number(srv.dataset.fxSrc)];
    if (frame && src) frame.src = src.url;
    $$('[data-fx-src]').forEach((b) => b.setAttribute('aria-pressed', String(b === srv)));
  }
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('[data-show]');
  if (card) { e.preventDefault(); openShow(card.dataset.show); }
});

// First landing on the view loads the shelf; revisits keep what was searched.
// One flag keeps the view click and a back-restore from double-loading.
let showsLoaded = false;
function ensureShows() {
  if (showsLoaded) return;
  showsLoaded = true;
  renderShowsLib();   // the device's own rows paint without a request
  loadShows(1);
}
document.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go && go.dataset.go === 'movies') ensureShows();
});

export { loadShows, searchShows, openShow, ensureShows };
