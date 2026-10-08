// anime.mjs — the normal (non-adult) anime area.
//
// Two upstreams, one route family:
//
//   AniList (graphql.anilist.co)   catalog: search, trending, details,
//                                  recommendations. Public API, CORS-open,
//                                  no key.
//   LunarX  (api.lunarx.to)        episode lists and the embed player URL for
//                                  a given anilist id + episode. It answers
//                                  only requests whose Origin is absent or
//                                  lunarx.to itself, so a browser can never
//                                  call it directly — every request from this
//                                  client passes through here, which spoofs
//                                  the origin on the way out.
//
// The two are keyed by the same id: the id in a lunarx.to/anime/<id>/… URL is
// the AniList id, so one identifier carries both the metadata and the streams.
//
// Everything is cached in memory with a TTL: the catalog changes on the scale
// of days, episode lists on the scale of a season, and a player link is only
// good for as long as the embed host keeps it — none of them deserve a
// request per page view.

const ANILIST = 'https://graphql.anilist.co';
const LUNARX = 'https://api.lunarx.to';

// lunarx.to refuses any request that carries another site's Origin (400), so
// the proxy speaks as the site itself. The UA matches a normal browser
// because the API sits behind the same bot rules as the site.
const LUNARX_HEADERS = {
  Origin: 'https://lunarx.to',
  Referer: 'https://lunarx.to/',
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
    + ' (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  Accept: 'application/json',
};

const TIMEOUT_MS = 12_000;

// ---------------------------------------------------------------- cache

const cache = new Map();
const TTL = {
  search: 15 * 60_000,
  details: 6 * 60 * 60_000,
  episodes: 6 * 60 * 60_000,
  player: 30 * 60_000,
};

function cached(kind, key, produce) {
  const hit = cache.get(key);
  if (hit && hit.expires > Date.now()) return hit.value;
  const value = produce();
  // Bound the cache: a long-running server should not grow one entry per
  // episode ever looked at.
  if (cache.size > 500) {
    for (const [k, v] of cache) if (v.expires <= Date.now()) cache.delete(k);
    if (cache.size > 500) cache.clear();
  }
  cache.set(key, { value, expires: Date.now() + TTL[kind] });
  return value;
}

// ---------------------------------------------------------------- fetch

