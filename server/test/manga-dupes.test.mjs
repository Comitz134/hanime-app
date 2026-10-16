// Two rows for one chapter is a catalogue fact, not a rendering bug: mangafire
// carries more than one upload of a number, so a title's list can hold "Ch.
// 156" twice with different ids and dates. The list can draw one of them, and
// the reader's step has to walk past the twin — both are decided by the number,
// so both are pinned here, on the rows themselves rather than on a phone.

import test from 'node:test';
import assert from 'node:assert/strict';

import { duplicateCount, stepIndex, visibleChapters } from '../client/src/manga-dupes.js';

/** Rows newest first, the way mangafire returns them. */
function row(number, id, createdAt = 0) {
  return { number, id, createdAt };
}

const LIST = [
  row(156, '9406436', 1_700_000_000),   // the newest upload of 156
  row(156, '9320171', 1_690_000_000),   // an older scanlation of the same chapter
  row(155, '9391653', 1_699_000_000),
  row(155, '9320170', 1_689_000_000),
  row(154, '9378851', 1_698_000_000),
  row(153, '9372830', 1_697_000_000),
  row(153, '9372829', 1_687_000_000),   // the oldest chapter, posted twice
];

test('a chapter posted twice is one row when the reader asks for one row', () => {
  const kept = visibleChapters(LIST, true);
  assert.deepEqual(kept.map((c) => c.number), [156, 155, 154, 153]);
  // The row kept is the one the list already showed first — upstream's own
  // order, which is the newest upload of that number.
  assert.equal(kept[0].id, '9406436');
  assert.equal(kept[1].id, '9391653');
  // The list itself is not edited: opening a hidden copy's row still works.
  assert.equal(LIST.length, 7);
});

test('with the duplicates showing, every row upstream sent is drawn', () => {
  assert.deepEqual(visibleChapters(LIST, false).map((c) => c.id), LIST.map((c) => c.id));
  assert.deepEqual(visibleChapters(LIST, undefined).map((c) => c.id), LIST.map((c) => c.id));
  assert.equal(duplicateCount(LIST), 3);
  assert.equal(duplicateCount(visibleChapters(LIST, true)), 0);
});

test('almost the same number is a different chapter', () => {
  // mangafire numbers specials `14.001`, and a half chapter is a chapter.
  const rows = [row(14, 'a'), row(14.001, 'b'), row(14, 'c'), row(13.5, 'd')];
  assert.deepEqual(visibleChapters(rows, true).map((c) => c.id), ['a', 'b', 'd']);
  assert.equal(duplicateCount(rows), 1);
});

test('a row whose number cannot be read is not merged with anything', () => {
  const rows = [row(undefined, 'a'), row('', 'b'), row(null, 'c'), row(5, 'd'), row(5, 'e')];
  const kept = visibleChapters(rows, true);
  assert.deepEqual(kept.map((c) => c.id), ['a', 'b', 'c', 'd']);
  assert.equal(duplicateCount(rows), 1);
});

test('a step lands on the next chapter, never on the twin of this one', () => {
  // From the newest copy of 156, "older" is 155 — not the other 156.
  assert.equal(stepIndex(LIST, 0, 1), 2);
  // From the hidden twin, it still steps to a different chapter.
  assert.equal(stepIndex(LIST, 1, 1), 2);
  // And "newer" from either copy of 155 lands on 156 — the row the list draws
  // first, not on the other copy of 155 and not on the hidden twin of 156.
  assert.equal(stepIndex(LIST, 2, -1), 0);
  assert.equal(stepIndex(LIST, 3, -1), 0, 'the walk did not start from the row given');
});

test('the ends are chapters too, not row indices', () => {
  // The last two rows are the same chapter, so "older" from either is the end.
  assert.equal(stepIndex(LIST, 6, 1), -1);
  assert.equal(stepIndex(LIST, 5, 1), -1, 'a step onto the last row\'s twin is a dead button');
  // And 154 still steps onto that last chapter — the walk stops at it, it does
  // not stop before it.
  assert.equal(stepIndex(LIST, 4, 1), 5);
  // The same at the other end: both copies of the newest chapter are the top.
  assert.equal(stepIndex(LIST, 0, -1), -1);
  assert.equal(stepIndex(LIST, 1, -1), -1);
});

test('a step off the list, or from nowhere, is no step', () => {
  assert.equal(stepIndex([], 0, 1), -1);
  assert.equal(stepIndex(LIST, -1, 1), -1);
  assert.equal(stepIndex(LIST, LIST.length, 1), -1);
  assert.equal(stepIndex(LIST, 0, 0), -1);
  assert.equal(stepIndex(LIST, 0, undefined), -1);
  assert.equal(stepIndex(LIST, 'not an index', 1), -1);
  assert.equal(stepIndex(null, 0, 1), -1);
});

test('a chapter with no readable number steps one row, like it reads', () => {
  const rows = [row('special', 'a'), row('special', 'b'), row(4, 'c')];
  // Two rows numbered the same unreadable word are two chapters for the step,
  // even though the list (which cannot tell them apart) keeps both.
  assert.equal(stepIndex(rows, 0, 1), 1);
  assert.equal(stepIndex(rows, 1, 1), 2);
  assert.equal(visibleChapters(rows, true).length, 3);
});
