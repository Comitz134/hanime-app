// Full round trip: wasm signature -> CSRF -> handshake -> stream manifest.
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';

const KEY_SEED = 'htv-insecure-handshake-v1';
const AAD = 'htv-insecure-v1';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

// --- 1. signer: instantiate the vendor wasm and expose a sign() that fires 'e' ---
const vendorCode = fs.readFileSync(new URL('../server/vendor/htv-signer.js', import.meta.url), 'utf8');
const listeners = new Map();
const win = {
  addEventListener(t, fn) { (listeners.get(t) ?? listeners.set(t, []).get(t)).push(fn); },
  removeEventListener() {},
  dispatchEvent(ev) { for (const fn of listeners.get(ev.type) ?? []) fn(ev); return true; },
  location: { href: 'https://hanime.tv/', origin: 'https://hanime.tv' },
  navigator: { userAgent: UA, language: 'en-US', languages: ['en-US'] },
  sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  document: {
    createElement: () => ({ style: {}, setAttribute() {}, appendChild() {}, addEventListener() {}, remove() {} }),
    documentElement: { style: {} }, head: { appendChild() {} }, body: { appendChild() {} },
    addEventListener() {}, removeEventListener() {}, cookie: '', currentScript: { src: 'https://hanime-cdn.com/js/vendor.min.js' },
  },
};
class CustomEvent { constructor(type, opts = {}) { this.type = type; this.detail = opts.detail; } }
class Event { constructor(type, opts = {}) { this.type = type; Object.assign(this, opts); } }
win.CustomEvent = CustomEvent;
win.Event = Event;
win.window = win; win.self = win; win.top = win; win.parent = win;

const sandbox = {
  window: win, self: win, document: win.document, navigator: win.navigator, location: win.location,
  CustomEvent, Event, crypto: globalThis.crypto, TextEncoder, TextDecoder,
  atob: (s) => Buffer.from(s, 'base64').toString('binary'),
  btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  console, setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
  performance: globalThis.performance, URL, URLSearchParams, AbortController, AbortSignal,
  fetch: globalThis.fetch, Image: class { set src(_) {} },
};
sandbox.global = sandbox; sandbox.globalThis = sandbox; sandbox.globalThis = win;
vm.runInContext(vendorCode, vm.createContext(sandbox, { codeGeneration: { strings: true, wasm: true } }), { filename: 'vendor.min.js' });
await new Promise((r) => setTimeout(r, 400));

export function sign() {
  win.dispatchEvent(new CustomEvent('e', { detail: {} }));
  return { ssignature: win.ssignature, stime: win.stime };
}

// --- 2. token envelope ---
const key = crypto.createHash('sha256').update(KEY_SEED, 'utf8').digest();
const b64u = (b) => Buffer.from(b).toString('base64url');
const unb64u = (s) => Buffer.from(String(s), 'base64url');

function seal(payload) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, iv, { authTagLength: 16 });
  c.setAAD(Buffer.from(AAD, 'utf8'));
  const data = Buffer.concat([c.update(JSON.stringify(payload), 'utf8'), c.final()]);
  return b64u(JSON.stringify({ v: 1, alg: 'AES-256-GCM', iv: b64u(iv), tag: b64u(c.getAuthTag()), data: b64u(data) }));
}
function open(tok) {
  const e = JSON.parse(unb64u(tok).toString('utf8'));
  const d = crypto.createDecipheriv('aes-256-gcm', key, unb64u(e.iv), { authTagLength: 16 });
  d.setAAD(Buffer.from(AAD, 'utf8'));
  d.setAuthTag(unb64u(e.tag));
  return JSON.parse(Buffer.concat([d.update(unb64u(e.data)), d.final()]).toString('utf8'));
}

// --- 3. API calls ---
export const API = 'https://auth.hanime.tv';
const jar = new Map();
function cookieHeader() { return [...jar].map(([k, v]) => `${k}=${v}`).join('; '); }
function absorb(res) {
  for (const c of res.headers.getSetCookie?.() ?? []) {
    const [pair] = c.split(';');
    const i = pair.indexOf('=');
    jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
  }
}

async function api(url, { method = 'GET', body, csrf } = {}) {
  const s = sign();
  const headers = {
    'user-agent': UA,
    accept: 'application/json',
    'content-type': 'application/json',
    origin: 'https://hanime.tv',
    referer: 'https://hanime.tv/',
    'x-signature-version': 'web2',
    'x-signature': s.ssignature,
    'x-time': String(s.stime),
  };
  if (csrf) headers['x-csrf-token'] = csrf;
  if (jar.size) headers.cookie = cookieHeader();
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  absorb(res);
  const text = await res.text();
  let data = null;
  try { data = text.trim() ? JSON.parse(text) : null; } catch { data = text.slice(0, 200); }
  return { res, data };
}

async function main() {
  const slug = process.argv[2] ?? 'yabai-fukushuu-yami-site-2';

  const s0 = sign();
  console.log('signature sample:', s0.ssignature.slice(0, 32), 'time', s0.stime);

  const csrfRes = await api('https://ct.hanime.tv/csrf-token');
  console.log('csrf status', csrfRes.res.status, JSON.stringify(csrfRes.data)?.slice(0, 120));
  const csrf = csrfRes.data?.csrf_token;
  console.log('cookies:', [...jar.keys()]);

  const payload = { timestamp_unix: Math.floor(Date.now() / 1000), directive: 'htv_player_handshake', slug };
  const hs = await api(`${API}/api/v11/handshake`, { method: 'POST', body: { token: seal(payload) }, csrf });
  console.log('handshake status', hs.res.status, 'body', JSON.stringify(hs.data).slice(0, 200));
  const xt = hs.res.headers.get('x-token');
  if (xt) {
    const parsed = open(xt);
    console.log('x-token keys:', Object.keys(parsed));
    console.log('sources:', JSON.stringify(parsed.sources, null, 1).slice(0, 1200));
    const src = parsed.sources?.find((x) => x.src?.startsWith('http')) ?? parsed.sources?.[0];
    if (src?.src) {
      const m = await fetch(src.src, { headers: { 'user-agent': UA, origin: 'https://hanime.tv', referer: 'https://hanime.tv/' } });
      console.log('manifest status', m.status, m.headers.get('content-type'));
      console.log((await m.text()).slice(0, 700));
    }
  }
}

const direct = process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href;
if (direct) await main();
