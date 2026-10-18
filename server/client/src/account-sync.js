// account-sync.js — what signing in actually keeps in step.
//
// The account holds three things this phone also has of its own: the library
// (favourites), where each title was left, and the history — the titles that
// were opened, which used to be the one list tied to a single device. All three
// move both ways:
//
//   * on the way up, the moments this device changes: a heart pressed, a
//     position written while watching, a title opened, a list cleared. Each one
//     is queued rather than sent (account-queue.js): writes about one title
//     collapse into the newest, a burst becomes one call per dataset, and a
//     call that fails is retried instead of lost;
//
//   * on the way down, one pull on the way in (and whenever the reader asks):
//     the account's three lists are read and merged into this device's own
//     records, and then whatever the account had never seen is queued up in one
//     batched call per dataset.
//
// The rules of that merge are in account-model.js, which is pure and tested on
// its own; this file is the part that talks.
//
// The anime and films rails travel the same way, and that is the newest part
// of this file. Those two areas watch through an embed, so their row carries
// the episode that was opened and nothing about the second (see
// account-model.js) — enough for the window to offer the title back, and enough
// for this phone to show what the window was watching, at the episode it is at.
//
// Two things deliberately do not sync, for two different reasons:
//
//   * Manga. It keeps its own store (`htv:activity:v1`, kind `manga`, with
//     mangafire's own chapter refs), and the window has no reader at all — a
//     chapter would be a row nothing on the other side could ever show.
//   * Episode marks. The 18+ shelf plays whole videos, and the anime and manga
//     the phone tracks are tracked on MyAnimeList, which has its own list.
//
// Nothing here runs without a session: every call is guarded, so a reader who
// has never signed in sees exactly the app they had before.

import {
  CONTENT_TYPE,
  PROFILE_ID,
  accountIdFor,
  activityEntryForTitle,
  activityIdentityFor,
  activityProgressKey,
  activityProgressPayload,
  activityTitleGroups,
  animeKeyFromId,
  filmKeyFromId,
  hasRealPosition,
  historyPayload,
  libraryPayload,
  mergePositions,
  progressPayload,
  reconcileFavorites,
  reconcileHistory,
  seriesKeyFromId,
} from './account-model.js';
import { applyActivityFromAccount, listFor, markActivityFromAccount } from './activity.js';
import { createAccountQueue } from './account-queue.js';
import { accountNote, accountRpc, onAccountEvent, signedIn } from './account.js';
import { LIB_MAX_HISTORY, keepCover, lib, libWrite } from './library.js';
import { renderLibrary } from './positions.js';

/** A page of the library snapshot. The desktop pages the same way. */
const PAGE = 500;

/** How many pages a pull will walk before it stops: a phone's library is not
 *  ten thousand titles, and a truncated pull must not become an endless one. */
const MAX_PAGES = 20;

const CLIENT_KEY = 'htv:account-client:v1';

/**
 * This device's own id in the account's delta log.
 *
 * The desktop tags every write with one so a client can tell its own events
 * apart from another's; a phone that sent nothing would leave the window
 * unable to see where a row came from. It is minted once and kept.
 */
function clientId() {
  const held = (() => {
    try { return localStorage.getItem(CLIENT_KEY); } catch (e) { return null; }
  })();
  if (held) return held;

  const alphabet = 'abcdef0123456789';
  let tail = '';
  try {
    const buf = new Uint8Array(8);
    window.crypto.getRandomValues(buf);
    for (let i = 0; i < 8; i += 1) tail += alphabet[buf[i] % alphabet.length];
  } catch (e) {
    for (let i = 0; i < 8; i += 1) tail += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  const id = `sekai-phone-${tail}`;
  try { localStorage.setItem(CLIENT_KEY, id); } catch (e) { /* minted again next time */ }
  return id;
}

/* ------------------------------------------------------------------ pull */

async function pullLibrary() {
  const rows = [];
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const chunk = await accountRpc('sync_pull_library', {
      p_profile_id: PROFILE_ID,
      p_limit: PAGE,
      p_offset: page * PAGE,
    });
    const list = Array.isArray(chunk) ? chunk : [];
    rows.push(...list);
    if (list.length < PAGE) break;
  }
  return rows;
}

