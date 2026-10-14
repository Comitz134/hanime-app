// mal.mjs — MyAnimeList relay: the PKCE token exchange and the API pipe.
//
// MyAnimeList speaks no CORS — neither myanimelist.net/v1/oauth2/token nor
// api.myanimelist.net answers an Origin (a preflight is met with 405), which
// was verified against the live endpoints — so a browser or a WebView can
// only reach MAL through a server. These handlers are a pipe, not a store:
//
//   POST /api/mal/token?...    one PKCE exchange, fields allowlisted. A
//                              confidential client's secret (if the
//                              registration issued one) is added from env
//                              MAL_CLIENT_SECRET and never travels in a
//                              bundle.
//   *    /api/mal/v2/<...>?..  forwards the caller's own Bearer token.
//
// The contract is deliberately QUERY-ONLY — no request bodies anywhere.
// Android's WebView interceptor is never handed the request body of an
// intercepted request, so the app's Java twin could not implement a
// body-based contract at all; one contract that both backends can honour
// beats two that nearly match. GET parameters ride on to MAL as the query,
// PUT parameters become the form body MAL writes list status with.
//
// Nothing is written to disk: the tokens belong to the client that earned
// them. Both routes refuse cross-origin browser callers so this server can
// never be borrowed as an open relay (a foreign page could otherwise burn
// this machine's MAL quota — limits are shared per IP).

const TOKEN_ENDPOINT = 'https://myanimelist.net/v1/oauth2/token';
const API_BASE = 'https://api.myanimelist.net';
const TIMEOUT_MS = 15_000;

function json(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
  });
  res.end(text);
}

/**
 * A browser caller must share this server's origin; a non-browser caller
 * (the app's own tests, curl, the Node suite) sends no Origin at all and is
 * the relay's normal traffic.
 */
function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  const host = req.headers['x-forwarded-host'] ?? req.headers.host ?? '';
  const proto = req.headers['x-forwarded-proto'] ?? 'http';
  return origin === `${proto}://${host}`;
}

/** Relay one MAL answer back verbatim — status, body, content-type. */
function pipe(res, upstream, body) {
  res.writeHead(upstream.status, {
    'content-type': upstream.headers.get('content-type') ?? 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
  });
  res.end(body);
}

/**
 * POST /api/mal/token?client_id=&grant_type=... — the authorization-code
 * exchange and the refresh, through one door. Only the fields MAL documents
 * are ever forwarded, so a caller cannot smuggle anything else into the
 * form.
 */
export async function handleMalToken(url, req, res) {
  if ((req.method ?? 'GET').toUpperCase() !== 'POST') {
    return json(res, 405, { error: 'method_not_allowed' });
  }
  if (!sameOrigin(req)) return json(res, 403, { error: 'origin_not_allowed' });

  const q = url.searchParams;
  const clientId = q.get('client_id') ?? '';
  const grant = q.get('grant_type') ?? '';
  if (!clientId) return json(res, 400, { error: 'client_id_required' });
  if (grant !== 'authorization_code' && grant !== 'refresh_token') {
    return json(res, 400, { error: 'unsupported_grant_type' });
  }

  const form = new URLSearchParams();
  form.set('client_id', clientId);
  form.set('grant_type', grant);
  if (grant === 'authorization_code') {
    for (const key of ['code', 'code_verifier', 'redirect_uri']) {
      const value = q.get(key);
      if (value) form.set(key, value);
    }
    if (!form.has('code') || !form.has('code_verifier') || !form.has('redirect_uri')) {
      return json(res, 400, { error: 'code_verifier_and_redirect_required' });
    }
  } else {
    const refresh = q.get('refresh_token');
    if (!refresh) return json(res, 400, { error: 'refresh_token_required' });
    form.set('refresh_token', refresh);
  }
  // A confidential registration keeps its secret here, in env — Scheme 2 of
  // MAL's token docs, client credentials in the request body.
  if (process.env.MAL_CLIENT_SECRET) form.set('client_secret', process.env.MAL_CLIENT_SECRET);

  const upstream = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      accept: 'application/json',
    },
    body: form.toString(),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  return pipe(res, upstream, await upstream.text());
}

