// Which chapters this device has read is memory, not decoration: the chapter
// list greys a row because this file said so. So it gets a behavioural test,
// not a presence check — the module has one import-free surface and touches
// nothing but localStorage, which means its source can be run verbatim against
// a storage stub and the answers asserted: a chapter filed once is read, the
// same number on another title is not, a prologue (chapter 0) is a chapter,
// and a device that refuses writes refuses the memory rather than the reader.
//
// The caps are asserted where they are decided: the newest chapter numbers are
// the ones kept per title, and the title read longest ago is the one that makes
// room when there are too many titles.
//
// The one thing this test does not do is render: the greying itself is asserted
// against the shipped bundle in web-client.test.mjs.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE = path.resolve(HERE, '../client/src/manga-read.js');

// The module's own export list is the only ESM syntax in it. It is replaced
// with a hand-off through the context, so the test calls the real functions
// rather than a copy of them — and a renamed export breaks this loudly.
const source = fs.readFileSync(SOURCE, 'utf8').replace(
  /export\s*\{[^}]*\};?/,
  'globalThis.__read = { isRead, markRead, readNumbers, setProgress, progressFor,'
  + ' KEY, MAX_TITLES, MAX_CHAPTERS };',
);
assert.ok(!/^\s*export\b/m.test(source), 'the export list was not replaced');

/** One device: a storage, and the module loaded against it. */
function device() {
  const entries = new Map();
  const localStorage = {
    getItem: (key) => (entries.has(String(key)) ? entries.get(String(key)) : null),
    setItem: (key, value) => { entries.set(String(key), String(value)); },
    removeItem: (key) => { entries.delete(String(key)); },
  };
  const context = vm.createContext({ localStorage });
  new vm.Script(source).runInContext(context);
  return { api: context.__read, entries, localStorage };
}

/** A device whose storage throws — private mode, a full quota, no storage at all. */
function readOnlyDevice() {
  const context = vm.createContext({
    localStorage: {
      getItem: () => { throw new Error('denied'); },
      setItem: () => { throw new Error('denied'); },
      removeItem: () => { throw new Error('denied'); },
    },
  });
  new vm.Script(source).runInContext(context);
  return context.__read;
}

test('a fresh device has read nothing', () => {
  const { api } = device();
  assert.equal(api.isRead('1234', 5), false);
  assert.equal(api.readNumbers('1234').length, 0);
});

test('a chapter that was opened is read, and only that chapter', () => {
  const { api } = device();
  assert.equal(api.markRead('1234', 12), true);

  assert.equal(api.isRead('1234', 12), true);
  // The chapters around it are untouched: a bookmark is where the reading
  // stopped, and this is the set of chapters actually opened.
  assert.equal(api.isRead('1234', 11), false);
  assert.equal(api.isRead('1234', 13), false);
  // The same number on another title is another chapter.
  assert.equal(api.isRead('9999', 12), false);
});

test('a prologue is a chapter, and nothing else is', () => {
  const { api } = device();
  // mangafire numbers prologues "Ch. 0", and the rail already accepts one; a
  // reader who opened it must not get a list that calls it unread.
  assert.equal(api.markRead('1234', 0), true);
  assert.equal(api.isRead('1234', 0), true);

  // A chapter without a number is not a chapter this device can remember.
  assert.equal(api.markRead('1234', undefined), false);
  assert.equal(api.markRead('1234', 'later'), false);
  assert.equal(api.markRead('', 4), false);
  assert.equal(api.isRead('1234', undefined), false);
});

test('a chapter read twice is one chapter', () => {
  const { api } = device();
  api.markRead('1234', 7);
  api.markRead('1234', 7);
  assert.equal(api.readNumbers('1234').length, 1);
  assert.equal(api.isRead('1234', 7), true);
});

test('what was read survives the app closing', () => {
  const { api, entries } = device();
  api.markRead('1234', 3);

  // A restart is a fresh load of the module against the same storage.
  const context = vm.createContext({
    localStorage: {
      getItem: (key) => (entries.has(String(key)) ? entries.get(String(key)) : null),
      setItem: (key, value) => { entries.set(String(key), String(value)); },
      removeItem: (key) => { entries.delete(String(key)); },
    },
  });
  new vm.Script(source).runInContext(context);
  assert.equal(context.__read.isRead('1234', 3), true);
});

test('the newest chapter numbers are the ones a title keeps', () => {
  const { api, entries } = device();
  // A thousand-chapter title, filled to its cap, then read from again.
  const key = api.KEY;
  entries.set(key, JSON.stringify({
    '1234': { n: Array.from({ length: api.MAX_CHAPTERS }, (_, i) => i), at: Date.now() },
  }));

  assert.equal(api.markRead('1234', 999_999), true);
  const numbers = api.readNumbers('1234');
  assert.equal(numbers.length, api.MAX_CHAPTERS);
  assert.equal(numbers[0], 999_999, 'the chapter just read is not the one kept');
  assert.equal(numbers.includes(api.MAX_CHAPTERS - 1), false, 'the oldest chapter was not let go');
});

test('a device with too many titles forgets the one read longest ago', () => {
  const { api, entries } = device();
  const total = api.MAX_TITLES + 5;
  for (let i = 0; i < total; i += 1) api.markRead(`title-${i}`, 1);

  assert.equal(api.isRead('title-0', 1), false, 'the oldest title was kept');
  assert.equal(api.isRead(`title-${total - 1}`, 1), true, 'the newest title was lost');

  const rows = JSON.parse(entries.get(api.KEY));
  const ids = Object.keys(rows);
  assert.equal(ids.length, api.MAX_TITLES, 'the cap is not the one it promises');
  // Every title still held has something in it: the cap takes whole titles,
  // never the chapters of one.
  for (const id of ids) assert.equal(rows[id].n.includes(1), true, `${id} kept no chapters`);
});

