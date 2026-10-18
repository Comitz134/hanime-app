// account-model.js — the shapes this phone and the account agree on.
//
// The account is one library and one set of watch positions for both clients:
// the desktop window (Nuvio's Compose UI, pointed at the same project) and this
// web client. What crosses the wire is not this file's to invent — it is the
// desktop app's own contract, the `sync_*` RPCs in `accounts/sql/`, and the
// payload there is a `LibrarySyncItem` / `WatchProgressSyncEntry`. So this is
// where the phone's records (`htv:library:v1`, the same ones the Library view
// draws) are written into that contract, and where a row that came back is read
// into the phone's shape. Deliberately pure: no storage, no network, no DOM, so
// every rule below can be tested by calling it.
//
// Three things are load-bearing, and each of them is a place the two clients
// could quietly disagree:
//
//   * The id. On the wire a title is `<area>:<key>` — `ha:ad:<hanime slug>` for
//     the 18+ shelf, `ha:an:<anilist id>` for an anime, and an episode of a
//     series is `<id>:<season>:<episode>` (server/src/addon.mjs builds them).
//     On this phone every record is keyed by a bare slug, which is why the
//     translation is here, in both directions.
//
//   * The unit. A position on the wire is *milliseconds* — the desktop sends
//     `entry.lastPositionMs`, `entry.durationMs`, `entry.lastUpdatedEpochMs` —
//     and this phone's is seconds (`lib.positions` is `{ t, d, at }`, read
//     straight off the <video> element). A scale that is assumed instead of
//     converted puts every title at 0% or at the end.
//
//   * The content type. The 18+ shelf is `movie` on the wire even though its
//     catalogue is typed `hentai`; that is the type the desktop's library and
//     player read for these entries (addon.mjs says why), so it is the type a
//     row has to carry for the window to show it as the same title.
//
// The progress key is the desktop's own rule (WatchProgressIdentity.kt):
// `<content_id>` for a film, `<content_id>_s<season>e<episode>` for an episode.
// The 18+ shelf plays whole videos, so this phone's key is always the bare id —
// and a bare id is what the window writes for the same title, which is what
// makes one row the same row on both sides.

/**
 * The 18+ shelf, which is the area whose *positions* this client speaks for:
 * its player is this client's own `<video>`, so it knows the second a title was
 * left at, and its bookmarks and history are the account's too.
 *
 * The anime and films areas are here as well, but for less: they watch through
 * an embed (see below), so what they can give the account is which episode was
 * opened, not where inside it the reader was. Manga is not here at all — the
 * window has no reader, so a chapter would be a row nothing could ever show.
 */
const AREA = 'ad';
const ID_PREFIX = `ha:${AREA}:`;
const CONTENT_TYPE = 'movie';

/**
 * The two areas that play through somebody else's document in a frame.
 *
 * `anime.js` and `movies.js` mount the door's own embed, and a frame cannot be
 * asked what it is playing: `currentTime` is not reachable across origins, and
 * every one of those embeds starts at the beginning anyway. So the honest
 * record for these areas is *which* episode was opened — and that is still the
 * window's own record, because the ids below are the ones
 * `server/src/addon.mjs` builds and the window's progress rows carry:
 *
 *   ha:an:<anilist id>            an anime            (type series)
 *   ha:ft:<slug>                  a series            (type series)
 *   ha:fm:<slug>                  a film              (type movie)
 *
 * with an episode as `<id>:<season>:<episode>` and a progress key as
 * `<id>_s<season>e<episode>` (the window's rule, `WatchProgressIdentity.kt`).
 * One row written here is the row the window's Continue watching rail reads, so
 * an episode opened on this phone is an episode the window offers to resume.
 */
const ANIME_PREFIX = 'ha:an:';
const SERIES_PREFIX = 'ha:ft:';
const FILM_PREFIX = 'ha:fm:';

/** Anime is one season on this catalogue: `addon.mjs` lists every episode it
 *  serves as season 1. */
