// mal.js — MyAnimeList tracking for all three areas: anime, manga and the
// 18+ shelf all write to the same MAL list, through the same row.
//
// How the pieces fit:
//
//   * Linking is OAuth2 authorization-code with PKCE (MAL supports the
//     `plain` challenge only). The login page opens as a normal navigation —
//     in the app the WebView walks to myanimelist.net and MAL redirects back
//     to the origin the flow started on, where this module swaps the code
//     for tokens and cleans the address bar. The verifier and state live in
//     storage between the two halves; nothing travels through a server we
//     control, because MAL's endpoints answer no browser origin (see
//     /api/mal in either backend: a pure pipe, no storage).
//
//   * Tokens live in this client's storage and nowhere else. Access tokens
//     are renewed on 401 (single-flight: MAL revokes the old refresh token
//     the moment a new one is issued, so two parallel refreshes would race
//     each other into a dead session).
//
//   * Detail sheets carry an empty `.mal-slot`. This module fills them —
//     resolving the MAL id from the sheet's data (AniList's idMal for anime,
//     mangafire's malId for manga) or, when there is none, by searching the
//     title once and remembering the answer.
//
//   * A build with no client id is honest about it: the slots stay empty,
//     Settings says the feature is not configured, and nothing pretends.

import { esc } from './core.js';

/**
 * The public identifier of this app's MyAnimeList registration (a client id
 * is not a secret — it rides in every authorize URL). Empty means "not
 * configured", which the Settings card says out loud.
 *
 * The registration's *secret* never appears here: the server relay adds it
 * from the environment (MAL_CLIENT_SECRET in .env) and the app adds it from
 * BuildConfig, which is filled from local.properties.
 */
const MAL_CLIENT_ID = '63e08e44f57ef1b4644322939ebd9fa6';

const AUTHORIZE = 'https://myanimelist.net/v1/oauth2/authorize';
const AUTH_KEY = 'mal.auth';      // { access, refresh, expiresAt }
const FLOW_KEY = 'mal.flow';      // { state, verifier } between the two halves
const IDS_KEY = 'mal.ids';        // resolved title -> MAL id
const TTL_FALLBACK_MS = 3600_000; // MAL's documented access-token lifetime

/** MAL's list statuses, per media kind — the API's own spellings. */
const STATUSES = {
  anime: [
    ['watching', 'Watching'],
    ['completed', 'Completed'],
    ['on_hold', 'On hold'],
    ['dropped', 'Dropped'],
    ['plan_to_watch', 'Plan to watch'],
  ],
  manga: [
    ['reading', 'Reading'],
    ['completed', 'Completed'],
    ['on_hold', 'On hold'],
    ['dropped', 'Dropped'],
    ['plan_to_read', 'Plan to read'],
  ],
};

/** GET-side field for progress, PUT-side field for progress — MAL differs. */
const PROGRESS = {
  anime: { put: 'num_watched_episodes', get: 'num_episodes_watched', label: 'ep watched' },
  manga: { put: 'num_chapters_read', get: 'num_chapters_read', label: 'chapters read' },
};

/* ------------------------------------------------------------- storage */

// Same guarded reads as the rest of the client: private-mode storage that
// throws must not take the page down.
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
function drop(key) {
  try { localStorage.removeItem(key); } catch (e) { /* unwritable */ }
}

const auth = () => load(AUTH_KEY, null);
const configured = () => !!MAL_CLIENT_ID;
const linked = () => {
  const a = auth();
  return !!(a && a.access);
};

/* ------------------------------------------------------- login, PKCE */

function randomToken(length) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
  const out = [];
  if (window.crypto && window.crypto.getRandomValues) {
    const buf = new Uint8Array(length);
    window.crypto.getRandomValues(buf);
    for (let i = 0; i < length; i += 1) out.push(alphabet[buf[i] % alphabet.length]);
  } else {
    // Non-secure contexts have no WebCrypto. The verifier still only has to
    // match its own challenge — state is the CSRF half, and this device is
    // the one that minted both.
    for (let i = 0; i < length; i += 1) {
      out.push(alphabet[Math.floor(Math.random() * alphabet.length)]);
    }
  }
  return out.join('');
}

/** Where MAL sends the user back: the plain origin root of whatever copy
 *  started the flow — https://hanime.tv/ in the app, the server's own
 *  origin in the browser. Both are pre-registered on the MAL application. */
const redirectUri = () => `${window.location.origin}/`;

function startLogin() {
  const verifier = randomToken(64);   // 43..128: the range MAL documents
  const state = randomToken(32);
  save(FLOW_KEY, { state, verifier, at: Date.now() });
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: MAL_CLIENT_ID,
    state,
    redirect_uri: redirectUri(),
    code_challenge: verifier,
    code_challenge_method: 'plain',
  });
  window.location.assign(`${AUTHORIZE}?${params.toString()}`);
}

