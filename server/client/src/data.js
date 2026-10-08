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

// The whole histogram, and the word the cloud is narrowed by. Selection lives
// in state.tags as it always did; the cloud is a view over both.
let tagList = [];
let tagQuery = '';

async function loadTags() {
  const { data } = await api('/api/tags');
  // The histogram arrives sorted by count. The old picker kept a top thirty
  // of tags with real coverage; the cloud can carry the long tail now that
  // there is a box to type it into, so the bar drops and the cap only stops
  // a pathological catalog.
  tagList = (data ?? []).filter((t) => t.count >= 5).slice(0, 200);
  renderTags();
}

function setTagQuery(val) {
  tagQuery = (val ?? '').trim().toLowerCase();
  renderTags();
}

function renderTags() {
  const rail = $('#tag-rail');
  if (!rail) return;
  const on = (name) => state.tags.includes(name);
  // A selected tag always stays visible: hiding the reader's own choices
  // behind the filter box would make them impossible to take back off.
  const shown = tagList.filter((t) => !tagQuery || t.name.toLowerCase().includes(tagQuery) || on(t.name));
  rail.innerHTML = shown
    .map((t) => `<button class="chip" data-tag="${esc(t.name)}" aria-pressed="${on(t.name)}">${esc(t.name)} <span class="chip-n">${t.count}</span></button>`)
    .join('');

  $('#clear-tags').hidden = state.tags.length === 0;

  const count = $('#tag-count');
  if (count) {
    const bits = [`${state.tags.length} selected`, `${tagList.length} genres`];
    if (tagQuery) bits.push(`${shown.length} shown`);
    count.textContent = bits.join(' · ');
    count.hidden = false;
  }
  const note = $('#tag-note');
  if (note) {
    note.hidden = shown.length > 0;
    note.textContent = shown.length ? '' : 'No genre matches that — try a shorter word.';
  }
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

export { loadTags, loadBrands, loadGrid, loadFeatured, renderTags, setTagQuery };
