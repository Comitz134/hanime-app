// Films and series (the "movies" area), sourced from f-movies.org.
//
// The site is two halves. Its catalog is a TMDB proxy —
// `/api/search?q=` plus static `/movies` and `/tv-series` listing pages whose
// cards are `image.tmdb.org` URLs and `★` vote averages — so search is exact
// and JSON, cheaper than scraping results markup. Its detail and episode
// pages are static HTML: a JSON-LD block (Movie/TVSeries with name,
// description, poster, genres, cast, `dateCreated` and, for series,
// `numberOfSeasons`) plus one `<a href="?season=S&episode=N" title="…">` per
// episode and a `?season=` link per season.
//
// Playback does not need scraping at all. The page builds three embed URLs
// from the numeric id in the slug (`…-1396`), the season and the episode —
// embos, vidcore, vidapi — with only the Referer varying per server. So the
// player route is a pure template fill; it cannot break the way a stored
// iframe URL would.
//
// Everything is parsed from the site's real markup shape (verified against
// live pages on 2026-10-09). No client-side keys, no account, and the same
// self-contained cache discipline as the anime module: details and episodes
// change slowly, search quickly, and a rejected fetch is evicted instead of
// being remembered as an answer.

const SITE = 'https://www.f-movies.org';
const TIMEOUT_MS = 12_000;
const SEARCH_PAGE_SIZE = 20;   // upstream's fixed `/api/search` limit

// The three embeds the page itself advertises (`server=1..3`).
const SERVERS = [
  {
    id: 'embos', label: 'Server 1',
    movie: 'https://vidsrc.cc/embed/movie/{id}?auto=true&server=1&color=ef4444&ref=www.f-movies.org',
    tv: 'https://vidsrc.cc/embed/tv/{id}/{season}/{episode}?auto=true&server=1&color=ef4444&ref=www.f-movies.org',
  },
  {
    id: 'vidcore', label: 'Server 2',
    movie: 'https://vidsrc.xyz/embed/movie/{id}?server=2&color=ef4444&ref=www.f-movies.org',
    tv: 'https://vidsrc.xyz/embed/tv/{id}/{season}/{episode}?server=2&color=ef4444&ref=www.f-movies.org',
  },
  {
    id: 'vidapi', label: 'Server 3',
    movie: 'https://vidapi.to/embed/movie/{id}?ref=www.f-movies.org&color=ef4444&s=1',
    tv: 'https://vidapi.to/embed/tv/{id}/{season}/{episode}?ref=www.f-movies.org&color=ef4444&s=1',
  },
];

const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

// ------------------------------------------------------------------ cache

const TTL = {
  search: 15 * 60_000,     // results move with the catalog
  catalog: 15 * 60_000,    // the /movies and /tv-series listing pages
  details: 6 * 3600_000,   // synopses and season lists are near-static
  episodes: 6 * 3600_000,
};
const cache = new Map();

/**
 * memo(kind, key, produce) — same contract as the anime module's cached():
 * the first caller pays for the fetch, everyone inside the TTL shares the
 * answer. A rejection is not an answer: it is evicted immediately, so one
 * upstream blip costs a single retry instead of six hours of failure.
 */
function memo(kind, key, produce) {
  const hit = cache.get(key);
  if (hit && hit.expires > Date.now()) return hit.value;
  const value = (async () => {
    try {
      return await produce();
    } catch (e) {
      cache.delete(key);
      throw e;
    }
  })();
  cache.set(key, { value, expires: Date.now() + TTL[kind] });
  return value;
}

// ---------------------------------------------------------------- upstream

function upstreamError(status, note) {
  const e = new Error(`f-movies: ${note ?? `status ${status}`}`);
  e.status = status === 404 ? 404 : 502;
  return e;
}

