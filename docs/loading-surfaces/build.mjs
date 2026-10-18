#!/usr/bin/env node
// Takes the screenshots in shots/ — and checks them.
//
//   node hanime-app/docs/loading-surfaces/build.mjs
//
// There is no way to look at this app's loading screens without running it
// (they last a second or two, on screens you have to navigate to), so they are
// photographed from a harness that draws them for real: harness.html imports
// server/client/src/loading.js, links the client's own stylesheets, and reads
// the veil, the nav and the sheet out of server/client/template.html. Nothing
// here re-draws a surface, so nothing here can drift from the app.
//
// The images are taken with headless Chrome (or Edge) over the DevTools
// protocol rather than with a driver library: the machine has a browser and no
// puppeteer, and a screenshot needs about six protocol calls.
//
// A screenshot that cannot be trusted is worse than none, so each one is
// measured before it is written:
//
//   * the surface's own facts (geometry, ratio, computed filter, captions,
//     animation names) come back from the page and are checked against what
//     that surface is supposed to be — a stage that is not 16:9 fails the run;
//   * the harness reports a page error instead of leaving an empty frame;
//   * the PNG's size is read out of its own IHDR header, so a stunted capture
//     is caught rather than committed;
//   * the pixels are read back, in the browser that took them, and checked:
//     a flat image, a stage with no poster behind it, or a page that never
//     drew is a failed run rather than a plausible-looking file;
//   * the picture itself is taken with every animation held at a fixed time,
//     which two runs produce byte-identically (measured, not assumed); and
//     then the animations are let go and the surface is photographed again —
//     a surface whose second capture is byte-identical to the first is a
//     surface where nothing drew, and the run fails.
//
// Whatever fails, the run exits non-zero and says which surface and which
// expectation.

import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');
const HARNESS = path.join(HERE, 'harness.html');
const STYLES = path.join(ROOT, 'server/client/styles');
const SHOTS = path.join(HERE, 'shots');

/** Where the animations are held while a picture is taken. Nothing here is
    mid-transition, and shimmer/ring/spin are all partway through a sweep
    rather than at a frame edge. */
const FRAME_AT = 700;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
};

/**
 * What each surface is allowed to claim.
 *
 * These are the assertions that make a picture evidence: a fact measured off
 * the rendered page, checked against the surface it is meant to show. A new
 * surface with no checks is an error, so a screenshot cannot be added quietly.
 */