async function pullPositions() {
  const rows = await accountRpc('sync_pull_watch_progress', { p_profile_id: PROFILE_ID });
  return Array.isArray(rows) ? rows : [];
}

async function pullHistory() {
  const rows = [];
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const chunk = await accountRpc('sync_pull_history', {
      p_profile_id: PROFILE_ID,
      p_limit: PAGE,
      p_offset: page * PAGE,
    });
    const list = Array.isArray(chunk) ? chunk : [];
    rows.push(...list);
    if (list.length < PAGE) break;
  }
  return rows;
}

/* ------------------------------------------------------------------ push */

async function pushLibrary(items) {
  if (!items.length) return;
  await accountRpc('sync_push_library_items', {
    p_profile_id: PROFILE_ID,
    p_items: items,
    p_origin_client_id: clientId(),
  });
}

async function deleteLibrary(keys) {
  if (!keys.length) return;
  await accountRpc('sync_delete_library_items', {
    p_profile_id: PROFILE_ID,
    p_keys: keys,
    p_origin_client_id: clientId(),
  });
}

async function pushPositions(entries) {
  if (!entries.length) return;
  await accountRpc('sync_push_watch_progress', {
    p_profile_id: PROFILE_ID,
    p_entries: entries,
    p_origin_client_id: clientId(),
  });
}

async function deletePositions(keys) {
  if (!keys.length) return;
  await accountRpc('sync_delete_watch_progress', {
    p_profile_id: PROFILE_ID,
    p_keys: keys,
    p_origin_client_id: clientId(),
  });
}

async function pushHistory(entries) {
  if (!entries.length) return;
  await accountRpc('sync_push_history', {
    p_profile_id: PROFILE_ID,
    p_entries: entries,
    p_origin_client_id: clientId(),
  });
}

async function deleteHistory(keys) {
  if (!keys.length) return;
  await accountRpc('sync_delete_history', {
    p_profile_id: PROFILE_ID,
    p_keys: keys,
    p_origin_client_id: clientId(),
  });
}

async function clearHistory() {
  await accountRpc('sync_clear_history', {
    p_profile_id: PROFILE_ID,
    p_origin_client_id: clientId(),
  });
}

/* --------------------------------------------------------------- the queue */
//
// Every write this device makes goes through one queue (account-queue.js):
// collapsed by title, batched by dataset, and retried when a call does not
// land. The sender below is the only part that knows which functions the
// account answers to.

/** A PostgREST "no such function" — the project has not been migrated yet. */
function isMissingFunction(e) {
  return /could not find the function|PGRST202|HTTP 404/i.test(String(e?.message ?? e ?? ''));
}

/**
 * Whether the account knows the history dataset yet.
 *
 * History arrived in `accounts/sql/0007_history.sql`, which is a *migration*:
 * a client can be newer than the project it talks to, and a phone shipped
 * before the SQL was run must not fail every sync over a table that is not
 * there. So a call that comes back "no such function" means "this project does
 * not keep history yet": the entries are dropped rather than retried for ever,
 * the other two datasets carry on, and the card says which file to run.
 */
let historyState = 'unknown';   // 'unknown' | 'ready' | 'missing'