const ANIME_SEASON = 1;

/** The types the window's library and player read for these two areas. */
const SERIES_TYPE = 'series';
const FILM_TYPE = 'movie';

/**
 * Where an episode stands when the only thing this client knows is that it was
 * opened.
 *
 * One second — not a guess about the reader, but the smallest claim that means
 * "watched" rather than "not watched". The window's rail lists a title it can
 * see progress on (`lastPositionMs > 0`), and its own store writes nothing
 * below a second, so this is exactly the edge of a real record; and one second
 * into an episode is where this phone starts it anyway. `duration` travels as
 * 0 because an embed cannot report one: the window draws no bar, and a duration
 * of nothing can never be read as a finished title.
 */
const STARTED_POSITION_MS = 1_000;

/**
 * The window's completion rule, mirrored (`WatchingPolicies.kt`): an episode is
 * done at ninety percent, and a duration below two minutes is a placeholder or
 * an error clip rather than an episode, so it is never done.
 */
const COMPLETE_FRACTION = 0.9;
const MIN_REAL_DURATION_MS = 121_000;

/**
 * The profile inside the account. The desktop has a profile picker and its rows
 * are indexed 1..N; this phone has no picker, and profile 1 is the one a reader
 * who never touched the window's picker is using.
 */
const PROFILE_ID = 1;

/** The 18+ shelf's slug for a title, or '' — an empty id is never sent. */
function accountIdFor(slug) {
  const key = String(slug ?? '').trim();
  return key ? ID_PREFIX + key : '';
}

/**
 * A slug back out of an id, or null for anything this client cannot draw.
 *
 * The 18+ shelf is the only area whose titles this phone can resolve on its own,
 * so an entry from another area is not an error: it is the desktop's or the
 * phone's films area, and it is passed over rather than guessed at.
 */
function slugFromAccountId(id) {
  const value = String(id ?? '');
  if (!value.startsWith(ID_PREFIX)) return null;
  const key = value.slice(ID_PREFIX.length);
  return key && !key.includes(':') ? key : null;
}

/** The account's id for an anime, or '' when there is no key to send. */
function animeIdFor(key) {
  const trimmed = String(key ?? '').trim();
  return trimmed ? ANIME_PREFIX + trimmed : '';
}

/**
 * The account's id for a film or a series, from this area's own ref —
 * `tv/breaking-bad-1396` or `movie/dune-2021` — or '' when the ref is not one.
 */
function showIdFor(ref) {
  const [type, slug] = String(ref ?? '').split('/');
  const key = String(slug ?? '').trim();
  if (!key) return '';
  if (type === 'tv') return SERIES_PREFIX + key;
  if (type === 'movie') return FILM_PREFIX + key;
  return '';
}

/**
 * The key inside an id of one of the embed areas, or null.
 *
 * An *episode's* id carries `:<season>:<episode>` in its tail, which is not
 * part of the key — a row's `content_id` is the title either way, but a row
 * written by another client is not something to crash over.
 */
function areaKeyFromId(id, prefix) {
  const value = String(id ?? '');
  if (!value.startsWith(prefix)) return null;
  const rest = value.slice(prefix.length);
  const key = rest.split(':')[0];
  return key || null;
}

/** The anilist key inside an anime id, or null for anything else. */
const animeKeyFromId = (id) => areaKeyFromId(id, ANIME_PREFIX);

/** The slug inside a series id, or null. */
const seriesKeyFromId = (id) => areaKeyFromId(id, SERIES_PREFIX);

/** The slug inside a film id, or null. */
const filmKeyFromId = (id) => areaKeyFromId(id, FILM_PREFIX);

/** A stored position's seconds as the milliseconds the wire speaks. */
const millis = (seconds) => Math.round(Number(seconds) * 1000);

/** The wire's milliseconds back into a stored position's seconds. */
const seconds = (millis) => Math.round(Number(millis) / 1000);

