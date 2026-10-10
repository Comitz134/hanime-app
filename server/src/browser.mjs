// A real browser, borrowed for one question: what does the player actually play?
//
// The film doors (f-movies' "servers") do not expose an API. Their player
// builds a signed CDN URL at runtime — the signature comes out of their own
// JavaScript, it changes with every deployment, and the CDN refuses anything
// that did not come from a page on their site (no referer, 403). What *can* be
// observed is the result: the browser asks for a master playlist. So this
// borrows the machine's own Chromium-family browser for a few seconds, opens
// the door, and reads the first playlist request off the wire — no guessing at
// their signing, and nothing to port when they rebuild.
//
// Zero dependencies on purpose, like the rest of this server: Node's built-in
// WebSocket speaks CDP, and Chrome or Edge is already installed on any machine
// that can run the app. The browser is a *resolution* tool, not a renderer:
// playback never happens here, only the question of where the bytes are.
//
// Everything here fails closed. No browser, a page that never starts a player,
// a machine that cannot spawn a process — each returns `{ ok: false }` with a
// code the caller can put in a log, never an exception that takes the request
// down with it.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** How long a door may take to start asking for video. */
const DEFAULT_TIMEOUT_MS = 25_000;
/** How long a resolved URL is reused. The CDN tokens outlive this comfortably. */
const CACHE_TTL_MS = 10 * 60_000;

const cache = new Map();

/**
 * Doors serve their player only to a browser that looks like one. Headless
 * Chrome announces itself as `HeadlessChrome/…` and gets a page with no player
 * on it at all — which is why the UA is overridden rather than left to the
 * browser, and why the automation flag is dropped with it.
 */
const PLAYER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

/** Only the tests call this; the same hook exists in the other modules. */
export function clearCache() {
  cache.clear();
}

function windowsCandidates() {
  const programFiles = [process.env['ProgramFiles'], process.env['ProgramFiles(x86)']].filter(Boolean);
  const out = [];
  for (const root of programFiles) {
    out.push(path.join(root, 'Google', 'Chrome', 'Application', 'chrome.exe'));
    out.push(path.join(root, 'Microsoft', 'Edge', 'Application', 'msedge.exe'));
  }
  return out;
}

/**
 * Chromium builds that Playwright (or a CI image) already downloaded. Kept
 * because it is the one browser guaranteed to be present in an automation
 * environment, and it costs a directory listing to find.
 */
function bundledChromiumCandidates() {
  const roots = [
    process.env['LOCALAPPDATA'] ? path.join(process.env['LOCALAPPDATA'], 'ms-playwright') : null,
    process.env['HOME'] ? path.join(process.env['HOME'], '.cache', 'ms-playwright') : null,
  ].filter(Boolean);
  const out = [];
  for (const root of roots) {
    let entries = [];
    try {
      entries = fs.readdirSync(root).filter((name) => name.startsWith('chromium'));
    } catch {
      continue;
    }
    for (const name of entries) {
      out.push(path.join(root, name, 'chrome-win', 'chrome.exe'));
      out.push(path.join(root, name, 'chrome-linux', 'chrome'));
      out.push(path.join(root, name, 'chrome-mac', 'Chromium.app', 'Contents', 'MacOS', 'Chromium'));
    }
  }
  return out;
}

/**
 * Every browser this machine could lend us, best first. `HANIME_BROWSER` wins
 * when set, so a machine with a browser in an unusual place needs no code
 * change — just an environment variable.
 */
export function browserCandidates() {
  const candidates = [
    process.env.HANIME_BROWSER,
    ...windowsCandidates(),
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/snap/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    ...bundledChromiumCandidates(),
  ];
  return candidates.filter(Boolean).filter((file) => {
    try {
      return fs.statSync(file).isFile();
    } catch {
      return false;
    }
  });
}

/** Does any browser exist on this machine — i.e. can films resolve at all? */
export function browserAvailable() {
  return browserCandidates().length > 0;
}

/**
 * The playlist request that says "this is the video". Both the master and the
 * media playlists match; the *first* one seen is the master (a player always
 * asks for it before any variant), which is the one worth handing to a player.
 */
export function isPlaylistUrl(url) {
  return /\.m3u8(\?|#|$)/i.test(url);
}

/** Referer the door page sent, when it sent one. */
export function refererFromHeaders(headers = {}) {
  const key = Object.keys(headers).find((name) => name.toLowerCase() === 'referer');
  return key ? headers[key] : null;
}

/** One CDP command over one socket, JSON-RPC shaped. */
function sendCommand(socket, id, method, params = {}) {
  socket.send(JSON.stringify({ id, method, params }));
}

async function pageTarget(port) {
  const res = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(5_000) });
  const targets = await res.json();
  const page = targets.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
  return page?.webSocketDebuggerUrl ?? null;
}