async function get(path, accept) {
  let res;
  try {
    res = await fetch(SITE + path, {
      headers: {
        'user-agent': BROWSER_UA,
        accept,
        'accept-language': 'en-US,en;q=0.9',
      },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (e) {
    throw upstreamError(502, e.message);
  }
  if (res.status === 404) throw upstreamError(404);
  if (!res.ok) throw upstreamError(res.status);
  return res;
}

const getHtml = (path) => get(path, 'text/html,application/xhtml+xml').then((r) => r.text());
const getJson = (path) => get(path, 'application/json').then((r) => r.json());

// ------------------------------------------------------------------ parsing

const ENTITIES = {
  '&amp;': '&', '&#38;': '&', '&quot;': '"', '&#34;': '"', '&#39;': "'",
  '&apos;': "'", '&lt;': '<', '&gt;': '>', '&nbsp;': ' ',
};
function decode(text) {
  return String(text)
    .replace(/&(?:amp|#38|quot|#34|#39|apos|lt|gt|nbsp);/g, (m) => ENTITIES[m] ?? m)
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)));
}
const asList = (v) => (Array.isArray(v) ? v : v == null ? [] : [v]);
const personName = (v) => (typeof v === 'string' ? v : v?.name ?? null);

/**
 * The JSON-LD block the detail page ships (Movie or TVSeries node, possibly
 * inside an @graph). Returns the node or null; a malformed block is a
 * missing field, never a crash.
 */
function parseJsonLd(html) {
  const scripts = html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g);
  for (const m of scripts) {
    let data;
    try {
      data = JSON.parse(m[1]);
    } catch {
      continue;                       // not the block we want
    }
    const nodes = Array.isArray(data) ? data : (data['@graph'] ?? [data]);
    const node = nodes.find((n) => n && (n['@type'] === 'Movie' || n['@type'] === 'TVSeries'));
    if (node) return node;
  }
  return null;
}

/** `?season=N&episode=…` occurrences → the season numbers the page offers. */
function parseSeasons(html) {
  const set = new Set();
  for (const m of html.matchAll(/\?season=(\d+)(?:&#38;|&amp;|&)episode=\d+/g)) set.add(Number(m[1]));
  return [...set].sort((a, b) => a - b);
}

/** Episode anchors for one season, in page order. */
function parseEpisodes(html, season) {
  const re = new RegExp(`\\?season=${season}(?:&#38;|&amp;|&)episode=(\\d+)(?:&#38;|&amp;|&)?[^"]*"[^>]*`, 'g');
  const out = new Map();
  for (const m of html.matchAll(re)) {
    const attrs = m[0];
    const n = Number(m[1]);
    const title = /title="([^"]*)"/.exec(attrs);
    // Episode 1 appears twice in the page: once as the active season's
    // jump button in the nav (no title) and once as the episode row itself
    // (titled). The titled occurrence is the real one — a duplicate never
    // overwrites a title, and never replaces a row that has one.
    const prev = out.get(n);
    if (prev && (prev.title !== null || !title)) continue;
    out.set(n, { n, title: title ? decode(title[1]) : null });
  }
  return [...out.values()].sort((a, b) => a.n - b.n);
}

/**
 * Cards in a listing page. Each card is an <a href="/movie/slug" …> block
 * whose text row carries `★ <score> · <year> · EN`. Windows run from one
 * detail link to the next, so the rating row — which sits after the card
 * article, inside the anchor — belongs to the right card.
 */
function parseCards(html) {
  const links = [...html.matchAll(/<a href="\/(movie|tv)\/([a-z0-9-]+-\d+)"[^>]*>/g)];
  const items = [];
  for (let i = 0; i < links.length; i += 1) {
    const [, type, slug] = links[i];
    const window = html.slice(links[i].index, links[i + 1]?.index ?? links[i].index + 4000);
    const alt = /alt="([^"]*)"/.exec(window);
    const img = /src="(https:\/\/image\.tmdb\.org[^"]+)"/.exec(window);
    // The score sits right after the star in the title line (`★ 5.9`) but
    // inside the *next* span in the meta row (`★</span><span>5.9</span>`) —
    // both shapes occur on the same page, so accept either.
    const star = /★(?:<\/span>\s*(?:<span[^>]*>)?)?\s*([\d.]+)/.exec(window);
    const year = />·<\/span>\s*<span>((?:19|20)\d{2})<\/span>/.exec(window);
    items.push({
      type,
      slug,
      title: alt ? decode(alt[1]) : slug.replace(/-\d+$/, '').replace(/-/g, ' '),
      year: year ? Number(year[1]) : null,
      score: star ? Number(star[1]) : null,
      poster: img ? img[1] : null,
    });
  }
  return items;
}

/** TMDB-proxy search row → the same card shape as parseCards. */
function shapeSearchItem(raw) {
  const path = String(raw?.href ?? '');
  const m = /^\/(movie|tv)\/([a-z0-9-]+-\d+)$/.exec(path);
  if (!m) return null;                             // anything else is not a title
  return {
    type: m[1],
    slug: m[2],
    title: decode(raw.title || raw.name || 'Untitled'),
    year: Number(String(raw.release_date || raw.first_air_date || '').slice(0, 4)) || null,
    score: typeof raw.vote_average === 'number' ? raw.vote_average : null,
    poster: raw.poster_url ? String(raw.poster_url).replace('/w92/', '/w500/') : null,
  };
}

/** The detail page's own IMDb row (`<dt>IMDb:</dt><dd>4.1</dd>`). */
function parseImdbRow(html) {
  const m = /IMDb:<\/dt>\s*<dd[^>]*>([\d.]+)</.exec(html);
  return m ? Number(m[1]) : null;
}

/**
 * A detail page → the details object the client renders: JSON-LD fields
 * where they exist, the IMDb row for the score, and (series only) the season
 * numbers the page offers so the strip needs no second request to draw.
 */