test("the account's own list is read too, and the list is drawn that way", () => {
  const { api } = device();
  assert.equal(api.setProgress('1234', 12), true);
  assert.equal(api.progressFor('1234'), 12);

  // Everything at or below it is read, and the chapters above it are not: this
  // is a watermark, not a bookmark that moved the reading to chapter 12.
  assert.equal(api.isRead('1234', 1), true);
  assert.equal(api.isRead('1234', 12), true);
  assert.equal(api.isRead('1234', 13), false);
  // A prologue is *not* implied by it: the app writes a chapter's own number
  // into MAL's progress, and nothing it writes can say a prologue was read.
  assert.equal(api.isRead('1234', 0), false);

  // The same number on another title is another title.
  assert.equal(api.setProgress('9999', 3), true);
  assert.equal(api.isRead('1234', 3), true);
  assert.equal(api.isRead('9999', 3), true);
  assert.equal(api.isRead('9999', 4), false);
});

test('the watermark is one number, not a chapter filed per number', () => {
  const { api, entries } = device();
  api.setProgress('1234', 3000);

  // A three-thousand-chapter watermark costs one record: nothing is filed, and
  // the cap on chapters cannot shrink it.
  const row = JSON.parse(entries.get(api.KEY))['1234'];
  assert.equal(row.p, 3000);
  assert.deepEqual(row.n, []);
  assert.equal(api.readNumbers('1234').length, 0);
  assert.equal(api.isRead('1234', 3000), true);
  assert.equal(api.isRead('1234', 3001), false);
});

test('a watermark that is not a number is not a watermark', () => {
  const { api, entries } = device();
  for (const junk of [undefined, null, 'later', NaN, -1, {}, []]) {
    assert.equal(api.setProgress('1234', junk), false, `${junk} was taken as progress`);
  }
  assert.equal(api.progressFor('1234'), 0);
  assert.equal(api.isRead('1234', 1), false);
  // A title the account is not reading does not get a record of its own.
  assert.equal(entries.get(api.KEY), undefined);

  // An entry in the storage that is not a record is not read as one either.
  entries.set(api.KEY, JSON.stringify({ '1234': { n: [], p: 'twelve' } }));
  assert.equal(api.progressFor('1234'), 0);
  assert.equal(api.isRead('1234', 5), false);
});

test("the account's list and this device's marks are two memories, and neither erases the other", () => {
  const { api } = device();
  api.markRead('1234', 20);
  api.setProgress('1234', 12);

  // The chapter this device opened, above the watermark, stays read.
  assert.equal(api.isRead('1234', 20), true);
  assert.equal(api.isRead('1234', 12), true);
  assert.equal(api.isRead('1234', 15), false);

  // Taking the title off the list takes the watermark away and nothing else:
  // the device's own chapters were not the account's to remove.
  assert.equal(api.setProgress('1234', 0), true);
  assert.equal(api.progressFor('1234'), 0);
  assert.equal(api.isRead('1234', 20), true);
  assert.equal(api.isRead('1234', 12), false);

  // And the watermark moving down does not un-file what was filed either.
  api.setProgress('1234', 12);
  api.setProgress('1234', 5);
  assert.equal(api.isRead('1234', 7), false);
  assert.equal(api.isRead('1234', 20), true);
});

test('saying the same thing twice writes nothing, and the watermark survives a restart', () => {
  const { api, entries } = device();
  api.setProgress('1234', 7);
  const first = entries.get(api.KEY);
  assert.equal(api.setProgress('1234', 7), false, 'an unchanged watermark was written again');
  assert.equal(entries.get(api.KEY), first, 'the record was rewritten for no reason');

  const context = vm.createContext({
    localStorage: {
      getItem: (key) => (entries.has(String(key)) ? entries.get(String(key)) : null),
      setItem: (key, value) => { entries.set(String(key), String(value)); },
      removeItem: (key) => { entries.delete(String(key)); },
    },
  });
  new vm.Script(source).runInContext(context);
  assert.equal(context.__read.progressFor('1234'), 7);
  assert.equal(context.__read.isRead('1234', 7), true);
});

test('a title the account reads is a title the cap counts', () => {
  const { api } = device();
  const total = api.MAX_TITLES + 5;
  // Nothing opened on this device: every title is known only by MAL's count.
  for (let i = 0; i < total; i += 1) api.setProgress(`title-${i}`, 1);

  assert.equal(api.isRead('title-0', 1), false, 'the oldest title was kept');
  assert.equal(api.isRead(`title-${total - 1}`, 1), true, 'the newest title was lost');
  assert.equal(api.progressFor(`title-${total - 1}`), 1, 'the watermark itself was lost');
});

test('a device that refuses storage does not take the reader down', () => {
  const api = readOnlyDevice();
  assert.doesNotThrow(() => api.markRead('1234', 5));
  assert.doesNotThrow(() => api.setProgress('1234', 9));
  // Nothing was kept, and the module says so rather than claiming a memory it
  // does not have.
  assert.equal(api.isRead('1234', 5), false);
  assert.equal(api.readNumbers('1234').length, 0);
  assert.equal(api.progressFor('1234'), 0);
  assert.equal(api.isRead('1234', 9), false);
});

test('a payload that is not a record is not read as one', () => {
  const { api, entries } = device();
  for (const payload of ['[]', '"1234"', 'null', 'not json', '{"1234":[]}', '{"1234":{"n":"9"}}']) {
    entries.set(api.KEY, payload);
    assert.equal(api.isRead('1234', 9), false, `\`${payload}\` was read as a record`);
    assert.equal(api.readNumbers('1234').length, 0);
  }
});
