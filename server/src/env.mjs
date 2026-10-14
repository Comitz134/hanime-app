// env.mjs — the one file this server reads before any other.
//
// The MAL registration's secret lives in a gitignored `.env` as
// MAL_CLIENT_SECRET (README-ANDROID-BUILD.md), and the token exchange adds it
// from the environment. Nothing used to *load* that file, so the exchange only
// worked when the secret happened to be exported into the starting shell. Every
// other start — this repo's own `npm start`, the window's bundled server —
// answered MAL with `invalid_client: Client authentication failed`, which reads
// like a bad registration rather than a file nobody read.
//
// It is imported first by server.mjs so module-level reads get it too
// (playlist-crawl reads DATA_DIR at import time). A variable already present in
// the real environment always wins: the shell is the more explicit source.
//
// Zero dependencies, matching the rest of the server.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * Where a `.env` is looked for, nearest first:
 *   1. the server's own directory  — `server/.env`
 *   2. the checkout root           — the file this repo documents
 *   3. the working directory       — wherever the process was started
 * The bundled app has no `.env` of its own, so (1) is also the hook for a
 * secret the person installs by hand next to the materialised server.
 */
const CANDIDATES = [
  path.resolve(HERE, '../.env'),
  path.resolve(HERE, '../../.env'),
  path.resolve(process.cwd(), '.env'),
];

const KEY = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * `KEY=value` lines, `#` comments and blanks skipped, surrounding quotes
 * stripped, and everything after the first `=` taken verbatim (secrets are
 * base64-ish and may contain `=`).
 */
export function parseEnv(text) {
  const entries = [];
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    if (!KEY.test(key)) continue;
    let value = line.slice(eq + 1).trim();
    const quoted =
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"));
    if (quoted && value.length >= 2) value = value.slice(1, -1);
    entries.push([key, value]);
  }
  return entries;
}

/** The files that supplied at least one variable, for a startup line. */
export const loadedEnvFiles = [];

for (const file of CANDIDATES) {
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    continue;                       // no such file is the normal case
  }
  let applied = 0;
  for (const [key, value] of parseEnv(text)) {
    if (process.env[key] === undefined) {
      process.env[key] = value;
      applied += 1;
    }
  }
  // The count only — never a value.
  if (applied > 0) {
    loadedEnvFiles.push({ file, applied });
    console.log(`env: ${applied} variable(s) from ${path.basename(file)}`);
  }
}