function waitForDevtoolsWs(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const timer = setTimeout(() => reject(new Error('devtools endpoint never appeared')), timeoutMs);
    const onData = (chunk) => {
      buffer += chunk.toString('utf8');
      // Chromium prints the endpoint on stderr, optionally behind other noise.
      const m = /ws:\/\/([0-9.]+):(\d+)\//.exec(buffer);
      if (m) {
        clearTimeout(timer);
        child.stderr.off('data', onData);
        resolve({ host: m[1], port: Number(m[2]) });
      }
    };
    child.stderr.on('data', onData);
    child.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`browser exited early (code ${code})`));
    });
  });
}

function killTree(child) {
  try {
    if (process.platform === 'win32' && child.pid) {
      // The browser spawns its own children; killing only the root leaves them.
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      child.kill('SIGKILL');
    }
  } catch {
    // Nothing to do: the process is already gone.
  }
}

/**
 * Open `doorUrl` in a real browser and return the first playlist it asks for.
 *
 * @returns {Promise<{ok: true, url: string, referer: string|null} | {ok: false, code: string}>}
 */
async function resolveInBrowser(doorUrl, { timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
  const [browser] = browserCandidates();
  if (!browser) return { ok: false, code: 'no_browser' };

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hanime-resolve-'));
  const child = spawn(browser, [
    '--headless=new',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-background-networking',
    '--mute-audio',
    // Doors that wait for a gesture would never start otherwise.
    '--autoplay-policy=no-user-gesture-required',
    `--user-agent=${PLAYER_UA}`,
    '--disable-blink-features=AutomationControlled',
    '--window-size=1280,720',
    'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });

  let socket = null;
  try {
    const { port } = await waitForDevtoolsWs(child, 15_000);
    const wsUrl = await pageTarget(port);
    if (!wsUrl) return { ok: false, code: 'no_page_target' };

    const found = await new Promise((resolve) => {
      socket = new WebSocket(wsUrl);
      let id = 0;
      const timer = setTimeout(() => resolve(null), timeoutMs);

      socket.addEventListener('open', () => {
        sendCommand(socket, ++id, 'Network.enable');
        sendCommand(socket, ++id, 'Page.enable');
        sendCommand(socket, ++id, 'Page.navigate', { url: doorUrl });
      });

      socket.addEventListener('message', (event) => {
        let message;
        try {
          message = JSON.parse(typeof event.data === 'string' ? event.data : event.data.toString());
        } catch {
          return;
        }
        if (message.method !== 'Network.requestWillBeSent') return;
        const request = message.params?.request;
        if (!request || request.method !== 'GET' || !isPlaylistUrl(request.url)) return;
        clearTimeout(timer);
        resolve({ url: request.url, referer: refererFromHeaders(request.headers) });
      });

      socket.addEventListener('error', () => {
        clearTimeout(timer);
        resolve(null);
      });

      socket.addEventListener('close', () => {
        clearTimeout(timer);
        resolve(null);
      });
    });

    if (!found) return { ok: false, code: 'no_playlist_seen' };
    return { ok: true, url: found.url, referer: found.referer ?? new URL(doorUrl).origin + '/' };
  } catch (e) {
    return { ok: false, code: 'browser_failed', detail: e.message };
  } finally {
    try {
      socket?.close();
    } catch {
      // The socket is already gone.
    }
    killTree(child);
    // Best effort: Windows holds a lock on the profile for a moment after exit.
    setTimeout(() => {
      try {
        fs.rmSync(profile, { recursive: true, force: true });
      } catch {
        // A leftover temp profile is not worth failing a resolution over.
      }
    }, 2_000).unref?.();
  }
}

/**
 * Resolution is expensive (a browser boot) and the route that uses it is
 * reachable from anywhere this server is reachable, so at most one runs at a
 * time and a second caller for the same door joins the first instead of
 * starting another browser.
 */
const inFlight = new Map();

/**
 * Resolve a door to a playable URL, remembering the answer briefly: the door
 * page is a whole browser boot, so a second open of the same film must not pay
 * for it again.
 */
export async function resolveDoor(doorUrl, options = {}) {
  const hit = cache.get(doorUrl);
  if (hit && hit.expires > Date.now()) return { ...hit.value, cached: true };

  const running = inFlight.get(doorUrl);
  if (running) return running;
  if (inFlight.size > 0) return { ok: false, code: 'busy' };

  const value = resolveInBrowser(doorUrl, options)
    .then((result) => {
      if (result.ok) cache.set(doorUrl, { value: result, expires: Date.now() + CACHE_TTL_MS });
      return result;
    })
    .finally(() => inFlight.delete(doorUrl));

  inFlight.set(doorUrl, value);
  return value;
}