const CHECKS = {
  'first-paint': (f) => [
    !f.coversViewport && 'the veil does not cover the viewport',
    f.zIndex !== '40000' && `z-index is ${f.zIndex}, not 40000`,
    f.mark !== 1 && 'the gate mark is missing',
    f.bar !== 'boot-bar' && `the bar is not animating (${f.bar})`,
  ],

  'stage-films': (f) => stageChecks(f, { caption: 'Looking for a stream…' }),
  'stage-still': (f) => stageChecks(f, {
    caption: 'Still looking — some titles take a few seconds',
  }),
  'stage-no-poster': (f) => stageChecks(f, { caption: 'Loading the player…', noPoster: true }),

  'video-wait': (f) => [
    !f.overlayVisible && 'the overlay is hidden — this is not the state a video waits in',
    f.overlay.w !== f.slot.w && `the overlay (${f.overlay.w}px) does not cover the slot (${f.slot.w}px)`,
    f.overlay.h !== f.slot.h && `the overlay is ${f.overlay.h}px tall, the slot ${f.slot.h}px`,
    !`${f.backdrop}`.includes('blur(20px)') && `the cover is not blurred back (${f.backdrop})`,
    f.ring !== 'spin' && `the ring is not spinning (${f.ring})`,
    f.video.h !== f.slot.h && 'the video is not the size of its slot',
  ],

  'detail-sheet': (f) => [
    !f.sheetOpen && 'the sheet is not open',
    f.bars < 8 && `only ${f.bars} text bars — this is not the detail page skeleton`,
    f.stageRatio !== 1.78 && `the stage block is ${f.stageRatio}:1, not 16:9`,
    f.columns.trim().split(/\s+/).length !== 2 && `the detail grid is ${f.columns}, not two columns`,
    f.poster.h <= f.poster.w && 'the poster block is not portrait',
  ],

  'detail-sheet-phone': (f) => [
    f.columns.trim().split(/\s+/).length !== 1 && `the phone grid is ${f.columns}, not one column`,
    f.body.w > 430 && `the sheet is wider than the phone (${f.body.w}px)`,
    f.stage.w <= 0 && 'the stage block has no width',
  ],

  grid: (f) => [
    f.cards !== 18 && `${f.cards} skeleton cards, expected 18`,
    f.skeletons !== 54 && `${f.skeletons} shimmering blocks, expected 54`,
    f.columns < 4 && `only ${f.columns} columns at desktop width`,
    f.nav !== 1 && 'the nav is missing — this is not the catalog page',
    f.firstPoster.h < f.firstPoster.w && 'a skeleton poster is not portrait',
  ],

  'playlist-sheet': (f) => [
    f.rows !== 6 && `${f.rows} rows, expected 6`,
    f.squares !== 6 && `${f.squares} squares of art, expected 6`,
    !f.sheetOpen && 'the rows are not in an open sheet',
  ],

  'reader-chapter': (f) => [
    f.pages !== 3 && `${f.pages} page strips, expected 3`,
    f.strips !== 3 && `${f.strips} of them carry .rd-skel`,
    Math.abs(f.pageRatio - 2 / 3) > 0.02 && `a strip is ${f.pageRatio}:1, not page shaped`,
    f.readerChromeHidden !== 'none' && 'the reader is still showing the sheet’s top bar',
    f.layout !== 'strip' && 'the reader is not in its strip layout',
  ],

  'reader-double': (f) => [
    f.pages !== 4 && `${f.pages} page strips, expected 4`,
    !f.sideBySide && 'the two pages are stacked — the skeleton ignored the layout',
  ],

  'reader-seam': (f) => [
    f.loading !== 'true' && 'the seam is not in its loading state',
    f.rules !== 2 && `the band has ${f.rules} rules, expected two`,
    !f.finished?.includes('finished') && `the band does not say what finished (“${f.finished}”)`,
    !f.starting?.startsWith('Ch.') && `the band does not name the next chapter (“${f.starting}”)`,
    f.ring !== 'rd-spin' && `the ring is not turning (${f.ring})`,
  ],

  'mal-slot-loading': (f) => [
    f.state !== 'loading' && `the box is in its “${f.state}” state`,
    f.entry !== 'looking this up…' && `the box says “${f.entry}”`,
    f.skeletons !== 0 && 'the box already has shapes in it — this picture is out of date',
    f.box.h <= 0 && 'the box has no height',
  ],

  'settings-signin': (f, { animations }) => [
    f.who !== 'Not signed in' && `the card says it is signed in as “${f.who}”`,
    f.note !== 'Signing in…' && `the note reads “${f.note}”`,
    f.disabledButtons !== 2 && `${f.disabledButtons} of the two buttons are disabled`,
    f.skeletons !== 0 && 'the card already has shapes in it — this picture is out of date',
    f.card.h <= 0 && 'the card has no height',
    // This one and the tracking box are photographed as the states they are:
    // a line of text and nothing moving. If either ever gains a shape, this
    // fails and the picture gets taken again, which is what should happen.
    animations !== 0 && `${animations} things are animating — the card is no longer the inert state this records`,
  ],

  'reader-page-pending': (f) => [
    f.pages !== 3 && `${f.pages} pages, expected 3`,
    f.pending !== 2 && `${f.pending} pages still pending, expected 2`,
    f.decoded !== 1 && `${f.decoded} pages have decoded, expected 1`,
    f.page.h <= 0 && 'a pending page has no height',
  ],
};

/**
 * What the pixels themselves have to show.
 *
 * The facts above are read off the live DOM, which says nothing about whether
 * the capture came out blank — and none of these pictures can be checked by
 * looking at them. So each shot is measured after it is taken: its standard
 * deviation (a flat fill means nothing drew), its mean luminance, and the mean
 * colour of the middle of the frame.
 *
 * `stats` is that report; every value is 0–255.
 */