async function send(kind, entries) {
  if (kind === 'library') {
    const rows = entries.filter((e) => e.payload).map((e) => e.payload);
    const keys = entries.filter((e) => !e.payload).map((e) => ({ content_id: e.key, content_type: CONTENT_TYPE }));
    await Promise.all([pushLibrary(rows), deleteLibrary(keys)]);
    return;
  }

  if (kind === 'progress') {
    const rows = entries.filter((e) => e.payload).map((e) => e.payload);
    const keys = entries.filter((e) => !e.payload).map((e) => e.key);
    await Promise.all([pushPositions(rows), deletePositions(keys)]);
    return;
  }

  if (kind === 'history') {
    const rows = entries.filter((e) => e.payload).map((e) => e.payload);
    const keys = entries.filter((e) => !e.payload).map((e) => ({ content_id: e.key, content_type: CONTENT_TYPE }));
    try {
      await Promise.all([pushHistory(rows), deleteHistory(keys)]);
      historyState = 'ready';
    } catch (e) {
      if (!isMissingFunction(e)) throw e;
      historyState = 'missing';
    }
  }
}

const queue = createAccountQueue({ send });

/* ------------------------------------------- the embed areas' own rails ---- */
//
// The anime area and the films area watch through somebody else's document in
// a frame (account-model.js says why that matters), so what they add to the
// account is *which episode was opened* — never a second. Both directions live
// here: their rows are read back onto this device's rails, which is how an
// episode watched in the window turns up on this phone, and an episode opened
// here is queued as the row the window's own Continue watching rail reads.

/** The two kinds whose rail is the account's as well as this device's. */
const ACTIVITY_KINDS = ['anime', 'show'];

/**
 * What the last pull said each progress key holds, by key — `null` until a pull
 * has happened in this session.
 *
 * It exists for one rule: an episode *opened* here may never replace a *real*
 * position the window wrote for the same episode (see `hasRealPosition`). The
 * row itself is not kept, only the one fact the rule needs.
 */
let accountProgress = null;

/** The one fact per key that the rule above needs out of a pull's rows. */
function realPositions(rows) {
  const out = new Map();
  for (const row of Array.isArray(rows) ? rows : []) {
    const key = String(row?.progress_key ?? '');
    if (key && hasRealPosition(row)) out.set(key, true);
  }
  return out;
}

/**
 * True when writing this row would replace a real position with "opened it".
 *
 * Unknown counts as "would": before a pull this session, the only safe answer
 * is to leave the row to the next sync, which pulls first and therefore knows.
 * Nothing is lost by waiting — the entry stays on this device's rail, unowned,
 * and the sync's own push carries it.
 */
function clobbersRealPosition(row) {
  if (!row) return false;
  if (accountProgress === null) return true;
  return accountProgress.has(row.progress_key);
}

/** How many titles are looked up at once. The server is this app's own — the
 *  shell's `ApiServer` on the phone, loopback in the window — so a handful in
 *  flight is plenty, and sixty at once is a stall a reader would see. */
const TITLE_LOOKUP_CONCURRENCY = 4;

/**
 * What one title's own page says about it: the two things a progress row does
 * not carry (a name and a cover), and the one that settles an edge case — how
 * many episodes it has, which is what tells "the next episode" apart from "the
 * last one".
 *
 * @returns {Promise<{title: string, cover: string, episodes: number}|null>}
 */
async function resolveActivityTitle(contentId) {
  try {
    const animeKey = animeKeyFromId(contentId);
    if (animeKey) {
      const res = await fetch(`/api/anime/${encodeURIComponent(animeKey)}`);
      if (!res.ok) return null;
      const details = (await res.json())?.details;
      if (!details) return null;
      return {
        title: String(details.title ?? animeKey),
        cover: String(details.cover ?? ''),
        episodes: Number(details.eps) || 0,
      };
    }

    const seriesKey = seriesKeyFromId(contentId);
    const slug = seriesKey ?? filmKeyFromId(contentId);
    if (!slug) return null;
    const type = seriesKey ? 'tv' : 'movie';
    const res = await fetch(`/api/fmovies/${type}/${encodeURIComponent(slug)}`);
    if (!res.ok) return null;
    const details = (await res.json())?.details;
    if (!details) return null;
    // A series' episode count is per season on this catalogue, and a row does
    // not say which season's count would be the right one — so it is 0, and a
    // finished episode of a series is left to the window, whose own rail is
    // where a reader finds the next one.
    return { title: String(details.title ?? slug), cover: String(details.poster ?? ''), episodes: 0 };
  } catch (e) {
    return null;
  }
}

