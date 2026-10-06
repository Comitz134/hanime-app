// The GitHub Releases half of the updater, tested offline.
//
// publish-github.mjs is what turns a locally built APK into something a phone
// can actually reach, so the parts that must not drift are the manifest it
// writes (the app parses it in UpdateInfo.parse) and the URLs it hands out.
// Nothing here touches the network: a real upload needs a token and a repo, and
// a test that needs those is a test nobody runs.

import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'htv-ghpub-'));
// Must be set before the script is imported: app-release.mjs captures APK_DIR
// at module load, and publish-github.mjs pulls it in transitively.
process.env.APK_DIR = TMP;
process.env.GITHUB_REPOSITORY = 'octo/hanime-app';
delete process.env.GITHUB_TOKEN;

const gh = await import('../scripts/publish-github.mjs');
const appRel = await import('../src/app-release.mjs');

const APK_BYTES = Buffer.from('fake apk bytes for the github publisher');
const APK_NAME = 'app.hanime.shell-3.apk';

function writeRelease(overrides = {}, { withApk = true } = {}) {
  const file = overrides.file ?? APK_NAME;
  if (withApk && !fs.existsSync(path.join(TMP, file))) {
    fs.writeFileSync(path.join(TMP, file), APK_BYTES);
  }
  const manifest = {
    application_id: 'app.hanime.shell',
    version_code: 3,
    version_name: '1.1.0',
    file,
    size: APK_BYTES.length,
    sha256: 'cafe1234',
    notes: 'from publish-apk',
    published_at: '2026-10-06T00:00:00.000Z',
    ...overrides,
  };
  fs.writeFileSync(path.join(TMP, 'release.json'), JSON.stringify(manifest));
  return manifest;
}

function clearRelease() {
  const f = path.join(TMP, 'release.json');
  if (fs.existsSync(f)) fs.rmSync(f);
}

/** Runs `fn` with fetch replaced by a counter, so "no network" is asserted. */
async function withoutNetwork(fn) {
  const realFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (...args) => {
    calls.push(args[0]);
    throw new Error(`unexpected network call: ${args[0]}`);
  };
  try {
    return await fn(calls);
  } finally {
    globalThis.fetch = realFetch;
  }
}

test.after(() => fs.rmSync(TMP, { recursive: true, force: true }));

// --------------------------------------------------------------------------
// the tag
// --------------------------------------------------------------------------

test('the release tag is the version name with a v', () => {
  assert.equal(gh.releaseTag('1.1.0'), 'v1.1.0');
  assert.equal(gh.releaseTag('2'), 'v2');
});

test('an already-v version name is not doubled', () => {
  assert.equal(gh.releaseTag('v1.1.0'), 'v1.1.0');
});

test('a release with no version name cannot be tagged', () => {
  for (const bad of ['', '   ', null, undefined]) {
    assert.throws(() => gh.releaseTag(bad), /no version_name/, `accepted ${JSON.stringify(bad)}`);
  }
});

// --------------------------------------------------------------------------
// the manifest the phone parses
// --------------------------------------------------------------------------

test('the manifest carries everything UpdateInfo.parse requires', () => {
  const release = writeRelease();
  const url =
    'https://github.com/octo/hanime-app/releases/download/v1.1.0/app.hanime.shell-3.apk';
  const m = gh.buildManifest(release, url);

  // UpdateInfo.parse rejects the manifest unless configured, version_code>0
  // and a non-empty apk_url all hold — a half-published release would look
  // like an update and then fail at install time.
  assert.equal(m.configured, true);
  assert.ok(Number.isInteger(m.version_code) && m.version_code > 0);
  assert.ok(m.apk_url.length > 0);

  assert.equal(m.version_code, 3);
  assert.equal(m.version_name, '1.1.0');
  assert.equal(m.apk_url, url);
  assert.equal(m.size, APK_BYTES.length);
  assert.equal(m.sha256, 'cafe1234');
  assert.equal(m.notes, 'from publish-apk');
});

test('notes passed at publish time win over the recorded ones', () => {
  const release = writeRelease();
  const url = 'https://example.invalid/apk';

  assert.equal(gh.buildManifest(release, url, 'shipped from CI').notes, 'shipped from CI');
  assert.equal(gh.buildManifest(release, url).notes, 'from publish-apk');
  assert.equal(gh.buildManifest({ ...release, notes: undefined }, url).notes, null);
});

test('a manifest with no version_name still reports a usable one', () => {
  const release = writeRelease({ version_name: undefined });
  assert.equal(gh.buildManifest(release, 'https://example.invalid/apk').version_name, '3');
});

test('there is nothing to publish without a release', () => {
  assert.throws(() => gh.buildManifest(null, 'https://example.invalid/apk'), /nothing to publish/);
});

test('the GitHub manifest uses an absolute APK URL, unlike the server payload', () => {
  writeRelease();
  const served = appRel.versionPayload();
  const hosted = gh.buildManifest(
    appRel.readRelease(),
    'https://github.com/o/r/releases/download/v1.1.0/a.apk',
  );

  // Same fields, deliberately different URL policy. versionPayload() stays
  // relative because one server is reached as 10.0.2.2 from an emulator and a
  // LAN address from a phone; a release asset has exactly one address in the
  // world, and the manifest is fetched from a different host than it serves.
  assert.ok(served.apk_url.startsWith('/'), 'server payload must stay relative');
  assert.ok(hosted.apk_url.startsWith('https://'), 'release manifest must be absolute');

  for (const field of ['configured', 'version_code', 'version_name', 'size', 'sha256', 'notes']) {
    assert.ok(field in hosted, `manifest is missing ${field}`);
  }
});

