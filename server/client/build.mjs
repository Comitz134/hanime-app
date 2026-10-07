// Builds the web client.
//
// Sources live in this directory: src/*.js (bundled by esbuild), styles/*.css
// (concatenated in name order) and template.html (the markup). The result is
// written to the two places that serve it — server/public/index.html for the
// server, android/app/src/main/assets/index.html for the app — so the two can
// never drift: both are regenerated from the same sources.
//
//   cd server && npm run build:client
//
// The suite (test/web-client.test.mjs) calls render() and fails if either
// committed copy differs from it, so a stale bundle is caught before release.
//
// Determinism: same sources + same esbuild version (pinned in package.json)
// produce byte-identical output; that is what makes the freshness guard sound.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');

const OUTPUTS = [
  path.resolve(HERE, '../public/index.html'),
  path.join(ROOT, 'android/app/src/main/assets/index.html'),
];

/** Renders the complete index.html from the sources. */
export async function render() {
  const esbuild = await import('esbuild');

  const css = fs.readdirSync(path.join(HERE, 'styles'))
    .filter((f) => f.endsWith('.css'))
    .sort()
    .map((f) => fs.readFileSync(path.join(HERE, 'styles', f), 'utf8'))
    .join('\n');

  const js = (await esbuild.build({
    entryPoints: [path.join(HERE, 'src/main.js')],
    bundle: true,
    write: false,
    format: 'iife',
    platform: 'browser',
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
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => { console.error(e.message || e); process.exit(1); });
}
