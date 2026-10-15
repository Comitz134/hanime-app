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
//   * Both of those are somebody else's guess about which MAL entry a title
//     is, and this app's names are not MAL's names. So the row can be pointed
//     elsewhere by hand: it searches MAL, shows what came back with enough of
//     each hit to tell two apart, and the reader picks. That pick outranks
//     every guess above, and it is kept for that title.
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

/* ------------------------------------------------------- which MAL entry */

/** The MAL id kept under one app title, in whichever shape it was stored. */
function storedPick(kind, title) {
  const key = `${kind}:${String(title ?? '').toLowerCase()}`;
  const hit = load(IDS_KEY, {})[key];
  if (hit == null) return null;
  // Older builds kept the bare number. A pick made by hand is an object
  // instead, because MAL's own name for that entry has to survive beside it —
  // the name is what the row shows back, and what tells the reader which of
  // MAL's entries this app is now tracking.
  return typeof hit === 'object'
    ? { id: Number(hit.id), title: hit.title ?? '', picked: !!hit.picked }
    : { id: Number(hit), title: '', picked: false };
}

/** The reader said this is the one. Kept under the app's own title, so the
 *  same pick answers the next time this title is opened. */
function rememberPick(kind, title, item) {
  const cache = load(IDS_KEY, {});
  cache[`${kind}:${String(title ?? '').toLowerCase()}`] = {
    id: Number(item.id), title: item.title ?? '', picked: true,
  };
  save(IDS_KEY, cache);
}

/**
 * MAL's own search — the way to a title this app calls something else. Every
 * hit carries what tells two same-named entries apart: the kind, the year it
 * started, and how long it is. None of those can be guessed for the reader,
 * which is the whole reason the choice is theirs.
 */
const SEARCH_FIELDS = {
  anime: 'id,title,media_type,start_date,num_episodes',
  manga: 'id,title,media_type,start_date,num_chapters',
};

/** The box searches as the reader types. This is the pause that decides how
 *  often: long enough not to spend a request per letter, short enough that the
 *  list is already there when the typing stops. The manga shelf's own field
 *  waits 240ms, so this is that pause, to the eye. */
const TYPE_MS = 250;

/** One letter matches half of MAL's catalogue and helps nobody. */
const MIN_QUERY = 2;

async function searchMal(kind, q, limit = 10) {
  const query = String(q ?? '').trim();
  if (!query) return [];
  const body = await mal(`/${kind}?q=${encodeURIComponent(query)}&limit=${limit}&fields=${SEARCH_FIELDS[kind]}`);
  return (body?.data ?? []).map((x) => ({
    id: Number(x.id),
    title: x.title ?? '',
    kind: x.media_type ? String(x.media_type).replace(/_/g, ' ') : '',
    year: String(x.start_date ?? '').slice(0, 4),
    length: Number(kind === 'manga' ? x.num_chapters : x.num_episodes) || 0,
  }));
}

/* ------------------------------------------------------------ list ops */

/**
 * Which MyAnimeList entry an app title is, in three answers and in this order:
 *
 *   1. what the reader picked by hand — the one answer nobody has to check,
 *      and therefore the one that outranks the rest;
 *   2. the id the sheet arrived with (AniList's idMal, mangafire's malId),
 *      which is somebody else's guess;
 *   3. a title search, whose answer is remembered.
 *
 * A miss is null rather than the first row of a search: the slot says so and
 * offers the picker, which beats attaching a title to the wrong entry.
 */
async function resolveMalId(kind, id, title) {
  const stored = storedPick(kind, title);
  if (stored && stored.picked) return stored.id;
  if (id) return Number(id);
  if (stored && stored.id) return stored.id;

  const found = await searchMal(kind, title, 6);
  const wanted = String(title ?? '').toLowerCase();
  const exact = found.find((x) => x.title.toLowerCase() === wanted);
  const starts = found.find((x) => x.title.toLowerCase().startsWith(wanted));
  const hit = exact || starts || found[0];
  if (!hit || !hit.id) return null;
  const cache = load(IDS_KEY, {});
  cache[`${kind}:${String(title ?? '').toLowerCase()}`] = { id: hit.id, title: hit.title };
  save(IDS_KEY, cache);
  return hit.id;
}