/**
 * One favourite as a library row.
 *
 * Only the fields this phone actually holds are sent: the desktop's other
 * columns (background, logo, imdb rating, genres, …) belong to a metadata
 * lookup the window does and the phone does not have, and a column left out
 * keeps whatever the window wrote rather than blanking it. That is why
 * `account-sync.js` pushes the row again when the window's own copy is newer
 * instead of pushing an empty one.
 *
 * @returns {object|null} the payload, or null when the record cannot be one.
 */
function libraryPayload(entry) {
  const contentId = accountIdFor(entry?.slug);
  if (!contentId) return null;
  const addedAt = Number(entry?.at);
  return {
    content_id: contentId,
    content_type: CONTENT_TYPE,
    name: String(entry?.name ?? entry?.slug ?? ''),
    poster: entry?.cover ? String(entry.cover) : null,
    poster_shape: 'POSTER',
    added_at: Number.isFinite(addedAt) && addedAt > 0 ? Math.round(addedAt) : 0,
  };
}

/**
 * Where a title was left, as a watch-progress row.
 *
 * A position that is not a position — a junk record, a duration of zero, a title
 * watched to the end — is refused rather than sent: this phone's own rule is
 * that a finished title has no position (positions.js clears it), and a row
 * that says otherwise would come back as "continue watching" on the window.
 *
 * @returns {object|null} the payload, or null when there is nothing to say.
 */
function progressPayload(slug, position) {
  const contentId = accountIdFor(slug);
  if (!contentId || !position || typeof position !== 'object') return null;
  const t = Number(position.t);
  const d = Number(position.d);
  if (!Number.isFinite(t) || !Number.isFinite(d) || d <= 0 || t < 0 || t >= d) return null;
  const at = Number(position.at);
  return {
    progress_key: contentId,
    content_id: contentId,
    content_type: CONTENT_TYPE,
    video_id: '',
    season: null,
    episode: null,
    position: millis(t),
    duration: millis(d),
    last_watched: Number.isFinite(at) && at > 0 ? Math.round(at) : Date.now(),
  };
}

/**
 * One opening as a history row — the list the phone's Library view draws as
 * "what was opened", which is now the account's list rather than this device's.
 *
 * It carries the brand because the phone draws a card's own line from it, and
 * the library row has no such column: history is its own dataset
 * (`accounts/sql/0007_history.sql`), not a filing and not a progress row.
 *
 * @returns {object|null} the payload, or null when there is nothing to file.
 */
function historyPayload(entry) {
  const contentId = accountIdFor(entry?.slug);
  if (!contentId) return null;
  const openedAt = Number(entry?.at);
  return {
    content_id: contentId,
    content_type: CONTENT_TYPE,
    name: String(entry?.name ?? entry?.slug ?? ''),
    poster: entry?.cover ? String(entry.cover) : null,
    brand: entry?.brand ? String(entry.brand) : null,
    opened_at: Number.isFinite(openedAt) && openedAt > 0 ? Math.round(openedAt) : 0,
  };
}

/**
 * The phone's history against the account's, both ways — the same two rules as
 * the favourites, and the same mark deciding between them.
 *
 * The one difference is the cap: this device keeps the newest `limit` entries
 * (`LIB_MAX_HISTORY` — a shelf, not an archive), while the account keeps every
 * one of them. A row this device keeps only because the account had it belongs
 * to the account (`from: 'account'`), so a list cleared on another phone is
 * cleared here rather than being pushed straight back.
 *
 * @returns {{ history: Array, push: Array }} the new list and the openings the
 *   account has never heard of.
 */
