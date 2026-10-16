// Builds the web client.
//
// Sources live in this directory: src/*.js (bundled by esbuild), styles/*.css
// (concatenated in name order) and template.html (the markup). The result is
// written to the two places that serve it — server/public/index.html for the
// server, android/app/src/main/assets/index.html for the app — so the two can
// never drift: both are regenerated from the same sources.
//
// The document's own outside-the-page files ride along in the same run: the
// install manifest and the icons the manifest and the page's <link> tags name
// are copied from public/ into the app's assets as bytes. They used to be
// copied by hand, and the hand copy is exactly how the app came to install
// itself as "Library" with an icon no build had ever written.
//
//   cd server && npm run build:client
//
// The suite (test/web-client.test.mjs) calls render() and fails if either
// committed copy differs from it — and fails on either stale icon — so a stale
// bundle is caught before release.
//
// Determinism: same sources + same esbuild version (pinned in package.json) +
// the same account configuration (the two variables below, defaulted) produce
// byte-identical output; that is what makes the freshness guard sound.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');

const OUTPUTS = [
  path.resolve(HERE, '../public/index.html'),
  path.join(ROOT, 'android/app/src/main/assets/index.html'),
];

/**
 * The files beside the document that the page and the manifest name.
 *
 * Generated once into public/ by `_mf/make-sekai-icons.py` — one source image,
 * one run — and copied from there rather than written twice: the phone app
 * answers /app.webmanifest, /favicon.ico and the rest out of its own assets,
 * so a copy that is not the server's is a second identity for the same app.
 * An unknown path there is answered with index.html, so a missing icon is not
 * even a 404 — it is the whole page.
 */
/**
 * The account backend this build of the client talks to.
 *
 * Same story as the desktop's: the account is one Supabase project shared with
 * the window, and both values are *publishable* — the URL and the publishable
 * key ship inside every client and carry no privileges of their own, which is
 * why row-level security rather than key secrecy is what protects a reader's
 * library. The desktop build carries the same pair (`SupabaseDefaultUrl` in
 * shell/overrides/composeApp/build.gradle.kts), and `accounts/probe.mjs` reads
 * the same two environment variables this does, so all three can be pointed at
 * a project of one's own with one name each.
 *
 * They are baked at build time, not fetched at run time: the app serves this
 * client out of its own assets, with no server of ours in front of it to ask.
 */
export const SUPABASE_DEFAULT_URL = 'https://hijiurhppikjgammgdmg.supabase.co';
export const SUPABASE_DEFAULT_KEY = 'sb_publishable_nr-W-nQRbUEg7pJ3ipMUxA_fwqw6t5e';

/** The pair as `build` and `render` substitute it, environment first. */
function accountConfig() {
  const url = (process.env.SEKAI_SUPABASE_URL ?? SUPABASE_DEFAULT_URL).trim().replace(/\/+$/, '');
  const key = (process.env.SEKAI_SUPABASE_KEY ?? SUPABASE_DEFAULT_KEY).trim();
  return { url, key };
}

export const COPIED_ASSETS = [
  'app.webmanifest',
  'favicon.ico',
  'icon-192.png',
  'icon-512.png',
  'icon-maskable-512.png',
  'apple-touch-icon.png',
];

/** Copies those files from public/ into the app's assets, byte for byte. */
export async function copyAssets() {
  const from = path.resolve(HERE, '../public');
  const to = path.join(ROOT, 'android/app/src/main/assets');
  if (!fs.existsSync(to)) return []; // server-only checkout

  const written = [];
  for (const name of COPIED_ASSETS) {
    const source = path.join(from, name);
    if (!fs.existsSync(source)) {
      throw new Error(`${name} is not in public/ — nothing to put in front of the app`);
    }
    const dest = path.join(to, name);
    // A Buffer, not utf8: an .ico and a PNG have no line endings to convert,
    // and the manifest's copy has to be the same bytes the test compares.
    fs.writeFileSync(dest, fs.readFileSync(source));
    written.push(name);
    console.log(`wrote ${path.relative(ROOT, dest)} — ${fs.statSync(dest).size} bytes`);
  }
  return written;
}

/** Renders the complete index.html from the sources. */
export async function render() {
  const esbuild = await import('esbuild');

  const css = fs.readdirSync(path.join(HERE, 'styles'))
    .filter((f) => f.endsWith('.css'))
    .sort()
    .map((f) => fs.readFileSync(path.join(HERE, 'styles', f), 'utf8'))
    .join('\n');

  const account = accountConfig();
  const js = (await esbuild.build({
    entryPoints: [path.join(HERE, 'src/main.js')],
    bundle: true,
    write: false,
    format: 'iife',
    platform: 'browser',
    // account.js reads these two names; a build with no project at all (both
    // empty) is a client that says so on its Settings card rather than one that
    // cannot compile.
    define: {
      __SEKAI_SUPABASE_URL__: JSON.stringify(account.url),
      __SEKAI_SUPABASE_KEY__: JSON.stringify(account.key),
    },
    // The floor this client ships to: Android 11's bundled WebView. Anything
    // newer in the sources gets downleveled here instead of breaking there.
    target: ['chrome83'],
    logLevel: 'silent',
  })).outputFiles[0].text;

  // An inline <script>/<style> ends at the first close tag, whatever is inside
  // it — if a generated bundle ever contains one, the page would silently
  // truncate. Refuse to emit that.
  if (js.includes('</script') || js.includes('</style')) {
    throw new Error('bundle contains a close tag and would truncate the inline block');
  }

  const template = fs.readFileSync(path.join(HERE, 'template.html'), 'utf8');
  const html = template
    .replace('/*@CSS@*/', () => css)
    .replace('/*@JS@*/', () => js);
  if (html.includes('/*@CSS@*/') || html.includes('/*@JS@*/')) {
    throw new Error('template placeholder missing — nothing was substituted');
  }
  return html;
}

async function main() {
  const html = await render();
  for (const out of OUTPUTS) {
    const dir = path.dirname(out);
    if (!fs.existsSync(dir)) continue; // server-only checkout
    fs.writeFileSync(out, html);
    console.log(`wrote ${path.relative(ROOT, out)} — ${Buffer.byteLength(html)} bytes`);
  }
  await copyAssets();
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => { console.error(e.message || e); process.exit(1); });
}
