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
