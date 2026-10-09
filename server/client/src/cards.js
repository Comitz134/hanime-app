// cards.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, ago, esc, fmtCount } from './core.js';
import { isFav, remember } from './library.js';
import { progressOf } from './positions.js';
import { shellCall } from './shell.js';
import { views } from './views.js';

/* ------------------------------------------------------------------ cards */

/* Library cards are drawn from the copy the app stored, so the list still has
   faces with no network. A title that was never kept has no copy and uses its
   URL, exactly as before. */
function coverFor(slug, remote, opts) {
  if (!opts || !opts.local) return remote;
  const stored = shellCall('cachedCover', slug);
  return stored ? stored : remote;
}

function cardHtml(v, opts) {
  // Every card is also a place to file the title: the heart is drawn from the
  // same records the library view renders, so the two can never disagree.
  remember(v);
  const name = v.name ?? v.title ?? v.slug;
  const cover = coverFor(v.slug, v.cover ?? v.cover_url ?? '', opts);
  const owned = isFav(v.slug);
  const heart = owned ? 'Remove from favorites' : 'Add to favorites';
  const views = Number(v.views);
  // opts is an object here and the array index when this is used as a map
  // callback, so every read off it is checked for the shape it expects.
  const pct = opts && opts.progress ? progressOf(v.slug) : 0;
  // On the device's own shelves the record carries when it happened, and that
  // is the one fact a reader scanning History is looking for.
  const when = opts && opts.local && Number(v.at) ? ago(v.at) : '';
  const metaLine = [
    v.brand ?? '',
    v.released_at ? v.released_at.slice(0, 4) : '',
    when,
  ].filter(Boolean).join(' · ');

  return `<article class="card" data-slug="${esc(v.slug)}" tabindex="0" role="button" aria-label="${esc(name)}">
    <div class="card-frame">
      <img loading="lazy" decoding="async" src="${esc(cover)}" alt="">
      <div class="card-fallback" hidden>${esc(name)}</div>
      <div class="card-scrim"></div>
      ${Number.isFinite(views) ? `<span class="card-badge">${fmtCount(views)}</span>` : ''}
      ${pct ? `<div class="card-progress"><span style="width:${pct}%"></span></div>` : ''}
      <button class="fav-btn" type="button" data-fav="${esc(v.slug)}" aria-pressed="${owned}" aria-label="${heart}" title="${heart}">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M12 20.4 4.9 14.1A5.3 5.3 0 0 1 12 6.3a5.3 5.3 0 0 1 7.1 7.8Z"/></svg>
      </button>
      <div class="card-play"><span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
      </span></div>
    </div>
    <div class="card-body">
      <h3 class="card-title">${esc(name)}</h3>
      <p class="card-meta">${esc(metaLine)}</p>
    </div>
  </article>`;
}

export { coverFor, cardHtml };
