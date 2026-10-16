// manga-read.js — the chapters this device has already read.
//
// The reading rail (activity.js) keeps one record per title: the chapter that
// was open last. That answers "where was I", and it is not enough to answer
// "have I read this one" — a reader who jumps back to an earlier chapter, or
// forward past one, is invisible to a single bookmark. The chapter list is the
// place that question is actually asked, every time a title page is opened:
// a hundred rows of the same weight, half of them already read.
//
// So this is the second note, one set of chapter numbers per title, in the
// same guarded localStorage as the rest of the device's own state. Numbers,
// not mangafire's chapter ids: upstream re-uploads chapters and hands the same
// chapter a new id, and it is the *number* — the "Ch. 12" on the row, and the
// one thing a reader has in their head — that survives that.
//
// Nothing here talks to a network or a server, and nothing is ever deleted on
// its own: this is a device's memory of its own reading, so it is capped the
// way the other local lists are — oldest title first — rather than trimmed at
// some clever boundary.
//
// There is a second thing the list has to know, and it does not come from this
// device: an account's MyAnimeList entry carries how far the reader got there
// (`num_chapters_read`), which the tracking slot reads off each title page. A
// reader who has been reading on MAL's own site, or on another phone, has
// chapters read that this device never opened — and a list that only greyed
// what it watched itself would call forty-seven of them unread.
//
// So a title also remembers a *watermark*: the number MAL says the reading got
// to, and every chapter at or below it is read. It is kept as one number rather
// than by filing every chapter under it — the count is what MAL has, it can
// only go one way, and it costs nothing when it does not change. The app writes
// a chapter's own *number* into MAL's progress (see autoAdvance in mal.js), so
// the watermark is that same identity: chapters 1..N are read, and no prologue
// is implied, because nothing this app writes to MAL can say a prologue was
// read.

const KEY = 'htv:manga-read:v1';

/** Titles kept. A reader with more than this has stopped reading some of them. */
const MAX_TITLES = 300;

/** Chapter numbers kept per title, newest first. A thousand-chapter title
 *  still fits, with room for the chapters of a second reading. */
const MAX_CHAPTERS = 4000;

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    const rows = raw ? JSON.parse(raw) : {};
    // One record per title, keyed by id. Anything else in that slot — an
    // array, a string, a number from some older shape — is not a record, and
    // reading it would be reading a title that does not exist.
    return rows && typeof rows === 'object' && !Array.isArray(rows) ? rows : {};
  } catch (e) {
    return {};
  }
}

function save(rows) {
  try {
    localStorage.setItem(KEY, JSON.stringify(rows));
  } catch (e) { /* unwritable */ }
}

/** The chapter numbers read for a title, newest first. */
function readNumbers(titleId) {
  const id = String(titleId ?? '');
  if (!id) return [];
  const entry = load()[id];
  return Array.isArray(entry?.n) ? entry.n.filter((n) => Number.isFinite(Number(n))) : [];
}

/**
 * How far MyAnimeList says the reading got in a title: the number of the last
 * chapter read there, or 0 when the account has nothing for it. A watermark
 * of 0 is no watermark — nothing is read at or below it.
 */
function progressFor(titleId) {
  const id = String(titleId ?? '');
  if (!id) return 0;
  const upTo = Number(load()[id]?.p);
  return Number.isFinite(upTo) && upTo >= 1 ? Math.floor(upTo) : 0;
}

/**
 * Keeps what MyAnimeList says the reading got to, per title.
 *
 * Called where that number arrives — the tracking slot on a title page, once
 * the reader's own entry has been read back. It can move down as well as up:
 * progress on MAL is the account's, and a title taken off the list stops
 * greying anything. It never touches the chapters this device opened itself —
 * those are this device's memory, and one is not the other.
 *
 * @returns {boolean} true when the stored watermark changed.
 */