let notice = '';

/**
 * Second half of the login, run at boot when the URL carries a code. The
 * address bar is cleaned before anything else so a reload can never replay
 * a spent code. Returns true when a link was established.
 */
async function handleMalCallback() {
  const query = new URLSearchParams(window.location.search);
  const code = query.get('code');
  const state = query.get('state');
  const problem = query.get('error');
  if (!code && !problem) return false;

  const flow = load(FLOW_KEY, null);
  drop(FLOW_KEY);
  try {
    window.history.replaceState(null, '', window.location.pathname + window.location.hash);
  } catch (e) { /* replace refused: the code still dies with this load */ }

  if (problem) {
    notice = problem === 'access_denied'
      ? 'MyAnimeList sign-in was cancelled.'
      : `MyAnimeList sign-in failed: ${problem}`;
    return false;
  }
  if (!flow || !state || state !== flow.state) {
    notice = 'MyAnimeList sign-in state did not match — please try again.';
    return false;
  }

  try {
    const body = await tokenRequest({
      grant_type: 'authorization_code',
      code,
      code_verifier: flow.verifier,
      redirect_uri: redirectUri(),
    });
    save(AUTH_KEY, tokenState(body, null));
    notice = '';
    return true;
  } catch (e) {
    notice = `MyAnimeList sign-in failed: ${e.message}`;
    return false;
  }
}

/* -------------------------------------------------------------- tokens */

function tokenState(body, previous) {
  const ttl = Number(body.expires_in) > 0 ? Number(body.expires_in) * 1000 : TTL_FALLBACK_MS;
  return {
    access: body.access_token,
    // A successful refresh issues a new refresh token; the old one dies
    // with it, so keeping the previous one would strand the next attempt.
    refresh: body.refresh_token || (previous && previous.refresh) || null,
    expiresAt: Date.now() + ttl,
  };
}

async function tokenRequest(fields) {
  // Query-only, like every other call into the pipe: Android's WebView
  // interceptor is never handed a request body, so a body-based contract
  // could not be implemented by the app's Java twin at all.
  const params = new URLSearchParams({ client_id: MAL_CLIENT_ID, ...fields });
  const res = await fetch(`/api/mal/token?${params.toString()}`, { method: 'POST' });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || !body.access_token) {
    throw new Error(body.error_description || body.error || `status ${res.status}`);
  }
  return body;
}

/** Single-flight refresh: MAL revokes the old refresh token the instant a
 *  new one is issued, so concurrent refreshes would invalidate each other. */
let refreshing = null;
function refreshAccess() {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    const current = auth();
    if (!current || !current.refresh) {
      const err = new Error('session expired — link MyAnimeList again');
      err.permanent = true;
      throw err;
    }
    const body = await tokenRequest({
      grant_type: 'refresh_token',
      refresh_token: current.refresh,
    });
    const next = tokenState(body, current);
    save(AUTH_KEY, next);
    return next;
  })().finally(() => { refreshing = null; });
  return refreshing;
}

/** The access token to call the pipe with — renewed when it is stale. */
async function currentAccess() {
  const a = auth();
  if (!a || !a.access) {
    const err = new Error('not linked');
    err.permanent = true;
    throw err;
  }
  if (a.expiresAt && Date.now() > a.expiresAt - 30_000) return refreshAccess();
  return a;
}

/* ---------------------------------------------------------- the API pipe */

