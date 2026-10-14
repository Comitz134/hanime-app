// The .env reader (src/env.mjs). It exists because the MAL token exchange
// needs a secret that lives in a gitignored `.env`, and nothing used to load
// that file — every start without the variable already exported answered MAL
// with `invalid_client`, which looks like a broken registration rather than a
// missing file.

import test from 'node:test';
import assert from 'node:assert/strict';

import { parseEnv } from '../src/env.mjs';

test('a line is a key and its value, comments and blanks skipped', () => {
  const entries = parseEnv([
    '# a comment',
    '',
    'MAL_CLIENT_SECRET=abc',
    '  PORT=8787  ',
  ].join('\n'));

  assert.deepEqual(entries, [
    ['MAL_CLIENT_SECRET', 'abc'],
    ['PORT', '8787'],
  ]);
});

test('a value keeps its own equals signs and loses only outer quotes', () => {
  // Secrets are base64-ish and may carry `=` padding; a naive split on `=`
  // would truncate them into a silent auth failure.
  const entries = parseEnv([
    'SECRET_A=abc==',
    'SECRET_B="quoted value"',
    "SECRET_C='single'",
    `SECRET_D="has 'inner' quotes"`,
  ].join('\n'));

  assert.deepEqual(entries, [
    ['SECRET_A', 'abc=='],
    ['SECRET_B', 'quoted value'],
    ['SECRET_C', 'single'],
    // One outer pair goes; the quotes inside the value stay.
    ['SECRET_D', "has 'inner' quotes"],
  ]);
});

test('a line that is not a variable is not a variable', () => {
  const entries = parseEnv([
    'not a variable',
    '=novalue',
    '1BAD=x',
    'A-B=x',
    'GOOD=x',
  ].join('\n'));

  assert.deepEqual(entries, [['GOOD', 'x']]);
});

test('a quoted value never loses characters', () => {
  // `""` is a quote pair, not a two-character value to be sliced away.
  assert.deepEqual(parseEnv('EMPTY=""'), [['EMPTY', '']]);
  assert.deepEqual(parseEnv('ONE="'), [['ONE', '"']]);
});
