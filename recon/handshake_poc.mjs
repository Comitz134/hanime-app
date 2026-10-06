// Proof-of-concept: hanime.tv v11 handshake -> stream manifest URL.
// Scheme recovered from /_astro/CwylGxhk2.js ("htv-insecure-handshake-v1").
//
//   key  = SHA-256("htv-insecure-handshake-v1")
//   aad  = "htv-insecure-v1"
//   token = base64url( JSON.stringify({ v:1, alg:"AES-256-GCM",
//            iv:b64url(12B), tag:b64url(16B), data:b64url(ciphertext) }) )
//
// POST { token } -> /api/v11/handshake -> response header x-token holds
// the same envelope; decrypt -> { sources:[...] } where each source.src is
// an HLS master playlist (m3u8), optionally with ?token= appended.

import crypto from 'node:crypto';

const KEY_SEED = 'htv-insecure-handshake-v1';
const AAD = 'htv-insecure-v1';
const GUEST = 'https://guest.freeanimehentai.net';
const AUTHED = 'https://auth.hanime.tv';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const key = crypto.createHash('sha256').update(KEY_SEED, 'utf8').digest();

const b64u = (buf) => Buffer.from(buf).toString('base64url');
const unb64u = (s) => Buffer.from(String(s), 'base64url');

export function seal(payload) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv, { authTagLength: 16 });
  cipher.setAAD(Buffer.from(AAD, 'utf8'));
  const data = Buffer.concat([cipher.update(JSON.stringify(payload), 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return b64u(JSON.stringify({ v: 1, alg: 'AES-256-GCM', iv: b64u(iv), tag: b64u(tag), data: b64u(data) }));
}

export function open(token) {
  const env = JSON.parse(unb64u(token).toString('utf8'));
  if (env.v !== 1) throw new Error(`unexpected envelope version ${env.v}`);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, unb64u(env.iv), { authTagLength: 16 });
  decipher.setAAD(Buffer.from(AAD, 'utf8'));
  decipher.setAuthTag(unb64u(env.tag));
  return JSON.parse(Buffer.concat([decipher.update(unb64u(env.data)), decipher.final()]).toString('utf8'));
}

const commonHeaders = {
  'user-agent': UA,
  origin: 'https://hanime.tv',
  referer: 'https://hanime.tv/',
  accept: '*/*',
};

export async function csrfToken() {
  const r = await fetch('https://ct.hanime.tv/csrf-token', { headers: commonHeaders });
  const j = await r.json().catch(() => ({}));
  return { token: j.csrf_token ?? j.token ?? null, headers: r.headers };
}

export async function handshake(base, slug, csrf = null) {
  const payload = { timestamp_unix: Math.floor(Date.now() / 1000), directive: 'htv_player_handshake', slug };
  const headers = { ...commonHeaders, 'content-type': 'application/json' };
  if (csrf) headers['x-csrf-token'] = csrf;
  const r = await fetch(`${base}/api/v11/handshake`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ token: seal(payload) }),
  });
  const xt = r.headers.get('x-token');
  const body = await r.text();
  return { status: r.status, xToken: xt ? open(xt) : null, body: body.slice(0, 300) };
}

export async function fetchManifest(url) {
  const r = await fetch(url, { headers: commonHeaders });
  return { status: r.status, text: await r.text() };
}

const invokedDirectly = process.argv[1]
  && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href;

if (invokedDirectly) {
  const slug = process.argv[2] ?? 'yabai-fukushuu-yami-site-2';
  for (const base of [AUTHED, GUEST]) {
    try {
      const csrf = base === AUTHED ? (await csrfToken()).token : null;
      console.log(`\n=== ${base}/api/v11/handshake (csrf=${csrf ? 'yes' : 'no'})`);
      const out = await handshake(base, slug, csrf);
      console.log('status', out.status);
      if (out.xToken) {
        console.log('x-token sources:', JSON.stringify(out.xToken.sources, null, 1).slice(0, 1500));
        const first = out.xToken.sources?.[0];
        if (first?.src) {
          const m = await fetchManifest(first.src.startsWith('http') ? first.src : base + first.src);
          console.log('manifest status', m.status);
          console.log(m.text.slice(0, 800));
        }
      } else {
        console.log('body', out.body);
      }
    } catch (e) {
      console.log('ERR', e.message);
    }
  }
}