const PIXELS = {
  'first-paint': (s) => [
    s.std < 2 && `the veil is a flat fill (σ ${s.std}) — the mark and the bar did not draw`,
    s.maxLum < 120 && `nothing bright in the veil (max ${s.maxLum})`,
    s.lum > 60 && `the veil is not the dark screen it should be (mean ${s.lum})`,
  ],
  'stage-films': (s) => stagePixels(s),
  'stage-still': (s) => stagePixels(s),
  'stage-no-poster': (s) => [
    s.center > 25 && `there is something bright behind a stage that has no cover (${s.center})`,
    s.std < 1.5 && `the stage is a flat fill (σ ${s.std})`,
  ],
  'video-wait': (s) => [
    s.center < 20 && `the cover behind the video never drew (${s.center})`,
    s.std < 3 && `the overlay is a flat fill (σ ${s.std})`,
  ],
  'detail-sheet': (s) => [
    s.std < 4 && `the skeleton is a flat fill (σ ${s.std})`,
    s.lum < 6 && 'the sheet is entirely black',
  ],
  'detail-sheet-phone': (s) => [s.std < 4 && `the skeleton is a flat fill (σ ${s.std})`],
  grid: (s) => [
    s.std < 4 && `the grid is a flat fill (σ ${s.std})`,
    s.lum < 6 && 'the page is entirely black',
  ],
  'playlist-sheet': (s) => [s.std < 3 && `the rows are a flat fill (σ ${s.std})`],
  'reader-chapter': (s) => [s.std < 2.5 && `the strips are a flat fill (σ ${s.std})`],
  'reader-double': (s) => [
    s.std < 2.5 && `the strips are a flat fill (σ ${s.std})`,
    s.center < 25 && `the middle of the two-page spread is empty (${s.center})`,
  ],
  'reader-page-pending': (s) => pageAboveBand(s),
  'reader-seam': (s) => pageAboveBand(s),
  'mal-slot-loading': (s) => [
    s.std < 1.5 && `the box is a flat fill (σ ${s.std}) — nothing drew`,
    s.lum > 70 && `the frame is not the app’s dark background (mean ${s.lum})`,
  ],
  'settings-signin': (s) => [
    s.std < 2 && `the card is a flat fill (σ ${s.std}) — nothing drew`,
    s.lum > 80 && `the frame is not the app’s dark background (mean ${s.lum})`,
  ],
};

/** A page that has drawn, with the reader’s dark chrome under it. */
function pageAboveBand(s) {
  return [
    s.top < 60 && `the decoded page never drew (top of frame is ${s.top})`,
    s.bottom > 70 && `the bottom of the frame is as bright as a page (${s.bottom})`,
  ];
}

/** The stage is a dark screen with the title's own cover blurred across it. */
function stagePixels(s) {
  return [
    s.std < 3 && `the stage is a flat fill (σ ${s.std}) — no cover behind it`,
    s.center < 20 && `the middle of the stage is black (${s.center})`,
    s.centerR <= s.centerB && `the backdrop is not the warm cover (r${s.centerR} ≤ b${s.centerB})`,
  ];
}

/** The player's stage appears in three states; the rules they share. */
function stageChecks(f, { caption, noPoster = false }) {
  const problems = [
    f.ratio !== 1.78 && `the stage is ${f.ratio}:1, not 16:9`,
    f.caption !== caption && `the caption reads “${f.caption}”`,
    f.ring !== 'spin' && `the ring is not spinning (${f.ring})`,
  ];
  if (noPoster) {
    problems.push(f.backdrop !== 'none' && `a poster was drawn anyway (${f.backdrop})`);
    problems.push(f.backdropDecoded && 'a cover was loaded for a stage that has none');
  } else {
    problems.push(!f.backdropDecoded && 'the cover behind the stage never decoded');
    problems.push(!`${f.backdrop}`.includes('blur(22px)') && `the cover is not blurred back (${f.backdrop})`);
  }
  return problems;
}

/* ------------------------------------------------------------------ chrome */