// ------------------------------------------------------------ desktop leg
//
// The desktop app is a native window, not a WebView, so it cannot watch the
// redirect MAL follows. What it can do is open the system browser at the
// authorize URL, and the redirect lands on the one origin both halves share:
// this server (`http://localhost:8787/` is a registered redirect URI on the
// MAL application — verified live on 2026-10-10). The browser arrives here
// holding `?code=…&state=…`; the app polls for that state and takes the code.
//
// The state is the whole handover's security: it is minted by the app, shown
// to MAL, and comes back on the redirect, so only the app that started the
// flow can collect its code. Nothing here is persisted — an entry dies at its
// TTL or on first read, whichever comes first.

const pendingCodes = new Map();  // state -> { code, expires }
const PENDING_TTL_MS = 10 * 60_000;
const PENDING_MAX = 20;

/** Only the tests call this, and only so one case cannot seed the next. */
export function clearPendingDesktopCodes() {
  pendingCodes.clear();
}

/**
 * The bounds on a redirect this server will believe. The route is reachable
 * by anything on this machine, so the stash is bounded — but the bound has to
 * fit what MAL itself sends, or a real login is thrown away.
 *
 * MAL's authorization code is opaque and long: a live login on 2026-10-11
 * arrived with a 898-character code. The state is this project's own
 * identifier (the app mints 54 characters; the web client's is comparable),
 * so it stays tight.
 */
const STATE_MAX = 256;
const CODE_MAX = 4096;

/**
 * Remember a code the browser dropped at the door. A state of an absurd
 * length is not a redirect MAL produced (it caps `state`), so it is not
 * believed — the route that calls this is reachable by anything local.
 */
export function stashDesktopCode(state, code) {
  const s = typeof state === 'string' ? state.trim() : '';
  const c = typeof code === 'string' ? code.trim() : '';
  if (!s || !c || s.length > STATE_MAX || c.length > CODE_MAX) {
    // Loud, because the alternative is silent: the browser gets a page and
    // the app polls forever for a code this server threw away.
    console.warn(
      `mal: refused a redirect-shaped payload (state ${s.length}B, code ${c.length}B)`,
    );
    return false;
  }
  if (pendingCodes.size >= PENDING_MAX) {
    const now = Date.now();
    for (const [key, entry] of pendingCodes) if (entry.expires <= now) pendingCodes.delete(key);
    while (pendingCodes.size >= PENDING_MAX) pendingCodes.delete(pendingCodes.keys().next().value);
  }
  pendingCodes.set(s, { code: c, expires: Date.now() + PENDING_TTL_MS });
  return true;
}

/**
 * GET /api/mal/desktop-callback?state=… — the code the browser dropped, for
 * the one app that minted that state. One read, then it is gone: a second
 * poll is a 404, which is also what a wrong or expired state answers. The
 * absence of a code is not an error the app should shout about — the person
 * may still be reading MAL's consent page — so it is a plain 404.
 */
export async function handleMalDesktopCallback(url, res) {
  const state = (url.searchParams.get('state') ?? '').trim();
  const entry = state ? pendingCodes.get(state) : null;
  if (state) pendingCodes.delete(state);   // single-use, matched or not
  if (!entry || entry.expires <= Date.now()) {
    return json(res, 404, { error: 'none_pending' });
  }
  return json(res, 200, { code: entry.code, state });
}

/**
 * The marker a desktop login's state carries.
 *
 * The desktop app and the web client are both registered against this same
 * origin as their redirect URI, so the redirect alone cannot say which one a
 * login belongs to — the state can, because the client mints it. The app
 * prefixes its own (see MalApi.DESKTOP_STATE_PREFIX); the web client does
 * not. Without this, a desktop login lands on the web client, whose callback
 * handler sees a state it never minted and reports a mismatch for a login
 * that was never its business.
 */
export const DESKTOP_STATE_PREFIX = 'sekai-';

export function isDesktopState(state) {
  return typeof state === 'string' && state.startsWith(DESKTOP_STATE_PREFIX);
}