/**
 * The account's rows for these two areas, as this device's own rail entries.
 *
 * A title this phone already has on a rail needs no lookup: it was opened here,
 * so its name, cover and episode count are already in the record, and the
 * account's row only adds the episode it is at. The rest — titles watched in
 * the window and never here — are asked about once, on this app's own server,
 * and one it cannot answer for is passed over rather than drawn as an id: this
 * rail is a picture of what the reader can pick up, not a list of what went
 * wrong.
 */
async function activityEntriesFromRows(progress) {
  const groups = activityTitleGroups(progress);
  if (groups.size === 0) return [];

  const here = new Map();
  for (const kind of ACTIVITY_KINDS) {
    for (const entry of listFor(kind)) here.set(`${entry.kind}:${entry.id}`, entry);
  }

  const items = [];
  for (const [contentId, rows] of groups) {
    const identity = activityIdentityFor(contentId);
    if (!identity) continue;
    const local = here.get(`${identity.kind}:${identity.id}`) ?? null;
    items.push({ contentId, rows, local, resolved: null });
  }

  const pending = items.filter((item) => !(item.local && item.local.title));
  let next = 0;
  const worker = async () => {
    while (next < pending.length) {
      const item = pending[next];
      next += 1;
      item.resolved = await resolveActivityTitle(item.contentId);
    }
  };
  if (pending.length) {
    const workers = Math.min(TITLE_LOOKUP_CONCURRENCY, pending.length);
    await Promise.all(Array.from({ length: workers }, worker));
  }

  const entries = [];
  for (const item of items) {
    const known = item.resolved ?? (item.local?.title
      ? { title: item.local.title, cover: item.local.cover, episodes: Number(item.local.total) || 0 }
      : null);
    if (!known) continue;
    const entry = activityEntryForTitle(item.rows, known);
    if (!entry) continue;
    entry.title = String(known.title || item.local?.title || '');
    entry.cover = String(known.cover || item.local?.cover || '');
    if (Number(known.episodes) > 0) entry.total = Number(known.episodes);
    entries.push(entry);
  }
  return entries;
}

/**
 * An episode opened on this device, as the account hears it.
 *
 * Queued like every other write — an episode opened with the network down is
 * not lost — and collapsed by key, so a sitting of four episodes is one row:
 * the rail keeps one card per title, and the account keeps one row per episode.
 * A manga chapter is refused by the payload builder rather than here.
 */
function accountActivityOpened(entry) {
  if (!signedIn()) return;
  const row = activityProgressPayload(entry);
  if (!row || clobbersRealPosition(row)) return;
  queue.set('progress', row.progress_key, row);
}

/**
 * A title taken off this phone's rail, as the account hears it.
 *
 * Every row the card stood for goes, not just the one it names: an "up next"
 * card speaks for the episode that was finished, and a card at episode 5
 * speaks for the episodes behind it — leaving those would put the title back,
 * one episode earlier, on the very next pull.
 */
function accountActivityDropped(entry) {
  if (!signedIn() || !entry) return;
  const keys = Array.isArray(entry.keys) && entry.keys.length
    ? entry.keys
    : [activityProgressKey(entry)];
  for (const key of keys) {
    if (key) queue.set('progress', key, null);
  }
}

/** The rail emptied: every title that was on it is off the account's too. */
function accountActivityCleared(entries) {
  if (!signedIn()) return;
  for (const entry of Array.isArray(entries) ? entries : []) accountActivityDropped(entry);
}

/* ------------------------------------------------------------------ sync */

/**
 * The account and this device, in step.
 *
 * Pull first, then send what the merge found only here: a title this phone
 * files after the pull is not lost, it is simply the next sync's to send, and
 * sending before reading would overwrite a row the account has a better copy of.
 *
 * @returns {{ note: string }} the sentence the card should say, in this
 *   account's own words.
 */
