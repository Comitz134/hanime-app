// manga-api.js — a direct line to mangafire.to's public JSON API.
//
// The browser calls their API itself: their CORS is wide open
// (`access-control-allow-origin: *` on every answer, preflight included), so
// neither the Node server nor Java needs a catalog proxy — only reader pages
// do, because their image CDN refuses hotlinks without a mangafire Referer
// (see /api/manga/page).
//
// Listing requests are signed: each one carries a `vrf` token derived from
// the path and its canonical query. The token is computed by the signer
// vendored beside this file (mangafire-polyfill.js — the chunk of their own
// bundle that does it), driven exactly the way their client drives it: the
// module is handed an axios-shaped object, the request interceptor it
// registers is captured, and it is asked to sign each request we make. No
// cookies, no expiry, no shared secret — the token is deterministic, so the
// signer only has to run, not stay in sync.
//
// Requests go out as plain GETs with only the safelisted Accept header, so
// the browser never has to send a CORS preflight at all.

import mfSigner from './mangafire-polyfill.js';

/** Their signer module as it shipped: `.a` registers the request interceptor
 *  that mints the `vrf` token for each path. */
const extendClient = mfSigner.a;

const API = 'https://mangafire.to/api';

/** One request's worth of time before the fetch is given up on. */
const TIMEOUT_MS = 15000;

/** The signer's interceptor, captured the first time it is asked to sign. */
let signRequest = null;
let signerTried = false;

function signer() {
  if (signerTried) return signRequest;
  signerTried = true;
  try {
    extendClient({
      defaults: { headers: {} },
      interceptors: {
        request: { use: (fn) => { signRequest = fn; } },
        response: { use: () => {} },
      },
    });
  } catch (e) {
    signRequest = null;
  }
  return signRequest;
}

/**
 * Drop anything that would make our query string disagree with the one the
 * signer canonicalises: undefined/null, empty strings, empty arrays and
 * objects. What is signed and what is sent must be built from this same
 * object — an extra or missing key changes the token, and upstream answers
 * 403 rather than saying which.
 */
function clean(params) {
  const out = {};
  Object.keys(params).forEach((k) => {
    const v = params[k];
    if (v === undefined || v === null || v === '') return;
    if (Array.isArray(v)) { if (v.length) out[k] = v; return; }
    if (typeof v === 'object') { if (Object.keys(v).length) out[k] = v; return; }
    out[k] = v;
  });
  return out;
}

/** The query string, serialised the way axios does it: `key[]=a`, `key[sub]=v`. */
function encode(params) {
  const out = [];
  const put = (k, v) => out.push(encodeURIComponent(k) + '=' + encodeURIComponent(v == null ? '' : v));
  Object.keys(params).forEach((k) => {
    const v = params[k];
    if (Array.isArray(v)) v.forEach((x) => put(k + '[]', x));
    else if (typeof v === 'object') Object.keys(v).forEach((s) => put(k + '[' + s + ']', v[s]));
    else put(k, v);
  });
  return out.join('&');
}

/** GET one path of their API, signed, with a deadline. */
async function mfGet(path, params = {}) {
  const q = clean(params);

  let vrf = '';
  const intercept = signer();
  if (intercept) {
    try {
      const cfg = await intercept({ url: path, method: 'get', params: q, headers: {}, baseURL: '/api' });
      vrf = (cfg && cfg.params && cfg.params.vrf) || '';
    } catch (e) {
      // Signed or not, the request is still worth sending: upstream's own
      // answer (403 "Missing token.") is a clearer failure than a throw here.
      vrf = '';
    }
  }

  const parts = [];
  const qs = encode(q);
  if (qs) parts.push(qs);
  if (vrf) parts.push('vrf=' + encodeURIComponent(vrf));
  const url = API + path + (parts.length ? '?' + parts.join('&') : '');

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal: ctrl.signal,
    });
    if (!res.ok) throw new Error(`status ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

// ------------------------------------------------------------------ routes

/** The homepage shelf: trending titles. No token, no paging — one shelf. */
function mangaTrending(limit = 30) {
  return mfGet('/top-titles', { type: 'trending', days: 1, limit });
}

/**
 * The catalog: keyword and/or filters, paged. `keyword` is their search
 * parameter (not `q`), the filters ride as `types[]`, `statuses[]` and
 * `genres_in[]`, and the default order is chapter-updated descending — what
 * their own browse page shows.
 */
function mangaSearch({ q = '', page = 1, limit = 24, type = '', status = '', genre = '' } = {}) {
  const params = { page, limit };
  if (q) {
    // With a keyword, upstream ranks by relevance — sending an order instead
    // buries the match the reader typed their query for. Browsing (no keyword)
    // is ordered by latest chapter update, which is what their own page shows.
    params.keyword = q;
  } else {
    params.order = { chapter_updated_at: 'desc' };
  }
  if (type) params.types = [type];
  if (status) params.statuses = [status];
  if (genre) params.genres_in = [genre];
  return mfGet('/titles', params);
}

/** One title: synopsis, genres, ratings, languages — everything but chapters. */
function mangaDetail(hid) {
  return mfGet('/titles/' + encodeURIComponent(hid)).then((b) => b.data);
}

/**
 * The chapter list, newest first. Long series exceed one page (200 a page,
 * the upstream maximum), so pages are walked until the list is whole — five
 * pages, a thousand chapters, after which the reader sees what there is.
 * A title with no English translation answers empty for `language=en`, and
 * the same list without the language filter is still worth showing.
 */
async function mangaChapters(hid) {
  const base = '/titles/' + encodeURIComponent(hid) + '/chapters';
  const fetchPage = (n, lang) => {
    const params = { sort: 'number', order: 'desc', page: n, limit: 200 };
    if (lang) params.language = lang;
    return mfGet(base, params);
  };

  let items = [];
  let page = 1;
  let last = 1;
  let lang = 'en';
  do {
    const body = await fetchPage(page, lang);
    items = items.concat(body.items ?? []);
    last = body.meta?.lastPage ?? 1;
    page += 1;
  } while (page <= last && page <= 5);

  if (!items.length && lang) return mangaChaptersNoLang(hid);
  return items;
}

async function mangaChaptersNoLang(hid) {
  const body = await mfGet('/titles/' + encodeURIComponent(hid) + '/chapters', {
    sort: 'number', order: 'desc', page: 1, limit: 200,
  });
  return body.items ?? [];
}

/** The page list of one chapter: image URLs for the reader (proxied). */
function mangaPages(chapterId) {
  return mfGet('/chapters/' + encodeURIComponent(chapterId)).then((b) => b.data?.pages ?? []);
}

/** Types, statuses and genres with their upstream enum values — for the filters. */
function mangaFilterOptions() {
  return mfGet('/filter-options').then((b) => b.data ?? b);
}

/**
 * Where a poster is served from: their static CDN answers hotlinks fine
 * (verified 200 with no Referer), so covers are used at their real URLs —
 * only reader pages, which do refuse, go through /api/manga/page.
 */
function posterFor(item, size = 'medium') {
  const p = item?.poster ?? {};
  return p[size] || p.medium || p.large || p.small || '';
}

/** Reader images as the app loads them: through the same-origin proxy. */
function proxied(url) {
  return url ? '/api/manga/page?u=' + encodeURIComponent(url) : '';
}

export {
  mangaTrending, mangaSearch, mangaDetail, mangaChapters,
  mangaPages, mangaFilterOptions, posterFor, proxied,
};