function reconcileHistory(local, remote, limit) {
  const rows = new Map();
  for (const row of Array.isArray(remote) ? remote : []) {
    const slug = slugFromAccountId(row?.content_id);
    if (!slug) continue;
    const openedAt = Number(row?.opened_at) || 0;
    const held = rows.get(slug);
    if (held && held.openedAt >= openedAt) continue;
    rows.set(slug, { slug, row, openedAt });
  }

  const history = [];
  const push = [];
  const seen = new Set();
  for (const entry of Array.isArray(local) ? local : []) {
    if (!entry || !entry.slug || seen.has(entry.slug)) continue;
    seen.add(entry.slug);
    const remoteRow = rows.get(entry.slug);
    if (!remoteRow) {
      if (entry.from === 'account') continue;   // cleared, or removed elsewhere
      history.push(entry);
      push.push(entry);
      continue;
    }
    const fresh = remoteRow.openedAt > (Number(entry.at) || 0);
    history.push({
      ...entry,
      from: 'account',
      at: Math.max(Number(entry.at) || 0, remoteRow.openedAt),
      ...(fresh && remoteRow.row?.name ? { name: String(remoteRow.row.name) } : {}),
      ...(fresh && remoteRow.row?.poster ? { cover: String(remoteRow.row.poster) } : {}),
      ...(fresh && remoteRow.row?.brand ? { brand: String(remoteRow.row.brand) } : {}),
    });
  }

  for (const { slug, row, openedAt } of rows.values()) {
    if (seen.has(slug)) continue;
    seen.add(slug);
    history.push({
      slug,
      name: String(row?.name ?? slug),
      cover: row?.poster ? String(row.poster) : '',
      brand: row?.brand ? String(row.brand) : '',
      at: openedAt,
      from: 'account',
    });
  }

  history.sort((a, b) => (Number(b.at) || 0) - (Number(a.at) || 0));
  const capped = Number.isFinite(limit) && limit > 0 ? history.slice(0, limit) : history;
  return { history: capped, push };
}

/** The rows of a pull that this phone can speak for, keyed by slug. */
function remoteFavorites(rows) {
  const out = new Map();
  for (const row of Array.isArray(rows) ? rows : []) {
    const slug = slugFromAccountId(row?.content_id);
    if (!slug) continue;
    const addedAt = Number(row?.added_at) || 0;
    const held = out.get(slug);
    // A title can only be one row per type, but the account is shared with a
    // client that writes both types for some entries; the newest row is the one
    // that says when this title was filed.
    if (!held || addedAt > held.addedAt) out.set(slug, { slug, row, addedAt });
  }
  return out;
}

/**
 * The phone's favourites against the account's, both ways.
 *
 * Two rules, and the mark that decides between them: a record this phone filed
 * while the account was not looking (`from` unset) is pushed, and a record the
 * *account* filed (`from: 'account'`) that the account no longer has was
 * removed elsewhere and is dropped here. Without the second rule a title
 * un-favourited on the desktop would live on in this phone for ever; without
 * the first, a title favourited on this phone before signing in would never
 * reach the account at all.
 *
 * @returns {{ favorites: Array, push: Array }} the new favourites and the rows
 *   the account has never seen.
 */
function reconcileFavorites(local, remote) {
  const rows = remoteFavorites(remote);
  const favorites = [];
  const push = [];
  const seen = new Set();

  for (const entry of Array.isArray(local) ? local : []) {
    if (!entry || !entry.slug || seen.has(entry.slug)) continue;
    seen.add(entry.slug);
    const fromAccount = entry.from === 'account';
    const remoteRow = rows.get(entry.slug);
    if (!remoteRow) {
      // Gone from the account, or never there: a row the account owns is gone
      // for good, and anything else is this device's own filing, still to send.
      if (fromAccount) continue;
      favorites.push(entry);
      push.push(entry);
      continue;
    }
    const at = Math.max(Number(entry.at) || 0, remoteRow.addedAt);
    // The account's copy of a title carries metadata from a lookup this phone
    // does not do, so when the window's row is newer its name and poster are the
    // better ones — the record stays this device's own either way.
    const fresh = remoteRow.addedAt > (Number(entry.at) || 0);
    favorites.push({
      ...entry,
      from: 'account',
      at,
      ...(fresh && remoteRow.row?.name ? { name: String(remoteRow.row.name) } : {}),
      ...(fresh && remoteRow.row?.poster ? { cover: String(remoteRow.row.poster) } : {}),
    });
  }

  // Titles the account has and this phone does not: a title favourited in the
  // window is in the phone's library too. Name and poster are all the row
  // carries, which is exactly what a card needs to draw it.
  for (const { slug, row, addedAt } of rows.values()) {
    if (seen.has(slug)) continue;
    seen.add(slug);
    favorites.push({
      slug,
      name: String(row?.name ?? slug),
      cover: row?.poster ? String(row.poster) : '',
      brand: '',
      at: addedAt,
      from: 'account',
    });
  }

  // Newest first, the order the Library view draws — the account's additions
  // land where a reader would look for them.
  favorites.sort((a, b) => (Number(b.at) || 0) - (Number(a.at) || 0));
  return { favorites, push };
}

