// The MAL search runs on a *shape* MyAnimeList chose, and this app read the
// wrong part of it for as long as the search existed.
//
// MAL wraps every hit — `/manga?q=…` and `/anime?q=…` alike — in a `node`:
//
//   { "data": [ { "node": { "id": 196874, "title": "…" } } ] }
//
// The client read `id` and `title` straight off the item, so every field came
// back `undefined`: a title that had to be found by name was answered with
// "no match for this title" even when MAL had it (reproduced on the device with
// /manga/196874/Copying_Skills_with_Affinity — MAL returned exactly 1 result and
// the row still said no match), and every hit in the hand-picker drew blank.
// Nothing caught it because nothing ran the mapping against MAL's own answer:
// from the outside a miss and an unreadable hit are the same empty row.
//
// So this is a behavioural test, in the same spirit as ad-guard.test.mjs: the
// module's own source is executed verbatim against a small stub of the browser
// it expects, `fetch` is handed the live API's answer shape, and the mapping is
// asserted. The committed bundle is checked against these sources elsewhere
// (web-client.test.mjs), so what runs here is what ships.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE = path.resolve(HERE, '../client/src/mal.js');

/**
 * mal.js as the bundler sees it, minus the one import it shares with the rest
 * of the client and the export block — both of which only exist for the module
 * graph. What is left is a plain script whose top-level functions land on the
 * context, so `searchMal` can be called the way the client calls it.
 */
function load({ answer, onRequest }) {
  const source = fs.readFileSync(SOURCE, 'utf8')
    .replace(/^import .*$/m, '')
    .replace(/\nexport \{[\s\S]*?\};\s*$/, '\n');

  const store = new Map();
  const requests = [];
  const context = {
    console,
    URLSearchParams,
    MutationObserver: class { observe() {} },
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k),
    },
    document: {
      // An empty page: no slots to fill, no settings card, no root element —
      // every optional path in the module takes its short way out.
      documentElement: null,
      addEventListener() {},
      querySelectorAll: () => [],
      getElementById: () => null,
      createElement: () => ({ style: {}, classList: { add() {}, remove() {} } }),
    },
    // A linked session, so the pipe is reached instead of refusing first.
    fetch: async (url, init = {}) => {
      requests.push({ url: String(url), method: init.method ?? 'GET' });
      return { ok: true, status: 200, json: async () => answer, text: async () => '' };
    },
  };
  context.window = {
    crypto: undefined,
    location: { origin: 'https://hanime.tv', search: '', pathname: '/', hash: '' },
    history: { replaceState() {} },
    addEventListener() {},
  };
  context.localStorage.setItem('mal.auth', JSON.stringify({
    access: 'access-token', refresh: 'refresh-token', expiresAt: Date.now() + 3600_000,
  }));
  if (onRequest) onRequest(context);

  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'mal.js' });
  return { context, requests };
}

/** MAL's own answer to `/manga?q=Copying+Skills+with+Affinity`, trimmed to the
 *  fields the client asks for. Verbatim from the live API on 2026-10-15. */
const COPYING_SKILLS = {
  data: [
    {
      node: {
        id: 196874,
        title: 'Copying Skills with Affinity!',
        alternative_titles: { synonyms: ['Copy Skill', 'Copying Skills With Affinity'] },
        media_type: 'manga',
        start_date: '2024-05-01',
        num_chapters: 0,
      },
    },
    {
      node: {
        id: 143199,
        title: 'Affinity for Skills',
        alternative_titles: { synonyms: [] },
        media_type: 'manga',
        start_date: '2019-02-02',
        num_chapters: 40,
      },
    },
  ],
  paging: {},
};

test('a search reads MAL\'s own answer, where every hit is wrapped in a node', async () => {
  const { context, requests } = load({ answer: COPYING_SKILLS });

  const found = await context.searchMal('manga', 'Copying Skills with Affinity!');

  assert.equal(requests.length, 1, 'the search did not go out once');
  assert.match(requests[0].url,
    /^\/api\/mal\/v2\/manga\?q=Copying%20Skills%20with%20Affinity!&limit=10&fields=/,
    'the search does not go through MAL\'s own query route');

  assert.equal(found.length, 2, 'a wrapped hit was dropped instead of unwrapped');
  assert.deepEqual(found.map((x) => x.id), [196874, 143199],
    'the ids came off the wrapper, not off the entry — this is the shipped bug');
  assert.equal(found[0].title, 'Copying Skills with Affinity!', 'a hit has no title to draw');
  assert.equal(found[0].kind, 'manga', 'a hit says nothing about what kind it is');
  assert.equal(found[0].year, '2024', 'a hit says nothing about when it started');
  assert.deepEqual(found[0].synonyms, ['Copy Skill', 'Copying Skills With Affinity'],
    'MAL\'s other names for an entry are not carried to the picker');
});

test('a title MAL has is no longer reported as no match', async () => {
  const { context } = load({ answer: COPYING_SKILLS });

  const id = await context.resolveMalId('manga', null, 'Copying Skills with Affinity!');

  assert.equal(id, 196874,
    'the entry that is on MyAnimeList is still answered with a miss');
  // And the answer is kept under the app's own title, so the next open costs
  // nothing and the picker opens on the right row.
  const cached = JSON.parse(context.localStorage.getItem('mal.ids'));
  assert.equal(cached['manga:copying skills with affinity!'].id, 196874,
    'a resolved entry is not remembered');
});

test('a name that differs only in punctuation or a leading article still matches', async () => {
  const answer = {
    data: [{
      node: {
        id: 528,
        title: 'The Affinity of the Shield Hero',
        alternative_titles: { synonyms: ['Shield Hero'] },
        media_type: 'manga',
        start_date: '2013-11-04',
        num_chapters: 0,
      },
    }],
    paging: {},
  };
  const { context } = load({ answer });

  // The app's own name differs by a trailing "!" and by the article.
  const id = await context.resolveMalId('manga', null, 'Affinity of the Shield Hero!');
  assert.equal(id, 528, 'a title is only matched when MAL spells it identically');
});

test('a hit without an entry is never taken as an answer', async () => {
  const answer = {
    data: [
      { node: { title: 'no id here' } },
      { id: 9, title: 'an unwrapped hit from an older response' },
      { node: { id: 0, title: 'id zero is not an entry' } },
    ],
    paging: {},
  };
  const { context } = load({ answer });

  const found = await context.searchMal('anime', 'anything');
  assert.deepEqual(found.map((x) => x.id), [9],
    'a malformed hit is kept and can be picked as if it were an entry');

  // A search with nothing usable is a miss, and a miss is the picker — never
  // the first row of nonsense.
  const id = await context.resolveMalId('anime', null, 'anything');
  assert.equal(id, 9, 'the one usable hit was not used');
});