async function mal(path, { method = 'GET' } = {}, retry = true) {
  const session = await currentAccess();
  const res = await fetch(`/api/mal/v2${path}`, {
    method,
    headers: { authorization: `Bearer ${session.access}` },
  });
  if (res.status === 401 && retry) {
    // A 401 means the call was refused before it did anything — the access
    // token simply went stale between the expiry check and the wire — so
    // renewing once and repeating is safe for every method.
    await refreshAccess();
    return mal(path, { method }, false);
  }
  if (!res.ok) {
    const detail = await res.json().catch(() => ({}));
    const err = new Error(detail.error || `status ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

/* ------------------------------------------------------------ list ops */

async function resolveMalId(kind, id, title) {
  if (id) return Number(id);
  const cache = load(IDS_KEY, {});
  const key = `${kind}:${String(title ?? '').toLowerCase()}`;
  if (cache[key]) return cache[key];

  const found = await mal(`/${kind}?q=${encodeURIComponent(title)}&limit=6&fields=id,title`);
  const items = (found.data ?? []).map((x) => ({ id: Number(x.id), title: x.title ?? '' }));
  const wanted = String(title ?? '').toLowerCase();
  const exact = items.find((x) => x.title.toLowerCase() === wanted);
  const starts = items.find((x) => x.title.toLowerCase().startsWith(wanted));
  const hit = exact || starts || items[0];
  if (!hit || !hit.id) return null;
  cache[key] = hit.id;
  save(IDS_KEY, cache);
  return hit.id;
}

function getEntry(kind, id) {
  // 404 is the honest "not on the list yet", not a failure of the pipe.
  return mal(`/${kind}/${id}/mylist_status`).catch((e) => {
    if (e.status === 404) return null;
    throw e;
  });
}

function setEntry(kind, id, { status, score, progress }) {
  const params = new URLSearchParams({ status });
  if (score !== '' && score != null) params.set('score', String(Number(score)));
  if (progress !== '' && progress != null) {
    params.set(PROGRESS[kind].put, String(Number(progress)));
  }
  // The fields ride the query: the pipe turns them into the form body MAL
  // writes list status with (see mal.mjs / Mal.java).
  return mal(`/${kind}/${id}/mylist_status?${params.toString()}`, { method: 'PUT' });
}

function removeEntry(kind, id) {
  return mal(`/${kind}/${id}/mylist_status`, { method: 'DELETE' });
}

/* --------------------------------------------------------- detail slots */

const filling = new WeakSet();

function statusOptions(kind, selected) {
  return STATUSES[kind]
    .map(([value, label]) => `<option value="${value}"${value === selected ? ' selected' : ''}>${label}</option>`)
    .join('');
}

function slotLinkHtml() {
  return '<div class="mal-box" data-state="idle">'
    + '<div class="mal-head"><span>MyAnimeList</span></div>'
    + '<button class="chip mal-link" type="button">Link account to track</button>'
    + '</div>';
}

function slotFormHtml(kind, entry) {
  const progress = PROGRESS[kind];
  const score = entry && entry.score ? String(entry.score) : '';
  const current = entry && entry.status ? entry.status : '';
  const read = entry ? String(entry[progress.get] ?? '') : '';
  return `<div class="mal-box" data-state="ready" data-entry="${entry ? '1' : '0'}">
    <div class="mal-head"><span>MyAnimeList</span><span class="mal-entry">${entry ? 'on your list' : 'not on your list'}</span></div>
    <div class="mal-controls">
      <select class="mal-status" aria-label="MyAnimeList status">
        <option value=""${current ? '' : ' selected'} disabled>Choose status…</option>
        ${statusOptions(kind, current)}
      </select>
      <select class="mal-score" aria-label="MyAnimeList score">
        <option value=""${score ? '' : ' selected'}>score: —</option>
        ${[10, 9, 8, 7, 6, 5, 4, 3, 2, 1]
          .map((n) => `<option value="${n}"${score === String(n) ? ' selected' : ''}>score: ${n}</option>`)
          .join('')}
      </select>
      <input class="mal-prog" type="number" min="0" inputmode="numeric"
             placeholder="${progress.label}" value="${esc(read)}" aria-label="${progress.label}">
      <button class="chip mal-save" type="button">Save</button>
      <button class="chip mal-remove" type="button"${entry ? '' : ' hidden'}>Remove</button>
    </div>
    <p class="mal-msg" hidden></p>
  </div>`;
}

function slotMessage(slot, text, tone) {
  const msg = slot.querySelector('.mal-msg');
  if (!msg) return;
  msg.hidden = !text;
  msg.textContent = text || '';
  if (tone) msg.dataset.tone = tone;
}

async function fillMalSlot(slot) {
  if (!configured() || filling.has(slot)) return;
  filling.add(slot);
  try {
    if (!linked()) {
      slot.innerHTML = slotLinkHtml();
      slot.querySelector('.mal-link').addEventListener('click', startLogin);
      return;
    }

    const kind = slot.dataset.malKind === 'manga' ? 'manga' : 'anime';
    const title = slot.dataset.malTitle ?? '';
    slot.innerHTML = '<div class="mal-box" data-state="loading">'
      + '<div class="mal-head"><span>MyAnimeList</span><span class="mal-entry">looking this up…</span></div>'
      + '</div>';

    const id = await resolveMalId(kind, slot.dataset.malId || '', title);
    if (!slot.isConnected) return;
    if (!id) {
      slot.innerHTML = '<div class="mal-box" data-state="none">'
        + '<div class="mal-head"><span>MyAnimeList</span>'
        + '<span class="mal-entry">no match for this title</span></div></div>';
      return;
    }

    const entry = await getEntry(kind, id);
    if (!slot.isConnected) return;
    renderSlotForm(slot, kind, id, entry);
  } catch (e) {
    if (!slot.isConnected) return;
    slot.innerHTML = '';
    const box = document.createElement('div');
    box.className = 'mal-box';
    box.dataset.state = 'error';
    box.innerHTML = `<div class="mal-head"><span>MyAnimeList</span>`
      + `<span class="mal-entry">${esc(e.message)}</span></div>`;
    slot.appendChild(box);
  } finally {
    filling.delete(slot);
  }
}

function renderSlotForm(slot, kind, id, entry) {
  slot.innerHTML = slotFormHtml(kind, entry);
  const status = slot.querySelector('.mal-status');
  const score = slot.querySelector('.mal-score');
  const prog = slot.querySelector('.mal-prog');

  slot.querySelector('.mal-save').addEventListener('click', async () => {
    if (!status.value) {
      slotMessage(slot, 'Pick a status first.', 'bad');
      return;
    }
    const button = slot.querySelector('.mal-save');
    button.disabled = true;
    try {
      const updated = await setEntry(kind, id, {
        status: status.value,
        score: score.value,
        progress: prog.value,
      });
      renderSlotForm(slot, kind, id, updated);
      slotMessage(slot, 'Saved to MyAnimeList.', 'good');
    } catch (e) {
      button.disabled = false;
      slotMessage(slot, `Could not save — ${e.message}`, 'bad');
    }
  });

  const remove = slot.querySelector('.mal-remove');
  if (remove) {
    remove.addEventListener('click', async () => {
      remove.disabled = true;
      try {
        await removeEntry(kind, id);
        renderSlotForm(slot, kind, id, null);
        slotMessage(slot, 'Removed from your list.', '');
      } catch (e) {
        remove.disabled = false;
        slotMessage(slot, `Could not remove — ${e.message}`, 'bad');
      }
    });
  }
}

/** Fill every slot already on screen, and every slot that lands later. */
function watchSlots() {
  document.querySelectorAll('.mal-slot').forEach(fillMalSlot);
  if (!document.documentElement) return;
  new MutationObserver((records) => {
    for (const record of records) {
      for (const node of record.addedNodes) {
        if (node.nodeType !== 1) continue;
        if (node.matches && node.matches('.mal-slot')) fillMalSlot(node);
        if (node.querySelectorAll) node.querySelectorAll('.mal-slot').forEach(fillMalSlot);
      }
    }
  }).observe(document.documentElement, { childList: true, subtree: true });
}

/* ------------------------------------------------------------- settings */

async function fillMalSettings() {
  const who = document.getElementById('mal-user');
  if (!who) return;
  const note = document.getElementById('mal-note');
  const link = document.getElementById('mal-link');
  const unlink = document.getElementById('mal-unlink');

  if (!configured()) {
    who.textContent = 'not configured';
    if (note) {
      note.hidden = false;
      note.textContent = 'This build has no MyAnimeList client id, so tracking '
        + 'is switched off. Register an application at myanimelist.net/apiconfig '
        + 'and set its id in the client to turn it on.';
    }
    link.hidden = true;
    unlink.hidden = true;
    return;
  }

  if (!linked()) {
    who.textContent = 'not linked';
    link.hidden = false;
    unlink.hidden = true;
    if (note) {
      note.hidden = !notice;
      if (notice) note.textContent = notice;
    }
    return;
  }

  try {
    const me = await mal('/users/@me');
    who.textContent = `@${me.name}`;
    link.hidden = true;
    unlink.hidden = false;
    if (note) note.hidden = true;
  } catch (e) {
    who.textContent = e.permanent ? 'not linked' : 'unreachable';
    link.hidden = e.permanent ? false : true;
    unlink.hidden = false;
    if (note) {
      note.hidden = false;
      note.textContent = e.permanent
        ? 'The MyAnimeList session expired — link again.'
        : `MyAnimeList: ${e.message}`;
    }
  }
}

/* --------------------------------------------------------------- wiring */

// The settings rows are this module's own: the card exists in the markup
// above the script, so — exactly like settings.js — the buttons bind here
// and the view refills on the way in, the way the manga shelf refills.
document.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go && go.dataset.go === 'settings') fillMalSettings();
});

(() => {
  const link = document.getElementById('mal-link');
  const unlink = document.getElementById('mal-unlink');
  if (link) link.addEventListener('click', startLogin);
  if (unlink) {
    unlink.addEventListener('click', () => {
      drop(AUTH_KEY);
      drop(IDS_KEY);
      notice = 'MyAnimeList unlinked from this device.';
      fillMalSettings();
    });
  }
})();

watchSlots();

export { handleMalCallback, fillMalSettings, startLogin, configured, linked };
