// Harness: load hanime.tv's emscripten module (hanime-cdn vendor bundle) under a
// browser-ish shim, let the wasm register its own window listeners, fire the
// event the fetch layer fires, and read window.ssignature / window.stime.
import fs from 'node:fs';
import vm from 'node:vm';

const SRC = process.argv[2] ?? '../server/vendor/htv-signer.js';
const code = fs.readFileSync(SRC, 'utf8');

const listeners = new Map();
const events = [];
function makeEvent(type, detail) {
  return { type, detail, target: null, currentTarget: null };
}

const windowShim = {
  addEventListener(type, fn) {
    if (!listeners.has(type)) listeners.set(type, []);
    listeners.get(type).push(fn);
  },
  removeEventListener(type, fn) {
    const l = listeners.get(type);
    if (l) l.splice(l.indexOf(fn) >>> 0, 1);
  },
  dispatchEvent(evt) {
    events.push(evt.type);
    for (const fn of listeners.get(evt.type) ?? []) {
      try { fn(evt); } catch (e) { console.error(`listener[${evt.type}] threw:`, e.message); }
    }
    return true;
  },
  CustomEvent: class CustomEvent { constructor(type, opts = {}) { this.type = type; this.detail = opts.detail; } },
  location: { href: 'https://hanime.tv/', origin: 'https://hanime.tv' },
  navigator: { userAgent: 'Mozilla/5.0', language: 'en-US', languages: ['en-US'] },
  sessionStorage: (() => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) }; })(),
  localStorage: (() => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) }; })(),
  crypto: globalThis.crypto,
  TextEncoder,
  TextDecoder,
  fetch: globalThis.fetch,
  atob: (s) => Buffer.from(s, 'base64').toString('binary'),
  btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  performance: globalThis.performance,
  console,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  queueMicrotask,
  URL,
  Promise,
  Math,
  Date,
  JSON,
  Uint8Array,
  Int8Array,
  Uint16Array,
  Int16Array,
  Uint32Array,
  Int32Array,
  Float32Array,
  Float64Array,
  ArrayBuffer,
  DataView,
  Number,
  String,
  Object,
  Array,
  Boolean,
  Error,
  TypeError,
  RangeError,
  Map,
  Set,
  WeakMap,
  WeakSet,
  Symbol,
  Reflect,
  Proxy,
  globalThis,
  Image: class { set src(_) {} },
  document: {
    createElement: () => ({ style: {}, setAttribute() {}, appendChild() {}, addEventListener() {}, getContext: () => null, remove() {} }),
    documentElement: { style: {} },
    head: { appendChild() {} },
    body: { appendChild() {} },
    addEventListener() {},
    removeEventListener() {},
    cookie: '',
    currentScript: { src: 'https://hanime-cdn.com/js/vendor.min.js' },
  },
};
windowShim.window = windowShim;
windowShim.self = windowShim;
windowShim.top = windowShim;
windowShim.parent = windowShim;

const sandbox = {
  window: windowShim,
  self: windowShim,
  document: windowShim.document,
  navigator: windowShim.navigator,
  location: windowShim.location,
  crypto: windowShim.crypto,
  fetch: globalThis.fetch,
  TextEncoder,
  TextDecoder,
  atob: windowShim.atob,
  btoa: windowShim.btoa,
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
  Blob: class {},
  Worker: undefined,
  process: undefined,
  require: undefined,
  module: undefined,
  exports: undefined,
  __filename: undefined,
  __dirname: undefined,
  globalThis: undefined,
};
sandbox.global = sandbox;
sandbox.globalThis = sandbox;

sandbox.CustomEvent = windowShim.CustomEvent;
sandbox.Event = class Event { constructor(type, opts = {}) { this.type = type; Object.assign(this, opts); } };
windowShim.Event = sandbox.Event;
windowShim.CustomEvent = sandbox.CustomEvent;

const ctx = vm.createContext(sandbox, { codeGeneration: { strings: true, wasm: true } });

// Feed the wasm straight in so emscripten doesn't try to fetch its own bundle.
const wasmBytes = fs.readFileSync(new URL('../server/vendor/htv-signer.wasm', import.meta.url));
const prelude = `var Module = { wasmBinary: new Uint8Array([${Array.from(wasmBytes.slice(0, 8)).join(',')}]) };`;

vm.runInContext(code, ctx, { filename: SRC });

// Let emscripten finish instantiation, then poke it.
const settle = (ms) => new Promise((r) => setTimeout(r, ms));
await settle(500);

const W = ctx.window;
console.log('registered event types:', [...(listeners.keys())].reverse());
console.log('wasm flags: wasmReady=', typeof W.wasmReady, 'stime=', W.stime, 'ssignature=', W.ssignature);

for (const type of [...listeners.keys()].reverse()) {
  W.dispatchEvent(new W.CustomEvent(type, { detail: {} }));
  await settle(60);
}
console.log('after dispatch: stime=', W.stime, 'ssignature=', W.ssignature);
