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
// Playback is the site's own player page, and the doors it offers are read
// from the site rather than hard-coded. That page (`/watch/index.html`) names
// its three servers as `sources = [...]` — embos, vidcore, vidapi — each as a
// URL template filled with the numeric id in the slug (`…-1396`), the season
// and the episode. The hosts behind those names rotate, though: the previous
// three (vidsrc.cc, vidsrc.xyz, vidapi.to) all died within a day of being
// recorded here — a 522 from the edge, a domain with no DNS left, and a
// Turnstile gate — and every one of them is a black frame in the player. So
// the names, and the health of the hosts behind them, are asked of the site
// every time (with the list below as the fallback for the day its page cannot
// be read), and a door that does not answer is labelled as such instead of
// being offered as a way to watch.
//
// Everything is parsed from the site's real markup shape (verified against
// live pages on 2026-10-09). No client-side keys, no account, and the same
// self-contained cache discipline as the anime module: details and episodes
// change slowly, search quickly, and a rejected fetch is evicted instead of
// being remembered as an answer.

const SITE = 'https://www.f-movies.org';
const TIMEOUT_MS = 12_000;
const SEARCH_PAGE_SIZE = 20;   // upstream's fixed `/api/search` limit

// The player page that names the doors, and the list to fall back on when it
// cannot be read. Any id answers here: the page's source table does not depend
// on what was asked for.
const WATCH_PAGE = '/watch/index.html?type=tv&id=1&server=1&season=1&episode=1';

import { browserAvailable, resolveDoor } from './browser.mjs';

// The doors to fall back on when that page cannot be read, verified against
// the live page on 2026-10-10 — in our own order, for the reason below.
const SERVERS = [
  {
    id: 'vidcore', label: 'Server 1',
    movie: 'https://vidcore.net/movie/{id}',
    tv: 'https://vidcore.net/tv/{id}/{season}/{episode}',
  },
  {
    id: 'vidapi', label: 'Server 2',
    movie: 'https://vidapi.xyz/embed/movie/{id}',
    tv: 'https://vidapi.xyz/embed/tv/{id}/{season}/{episode}',
  },
  {
    id: 'embos', label: 'Server 3',
    movie: 'https://embos.top/movie/?mid={id}',
    tv: 'https://embos.top/tv/?mid={id}&s={season}&e={episode}',
  },
];

/**
 * Which door leads, when the site's own page does not get to say.
 *
 * The site lists embos first, and embos is not a player: it is a picker page
 * that frames one of several providers of its own choosing, and it does not
 * say which. On the phone it landed on one that answers 404, twice in a row,
 * for the same episode that plays through the other two doors — a door that
 * answers our knock and still leaves the reader with nothing. The two that
 * render their own player therefore lead, and the picker comes last. A door the
 * site names that is not in this list keeps its own relative order, after
 * these; a list that names none of them keeps the site's order entirely.
 */
const DOOR_ORDER = ['vidcore', 'vidapi', 'embos'];

function orderDoors(list) {
  const rank = (id) => {
    const i = DOOR_ORDER.indexOf(id);
    return i === -1 ? DOOR_ORDER.length : i;
  };
  return list
    .map((door, at) => ({ door, at }))
    .sort((a, b) => rank(a.door.id) - rank(b.door.id) || a.at - b.at)
    .map((entry) => entry.door);
}

/** How long a health answer about a door is trusted, and how long it may take. */
const HEALTH_TIMEOUT_MS = 8_000;

const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

// ------------------------------------------------------------------ cache

const TTL = {
  search: 15 * 60_000,     // results move with the catalog
  catalog: 15 * 60_000,    // the /movies and /tv-series listing pages
  details: 6 * 3600_000,   // synopses and season lists are near-static
  episodes: 6 * 3600_000,
  servers: 30 * 60_000,    // the doors rotate, but not every minute
  health: 10 * 60_000,     // a host that is down is usually down for a while
};
const cache = new Map();

