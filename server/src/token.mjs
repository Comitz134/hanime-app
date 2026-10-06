// Handshake token envelope.
//
// Recovered from https://hanime.tv/_astro/CwylGxhk2.js, which names its own
// scheme "htv-insecure-handshake-v1". AES-256-GCM with the key derived from a
// string constant baked into the bundle — no server secret involved, so anyone
// holding this file can mint and read these tokens.
//
//   key   = SHA-256("htv-insecure-handshake-v1")
//   aad   = "htv-insecure-v1"
//   wire  = base64url( JSON.stringify({ v:1, alg:"AES-256-GCM",
//             iv: b64url(12 random bytes),
//             tag: b64url(16 byte GCM tag),
//             data: b64url(ciphertext) }) )
//
// A single base64url layer wraps the envelope; the JSON inside is Base64URL
// text, not raw bytes.

import crypto from 'node:crypto';

const KEY_SEED = 'htv-insecure-handshake-v1';
const AAD = 'htv-insecure-v1';
const TAG_BYTES = 16;

const key = crypto.createHash('sha256').update(KEY_SEED, 'utf8').digest();

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const unb64url = (s) => Buffer.from(String(s), 'base64url');

/**
 * Seal an arbitrary JSON payload into the wire envelope.
 * @param {unknown} payload
 * @returns {string} base64url envelope
 */
export function seal(payload) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv, { authTagLength: TAG_BYTES });
  cipher.setAAD(Buffer.from(AAD, 'utf8'));
  const data = Buffer.concat([cipher.update(JSON.stringify(payload), 'utf8'), cipher.final()]);
  return b64url(
    JSON.stringify({
      v: 1,
      alg: 'AES-256-GCM',
      iv: b64url(iv),
      tag: b64url(cipher.getAuthTag()),
      data: b64url(data),
    }),
  );
}

/**
 * Open a wire envelope back into its payload.
 * Throws on tampering — GCM authentication is enforced by node:crypto.
 * @param {string} envelope
 * @returns {any}
 */
export function open(envelope) {
  const env = JSON.parse(unb64url(envelope).toString('utf8'));
  if (env.v !== 1) throw new Error(`unsupported envelope version: ${env.v}`);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, unb64url(env.iv), {
    authTagLength: TAG_BYTES,
  });
  decipher.setAAD(Buffer.from(AAD, 'utf8'));
  decipher.setAuthTag(unb64url(env.tag));
  const out = Buffer.concat([decipher.update(unb64url(env.data)), decipher.final()]);
  return JSON.parse(out.toString('utf8'));
}