/**
 * The phone's positions against the account's.
 *
 * One row per title and the newest one wins: `last_watched` on the wire is the
 * phone's `at`, both are the moment the title was last being watched, so the
 * comparison needs no clock of its own. A row this phone cannot use is passed
 * over — a finished title (position at the end) is not "continue watching", and
 * a duration of zero is not a duration.
 *
 * @returns {{ positions: object, push: Array }} the new positions and the
 *   entries this phone's own watching is ahead on.
 */
function mergePositions(local, remote) {
  const positions = {};
  for (const slug of Object.keys(local && typeof local === 'object' ? local : {})) {
    const p = local[slug];
    if (p && typeof p === 'object') positions[slug] = p;
  }

  // One row per title, newest first: the same title twice in one pull is one
  // title, and the later watching is the one in progress.
  const known = new Map();
  for (const row of Array.isArray(remote) ? remote : []) {
    const slug = slugFromAccountId(row?.content_id);
    if (!slug) continue;
    const lastWatched = Number(row?.last_watched) || 0;
    const held = known.get(slug);
    if (held && held.lastWatched >= lastWatched) continue;
    known.set(slug, { row, lastWatched });
  }

  const push = [];
  for (const [slug, { row, lastWatched }] of known) {
    const held = positions[slug];
    const heldAt = Number(held?.at) || 0;
    // This phone is ahead on this title: it was watched here later, and its own
    // row is the true one.
    if (held && heldAt > lastWatched) {
      push.push({ slug, position: held });
      continue;
    }
    if (held && heldAt === lastWatched) continue;
    // A row with no position in it says nothing, and reading it as a position
    // of zero would draw a bar at the very start of a title nobody stopped in.
    if (row?.position == null || row?.duration == null) continue;
    const t = seconds(row.position);
    const d = seconds(row.duration);
    if (!Number.isFinite(d) || d <= 0 || !Number.isFinite(t) || t < 0 || t >= d) continue;
    positions[slug] = { t, d, at: lastWatched };
  }

  // Positions this device holds and the account has never heard of: watching
  // that happened here, on its way to the window.
  for (const slug of Object.keys(positions)) {
    if (known.has(slug)) continue;
    if (!progressPayload(slug, positions[slug])) continue;
    push.push({ slug, position: positions[slug] });
  }

  return { positions, push };
}

/* -------------------------------------- the areas that watch through an embed */

/** The window's own key rule (`WatchProgressIdentity.kt`): an episode is
 *  `<title>_s<season>e<episode>`, a film is the title's own id. */
function progressKeyFor(contentId, season, episode) {
  return season === null || episode === null ? contentId : `${contentId}_s${season}e${episode}`;
}

/** One row in the shape every client on the account reads. */
function startedProgressRow(contentId, contentType, videoId, season, episode, lastWatched) {
  return {
    progress_key: progressKeyFor(contentId, season, episode),
    content_id: contentId,
    content_type: contentType,
    video_id: videoId,
    season,
    episode,
    position: STARTED_POSITION_MS,
    duration: 0,
    last_watched: lastWatched,
  };
}

