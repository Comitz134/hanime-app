// Films and series (the "movies" area), sourced the way cinehd.vc does it.
//
// cinehd is a Next.js front end with no catalog of its own: its pages are
// rendered from TMDB's public API (search, trending, title details, seasons and
// episodes all arrive as JSON with the key its own bundle carries), and its
// player is an iframe onto a list of third-party doors — videasy, vidlink,
// vidfast, vidcore, ythd, vixsrc and a few dozen more — each a URL template
// filled with the title's TMDB id (and season/episode for a series).
//
// That is a better source than the static HTML this module used to scrape:
// metadata is exact instead of parsed out of markup that changes shape, search
// is one request, and a series' episode list is the real one rather than the
// anchors a page happened to render. The *f-movies* route names stay: they are
// the API contract the web client, the addon, the desktop window and the Java
// twin all speak, and a rename would strand every one of them for a change that
// is about where the data comes from, not what it looks like.
//
// The doors are the six cinehd lists first (Max, Vidpro, V2, Premium, 4K,
// Vidfast), in its own order. A door is only offered if it answers: hosts
// rotate and go dark, so each is knocked on when a title is opened (once per
// ten minutes per URL) and a door that did not answer is a disabled button with
// its reason, never a black frame the reader has to guess at.
//
// No account and no client-side key of our own: TMDB_API_KEY wins if the
// machine has one, and otherwise the public key cinehd's bundle ships is used,
// which is the same one every web reader of that site uses.

const TMDB_API = 'https://api.themoviedb.org/3';
const TMDB_IMAGE = 'https://image.tmdb.org/t/p';
const PUBLIC_TMDB_KEY = 'ef311eb0b9b07b9c73e9fb0a732cc150';
const TIMEOUT_MS = 12_000;
const SEARCH_PAGE_SIZE = 20;   // TMDB's own page size, stated so it is not assumed

import { browserAvailable, resolveDoor } from './browser.mjs';
import { openSubtitlesTracks } from './opensubs.mjs';

/**
 * Where vidsrc.sh — the player behind several doors — keeps the facts it shows
 * beside a stream: the title's IMDb id and, sometimes, one subtitle of its own.
 * Plain JSON, no key, no referer.
 */
const VIDSRC_DATA = 'https://data.vidsrc.sh/api.php';

/** The doors, in cinehd's order, with its own URL formats. */
const SERVERS = [
  {
    id: 'max', label: 'Max',
    movie: 'https://ythd.org/embed/{id}',
    tv: 'https://ythd.org/embed/{id}/{season}-{episode}',
  },
  {
    id: 'vidpro', label: 'Vidpro',
    movie: 'https://vixsrc.to/movie/{id}',
    tv: 'https://vixsrc.to/tv/{id}/{season}/{episode}',
  },
  {
    id: 'v2', label: 'V2',
    movie: 'https://player2.vidplus.pro/embed/movie/{id}?autoplay=true',
    tv: 'https://player2.vidplus.pro/embed/tv/{id}/{season}/{episode}',
  },
  {
    id: 'premium', label: 'Premium',
    movie: 'https://player.vidplus.pro/embed/movie/{id}?autoplay=true&download=true',
    tv: 'https://player.vidplus.pro/embed/tv/{id}/{season}/{episode}',
  },
  {
    id: '4k', label: '4K',
    movie: 'https://player.videasy.to/movie/{id}',
    tv: 'https://player.videasy.to/tv/{id}/{season}/{episode}',
  },
  {
    id: 'vidfast', label: 'Vidfast',
    movie: 'https://vidfast.vc/movie/{id}?autoplay=true',
    tv: 'https://vidfast.vc/tv/{id}/{season}/{episode}?autoplay=true',
  },
];

/**
 * Which door the in-app player reaches for first.
 *
 * cinehd lists Max first, and Max is the right lead for a framed embed — but the
 * in-app player does not frame anything: it needs a playlist URL it can serve,
 * and the door that hands one over is Vidpro. So the walk starts there, and the
 * rest of cinehd's order follows behind it, tried when Vidpro does not answer.
 * A reader who names a door in the URL (the player's `src=`) leads with that one
 * instead; nothing here is a lock, only a preference.
 */
const IN_APP_FIRST = 'vidpro';

/**
 * The doors in the order the in-app player should try them, with the one asked
 * for — or Vidpro — at the front. A name that matches no door is not an error:
 * the list is simply the default one, which is what the caller would have got
 * by asking for nothing.
 */