async function syncAccount() {
  if (!signedIn()) throw new Error('not signed in');

  // History is pulled on its own first, because a project without 0007 answers
  // "no such function" — and a missing list must not take the other two down.
  let historyRows = [];
  try {
    historyRows = await pullHistory();
    historyState = 'ready';
  } catch (e) {
    if (!isMissingFunction(e)) throw e;
    historyState = 'missing';
  }

  const [rows, progress] = await Promise.all([pullLibrary(), pullPositions()]);
  // What the window has really watched, before anything here is written: the
  // episode openings below are cheaper records than a real position and must
  // never replace one.
  accountProgress = realPositions(progress);

  const favourites = reconcileFavorites(lib.favorites, rows);
  const positions = mergePositions(lib.positions, progress);

  // The two embed areas' rails, which are the account's as well: a title the
  // window was watching belongs on this phone's rail at the episode it is at,
  // and one this phone opened belongs on the window's.
  const activityEntries = await activityEntriesFromRows(progress);
  const activityPush = [];
  for (const kind of ACTIVITY_KINDS) {
    // Each kind is merged on its own, because each kind is its own rail.
    activityPush.push(
      ...applyActivityFromAccount(kind, activityEntries.filter((entry) => entry.kind === kind)),
    );
  }
  const past = historyState === 'missing'
    ? { history: lib.history, push: [] }
    : reconcileHistory(lib.history, historyRows, LIB_MAX_HISTORY);

  // Written in place: `lib` is the one object the Library view reads, and
  // replacing it would leave every other module holding the old records.
  lib.favorites = favourites.favorites;
  lib.positions = positions.positions;
  lib.history = past.history;

  // A title that arrived from the account is on this device from now on, so its
  // cover is kept for the screen that has to work offline — the same courtesy
  // library.js does when a heart is pressed here.
  for (const entry of [...favourites.favorites, ...past.history]) {
    if (entry.from === 'account') keepCover(entry);
  }

  // Through the queue like every other write: one call per dataset, and a
  // failure is retried rather than reported as a sync that did not happen.
  for (const entry of favourites.push) {
    const row = libraryPayload(entry);
    if (row) queue.set('library', row.content_id, row);
  }
  for (const { slug, position } of positions.push) {
    const row = progressPayload(slug, position);
    if (row) queue.set('progress', row.progress_key, row);
  }
  for (const entry of past.push) {
    const row = historyPayload(entry);
    if (row) queue.set('history', row.content_id, row);
  }
  // An episode this phone opened is the same row the window writes when it
  // plays one, so the two rails carry one list rather than two.
  const sentActivity = new Set();
  for (const entry of activityPush) {
    const row = activityProgressPayload(entry);
    if (!row) continue;
    // The account is already further into this episode than "opened it": the
    // window stopped twelve minutes in, and one second would send the reader
    // back to the start. Nothing to write — but the account does know the
    // title, so this is not a row to send again on the next sync.
    if (!clobbersRealPosition(row)) queue.set('progress', row.progress_key, row);
    sentActivity.add(`${entry.kind}:${entry.id}`);
  }
  await queue.flush();

  // Pushed is the account's now: the mark is what stops a row being sent again
  // on every sync, and what lets a removal on the window be a removal here.
  const sentFavourites = new Set(favourites.push.map((e) => e.slug));
  for (const entry of lib.favorites) {
    if (sentFavourites.has(entry.slug)) entry.from = 'account';
  }
  const sentOpenings = new Set(past.push.map((e) => e.slug));
  for (const entry of lib.history) {
    if (sentOpenings.has(entry.slug)) entry.from = 'account';
  }
  markActivityFromAccount(sentActivity);

  libWrite();
  renderLibrary();

  return { note: historyState === 'missing'
    ? 'Favorites and watch positions are up to date. History is not kept in this project yet — run accounts/sql/0007_history.sql, then sync again.'
    : 'Favorites, watch positions and history are up to date.' };
}

/* ------------------------------------------------------ this device moves */