/**
 * An episode opened on this device, as the row the window reads.
 *
 * The write is the *rail's* write, which is where the record comes from: an
 * area's rail keeps one entry per title, so one title is one row on the account
 * too — the episode the reader is on now, replacing the one they left.
 *
 * @returns {object|null} the payload, or null when the entry is not one of the
 *   areas that can speak for it. Manga is the case that matters: the window has
 *   no reader, so a chapter is a row nothing on the other side could ever show.
 */
function activityProgressPayload(entry) {
  const kind = entry?.kind;
  const at = Number(entry?.at);
  const lastWatched = Number.isFinite(at) && at > 0 ? Math.round(at) : Date.now();
  const number = Number(entry?.number);

  if (kind === 'anime') {
    const contentId = animeIdFor(entry?.id);
    if (!contentId || !Number.isInteger(number) || number < 1) return null;
    return startedProgressRow(
      contentId, SERIES_TYPE, `${contentId}:${ANIME_SEASON}:${number}`, ANIME_SEASON, number, lastWatched,
    );
  }

  if (kind === 'show') {
    const contentId = showIdFor(entry?.id);
    if (!contentId) return null;
    const ref = /^s(\d+)e(\d+)$/.exec(String(entry?.ref ?? ''));
    // A film has no episode to resume: it travels as the title itself, which is
    // the row the window writes when it plays one.
    if (!ref) return startedProgressRow(contentId, FILM_TYPE, contentId, null, null, lastWatched);
    const season = Number(ref[1]);
    const episode = Number(ref[2]);
    return startedProgressRow(
      contentId, SERIES_TYPE, `${contentId}:${season}:${episode}`, season, episode, lastWatched,
    );
  }

  return null;
}

/** The account's key for one rail entry, or null when it has none. */
function activityProgressKey(entry) {
  return activityProgressPayload(entry)?.progress_key ?? null;
}

/**
 * True when a row says more than "this episode was opened".
 *
 * This phone can only ever write `STARTED_POSITION_MS` — an embed in a frame
 * has no `currentTime` to read — while the window writes the second it actually
 * stopped at. So a row that carries a real position is the window's own record
 * of somebody's watching, and writing the opening over it would send them back
 * to the start of an episode they were half-way through. Anything that claims
 * more than the opening's one second is that record, whether or not it also
 * reports a duration.
 */
function hasRealPosition(row) {
  const position = Number(row?.position) || 0;
  const duration = Number(row?.duration) || 0;
  return position > STARTED_POSITION_MS || duration > 0;
}

/**
 * The account's rows grouped by the title they are about, newest row first.
 *
 * Only the embed areas' rows are grouped: the 18+ shelf's positions are this
 * client's own list (`mergePositions`) and must not be drawn on another rail.
 * A row with no id, or no moment it was watched, is not a row.
 *
 * @returns {Map<string, Array<object>>} content id → its rows.
 */
function activityTitleGroups(rows) {
  const groups = new Map();
  for (const row of Array.isArray(rows) ? rows : []) {
    const contentId = String(row?.content_id ?? '');
    if (!contentId) continue;
    if (!animeKeyFromId(contentId) && !seriesKeyFromId(contentId) && !filmKeyFromId(contentId)) continue;
    if (!(Number(row?.last_watched) > 0)) continue;
    const held = groups.get(contentId);
    if (held) held.push(row);
    else groups.set(contentId, [row]);
  }
  for (const held of groups.values()) {
    held.sort((a, b) => (Number(b.last_watched) || 0) - (Number(a.last_watched) || 0));
  }
  return groups;
}

/**
 * Which rail entry a title's rows are about — `{ kind, id }`, in this client's
 * own vocabulary — or null when the id is not one of these two areas.
 *
 * Split out from `activityEntryForTitle` because the caller needs the identity
 * before it can decide anything: whether this device already has that title on
 * a rail, and therefore whether its name has to be looked up at all.
 */
