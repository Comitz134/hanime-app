// Account session passthrough.
//
// Playlists are the one thing on the site that is *not* public. Verified:
//
//   GET /playlists            -> 404 (no public index page)
//   /api/v11/playlists        -> 404, and the same for every playlist path tried
//   sitemap.xml.gz            -> 3429 video URLs, 178 brand, 61 tag, zero playlists
//
// What does exist is `/api/v11/keep-alive`, which needs three things at once:
//
//   x-signature / x-time   from the wasm signer   -> else 401 UA1
//   x-csrf-token           from ct.hanime.tv      -> else 422 CSRF_ERROR_1
//   the htv_csrf_proof cookie that binds the two  -> else 422 even with a token
//
// With all three and no login it answers `401 {"error_id":"UA1"}`. With a real
// session cookie it returns the whole account payload, playlists included. So
// the proxy holds one session cookie, never the password, and never logs in on
// your behalf — you paste a cookie you already have.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { signedHeaders } from './signer.mjs';
import { API_BASE, CSRF_URL } from './hanime.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const STORE = process.env.SESSION_FILE ?? path.resolve(HERE, '../.session.json');

const KEEP_ALIVE_MS = 4 * 60 * 1000;

/** @type {{ cookie: string, csrf: string|null, csrfProof: string|null, user: any, payload: any, at: number, verified: boolean }} */
let session = { cookie: '', csrf: null, csrfProof: null, user: null, payload: null, at: 0, verified: false };

let inflight = null;

// --------------------------------------------------------------------------
// persistence — the cookie survives a restart so you paste it once, not daily
// --------------------------------------------------------------------------

function load() {
  if (process.env.SESSION_FILE === '') return; // explicit opt-out
  try {
    if (!fs.existsSync(STORE)) return;
    const saved = JSON.parse(fs.readFileSync(STORE, 'utf8'));
    if (saved?.cookie) {
      session = { ...session, ...saved, payload: null, verified: false };
      console.log(`session: loaded cookie from ${path.basename(STORE)} (not yet revalidated)`);
    }
  } catch (e) {
    console.warn('session: could not read store —', e.message);
  }
}

function save() {
  if (process.env.SESSION_FILE === '') return;
  try {
    // 0600: this is a bearer credential, not config
    fs.writeFileSync(STORE, JSON.stringify({ cookie: session.cookie, at: session.at }, null, 2), { mode: 0o600 });
  } catch (e) {
    console.warn('session: could not write store —', e.message);
  }
}

load();

// --------------------------------------------------------------------------
// cookie handling
// --------------------------------------------------------------------------

/** Accept either a raw `Cookie:` header value or a copy-paste of one or more `name=value;` pairs. */
export function normalizeCookie(input) {
  const raw = String(input ?? '').trim();
  if (!raw) return '';
  // Tolerate a pasted header line, a JSON blob, or bare pairs.
  const stripped = raw
    .replace(/^cookie:\s*/i, '')
    .replace(/^"|"$/g, '');
  try {
    const parsed = JSON.parse(stripped);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.entries(parsed).map(([k, v]) => `${k}=${v}`).join('; ');
    }
  } catch {
    /* not JSON — treat as a header string */
  }
  // Keep only well-formed pairs so a pasted mess cannot smuggle a header break.
  return stripped
    .split(';')
    .map((p) => p.trim())
    .filter((p) => /^[A-Za-z0-9_.\-]+=[^;\r\n]*$/.test(p))
    .join('; ');
}

function cookieValue(cookie, name) {
  const hit = cookie.split(';').map((p) => p.trim()).find((p) => p.startsWith(name + '='));
  return hit ? hit.slice(name.length + 1) : null;
}

export function hasSession() {
  return !!session.cookie;
}

export function sessionInfo() {
  return {
    configured: hasSession(),
    verified: session.verified,
    user: session.user ? { username: session.user.username ?? null, id: session.user.id ?? null } : null,
    playlists: Array.isArray(session.payload?.playlists) ? session.payload.playlists.length : null,
    checked_at: session.at ? new Date(session.at).toISOString() : null,
  };
}

