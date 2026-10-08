// anime.js — the normal (non-adult) anime area.
//
// Catalog comes from AniList through this server's /api/anime routes, episode
// lists and the embed player come from LunarX behind the same routes — the
// browser never talks to either upstream directly, because LunarX answers
// only its own Origin.
//
// Opening a title renders the same detail page the 18+ area uses (one
// surface, one back contract): header, player, sidebar with the episode
// list, recommended below. The player here is the same embed LunarX itself
// plays, so what works there works here.

import { $, esc } from './core.js';
import { openSheet } from './sheet.js';

/* ------------------------------------------------------------------ grid */

const aState = { q: '', page: 1, hasNext: false };
let aDebounce;

function animeCardHtml(m) {
  const meta = [m.year, m.eps ? `${m.eps} eps` : null, m.format]
    .filter(Boolean).map(esc).join(' · ');
  return `<article class="card" data-anime="${Number(m.id)}" tabindex="0" role="button" aria-label="${esc(m.title)}">
    <div class="card-frame">
      <img loading="lazy" decoding="async" src="${esc(m.cover ?? '')}" alt="">
      <div class="card-fallback" hidden>${esc(m.title)}</div>
      <div class="card-scrim"></div>
      ${m.score ? `<span class="card-badge">${esc(String(m.score))}</span>` : ''}
      <div class="card-play"><span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
      </span></div>
    </div>
    <div class="card-body">
      <h3 class="card-title">${esc(m.title)}</h3>
      <p class="card-meta">${meta}</p>
    </div>
  </article>`;
}

function animeNote(msg) {
  const note = $('#anime-note');
  if (!note) return;
  note.hidden = !msg;
  note.textContent = msg || '';
}

async function loadAnime(page) {
  const grid = $('#anime-grid');
  const pager = $('#anime-pager');
  if (page > 1) grid.innerHTML = '<div class="center-spin"><div class="spinner"></div></div>';
  try {
    const res = await fetch(`/api/anime/search?q=${encodeURIComponent(aState.q)}&page=${page}`);
    if (!res.ok) throw new Error(`status ${res.status}`);
    const data = await res.json();
    aState.page = data.page;
    aState.hasNext = !!data.hasNext;
    grid.innerHTML = (data.items ?? []).map(animeCardHtml).join('');
    $('#anime-count').textContent = `${data.items?.length ?? 0}${aState.q ? ' found' : ' trending now'}`;
    animeNote((data.items ?? []).length ? '' : 'Nothing matched — try another title.');
    pager.hidden = !data.hasNext && aState.page <= 1;
    $('#anime-prev').disabled = aState.page <= 1;
    $('#anime-next').disabled = !aState.hasNext;
    $('#anime-pageinfo').textContent = `page ${aState.page}`;
  } catch (e) {
    grid.innerHTML = '';
    $('#anime-count').textContent = '';
    pager.hidden = true;
    // Both backends answer these routes (Node in the server, Java in the app
    // shell); a failure here is the network or the upstream, not the place.
    animeNote(`Could not load the anime catalog (${e.message}). `
      + 'The catalog comes from AniList — check the connection and try again.');
  }
}

function searchAnime(q) {
  aState.q = (q ?? '').trim();
  loadAnime(1);
}

/* ------------------------------------------------------------------ detail */

const dState = { id: null, ep: 1, details: null, episodes: [] };

function epListHtml() {
  return dState.episodes.map((ep) => `
    <button class="ep-card${Number(ep.number) === dState.ep ? ' active' : ''}" type="button"
            data-ep="${Number(ep.number)}">
      <span class="ep-thumb">${ep.img ? `<img src="${esc(ep.img)}" alt="" loading="lazy">` : ''}
        <span class="ep-num">EP ${Number(ep.number)}</span></span>
      <span class="ep-text">
        <span class="ep-title">Episode ${Number(ep.number)}</span>
        <span class="ep-desc">${esc(ep.title || '')}</span>
      </span>
    </button>`).join('');
}

function paintEpisodeSide() {
  const ep = dState.episodes.find((e) => Number(e.number) === dState.ep);
  const chip = $('#side-chip');
  if (chip) chip.textContent = `S1 · E${dState.ep}${ep?.hasDub ? ' · Sub/Dub' : ' · Sub'}`;
  const title = $('#side-ep-title');
  if (title) title.textContent = ep?.title || `Episode ${dState.ep}`;
  const desc = $('#side-ep-desc');
  if (desc) desc.textContent = ep?.description || '';
  const list = $('#ep-list');
  if (list) list.innerHTML = epListHtml();
  const line = $('#hero-ep-line');
  if (line) line.textContent = `Episode ${dState.ep}${ep ? ' · ' + ep.title : ''}`;
  const prev = $('#ep-prev');
  if (prev) prev.disabled = dState.ep <= 1;
  const next = $('#ep-next');
  if (next) next.disabled = dState.ep >= (dState.details?.eps ?? dState.episodes.length);
}

async function loadPlayer(ep) {
  const slot = $('#lx-slot');
  if (!slot) return;
  dState.ep = ep;
  paintEpisodeSide();
  try {
    const res = await fetch(`/api/anime/${dState.id}/player?ep=${ep}`);
    if (!res.ok) throw new Error(`status ${res.status}`);
    const data = await res.json();
    const src = data.sources?.[0]?.url;
    if (!src) throw new Error('no source');
    let frame = document.getElementById('lx-frame');
    if (!frame) {
      slot.innerHTML = `<div class="lx-frame"><iframe id="lx-frame" allow="autoplay; fullscreen; picture-in-picture; encrypted-media"
          allowfullscreen referrerpolicy="origin" title="Player"></iframe></div>`;
      frame = document.getElementById('lx-frame');
    }
    frame.src = src;
  } catch (e) {
    slot.innerHTML = `<p class="note">Could not resolve a player for episode ${ep} — ${esc(e.message)}</p>`;
  }
}

