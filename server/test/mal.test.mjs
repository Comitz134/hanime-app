// The MyAnimeList relay with fetch stubbed — the Node twin of
// android/.../MalTest.java, pinning the same contract: everything crosses
// the pipe as QUERY PARAMETERS (Android's WebView interceptor is never
// handed a request body, so a body-based contract could not be implemented
// by the app's Java twin at all), the token route forwards only the fields
// MAL documents (plus the env-held secret, when a registration issued one),
// the API route forwards the caller's own Bearer untouched, GET parameters
// continue as MAL's query, PUT parameters become the form body MAL writes
// list status with, and a request that fails a precondition never reaches
// the network.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';

import { handleMalApi, handleMalToken } from '../src/mal.mjs';

/** A response double that records what the handler answered. */
function fakeRes() {
  return {
    status: null,
    headers: null,
    body: null,
    writeHead(status, headers) { this.status = status; this.headers = headers; },
    end(text) { this.body = text; this.json = JSON.parse(text); },
  };
}

/** A request double: method and headers; the pipe never reads a body. */
function fakeReq(method, headers) {
  const req = Readable.from([]);
  req.method = method;
  req.headers = headers ?? {};
  return req;
}

const realFetch = globalThis.fetch;
let fetchLog = [];

function stubFetch(routes) {
  fetchLog = [];
  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    fetchLog.push({ url: u, opts });
    for (const [match, reply] of routes) {
      if (u.includes(match)) {
        const { status, body, headers } = typeof reply === 'function' ? reply(u, opts) : reply;
        return {
          ok: status >= 200 && status < 300,
          status,
          headers: new Map(Object.entries(headers ?? { 'content-type': 'application/json' })),
          text: async () => JSON.stringify(body),
        };
      }
    }
    throw new Error(`unstubbed fetch: ${u}`);
  };
}

const tokenUrl = (query) => new URL(`http://localhost/api/mal/token?${query}`);

afterEach(() => {
  globalThis.fetch = realFetch;
  delete process.env.MAL_CLIENT_SECRET;
});

// ------------------------------------------------------------ POST /token

test('the token route forwards exactly the fields MAL documents', async () => {
  stubFetch([['myanimelist.net/v1/oauth2/token', {
    status: 200,
    body: { token_type: 'Bearer', expires_in: 3600, access_token: 'a', refresh_token: 'r' },
  }]]);

  const res = fakeRes();
  await handleMalToken(tokenUrl(new URLSearchParams({
    client_id: 'cid123',
    grant_type: 'authorization_code',
    code: 'the-code',
    code_verifier: 'v'.repeat(43),
    redirect_uri: 'https://hanime.tv/',
    evil: 'must not pass',
  }).toString()), fakeReq('POST'), res);

  assert.equal(res.status, 200);
  assert.equal(fetchLog.length, 1);
  assert.ok(fetchLog[0].url.includes('myanimelist.net/v1/oauth2/token'));
  // Inbound is the query; onward to MAL it is the form those docs specify.
  const sent = fetchLog[0].opts.body;
  assert.ok(sent.includes('client_id=cid123'));
  assert.ok(sent.includes('grant_type=authorization_code'));
  assert.ok(sent.includes('code=the-code'));
  assert.ok(sent.includes('code_verifier='));
  assert.ok('the redirect is form-encoded',
    sent.includes('redirect_uri=https%3A%2F%2Fhanime.tv%2F'));
  assert.ok(!sent.includes('evil'), 'an undocumented field leaked into the form');
  assert.equal(fetchLog[0].opts.headers['content-type'],
    'application/x-www-form-urlencoded');
});

test('an env-held client secret rides only on the token route', async () => {
  process.env.MAL_CLIENT_SECRET = 'topsecret';
  stubFetch([['myanimelist.net/v1/oauth2/token', { status: 200, body: { access_token: 'a' } }]]);

  const res = fakeRes();
  await handleMalToken(
    tokenUrl('client_id=c&grant_type=refresh_token&refresh_token=old'),
    fakeReq('POST'),
    res,
  );

  assert.equal(res.status, 200);
  assert.ok(fetchLog[0].opts.body.includes('client_secret=topsecret'));
});

test('a grant MAL does not document is refused before the network', async () => {
  stubFetch([]);
  const res = fakeRes();
  await handleMalToken(tokenUrl('client_id=c&grant_type=password'), fakeReq('POST'), res);
  assert.equal(res.status, 400);
  assert.equal(res.json.error, 'unsupported_grant_type');
  assert.equal(fetchLog.length, 0, 'a refusal must not reach the network');
});

test('a half-finished exchange is refused before the network', async () => {
  stubFetch([]);
  const res = fakeRes();
  await handleMalToken(
    tokenUrl('client_id=c&grant_type=authorization_code&code=x'),
    fakeReq('POST'),
    res,
  );
  assert.equal(res.status, 400);
  assert.equal(res.json.error, 'code_verifier_and_redirect_required');
  assert.equal(fetchLog.length, 0);
});