/**
 * A title's own entry: the reader's status on it, plus how many episodes or
 * chapters it has — the total is what decides "finished" when progress is
 * written for them.
 *
 * Read through the media route, not a status route: `/anime/{id}/my_list_status`
 * takes writes only (a GET is `405 method_not_allowed`), and its spellings are
 * load-bearing — `mylist_status` without the underscores is not a route at all
 * and answers `404 {"error":"not_found"}`, which is what this client used to
 * ask for. An entry that is not on the list is simply absent from the answer.
 */
async function getTitleEntry(kind, id) {
  const total = kind === 'manga' ? 'num_chapters' : 'num_episodes';
  // MAL's own name for the entry rides along with the status: the app's title
  // and MAL's are often two different names, and a row that tracks an entry
  // should say which one it is attached to.
  const body = await mal(`/${kind}/${id}?fields=title,my_list_status,${total}`);
  const status = body?.my_list_status ?? null;
  return {
    malTitle: body?.title ?? '',
    entry: status ? { ...status, [total]: Number(body?.[total]) || 0 } : null,
  };
}

/** Just the list status — what auto-advance reads to decide what to write. */
const getEntry = async (kind, id) => (await getTitleEntry(kind, id)).entry;

function setEntry(kind, id, { status, score, progress }) {
  const params = new URLSearchParams({ status });
  if (score !== '' && score != null) params.set('score', String(Number(score)));
  if (progress !== '' && progress != null) {
    params.set(PROGRESS[kind].put, String(Number(progress)));
  }
  // The fields ride the query: the pipe turns them into the form body MAL
  // writes list status with (see mal.mjs / Mal.java). The path is MAL's own —
  // `/my_list_status`, underscores and all (verified against the live API:
  // the write answers 200 with the status echoed back).
  return mal(`/${kind}/${id}/my_list_status?${params.toString()}`, { method: 'PUT' });
}

function removeEntry(kind, id) {
  return mal(`/${kind}/${id}/my_list_status`, { method: 'DELETE' });
}

/* ------------------------------------------------------------ auto-advance */

/**
 * The reader is already telling this app what they are watching, so the app
 * tells MyAnimeList: opening an episode writes episodes watched, opening a
 * chapter writes chapters read. The number only ever moves forward, and the
 * furthest one written this session is remembered, so re-opening the same
 * episode costs no request.
 *
 * Two rules worth stating out loud, because this writes to the reader's own
 * list:
 *
 *   * a title that is not on the list yet is added as watching/reading — that
 *     is what "the list keeps itself current without touching MAL" means;
 *   * reaching the last episode or chapter marks it completed, which is what
 *     MAL's own clients do when progress reaches the total.
 *
 * A failure here is swallowed on purpose: playback must never depend on MAL.
 * The detail sheet's own row is where a reader sees exactly what happened.
 */
const autoWritten = {};   // `${kind}:${id}` -> the furthest number written this session

async function autoAdvance(kind, id, number, title) {
  const n = Number(number);
  if (!configured() || !linked() || !PROGRESS[kind] || !Number.isFinite(n) || n < 1) return;
  try {
    const malId = await resolveMalId(kind, id, title);
    if (!malId) return;
    const key = `${kind}:${malId}`;
    if (autoWritten[key] >= n) return;

    const entry = await getEntry(kind, malId);   // null when it is not on the list
    const have = Number(kind === 'manga' ? entry?.num_chapters_read : entry?.num_episodes_watched) || 0;
    if (have >= n) { autoWritten[key] = have; return; }

    const total = Number(kind === 'manga' ? entry?.num_chapters : entry?.num_episodes) || 0;
    // Their own status is kept unless it was a plan — moving a plan to watching
    // is the one case where opening something plainly says more than the list.
    const kept = entry?.status && !/^plan_to_/.test(entry.status) ? entry.status : null;
    const status = total && n >= total ? 'completed'
      : kept ?? (kind === 'manga' ? 'reading' : 'watching');
    await setEntry(kind, malId, { status, progress: n });
    autoWritten[key] = n;
  } catch (e) { /* a failed write must not interrupt an episode */ }
}

