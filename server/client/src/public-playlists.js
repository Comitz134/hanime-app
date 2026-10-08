// public-playlists.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { cardHtml } from './cards.js';
import { $, api, esc, fmtCount } from './core.js';
import { closeSheet, openSheet } from './sheet.js';
import { showView, views } from './views.js';

/* ----------------------------------------------------------- public playlists */
//
// hanime.tv has no playlist index and no public playlist API — guests get 403
// on every playlist path. The only way to read them is the server-rendered
// page, and the only way to find them is the "Related Playlists" rail on a
// video page plus each owner's channel. The proxy crawls that graph and serves
// it here. Wording matters: this section is the crawl, not the site's own
// (nonexistent) index.

let ppIndex = [];
let ppQuery = '';

function ppMatchBadge(p) {
  if (p.match_kind === 'title') return '<span class="pl-flag">title</span>';
  if (p.match_kind === 'content') {
    // "entries" means an entry title matched; "studio or tag" means the
    // playlist is mostly that thing — different results, labelled differently.
    const what = p.matched_on === 'studio_or_tag' ? 'studio or tag' : `${p.match_count} inside`;
    return `<span class="pl-flag">${esc(what)}</span>`;
  }
  return '';
}

function ppCard(p) {
  const cover = p.cover_url ? `<img loading="lazy" src="${esc(p.cover_url)}" alt="">` : '<span class="pl-art-empty">no cover</span>';
  const owner = p.owner_name
    ? `<span class="pp-owner">
         ${p.owner_avatar_url ? `<img loading="lazy" src="${esc(p.owner_avatar_url)}" alt="">` : ''}
         <span class="pp-owner-name">${esc(p.owner_name)}</span>
       </span>`
    : '';
  return `<article class="pl-card pp-card" data-pp="${esc(p.slug)}" tabindex="0" role="button" aria-label="${esc(p.title)}">
    <div class="pl-art" data-count="1">${cover}${p.truncated ? '<span class="pl-more">partial</span>' : ''}</div>
    <h3 class="pl-name">${esc(p.title)}</h3>
    ${owner}
    <p class="pl-meta">${p.item_count} ${p.item_count === 1 ? 'title' : 'titles'}${
      p.fetched ? '' : ' · entries not pulled yet'
    }${ppMatchBadge(p) ? `<span class="pl-flags">${ppMatchBadge(p)}</span>` : ''}</p>
  </article>`;
}

async function loadPublicPlaylists(q = ppQuery) {
  const rail = $('#pp-rail');
  if (!rail) return;
  ppQuery = q;
  rail.innerHTML = Array.from({ length: 5 }, () => '<div class="pl-card"><div class="skeleton" style="aspect-ratio:1"></div></div>').join('');

  const note = $('#pp-note');
  try {
    const params = new URLSearchParams({ limit: '60' });
    if (q) params.set('q', q);
    const body = await api('/api/public/playlists?' + params);
    ppIndex = body.playlists ?? [];

    const stats = body.stats ?? {};
    $('#pp-count').textContent = `${stats.playlists ?? 0} crawled · ${fmtCount(stats.items ?? 0)} titles`;
    rail.innerHTML = body.playlists.map(ppCard).join('');

    if (!body.playlists.length) {
      note.hidden = false;
      note.textContent = q
        ? `No crawled playlist matches “${q}”. The index only holds what has been discovered so far — scan more to widen it.`
        : 'The index is empty. Hit Scan more to start discovering public playlists.';
    } else {
      note.hidden = true;
    }
  } catch (e) {
    rail.innerHTML = '';
    note.hidden = false;
    note.textContent = `Could not load public playlists — ${e.message}`;
  }
}

