// account.js — the SEKAI account as this phone sees it: the session, the
// requests that carry it, and the Settings card that manages it.
//
// The account is one Supabase project shared with the desktop window. The
// desktop signs in through the app's own gate and syncs through the `sync_*`
// RPCs in `accounts/sql/`; this file is the same sign-in and the same pipe,
// written for the browser the phone is inside. What is synced, and how a row
// becomes a card here, is account-sync.js — this module only knows the account
// itself.
//
// Two things about the keys are worth saying out loud, because they look like
// secrets and are not: the project URL and the publishable key are public by
// design. They ship inside every client — the desktop build bakes the same pair
// (`SupabaseDefaultUrl` in shell/overrides/.../build.gradle.kts), and so does
// `accounts/probe.mjs` — and they carry no privileges of their own. Row-level
// security, not key secrecy, is what keeps one account's library out of another
// reader's. The service-role key (which *is* a secret) is never in a client.
//
// The session lives in this device's own storage, in the same guarded
// localStorage as the library, so a reader stays signed in across a restart and
// a self-update. Nothing here runs without it: every request is made as the
// signed-in account, and every failure is reported to the card rather than
// thrown at the page.

import { $ } from './core.js';

/**
 * The project this build talks to, substituted into the bundle by
 * `client/build.mjs` (esbuild's `define`). Both may be overridden at build time
 * with `SEKAI_SUPABASE_URL` / `SEKAI_SUPABASE_KEY` — the same two variables
 * `accounts/probe.mjs` reads — so a self-hosted project is a build, not a patch.
 */
const SUPABASE_URL = __SEKAI_SUPABASE_URL__;
const SUPABASE_KEY = __SEKAI_SUPABASE_KEY__;

const ACCOUNT_KEY = 'htv:account:v1';

/** How long before it expires a session is renewed, so a request never
 *  travels on a token that dies on the way. */
const RENEW_MARGIN_MS = 60_000;

/* ---------------------------------------------------------------- session */

function load() {
  try {
    const raw = localStorage.getItem(ACCOUNT_KEY);
    const session = raw ? JSON.parse(raw) : null;
    if (!session || typeof session !== 'object' || !session.access) return null;
    return session;
  } catch (e) {
    return null;
  }
}

function save(session) {
  try {
    if (session) localStorage.setItem(ACCOUNT_KEY, JSON.stringify(session));
    else localStorage.removeItem(ACCOUNT_KEY);
  } catch (e) { /* an unwritable store leaves the session for this run only */ }
}

let session = load();

const configured = () => !!(SUPABASE_URL && SUPABASE_KEY);
const signedIn = () => !!session?.access;
const accountEmail = () => String(session?.email ?? '');

/* -------------------------------------------------------------- transport */

/** The account server's own words for what went wrong, or the status code. */
function reasonOf(status, text) {
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (e) { body = null; }
  const detail = body?.error_description ?? body?.msg ?? body?.message ?? body?.error;
  return String(detail || `HTTP ${status}`);
}

