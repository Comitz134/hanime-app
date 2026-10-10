// addon.mjs — the catalogue this server already serves, shaped as an addon.
//
// The desktop app (../desktop) is a fork of NuvioDesktop, and that app is a
// Stremio client: it asks a source for a manifest, then reads `catalog`, `meta`
// and `stream` documents from it. This module answers that protocol *from the
// routes this server already has* — in process, through the router itself —
// so nothing here duplicates a parser, a cache or a TTL. The web client, the
// phone and the desktop app are all reading the same upstreams; only the
// envelope differs.
//
// Ids carry the area they came from, because one client asks about every area
// through one string:
//
//   ha:an:<anilist id>          an anime title              (type series)
//   ha:fm:<f-movies slug>       a film                      (type movie)
//   ha:ft:<f-movies slug>       a series                    (type series)
//   ha:ad:<hanime slug>         a video from the 18+ shelf  (type movie)
//
// and an episode of a series is `<id>:<season>:<episode>` — the shape Stremio
// clients append themselves, which is why an id is parsed rather than looked
// up: the season and episode travel in the string.
//
// Playback is the interesting part, and `streams()` is where it shows. The 18+
// shelf resolves to HLS playlists a native player can open. Films are one layer
// harder: their doors hand out a *player*, not a stream, so `/play/films/…`
// borrows a browser to learn what that player plays and the relay serves it —
// the film lands in the app's own player all the same. Anime titles still answer
// with embed pages and travel as `externalUrl` until someone resolves them the
// same way.

const PAGE_SIZE = 24;

// ------------------------------------------------------------------- ids

function idFor(area, key) {
  return `ha:${area}:${key}`;
}

/**
 * Parse one of our ids back into its area, key and — for a series episode —
 * the season and episode carried in the tail.
 */
function parseId(raw) {
  const parts = String(raw ?? '').split(':');
  if (parts.length < 3 || parts[0] !== 'ha') return null;
  const [, area, ...rest] = parts;
  if (!rest.length || !rest.every((p) => p.length)) return null;

  // Both series areas carry an episode tail: `ha:an:21:1:5` and
  // `ha:ft:breaking-bad-1396:5:16` are the same two numbers in the same two
  // places, and neither area's key can hold a colon of its own.
  if (area === 'an' || area === 'ft') {
    if (rest.length >= 3) {
      const season = Number(rest[rest.length - 2]);
      const episode = Number(rest[rest.length - 1]);
      if (Number.isInteger(season) && Number.isInteger(episode)) {
        return { area, key: rest.slice(0, -2).join(':'), season, episode };
      }
    }
    return { area, key: rest.join(':') };
  }
  if (area === 'fm' || area === 'ad') return { area, key: rest.join(':') };
  return null;
}

// ------------------------------------------------------------- the catalog

const CATALOGS = [
  { key: 'anime-trending', type: 'series', name: 'Anime — trending now' },
  { key: 'films-listing', type: 'movie', name: 'Films — popular now' },
  { key: 'series-listing', type: 'series', name: 'Series — popular now' },
  { key: 'adult-newest', type: 'movie', name: '18+ — newest' },
  { key: 'adult-views', type: 'movie', name: '18+ — most viewed' },
];

/** The manifest a Stremio client reads first. */
export function manifest() {
  return {
    id: 'tv.hanime.bridge',
    version: '1.0.0',
    name: 'Hanime',
    description: 'Anime, films and series from this server, plus its own 18+ shelf.',
    resources: ['catalog', 'meta', 'stream'],
    types: ['movie', 'series'],
    catalogs: CATALOGS.map(({ key, type, name }) => ({
      type,
      id: key,
      name,
      // Every shelf answers a search, so the client's search box reaches it.
      extra: [{ name: 'search', isRequired: false }, { name: 'skip', isRequired: false }],
    })),
  };
}

// ------------------------------------------------------------- the shapes

/** Score as the client wants it: a string out of ten, or omitted. */
function rating(score) {
  if (typeof score !== 'number' || !Number.isFinite(score) || score <= 0) return undefined;
  const out = score > 10 ? score / 10 : score;
  return out.toFixed(1);
}

