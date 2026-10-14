// Flixcloud: the anime area's embed provider, and the media path that sits
// behind it.
//
// Their client never hands a player a stream. It hands it an obfuscated payload
// and a wasm module, and everything below was read out of that client and then
// reproduced here — without a browser, because every step turned out to be an
// ordinary HTTP request once the wrapping was understood. In order:
//
//   1. the embed page (`/e/<id>?v=2`) carries a JS payload: an obfuscation seed, a
//      wasm module, and three key fragments under field names *derived* from that
//      seed, so the names differ on every load;
//   2. `/api/m3u8/<token>` answers the ciphertext and the third fragment, keyed by
//      two SHA-256 prefixes of the token, which is why a stateless fetch is enough;
//   3. the wasm mixes the three fragments under a seed integer. PBKDF2 over the
//      mixture, XOR with the seed's own characters, then SHA-256 makes an AES-256
//      key, and AES-CBC turns the ciphertext into the master playlist URL;
//   4. the same wasm also holds a 32-byte playlist key (`_c()`, which their page
//      publishes as `window.__pk`): every playlist body is base64 of the playlist
//      XORed with that key;
//   5. segments are a PNG (8-byte) or WebP (12-byte) header followed by the same
//      bytes XORed with a fixed 16-byte key. That decodes to MPEG-TS — so the
//      disguise is a header plus a repeating key, not a cipher.
//
// Consequence for the server: nothing here needs a browser, and the only state
// the relay has to carry between requests is the playlist key from step 4.

import crypto from 'node:crypto';

export const FLIX_REFERER = 'https://flixcloud.cc/';
export const FLIX_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const FETCH_TIMEOUT_MS = 30_000;
// Long enough that one playback does not re-resolve per segment, short enough
// that a stale master URL (its token lasts hours) is never what a new play gets.
const CACHE_TTL_MS = 15 * 60_000;

/** The fixed 16-byte key the segment bodies are XORed with. */
const SEGMENT_KEY = Buffer.from([
  157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197,
]);
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

const cache = new Map();

const sha256hex = (value) => crypto.createHash('sha256').update(value).digest('hex');

/** Only their embed pages go down this path; anything else is not ours to open. */
export function isFlixcloudEmbed(url) {
  try {
    const parsed = new URL(url);
    return /(^|\.)flixcloud\.(cc|to|com)$/.test(parsed.hostname) && /^\/e\/[A-Za-z0-9_-]+$/.test(parsed.pathname);
  } catch {
    return false;
  }
}