async function jget(url, headers = {}) {
  const res = await fetch(url, { headers, signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!res.ok) {
    const err = new Error(`upstream ${res.status} for ${new URL(url).pathname}`);
    err.status = 502;
    throw err;
  }
  return res.json();
}

async function anilist(query, variables) {
  const res = await fetch(ANILIST, {
    method: 'POST',
    headers: { 'content-type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ query, variables }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  const body = await res.json().catch(() => null);
  if (!res.ok || body?.errors) {
    const msg = body?.errors?.[0]?.message ?? `status ${res.status}`;
    const err = new Error(`anilist: ${msg}`);
    err.status = 502;
    throw err;
  }
  return body.data;
}

// ---------------------------------------------------------------- queries

const SEARCH_QUERY = `
  query ($search: String, $page: Int) {
    Page(page: $page, perPage: 24) {
      pageInfo { currentPage hasNextPage }
      media(type: ANIME, search: $search, sort: [SEARCH_MATCH, POPULARITY_DESC]) {
        id title { romaji } episodes averageScore startDate { year }
        format status coverImage { large } bannerImage genres
      }
    }
  }`;

const TRENDING_QUERY = `
  query ($page: Int) {
    Page(page: $page, perPage: 24) {
      pageInfo { currentPage hasNextPage }
      media(type: ANIME, sort: [TRENDING_DESC, POPULARITY_DESC]) {
        id title { romaji } episodes averageScore startDate { year }
        format status coverImage { large } bannerImage genres
      }
    }
  }`;

const DETAILS_QUERY = `
  query ($id: Int) {
    Media(id: $id, type: ANIME) {
      id title { romaji } description(asHtml: false) episodes averageScore
      startDate { year } format status genres
      coverImage { large } bannerImage
      recommendations(perPage: 10, sort: [RATING_DESC]) {
        nodes { mediaRecommendation { id title { romaji } coverImage { large }
                        averageScore episodes startDate { year } } }
      }
    }
  }`;

// ---------------------------------------------------------------- shaping

function shapeCard(m) {
  return {
    id: m.id,
    title: m.title?.romaji ?? 'Unknown',
    year: m.startDate?.year ?? null,
    eps: m.episodes ?? null,
    score: m.averageScore ?? null,
    format: m.format ?? null,
    status: m.status ?? null,
    cover: m.coverImage?.large ?? null,
    banner: m.bannerImage ?? null,
    genres: m.genres ?? [],
  };
}

function shapeDetails(m) {
  return {
    ...shapeCard(m),
    description: (m.description ?? '')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<[^>]+>/g, ' ')
      .replace(/[ \t]{2,}/g, ' ')
      .trim(),
    // AniList names two fields per recommendation: `media` is the title you
    // are already looking at, `mediaRecommendation` is the suggested one.
    // Selecting `media` fills the rail with the watched anime itself, so the
    // suggestion is taken from `mediaRecommendation` and the watched id is
    // dropped if it ever arrives.
    recommendations: (m.recommendations?.nodes ?? [])
      .map((n) => n.mediaRecommendation)
      .filter((r) => r && r.id !== m.id)
      .map(shapeCard),
  };
}

function shapeEpisode(e) {
  return {
    number: e.number,
    title: e.title ?? '',
    description: e.description ?? '',
    img: e.img ?? null,
    airDate: e.airDate ?? null,
    length: e.length ?? e.runtime ?? null,
    hasSub: !!e.hasSub,
    hasDub: !!e.hasDub,
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

/** GET /api/anime/search?q=&page= — AniList search, or trending when empty. */
async function handleSearch(url, res) {
  const q = (url.searchParams.get('q') ?? '').trim();
  const page = Math.max(1, Number(url.searchParams.get('page') ?? 1) | 0);

  const data = await cached('search', `s:${q}:${page}`, () =>
    q
      ? anilist(SEARCH_QUERY, { search: q, page })
      : anilist(TRENDING_QUERY, { page }));

  const page_ = data.Page;
  json(res, 200, {
    q,
    page: page_.pageInfo.currentPage,
    hasNext: !!page_.pageInfo.hasNextPage,
    items: (page_.media ?? []).map(shapeCard),
  });
}

/** GET /api/anime/:id — one title with its description and recommendations. */
async function handleDetails(id, res) {
  const data = await cached('details', `d:${id}`, () => anilist(DETAILS_QUERY, { id }));
  if (!data.Media) return json(res, 404, { error: 'not_found', id });
  json(res, 200, { details: shapeDetails(data.Media) });
}

/** GET /api/anime/:id/episodes — LunarX's episode list for the season. */
async function handleEpisodes(id, res) {
  const data = await cached('episodes', `e:${id}`, async () => {
    const body = await jget(`${LUNARX}/api/animes/v2/episodes?id=${encodeURIComponent(id)}`, LUNARX_HEADERS);
    return (body?.data ?? []).map(shapeEpisode);
  });
  json(res, 200, { id, data });
}

/** GET /api/anime/:id/player?ep=N — the embed URL LunarX itself would use. */
async function handlePlayer(id, ep, res) {
  const n = Math.max(1, Number(ep) | 0);
  const list = await cached('player', `p:${id}:${n}`, async () => {
    const body = await jget(
      `${LUNARX}/api/3rdprovider?anilist=${encodeURIComponent(id)}&episode=${n}`,
      LUNARX_HEADERS,
    );
    return (body?.data ?? [])
      .filter((row) => row.player_url)
      .map((row) => ({ label: row.server ?? 'auto', url: row.player_url }));
  });
  if (!list.length) return json(res, 404, { error: 'no_player', id, ep: n });
  json(res, 200, { id, ep: n, sources: list });
}

/**
 * One entry point for the whole /api/anime family. Called from the server's
 * router with the parsed URL and the response.
 */
export async function handleAnime(url, res, pathname) {
  const searchMatch = /^\/api\/anime\/search$/.exec(pathname);
  if (searchMatch) return handleSearch(url, res);

  const episodesMatch = /^\/api\/anime\/(\d+)\/episodes$/.exec(pathname);
  if (episodesMatch) return handleEpisodes(episodesMatch[1], res);

  const playerMatch = /^\/api\/anime\/(\d+)\/player$/.exec(pathname);
  if (playerMatch) return handlePlayer(playerMatch[1], url.searchParams.get('ep') ?? '1', res);

  const detailsMatch = /^\/api\/anime\/(\d+)$/.exec(pathname);
  if (detailsMatch) return handleDetails(detailsMatch[1], res);

  return json(res, 404, { error: 'not_found', pathname });
}
