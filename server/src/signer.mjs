// Signer: drives hanime.tv's emscripten module under a jsdom-free VM shim.
//
// The site gates every authenticated API call behind three headers:
//   x-signature-version: web2
//   x-signature: <64 hex>
//   x-time:      <unix seconds>
//
// Those two values are produced by a wasm module embedded (base64) in
// https://hanime-cdn.com/js/vendor.<hash>.min.js. The wasm registers a window
// listener for the event "e"; the site's fetch layer fires that event before
// every request, the wasm recomputes the signature, and the header is read off
// window.ssignature / window.stime.
//
// We reproduce exactly that: instantiate the module once, then dispatch a real
// 'e' CustomEvent per request and read the fresh pair off the shim.

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const VENDOR = path.resolve(HERE, '../vendor');

export const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

class BrowserCustomEvent {
  constructor(type, opts = {}) {
    this.type = type;
    this.detail = opts.detail;
    this.defaultPrevented = false;
  }
}

class BrowserEvent {
  constructor(type, opts = {}) {
    this.type = type;
    Object.assign(this, opts);
  }
}

function createWindow(wasmBinary) {
  const listeners = new Map();
  const noop = () => {};
  const storage = {
    getItem: () => null,
    setItem: noop,
    removeItem: noop,
  };

  const win = {
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const list = listeners.get(type);
      if (list) {
        const i = list.indexOf(fn);
        if (i >= 0) list.splice(i, 1);
      }
    },
    dispatchEvent(ev) {
      for (const fn of listeners.get(ev.type) ?? []) fn(ev);
      return true;
    },
    location: { href: 'https://hanime.tv/', origin: 'https://hanime.tv', protocol: 'https:' },
    navigator: { userAgent: USER_AGENT, language: 'en-US', languages: ['en-US'] },
    sessionStorage: storage,
    localStorage: storage,
    document: {
      createElement: () => ({
        style: {},
        setAttribute: noop,
        appendChild: noop,
        addEventListener: noop,
        getContext: () => null,
        remove: noop,
      }),
      documentElement: { style: {} },
      head: { appendChild: noop },
      body: { appendChild: noop },
      addEventListener: noop,
      removeEventListener: noop,
      cookie: '',
      currentScript: { src: 'https://hanime-cdn.com/js/vendor.min.js' },
    },
    CustomEvent: BrowserCustomEvent,
    Event: BrowserEvent,
    Image: class {
      set src(_) {}
    },
  };

  // self-references the module touches during bootstrap
  win.window = win;
  win.self = win;
  win.top = win;
  win.parent = win;
  win.global = win;

  const sandbox = {
    window: win,
    self: win,
    document: win.document,
    navigator: win.navigator,
    location: win.location,
    CustomEvent: BrowserCustomEvent,
    Event: BrowserEvent,
    Image: win.Image,
    crypto: globalThis.crypto,
    TextEncoder,
    TextDecoder,
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    console,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    queueMicrotask,
    performance: globalThis.performance,
    URL,
    URLSearchParams,
    AbortController,
    AbortSignal,
    fetch: globalThis.fetch,
    // emscripten picks this up as its global `Module`. Supplying wasmBinary
    // skips the bundle's own base64 decode; both paths yield the same module.
    Module: wasmBinary ? { wasmBinary } : {},
  };
  sandbox.globalThis = sandbox;
  sandbox.global = sandbox;

  return { win, sandbox };
}

let ready = null;

async function boot() {
  const jsPath = path.join(VENDOR, 'htv-signer.js');
  const wasmPath = path.join(VENDOR, 'htv-signer.wasm');
  if (!fs.existsSync(jsPath)) {
    throw new Error(`signer bundle missing at ${jsPath}`);
  }

  const source = fs.readFileSync(jsPath, 'utf8');
  // Optional: the bundle carries the same module as embedded base64. Passing it
  // in is faster and keeps the binary inspectable; absence is not fatal.
  const wasmBinary = fs.existsSync(wasmPath) ? fs.readFileSync(wasmPath) : null;
  const { win, sandbox } = createWindow(wasmBinary);

  const context = vm.createContext(sandbox, { codeGeneration: { strings: true, wasm: true } });
  vm.runInContext(source, context, { filename: 'htv-signer.js' });

  // emscripten runs main on a microtask; the signature lands on window.stime.
  const deadline = Date.now() + 8000;
  while (win.ssignature === undefined && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 20));
  }
  if (win.ssignature === undefined) throw new Error('signer module never produced a signature');

  return win;
}

/** Resolve the signer window, booting it on first use. */
export function getSigner() {
  ready ??= boot();
  return ready;
}

/**
 * Fresh signature pair. The wasm keys the signature to the current second, so
 * callers must sign immediately before the request they intend to send.
 * @returns {Promise<{ signature: string, time: number }>}
 */
export async function sign() {
  const win = await getSigner();
  win.dispatchEvent(new BrowserCustomEvent('e', { detail: {} }));
  return { signature: win.ssignature, time: win.stime };
}

/** Headers every hanime API call requires. */
export async function signedHeaders(extra = {}) {
  const { signature, time } = await sign();
  return {
    'user-agent': USER_AGENT,
    accept: 'application/json',
    'content-type': 'application/json',
    origin: 'https://hanime.tv',
    referer: 'https://hanime.tv/',
    'x-signature-version': 'web2',
    'x-signature': signature,
    'x-time': String(time),
    ...extra,
  };
}
