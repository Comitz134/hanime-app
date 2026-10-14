// manga-eta.js — when the next chapter is due, read off the title's own rhythm.
//
// mangafire says nothing about the future: there is no schedule endpoint and no
// next-chapter field, only the chapters already posted and the date each one
// went up. What a serialised title does have is a rhythm, and a rhythm projected
// forward is the only honest answer available here — so the estimate is the
// median gap between the recent chapters applied to the newest one.
//
// "Estimated" is doing real work in that sentence, which is why this is a
// separate module with no DOM in it: the arithmetic is the part that can be
// wrong, so it is the part that is testable. The caller hands in the chapter
// rows and the clock; nothing here reads storage, the network or the page.
//
// Three refusals matter as much as the estimate itself, and each one is a real
// shape a catalogue returns:
//
//   * a title that has stopped is not projected — its status says so
//     (mangafire's own word: `finished`, `on_hiatus`, `discontinued`), and the
//     button reads N/A instead;
//   * a title with too few dated chapters has no rhythm to read — two chapters
//     a year apart are a fact, not a cadence;
//   * a "cadence" faster than a couple of hours or slower than a year is a
//     re-upload or a gap in the scanlation, not a schedule, and projecting
//     from it would print a confident date that means nothing.

const HOUR = 3600000;
const DAY = 86400000;

/** Statuses that mean nobody is going to post another chapter. Everything
 *  else — including a title that never said — is treated as still running,
 *  because refusing to estimate is the unhelpful answer there. */
const STOPPED = new Set([
  'finished', 'completed', 'complete', 'on_hiatus', 'hiatus',
  'discontinued', 'cancelled', 'canceled', 'dropped', 'axed',
]);

/** The chapter dates, in seconds and newest first — the list's own order. */
function dated(chapters, span) {
  return (chapters ?? [])
    .map((c) => Number(c?.createdAt))
    .filter((t) => Number.isFinite(t) && t > 0)
    .sort((a, b) => b - a)
    .slice(0, span);
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[mid]
    : Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

/**
 * The next chapter, estimated, or null when this title cannot be estimated.
 *
 * @param {Array<{createdAt?: number}>} chapters newest first, as mangafire
 *        returns them — `createdAt` in seconds.
 * @param {{ status?: string, now?: number, wanted?: number, span?: number }} [opts]
 *        `wanted` is how many gaps make a rhythm worth reading (three, so a
 *        single lazy week cannot carry the estimate on its own); `span` is how
 *        many chapters back to look.
 * @returns {{ at: number, cadence: number, late: number }|null} `at` and
 *        `late` in epoch milliseconds, `cadence` the estimated gap between
 *        chapters. `late` is positive once the estimated moment has passed.
 */
function nextChapterEstimate(chapters, opts = {}) {
  const { status = '', now = Date.now(), wanted = 3, span = 6 } = opts;
  if (STOPPED.has(String(status ?? '').trim().toLowerCase())) return null;

  const dates = dated(chapters, span);
  if (dates.length < wanted + 1) return null;

  const gaps = [];
  for (let i = 0; i + 1 < dates.length; i += 1) {
    const gap = dates[i] - dates[i + 1];
    if (gap > 0) gaps.push(gap);
  }
  if (gaps.length < wanted) return null;

  const cadence = median(gaps);                       // seconds
  if (!(cadence >= 2 * 3600 && cadence <= 365 * DAY / 1000)) return null;

  const at = (dates[0] + cadence) * 1000;             // seconds -> milliseconds
  return { at, cadence: cadence * 1000, late: now - at };
}

/** The estimate in the four characters a tile can hold: `≈3d`, `≈9h`, `N/A`. */
function estimateChip(est, now = Date.now()) {
  if (!est) return 'N/A';
  const left = est.at - now;
  if (left <= 0) return 'Any day';
  if (left < 90 * 60000) return '≈1h';
  // Hours only while there is less than a day of them: "≈46h" is a number the
  // reader has to divide, where "≈2d" is the answer to "when".
  if (left < DAY) return `≈${Math.round(left / HOUR)}h`;
  if (left < 60 * DAY) return `≈${Math.round(left / DAY)}d`;
  return `≈${Math.round(left / (30 * DAY))}mo`;
}

/** The same estimate as a details row: the day, and the rhythm it came from. */
function estimateDetail(est) {
  if (!est) return '';
  const day = new Date(est.at).toISOString().slice(0, 10);
  const cycle = est.cadence >= 2 * DAY ? `about every ${Math.round(est.cadence / DAY)} days`
    : est.cadence >= DAY ? 'about every day'
      : `about every ${Math.max(1, Math.round(est.cadence / HOUR))} hours`;
  return `${day} · ${cycle}`;
}

/** And in a sentence, for the tile's own label when it is read aloud. */
function estimateTitle(est, now = Date.now()) {
  if (!est) return 'Next chapter: no estimate — this title has no chapter rhythm to read.';
  const day = new Date(est.at).toISOString().slice(0, 10);
  return `${estimateChip(est, now)} — the next chapter is estimated around ${day}, `
    + `read off the gap between the chapters already posted.`;
}

export { nextChapterEstimate, estimateChip, estimateDetail, estimateTitle };
