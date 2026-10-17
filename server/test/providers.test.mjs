// The provider register (src/providers.mjs): what counts as openable, what the
// answer carries, and what happens to a host nobody claims.
//
// The register's job is to be the one place that knows which embed hosts this
// server can open; the resolvers themselves are tested next to their own
// captures (test/flixcloud.test.mjs). What is tested here is the seam — that a
// URL is claimed by exactly the provider that can open it, that a resolve comes
// back with the provider's own referer attached, and that an unclaimed host is
// refused without a single network call, because the stream picker asks this
// question for every source it lists.

import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  isResolvableEmbed,
  providerFor,
  resolveEmbed,
  resolveSubtitles,
  providerIds,
} from '../src/providers.mjs';
import { resetCache, FLIX_REFERER } from '../src/flixcloud.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const capture = JSON.parse(fs.readFileSync(path.join(HERE, 'fixtures/flixcloud-capture.json'), 'utf8'));

const realFetch = globalThis.fetch;
let fetchLog = [];

function stubFetch(routes) {
  fetchLog = [];
  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    fetchLog.push({ url: u, opts });
    for (const [match, reply] of routes) {
      if (u.includes(match)) return reply(u, opts);
    }
    throw new Error(`unstubbed fetch: ${u}`);
  };
}

afterEach(() => {
  globalThis.fetch = realFetch;
  resetCache();
});

/** The captured embed page, rendered the way the tests below need it. */
const embedPage = `<!doctype html><html><body><script>
  const data = { obfuscation_seed:"${capture.embed.seed}",
    ${capture.embed.tokenField}:"${capture.embed.token}",
    ${capture.embed.keyFrag2Field}:"${capture.embed.keyFrag2Value}",
    ${capture.embed.containerName}:{${capture.embed.arrayName}:[{${capture.embed.objectName}:{${capture.embed.keyField}:"${capture.embed.keyFieldValue}",${capture.embed.ivField}:"${capture.embed.ivFieldValue}"}}]},
    w_payload:"${capture.embed.wPayload}",
    subtitles:${capture.embed.subtitlesRaw ?? '[]'} };
</script></body></html>`;

// ------------------------------------------------------------------ matching

test('flixcloud embeds are openable, everything else is not', () => {
  assert.equal(isResolvableEmbed(capture.embed.source), true);
  assert.equal(isResolvableEmbed('https://flixcloud.cc/e/bo2qdw3m3kjf?v=2'), true);
  assert.equal(isResolvableEmbed('https://flixcloud.to/e/bo2qdw3m3kjf'), true);

  // A provider nobody registered: the stream picker must say no here, so the
  // episode answers with no entry at all — the register is where a second
  // provider is added, and a browser hand-off is no longer the fallback.
  assert.equal(isResolvableEmbed('https://other-provider.example/e/xyz?v=2'), false);
  // A flixcloud URL that is not an embed page is still not openable.
  assert.equal(isResolvableEmbed('https://flixcloud.cc/watch/bo2qdw3m3kjf'), false);
  assert.equal(isResolvableEmbed('nonsense'), false);
});

test('the register names the provider that claims a URL', () => {
  const provider = providerFor(capture.embed.source);
  assert.equal(provider?.id, 'flixcloud');
  assert.equal(provider?.referer, FLIX_REFERER);
  assert.equal(providerFor('https://other-provider.example/e/xyz'), null);
  assert.deepEqual(providerIds(), ['flixcloud']);
});

// ------------------------------------------------------------------ resolving

test('a resolve comes back with the provider and its referer attached', async () => {
  stubFetch([
    ['flixcloud.cc/e/', () => ({ ok: true, status: 200, text: async () => embedPage })],
    ['/api/m3u8/', () => ({ ok: true, status: 200, json: async () => capture.api })],
  ]);

  const found = await resolveEmbed(capture.embed.source);
  assert.equal(found.ok, true, `resolve failed: ${found.code}`);
  assert.equal(found.provider, 'flixcloud');
  assert.equal(found.url, capture.expected.masterUrl);
  assert.equal(found.referer, FLIX_REFERER, 'every request below the master needs this referer');
  assert.equal(Buffer.from(found.key, 'base64url').toString('hex'), capture.expected.playlistKey);
});

test('an unclaimed host is refused before any request is made', async () => {
  stubFetch([]);

  const found = await resolveEmbed('https://other-provider.example/e/xyz?v=2');
  assert.equal(found.ok, false);
  assert.equal(found.code, 'unknown_provider');
  assert.equal(fetchLog.length, 0, 'listing an episode must not fan out to unknown hosts');
});

test('subtitles are the provider\'s tracks, carried with the provider\'s referer', async () => {
  stubFetch([
    ['flixcloud.cc/e/', () => ({ ok: true, status: 200, text: async () => embedPage })],
    ['/api/m3u8/', () => ({ ok: true, status: 200, json: async () => capture.api })],
  ]);

  const found = await resolveSubtitles(capture.embed.source);
  assert.equal(found.ok, true, `subtitles failed: ${found.code}`);
  assert.equal(found.provider, 'flixcloud');
  assert.equal(found.referer, FLIX_REFERER, 'whoever relays these files has to read them as the provider');
  assert.deepEqual(found.tracks, capture.parsed.subtitles);
});

test('subtitles from an unclaimed host are refused without a fetch too', async () => {
  stubFetch([]);

  const found = await resolveSubtitles('https://other-provider.example/e/xyz?v=2');
  assert.equal(found.ok, false);
  assert.equal(found.code, 'unknown_provider');
  assert.equal(fetchLog.length, 0);
});