async function openPublicPlaylist(slug) {
  openSheet('public', slug);

  let p;
  try {
    p = await api('/api/public/playlists/' + encodeURIComponent(slug));
  } catch (e) {
    $('#sheet-body').innerHTML = `<p class="note">Could not open this playlist — ${esc(e.message)}</p>`;
    return;
  }

  const items = p.items ?? [];
  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = `${items.length} ${items.length === 1 ? 'title' : 'titles'}`;

  const ownerLine = p.owner_name
    ? `<a class="pl-owner-link" href="/api/public/playlists?owner=${encodeURIComponent(p.owner_channel_slug ?? '')}" data-pp-owner="${esc(p.owner_channel_slug ?? '')}">${esc(p.owner_name)}</a>`
    : 'unknown creator';

  $('#sheet-body').innerHTML = `
    <div class="detail-head">
      <h2 class="detail-title">${esc(p.title)}</h2>
      <div class="detail-meta">by ${ownerLine} · ${items.length} titles · ${p.playable ?? 0} playable${
        p.unresolved ? ` · ${p.unresolved} not in the local catalog` : ''}${
        p.truncated ? ' · upstream truncated this list' : ''}</div>
      <div class="detail-tags">
        <span class="hero-tag">public</span>
        ${p.views ? `<span class="hero-tag">${fmtCount(p.views)} views</span>` : ''}
        ${p.updated_at ? `<span class="hero-tag">updated ${esc(String(p.updated_at).slice(0, 10))}</span>` : ''}
        ${(p.tags ?? []).slice(0, 8).map((t) => `<button class="hero-tag pp-tag" type="button" data-pp-tag="${esc(t.text)}">${esc(t.text)}${t.count ? ` <em>${t.count}</em>` : ''}</button>`).join('')}
        ${(p.brands ?? []).slice(0, 6).map((b) => `<button class="hero-tag pp-tag" type="button" data-pp-brand="${esc(b.title)}">${esc(b.title)}${b.in_playlist_count ? ` <em>${b.in_playlist_count}</em>` : ''}</button>`).join('')}
      </div>
    </div>
    <div class="pl-items">
      ${items.map((i) => i.resolved === false
        ? `<div class="unresolved" title="Not present in the local catalog">${esc(i.name ?? i.slug ?? 'unknown')}</div>`
        : cardHtml(i)).join('')}
    </div>`;
}

// Tapping a studio or tag chip inside a playlist searches for it, so a
// playlist becomes a way into the rest of the index rather than a dead end.
document.addEventListener('click', (e) => {
  const chip = e.target.closest('[data-pp-tag], [data-pp-brand]');
  if (!chip) return;
  e.preventDefault();
  closeSheet();
  const term = chip.dataset.ppTag ?? chip.dataset.ppBrand;
  const input = $('#pp-q');
  input.value = term;
  $('#pp-search-shell').dataset.filled = 'true';
  loadPublicPlaylists(term);
  showView('playlists');   // the filtered rail is the answer, so go there
});

/** Which public playlists a given video appears in — the reverse lookups. */
async function playlistsForVideo(slug) {
  const slot = $('#in-playlists');
  if (!slot) return;
  let body;
  try {
    body = await api(`/api/public/videos/${encodeURIComponent(slug)}/playlists`);
  } catch {
    slot.remove();
    return;
  }
  if (!body.playlists?.length) { slot.remove(); return; }
  slot.hidden = false;
  slot.innerHTML = `
    <h3 class="in-pl-head">In ${body.total} public ${body.total === 1 ? 'playlist' : 'playlists'}</h3>
    <div class="rail">${body.playlists.map((p) => `
      <button class="chip in-pl-chip" type="button" data-pp="${esc(p.slug)}">
        ${esc(p.title)}${p.item_count ? ` <em>${p.item_count}</em>` : ''}
      </button>`).join('')}</div>`;
}

function wirePublicPlaylists() {
  const q = $('#pp-q');
  const clear = $('#pp-clear');
  if (q) {
    let t = null;
    q.oninput = () => {
      clearTimeout(t);
      t = setTimeout(() => loadPublicPlaylists(q.value.trim()), 220);
    };
    q.onkeydown = (e) => { if (e.key === 'Enter') { clearTimeout(t); loadPublicPlaylists(q.value.trim()); } };
  }
  if (clear) clear.onclick = () => { q.value = ''; loadPublicPlaylists(''); };

  const more = $('#pp-more');
  if (more) more.onclick = async () => {
    more.disabled = true;
    const was = more.textContent;
    more.textContent = 'Scanning…';
    try {
      await fetch('/api/public/crawl', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ count: 60, max_playlists: 400, max_channels: 60 }),
      });
      // The pass outlives the request; poll until it reports done, then reload.
      for (let i = 0; i < 120; i++) {
        await new Promise((r) => setTimeout(r, 1500));
        const st = await api('/api/public/crawl');
        if (!st.crawling) { await loadPublicPlaylists(); break; }
      }
    } catch (e) {
      $('#pp-note').hidden = false;
      $('#pp-note').textContent = `Scan failed — ${e.message}`;
    } finally {
      more.disabled = false;
      more.textContent = was;
    }
  };

  loadPublicPlaylists('');
}

// Public playlist cards, plus the "in N playlists" chips inside a video sheet.
document.addEventListener('click', (e) => {
  const card = e.target.closest('[data-pp]');
  if (!card) return;
  e.preventDefault();
  openPublicPlaylist(card.dataset.pp);
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const card = e.target.closest('[data-pp]');
  if (card) { e.preventDefault(); openPublicPlaylist(card.dataset.pp); }
});
$('#pp-q')?.addEventListener('input', (e) => {
  $('#pp-search-shell').dataset.filled = String(!!e.target.value);
});

export { ppIndex, ppQuery, ppMatchBadge, ppCard, loadPublicPlaylists, openPublicPlaylist, playlistsForVideo, wirePublicPlaylists };