function activityIdentityFor(contentId) {
  const animeKey = animeKeyFromId(contentId);
  if (animeKey) return { kind: 'anime', id: animeKey };
  const seriesKey = seriesKeyFromId(contentId);
  if (seriesKey) return { kind: 'show', id: `tv/${seriesKey}` };
  const filmKey = filmKeyFromId(contentId);
  if (filmKey) return { kind: 'show', id: `movie/${filmKey}` };
  return null;
}

/**
 * One title's rows, as the rail entry this device draws — or null when there is
 * nothing left to continue.
 *
 * Three decisions, and each one is the window's own so the two rails agree:
 *
 *   * a row that is *done* is not "continue watching". Ninety percent, and
 *     never for a placeholder duration (`WatchingPolicies.kt`);
 *   * a title whose newest row is done continues at the next episode — that is
 *     what the window's own rail shows for it — but only when this client could
 *     find that the next episode exists (`known.episodes`, which the title's
 *     own page answered). An anime whose last episode was the one finished, or
 *     a film series whose next episode nothing here knows the number of, is not
 *     on the rail at all;
 *   * otherwise the newest row that is still in progress is the entry, whatever
 *     order the account returned its rows in.
 *
 * @param {Array<object>} rows  every row the account holds for one title.
 * @param {{ episodes?: number }} known  what this client resolved about it.
 */
function activityEntryForTitle(rows, known = {}) {
  const list = Array.isArray(rows) ? rows : [];
  const newest = list[0] ?? null;
  if (!newest) return null;

  const contentId = String(newest.content_id ?? '');
  // Every row this entry stands for, so a tap that takes the title off the rail
  // can take the account's rows with it: an up-next card speaks for the episode
  // that was finished, and a card at episode 5 speaks for the episodes behind
  // it — leaving those behind would put the title straight back, one episode
  // earlier, on the next pull.
  const keys = list
    .map((row) => String(row?.progress_key ?? '')
      || progressKeyFor(contentId, Number(row?.season) || null, Number(row?.episode) || null))
    .filter(Boolean);
  const finished = (row) => {
    const position = Number(row?.position) || 0;
    const duration = Number(row?.duration) || 0;
    return position > 0 && duration >= MIN_REAL_DURATION_MS && position / duration >= COMPLETE_FRACTION;
  };

  const episodes = Number(known?.episodes);
  const animeKey = animeKeyFromId(contentId);
  if (animeKey && finished(newest)) {
    const done = Number(newest.episode);
    const next = Number.isInteger(done) && done > 0 ? done + 1 : null;
    if (next === null || !(Number.isFinite(episodes) && next <= episodes)) return null;
    return { kind: 'anime', id: animeKey, number: next, ref: null, at: Number(newest.last_watched) || 0, from: 'account', keys };
  }

  const inProgress = list.find((row) => !finished(row)) ?? null;
  if (!inProgress) return null;
  const at = Number(inProgress.last_watched) || 0;

  if (animeKey) {
    const episode = Number(inProgress.episode);
    const number = Number.isInteger(episode) && episode > 0 ? episode : 1;
    return { kind: 'anime', id: animeKey, number, ref: null, at, from: 'account', keys };
  }

  const seriesKey = seriesKeyFromId(contentId);
  if (seriesKey) {
    const season = Number(inProgress.season);
    const episode = Number(inProgress.episode);
    if (!Number.isInteger(season) || !Number.isInteger(episode)) {
      // A series row with no episode in it: the window's own library entry, not
      // something this phone can open at a place.
      return { kind: 'show', id: `tv/${seriesKey}`, number: 1, ref: null, at, from: 'account', keys };
    }
    return {
      kind: 'show', id: `tv/${seriesKey}`, number: episode, ref: `s${season}e${episode}`, at, from: 'account', keys,
    };
  }

  const filmKey = filmKeyFromId(contentId);
  if (filmKey) return { kind: 'show', id: `movie/${filmKey}`, number: 1, ref: null, at, from: 'account', keys };
  return null;
}