async function send(path, { method = 'GET', body, token, headers = {} } = {}) {
  const response = await fetch(`${SUPABASE_URL}${path}`, {
    method,
    headers: {
      apikey: SUPABASE_KEY,
      accept: 'application/json',
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  return { status: response.status, text };
}

/** One sign-in response, or one refresh, as this device stores it. */
function sessionFrom(body, previous) {
  const access = body?.access_token;
  if (!access) return null;
  const expiresIn = Number(body?.expires_in);
  return {
    access,
    // Rotation: a refresh may hand back a new refresh token, and the old one is
    // spent the moment it does.
    refresh: String(body?.refresh_token ?? previous?.refresh ?? ''),
    expiresAt: Date.now() + (Number.isFinite(expiresIn) && expiresIn > 0 ? expiresIn * 1000 : 3600_000),
    email: String(body?.user?.email ?? previous?.email ?? ''),
    userId: String(body?.user?.id ?? previous?.userId ?? ''),
  };
}

/**
 * Renews the session, once. A refresh token that no longer works is the end of
 * the session rather than an error to retry: the reader sees the card say so and
 * can sign in again.
 */
async function renew() {
  if (!session?.refresh) return false;
  let result;
  try {
    result = await send('/auth/v1/token?grant_type=refresh_token', {
      method: 'POST',
      body: { refresh_token: session.refresh },
    });
  } catch (e) {
    return false;
  }
  if (result.status >= 300) {
    session = null;
    save(null);
    fire('signedOut');
    // A session that lapsed behind the reader's back is visible the moment it
    // happens: the card is the one place the account speaks, and it should not
    // still be claiming a sign-in that is over.
    fillAccountSettings();
    return false;
  }
  let body = null;
  try { body = JSON.parse(result.text); } catch (e) { body = null; }
  const next = sessionFrom(body, session);
  if (!next) return false;
  session = next;
  save(session);
  return true;
}

/**
 * A request as the signed-in account, renewed when it is about to lapse and
 * retried once when the server says the token is no good.
 */
async function authed(path, { body } = {}) {
  if (!signedIn()) throw new Error('not signed in');
  if (Date.now() > session.expiresAt - RENEW_MARGIN_MS) await renew();
  let result = await send(path, { method: 'POST', body, token: session?.access });
  if (result.status === 401 && session) {
    if (await renew()) result = await send(path, { method: 'POST', body, token: session?.access });
  }
  if (result.status >= 300) throw new Error(reasonOf(result.status, result.text));
  return result;
}

/**
 * One of the account's own functions — `sync_pull_library` and the rest of
 * `accounts/sql/`, the same names the desktop calls.
 */
async function accountRpc(name, params) {
  const result = await authed(`/rest/v1/rpc/${name}`, { body: params ?? {} });
  // A `returns void` function answers 204 with nothing in it, and there is
  // nothing to read; everything else is the row set the RPC selected.
  try {
    return result.text ? JSON.parse(result.text) : null;
  } catch (e) {
    return null;
  }
}

/* ------------------------------------------------------------ the account */

/** Signs in with the account's email and password. Throws with a reason. */
async function signIn(email, password) {
  if (!configured()) throw new Error('this build has no account backend');
  const result = await send('/auth/v1/token?grant_type=password', {
    method: 'POST',
    body: { email, password },
  });
  if (result.status >= 300) throw new Error(reasonOf(result.status, result.text));
  let body = null;
  try { body = JSON.parse(result.text); } catch (e) { body = null; }
  const next = sessionFrom(body, null);
  if (!next) throw new Error('the server sent no session');
  session = next;
  save(session);
  fire('signedIn');
  return session;
}

/**
 * Creates an account and signs in with it.
 *
 * A project with email confirmation on answers a sign-up with a user and no
 * session, which is not a failure — it is a confirmation waiting to be used, and
 * the card says exactly that instead of pretending the reader is signed in.
 *
 * @returns {boolean} true when the sign-up left a session behind.
 */
async function signUp(email, password) {
  if (!configured()) throw new Error('this build has no account backend');
  const result = await send('/auth/v1/signup', { method: 'POST', body: { email, password } });
  if (result.status >= 300) throw new Error(reasonOf(result.status, result.text));
  let body = null;
  try { body = JSON.parse(result.text); } catch (e) { body = null; }
  const next = sessionFrom(body, null);
  if (!next) return false;
  session = next;
  save(session);
  fire('signedIn');
  return true;
}

/** Signs out: the account's session ends here, and this device forgets it. */
async function signOut() {
  const token = session?.access;
  session = null;
  save(null);
  fire('signedOut');
  // The revoke is best-effort — a reader who asked to be signed out is signed
  // out here and now, whether or not the server was reachable to hear it.
  if (token) {
    try { await send('/auth/v1/logout', { method: 'POST', token }); } catch (e) { /* already gone */ }
  }
}

/* ------------------------------------------------------------------ hooks */
//
// The account is signed in here and *used* in account-sync.js, and the two have
// to stay one-way: this module never imports the sync, so the app has no cycle
// and a build with the account but no sync would still work. The sync registers
// what it wants to hear about instead — and a listener that throws is not
// allowed to take the card down with it.

const hooks = { signedIn: [], signedOut: [], sync: [] };

function onAccountEvent(kind, fn) {
  if (!hooks[kind] || typeof fn !== 'function') return;
  hooks[kind].push(fn);
}

/** Runs a hook set and answers with its failures, so the card can report. */
function fire(kind) {
  const results = (hooks[kind] ?? []).map((fn) => {
    try {
      return Promise.resolve(fn());
    } catch (e) {
      return Promise.reject(e);
    }
  });
  return Promise.allSettled(results);
}

/* ------------------------------------------------------------- the card */
//
// The account's Settings card is this module's own, the way the MyAnimeList
// card is mal.js's: the markup is above the script, the buttons bind here, and
// the view refills on the way in (settings.js calls fillAccountSettings next to
// fillMalSettings).

/** What the reader is told when something did not work, in plain words. */
function plainReason(message) {
  const text = String(message ?? '');
  if (/invalid login credentials|invalid_grant|invalid email or password/i.test(text)) {
    return 'That email and password were not accepted.';
  }
  if (/email not confirmed/i.test(text)) {
    return 'That account\'s email address has not been confirmed yet.';
  }
  if (/already registered|already been registered/i.test(text)) {
    return 'That email already has an account — sign in instead.';
  }
  if (/fetch|network|failed to fetch/i.test(text)) {
    return 'The account server could not be reached.';
  }
  return text || 'That did not work.';
}

let notice = '';

/** What a reader who has just opened the card is told, when there is nothing
 *  newer to say: what this is for, and what it does not do. */
const IDLE_NOTE = 'Sign in with the account the desktop window uses. '
  + 'Favorites, history and where you left off are kept for the account, not for this phone.';

function accountNote(text) {
  notice = text ?? '';
  paintNote();
}

function paintNote() {
  const note = $('#acct-note');
  if (!note) return;
  const text = notice || IDLE_NOTE;
  note.hidden = false;
  note.textContent = text;
}

/**
 * The card, filled for the state the account is in: the email in the row, the
 * form only when it has something to do. Called on the way into the view
 * (settings.js), and again after anything here changes the account.
 */
function fillAccountSettings() {
  const who = $('#acct-who');
  const form = $('#acct-form');
  const out = $('#acct-signout');
  const now = $('#acct-sync');
  if (!who || !form || !out || !now) return;

  // A build that carries no project cannot sign anyone in, and says so instead
  // of offering buttons that would fail — the same honesty mal.js shows when
  // its client id is empty.
  if (!configured()) {
    who.textContent = 'not configured';
    form.hidden = true;
    out.hidden = true;
    now.hidden = true;
    accountNote('This build has no account backend, so nothing can be synced from here.');
    return;
  }

  const on = signedIn();
  who.textContent = on ? accountEmail() || 'signed in' : 'Not signed in';
  form.hidden = on;
  out.hidden = !on;
  now.hidden = !on;
  paintNote();
}

/** Reads the two fields, or says what is missing. */
function credentials() {
  const email = String($('#acct-email')?.value ?? '').trim();
  const password = String($('#acct-pass')?.value ?? '');
  if (!email || !password) {
    accountNote('Enter the account\'s email and password.');
    return null;
  }
  return { email, password };
}

/** Both ways into the account are disabled while one of them is in flight, so
 *  a second press cannot become a second account. */
function busy(on) {
  for (const id of ['acct-signin', 'acct-signup']) {
    const button = $(`#${id}`);
    if (button) button.disabled = on;
  }
}

// Filled on the way into the view, the way the MyAnimeList card is: the state
// can have changed behind it — a session that lapsed, a sync that failed.
document.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go && go.dataset.go === 'settings') fillAccountSettings();
});

