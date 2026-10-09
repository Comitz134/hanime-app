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

// Filter vocabularies, checked against AniList's enums before they are used
// as variable values: an unknown enum would fail the whole query upstream.
const GENRES = ['Action', 'Adventure', 'Comedy', 'Drama', 'Ecchi', 'Fantasy', 'Horror',
  'Mahou Shoujo', 'Mecha', 'Music', 'Mystery', 'Psychological', 'Romance', 'Sci-Fi',
  'Slice of Life', 'Sports', 'Supernatural', 'Thriller'];
const FORMATS = ['TV', 'TV_SHORT', 'MOVIE', 'SPECIAL', 'OVA', 'ONA', 'MUSIC'];
const STATUSES = ['FINISHED', 'RELEASED', 'RELEASING', 'NOT_YET_RELEASED', 'CANCELLED', 'HIATUS'];

/** The canonical spelling of a known value, or '' when it is not known. */
function pick(value, allowed) {
  const raw = (value ?? '').trim();
  return allowed.find((a) => a.toLowerCase() === raw.toLowerCase()) ?? '';
}

/**
 * The catalog query, assembled from the filters actually in play. Two rules:
 * filters travel as GraphQL variables, never spliced into the text; and only
 * the variables used are declared, because AniList rejects a query that
 * declares one it never reads. With no query the shelf trends — the sort says
 * which of the two this is, exactly as the old pair of queries did.
 */
function catalogQuery({ q, page, genre, format, status }) {
  const decls = ['$page: Int'];
  const args = ['type: ANIME'];
  const variables = { page };
  if (q) { decls.push('$search: String'); args.push('search: $search'); variables.search = q; }
  if (genre) { decls.push('$genre: String'); args.push('genre: $genre'); variables.genre = genre; }
  if (format) { decls.push('$format: MediaFormat'); args.push('format: $format'); variables.format = format; }
  if (status) { decls.push('$status: MediaStatus'); args.push('status: $status'); variables.status = status; }
  args.push(`sort: [${q ? 'SEARCH_MATCH' : 'TRENDING_DESC'}, POPULARITY_DESC]`);

  const query = `
  query (${decls.join(', ')}) {
    Page(page: $page, perPage: 24) {
      pageInfo { currentPage hasNextPage }
      media(${args.join(', ')}) {
        id title { romaji } episodes averageScore startDate { year }
        format status coverImage { large } bannerImage genres
      }
    }
  }`;
  return { query, variables };
}

const DETAILS_QUERY = `
  query ($id: Int) {
    Media(id: $id, type: ANIME) {
      id idMal title { romaji } description(asHtml: false) episodes averageScore
      startDate { year } format status genres
      coverImage { large } bannerImage
      recommendations(perPage: 10, sort: [RATING_DESC]) {
        nodes { mediaRecommendation { id title { romaji } coverImage { large }
                        averageScore episodes startDate { year } } }
      }
    }
  }`;

// A series is not one entry in AniList — every season is its own media
// record, linked to the last by SEQUEL/PREQUEL relations, which is why a grid
// of "anime" reads like a list of unrelated titles. These two relation types
// are the only ones that mean "the same story, next part": AniList also links
// ADAPTATION (the manga), SOURCE, SPIN_OFF, SUMMARY, ALTERNATIVE and the rest,
// and following those would stitch a show to its light novel as if it were the
// next season. The node's own `type` has to be ANIME for the same reason.
const SEASON_REL = { SEQUEL: 'sequel', PREQUEL: 'prequel' };

const SEASONS_QUERY = `
  query ($id: Int) {
    Media(id: $id, type: ANIME) {
      id title { romaji } episodes startDate { year } format coverImage { large }
      relations { edges { relationType node {
        id type title { romaji } episodes startDate { year } format coverImage { large }
      } } }
    }
  }`;

// The walk's budget: one AniList fetch per hop, forwards and backwards from
// the title asked for. Ten round trips reach any real run of seasons, and a
// broken or absurd chain stops instead of circling.
const MAX_SEASON_FETCHES = 10;

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
    // AniList carries MAL's own id for the title — one field, and the
    // tracking row never has to search by name for the anime area.
    malId: m.idMal ?? null,
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

/**
 * GET /api/anime/search?q=&page=&genre=&format=&status=
 * AniList search, or trending when empty; the filters narrow either.
 */
