#!/usr/bin/env node
// Build the signer asset that ships inside the APK.
//
//   node android/tools/make-signer-asset.mjs
//
// Reads server/vendor/htv-signer.js — the untouched copy the Node proxy runs —
// and writes android/app/src/main/assets/htv-signer.js with the one construct
// Chromium 83 cannot parse downgraded to its ES2019 equivalent.
//
// Why this exists
// ---------------
// The vendored bundle is emscripten output that uses `??=` (logical assignment),
// which arrived in Chrome 85. The emulator in this project runs WebView
// 83.0.4103.120, so the file fails to parse at the first `??=`:
//
//     SyntaxError: Unexpected token '='   (column 174371)
//
// and the signer module never boots, which silently breaks stream resolution.
// Node is new enough that the proxy never hits this, so nothing in server/ was
// ever changed — the downgrade is applied only to the copy the app bundles.
//
// Semantics are preserved exactly: `x ??= y` assigns only when x is null or
// undefined, which is precisely what `if (x == null) x = y;` does.
//
// Run this after updating the vendored bundle; a check in the test suite
// asserts the asset is what this script produces.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE = path.resolve(HERE, '../../server/vendor/htv-signer.js');
const TARGET = path.resolve(HERE, '../app/src/main/assets/htv-signer.js');

/** Downgrade constructs that predate Chrome 85. Returns { code, count }. */
export function downgrade(source) {
  let count = 0;
  // Only simple identifier targets are rewritten. Anything more complex would
  // fail this pattern and be reported rather than silently mistranslated.
  const code = source.replace(
    /([A-Za-z_$][\w$]*) \?\?= ([^;]+);/g,
    (_, name, value) => {
      count++;
      return `if (${name} == null) ${name} = ${value};`;
    },
  );

  const leftovers = code.match(/\?\?=/g);
  if (leftovers) {
    throw new Error(
      `refusing to ship: ${leftovers.length} logical-assignment operator(s) remain, `
      + 'including at least one this script does not know how to rewrite',
    );
  }
  return { code, count };
}

// Also assert nothing else modern slipped in: the emulator's Chromium is 83.
const TOO_NEW = [
  [/\|\|=/, '||= (Chrome 85)'],
  [/&&=/, '&&= (Chrome 85)'],
  [/static\s*\{/, 'static initialization block (Chrome 94)'],
  [/[#]\w+\s*[({=]/, 'private class member (Chrome 84+)'],
];

function main() {
  const source = fs.readFileSync(SOURCE, 'utf8');
  const { code, count } = downgrade(source);

  for (const [re, label] of TOO_NEW) {
    if (re.test(code)) throw new Error(`refusing to ship: bundle uses ${label}`);
  }

  fs.mkdirSync(path.dirname(TARGET), { recursive: true });
  fs.writeFileSync(TARGET, code);

  console.log(`signer asset: ${path.relative(process.cwd(), TARGET)}`);
  console.log(`  source      ${path.relative(process.cwd(), SOURCE)} (${source.length} bytes)`);
  console.log(`  downgraded  ${count} logical-assignment operator(s)`);
  console.log(`  output      ${code.length} bytes`);
}

// Test-suite entry point.
if (process.argv[1] === fileURLToPath(import.meta.url)) main();