function preview(meta) {
  return {
    id: meta.id,
    type: meta.type,
    name: meta.name,
    poster: meta.poster,
    background: meta.background,
    posterShape: 'poster',
    releaseInfo: meta.releaseInfo,
    imdbRating: meta.imdbRating,
  };
}

/** One anime card as a catalog entry or a full meta. */
function animeMeta(card, extra = {}) {
  return {
    id: idFor('an', card.id),
    type: 'series',
    name: card.title,
    poster: card.cover ?? undefined,
    background: card.banner ?? undefined,
    description: extra.description,
    releaseInfo: card.year ? String(card.year) : undefined,
    imdbRating: rating(card.score),
    genres: card.genres?.length ? card.genres : undefined,
    videos: extra.videos,
  };
}

function filmMeta(details, ref, videos) {
  const type = ref.area === 'ft' ? 'series' : 'movie';
  return {
    id: idFor(ref.area, ref.key),
    type,
    name: details.title,
    poster: details.poster ?? undefined,
    description: details.description ?? undefined,
    releaseInfo: details.year ? String(details.year) : undefined,
    imdbRating: rating(details.score),
    genres: details.genres?.length ? details.genres : undefined,
    cast: details.cast?.length ? details.cast.slice(0, 8) : undefined,
    // A film has none; Stremio clients read an absent list as "nothing to
    // pick", but an explicit one keeps the page from guessing.
    videos: videos ?? [],
  };
}


function adultMeta(video) {
  return {
    id: idFor('ad', video.slug),
    type: 'movie',
    name: video.name,
    poster: video.poster ?? video.cover ?? undefined,
    background: video.cover ?? video.poster ?? undefined,
    description: video.description
      ? String(video.description).replace(/<[^>]+>/g, ' ').replace(/\s{2,}/g, ' ').trim()
      : undefined,
    releaseInfo: video.released_at ? String(video.released_at).slice(0, 4) : undefined,
    genres: video.tags?.length ? video.tags : undefined,
    studio: video.brand ?? undefined,
  };
}

// ------------------------------------------------------------- the handlers

function enc(value) {
  return encodeURIComponent(value).replace(/%20/g, '+');
}

/** A path segment: `+` means a plus here, not a space. */
function seg(value) {
  return encodeURIComponent(value);
}

function pageFor(skip) {
  const n = Number(skip);
  return Number.isFinite(n) && n > 0 ? Math.floor(n / PAGE_SIZE) + 1 : 1;
}

/**
 * Where a catalog's `search` and `skip` came from: the path segment when the
 * client uses one (`skip=24&search=breaking.json`), the query string when it
 * does not. Either way they arrive as `key=value` pairs.
 */
function parseCatalogExtra(extraPart, url) {
  const extra = {
    search: url.searchParams.get('search') ?? '',
    skip: url.searchParams.get('skip') ?? 0,
  };
  if (!extraPart) return extra;
  for (const pair of decodeURIComponent(extraPart.replace(/\.json$/, '')).split('&')) {
    const at = pair.indexOf('=');
    if (at < 0) continue;
    const key = pair.slice(0, at);
    const value = pair.slice(at + 1);
    if (key === 'search') extra.search = value;
    if (key === 'skip') extra.skip = value;
  }
  return extra;
}

/** One shelf, in the shape the client's rails read. */
async function catalog(id, extra, self) {
  const q = String(extra?.search ?? '').trim();
  const page = pageFor(extra?.skip);
  const search = q ? `&q=${enc(q)}` : '';

  switch (id) {
    case 'anime-trending': {
      const body = await self(`/api/anime/search?page=${page}${search}`);
      return (body?.items ?? []).map((item) => preview(animeMeta(item)));
    }
    // The listing pages carry both kinds of card, so each shelf keeps its own:
    // a shelf that says Films must not hand back a series, and the series
    // shelf asks upstream for its own page with type=tv.
    case 'films-listing': {
      const body = await self(`/api/fmovies/search?page=${page}${search}`);
      return (body?.items ?? [])
        .filter((i) => i.type === 'movie')
        .map((i) => preview(filmMeta(i, { area: 'fm', key: i.slug })));
    }
    case 'series-listing': {
      const body = await self(`/api/fmovies/search?page=${page}&type=tv${search}`);
      return (body?.items ?? [])
        .filter((i) => i.type === 'tv')
        .map((i) => preview(filmMeta(i, { area: 'ft', key: i.slug })));
    }
    case 'adult-newest':
    case 'adult-views': {
      const order = id === 'adult-views' ? 'views' : 'released_at';
      const body = await self(
        `/api/videos?page=${page}&per_page=${PAGE_SIZE}&order_by=${order}&ordering=desc${search}`,
      );
      return (body?.data ?? []).map((item) => preview(adultMeta(item)));
    }
    default:
      return [];
  }
}