/* ------------------------------------------------------- the whole list */

/**
 * The account's own lists, read once and kept on the device.
 *
 * This is the other half of tracking: the slots above write one title at a
 * time from a detail page, while this pulls everything the account already
 * watches and reads, so the Library can show it without anyone retyping a
 * list into the app.
 *
 * `nsfw=true` is not decoration. MAL hides black-flagged entries from list
 * answers unless it is asked for them, and those are exactly the ones the
 * 18+ shelf exists to show. `fields` asks for the few things the shelves
 * draw — status, progress, cover, titles — because an unqualified list
 * answer is heavy and the WAN on a phone is the slow part.
 */
const LISTS_KEY = 'mal.lists';    // { at, anime: [...], manga: [...] }
const LIST_TTL_MS = 10 * 60_000;  // switching tabs must not re-fetch what was just read
const LIST_PAGES = 20;            // 100 a page, so 2000 entries a kind — beyond any real list

/**
 * What the shelves need, asked for twice, because MAL's field parser takes
 * unknown names quietly (verified against the live API: a made-up field, and
 * even a made-up sub-section, come back 200 with the valid keys and no
 * error). A list answer is `{ node, list_status }` per entry, so the fields
 * are named both as the sub-sections they are and as the keys inside them —
 * whichever way MAL matches them, the answer carries the same handful of
 * keys, and a silent miss would empty the shelves rather than fail loudly.
 *
 * The two kinds differ in exactly one field, and asking for the other kind's
 * name would be asking for something the media does not have.
 */
const LIST_FIELDS = {
  anime: [
    'list_status{status,score,num_episodes_watched,updated_at}',
    'node{id,title,main_picture,num_episodes,media_type,nsfw,genres}',
    'list_status,node',
    'id,title,main_picture,num_episodes,media_type,nsfw,genres',
    'status,score,num_episodes_watched,updated_at',
  ].join(','),
  manga: [
    'list_status{status,score,num_chapters_read,updated_at}',
    'node{id,title,main_picture,num_chapters,media_type,nsfw,genres}',
    'list_status,node',
    'id,title,main_picture,num_chapters,media_type,nsfw,genres',
    'status,score,num_chapters_read,updated_at',
  ].join(','),
};

/**
 * One entry, normalised once: both kinds answer in the same envelope, and the
 * shelves differ only in which fields they read off it.
 */
function malEntry(kind, item) {
  const node = item?.node ?? {};
  const status = item?.list_status ?? {};
  const genres = (node.genres ?? []).map((g) => String(g?.name ?? ''));
  const total = Number(kind === 'manga' ? node.num_chapters : node.num_episodes);
  const read = Number(kind === 'manga' ? status.num_chapters_read : status.num_episodes_watched);
  return {
    kind,
    malId: Number(node.id) || 0,
    title: String(node.title ?? ''),
    cover: node.main_picture?.medium ?? node.main_picture?.large ?? '',
    status: String(status.status ?? ''),
    score: Number(status.score) || 0,
    progress: Number.isFinite(read) ? read : 0,
    total: Number.isFinite(total) ? total : 0,
    updatedAt: Date.parse(status.updated_at ?? '') || 0,
    // MAL answers "is this adult" twice: the black NSFW flag, and the Hentai
    // genre. Either one files the entry on the 18+ shelf.
    adult: node.nsfw === 'black' || genres.some((g) => g.toLowerCase() === 'hentai'),
  };
}

/**
 * One kind, every page. MAL pages at 100 and hands the next one back as a
 * full URL; it is reduced to the path this pipe already understands, because
 * the path is the part that carries the caller's own token.
 */