/**
 * A heart, as the account hears it: pressed, a row; released, a deletion.
 *
 * Called by library.js at the moment the device's own record changes, so the
 * account can never be a version behind the screen. A failure is swallowed —
 * the reader pressed a heart, and a network that is down is not their problem;
 * the record stays on this device and the next sync carries it.
 */
function accountFavorite(slug, on, entry) {
  if (!signedIn() || !slug) return;
  const contentId = accountIdFor(slug);
  if (!contentId) return;

  if (!on) {
    queue.set('library', contentId, null);
    return;
  }

  const row = libraryPayload(entry ?? { slug });
  if (!row) return;
  // The mark that says the account owns this row is not put on here: it is the
  // *next* sync's, once a pull has seen the row come back. Until then the title
  // is this device's, which is why an unsent heart is simply sent again rather
  // than counted as filed.
  queue.set('library', row.content_id, row);
}

/**
 * A title was opened on this device — the one write that used to stay here.
 *
 * Queued like the others, so opening four titles from a rail is one call, and
 * retried when the network is not there rather than forgotten. A project that
 * has not run 0007 yet answers "no such function", which the sender treats as
 * "not now" rather than an error to keep retrying (see `send`).
 */
function accountOpened(entry) {
  if (!signedIn() || !entry?.slug) return;
  const row = historyPayload(entry);
  if (!row) return;
  queue.set('history', row.content_id, row);
}

/**
 * The reader emptied the history, and that is not "these keys are gone": the
 * account's list is the one being cleared, wherever those rows came from — so
 * the whole profile's history goes (`sync_clear_history`), and so do the watch
 * positions, which is what the same button has always done on the device.
 *
 * Called *before* library.js wipes `lib`, because the positions being deleted
 * are the ones the device still holds.
 */
function accountHistoryCleared() {
  if (!signedIn()) return;

  // Everything queued about history and positions is stale the moment the
  // reader empties the list: a title opened a second ago would be filed again
  // after the clear, and a position would put a row back in a rail that was
  // just emptied.
  queue.clear('history');
  queue.clear('progress');

  const keys = Object.keys(lib.positions).map(accountIdFor).filter(Boolean);
  Promise.all([clearHistory(), deletePositions(keys)]).catch(() => {});
}

/**
 * A position, as the account hears it.
 *
 * `null` is a title that was finished or cleared on this device, which is a
 * deletion on the account too: leaving the row behind would put the title back
 * in this phone's "continue watching" on the very next pull.
 */
function accountPosition(slug, position) {
  if (!signedIn() || !slug) return;
  const contentId = accountIdFor(slug);
  if (!contentId) return;
  // A position that is not a position is not pushed: this device's own rule is
  // that a finished title has no position (progressPayload refuses one), and a
  // row that disagrees would come back as "continue watching".
  if (!position) { queue.set('progress', contentId, null); return; }
  const payload = progressPayload(slug, position);
  if (!payload) return;
  queue.set('progress', payload.progress_key, payload);
}

/** The account server's own words, or the transport's. */
function plainFailure(e) {
  const text = String(e?.message ?? e ?? '');
  return /fetch|network|failed to fetch/i.test(text) ? 'the account server could not be reached' : (text || 'unknown error');
}

// Registered on the account's own events (account.js): the card reports what
// the reader asked for, and this is what answers.
onAccountEvent('signedIn', () => syncAccount().catch((e) => {
  accountNote(`Signed in, but the sync failed — ${plainFailure(e)}`);
}));
onAccountEvent('sync', syncAccount);
// A signed-out phone owes the account nothing, and a write that went out after
// the session ended would only fail and retry: the queue is emptied with it, and
// so is what the last account's pull said about its positions.
onAccountEvent('signedOut', () => {
  queue.clear();
  accountProgress = null;
});

export {
  syncAccount, accountFavorite, accountOpened, accountPosition, accountHistoryCleared,
  accountActivityOpened, accountActivityDropped, accountActivityCleared,
  clientId, CLIENT_KEY,
};