async function openAnime(id, ep = 1) {
  dState.id = Number(id);
  dState.ep = Math.max(1, Number(ep) | 0);
  openSheet();

  let details = null;
  let episodes = [];
  try {
    const [d, e] = await Promise.all([
      fetch(`/api/anime/${dState.id}`).then((r) => { if (!r.ok) throw new Error(`status ${r.status}`); return r.json(); }),
      fetch(`/api/anime/${dState.id}/episodes`).then((r) => { if (!r.ok) throw new Error(`status ${r.status}`); return r.json(); }),
    ]);
    details = d.details;
    episodes = e.data ?? [];
  } catch (err) {
    $('#sheet-body').innerHTML = `<p class=\"note\">Could not load this title — ${esc(err.message)}</p>`;
    return;
  }
  dState.details = details;
  dState.episodes = episodes;
  if (episodes.length && !episodes.some((e) => Number(e.number) === dState.ep)) dState.ep = 1;

  const genres = (details.genres ?? []).map((g) => `<span class="hero-tag">${esc(g)}</span>`).join('');
  const kicker = [details.format, details.year, details.eps ? `${details.eps} eps` : null,
    details.score ? `★ ${(details.score / 10).toFixed(1)}` : null].filter(Boolean).map(esc).join(' · ');

  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = dState.episodes.length
    ? `${dState.episodes.length} episodes` : (details.status ?? '');

  $('#sheet-body').innerHTML = `
    <div class="detail-hero">
      <div class="detail-poster"><img src="${esc(details.cover ?? '')}" alt="" loading="lazy"></div>
      <div class="detail-hero-text">
        <div class="detail-kicker">${kicker}</div>
        <h2 class="detail-title">${esc(details.title)}</h2>
        <div class="detail-meta" id="hero-ep-line"></div>
      </div>
    </div>
    <div class="detail-grid">
      <div class="detail-main">
        <div class="player-card">
          <div id="lx-slot"><div class="center-spin"><div class="spinner"></div></div></div>
        </div>
        <div class="ep-nav">
          <button class="page-btn" id="ep-prev" type="button">← Previous</button>
          <button class="page-btn" id="ep-next" type="button">Next →</button>
        </div>
        <section id="detail-recs" hidden>
          <div class="sec-head"><div class="sec-head-l"><h2>Recommended</h2></div></div>
          <div class="rail" id="rec-rail" aria-label="Recommended"></div>
        </section>
      </div>
      <aside class="detail-side">
        <div class="side-row"><span class="side-chip" id="side-chip"></span></div>
        <h3 class="side-title" id="side-ep-title"></h3>
        <div class="detail-label">Description</div>
        <p class="detail-desc" id="side-ep-desc"></p>
        <div class="detail-tags">${genres}</div>
        <div class="detail-label">Episodes</div>
        <div class="ep-list" id="ep-list"></div>
      </aside>
    </div>`;

  const recs = details.recommendations ?? [];
  if (recs.length) {
    $('#rec-rail').innerHTML = recs.map(animeCardHtml).join('');
    $('#detail-recs').hidden = false;
  }

  $('#ep-prev').onclick = () => { if (dState.ep > 1) loadPlayer(dState.ep - 1); };
  $('#ep-next').onclick = () => loadPlayer(dState.ep + 1);

  await loadPlayer(dState.ep);
}

/* ------------------------------------------------------------------ wiring */

function bindAnimeView() {
  $('#anime-q')?.addEventListener('input', (e) => {
    const val = e.target.value;
    $('#anime-search-shell').dataset.filled = String(!!val);
    clearTimeout(aDebounce);
    aDebounce = setTimeout(() => searchAnime(val), 240);
  });
  $('#anime-clear')?.addEventListener('click', () => {
    $('#anime-q').value = '';
    $('#anime-search-shell').dataset.filled = 'false';
    searchAnime('');
    $('#anime-q').focus({ preventScroll: true });
  });
  $('#anime-prev')?.addEventListener('click', () => {
    if (aState.page > 1) loadAnime(aState.page - 1).then(() => scrollTo({ top: 0, behavior: 'smooth' }));
  });
  $('#anime-next')?.addEventListener('click', () => {
    if (aState.hasNext) loadAnime(aState.page + 1).then(() => scrollTo({ top: 0, behavior: 'smooth' }));
  });
}
bindAnimeView();

// Cards in the anime grid, the recommended rail, and anywhere else they land.
document.addEventListener('click', (e) => {
  const card = e.target.closest('[data-anime]');
  if (card) { e.preventDefault(); openAnime(card.dataset.anime); return; }
  const ep = e.target.closest('[data-ep]');
  if (ep && dState.id) { e.preventDefault(); loadPlayer(Number(ep.dataset.ep)); }
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('[data-anime]');
  if (card) { e.preventDefault(); openAnime(card.dataset.anime); }
});

// First landing on the view loads the shelf; revisits keep what was searched.
let animeLoaded = false;
document.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go && go.dataset.go === 'anime' && !animeLoaded) { animeLoaded = true; loadAnime(1); }
});

export { loadAnime, searchAnime, openAnime };
