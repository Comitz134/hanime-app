// playlists.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { cardHtml } from './cards.js';
import { $, api, esc } from './core.js';
import { updateBackState } from './shell.js';

/* ------------------------------------------------------------------ playlists */

let plIndex = [];

/* ------------------------------------------------------------------ playlists */

function plArt(p) {
  const covers = (p.preview ?? []).map((x) => x.cover).filter(Boolean).slice(0, 4);
  if (!covers.length) {
    return `<div class="pl-art" data-count="1"><span class="pl-art-empty">no cover</span></div>`;
  }
  const shown = covers.slice(0, 3);
  const extra = (p.count ?? 0) - shown.length;
  return `<div class="pl-art" data-count="${Math.min(covers.length, 4)}">
    ${shown.map((c) => `<img loading="lazy" src="${esc(c)}" alt="">`).join('')}
    ${extra > 0 ? `<span class="pl-more">+${extra}</span>` : ''}
  </div>`;
}

function plCard(p) {
  const flags = [
    p.synthetic ? 'derived' : null,
    p.unresolved ? `${p.unresolved} unresolved` : null,
    p.match ? p.match === 'title' ? 'title match' : `${p.match_count} in list` : null,
  ].filter(Boolean);
  return `<article class="pl-card" data-playlist="${esc(p.slug)}" tabindex="0" role="button" aria-label="${esc(p.title)}">
    ${plArt(p)}
    <h3 class="pl-name">${esc(p.title)}</h3>
    <p class="pl-meta">${p.count} ${p.count === 1 ? 'title' : 'titles'}${
      flags.length ? `<span class="pl-flags">${flags.map((f) => `<span class="pl-flag">${esc(f)}</span>`).join('')}</span>` : ''
    }</p>
  </article>`;
}

function connectCard(reason) {
  const why = {
    no_session: 'No account connected yet.',
    expired: 'The stored cookie no longer authenticates — it has probably expired.',
    empty: 'No cookie was supplied.',
  }[reason] ?? (reason ? `Session check failed: ${reason}` : 'No account connected yet.');

  return `<div class="connect-card">
    <h3>Connect an account to see your playlists</h3>
    <p>
      Public playlists need no account — see <em>Public Playlists</em> above.
      Your own playlists are different: they arrive inside the account session
      payload, so the proxy needs a cookie from a browser you are already
      logged in with. ${esc(why)}
    </p>
    <p>
      1. Log in on hanime.tv &nbsp;→&nbsp; 2. DevTools &nbsp;→&nbsp; Network &nbsp;→&nbsp; any request to
      <code>auth.hanime.tv</code> &nbsp;→&nbsp; 3. copy its <code>Cookie</code> request header.
      The password is never used and never stored.
    </p>
    <div class="connect-row">
      <input id="cookie-input" type="password" placeholder="session=…" autocomplete="off" spellcheck="false">
      <button class="pill pill-primary" id="cookie-send">Connect</button>
    </div>
    <div class="connect-msg" id="cookie-msg" hidden></div>
  </div>`;
}

function wireConnect() {
  const send = $('#cookie-send');
  if (!send) return;
  const input = $('#cookie-input');
  const msg = $('#cookie-msg');
  const submit = async () => {
    const cookie = input.value.trim();
    if (!cookie) return;
    send.disabled = true;
    msg.hidden = false;
    msg.dataset.kind = '';
    msg.textContent = 'Checking…';
    try {
      const res = await fetch('/api/session', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ cookie }),
      });
      const body = await res.json();
      if (!res.ok) {
        msg.dataset.kind = 'err';
        msg.textContent = body.hint ?? body.reason ?? 'Rejected.';
        return;
      }
      input.value = '';
      msg.dataset.kind = 'ok';
      msg.textContent = `Connected as ${body.user ?? 'account'} — ${body.playlists} playlists.`;
      await loadSession();
    } catch (e) {
      msg.dataset.kind = 'err';
      msg.textContent = `Request failed: ${e.message}`;
    } finally {
      send.disabled = false;
    }
  };
  send.onclick = submit;
  input.onkeydown = (e) => { if (e.key === 'Enter') submit(); };
}

async function loadSession() {
  let info;
  try {
    info = await api('/api/session');
  } catch (e) {
    $('#sess-label').textContent = 'unreachable';
    $('#connect-slot').innerHTML = connectCard(e.message);
    wireConnect();
    return;
  }

  const live = info.live === true;
  $('#sess-dot').dataset.live = String(live);
  $('#sess-label').textContent = live
    ? (info.user?.username ? `@${info.user.username}` : 'connected')
    : 'not connected';
  $('#sess-reset').hidden = !info.configured;

  if (live) {
    $('#connect-slot').innerHTML = '';
    $('#playlists-body').hidden = false;
    await loadPlaylists();
  } else {
    $('#playlists-body').hidden = true;
    $('#connect-slot').innerHTML = connectCard(info.reason);
    wireConnect();
  }
}

async function loadPlaylists() {
  const q = ($('#pl-q')?.value ?? '').trim();
  const note = $('#pl-note');
  const rail = $('#pl-rail');
  if (!rail) return;
  rail.innerHTML = Array.from({ length: 6 }, () => '<div class="pl-card"><div class="skeleton" style="aspect-ratio:1"></div></div>').join('');

  try {
    const body = await api('/api/playlists' + (q ? `?q=${encodeURIComponent(q)}` : ''));
    if (!body.configured) { await loadSession(); return; }
    plIndex = body.playlists;

    const s = body.stats ?? {};
    rail.innerHTML = body.playlists.map(plCard).join('');

    if (q && !body.playlists.length) {
      note.hidden = false;
      note.textContent = `Nothing in your playlists matches “${q}”.`;
    } else {
      note.hidden = true;
    }
    $('#sess-label').textContent = `${s.total_playlists ?? 0} playlists`;
  } catch (e) {
    rail.innerHTML = '';
    note.hidden = false;
    note.textContent = `Could not load playlists — ${e.message}`;
  }
}

async function openPlaylist(slug) {
  const sheet = $('#sheet');
  sheet.classList.add('open');
  document.body.style.overflow = 'hidden';
  sheet.scrollTop = 0;
  updateBackState();
  $('#sheet-count').hidden = true;
  $('#sheet-body').innerHTML = '<div class="center-spin"><div class="spinner"></div></div>';

  let p;
  try {
    p = await api('/api/playlists/' + encodeURIComponent(slug));
  } catch (e) {
    $('#sheet-body').innerHTML = `<p class="note">Could not open this playlist — ${esc(e.message)}</p>`;
    return;
  }

  const items = p.items ?? [];
  const resolved = items.filter((i) => i.resolved);
  const unresolved = items.length - resolved.length;

  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = `${items.length} ${items.length === 1 ? 'title' : 'titles'}`;

  $('#sheet-body').innerHTML = `
    <div class="detail-head">
      <h2 class="detail-title">${esc(p.title)}</h2>
      <div class="detail-meta">${items.length} titles · ${resolved.length} playable${
        unresolved ? ` · ${unresolved} not in the local catalog` : ''}</div>
    </div>
    <div class="pl-items">
      ${items.map((i) => i.resolved
        ? cardHtml(i)
        : `<div class="unresolved" title="Not present in the local catalog">${esc(i.title)}</div>`).join('')}
    </div>`;
}

export { plIndex, plArt, plCard, connectCard, wireConnect, loadSession, loadPlaylists, openPlaylist };