export function inAppOrder(wanted) {
  const key = String(wanted ?? '').trim().toLowerCase();
  const asked = key ? SERVERS.find((s) => s.id === key || s.label.toLowerCase() === key) : null;
  const first = asked ?? SERVERS.find((s) => s.id === IN_APP_FIRST) ?? SERVERS[0];
  return [first, ...SERVERS.filter((s) => s !== first)];
}

/** The TMDB key in force: a configured one first, the public one otherwise. */
function tmdbKey() {
  return process.env.TMDB_API_KEY || process.env.HANIME_TMDB_KEY || PUBLIC_TMDB_KEY;
}

/** How long a health answer about a door is trusted, and how long it may take. */
const HEALTH_TIMEOUT_MS = 8_000;

const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

// ------------------------------------------------------------------ cache

const TTL = {
  search: 15 * 60_000,     // results move with the catalog
  catalog: 15 * 60_000,    // the trending shelf behind an empty query
  details: 6 * 3600_000,   // synopses, cast and season lists are near-static
  episodes: 6 * 3600_000,
  health: 10 * 60_000,     // a host that is down is usually down for a while
  subtitles: 6 * 3600_000, // a release's subtitle list moves with the release
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
  const e = new Error(`tmdb: ${note ?? `status ${status}`}`);
  e.status = status === 404 ? 404 : 502;
  return e;
}