/** Everything one title's page needs, including its episode rows. */
async function meta(ref, self) {
  if (ref.area === 'an') {
    const body = await self(`/api/anime/${seg(ref.key)}`);
    const card = body?.details;
    if (!card) return null;

    // Episodes are what turns a series from a poster into something playable;
    // a failure there is not a failure of the page, so it answers empty.
    let videos;
    try {
      const list = await self(`/api/anime/${seg(ref.key)}/episodes`);
      videos = (list?.data ?? [])
        .filter((e) => Number.isFinite(Number(e.number)))
        .map((e) => ({
          id: `${idFor('an', card.id)}:1:${e.number}`,
          title: e.title || `Episode ${e.number}`,
          season: 1,
          episode: Number(e.number),
          thumbnail: e.img ?? undefined,
          released: e.airDate ?? undefined,
        }));
    } catch (e) {
      videos = [];
    }
    return animeMeta(card, { description: card.description, videos });
  }

  if (ref.area === 'fm' || ref.area === 'ft') {
    const type = ref.area === 'fm' ? 'movie' : 'tv';
    const body = await self(`/api/fmovies/${type}/${seg(ref.key)}`);
    const details = body?.details;
    if (!details) return null;

    let videos;
    if (type === 'tv') {
      videos = [];
      // Every season the page names, in order — the ids carry them, so the
      // client can ask for a stream of any of these episodes directly.
      for (const season of (details.seasons ?? []).slice(0, 20)) {
        try {
          const list = await self(`/api/fmovies/tv/${seg(ref.key)}/episodes?season=${season}`);
          for (const row of list?.data ?? []) {
            const number = Number(row.n ?? row.episode);
            if (!Number.isFinite(number)) continue;
            videos.push({
              id: `${idFor('ft', ref.key)}:${season}:${number}`,
              title: row.title || `Episode ${number}`,
              season: Number(season),
              episode: number,
            });
          }
        } catch (e) {
          // One unreadable season does not empty the list.
        }
      }
    }
    return filmMeta(details, ref, videos);
  }

  if (ref.area === 'ad') {
    const video = await self(`/api/videos/${seg(ref.key)}`);
    if (!video?.slug) return null;
    return adultMeta(video);
  }

  return null;
}

/**
 * What can play this id, and how.
 *
 * The 18+ shelf answers with resolved HLS playlists — the relay rewrites every
 * nested URI, so the app's own player fetches them off loopback with no headers
 * of its own. Anime and films answer with embed pages; those travel as
 * `externalUrl` so the client opens them where they actually work, rather than
 * handing a player an HTML document it cannot decode.
 */