/**
 * This device's rail against the account's, both ways — the same two rules as
 * the favourites and the history, and the same mark deciding between them.
 *
 * An entry this device made while the account was not looking (`from` unset) is
 * pushed, so an episode played here before signing in is not lost — and so is
 * one that is strictly newer here than there, which is what a later episode
 * opened on this phone looks like from the account's side; an entry the
 * *account* owns that the account no longer has was dropped on the other side —
 * the reader finished it or took it off that rail — and goes here too, or the
 * window's rail could never get shorter than this phone's.
 *
 * @returns {{ entries: Array, push: Array }} the new rail and the entries the
 *   account has never seen.
 */
function reconcileActivity(local, remote, limit) {
  const theirs = new Map();
  for (const entry of Array.isArray(remote) ? remote : []) {
    if (!entry || !entry.kind || !entry.id) continue;
    const key = `${entry.kind}:${entry.id}`;
    const held = theirs.get(key);
    if (held && (Number(held.at) || 0) >= (Number(entry.at) || 0)) continue;
    theirs.set(key, entry);
  }

  const entries = [];
  const push = [];
  const seen = new Set();
  for (const entry of Array.isArray(local) ? local : []) {
    if (!entry || !entry.kind || !entry.id) continue;
    const key = `${entry.kind}:${entry.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const remoteEntry = theirs.get(key);
    if (!remoteEntry) {
      if (entry.from === 'account') continue;   // finished, or dropped elsewhere
      entries.push(entry);
      push.push(entry);
      continue;
    }
    // The account's row is the newer watching, and it carries the episode —
    // which is the one thing this phone cannot know about a title it never
    // opened. Its title and cover are the other way round: those come from this
    // side, because the account does not keep them.
    const fresh = (Number(remoteEntry.at) || 0) > (Number(entry.at) || 0);
    // Newer here than there: the reader opened a *later* episode on this phone
    // since the account last heard from it, and the window has to hear about it
    // or the two rails keep two different episodes of one title.
    if (!fresh && (Number(entry.at) || 0) > (Number(remoteEntry.at) || 0)) push.push(entry);
    entries.push({
      ...entry,
      from: 'account',
      at: Math.max(Number(entry.at) || 0, Number(remoteEntry.at) || 0),
      ...(fresh ? { number: remoteEntry.number, ref: remoteEntry.ref } : {}),
      ...(fresh && remoteEntry.title ? { title: String(remoteEntry.title) } : {}),
      ...(fresh && remoteEntry.cover ? { cover: String(remoteEntry.cover) } : {}),
      ...(fresh && Number(remoteEntry.total) > 0 ? { total: Number(remoteEntry.total) } : {}),
      ...(Array.isArray(remoteEntry.keys) && remoteEntry.keys.length ? { keys: remoteEntry.keys } : {}),
    });
  }

  for (const [key, remoteEntry] of theirs) {
    if (seen.has(key)) continue;
    seen.add(key);
    entries.push(remoteEntry);
  }

  // Newest activity first, the order both rails draw — capped when the caller
  // asked for a shelf (`ACTIVITY_MAX`), never by the account's own size.
  entries.sort((a, b) => (Number(b.at) || 0) - (Number(a.at) || 0));
  const capped = Number.isFinite(limit) && limit > 0 ? entries.slice(0, limit) : entries;
  return { entries: capped, push };
}

export {
  AREA,
  CONTENT_TYPE,
  ID_PREFIX,
  PROFILE_ID,
  accountIdFor,
  slugFromAccountId,
  millis,
  seconds,
  libraryPayload,
  historyPayload,
  progressPayload,
  reconcileFavorites,
  reconcileHistory,
  mergePositions,
  animeIdFor,
  showIdFor,
  animeKeyFromId,
  seriesKeyFromId,
  filmKeyFromId,
  activityIdentityFor,
  progressKeyFor,
  activityProgressPayload,
  activityProgressKey,
  hasRealPosition,
  activityTitleGroups,
  activityEntryForTitle,
  reconcileActivity,
  STARTED_POSITION_MS,
};