// --------------------------------------------------------------------------
// upstream calls
// --------------------------------------------------------------------------

const TIMEOUT_MS = 20_000;

/**
 * Hit keep-alive and merge the account payload.
 * @returns {Promise<{ status: number, payload: any }>}
 */
async function keepAlive() {
  const cookie = session.cookie;

  // A fresh proof cookie, requested while carrying the session, is what makes
  // the csrf token valid on the next call.
  const csrfHeaders = await signedHeaders();
  csrfHeaders.cookie = cookie;
  const csrfRes = await fetch(CSRF_URL, { headers: csrfHeaders, signal: AbortSignal.timeout(TIMEOUT_MS) });
  const proofCookies = csrfRes.headers.getSetCookie?.() ?? [];
  const csrfProof = proofCookies
    .map((c) => c.split(';')[0])
    .find((p) => p.startsWith('htv_csrf_proof='));

  const csrfBody = await csrfRes.json().catch(() => null);
  const csrf = csrfBody?.csrf_token ?? null;
  if (!csrf) throw Object.assign(new Error('could not obtain a csrf token'), { status: 502 });

  // Carry both the account cookie and the proof cookie the token is bound to.
  const outgoing = csrfProof ? `${cookie}; ${csrfProof}` : cookie;
  const headers = await signedHeaders({ 'x-csrf-token': csrf });
  headers.cookie = outgoing;

  const res = await fetch(`${API_BASE}/api/v11/keep-alive`, {
    headers,
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  const body = await res.json().catch(() => null);

  if (res.ok && body) {
    session.csrf = csrf;
    session.csrfProof = csrfProof;
    session.payload = body;
    session.user = body.user ?? null;
    session.at = Date.now();
    session.verified = true;
  }
  return { status: res.status, payload: body };
}

/** Validate (or revalidate) the stored cookie. */
export async function refresh({ force = false } = {}) {
  if (!hasSession()) return { ok: false, reason: 'no_session' };
  if (!force && session.payload && Date.now() - session.at < KEEP_ALIVE_MS) {
    return { ok: true, payload: session.payload, cached: true };
  }
  if (inflight) return inflight;
  inflight = (async () => {
    try {
      const { status, payload } = await keepAlive();
      if (status === 200 && payload) return { ok: true, payload };
      session.verified = false;
      if (status === 401) return { ok: false, reason: 'expired', status };
      return { ok: false, reason: `upstream_${status}`, status };
    } catch (e) {
      session.verified = false;
      return { ok: false, reason: e.message, status: 502 };
    } finally {
      inflight = null;
    }
  })();
  return inflight;
}

/** Store a cookie and prove it works before keeping it. */
export async function setCookie(input) {
  const cookie = normalizeCookie(input);
  if (!cookie) return { ok: false, reason: 'empty' };
  const previous = session;
  session = { ...session, cookie, payload: null, verified: false, at: 0 };
  const result = await refresh({ force: true });
  if (!result.ok) {
    // Do not hold a credential that does not work.
    session = previous;
    return { ok: false, reason: result.reason, status: result.status };
  }
  save();
  return { ok: true, user: session.user, playlists: result.payload?.playlists?.length ?? 0 };
}

export function clear() {
  session = { cookie: '', csrf: null, csrfProof: null, user: null, payload: null, at: 0, verified: false };
  try {
    if (fs.existsSync(STORE)) fs.rmSync(STORE);
  } catch (e) {
    console.warn('session: could not remove store —', e.message);
  }
}

/** The account payload, refreshing if it has gone cold. */
export async function account() {
  const result = await refresh();
  if (!result.ok) return { ok: false, ...result, payload: null };
  return { ok: true, payload: result.payload };
}

/** Raw payload, for inspecting shapes after a schema change. */
export function raw() {
  return session.payload;
}