/** The page a finished desktop login lands on: the browser's part is done, so it says so. */
export function sendDesktopSignedIn(res) {
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Signed in \u00b7 SEKAI</title>
<style>
  html, body { height: 100%; margin: 0; }
  body {
    display: grid; place-items: center; background: #0b0b0c; color: #f2f2f3;
    font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  }
  main { max-width: 30rem; padding: 2rem; text-align: center; }
  .tick {
    width: 3.5rem; height: 3.5rem; margin: 0 auto 1.25rem; border-radius: 50%;
    display: grid; place-items: center; background: #2e51a2; font-size: 1.75rem;
  }
  h1 { font-size: 1.25rem; margin: 0 0 .5rem; }
  p { margin: 0; color: #b9b9bd; }
</style>
</head>
<body>
<main>
  <div class="tick" aria-hidden="true">\u2713</div>
  <h1>Signed in to MyAnimeList</h1>
  <p>You can close this tab and return to SEKAI.</p>
</main>
</body>
</html>`;
  res.writeHead(200, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': Buffer.byteLength(html),
    'cache-control': 'no-store',
  });
  res.end(html);
}

/**
 * The page a desktop login lands on when its code could not be held. It says
 * so plainly and points back at the app, rather than claiming a success the
 * app will never see.
 */
export function sendDesktopStashFailed(res) {
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sign-in not completed \u00b7 SEKAI</title>
<style>
  html, body { height: 100%; margin: 0; }
  body {
    display: grid; place-items: center; background: #0b0b0c; color: #f2f2f3;
    font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  }
  main { max-width: 30rem; padding: 2rem; text-align: center; }
  .mark {
    width: 3.5rem; height: 3.5rem; margin: 0 auto 1.25rem; border-radius: 50%;
    display: grid; place-items: center; background: #2e51a2; font-size: 1.75rem;
  }
  h1 { font-size: 1.25rem; margin: 0 0 .5rem; }
  p { margin: 0; color: #b9b9bd; }
</style>
</head>
<body>
<main>
  <div class="mark" aria-hidden="true">!</div>
  <h1>SEKAI could not finish this sign-in</h1>
  <p>The app is still waiting. Please start the connection again from SEKAI &mdash; try again.</p>
</main>
</body>
</html>`;
  res.writeHead(200, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': Buffer.byteLength(html),
    'cache-control': 'no-store',
  });
  res.end(html);
}

/**
 * A desktop login landing on the shared redirect origin: stash the code for the
 * app to collect and answer with the sign-in page, so the web client — which
 * would run its own callback against a state it never minted — is not loaded at
 * all. Returns true when it answered the request.
 */
export function handleDesktopLanding(url, res) {
  if (url.pathname !== '/') return false;
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  if (!code || !state || !isDesktopState(state)) return false;
  // The page must reflect what actually happened: claiming "you can close
  // this tab" while the code was refused is what made a refused login look
  // like a stalled one, with nothing anywhere to say why.
  if (stashDesktopCode(state, code)) sendDesktopSignedIn(res);
  else sendDesktopStashFailed(res);
  return true;
}

const FORWARDABLE = new Set(['GET', 'POST', 'PUT', 'DELETE']);

/**
 * /api/mal/v2/<rest>?... — one hop onto api.myanimelist.net with the
 * caller's Authorization header. GET parameters continue as MAL's query;
 * PUT and POST parameters become the form body (MAL's list status is
 * written as form fields, and the query on the forwarded URL is dropped so
 * the two spellings of one value can never disagree).
 */
export async function handleMalApi(url, req, res, pathname) {
  if (!sameOrigin(req)) return json(res, 403, { error: 'origin_not_allowed' });

  const method = (req.method ?? 'GET').toUpperCase();
  if (!FORWARDABLE.has(method)) return json(res, 405, { error: 'method_not_allowed' });

  const rest = pathname.slice('/api/mal/'.length);
  if (!rest.startsWith('v2/')) return json(res, 404, { error: 'not_found' });

  const authorization = req.headers.authorization ?? '';
  if (!/^Bearer\s+\S+/i.test(authorization)) {
    return json(res, 401, { error: 'token_required' });
  }

  const headers = {
    authorization,
    accept: 'application/json',
  };
  const writesBody = method === 'PUT' || method === 'POST';

  let target = `${API_BASE}/${rest}`;
  let body;
  if (writesBody) {
    const form = new URLSearchParams();
    for (const [key, value] of url.searchParams) {
      if (value === '') continue;
      form.set(key, value);
    }
    headers['content-type'] = 'application/x-www-form-urlencoded';
    body = form.toString();
  } else if (url.search) {
    target += url.search;
  }

  const upstream = await fetch(target, {
    method,
    headers,
    ...(body !== undefined ? { body } : {}),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  return pipe(res, upstream, await upstream.text());
}