async function fetchList(kind) {
  const out = [];
  // `/users/@me/<kind>list` — the account's own list. The bare `animelist`
  // path is not an endpoint at all: MAL answers it 404 `not_found`, which is
  // exactly the silence this comment exists to prevent happening twice.
  let path = `/users/@me/${kind === 'manga' ? 'mangalist' : 'animelist'}`
    + `?limit=100&nsfw=true&fields=${encodeURIComponent(LIST_FIELDS[kind])}`;
  for (let page = 0; page < LIST_PAGES && path; page += 1) {
    const body = await mal(path);
    for (const item of body?.data ?? []) out.push(malEntry(kind, item));
    const next = String(body?.paging?.next ?? '');
    path = next ? next.replace(/^https?:\/\/api\.myanimelist\.net\/v2/, '') : '';
  }
  return out.filter((e) => e.malId && e.title);
}

/** The device's copy, as it stands — for painting before the network answers. */
function malCache() {
  return load(LISTS_KEY, null);
}

let listPull = null;   // single-flight: three shelves, one load

/**
 * Both lists, refreshed when stale. A pull that fails over a usable copy
 * answers with the copy — a phone with no signal still shows the list it
 * read this morning, which is the point of keeping one.
 */
async function malLists({ force = false } = {}) {
  const cached = malCache();
  const fresh = cached && Date.now() - (Number(cached.at) || 0) < LIST_TTL_MS;
  if (!force && fresh) return cached;
  if (!linked()) return cached;
  if (!listPull) {
    listPull = Promise.all([fetchList('anime'), fetchList('manga')])
      .then(([anime, manga]) => {
        const next = { at: Date.now(), anime, manga };
        save(LISTS_KEY, next);
        return next;
      })
      .finally(() => { listPull = null; });
  }
  try {
    return await listPull;
  } catch (e) {
    if (cached) return cached;
    throw e;
  }
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

function slotFormHtml(kind, entry, malTitle, picked) {
  const progress = PROGRESS[kind];
  const score = entry && entry.score ? String(entry.score) : '';
  const current = entry && entry.status ? entry.status : '';
  const read = entry ? String(entry[progress.get] ?? '') : '';
  return `<div class="mal-box" data-state="ready" data-entry="${entry ? '1' : '0'}">
    <div class="mal-head"><span>MyAnimeList</span><span class="mal-entry">${entry ? 'on your list' : 'not on your list'}</span></div>
    ${malTitle ? `<p class="mal-attached">${esc(malTitle)}${picked ? '<span class="mal-tag">your pick</span>' : ''}</p>` : ''}
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
    <div class="mal-controls">
      <button class="chip mal-find" type="button"
              title="This app's title and MyAnimeList's may not be the same one">Search MyAnimeList…</button>
    </div>
    <p class="mal-msg" hidden></p>
  </div>`;
}

/**
 * The picker: MAL's search, with the app's own title already in the field.
 *
 * This is the one place a title's identity is decided by the reader instead
 * of by a rule, because it has to be: these names come from mangafire and
 * AniList, MAL keeps its own, and no automatic match can be trusted to tell
 * "Beware the Villainess!" from whatever else shares its words. The search
 * runs as the box opens — candidates on screen before anything is typed, the
 * way MAL's own site behaves — and each hit says enough to choose between
 * same-named entries: what kind it is, the year it started, how long it is.
 */
function slotSearchHtml(appTitle) {
  return `<div class="mal-box" data-state="search">
    <div class="mal-head"><span>MyAnimeList</span><span class="mal-entry">pick the right entry</span></div>
    <p class="mal-hint">This app calls it <b>${esc(appTitle)}</b> — MyAnimeList may call it
      something else. Type, and pick the entry you mean.</p>
    <div class="mal-search">
      <input class="mal-q" type="search" enterkeyhint="search" autocapitalize="none"
             autocomplete="off" spellcheck="false" placeholder="Search MyAnimeList…"
             value="${esc(appTitle)}" aria-label="Search MyAnimeList">
      <button class="chip mal-clear" type="button" aria-label="Clear the search">Clear</button>
    </div>
    <div class="mal-hits"></div>
    <p class="mal-msg" hidden></p>
    <div class="mal-controls">
      <button class="chip mal-cancel" type="button">Cancel</button>
    </div>
  </div>`;
}

function slotMessage(slot, text, tone) {
  const msg = slot.querySelector('.mal-msg');
  if (!msg) return;
  msg.hidden = !text;
  msg.textContent = text || '';
  if (tone) msg.dataset.tone = tone;
}

/** The chip that opens the picker, wherever the box happens to be drawn. */
function openPick(slot, kind, title) {
  const button = slot.querySelector('.mal-find');
  if (!button) return;
  button.addEventListener('click', () => {
    renderSlotSearch(slot, kind, title, () => fillMalSlot(slot));
  });
}

/** One entry, drawn: the reader's status on it, the controls, and MAL's own
 *  name for it — which is what says *which* entry this row is tracking. */
async function showEntry(slot, kind, id, malTitle, picked) {
  const { entry, malTitle: name } = await getTitleEntry(kind, id);
  if (!slot.isConnected) return;
  renderSlotForm(slot, kind, id, entry, name || malTitle || '', picked);
}

async function fillMalSlot(slot) {
  if (!configured() || filling.has(slot)) return;
  const kind = slot.dataset.malKind === 'manga' ? 'manga' : 'anime';
  const title = slot.dataset.malTitle ?? '';
  filling.add(slot);
  try {
    if (!linked()) {
      slot.innerHTML = slotLinkHtml();
      slot.querySelector('.mal-link').addEventListener('click', startLogin);
      return;
    }

    const stored = storedPick(kind, title);
    slot.innerHTML = '<div class="mal-box" data-state="loading">'
      + '<div class="mal-head"><span>MyAnimeList</span><span class="mal-entry">looking this up…</span></div>'
      + '</div>';

    const id = await resolveMalId(kind, slot.dataset.malId || '', title);
    if (!slot.isConnected) return;
    if (!id) {
      // Nothing came back that this app could be sure of. The search is the
      // reader's way out, so it is offered in the same breath as the miss.
      slot.innerHTML = '<div class="mal-box" data-state="none">'
        + '<div class="mal-head"><span>MyAnimeList</span>'
        + '<span class="mal-entry">no match for this title</span></div>'
        + '<div class="mal-controls"><button class="chip mal-find" type="button">Search MyAnimeList…</button></div>'
        + '</div>';
      openPick(slot, kind, title);
      return;
    }

    await showEntry(slot, kind, id, '', !!stored?.picked);
  } catch (e) {
    if (!slot.isConnected) return;
    slot.innerHTML = '';
    const box = document.createElement('div');
    box.className = 'mal-box';
    box.dataset.state = 'error';
    box.innerHTML = `<div class="mal-head"><span>MyAnimeList</span>`
      + `<span class="mal-entry">${esc(e.message)}</span></div>`
      + '<div class="mal-controls"><button class="chip mal-find" type="button">Search MyAnimeList…</button></div>';
    slot.appendChild(box);
    openPick(slot, kind, title);
  } finally {
    filling.delete(slot);
  }
}

function renderSlotForm(slot, kind, id, entry, malTitle, picked) {
  // Save and Remove redraw this box without the name to hand, so the two facts
  // that survive a redraw ride on the slot itself.
  if (malTitle !== undefined) slot.dataset.malName = malTitle;
  if (picked !== undefined) slot.dataset.malPicked = picked ? '1' : '0';
  slot.innerHTML = slotFormHtml(kind, entry, slot.dataset.malName ?? '', slot.dataset.malPicked === '1');
  const status = slot.querySelector('.mal-status');
  const score = slot.querySelector('.mal-score');
  const prog = slot.querySelector('.mal-prog');
  openPick(slot, kind, slot.dataset.malTitle ?? '');

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

/**
 * The picker, live: search MAL, show what came back, and let a hit be pressed.
 *
 * A press is the whole point — it writes the pick under this app's title (so
 * this title opens on that entry from now on, ahead of every guess) and draws
 * the entry's own row, which is where the reader sees MAL's name for what they
 * just picked. Cancelling goes back to the row as it was.
 */
async function renderSlotSearch(slot, kind, appTitle, onCancel) {
  slot.innerHTML = slotSearchHtml(appTitle);
  const input = slot.querySelector('.mal-q');
  const hits = slot.querySelector('.mal-hits');
  const msg = slot.querySelector('.mal-msg');
  const say = (text, tone) => {
    if (!msg) return;
    msg.hidden = !text;
    msg.textContent = text || '';
    if (tone) msg.dataset.tone = tone;
  };

  // Two things a search bar needs that a one-shot form did not. Keystrokes are
  // debounced into one request; and every request is stamped, because with a
  // request per pause the answers can arrive out of order — the reply for "Bew"
  // may land after the reply for "Beware the Villainess!" and put a list on
  // screen that does not match what is in the field.
  let typeTimer = null;
  let seq = 0;

  const render = (found) => {
    const unit = kind === 'manga' ? 'chapters' : 'episodes';
    hits.innerHTML = found.map((x) => `<button class="mal-hit" type="button"
        data-malid="${x.id}" data-maltitle="${esc(x.title)}">
        <b>${esc(x.title)}</b>
        <span>${esc([x.kind, x.year,
          x.length ? `${x.length} ${unit}` : ''].filter(Boolean).join(' · '))}</span>
      </button>`).join('');
    for (const hit of hits.querySelectorAll('.mal-hit')) {
      hit.addEventListener('click', async () => {
        const pick = { id: Number(hit.dataset.malid), title: hit.dataset.maltitle };
        rememberPick(kind, appTitle, pick);
        await showEntry(slot, kind, pick.id, pick.title, true);
      });
    }
  };

  const run = async (raw) => {
    const query = String(raw ?? '').trim();
    const mine = ++seq;
    delete hits.dataset.busy;
    say('');
    if (!query) {
      hits.innerHTML = '<p class="mal-hint">Type a title — the name here and the name '
        + 'on MyAnimeList are often two different names.</p>';
      return;
    }
    if (query.length < MIN_QUERY) {
      hits.innerHTML = `<p class="mal-hint">Keep typing — MyAnimeList is searched from
        ${MIN_QUERY} letters.</p>`;
      return;
    }
    // While the answer is out, the list that is already there stays, dimmed:
    // replacing it with a spinner on every keystroke is what makes a live
    // search flicker.
    hits.dataset.busy = '1';
    try {
      const found = await searchMal(kind, query);
      if (mine !== seq) return;                        // a newer keystroke won
      if (!slot.isConnected || !hits.isConnected) return;
      delete hits.dataset.busy;
      if (!found.length) {
        hits.innerHTML = `<p class="mal-hint">Nothing on MyAnimeList for “${esc(query)}”. `
          + 'Try the name you know it by there, or a word from it.</p>';
        return;
      }
      render(found);
    } catch (e) {
      if (mine !== seq || !slot.isConnected) return;
      delete hits.dataset.busy;
      hits.innerHTML = '';
      say(`Could not search MyAnimeList — ${e.message}`, 'bad');
    }
  };

  input.addEventListener('input', () => {
    clearTimeout(typeTimer);
    typeTimer = setTimeout(() => run(input.value), TYPE_MS);
  });
  input.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    clearTimeout(typeTimer);
    run(input.value);
  });
  slot.querySelector('.mal-clear').addEventListener('click', () => {
    input.value = '';
    clearTimeout(typeTimer);
    input.focus({ preventScroll: true });
    run('');
  });
  slot.querySelector('.mal-cancel').addEventListener('click', () => onCancel());
  input.focus({ preventScroll: true });
  await run(appTitle);
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
      drop(LISTS_KEY);
      notice = 'MyAnimeList unlinked from this device.';
      fillMalSettings();
    });
  }
})();

watchSlots();

export {
  handleMalCallback, fillMalSettings, startLogin, configured, linked,
  malLists, malCache, malEntry, LISTS_KEY, LIST_TTL_MS, autoAdvance,
};