function setProgress(titleId, upTo) {
  const id = String(titleId ?? '');
  const n = Number(upTo);
  // Only a number is progress. An entry with no count, or one that arrived as
  // something else, must not clear a watermark that was true a moment ago.
  if (!id || !Number.isFinite(n) || n < 0) return false;

  const rows = load();
  const known = rows[id] && typeof rows[id] === 'object' && !Array.isArray(rows[id]);
  const held = known && Number.isFinite(Number(rows[id].p)) ? Math.floor(Number(rows[id].p)) : 0;
  const wanted = n >= 1 ? Math.floor(n) : 0;
  // Nothing to say, and nobody to say it to: a title the account is not reading
  // does not get a record of its own just for being opened.
  if (held === wanted && (known || wanted === 0)) return false;

  const entry = known ? { ...rows[id] } : { n: [], at: 0 };
  if (!Array.isArray(entry.n)) entry.n = [];
  if (wanted >= 1) entry.p = wanted;
  else delete entry.p;
  entry.at = stamp(rows);
  rows[id] = entry;
  prune(rows);
  save(rows);
  return true;
}

/**
 * Whether this chapter of this title is read: the device opened it, or the
 * account's own list says the reading got at least this far.
 *
 * A number that is not a number is not a chapter — the chapter list draws
 * whatever upstream sent, and an unreadable number cannot be remembered, so
 * it must not be reported as read either.
 */
function isRead(titleId, number) {
  const n = Number(number);
  if (!titleId || !Number.isFinite(n)) return false;
  if (readNumbers(titleId).includes(n)) return true;
  const upTo = progressFor(titleId);
  return n >= 1 && n <= upTo;
}

/**
 * Files a chapter as read.
 *
 * Called where the reading *is* — the moment a chapter renders — so the mark
 * cannot disagree with what was on screen. Reading a chapter again is not a
 * second entry, and moves nothing: the list is the set of chapters read, and
 * the order inside it is only how the cap takes its space back.
 *
 * @returns {boolean} true when the chapter is read by the time this returns.
 */
function markRead(titleId, number) {
  const id = String(titleId ?? '');
  const n = Number(number);
  // Zero is a chapter (mangafire numbers prologues "Ch. 0"), and noteOpen
  // accepts it; the two have to agree, or a prologue is remembered and then
  // drawn as unread.
  if (!id || !Number.isFinite(n) || n < 0) return false;

  const rows = load();
  const entry = rows[id] && Array.isArray(rows[id].n) ? rows[id] : { n: [], at: 0 };
  if (!entry.n.includes(n)) entry.n = [n, ...entry.n].slice(0, MAX_CHAPTERS);
  entry.at = stamp(rows);
  rows[id] = entry;
  prune(rows);
  save(rows);
  return true;
}

/**
 * Now, but never twice the same.
 *
 * The stamp is what decides which title makes room when there are too many,
 * and two titles read inside the same millisecond — a script, a fast reader, a
 * test — would otherwise be sorted arbitrarily and the newest of them could be
 * the one forgotten. One millisecond apart is still a true order of reading.
 */
function stamp(rows) {
  let newest = 0;
  for (const id of Object.keys(rows)) newest = Math.max(newest, Number(rows[id]?.at ?? 0));
  return Math.max(Date.now(), newest + 1);
}

/** Makes room for a new title by forgetting the one read longest ago. */
function prune(rows) {
  const ids = Object.keys(rows);
  if (ids.length <= MAX_TITLES) return rows;
  ids.sort((a, b) => Number(rows[b]?.at ?? 0) - Number(rows[a]?.at ?? 0));
  for (const id of ids.slice(MAX_TITLES)) delete rows[id];
  return rows;
}

export {
  KEY as MANGA_READ_KEY,
  MAX_TITLES as MANGA_READ_MAX_TITLES,
  isRead,
  markRead,
  progressFor,
  readNumbers,
  setProgress,
};