/** Chrome, or Edge, or a Chromium the environment names. */
function browserPath() {
  const candidates = [
    process.env.CHROME_PATH,
    process.env.WEBVIEW_SHOT_CHROME,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/bin/microsoft-edge',
  ].filter(Boolean);
  const found = candidates.find((p) => fs.existsSync(p));
  if (!found) throw new Error('no Chrome or Edge found — set CHROME_PATH to one');
  return found;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A minimal DevTools-protocol client: send, and listen for events. */
class Devtools {
  constructor(url) {
    this.url = url;
    this.next = 0;
    this.waiting = new Map();
    this.listeners = new Set();
  }

  async open() {
    this.ws = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('the DevTools socket never opened')), 10000);
      this.ws.onopen = () => { clearTimeout(timer); resolve(); };
      this.ws.onerror = () => { clearTimeout(timer); reject(new Error('the DevTools socket failed')); };
    });
    this.ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.id && this.waiting.has(m.id)) {
        const { resolve, reject } = this.waiting.get(m.id);
        this.waiting.delete(m.id);
        if (m.error) reject(new Error(`${m.error.message}${m.error.data ? ' — ' + m.error.data : ''}`));
        else resolve(m.result);
        return;
      }
      if (m.method) for (const l of this.listeners) l(m);
    };
    return this;
  }

  send(method, params = {}, sessionId) {
    const id = ++this.next;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.waiting.delete(id)) reject(new Error(`${method} did not answer in 20s`));
      }, 20000);
      this.waiting.set(id, {
        resolve: (r) => { clearTimeout(timer); resolve(r); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      this.ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  }

  /** One event, or a timeout. */
  on(fn) { this.listeners.add(fn); return this; }

  once(method, { sessionId, timeout = 15000 } = {}) {
    return new Promise((resolve, reject) => {
      const listener = (m) => {
        if (m.method !== method) return;
        if (sessionId && m.sessionId !== sessionId) return;
        clearTimeout(timer);
        this.listeners.delete(listener);
        resolve(m.params);
      };
      const timer = setTimeout(() => {
        this.listeners.delete(listener);
        reject(new Error(`${method} never arrived`));
      }, timeout);
      this.listeners.add(listener);
    });
  }

  close() { try { this.ws.close(); } catch { /* already gone */ } }
}

async function launch() {
  const bin = browserPath();
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'sekai-shots-'));
  const child = spawn(bin, [
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-background-networking',
    '--force-color-profile=srgb',
    `--user-data-dir=${profile}`,
    '--remote-debugging-port=0',
    'about:blank',
  ], { stdio: 'ignore' });

  // With --remote-debugging-port=0 the browser writes the port it chose here,
  // which is also what keeps two runs from fighting over one.
  const portFile = path.join(profile, 'DevToolsActivePort');
  let port = null;
  for (let i = 0; i < 100 && !port; i++) {
    if (fs.existsSync(portFile)) {
      const first = fs.readFileSync(portFile, 'utf8').split('\n')[0].trim();
      if (first) port = Number(first);
    }
    if (!port) await sleep(150);
  }
  if (!port) {
    child.kill();
    throw new Error(`${path.basename(bin)} started but never opened its DevTools port`);
  }
  const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
  return { child, profile, port, wsUrl: version.webSocketDebuggerUrl, bin };
}

/* ------------------------------------------------------------------ server */

/**
 * The repo, over loopback, for the length of the run.
 *
 * The harness needs http for its module import, and the shots need the real
 * stylesheets and the real template.html — which is the same directory, so the
 * server is rooted at the repo. It listens on an ephemeral port on 127.0.0.1
 * only and is closed before this process exits.
 */