/**
 * Drop every cached answer. Only the tests call it, and only so one case's
 * answers never seed the next — the Java twin carries the same hook, for the
 * same reason and under the same name.
 */
export function clearCache() {
  cache.clear();
}

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
 * Today's player doors, read from the site's own player page.
 *
 * The page carries its server table as plain JavaScript:
 *
 *   var sources = [
 *     { id: 'embos', aliases: [...], movie: 'https://embos.top/movie/?mid={id}',
 *       tv: 'https://embos.top/tv/?mid={id}&s={season}&e={episode}' },
 *     …
 *
 * One object per door, in the order the site itself lists them. A page that
 * cannot be read, or whose table no longer parses, is not an error the reader
 * should see: the built-in list is the answer, cached as though it had been
 * read, so a dead upstream costs one request per TTL rather than one per open.
 */
function parseServers(html) {
  const table = /var\s+sources\s*=\s*\[([\s\S]*?)\];/.exec(html);
  if (!table) return [];
  const out = [];
  const row = /id:\s*['"]([^'"]+)['"][^}]*?movie:\s*['"]([^'"]+)['"][^}]*?tv:\s*['"]([^'"]+)['"]/g;
  let m;
  while ((m = row.exec(table[1])) !== null) {
    out.push({ id: m[1], movie: m[2], tv: m[3] });
  }
  return out;
}

function servers() {
  return memo('servers', 'doors', async () => {
    try {
      const list = orderDoors(parseServers(await getHtml(WATCH_PAGE)));
      if (list.length) {
        return list.map((s, i) => ({ id: s.id, label: `Server ${i + 1}`, movie: s.movie, tv: s.tv }));
      }
    } catch (e) {
      // The built-in list below is the answer; this is not a failed request.
    }
    return SERVERS;
  });
}

/**
 * Does this door answer at all — and may it be framed?
 *
 * A dead host is the failure that actually happened: two of the three doors
 * stopped resolving, and the third refused to be framed, so the player showed
 * black and said nothing. Reachability and the framing headers are the two
 * questions the page itself cannot answer for us, so they are answered here,
 * once per door per ten minutes. The body is dropped unread: this is a knock
 * on the door, not a download.
 */
function health(url) {
  return memo('health', `h:${url}`, async () => {
    let res;
    try {
      res = await fetch(url, {
        redirect: 'follow',
        headers: {
          'user-agent': BROWSER_UA,
          referer: `${SITE}/`,
          accept: 'text/html,application/xhtml+xml',
        },
        signal: AbortSignal.timeout(HEALTH_TIMEOUT_MS),
      });
    } catch (e) {
      return { ok: false, code: 'unreachable' };
    }
    try {
      res.body?.cancel?.();
    } catch (e) {
      // Nothing to release; the answer stands either way.
    }
    if (!res.ok) return { ok: false, code: `http_${res.status}` };
    const xfo = (res.headers.get('x-frame-options') ?? '').toLowerCase();
    if (xfo.includes('deny') || xfo.includes('sameorigin')) {
      return { ok: false, code: 'frames_refused' };
    }
    const ancestors = /frame-ancestors\s+([^;]+)/i.exec(res.headers.get('content-security-policy') ?? '');
    if (ancestors && /'none'/i.test(ancestors[1])) return { ok: false, code: 'frames_refused' };
    return { ok: true };
  });
}

/**
 * GET /api/fmovies/(movie|tv)/:slug/player?season=&episode= — one URL per door
 * the site offers, filled with the numeric id in the slug, plus whether that
 * door answered. The client opens the first one that did, so a door that went
 * dark is a labelled button rather than a black player.
 */
async function handlePlayer(type, slug, url, res) {
  const target = requestTarget(type, slug, url);
  if (!target) return json(res, 404, { error: 'not_found', slug });
  const { id, season, episode } = target;
  const sources = await Promise.all((await servers()).map((s) => doorSource(s, target)));
  json(res, 200, { type, slug, id, season, episode, sources });
}