async function streams(ref, self, origin) {
  if (ref.area === 'ad') {
    const body = await self(`/api/videos/${seg(ref.key)}/sources`);
    return (body?.sources ?? []).map((s) => {
      const size = s.height ? `${s.height}p` : '';
      // The label is usually the resolution itself; saying it twice helps
      // nobody, so it is dropped when it repeats.
      const label = s.label && s.label.toLowerCase() !== size ? s.label : '';
      return {
        name: 'Hanime',
        description: [label, size].filter(Boolean).join(' · '),
        url: s.url,
      };
    });
  }

  if (ref.area === 'an') {
    const ep = Math.max(1, Number(ref.episode ?? 1) | 0);
    let body;
    try {
      body = await self(`/api/anime/${seg(ref.key)}/player?ep=${ep}`);
    } catch (e) {
      return [];
    }
    return (body?.sources ?? []).map((s) => ({
      name: `Hanime · ${s.label ?? 'player'}`,
      description: 'embed — opens in your browser',
      externalUrl: s.url,
    }));
  }

  if (ref.area === 'fm' || ref.area === 'ft') {
    const type = ref.area === 'fm' ? 'movie' : 'tv';
    const season = Math.max(1, Number(ref.season ?? 1) | 0);
    const episode = Math.max(1, Number(ref.episode ?? 1) | 0);
    let body;
    try {
      body = await self(
        `/api/fmovies/${type}/${seg(ref.key)}/player?season=${season}&episode=${episode}`,
      );
    } catch (e) {
      return [];
    }
    const doors = body?.sources ?? [];

    // The door pages do not hand out a stream; they hand out a player, and the
    // stream URL exists only inside it. `/play/films/…` resolves that on demand
    // (booting a browser for a few seconds) and answers with the playlist — so
    // the film plays in the app's own player instead of a browser tab. It leads
    // the list, because it is the only entry that ends in the app's player; the
    // doors stay behind it, honestly labelled, for the titles it cannot resolve.
    const query = type === 'tv' ? `?season=${season}&episode=${episode}` : '';
    const inApp = doors.some((s) => s.ok !== false)
      ? [{
          name: 'Hanime · in-app',
          description: 'HLS — plays in the player',
          url: `${origin}/play/films/${type}/${seg(ref.key)}${query}`,
        }]
      : [];

    return [
      ...inApp,
      ...doors.map((s) => ({
        name: `Hanime · ${s.label ?? 'door'}`,
        description: s.ok === false
          ? `embed — ${s.note ?? 'not answering'}, opens in your browser`
          : 'embed — opens in your browser',
        externalUrl: s.url,
      })),
    ];
  }

  return [];
}

// ---------------------------------------------------------------- routing

function send(res, status, body, contentType = 'application/json; charset=utf-8') {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, {
    'content-type': contentType,
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
  });
  res.end(text);
}

/**
 * One entry point for /addon/*. `self(path)` runs a GET through this server's
 * own router and answers the parsed JSON — the same routes the web client
 * calls, minus the round trip through the network.
 */
export async function handleAddon(url, res, pathname, self) {
  const parts = pathname.replace(/^\/addon\/?/, '').split('/');

  if (pathname === '/addon/manifest.json' || pathname === '/addon/') {
    return send(res, 200, manifest());
  }

  // /addon/catalog/<type>/<id>.json is the plain form; clients that carry
  // their extra parameters in the path ask for
  // /addon/catalog/<type>/<id>/<skip=..&search=..>.json instead. Both are the
  // same request — the extras are read from the path when they are there and
  // from the query string when they are not.
  if (parts[0] === 'catalog' && (parts.length === 3 || parts.length === 4)) {
    const [, type, idPart, extraPart = ''] = parts;
    if (!idPart.endsWith('.json') && !extraPart.endsWith('.json')) {
      return send(res, 404, { error: 'not_found', pathname });
    }
    const extra = parseCatalogExtra(extraPart, url);
    const metas = await catalog(decodeURIComponent(idPart.replace(/\.json$/, '')), extra, self);
    return send(res, 200, { metas });
  }

  // /addon/meta/<type>/<id>.json and /addon/stream/<type>/<id>.json — the type
  // is the client's view of the id and carries no information of its own.
  if ((parts[0] === 'meta' || parts[0] === 'stream') && parts.length === 3) {
    const ref = parseId(decodeURIComponent(parts[2].replace(/\.json$/, '')));
    if (!ref) return send(res, 404, { error: 'bad_id' });
    if (parts[0] === 'meta') {
      const doc = await meta(ref, self);
      if (!doc) return send(res, 404, { error: 'not_found' });
      return send(res, 200, { meta: doc });
    }
    const list = await streams(ref, self, `${url.protocol}//${url.host}`);
    return send(res, 200, { streams: list });
  }

  return send(res, 404, { error: 'not_found', pathname });
}