function serve() {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let rel = decodeURIComponent(url.pathname);
    if (rel.endsWith('/')) rel += 'index.html';
    const file = path.resolve(ROOT, '.' + rel);
    if (!file.startsWith(ROOT + path.sep)) {
      res.writeHead(403, { 'content-type': 'text/plain' }).end('outside the repo');
      return;
    }
    fs.readFile(file, (err, buf) => {
      if (err) {
        res.writeHead(404, { 'content-type': 'text/plain' }).end(`not found: ${rel}`);
        return;
      }
      res.writeHead(200, {
        'content-type': MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream',
        'cache-control': 'no-store',
      });
      res.end(buf);
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

/* ------------------------------------------------------------------- build */

const failures = [];
const fail = (surface, problem) => failures.push(`${surface}: ${problem}`);

async function main() {
  // The harness links the client's stylesheets one by one; a stylesheet added
  // to the client and not to the harness would leave these pictures subtly
  // wrong, so the list is checked before anything is photographed.
  const harness = fs.readFileSync(HARNESS, 'utf8');
  const styles = fs.readdirSync(STYLES).filter((f) => f.endsWith('.css')).sort();
  const unlinked = styles.filter((f) => !harness.includes(`styles/${f}`));
  if (unlinked.length) {
    throw new Error(`harness.html does not link: ${unlinked.join(', ')} — the surfaces would not be the app's`);
  }

  // Two surfaces are drawn from built-up markup rather than from markup that
  // exists in the document: the reader's seam between chapters (manga.js) and
  // the tracking box while it waits (mal.js), plus the states account.js puts
  // the account card in. The names those surfaces are made of are checked
  // against the sources that emit them, so a rename there fails this run
  // rather than leaving a picture of a state the app no longer has.
  const borrowed = [
    ['server/client/src/manga.js', ['rd-seam', 'rd-seam-rule', 'rd-seam-text', 'rd-seam-spin']],
    ['server/client/src/mal.js', ['mal-box', 'mal-head', 'looking this up']],
    ['server/client/src/account.js', ['acct-note', 'acct-signin', 'Signing in']],
  ];
  for (const [file, names] of borrowed) {
    const source = fs.readFileSync(path.join(ROOT, file), 'utf8');
    const gone = names.filter((n) => !source.includes(n));
    if (!gone.length) continue;
    throw new Error(`${file} no longer has ${gone.join(', ')} — harness.html is drawing a state that moved`);
  }

  fs.mkdirSync(SHOTS, { recursive: true });
  const { server, port } = await serve();
  const base = `http://127.0.0.1:${port}/docs/loading-surfaces/harness.html`;
  const chrome = await launch();
  const dt = await new Devtools(chrome.wsUrl).open();
  let exit = 0;

  try {
    const { targetId } = await dt.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await dt.send('Target.attachToTarget', { targetId, flatten: true });

    const read = async (expression) => {
      const r = await dt.send('Runtime.evaluate', {
        expression, returnByValue: true, awaitPromise: true,
      }, sessionId);
      if (r.exceptionDetails) {
        throw new Error(r.exceptionDetails.exception?.description
          ?? r.exceptionDetails.text ?? 'the page script threw');
      }
      return r.result.value;
    };

    /** Anything the page threw while a surface was on screen. */
    const thrown = [];
    dt.on((m) => {
      if (m.method === 'Runtime.exceptionThrown' && m.sessionId === sessionId) {
        const d = m.params.exceptionDetails;
        thrown.push(d.exception?.description ?? d.text ?? 'an exception');
      }
    });

    /**
     * Wait for one harness to be ready, by asking it rather than by watching
     * the document.
     *
     * `load` does not wait for a module with a top-level await to finish
     * evaluating, and the harness fetches template.html at the top level — so
     * waiting on the load event alone once left every surface after the second
     * reporting that it had never become ready. Asking the page is also what
     * keeps a previous surface's readiness from being mistaken for this one's:
     * the answer only counts once the page is on this surface's own URL.
     */
    const waitForReady = async (id, { tries = 200, gap = 100 } = {}) => {
      for (let i = 0; i < tries; i++) {
        const here = await read(
          `location.search.includes('only=${id}') && window.__ready ? 1 : null`,
        ).catch(() => null);
        if (here) return read('window.__ready');
        await sleep(gap);
      }
      return undefined;
    };

    await dt.send('Page.enable', {}, sessionId);
    await dt.send('Runtime.enable', {}, sessionId);
    await dt.send('Emulation.setDeviceMetricsOverride',
      { width: 1200, height: 800, deviceScaleFactor: 2, mobile: false }, sessionId);

    // The list of surfaces comes from the harness itself, so the build cannot
    // photograph a surface the app no longer has, or miss one it has gained.
    await dt.send('Page.navigate', { url: base }, sessionId);
    let surfaces = null;
    for (let i = 0; i < 100 && !surfaces; i++) {
      surfaces = await read('window.__surfaces ?? null').catch(() => null);
      if (!surfaces) await sleep(100);
    }
    if (!Array.isArray(surfaces) || !surfaces.length) {
      const why = await read(`JSON.stringify({ href: location.href, title: document.title,
        error: window.__error ?? null, hasModule: typeof window.__surfaces })`)
        .catch((e) => e.message);
      throw new Error(`the harness reported no surfaces — did it fail to load? ${why}`);
    }

    const unknown = surfaces.map((s) => s.id).filter((id) => !CHECKS[id] || !PIXELS[id]);
    if (unknown.length) throw new Error(`no checks for: ${unknown.join(', ')}`);

    const measured = [];
    const rows = [];

    for (const [i, surface] of surfaces.entries()) {
      const { id, size, title, note } = surface;
      const [width, height] = size;
      const scale = surface.scale ?? 2;
      await dt.send('Emulation.setDeviceMetricsOverride',
        { width, height, deviceScaleFactor: scale, mobile: false }, sessionId);

      await dt.send('Page.navigate', { url: `${base}?only=${encodeURIComponent(id)}` }, sessionId);

      thrown.length = 0;
      const ready = await waitForReady(id);
      if (ready !== true) {
        const why = await read(`JSON.stringify({ href: location.href, state: document.readyState,
          title: document.title, error: window.__error ?? null })`).catch((e) => e.message);
        fail(id, `the harness never became ready (${ready}) — ${why}`);
        continue;
      }
      const pageError = await read('window.__error ?? null');
      if (pageError) fail(id, `the page threw — ${pageError}`);
      if (thrown.length) fail(id, `the page threw — ${thrown[0].split('\n')[0]}`);

      const { facts } = await read('window.__measure()');

      // Every animation is held at one fixed time, which is what makes the
      // same picture on every run. It is not merely held still: stepping the
      // same held animations on to another time changed nothing in the boot
      // veil (measured), so liveness is checked by letting them run again and
      // photographing the surface a second time.
      const settle = 'new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))';
      const animations = await read('document.getAnimations().length');
      // Read before the picture is taken, so a check can assert that a surface
      // has nothing animating in it at all.
      const problems = CHECKS[id](facts, { animations }).filter(Boolean);
      problems.forEach((p) => fail(id, p));
      await read(`(document.getAnimations().forEach((a) => {
        try { a.pause(); a.currentTime = ${FRAME_AT}; } catch (e) { /* an animation that will not hold still */ }
      }), true)`);
      await read(settle);
      const held = await dt.send('Page.captureScreenshot', { format: 'png', fromSurface: true }, sessionId);

      await read('(document.getAnimations().forEach((a) => a.play()), true)');
      await sleep(200);
      const alive = await dt.send('Page.captureScreenshot', { format: 'png', fromSurface: true }, sessionId);

      const png = Buffer.from(held.data, 'base64');
      if (png.slice(12, 16).toString() !== 'IHDR') {
        fail(id, 'the capture is not a PNG');
        continue;
      }
      const w = png.readUInt32BE(16);
      const h = png.readUInt32BE(20);
      if (w !== width * scale || h !== height * scale) {
        fail(id, `the capture is ${w}×${h}, expected ${width * scale}×${height * scale}`);
      }
      // A surface with nothing animating in it cannot be checked this way —
      // and that it has nothing animating is itself worth recording, because
      // it is exactly the state this whole folder is about.
      if (animations && held.data === alive.data) {
        fail(id, 'nothing moved between two frames — the capture shows a dead page');
      }

      // The pixels, read back through the same browser that took them: an
      // OffscreenCanvas is the decoder, so this needs no image library and no
      // second opinion about what the PNG contains.
      const pixels = await read(`(async () => {
        const img = new Image();
        img.src = 'data:image/png;base64,${held.data}';
        await img.decode();
        const canvas = new OffscreenCanvas(img.width, img.height);
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(img, 0, 0);
        const region = (fx, fy, fw, fh) => {
          const x = Math.floor(img.width * fx), y = Math.floor(img.height * fy);
          const w = Math.max(1, Math.floor(img.width * fw)), h = Math.max(1, Math.floor(img.height * fh));
          const d = ctx.getImageData(x, y, w, h).data;
          let r = 0, g = 0, b = 0;
          for (let i = 0; i < d.length; i += 4) { r += d[i]; g += d[i + 1]; b += d[i + 2]; }
          const n = d.length / 4;
          return { r: Math.round(r / n), g: Math.round(g / n), b: Math.round(b / n) };
        };
        const d = ctx.getImageData(0, 0, img.width, img.height).data;
        let sum = 0, sumSq = 0, max = 0, r = 0, g = 0, b = 0;
        for (let i = 0; i < d.length; i += 4) {
          const l = 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
          sum += l; sumSq += l * l; if (l > max) max = l;
          r += d[i]; g += d[i + 1]; b += d[i + 2];
        }
        const n = d.length / 4;
        const mean = sum / n;
        const lum = (c) => Math.round(0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b);
        const centre = region(0.32, 0.32, 0.36, 0.36);
        return {
          w: img.width, h: img.height,
          lum: Math.round(mean), maxLum: Math.round(max),
          std: +Math.sqrt(Math.max(0, sumSq / n - mean * mean)).toFixed(2),
          mean: { r: Math.round(r / n), g: Math.round(g / n), b: Math.round(b / n) },
          center: lum(centre), centerR: centre.r, centerB: centre.b,
          top: lum(region(0.2, 0.02, 0.6, 0.25)),
          bottom: lum(region(0.2, 0.8, 0.6, 0.18)),
        };
      })()`);
      const pixelProblems = PIXELS[id](pixels).filter(Boolean);
      pixelProblems.forEach((p) => fail(id, p));

      const file = `${String(i + 1).padStart(2, '0')}-${id}.png`;
      fs.writeFileSync(path.join(SHOTS, file), png);
      facts.animations = animations;
      measured.push({ id, file, viewport: `${width}×${height}`, scale, facts, pixels, problems: [...problems, ...pixelProblems] });
      rows.push({ id, file, title, note, size: `${width}×${height} @${scale}×`, facts, pixels, bytes: png.length });
      console.log(`${problems.length || pixelProblems.length ? '!!' : 'ok'} ${file.padEnd(34)} ${w}×${h}  ${
        (png.length / 1024).toFixed(0)} KiB  σ${pixels.std} mean ${pixels.lum}  ${animations} anim`);
    }

    // This folder belongs to the run: a surface inserted in the middle
    // renumbers everything after it, and the old names would otherwise stay
    // behind — a picture of a surface under a name that now means another one.
    // Only after a run where every surface wrote and every check passed: a
    // bad run must not take the last good shots with it.
    if (rows.length === surfaces.length && !failures.length) {
      const written = new Set(rows.map((r) => r.file));
      const stale = fs.readdirSync(SHOTS).filter((f) => f.endsWith('.png') && !written.has(f));
      stale.forEach((f) => fs.rmSync(path.join(SHOTS, f)));
      if (stale.length) console.log(`removed ${stale.length} stale shot(s): ${stale.join(', ')}`);
    }

    fs.writeFileSync(path.join(HERE, 'measurements.json'),
      JSON.stringify({ browser: path.basename(chrome.bin), at: new Date().toISOString().slice(0, 10), surfaces: measured }, null, 2) + '\n');
    fs.writeFileSync(path.join(HERE, 'index.html'), gallery(rows));

    // And the gallery itself, opened the way a person opens it: every figure
    // present, every image decoded. A file that references a shot that is not
    // there is the one way these pictures can still be wrong.
    await dt.send('Page.navigate', { url: `http://127.0.0.1:${port}/docs/loading-surfaces/index.html` }, sessionId);
    await sleep(400);
    const shown = JSON.parse(await read(`(async () => {
      const images = [...document.images];
      images.forEach((i) => { i.loading = 'eager'; });
      await Promise.all(images.map((i) => i.decode().catch(() => {})));
      return JSON.stringify({
        figures: document.querySelectorAll('figure').length,
        images: images.length,
        broken: images.filter((i) => !i.naturalWidth).map((i) => i.getAttribute('src')),
      });
    })()`));
    if (shown.figures !== rows.length) fail('index.html', `${shown.figures} figures for ${rows.length} surfaces`);
    if (shown.broken.length) fail('index.html', `images that did not load: ${shown.broken.join(', ')}`);
    console.log(`gallery: ${shown.figures} figures, ${shown.images - shown.broken.length}/${shown.images} images decoded`);

    if (failures.length) {
      console.error(`\n${failures.length} expectation(s) failed:`);
      failures.forEach((f) => console.error(`  ${f}`));
    } else {
      console.log(`\nwrote ${rows.length} shots, measurements.json and index.html`);
    }
  } finally {
    dt.close();
    chrome.child.kill();
    server.close();
    await sleep(200);
    fs.rmSync(chrome.profile, { recursive: true, force: true });
  }
  exit = failures.length ? 1 : 0;
  process.exitCode = exit;
}

/* ----------------------------------------------------------------- gallery */

const escapeHtml = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]
));