(() => {
  const form = $('#acct-form');
  if (!form) return;
  // Once at load, so the card is already right the first time it is looked at —
  // and so a build with no backend has said so before anyone presses anything.
  fillAccountSettings();

  // Enter in either field submits the form — one way in, which is also the way
  // the browser's own password manager expects.
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const creds = credentials();
    if (!creds) return;
    busy(true);
    accountNote('Signing in…');
    try {
      await signIn(creds.email, creds.password);
      const passwordField = $('#acct-pass');
      if (passwordField) passwordField.value = '';
      notice = `Signed in as ${accountEmail()}.`;
      fillAccountSettings();
    } catch (err) {
      busy(false);
      accountNote(plainReason(err.message));
    }
  });

  $('#acct-signup')?.addEventListener('click', async () => {
    const creds = credentials();
    if (!creds) return;
    busy(true);
    accountNote('Creating the account…');
    try {
      const inNow = await signUp(creds.email, creds.password);
      notice = inNow
        ? `Signed in as ${accountEmail()}.`
        : `Account created. Confirm the address sent to ${creds.email}, then sign in here.`;
      fillAccountSettings();
    } catch (err) {
      busy(false);
      accountNote(plainReason(err.message));
    }
  });

  $('#acct-signout')?.addEventListener('click', async () => {
    await signOut();
    notice = 'Signed out on this device. Nothing was removed from the account.';
    fillAccountSettings();
  });

  $('#acct-sync')?.addEventListener('click', async () => {
    const button = $('#acct-sync');
    if (button) button.disabled = true;
    accountNote('Syncing…');
    const results = await fire('sync');
    if (button) button.disabled = false;
    const failed = results.find((r) => r.status === 'rejected');
    // The sync says what it managed to do in its own words — including the one
    // case where it did less than it wanted to, a project that has not been
    // migrated for history yet.
    const said = results.find((r) => r.status === 'fulfilled')?.value?.note;
    accountNote(failed
      ? `Sync failed — ${plainReason(failed.reason?.message ?? failed.reason)}`
      : (typeof said === 'string' && said ? said : 'Favorites, watch positions and history are up to date.'));
  });
})();

export {
  ACCOUNT_KEY,
  configured,
  signedIn,
  accountEmail,
  signIn,
  signUp,
  signOut,
  accountRpc,
  onAccountEvent,
  accountNote,
  fillAccountSettings,
};
