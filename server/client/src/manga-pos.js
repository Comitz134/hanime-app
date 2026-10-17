// manga-pos.js — where in a chapter this device stopped reading.
//
// The reading rail knows *which* chapter was opened last; it cannot know that
// the chapter was read to two thirds. A long strip is a hundred screens of art,
// so reopening it at the top is reopening at the wrong place — this is the
// other half of "pick up where you left off", and it is this device's own note,
// in the same guarded localStorage as the rest (positions.js keeps the video
// half of exactly this idea).
//
// A position is a page and a fraction of that page, never a pixel offset. The
// same chapter is a different number of pixels once the phone is unfolded, the
// reader's own settings change — margin, fit, layout — or the pages arrive in a
// different size than last time; the page and the fraction survive all four.
//
// The record also carries how many pages the chapter had, which is the whole of
// what "how far through" means for a chapter: pages.js cannot know it, because
// a chapter's page count is only known once its pages have been fetched.

const MANGA_POS_KEY = 'htv:manga-pos:v1';
/** Enough rows for months of reading, small enough to stay a localStorage
 *  entry rather than a database. The oldest fall off the end. */
const MAX = 400;

function load() {
  try {
    const raw = localStorage.getItem(MANGA_POS_KEY);
    const rows = raw ? JSON.parse(raw) : {};
    return rows && typeof rows === 'object' ? rows : {};
  } catch (e) {
    return {};
  }
}

function save(rows) {
  try { localStorage.setItem(MANGA_POS_KEY, JSON.stringify(rows)); } catch (e) { /* unwritable */ }
}

const clampInt = (value, max) => Math.max(0, Math.min(max, Math.round(Number(value) || 0)));

/**
 * Files where the reader has got to in a chapter.
 *
 * `p` is the page (0-based) at the top of the screen and `f` is how far into
 * that page they are. A chapter with one page left and a fraction of 0 is a
 * chapter read to the end — the caller decides, not this file.
 */
function noteSpot(chapterId, { hid, p, f, n } = {}) {
  const key = String(chapterId ?? '');
  if (!key) return;
  const page = clampInt(p, 10_000);
  const frac = Math.max(0, Math.min(0.999, Number(f) || 0));
  const count = clampInt(n, 10_000);
  const rows = load();
  rows[key] = {
    hid: String(hid ?? ''),
    p: page,
    f: Math.round(frac * 1000) / 1000,
    n: count,
    at: Date.now(),
  };
  // The oldest entries are dropped by when they were written, not by insertion
  // order: a chapter reopened is a new row in a fresh object, so the order of
  // the keys is already newest-first for everything but a long-abandoned one.
  const keys = Object.keys(rows);
  if (keys.length > MAX) {
    keys.map((k) => [k, Number(rows[k]?.at) || 0])
      .sort((a, b) => b[1] - a[1])
      .slice(MAX)
      .forEach(([k]) => { delete rows[k]; });
  }
  save(rows);
}

/** The spot for a chapter, or null when this device has never read it. */
function spotFor(chapterId) {
  const row = load()[String(chapterId ?? '')];
  if (!row || !Number.isFinite(Number(row.p))) return null;
  return {
    p: clampInt(row.p, 10_000),
    f: Math.max(0, Math.min(0.999, Number(row.f) || 0)),
    n: clampInt(row.n, 10_000),
    hid: String(row.hid ?? ''),
    at: Number(row.at) || 0,
  };
}

/** How far through a chapter a spot is, 0..1, or 0 when nothing is known. */
function readPercent(chapterId) {
  const spot = spotFor(chapterId);
  if (!spot || !spot.n) return 0;
  return Math.min(1, (spot.p + spot.f) / spot.n);
}

function dropSpot(chapterId) {
  const key = String(chapterId ?? '');
  const rows = load();
  if (!rows[key]) return;
  delete rows[key];
  save(rows);
}

export { MANGA_POS_KEY, MAX as MANGA_POS_MAX, noteSpot, spotFor, readPercent, dropSpot };