// --------------------------------------------------------------------------
// URLs
// --------------------------------------------------------------------------

test('asset URLs point at the release download route', () => {
  assert.equal(
    gh.assetDownloadUrl({
      serverUrl: 'https://github.com',
      owner: 'octo',
      repo: 'hanime-app',
      tag: 'v1.1.0',
      name: 'app.hanime.shell-3.apk',
    }),
    'https://github.com/octo/hanime-app/releases/download/v1.1.0/app.hanime.shell-3.apk',
  );
});

test('a trailing slash on the server URL does not double up', () => {
  const opts = {
    serverUrl: 'https://github.com/',
    owner: 'o',
    repo: 'r',
    tag: 'v1',
    name: 'a.apk',
  };
  assert.equal(
    gh.assetDownloadUrl(opts),
    'https://github.com/o/r/releases/download/v1/a.apk',
  );
});

test('tag and file name are URL-escaped', () => {
  const url = gh.assetDownloadUrl({
    serverUrl: 'https://github.com',
    owner: 'o',
    repo: 'r',
    tag: 'v1 1',
    name: 'a b.apk',
  });
  assert.match(url, /v1%201\/a%20b\.apk$/);
});

// --------------------------------------------------------------------------
// repository resolution
// --------------------------------------------------------------------------

test('owner/repo is split, not guessed', () => {
  assert.deepEqual(gh.resolveRepo('octo/hanime-app'), {
    owner: 'octo',
    repo: 'hanime-app',
  });
  assert.deepEqual(gh.resolveRepo('  a.b_c/d-e  '), { owner: 'a.b_c', repo: 'd-e' });
});

test('a repository that is not owner/repo is refused with a useful message', () => {
  const bad = ['', 'hanime-app', 'a/b/c', 'a b/c', '/repo', 'owner/', null, undefined];
  for (const value of bad) {
    assert.throws(() => gh.resolveRepo(value), /owner\/repo/, `accepted ${JSON.stringify(value)}`);
  }
});

test('a missing repository is refused with an actionable message', async () => {
  writeRelease();
  delete process.env.GITHUB_REPOSITORY;
  try {
    await assert.rejects(() => gh.publish({ dryRun: true }), /GITHUB_REPOSITORY/);
  } finally {
    process.env.GITHUB_REPOSITORY = 'octo/hanime-app';
  }
});

test('an explicit repo option beats the environment', async () => {
  writeRelease();
  const out = await gh.publish({ dryRun: true, repo: 'me/other' });
  assert.equal(out.repo, 'me/other');
  assert.match(out.apk.url, /^https:\/\/github\.com\/me\/other\//);
});

// --------------------------------------------------------------------------
// what never touches the network
// --------------------------------------------------------------------------

test('a dry run needs no token and makes no network call', async () => {
  writeRelease();
  await withoutNetwork(async (calls) => {
    const out = await gh.publish({ dryRun: true, repo: 'octo/hanime-app' });

    assert.equal(out.dry_run, true);
    assert.equal(out.tag, 'v1.1.0');
    assert.equal(out.repo, 'octo/hanime-app');
    assert.equal(
      out.apk.url,
      'https://github.com/octo/hanime-app/releases/download/v1.1.0/app.hanime.shell-3.apk',
    );
    assert.equal(out.apk.size, APK_BYTES.length);
    assert.equal(out.manifest.configured, true);
    assert.match(out.manifest_url, /releases\/download\/v1\.1\.0\/version\.json$/);

    assert.deepEqual(calls, [], 'a dry run must not reach for the network');
    assert.equal(process.env.GITHUB_TOKEN, undefined, 'a dry run must not need a token');
  });
});

test('a real publish without GITHUB_TOKEN fails before any network call', async () => {
  writeRelease();
  await withoutNetwork(async (calls) => {
    await assert.rejects(() => gh.publish({ repo: 'octo/hanime-app' }), /GITHUB_TOKEN/);
    assert.deepEqual(calls, [], 'must fail on the missing token, not mid-upload');
  });
});

test('with nothing published the script says so instead of guessing', async () => {
  clearRelease();
  await assert.rejects(() => gh.publish({ dryRun: true, repo: 'o/r' }), /publish-apk/);
});

// --------------------------------------------------------------------------
// the release actually on disk
// --------------------------------------------------------------------------

test('the release currently on disk is internally consistent', () => {
  const dir = path.resolve(import.meta.dirname, '../data/apk');
  const mf = path.join(dir, 'release.json');
  assert.ok(fs.existsSync(mf), 'no release.json — publish one with publish-apk.mjs');

  const rel = JSON.parse(fs.readFileSync(mf, 'utf8'));
  assert.ok(Number.isInteger(rel.version_code) && rel.version_code > 0, 'bad version_code');
  assert.match(rel.file, /^[A-Za-z0-9._-]+\.apk$/, 'manifest file name is not safe');

  const apk = path.resolve(dir, rel.file);
  assert.ok(apk.startsWith(path.resolve(dir) + path.sep), 'manifest file escapes its directory');
  assert.ok(fs.existsSync(apk), `APK missing: ${rel.file}`);

  const sha = crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex');
  assert.equal(sha, rel.sha256, 'published sha256 does not describe the published APK');

  // The GitHub publisher must describe the same binary the server serves, or
  // the two update sources would disagree about what is current.
  const hosted = gh.buildManifest(rel, 'https://github.com/o/r/releases/download/v/x.apk');
  assert.equal(hosted.version_code, rel.version_code);
  assert.equal(hosted.sha256, rel.sha256);
  assert.equal(hosted.size, rel.size);
  assert.equal(gh.releaseTag(rel.version_name), `v${String(rel.version_name).replace(/^v/, '')}`);
});
