// data.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { cardHtml } from './cards.js';
import { $, api, esc, state } from './core.js';
import { mountHero } from './hero.js';
import { views } from './views.js';

/* ------------------------------------------------------------------ data */

async function loadTags() {
  const { data } = await api('/api/tags');
  // only tags with real coverage make useful filters
  const top = data.filter((t) => t.count >= 25).slice(0, 30);
  $('#tag-rail').innerHTML = top
    .map((t) => `<button class="chip" data-tag="${esc(t.name)}" aria-pressed="false">${esc(t.name)} <span class="chip-n">${t.count}</span></button>`)
    .join('');
}

async function loadBrands() {
  const { data } = await api('/api/brands');
  $('#brand-rail').innerHTML = data.slice(0, 30)
    .map((b) => `<button class="chip" data-brand="${esc(b.name)}">${esc(b.name)} <span class="chip-n">${b.count}</span></button>`)
    .join('');
}

async function loadGrid() {
  const grid = $('#grid');
  const [order_by, ordering] = state.sort.split(':');
  const params = new URLSearchParams({ page: state.page, per_page: state.perPage, order_by, ordering });
  if (state.q) params.set('q', state.q);
  if (state.tags.length) params.set('tags', state.tags.join(','));
  if (state.brand) params.set('brand', state.brand);

  if (!grid.children.length) {
    grid.innerHTML = Array.from({ length: 18 }, () => '<div class="skeleton" style="aspect-ratio:2/3"></div>').join('');
  }

  try {
    const body = await api('/api/videos?' + params);
    state.total = body.total;
    state.pages = Math.max(1, body.pages);
    $('#total-count').textContent = `${body.total}`;
    $('#footer-stat').textContent = `${body.total} titles indexed · catalog cached locally.`;

    grid.innerHTML = body.data.map(cardHtml).join('') ||
      `<p class="note">Nothing matches that yet.</p>`;

    const label = $('#active-label');
    const bits = [...state.tags, state.brand && `studio: ${state.brand}`, state.q && `“${state.q}”`].filter(Boolean);
    label.hidden = !bits.length;
    label.textContent = bits.join(' · ');

    $('#pager').hidden = state.pages <= 1;
    $('#pageinfo').textContent = `page ${state.page + 1} / ${state.pages}`;
    $('#prev').disabled = state.page === 0;
    $('#next').disabled = state.page + 1 >= state.pages;
  } catch (e) {
    grid.innerHTML = `<p class="note">Load failed — ${esc(e.message)}</p>`;
  }
}

async function loadFeatured() {
  const body = await api('/api/videos?per_page=2&order_by=views&ordering=desc');
  state.featured = body.data;
  if (state.featured.length) mountHero();
}

export { loadTags, loadBrands, loadGrid, loadFeatured };