function shapeDetails(type, slug, html) {
  const ld = parseJsonLd(html) ?? {};
  const ogTitle = /<meta property="og:title" content="([^"]*)"/.exec(html);
  return {
    type,
    slug,
    title: decode(ld.name ?? ogTitle?.[1] ?? slug.replace(/-\d+$/, '').replace(/-/g, ' ')),
    description: ld.description ? decode(String(ld.description).replace(/<[^>]*>/g, '')) : null,
    poster: ld.image ?? null,
    year: ld.dateCreated ? Number(String(ld.dateCreated).slice(0, 4)) : null,
    score: parseImdbRow(html),
    genres: asList(ld.genre).map((g) => decode(String(g))),
    cast: asList(ld.actor).map(personName).filter(Boolean).map(decode),
    creator: asList(ld.creator).map(personName).filter(Boolean).map(decode),
    seasons: type === 'tv' ? parseSeasons(html) : [],
    numberOfSeasons: ld.numberOfSeasons ?? null,
    numberOfEpisodes: ld.numberOfEpisodes ?? null,
  };
}

// ---------------------------------------------------------------- handlers

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
 * GET /api/fmovies/search?q=&page=&type=
 * With a q: the site's TMDB search, optionally narrowed to movie or tv.
 * Without one: its `/movies` (default) or `/tv-series` listing page.
 */
async function handleSearch(url, res) {
  const q = (url.searchParams.get('q') ?? '').trim();
  const page = Math.max(1, Number(url.searchParams.get('page') ?? 1) | 0);
  const type = url.searchParams.get('type') === 'tv' ? 'tv' : url.searchParams.get('type') === 'movie' ? 'movie' : '';

  if (q) {
    const body = await memo('search', `s:${q}:${page}`, () =>
      getJson(`/api/search?q=${encodeURIComponent(q)}&page=${page}&limit=${SEARCH_PAGE_SIZE}`));
    const all = (body.results ?? []).map(shapeSearchItem).filter(Boolean);
    const items = type ? all.filter((i) => i.type === type) : all;
    return json(res, 200, { q, page, type, hasNext: all.length === SEARCH_PAGE_SIZE, items });
  }

  const path = type === 'tv' ? '/tv-series' : '/movies';
  const html = await memo('catalog', `c:${type}:${page}`, () => getHtml(`${path}?page=${page}`));
  const items = parseCards(html);
  return json(res, 200, {
    q, page, type: type || 'movie',
    hasNext: html.includes(`?page=${page + 1}`),
    items,
  });
}

/** GET /api/fmovies/(movie|tv)/:slug — one title. */
async function handleDetails(type, slug, res) {
  const html = await memo('details', `d:${type}:${slug}`, () => getHtml(`/${type}/${slug}`));
  json(res, 200, { details: shapeDetails(type, slug, html) });
}

/**
 * GET /api/fmovies/tv/:slug/episodes?season=N — the episode rows of one
 * season. The page is fetched at `?season=N&episode=1`; a season the page
 * does not offer is a 404 rather than silently the first season's list.
 */
async function handleEpisodes(slug, url, res) {
  const season = Math.max(1, Number(url.searchParams.get('season') ?? 1) | 0);
  const html = await memo('episodes', `e:${slug}:${season}`, () =>
    getHtml(`/tv/${slug}?season=${season}&episode=1`));
  if (!parseSeasons(html).includes(season)) {
    return json(res, 404, { error: 'season_not_found', slug, season });
  }
  const data = parseEpisodes(html, season);
  if (!data.length) return json(res, 404, { error: 'no_episodes', slug, season });
  json(res, 200, { slug, season, data });
}

/**
 * GET /api/fmovies/(movie|tv)/:slug/player?season=&episode= — the three
 * embeds the site itself would offer. Pure template fill from the numeric id
 * in the slug, so it costs no upstream request and cannot go stale.
 */
function handlePlayer(type, slug, url, res) {
  const idMatch = /-(\d+)$/.exec(slug);
  if (!idMatch) return json(res, 404, { error: 'not_found', slug });
  const id = Number(idMatch[1]);
  const season = Math.max(1, Number(url.searchParams.get('season') ?? 1) | 0);
  const episode = Math.max(1, Number(url.searchParams.get('episode') ?? 1) | 0);
  const sources = SERVERS.map((s) => ({
    label: s.label,
    url: (type === 'tv' ? s.tv : s.movie)
      .replaceAll('{id}', String(id))
      .replaceAll('{season}', String(season))
      .replaceAll('{episode}', String(episode)),
  }));
  json(res, 200, { type, slug, id, season, episode, sources });
}

/** One entry point for the whole /api/fmovies family. */
export async function handleFmovies(url, res, pathname) {
  if (pathname === '/api/fmovies/search') return handleSearch(url, res);

  const m = /^\/api\/fmovies\/(movie|tv)\/([a-z0-9.-]+)(?:\/(episodes|player))?$/.exec(pathname);
  if (m) {
    const [, type, slug, sub] = m;
    if (sub === 'episodes') {
      if (type !== 'tv') return json(res, 404, { error: 'not_found', pathname });
      return handleEpisodes(slug, url, res);
    }
    if (sub === 'player') return handlePlayer(type, slug, url, res);
    return handleDetails(type, slug, res);
  }
  return json(res, 404, { error: 'not_found', pathname });
}