test('the token route is POST-only', async () => {
  stubFetch([]);
  const res = fakeRes();
  await handleMalToken(tokenUrl('client_id=c'), fakeReq('GET'), res);
  assert.equal(res.status, 405);
  assert.equal(fetchLog.length, 0);
});

// ------------------------------------------------------------ /mal/v2/* pipe

test('a GET continues as the query, with the callers Bearer', async () => {
  stubFetch([['api.myanimelist.net', { status: 200, body: { name: 'me' } }]]);
  const res = fakeRes();
  await handleMalApi(
    new URL('http://localhost/api/mal/v2/users/@me?fields=name'),
    fakeReq('GET', { authorization: 'Bearer abc123' }),
    res,
    '/api/mal/v2/users/@me',
  );

  assert.equal(res.status, 200);
  assert.equal(res.json.name, 'me');
  assert.equal(fetchLog[0].url,
    'https://api.myanimelist.net/v2/users/@me?fields=name');
  assert.equal(fetchLog[0].opts.headers.authorization, 'Bearer abc123');
  assert.equal(fetchLog[0].opts.body, undefined, 'a GET carries no body');
});

test('PUT parameters become the form body MAL writes list status with', async () => {
  stubFetch([['api.myanimelist.net', { status: 200, body: { status: 'watching' } }]]);
  const res = fakeRes();
  await handleMalApi(
    new URL('http://localhost/api/mal/v2/anime/21/my_list_status'
      + '?status=watching&score=8&num_watched_episodes=5&blank='),
    fakeReq('PUT', { authorization: 'Bearer t' }),
    res,
    '/api/mal/v2/anime/21/my_list_status',
  );

  assert.equal(res.status, 200);
  assert.equal(fetchLog[0].opts.method, 'PUT');
  assert.ok(!fetchLog[0].url.includes('?'),
    'the fields must reach MAL as the body, not twice as the query');
  const sent = fetchLog[0].opts.body;
  assert.ok(sent.includes('status=watching'));
  assert.ok(sent.includes('score=8'));
  assert.ok(sent.includes('num_watched_episodes=5'));
  assert.ok(!sent.includes('blank'), 'an empty value must be omitted');
  assert.equal(fetchLog[0].opts.headers['content-type'],
    'application/x-www-form-urlencoded');
});

test('the pipe is closed to callers with no token', async () => {
  stubFetch([]);
  const noAuth = fakeRes();
  await handleMalApi(
    new URL('http://localhost/api/mal/v2/users/@me'),
    fakeReq('GET', {}),
    noAuth,
    '/api/mal/v2/users/@me',
  );
  assert.equal(noAuth.status, 401);
  assert.equal(fetchLog.length, 0, 'a refusal must not reach the network');

  const wrongScheme = fakeRes();
  await handleMalApi(
    new URL('http://localhost/api/mal/v2/users/@me'),
    fakeReq('GET', { authorization: 'Basic abc' }),
    wrongScheme,
    '/api/mal/v2/users/@me',
  );
  assert.equal(wrongScheme.status, 401);
  assert.equal(fetchLog.length, 0);
});

test('anything that is not v2 is not this pipe', async () => {
  stubFetch([]);
  const res = fakeRes();
  await handleMalApi(
    new URL('http://localhost/api/mal/v1/legacy'),
    fakeReq('GET', { authorization: 'Bearer t' }),
    res,
    '/api/mal/v1/legacy',
  );
  assert.equal(res.status, 404);
  assert.equal(fetchLog.length, 0);
});

test('a foreign origin cannot borrow the relay', async () => {
  stubFetch([['api.myanimelist.net', { status: 200, body: {} }]]);
  const res = fakeRes();
  await handleMalApi(
    new URL('http://localhost/api/mal/v2/users/@me'),
    fakeReq('GET', {
      origin: 'https://evil.example',
      host: 'localhost:8787',
      authorization: 'Bearer t',
    }),
    res,
    '/api/mal/v2/users/@me',
  );
  assert.equal(res.status, 403);
  assert.equal(fetchLog.length, 0, 'the relay was borrowed cross-origin');

  // The same origin the server answers under — what the web copy sends —
  // passes, and a non-browser caller that sends no Origin at all passes too.
  const own = fakeRes();
  await handleMalApi(
    new URL('http://localhost/api/mal/v2/users/@me'),
    fakeReq('GET', {
      origin: 'http://localhost:8787',
      host: 'localhost:8787',
      authorization: 'Bearer t',
    }),
    own,
    '/api/mal/v2/users/@me',
  );
  assert.equal(own.status, 200);
  assert.equal(fetchLog.length, 1);
});
