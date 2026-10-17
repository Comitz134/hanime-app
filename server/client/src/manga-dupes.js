// manga-dupes.js — one chapter per number, and a step that never lands on the
// twin of the chapter already on screen.
//
// mangafire carries more than one upload of the same chapter — two scanlations
// racing, a re-upload, a bulk drop of an older group — so a title's list can
// hold "Ch. 156" twice, next to each other, with different chapter ids and
// different dates. The rows are honest (they are two things upstream sent), but
// two things about reading them are not:
//
//   * a title with 328 rows and 172 chapters is a list nobody can scan, and
//     every number appears twice for no reason the reader can act on;
//   * stepping through them is worse than useless — "Older →" lands on the twin
//     of the chapter you are already reading, which reads as a button that did
//     nothing at all.
//
// Both are decided by the *number*, which is what the reader counts in, what
// the row prints, and what the read-mark is kept by (manga-read.js). Nothing
// here touches the network, the DOM or storage: the rows go in and the rows
// come out, so the rule can be tested on its own.

/**
 * A chapter's number as a key, or null when the row has no readable number.
 *
 * Numbers compare as numbers, so "14" and "14.0" are one chapter — and
 * mangafire's `14.001` specials stay their own chapter, which is what the list
 * has always shown. A row whose number cannot be read is not another copy of
 * anything: it is never merged and it hides nothing, because two rows with a
 * blank number are far more likely to be two chapters than one.
 */
function numberKey(chapter) {
  const raw = chapter?.number;
  if (raw === null || raw === undefined) return null;
  // `Number('')` and `Number(null)` are both 0, so the text has to be checked
  // before it is converted: otherwise every unnumbered row in the catalogue is
  // quietly filed as chapter zero and merged with its neighbours.
  const text = String(raw).trim();
  if (!text) return null;
  const n = Number(text);
  return Number.isFinite(n) ? String(n) : null;
}

/**
 * The rows the list draws: every row, or the first row of each number.
 *
 * "First" is upstream's order, which is the order the list shows — newest
 * chapter number first, and within one number the upload that went up last.
 * So what is kept is the newest copy of a chapter, and what is hidden is the
 * older duplicate of it.
 */
function visibleChapters(chapters, hideDuplicates) {
  const rows = Array.isArray(chapters) ? chapters : [];
  if (!hideDuplicates) return rows;
  const seen = new Set();
  return rows.filter((c) => {
    const key = numberKey(c);
    if (key === null) return true;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/**
 * How many chapters a title has, counted the way a reader counts them: by the
 * number on its newest one, not by the rows it took to list them.
 *
 * The row count is the one number here that a duplicate corrupts: a title of
 * 254 chapters with a second upload of forty of them is 294 rows, and "294
 * chapters" is simply not true of it. The chapter number is: the list ends at
 * `Ch. 254`, and that is the size of the title whether one upload of a chapter
 * came back or three did.
 *
 * A row with no readable number cannot be counted that way — it is a chapter
 * whose number the catalogue never said, so it is added on top rather than
 * lost. A half chapter (`14.5`) is rounded to the nearest whole chapter: a
 * title whose newest is `Ch. 254.5` has 255 chapters' worth of reading in it,
 * and a screen that says "254.5 chapters" is answering a question nobody asked.
 * `14.001` is a special that belongs to chapter 14, so it still counts as 14.
 */
function chapterTotal(chapters) {
  const rows = Array.isArray(chapters) ? chapters : [];
  let last = 0;
  let unnumbered = 0;
  for (const row of rows) {
    const key = numberKey(row);
    // A prologue numbered zero is where the run of chapters starts, not a
    // chapter in front of it: the newest number is already the title's size.
    if (key === null) unnumbered += 1;
    else last = Math.max(last, Number(key));
  }
  const total = Math.round(last) + unnumbered;
  // A title whose only chapter is the prologue still has one chapter in it.
  return rows.length && total < 1 ? 1 : total;
}

/** How many rows hiding the duplicates would leave out — 0 when there are none. */
function duplicateCount(chapters) {
  const rows = Array.isArray(chapters) ? chapters : [];
  return rows.length - visibleChapters(rows, true).length;
}

/**
 * Where a step from `from` lands, or -1 when there is no chapter that way.
 *
 * A step is one *chapter*, not one row: it keeps walking past rows carrying the
 * number it started on, so the button that says "Older" never lands on another
 * copy of the chapter already open. The target is then the copy the list draws
 * first — upstream's order, the newest upload — so a step lands on a row the
 * reader can see even with the duplicates hidden, and on the row the list
 * marks as read.
 *
 * The walk is bounded by the list, so a step off either end answers -1 — which
 * is also what decides whether the button is dead, rather than the row index,
 * because the last row of a title can be the twin of its last chapter.
 */
function stepIndex(chapters, from, delta) {
  const rows = Array.isArray(chapters) ? chapters : [];
  const at = Number(from);
  const dir = Number(delta);
  if (!Number.isFinite(at) || at < 0 || at >= rows.length) return -1;
  if (!Number.isFinite(dir) || dir === 0) return -1;
  const here = numberKey(rows[at]);
  let i = at + dir;
  // A chapter with no readable number has no twins to walk past, so the step is
  // one row — the same answer the read-mark gives it (manga-read.js).
  while (here !== null && i >= 0 && i < rows.length && numberKey(rows[i]) === here) i += dir;
  if (i < 0 || i >= rows.length) return -1;
  const reached = numberKey(rows[i]);
  while (reached !== null && i > 0 && numberKey(rows[i - 1]) === reached) i -= 1;
  return i;
}

export { chapterTotal, duplicateCount, stepIndex, visibleChapters };
