// The next-chapter estimate: the arithmetic behind the tile on a manga title
// page. It is pure on purpose — the chapter rows and the clock go in, and what
// comes back is what the page prints — so the three ways the catalogue can
// refuse to answer are pinned here rather than discovered on a phone.

import test from 'node:test';
import assert from 'node:assert/strict';

import { estimateChip, estimateDetail, estimateTitle, nextChapterEstimate } from '../client/src/manga-eta.js';

const DAY = 86400;                 // seconds, which is how mangafire dates a chapter
const NOW = 1_700_000_000_000;     // a fixed clock: nothing here reads the real one

/** Chapters newest first, `gaps` seconds apart, ending `sinceNewest` ago. */
function chapters(gaps, { newest = NOW / 1000 - DAY, status = 'releasing' } = {}) {
  const rows = [{ number: 1, createdAt: Math.round(newest) }];
  let at = newest;
  for (const gap of gaps) {
    at -= gap;
    rows.push({ number: rows.length + 1, createdAt: Math.round(at) });
  }
  return rows;
}

test('a weekly title is estimated a week after its newest chapter', () => {
  const est = nextChapterEstimate(chapters([7 * DAY, 7 * DAY, 7 * DAY, 7 * DAY]), { now: NOW });

  assert.equal(est.cadence, 7 * DAY * 1000);
  // The newest chapter went up a day ago, so a week after it is six days away.
  assert.equal(estimateChip(est, NOW), '≈6d');
  assert.match(estimateDetail(est), /· about every 7 days$/);
  assert.match(estimateDetail(est), /^\d{4}-\d{2}-\d{2} /);
  assert.match(estimateTitle(est, NOW), /estimated around \d{4}-\d{2}-\d{2}/);
});

test('one long gap does not set the rhythm — the median of the gaps does', () => {
  // Five chapters seven days apart, then a scanlation three months late.
  const est = nextChapterEstimate(
    chapters([7 * DAY, 7 * DAY, 7 * DAY, 7 * DAY, 90 * DAY]), { now: NOW });

  assert.equal(est.cadence, 7 * DAY * 1000);
});

test('a daily title says so in hours or days, in the words the tile has room for', () => {
  const rows = chapters([DAY, DAY, DAY, DAY], { newest: NOW / 1000 - DAY / 2 });
  const est = nextChapterEstimate(rows, { now: NOW });

  assert.equal(est.cadence, DAY * 1000);
  // Half a day since the newest chapter, so half a day until the next one.
  assert.equal(estimateChip(est, NOW), '≈12h');
  assert.match(estimateDetail(est), /about every day$/);
});

test('a title that has stopped is not projected, and says N/A', () => {
  for (const status of ['finished', 'Completed', 'on_hiatus', 'discontinued']) {
    const rows = chapters([7 * DAY, 7 * DAY, 7 * DAY, 7 * DAY], { status });
    assert.equal(nextChapterEstimate(rows, { status, now: NOW }), null, status);
  }

  const chip = estimateChip(null, NOW);
  assert.equal(chip, 'N/A');
  assert.equal(estimateDetail(null), '');
  assert.match(estimateTitle(null, NOW), /no estimate/);
});

test('a title that never said still gets an estimate', () => {
  const rows = chapters([7 * DAY, 7 * DAY, 7 * DAY]);
  assert.ok(nextChapterEstimate(rows, { status: '', now: NOW }));
  assert.ok(nextChapterEstimate(rows, { status: undefined, now: NOW }));
});

test('too few dated chapters is no rhythm at all', () => {
  assert.equal(nextChapterEstimate([], { status: 'releasing', now: NOW }), null);
  assert.equal(nextChapterEstimate(chapters([7 * DAY, 7 * DAY]), { now: NOW }), null);
  // Two chapters a year apart, and no dates in the rows to read at all.
  assert.equal(nextChapterEstimate([{ number: 2 }, { number: 1 }], { now: NOW }), null);
});

test('an implausible cadence is refused rather than reported', () => {
  // Three chapters in twenty minutes: a re-upload, not a schedule.
  assert.equal(nextChapterEstimate(chapters([600, 600, 600]), { now: NOW }), null);
  // Two chapters a year and a half apart: a gap, not a rhythm.
  assert.equal(nextChapterEstimate(chapters([540 * DAY, 540 * DAY, 540 * DAY]), { now: NOW }), null);
});

test('an estimate that has already passed reads as due, not as a negative day', () => {
  const rows = chapters([7 * DAY, 7 * DAY, 7 * DAY], { newest: NOW / 1000 - 30 * DAY });
  const est = nextChapterEstimate(rows, { now: NOW });

  assert.ok(est.late > 0, 'the estimate should be in the past');
  assert.equal(estimateChip(est, NOW), 'Any day');
});

test('duplicate timestamps do not count as chapters that were posted', () => {
  const flat = [
    { number: 3, createdAt: 1_000_000 },
    { number: 2, createdAt: 1_000_000 },
    { number: 1, createdAt: 1_000_000 },
  ];
  assert.equal(nextChapterEstimate(flat, { status: 'releasing', now: NOW }), null);
});