async function handleSearch(url, res) {
  const q = (url.searchParams.get('q') ?? '').trim();
  const page = Math.max(1, Number(url.searchParams.get('page') ?? 1) | 0);
  const genre = pick(url.searchParams.get('genre'), GENRES);
  const format = pick(url.searchParams.get('format'), FORMATS);
  const status = pick(url.searchParams.get('status'), STATUSES);

  const { query, variables } = catalogQuery({ q, page, genre, format, status });
  const data = await cached('search', `s:${q}:${page}:${genre}:${format}:${status}`,
    () => anilist(query, variables));

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

/**
 * One series, every season of it, in air order — the answer to "these are the
 * same show, why does the app treat them as different titles?".
 *
 * The walk starts at the title asked for: backwards until a season has no
 * prequel left (that is the first one), then forwards from there, following
 * only SEQUEL links. Each hop is one AniList query under the same cache the
 * detail page uses, so returning to a series costs nothing.
 *
 * A hop that fails stops the walk rather than failing the call: this is a
 * strip of buttons beside a page that already loaded, and half a chain is
 * worth more than nothing. Only a first fetch with nothing behind it is an
 * error, and it answers like the details route does.
 */
async function seasonChain(startId) {
  const nodes = new Map();   // id -> the media record
  const links = new Map();   // id -> { prequel, sequel } of other ids
  let fetches = 0;
  let failed = false;        // a hop that threw: the chain is incomplete

  const load = async (id) => {
    if (fetches >= MAX_SEASON_FETCHES) return null;
    try {
      const data = await cached('details', `s:${id}`, () => anilist(SEASONS_QUERY, { id }));
      const m = data?.Media;
      if (!m?.id) return null;
      fetches += 1;
      nodes.set(Number(m.id), m);
      const link = { prequel: null, sequel: null };
      for (const edge of m.relations?.edges ?? []) {
        const kind = SEASON_REL[edge.relationType];
        const node = edge.node;
        if (!kind || !node?.id || node.type !== 'ANIME') continue;
        link[kind] = Number(node.id);
      }
      links.set(Number(m.id), link);
      return m;
    } catch (e) {
      // A rejected promise would sit in the cache for the whole TTL and be
      // re-thrown at every later visit, so a rate-limit or a blip would cost
      // this series its strip for six hours. The failure is not an answer.
      cache.delete(`s:${id}`);
      failed = true;
      if (!nodes.size) throw e;   // nothing fetched yet: the caller's error
      return null;                // mid-chain: keep what was already gathered
    }
  };

  const first = await load(Number(startId));
  if (!first) return { data: [], partial: failed };

  // Backwards to the first season…
  let head = Number(first.id);
  for (;;) {
    const prev = links.get(head)?.prequel;
    if (!prev || nodes.has(prev)) break;
    const m = await load(prev);
    if (!m) break;
    head = Number(m.id);
  }

  // …forwards from it, which is the order the seasons air in. A title in the
  // middle of the run lands here too: its own seasons are already fetched, so
  // the order keeps going through them instead of stopping at itself.
  const order = [head];
  let cursor = head;
  for (;;) {
    const next = links.get(cursor)?.sequel;
    if (!next || order.includes(next)) break;   // no link, or a cycle
    const m = nodes.has(next) ? nodes.get(next) : await load(next);
    if (!m) break;
    order.push(Number(m.id));
    cursor = Number(m.id);
  }

  return {
    partial: failed,
    data: order.map((id) => ({
      ...shapeCard(nodes.get(id)),
      current: id === Number(startId),
    })),
  };
}

/** GET /api/anime/:id/seasons — the series this title belongs to. */
async function handleSeasons(id, res) {
  const key = `chain:${id}`;
  let out;
  try {
    out = await cached('details', key, () => seasonChain(Number(id)));
  } catch (e) {
    cache.delete(key);   // a rejection is not an answer either: see load()
    throw e;
  }
  // A chain cut short by a failed hop is not an answer worth keeping: the
  // strip would stay half-grown for the whole TTL over one blip, and the next
  // visit would show the same half. Only a chain that ended normally is
  // cached — which is why `partial` travels with the data.
  if (out?.partial) cache.delete(key);
  json(res, 200, { id: Number(id), data: out?.data ?? [] });
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

  const seasonsMatch = /^\/api\/anime\/(\d+)\/seasons$/.exec(pathname);
  if (seasonsMatch) return handleSeasons(seasonsMatch[1], res);

  const detailsMatch = /^\/api\/anime\/(\d+)$/.exec(pathname);
  if (detailsMatch) return handleDetails(detailsMatch[1], res);

  return json(res, 404, { error: 'not_found', pathname });
}