/** GET a TMDB path, with the key added and the answer cached per URL. */
async function tmdb(path, params, kind) {
  const url = new URL(TMDB_API + path);
  url.searchParams.set('language', 'en-US');
  url.searchParams.set('api_key', tmdbKey());
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value === undefined || value === null || value === '') continue;
    url.searchParams.set(key, String(value));
  }
  const href = url.toString();

  return memo(kind, href, async () => {
    let res;
    try {
      res = await fetch(href, {
        headers: { accept: 'application/json', 'user-agent': BROWSER_UA },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch (e) {
      throw upstreamError(502, e.message);
    }
    if (res.status === 404) throw upstreamError(404, 'not found');
    if (!res.ok) throw upstreamError(res.status);
    return res.json();
  });
}

// ------------------------------------------------------------------ shaping

/** `Breaking Bad` → `breaking-bad`; the id is appended by slugFor. */
function slugify(text) {
  return String(text ?? '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60) || 'title';
}

const titleOf = (type, raw) => (type === 'tv' ? raw.name ?? raw.original_name : raw.title ?? raw.original_title)
  ?? 'Untitled';
const yearOf = (raw) => Number(String(raw.release_date ?? raw.first_air_date ?? '').slice(0, 4)) || null;

/** The slug the whole app keys a title by: readable name, then its TMDB id. */
function slugFor(type, raw) {
  return `${slugify(titleOf(type, raw))}-${raw.id}`;
}

const posterUrl = (path, size = 'w500') => (path ? `${TMDB_IMAGE}/${size}${path}` : null);

/** A TMDB row → the card the shelf draws. */
function shapeItem(raw) {
  const type = raw?.media_type === 'tv' ? 'tv' : raw?.media_type === 'movie' ? 'movie' : null;
  if (!type) return null;                          // a person is not a title
  const score = typeof raw.vote_average === 'number' && raw.vote_average > 0
    ? Math.round(raw.vote_average * 10) / 10
    : null;
  return {
    type,
    slug: slugFor(type, raw),
    title: titleOf(type, raw),
    year: yearOf(raw),
    score,
    poster: posterUrl(raw.poster_path),
  };
}

const asList = (v) => (Array.isArray(v) ? v : v == null ? [] : [v]);
const personName = (v) => (typeof v === 'string' ? v : v?.name ?? null);

/**
 * The details object the title page renders. One TMDB request carries the
 * credits and the external ids too (`append_to_response`), which is where the
 * cast, the director/creator and the IMDb id for subtitles all come from.
 */
function shapeDetails(type, slug, raw) {
  const credits = raw.credits ?? {};
  const crew = asList(credits.crew);
  // A series names its creators at the top level of its own document; a film
  // names its directors in the crew list.
  const creator = type === 'tv'
    ? asList(raw.created_by ?? credits.created_by).map(personName)
    : crew.filter((c) => c?.job === 'Director').map(personName);
  return {
    type,
    slug,
    title: titleOf(type, raw),
    description: raw.overview ? String(raw.overview) : null,
    poster: posterUrl(raw.poster_path),
    backdrop: posterUrl(raw.backdrop_path, 'original'),
    year: yearOf(raw),
    // The score the site shows beside a title is TMDB's own average.
    score: typeof raw.vote_average === 'number' && raw.vote_average > 0
      ? Math.round(raw.vote_average * 10) / 10
      : null,
    genres: asList(raw.genres).map((g) => g?.name).filter(Boolean),
    cast: asList(credits.cast).map(personName).filter(Boolean).slice(0, 20),
    creator: creator.filter(Boolean),
    // Season 0 is TMDB's bucket for specials, not a season the strip offers.
    seasons: type === 'tv'
      ? asList(raw.seasons).map((s) => s?.season_number).filter((n) => Number.isInteger(n) && n > 0)
      : [],
    numberOfSeasons: raw.number_of_seasons ?? null,
    numberOfEpisodes: raw.number_of_episodes ?? null,
    imdb_id: raw.external_ids?.imdb_id ?? null,
  };
}

/** The one details request, shared by the title page and the IMDb lookup. */
function detailsRaw(type, id) {
  return tmdb(`/${type}/${id}`, { append_to_response: 'credits,external_ids' }, 'details');
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
 * With a q: TMDB's multi search, optionally narrowed to movie or tv. Without
 * one: the week's trending shelf — mixed, or the one type asked for.
 *
 * `source` names where the catalog came from. The client prints it, so a
 * backend that has not moved yet (the Java twin, until it is ported) keeps
 * naming its own source instead of inheriting this one's label.
 */
async function handleSearch(url, res) {
  const q = (url.searchParams.get('q') ?? '').trim();
  const page = Math.max(1, Number(url.searchParams.get('page') ?? 1) | 0);
  const type = url.searchParams.get('type') === 'tv' ? 'tv' : url.searchParams.get('type') === 'movie' ? 'movie' : '';

  const body = q
    ? await tmdb('/search/multi', { query: q, page, include_adult: 'false' }, 'search')
    : await tmdb(type ? `/trending/${type}/week` : '/trending/all/week', { page }, 'catalog');

  const all = asList(body.results).map(shapeItem).filter(Boolean);
  const items = type ? all.filter((i) => i.type === type) : all;
  const totalPages = Number(body.total_pages ?? 0);

  json(res, 200, {
    q,
    page,
    type,
    source: 'TMDB',
    // A page of the mixed search can be narrowed to nothing; the page count is
    // what upstream says, not what survived the filter.
    hasNext: Number.isFinite(totalPages) ? page < totalPages : all.length >= SEARCH_PAGE_SIZE,
    items,
  });
}

/** The id in a slug, or null when the slug carries none. */
function idFromSlug(slug) {
  const m = /-(\d+)$/.exec(String(slug));
  return m ? Number(m[1]) : null;
}

/** GET /api/fmovies/(movie|tv)/:slug — one title. */
async function handleDetails(type, slug, res) {
  const id = idFromSlug(slug);
  if (id === null) return json(res, 404, { error: 'not_found', slug });
  const raw = await detailsRaw(type, id);
  json(res, 200, { details: shapeDetails(type, slug, raw) });
}

/**
 * GET /api/fmovies/tv/:slug/episodes?season=N — the episode rows of one
 * season. TMDB answers a season with its real episode numbers and names; a
 * season the title does not have is a 404, not silently the first one's list.
 */
async function handleEpisodes(slug, url, res) {
  const id = idFromSlug(slug);
  if (id === null) return json(res, 404, { error: 'not_found', slug });
  const season = Math.max(1, Number(url.searchParams.get('season') ?? 1) | 0);

  let body;
  try {
    body = await tmdb(`/tv/${id}/season/${season}`, {}, 'episodes');
  } catch (e) {
    if (e.status === 404) return json(res, 404, { error: 'season_not_found', slug, season });
    throw e;
  }

  const data = asList(body.episodes)
    .map((ep) => ({
      n: Number(ep?.episode_number),
      title: ep?.name ? String(ep.name) : null,
    }))
    .filter((ep) => Number.isInteger(ep.n))
    .sort((a, b) => a.n - b.n);
  if (!data.length) return json(res, 404, { error: 'no_episodes', slug, season });
  json(res, 200, { slug, season, data });
}

/**
 * Does this door answer at all — and may it be framed?
 *
 * A dead host is the failure that actually happened before: doors stopped
 * resolving or refused to be framed, so the player showed black and said
 * nothing. Reachability and the framing headers are the two questions a URL
 * template cannot answer, so they are answered here, once per URL per ten
 * minutes. The body is dropped unread: this is a knock, not a download.
 */
function health(url) {
  return memo('health', `h:${url}`, async () => {
    let res;
    try {
      res = await fetch(url, {
        redirect: 'follow',
        headers: {
          'user-agent': BROWSER_UA,
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

/** The slug's id plus the episode asked for — everything a door URL needs. */
function requestTarget(type, slug, url) {
  const id = idFromSlug(slug);
  if (id === null) return null;
  return {
    type,
    id,
    season: Math.max(1, Number(url.searchParams.get('season') ?? 1) | 0),
    episode: Math.max(1, Number(url.searchParams.get('episode') ?? 1) | 0),
  };
}

function doorUrl(server, target) {
  return (target.type === 'tv' ? server.tv : server.movie)
    .replaceAll('{id}', String(target.id))
    .replaceAll('{season}', String(target.season))
    .replaceAll('{episode}', String(target.episode));
}

function doorSource(server, target) {
  const url = doorUrl(server, target);
  return health(url).then((probe) => ({
    label: server.label,
    url,
    ok: probe.ok,
    note: probe.ok ? null : probe.code,
  }));
}

/**
 * GET /api/fmovies/(movie|tv)/:slug/player?season=&episode= — one URL per
 * door, filled with the title's TMDB id, plus whether that door answered. The
 * client opens the first one that did, so a door that went dark is a labelled
 * button rather than a black player.
 */
async function handlePlayer(type, slug, url, res) {
  const target = requestTarget(type, slug, url);
  if (!target) return json(res, 404, { error: 'not_found', slug });
  const sources = await Promise.all(SERVERS.map((s) => doorSource(s, target)));
  json(res, 200, { type, slug, id: target.id, season: target.season, episode: target.episode, sources });
}

/**
 * Resolve the title to a media URL a normal player can open.
 *
 * Exported because the in-app route (`/play/films/…`) needs the same answer
 * without a second copy of the door walk. Doors are tried in order and the
 * first one that plays wins; a title the doors do not carry comes back as a
 * plain "nothing to play", with each door's reason kept for the log.
 *
 * `door` is the reader's own choice — a label or an id — and only reorders the
 * walk: a door that is named and then fails still leaves the others to try, so
 * a stale preference is a slower answer rather than a broken one.
 */
export async function resolveFilm(type, slug, { season = 1, episode = 1, door = null } = {}) {
  const id = idFromSlug(slug);
  if (id === null) return { ok: false, code: 'not_found' };
  if (!browserAvailable()) return { ok: false, code: 'no_browser' };

  const target = { type, id, season, episode };
  const attempts = [];

  for (const server of inAppOrder(door)) {
    const url = doorUrl(server, target);
    const probe = await health(url);
    if (!probe.ok) {
      attempts.push({ door: server.label, code: probe.code });
      continue;
    }
    const found = await resolveDoor(url);
    if (found.ok) {
      return {
        ok: true,
        door: server.label,
        door_id: server.id,
        url: found.url,
        referer: found.referer ?? url,
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

// ---------------------------------------------------------------- subtitles

/**
 * The IMDb id behind a slug's TMDB id. A film's slug carries its TMDB id and
 * nothing else, and the subtitle addon is keyed by IMDb id; this is the one hop
 * between them. It rides the details cache, so a title page that already asked
 * for the title costs nothing extra here.
 */
async function imdbIdFor(type, tmdbId) {
  const raw = await detailsRaw(type, tmdbId);
  const id = raw?.external_ids?.imdb_id;
  if (typeof id !== 'string' || !/^tt\d+$/.test(id)) throw upstreamError(404, 'no imdb id');
  return id;
}

/**
 * The player's own subtitle list — the one several doors show in their menu.
 *
 * It is thin: of thirteen popular titles sampled live, exactly one had a track.
 * It is still read, because it costs one request and it is the only list that
 * can name a track the bigger addon does not have.
 */
function vidsrcTracks(type, tmdbId, season, episode) {
  const kind = type === 'tv' ? 'tv' : 'movie';
  const ep = kind === 'tv' ? `&season=${season}&episode=${episode}` : '';
  return memo('subtitles', `vidsrc:${kind}:${tmdbId}:${season}:${episode}`, async () => {
    const res = await fetch(`${VIDSRC_DATA}?type=${kind}&tmdb=${tmdbId}${ep}`, {
      headers: { 'user-agent': BROWSER_UA, accept: 'application/json' },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!res.ok) return [];
    const body = await res.json();
    const list = Array.isArray(body?.default_subs) ? body.default_subs : [];
    return list
      .filter((s) => typeof s?.url === 'string' && s.url.startsWith('https://'))
      .map((s) => ({
        id: `vidsrc-${s.code ?? 'x'}`,
        url: s.url,
        // The page spells a language twice ('English - eng'); the code is the
        // half a picker can match a preference against, so it is preferred.
        lang: s.code || String(s.lang ?? '').split(' - ').pop() || 'unknown',
        label: String(s.lang ?? '').split(' - ')[0].trim() || undefined,
        format: 'srt',
      }));
  });
}

/**
 * Every subtitle track a film or an episode has, best source first.
 *
 * The OpenSubtitles addon answers nearly every title; the door player's own
 * list fills in the rare track it alone knows. A title with no IMDb id has
 * nothing to ask with, which is an empty list rather than a failure.
 */
export async function subtitleTracks(type, slug, { season = 1, episode = 1 } = {}) {
  const tmdbId = idFromSlug(slug);
  if (tmdbId === null) return { ok: false, code: 'not_found', tracks: [] };
  const isEpisode = type === 'tv';

  const tracks = [];
  let imdbId = null;
  try {
    imdbId = await imdbIdFor(type, tmdbId);
  } catch (e) {
    imdbId = null;   // no mapping: the player's own list is still worth asking
  }

  if (imdbId) {
    try {
      tracks.push(...await openSubtitlesTracks({
        imdbId,
        season: isEpisode ? season : undefined,
        episode: isEpisode ? episode : undefined,
      }));
    } catch (e) {
      // A source that is down is not the whole answer.
    }
  }

  if (!tracks.length) {
    try {
      tracks.push(...await vidsrcTracks(type, tmdbId, season, episode));
    } catch (e) {
      // Same again.
    }
  }

  // One track per URL: the two sources can name the same file.
  const seen = new Set();
  const unique = tracks.filter((track) => {
    if (seen.has(track.url)) return false;
    seen.add(track.url);
    return true;
  });
  return { ok: true, imdb_id: imdbId, tmdb_id: tmdbId, tracks: unique };
}

/**
 * GET /api/fmovies/(movie|tv)/:slug/subtitles?season=&episode= — the tracks an
 * episode has, in the same shape the anime area's route answers with, so one
 * client can read both.
 */
async function handleSubtitles(type, slug, url, res) {
  const season = Math.max(1, Number(url.searchParams.get('season') ?? 1) | 0);
  const episode = Math.max(1, Number(url.searchParams.get('episode') ?? 1) | 0);
  let found;
  try {
    found = await subtitleTracks(type, slug, { season, episode });
  } catch (e) {
    return json(res, 502, { error: 'upstream', detail: e.message });
  }
  if (!found.ok) return json(res, 404, { error: found.code, slug });
  json(res, 200, {
    slug,
    season,
    episode,
    imdb_id: found.imdb_id,
    tracks: found.tracks.map((track, i) => ({
      id: track.id ?? String(i),
      language: track.lang,
      name: track.label ?? null,
      format: track.format ?? 'srt',
      url: track.url,
    })),
  });
}

/** One entry point for the whole /api/fmovies family. */
export async function handleFmovies(url, res, pathname) {
  if (pathname === '/api/fmovies/search') return handleSearch(url, res);

  const m = /^\/api\/fmovies\/(movie|tv)\/([a-z0-9.-]+)(?:\/(episodes|player|resolve|subtitles))?$/.exec(pathname);
  if (m) {
    const [, type, slug, sub] = m;
    if (sub === 'episodes') {
      if (type !== 'tv') return json(res, 404, { error: 'not_found', pathname });
      return handleEpisodes(slug, url, res);
    }
    if (sub === 'player') return handlePlayer(type, slug, url, res);
    if (sub === 'resolve') return handleResolve(type, slug, url, res);
    if (sub === 'subtitles') return handleSubtitles(type, slug, url, res);
    return handleDetails(type, slug, res);
  }
  return json(res, 404, { error: 'not_found', pathname });
}