const factRows = (facts) => Object.entries(facts).map(([k, v]) => `<tr><th>${escapeHtml(k)}</th><td>${
  escapeHtml(typeof v === 'object' && v !== null ? JSON.stringify(v) : v)
}</td></tr>`).join('');

/** The page a person opens to review the shapes without running anything. */
function gallery(rows) {
  const cards = rows.map((r) => `
  <figure id="${escapeHtml(r.id)}">
    <a class="shot" href="shots/${escapeHtml(r.file)}"><img src="shots/${escapeHtml(r.file)}" alt="${escapeHtml(r.title)}" loading="lazy"></a>
    <figcaption>
      <h2>${escapeHtml(r.title)}</h2>
      <p class="meta">${escapeHtml(r.id)} · viewport ${escapeHtml(r.size)} · ${
        Math.round(r.bytes / 1024)} KiB · <a href="shots/${escapeHtml(r.file)}">full size</a></p>
      <p class="meta">image σ ${r.pixels.std} · mean ${r.pixels.lum} · centre ${r.pixels.center} · peak ${r.pixels.maxLum}</p>
      <p>${escapeHtml(r.note)}</p>
      <table>${factRows(r.facts)}</table>
    </figcaption>
  </figure>`).join('\n');

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Loading surfaces — screenshots</title>
<style>
  :root { color-scheme: dark; --ink:#f1ede7; --dim:#a49e94; --line:#2a2723; --bg:#0e0e0f; --card:#16161a; --accent:#ffe0bd; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--ink); font:16px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
  main { max-width: 1100px; margin: 0 auto; padding: 3rem 1.25rem 6rem; }
  h1 { font-size: 1.6rem; letter-spacing: -.01em; margin: 0 0 .5rem; }
  .lede { color: var(--dim); max-width: 68ch; }
  .lede a { color: var(--accent); }
  figure { margin: 3rem 0 0; border-top: 1px solid var(--line); padding-top: 1.75rem; display: grid; gap: 1.5rem; }
  @media (min-width: 900px) { figure { grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); align-items: start; } }
  .shot { display:block; border:1px solid var(--line); border-radius:.75rem; overflow:hidden; background:var(--card); }
  .shot img { display:block; width:100%; height:auto; }
  figcaption h2 { font-size: 1rem; margin: 0 0 .25rem; }
  figcaption p { margin: .5rem 0 0; color: var(--dim); font-size: .8125rem; }
  .meta { color: #7d7770; font-size: .75rem; }
  .meta a, figcaption a { color: var(--accent); }
  table { margin-top: .9rem; border-collapse: collapse; width: 100%; font-size: .75rem; }
  th, td { text-align: left; padding: .3rem .5rem .3rem 0; border-bottom: 1px solid var(--line); vertical-align: top; }
  th { color: #7d7770; font-weight: 400; white-space: nowrap; }
  td { color: var(--ink); word-break: break-word; }
</style>
</head>
<body>
<main>
  <h1>What the app draws while it waits</h1>
  <p class="lede">
    Every loading surface in the client, photographed from the client's own code:
    <code>server/client/src/loading.js</code> drew each one, the client's stylesheets sized it,
    and the veil, the nav and the sheet are the markup from <code>server/client/template.html</code>.
    Regenerate with <code>node docs/loading-surfaces/build.mjs</code> — see
    <a href="README.md">README.md</a> for what is real in these pictures, what is stand-in art,
    and what a still cannot show.
  </p>
  <p class="lede">${rows.length} surfaces · animations held at ${FRAME_AT}ms so two runs look the same.</p>
${cards}
</main>
</body>
</html>
`;
}

main().catch((e) => {
  console.error(`loading-surfaces: ${e.message || e}`);
  process.exitCode = 1;
});
