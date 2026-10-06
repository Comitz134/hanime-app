// Guards the web client, which has no build step and therefore no compiler.
//
// This exists because of a real bug: a CSS block was pasted inside the
// <script> tag while adding the public-playlist section. The markup looked
// fine, the stylesheet was untouched, and the only symptom was a single
// "SyntaxError: Unexpected token '.'" in the browser console that killed the
// entire client. Nothing on the server caught it. These tests do.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CLIENT = path.resolve(HERE, '../public/index.html');
const html = fs.readFileSync(CLIENT, 'utf8');

function inlineScripts(source) {
  const out = [];
  const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g;
  for (const m of source.matchAll(re)) out.push(m[1]);
  return out;
}

function stylesheets(source) {
  const out = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/g;
  for (const m of source.matchAll(re)) out.push(m[1]);
  return out;
}

test('the client has exactly one inline script and one stylesheet', () => {
  assert.equal(inlineScripts(html).length, 1);
  assert.equal(stylesheets(html).length, 1);
});

test('every inline script parses as an ES module', () => {
  const scripts = inlineScripts(html);
  assert.ok(scripts.length > 0, 'no inline script found — did the client move?');

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'htv-client-'));
  try {
    scripts.forEach((body, i) => {
      const file = path.join(dir, `inline-${i}.mjs`);
      fs.writeFileSync(file, body);
      // --check parses without executing; a CSS brace or stray token thrown in
      // here fails loudly instead of silently blanking the page.
      execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' });
    });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('no CSS leaks into the script and no JS leaks into the stylesheet', () => {
  for (const css of stylesheets(html)) {
    assert.ok(!css.includes('function '), 'a function body ended up in the stylesheet');
    assert.ok(!css.includes('=>'), 'an arrow function ended up in the stylesheet');
  }
  for (const js of inlineScripts(html)) {
    // A selector block such as `.pp-owner {` is the shape that got pasted in.
    assert.ok(!/^\s*\.[a-z-]+\s*\{/m.test(js), 'a CSS rule ended up inside the script');
    assert.ok(!js.includes('{{'), 'template braces look doubled');
  }
});

test('every element id the client queries exists in its markup', () => {
  const script = inlineScripts(html)[0];
  const styled = stylesheets(html)[0];

  // Ids the script reaches for by selector, e.g. $('#pp-rail'), getElementById,
  // or document.getElementById.
  const queried = new Set();
  for (const m of script.matchAll(/\$\(\s*'#([A-Za-z0-9_-]+)'/g)) queried.add(m[1]);
  for (const m of script.matchAll(/getElementById\(\s*'([A-Za-z0-9_-]+)'/g)) queried.add(m[1]);
  for (const m of script.matchAll(/\$\(\s*`#([A-Za-z0-9_-]+)`/g)) queried.add(m[1]);

  const defined = new Set();
  for (const m of html.matchAll(/\sid="([A-Za-z0-9_-]+)"/g)) defined.add(m[1]);

  const missing = [...queried].filter((id) => !defined.has(id));
  assert.deepEqual(missing, [], `the client queries ids that do not exist: ${missing.join(', ')}`);

  // The public-playlist section is the point of this change; make sure its
  // moving parts are present and not accidentally renamed.
  for (const id of ['public-playlists', 'pp-q', 'pp-rail', 'pp-note', 'pp-count', 'pp-clear', 'pp-more']) {
    assert.ok(defined.has(id), `missing #${id}`);
  }
  assert.ok(styled.includes('.pp-owner'), 'public-playlist card styles are not in the stylesheet');
  assert.ok(!styled.includes('.pp-tag em {') || styled.includes('button.pp-tag'), 'tag chip styles malformed');
});

test('the inline script has no top-level await, which breaks older WebViews', () => {
  // This is not hypothetical. The client is served to an installed Android app,
  // and the WebView bundled with Android 11 is Chromium 83 — top-level await in
  // a module needs 89. It failed there as "Uncaught SyntaxError: Unexpected
  // reserved word" and the entire page, not just one feature, came up blank.
  //
  // The check is a real parse rather than a regex: wrapping the body in a
  // non-async function makes any await outside an async context a syntax error,
  // while awaits inside async functions stay legal. That is exactly the rule.
  const script = inlineScripts(html)[0];
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'htv-await-'));
  try {
    const file = path.join(dir, 'wrapped.js');
    fs.writeFileSync(file, `(function () {\n${script}\n})();\n`);
    assert.doesNotThrow(
      () => execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' }),
      'the client uses await outside an async function (top-level await)',
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the client avoids APIs newer than the WebView it ships inside', () => {
  // Chromium 83 is the floor: Android 11's bundled WebView, which is what an
  // un-updated device runs. Each entry names the version that introduced it.
  const script = inlineScripts(html)[0];
  const tooNew = [
    [/\|\|=/, 'logical assignment ||= (Chrome 85)'],
    [/&&=/, 'logical assignment &&= (Chrome 85)'],
    [/\?\?=/, 'nullish assignment ??= (Chrome 85)'],
    [/\.replaceAll\(/, 'String.prototype.replaceAll (Chrome 85)'],
    [/\.at\(\s*-?\d/, 'Array.prototype.at (Chrome 92)'],
    [/\.findLast/, 'Array.prototype.findLast (Chrome 97)'],
    [/\.toSorted\(/, 'Array.prototype.toSorted (Chrome 110)'],
    [/Object\.hasOwn/, 'Object.hasOwn (Chrome 93)'],
    [/structuredClone/, 'structuredClone (Chrome 98)'],
    [/\.groupBy\(/, 'Array.prototype.groupBy (Chrome 117)'],
  ];

  for (const [re, label] of tooNew) {
    assert.ok(!re.test(script), `the client uses ${label}, which breaks WebView 83`);
  }
});

test('the client calls the public playlist endpoints the server actually serves', () => {
  const script = inlineScripts(html)[0];
  const calls = [...script.matchAll(/['"`](\/api\/public\/[A-Za-z0-9/_-]*)/g)].map((m) => m[1]);
  assert.ok(calls.some((c) => c.startsWith('/api/public/playlists')), 'no public playlist call');
  assert.ok(calls.some((c) => c.startsWith('/api/public/crawl')), 'no crawl call');
  assert.ok(calls.some((c) => c.startsWith('/api/public/videos/')), 'no reverse-lookup call');
});

test('the reskin tokens are still the measured ones', () => {
  const css = stylesheets(html)[0];
  // Values read off the reference with getComputedStyle, not estimated.
  const expected = {
    '--background': '0 0% 6.7%',
    '--foreground': '0 0% 93.3%',
    '--card': '0 0% 9.8%',
    '--primary': '29.5 100% 88%',
  };
  for (const [token, value] of Object.entries(expected)) {
    assert.ok(css.includes(`${token}: ${value}`), `${token} drifted from ${value}`);
  }
});

// ---------------------------------------------------------------------------
// The Android app has no server to fetch the client from — it bundles a copy.
// If the two drift, the shipped app silently runs an older UI than the one
// this suite just exercised, and nothing anywhere would report it.
// ---------------------------------------------------------------------------

const ANDROID = path.resolve(HERE, '../../android');

test('the copy bundled into the Android app is this client, byte for byte', () => {
  if (!fs.existsSync(ANDROID)) return; // server-only checkout
  const bundled = path.join(ANDROID, 'app/src/main/assets/index.html');
  assert.ok(fs.existsSync(bundled), `missing ${bundled} — run the app build`);
  assert.equal(
    fs.readFileSync(bundled, 'utf8'),
    html,
    'android/app/src/main/assets/index.html is stale: re-copy public/index.html',
  );
});

// The signer bundle is emscripten output using `??=`, which arrived in
// Chrome 85. It is downgraded by android/tools/make-signer-asset.mjs; if that
// step is skipped the module fails to parse, the signature never appears, and
// every video fails to resolve with no error on screen but a 502.
test('the bundled signer parses on the WebViews this app runs inside', () => {
  if (!fs.existsSync(ANDROID)) return; // server-only checkout
  const bundled = path.join(ANDROID, 'app/src/main/assets/htv-signer.js');
  assert.ok(fs.existsSync(bundled), `missing ${bundled}`);
  const source = fs.readFileSync(bundled, 'utf8');
  assert.ok(!source.includes('??='), 'logical assignment survives: regenerate the asset');
  assert.ok(!source.includes('||='), 'logical assignment survives: regenerate the asset');
  assert.ok(!source.includes('&&='), 'logical assignment survives: regenerate the asset');
  assert.ok(!/static\s*\{/.test(source), 'static block survives: regenerate the asset');
  assert.ok(
    !/[#]\w+\s*[({=]/.test(source),
    'private class member survives: regenerate the asset',
  );
});