/** The slug's id plus the episode asked for — everything a door URL needs. */
function requestTarget(type, slug, url) {
  const idMatch = /-(\d+)$/.exec(slug);
  if (!idMatch) return null;
  return {
    type,
    id: Number(idMatch[1]),
    season: Math.max(1, Number(url.searchParams.get('season') ?? 1) | 0),
    episode: Math.max(1, Number(url.searchParams.get('episode') ?? 1) | 0),
  };
}

function doorSource(server, target) {
  const doorUrl = (target.type === 'tv' ? server.tv : server.movie)
    .replaceAll('{id}', String(target.id))
    .replaceAll('{season}', String(target.season))
    .replaceAll('{episode}', String(target.episode));
  return health(doorUrl).then((probe) => ({
    label: server.label,
    url: doorUrl,
    ok: probe.ok,
    note: probe.ok ? null : probe.code,
  }));
}

/**
 * Resolve the title to a media URL a normal player can open.
 *
 * Exported because the in-app route (`/play/films/…`) needs the same answer
 * without a second copy of the door walk. Doors are tried in order and the
 * first one that plays wins; a title the doors do not carry comes back as a
 * plain "nothing to play", with each door's reason kept for the log.
 */
export async function resolveFilm(type, slug, { season = 1, episode = 1 } = {}) {
  const idMatch = /-(\d+)$/.exec(slug);
  if (!idMatch) return { ok: false, code: 'not_found' };
  if (!browserAvailable()) return { ok: false, code: 'no_browser' };

  const target = { type, id: Number(idMatch[1]), season, episode };
  const attempts = [];

  for (const server of await servers()) {
    const { url: doorUrl } = await doorSource(server, target);
    const probe = await health(doorUrl);
    if (!probe.ok) {
      attempts.push({ door: server.label, code: probe.code });
      continue;
    }
    const found = await resolveDoor(doorUrl);
    if (found.ok) {
      return {
        ok: true,
        door: server.label,
        url: found.url,
        referer: found.referer ?? doorUrl,
        attempts,
      };
    }
    attempts.push({ door: server.label, code: found.code });
  }

  return { ok: false, code: 'no_playable_door', attempts };
}

/**
 * GET /api/fmovies/(movie|tv)/:slug/resolve?season=&episode= — the same walk,
 * answered as JSON for anything that wants to know where the bytes are. This
 * is the slow one: it may boot a browser, and it says so in the response.
 */
async function handleResolve(type, slug, url, res) {
  const target = requestTarget(type, slug, url);
  if (!target) return json(res, 404, { error: 'not_found', slug });
  const found = await resolveFilm(type, slug, target);
  if (!found.ok) {
    return json(res, found.code === 'no_browser' ? 503 : 404, {
      error: found.code,
      slug,
      attempts: found.attempts ?? null,
      hint: found.code === 'no_browser'
        ? 'No Chrome/Edge found for the resolver; set HANIME_BROWSER to one.'
        : null,
    });
  }
  json(res, 200, {
    type, slug, id: target.id, season: target.season, episode: target.episode,
    door: found.door, media: 'hls', url: found.url, referer: found.referer,
  });
}

/** One entry point for the whole /api/fmovies family. */
export async function handleFmovies(url, res, pathname) {
  if (pathname === '/api/fmovies/search') return handleSearch(url, res);

  const m = /^\/api\/fmovies\/(movie|tv)\/([a-z0-9.-]+)(?:\/(episodes|player|resolve))?$/.exec(pathname);
  if (m) {
    const [, type, slug, sub] = m;
    if (sub === 'episodes') {
      if (type !== 'tv') return json(res, 404, { error: 'not_found', pathname });
      return handleEpisodes(slug, url, res);
    }
    if (sub === 'player') return handlePlayer(type, slug, url, res);
    if (sub === 'resolve') return handleResolve(type, slug, url, res);
    return handleDetails(type, slug, res);
  }
  return json(res, 404, { error: 'not_found', pathname });
}
