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
 * This client's own area. The phone's bookmarks, history and positions are the
 * 18+ shelf's; anime and manga are tracked on MyAnimeList, and the films area
 * keeps a library of its own (`htv:shows:v1`) with its own refs — none of which
 * this file speaks for.
 */
const AREA = 'ad';
const ID_PREFIX = `ha:${AREA}:`;
const CONTENT_TYPE = 'movie';

/**
 * The profile inside the account. The desktop has a profile picker and its rows
 * are indexed 1..N; this phone has no picker, and profile 1 is the one a reader
 * who never touched the window's picker is using.
 */
const PROFILE_ID = 1;

/** The shelf's slug for a title, or '' — an empty id is never sent. */
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
};