async function get(url) {
  return fetch(url, {
    headers: { 'user-agent': FLIX_UA, referer: FLIX_REFERER, accept: '*/*' },
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
}

/**
 * The field names their client derives from the seed: three chained hashes for
 * the names, three more for the key fragment's own name. Reproduced exactly,
 * since a single step out of place finds nothing on the page.
 */
function fieldsFor(seed) {
  let e = seed;
  for (let i = 0; i < 3; i += 1) e = sha256hex(e + i);
  let a = e;
  for (let i = 0; i < 3; i += 1) a = sha256hex(a + i);
  return {
    keyField: `kf_${e.slice(8, 16)}`,
    ivField: `ivf_${e.slice(16, 24)}`,
    tokenField: `${e.slice(48, 64)}_${e.slice(56, 64)}`,
    keyFrag2Field: `${a.slice(0, 16)}_${a.slice(16, 24)}`,
  };
}

/**
 * One field out of the page's JS payload. The payload is an object literal, not
 * JSON — keys are bare when they can be and quoted when they cannot — so the
 * lookup accepts both spellings rather than pretending it is JSON.
 */
function fieldValue(html, name) {
  for (const pattern of [`"${name}":"([^"]*)"`, `${name}:"([^"]*)"`]) {
    const match = new RegExp(pattern).exec(html);
    if (match) return match[1];
  }
  return null;
}

/**
 * The wasm's key mix, run exactly as the page runs it: three fragments written
 * at fixed offsets, one seed integer, one call — plus `_c()`, the playlist key
 * that only exists after the mix has been run.
 */
async function mixKeys(wasmBytes, fragments, seedInt) {
  const { instance } = await WebAssembly.instantiate(wasmBytes, {});
  const { memory, _s: setSeed, _r: read, _c: playlistKey } = instance.exports;
  if (!memory || typeof setSeed !== 'function' || typeof read !== 'function') {
    return { ok: false, code: 'wasm_shape' };
  }
  if (memory.buffer.byteLength === 0) memory.grow(1);

  const view = new Uint8Array(memory.buffer);
  const len = fragments[0].length;
  const offsets = [1000, 1000 + len, 1000 + 2 * len];
  fragments.forEach((fragment, i) => view.set(fragment, offsets[i]));
  const out = 1000 + 3 * len;
  setSeed(seedInt);
  read(offsets[0], offsets[1], offsets[2], out, len);

  const mixed = Buffer.from(new Uint8Array(memory.buffer).subarray(out, out + len));
  const key = typeof playlistKey === 'function'
    ? Buffer.from(new Uint8Array(memory.buffer).subarray(playlistKey(), playlistKey() + 32))
    : null;
  return { ok: true, mixed, playlistKey: key };
}

/**
 * AES-256 over the token response. PBKDF2 is the page's own choice of
 * parameters: 1000 rounds, the seed as salt, then the seed's characters XORed
 * back over the derived bits before the final hash.
 */
function openMasterUrl(mixed, seed, iv, ciphertext) {
  const bits = crypto.pbkdf2Sync(mixed, Buffer.from(seed, 'utf8'), 1000, 32, 'sha256');
  const stirred = Buffer.from(bits);
  for (let i = 0; i < 32; i += 1) stirred[i] ^= seed.charCodeAt(i % seed.length);
  const key = crypto.createHash('sha256').update(stirred).digest();
  const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString('utf8');
}

/**
 * The ISO code for a language the page names in words.
 *
 * The page writes a track the way a person would — 'English ([SoM] English)',
 * 'Portuguese(Brazil)', 'Spanish(Latin_America)' — and a client matches a
 * preferred language against a code: 'English' matches no preference at all,
 * 'en' matches an English one. So the name is reduced to its code here, and the
 * regional names keep their region for the same reason (a Brazilian track is
 * not a Portuguese one).
 *
 * A name with no entry is left to the caller as `null` rather than guessed at:
 * an unrecognised language should still reach the picker under the name the
 * page gave it, not disappear behind an invented code.
 */
export function languageCodeOf(name) {
  const text = String(name ?? '').trim().toLowerCase();
  if (!text) return null;

  const at = text.indexOf('(');
  const base = (at < 0 ? text : text.slice(0, at)).replace(/\s+/g, ' ').trim();
  const detail = at < 0 ? '' : text.slice(at);

  const code = LANGUAGE_CODES.get(base);
  if (!code) return null;
  if (code === 'pt') return /brazil|brasil/.test(detail) ? 'pt-BR' : 'pt';
  if (code === 'es') return /latin|latam|america|419/.test(detail) ? 'es-419' : 'es';
  if (code === 'zh') return /traditional/.test(detail) ? 'zh-TW' : 'zh-CN';
  return code;
}

const LANGUAGE_CODES = new Map([
  ['english', 'en'],
  ['japanese', 'ja'],
  ['korean', 'ko'],
  ['chinese', 'zh'],
  ['cantonese', 'zh-TW'],
  ['arabic', 'ar'],
  ['french', 'fr'],
  ['german', 'de'],
  ['italian', 'it'],
  ['spanish', 'es'],
  ['portuguese', 'pt'],
  ['russian', 'ru'],
  ['polish', 'pl'],
  ['dutch', 'nl'],
  ['swedish', 'sv'],
  ['norwegian', 'no'],
  ['danish', 'da'],
  ['finnish', 'fi'],
  ['icelandic', 'is'],
  ['turkish', 'tr'],
  ['greek', 'el'],
  ['hebrew', 'he'],
  ['hindi', 'hi'],
  ['urdu', 'ur'],
  ['bengali', 'bn'],
  ['tamil', 'ta'],
  ['telugu', 'te'],
  ['malayalam', 'ml'],
  ['kannada', 'kn'],
  ['marathi', 'mr'],
  ['thai', 'th'],
  ['vietnamese', 'vi'],
  ['indonesian', 'id'],
  ['malay', 'ms'],
  ['filipino', 'tl'],
  ['tagalog', 'tl'],
  ['czech', 'cs'],
  ['slovak', 'sk'],
  ['hungarian', 'hu'],
  ['romanian', 'ro'],
  ['bulgarian', 'bg'],
  ['ukrainian', 'uk'],
  ['catalan', 'ca'],
  ['basque', 'eu'],
  ['galician', 'gl'],
  ['croatian', 'hr'],
  ['serbian', 'sr'],
  ['bosnian', 'bs'],
  ['slovenian', 'sl'],
  ['macedonian', 'mk'],
  ['albanian', 'sq'],
  ['lithuanian', 'lt'],
  ['latvian', 'lv'],
  ['estonian', 'et'],
  ['persian', 'fa'],
  ['farsi', 'fa'],
  ['afrikaans', 'af'],
  ['swahili', 'sw'],
]);

/**
 * The page's own subtitle list, from the same object literal as the payload:
 *
 *   subtitles:[{url:"https://…/…_eng_3.ass",language:"English (Dialogues [SoM])",
 *               format:"ass",default:true}, …]
 *
 * The array is found by scanning to its matching bracket rather than by a
 * regex over the whole page: the objects inside are the only part of the page
 * known to be a list, and a page without subtitles has to read as "none", not
 * as a failure to decrypt. The files are CDN assets the player would have
 * fetched itself — the page only names them.
 */
export function parseSubtitles(html) {
  const at = html.indexOf('subtitles:');
  if (at < 0) return [];
  const open = html.indexOf('[', at);
  if (open < 0) return [];

  let depth = 0;
  let quote = null;
  let end = -1;
  for (let i = open; i < html.length; i += 1) {
    const c = html[i];
    if (quote) {
      if (c === '\\') i += 1;
      else if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") quote = c;
    else if (c === '[' || c === '{') depth += 1;
    else if (c === ']' || c === '}') {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  if (end < 0) return [];

  const tracks = [];
  for (const match of html.slice(open + 1, end).matchAll(/\{[^{}]*\}/g)) {
    const object = match[0];
    const url = stringFieldIn(object, 'url');
    if (!url || !/^https:\/\//.test(url)) continue;
    const language = stringFieldIn(object, 'language') ?? 'unknown';
    tracks.push({
      url,
      language,
      // The name is for people, the code is what a picker matches a preferred
      // language against — see languageCodeOf above.
      code: languageCodeOf(language),
      format: stringFieldIn(object, 'format') ?? url.split('.').pop().toLowerCase(),
      default: /default\s*:\s*true/.test(object),
    });
  }
  return tracks;
}

/** One string field of a JS object literal, bare or quoted key. */
function stringFieldIn(object, name) {
  const match = new RegExp(`"?${name}"?\\s*:\\s*"([^"]*)"`).exec(object);
  return match ? match[1] : null;
}

/** A playlist body: either already text, or base64 of the playlist XORed with the key. */
export function decodePlaylist(bytes, key) {
  const raw = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  const text = raw.toString('latin1');
  if (text.trimStart().startsWith('#EXTM3U')) return text;
  if (!/^[A-Za-z0-9+/=\s]+$/.test(text)) return null;
  const binary = Buffer.from(text.trim(), 'base64');
  const keyBytes = Buffer.isBuffer(key) ? key : Buffer.from(String(key), 'base64url');
  if (!keyBytes.length) return null;
  const out = Buffer.from(binary);
  for (let i = 0; i < out.length; i += 1) out[i] ^= keyBytes[i % keyBytes.length];
  const decoded = out.toString('latin1');
  return decoded.trimStart().startsWith('#EXTM3U') ? decoded : null;
}

/**
 * A media segment: drop the image header the CDN serves it under, undo the
 * repeating XOR, and hand back MPEG-TS. Returns null when the body is already
 * plain, so the relay can tell "disguised" from "not".
 */
export function unsealSegment(bytes) {
  const raw = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  let body = null;
  if (raw.subarray(0, 8).equals(PNG_SIGNATURE)) body = raw.subarray(8);
  else if (raw.subarray(0, 4).toString('latin1') === 'RIFF' && raw.subarray(8, 12).toString('latin1') === 'WEBP') {
    body = raw.subarray(12);
  }
  if (!body || body.length < 188) return null;
  // A body that already starts on a sync byte was served in the clear.
  if (body[0] === 0x47) return Buffer.from(body);
  const out = Buffer.from(body);
  for (let i = 0; i < out.length; i += 1) out[i] ^= SEGMENT_KEY[i & 15];
  return out[0] === 0x47 ? out : null;
}

/**
 * The subtitle tracks an embed offers, without opening a player: the same page
 * fetch the stream resolver makes, cached with it, so asking for subtitles and
 * then playing costs one page and one decrypt.
 */
export async function subtitleTracks(embedUrl) {
  const found = await resolveStream(embedUrl);
  if (!found.ok) return found;
  return { ok: true, tracks: found.subtitles ?? [] };
}

/**
 * Embed page in, master playlist URL and playlist key out.
 *
 * Cached per embed: a player asks for the master playlist once and every
 * segment after that travels through the relay, so the resolve happens once per
 * playback rather than once per request.
 */
export async function resolveStream(embedUrl) {
  const hit = cache.get(embedUrl);
  if (hit && Date.now() - hit.at < CACHE_TTL_MS) return hit.value;

  const value = await resolveUncached(embedUrl);
  if (value.ok) cache.set(embedUrl, { at: Date.now(), value });
  return value;
}

async function resolveUncached(embedUrl) {
  let html;
  try {
    const page = await get(embedUrl);
    if (!page.ok) return { ok: false, code: `embed_http_${page.status}` };
    html = await page.text();
  } catch (e) {
    return { ok: false, code: 'embed_unreachable', error: e.message };
  }

  const seed = fieldValue(html, 'obfuscation_seed');
  const wasmB64 = fieldValue(html, 'w_payload');
  if (!seed || !wasmB64) return { ok: false, code: 'no_payload' };

  // Subtitles are named on the page itself, so a page that decrypts but has
  // none still resolves; a page that fails earlier has none by definition.
  const subtitles = parseSubtitles(html);

  const names = fieldsFor(seed);
  const frag1B64 = fieldValue(html, names.keyField);
  const ivB64 = fieldValue(html, names.ivField);
  const frag2B64 = fieldValue(html, names.keyFrag2Field);
  const token = fieldValue(html, names.tokenField);
  if (!frag1B64 || !ivB64 || !frag2B64 || !token) return { ok: false, code: 'no_fragments' };

  let tokenBody;
  try {
    const tokenRes = await get(`https://flixcloud.cc/api/m3u8/${token}`);
    if (!tokenRes.ok) return { ok: false, code: `token_http_${tokenRes.status}` };
    tokenBody = await tokenRes.json();
  } catch (e) {
    return { ok: false, code: 'token_unreachable', error: e.message };
  }

  const ciphertext = tokenBody?.[sha256hex(`${token}vid`).slice(0, 10)];
  const frag3B64 = tokenBody?.[sha256hex(`${token}key`).slice(0, 10)];
  if (!ciphertext || !frag3B64) return { ok: false, code: 'no_token_fields' };

  let mixed;
  let playlistKey;
  try {
    const mixedKeys = await mixKeys(
      Buffer.from(wasmB64, 'base64'),
      [Buffer.from(frag1B64, 'base64'), Buffer.from(frag2B64, 'base64'), Buffer.from(frag3B64, 'base64')],
      parseInt(seed.slice(0, 8), 16),
    );
    if (!mixedKeys.ok) return mixedKeys;
    ({ mixed, playlistKey } = mixedKeys);
  } catch (e) {
    return { ok: false, code: 'wasm_failed', error: e.message };
  }

  if (!playlistKey) return { ok: false, code: 'no_playlist_key' };

  let url;
  try {
    url = openMasterUrl(mixed, seed, Buffer.from(ivB64, 'base64'), Buffer.from(ciphertext, 'base64'));
  } catch (e) {
    return { ok: false, code: 'open_failed', error: e.message };
  }
  if (!/^https:\/\//.test(url)) return { ok: false, code: 'not_a_url' };

  return {
    ok: true,
    url,
    // base64url so it can ride in the relay link like the referer does.
    key: playlistKey.toString('base64url'),
    token,
    // Free with the page we already fetched, and cached with it: a player that
    // opens the subtitle picker minutes into an episode must not cost a second
    // round trip to the same page.
    subtitles,
  };
}

/** Test seam: the cache is per process, and a test wants a cold one. */
export function resetCache() {
  cache.clear();
}
