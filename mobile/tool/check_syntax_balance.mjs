#!/usr/bin/env node
// Structural sanity check for the Dart sources.
//
// There is no Dart SDK in this environment, so `flutter analyze` cannot run.
// This is not a compiler and does not pretend to be one: it strips strings and
// comments, then reports unbalanced braces, parens, or brackets, and flags a
// few patterns a Dart parser would reject outright. It catches transcription
// errors. It does not catch type errors, and passing here is not "it compiles".
//
//   node tool/check_syntax_balance.mjs

import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (full.endsWith('.dart')) out.push(full);
  }
  return out;
}

/** Remove string literals and comments so their contents cannot skew counting. */
function strip(source) {
  let out = '';
  let i = 0;
  const n = source.length;
  while (i < n) {
    const c = source[i];
    const next = source[i + 1];

    if (c === '/' && next === '/') {
      while (i < n && source[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && next === '*') {
      i += 2;
      while (i < n && !(source[i] === '*' && source[i + 1] === '/')) i++;
      i += 2;
      continue;
    }
    // Triple-quoted first, or a single quote would end it early.
    if ((c === "'" || c === '"') && source.startsWith(c.repeat(3), i)) {
      const fence = c.repeat(3);
      i += 3;
      while (i < n && !source.startsWith(fence, i)) i++;
      i += 3;
      out += '""';
      continue;
    }
    if (c === "'" || c === '"') {
      const quote = c;
      i++;
      while (i < n) {
        if (source[i] === '\\') { i += 2; continue; }
        if (source[i] === quote || source[i] === '\n') { i++; break; }
        i++;
      }
      out += '""';
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

const COUNTERS = [
  ['{', '}'],
  ['(', ')'],
  ['[', ']'],
];

let flagged = 0;
const files = walk(path.join(ROOT, 'lib')).sort();

for (const file of files) {
  const raw = fs.readFileSync(file, 'utf8');
  const code = strip(raw);
  const rel = path.relative(ROOT, file);
  const problems = [];

  for (const [open, close] of COUNTERS) {
    const o = (code.match(new RegExp(`\\${open}`, 'g')) ?? []).length;
    const c = (code.match(new RegExp(`\\${close}`, 'g')) ?? []).length;
    if (o !== c) problems.push(`${open}${close} unbalanced (${o} vs ${c})`);
  }

  // A stray `=>` without a following expression, or a class with no body.
  if (/=>\s*[;,)]/.test(code)) problems.push('dangling "=>" with no body');
  if (/\bclass\s+\w+[^{]*$/.test(code.trim())) problems.push('class declaration with no body');

  if (problems.length) flagged++;
  const status = problems.length ? 'CHECK' : 'ok   ';
  console.log(`${status} ${rel.padEnd(40)} ${raw.split('\n').length} lines`);
  for (const p of problems) console.log(`      - ${p}`);
}

console.log(`\n${files.length} files, ${flagged} flagged`);
console.log('Not a compiler: type errors are out of scope. Run `flutter analyze` where an SDK exists.');
process.exit(flagged ? 1 : 0);
