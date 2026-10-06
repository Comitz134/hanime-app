// The app self-update contract, tested offline.
//
// This is the part of the updater that can be tested without a phone: what the
// server publishes, what it refuses to publish, and what a client is told when
// there is nothing to install. The Android side mirrors these rules, so a
// change here is a change to the app's behaviour.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Writable } from 'node:stream';

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'htv-apprelease-'));
process.env.APK_DIR = TMP;

const mod = await import('../src/app-release.mjs');

const FAKE_APK = Buffer.from('not a real apk, just bytes for hashing');

function writeManifest(overrides = {}, { withApk = true } = {}) {
  const file = overrides.file ?? 'app.hanime.shell-9.apk';
  if (withApk && !fs.existsSync(path.join(TMP, file))) {
    fs.writeFileSync(path.join(TMP, file), FAKE_APK);
  }
  const manifest = {
    application_id: 'app.hanime.shell',
    version_code: 9,
    version_name: '1.4.0',
    file,
    size: FAKE_APK.length,
    sha256: 'deadbeef',
    notes: 'test release',
    published_at: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
  fs.writeFileSync(path.join(TMP, 'release.json'), JSON.stringify(manifest));
  return manifest;
}

function clearManifest() {
  const f = path.join(TMP, 'release.json');
  if (fs.existsSync(f)) fs.rmSync(f);
}

test.after(() => fs.rmSync(TMP, { recursive: true, force: true }));

// --------------------------------------------------------------------------
// nothing published
// --------------------------------------------------------------------------

test('a server with no release answers configured:false, not an error', () => {
  clearManifest();
  const payload = mod.versionPayload();
  assert.equal(payload.ok, true);
  assert.equal(payload.configured, false);
  assert.match(payload.hint, /publish-apk/);
  assert.equal(mod.readRelease(), null);
});

test('a manifest whose APK is missing reads as no release', () => {
  // The file was published and then deleted. Offering it would send the app a
  // download that 404s.
  writeManifest({ file: 'gone.apk' }, { withApk: false });
  assert.equal(mod.readRelease(), null);
  assert.equal(mod.versionPayload().configured, false);
});

test('a manifest with no usable version code reads as no release', () => {
  for (const bad of [0, -1, '3', null, undefined]) {
    writeManifest({ version_code: bad });
    assert.equal(mod.readRelease(), null, `version_code ${JSON.stringify(bad)} was accepted`);
  }
});

test('a corrupt manifest reads as no release rather than throwing', () => {
  fs.writeFileSync(path.join(TMP, 'release.json'), '{ not json');
  assert.doesNotThrow(() => mod.versionPayload());
  assert.equal(mod.versionPayload().configured, false);
});

// --------------------------------------------------------------------------
// published
// --------------------------------------------------------------------------

test('a published release is described with a relative download URL', () => {
  const manifest = writeManifest();
  const payload = mod.versionPayload();

  assert.equal(payload.configured, true);
  assert.equal(payload.version_code, 9);
  assert.equal(payload.version_name, '1.4.0');
  assert.equal(payload.size, FAKE_APK.length);
  assert.equal(payload.sha256, 'deadbeef');
  assert.equal(payload.notes, 'test release');

  // Relative on purpose: the same server is reached as 10.0.2.2 from an
  // emulator and a LAN address from a phone, and a baked-in absolute URL would
  // be wrong for one of them.
  assert.equal(payload.apk_url, '/api/app/apk/app.hanime.shell-9.apk');
  assert.ok(!payload.apk_url.startsWith('http'), 'apk_url must stay relative');
});

test('a manifest with no version_name still reports a usable one', () => {
  writeManifest({ version_name: undefined });
  assert.equal(mod.versionPayload().version_name, '9');
});

// --------------------------------------------------------------------------
// path safety
// --------------------------------------------------------------------------

test('only plain .apk file names are accepted', () => {
  const refused = [
    '../release.json',
    '..%2f..%2frelease.json',
    'sub/dir/app.apk',
    'app.apk/../release.json',
    '.apk',
    'app.txt',
    'app.apk.exe',
    '',
    null,
    undefined,
  ];
  for (const name of refused) {
    assert.equal(mod.apkPath(name), null, `accepted ${JSON.stringify(name)}`);
  }
});

test('a traversal attempt in the manifest is refused at read time', () => {
  // Even a manifest written by hand with a hostile `file` cannot escape.
  writeManifest({ file: '../../etc/passwd.apk' }, { withApk: false });
  assert.equal(mod.readRelease(), null);
  assert.equal(mod.versionPayload().configured, false);
});

test('a legitimate file name resolves to a real path inside the APK directory', () => {
  writeManifest({ file: 'ok.apk' });
  const resolved = mod.apkPath('ok.apk');
  assert.ok(resolved);
  assert.ok(resolved.startsWith(path.resolve(TMP) + path.sep));
});

// --------------------------------------------------------------------------
// serving
// --------------------------------------------------------------------------

/**
 * A real Writable standing in for ServerResponse.
 *
 * Deliberately not a hand-rolled object with a writeHead: streamApk pipes into
 * the response, so anything that is not a genuine Writable fails in the pipe
 * rather than in the code under test.
 */
class FakeRes extends Writable {
  constructor() {
    super();
    this.status = null;
    this.headers = null;
    this.chunks = [];
  }

  writeHead(status, headers) {
    this.status = status;
    this.headers = headers;
    return this;
  }

  _write(chunk, _enc, cb) {
    this.chunks.push(Buffer.from(chunk));
    cb();
  }

  body() {
    return Buffer.concat(this.chunks);
  }

  /** Resolves once the pipe has finished writing. */
  finishedWriting() {
    return new Promise((resolve) => {
      if (this.writableFinished) return resolve();
      this.on('finish', resolve);
    });
  }
}

function fakeRes() {
  return new FakeRes();
}

test('a missing APK is a 404 with a JSON body, not a crash', () => {
  writeManifest({ file: 'real.apk' });
  const res = fakeRes();
  mod.streamApk('nope.apk', { headers: {} }, res);
  assert.equal(res.status, 404);
  assert.match(res.chunks.join(''), /not_found/);
});

test('a traversal in the request path is a 404', () => {
  writeManifest();
  for (const name of ['../../release.json', 'release.json', 'sub/app.apk']) {
    const res = fakeRes();
    mod.streamApk(name, { headers: {} }, res);
    assert.equal(res.status, 404, `served ${name}`);
  }
});

test('a published APK streams its bytes with the installer content type', async () => {
  writeManifest({ file: 'stream.apk' });
  const res = fakeRes();
  mod.streamApk('stream.apk', { headers: {} }, res);
  await res.finishedWriting();

  assert.equal(res.status, 200);
  assert.equal(res.headers['content-type'], 'application/vnd.android.package-archive');
  assert.equal(res.headers['content-length'], FAKE_APK.length);
  assert.equal(res.headers['accept-ranges'], 'bytes');
  // The bytes that reach the client are the bytes of the file, not a re-encode.
  assert.deepEqual(res.body(), FAKE_APK);
});

test('a range request answers 206 with exactly the requested slice', async () => {
  writeManifest({ file: 'range.apk' });
  const res = fakeRes();
  mod.streamApk('range.apk', { headers: { range: 'bytes=0-3' } }, res);
  await res.finishedWriting();

  assert.equal(res.status, 206);
  assert.equal(res.headers['content-length'], 4);
  assert.equal(res.headers['content-range'], `bytes 0-3/${FAKE_APK.length}`);
  assert.deepEqual(res.body(), FAKE_APK.subarray(0, 4));
});

test('an unsatisfiable range falls back to the whole file', async () => {
  writeManifest({ file: 'bad-range.apk' });
  const res = fakeRes();
  mod.streamApk('bad-range.apk', { headers: { range: 'bytes=99999-100000' } }, res);
  assert.equal(res.status, 200);
  assert.equal(res.headers['content-length'], FAKE_APK.length);
  // Drain it: leaving a read stream open outlives this test and then fails
  // against the temp directory being removed.
  await res.finishedWriting();
  assert.deepEqual(res.body(), FAKE_APK);
});

// --------------------------------------------------------------------------
// the rule the app actually applies
// --------------------------------------------------------------------------

test('the client-side comparison is strictly greater, so a re-publish is not an update', () => {
  // Mirrors UpdateInfo.isNewerThan on the Android side.
  const isNewer = (published, installed) => published > installed;
  assert.equal(isNewer(2, 1), true);
  assert.equal(isNewer(1, 1), false, 'same version must not prompt');
  assert.equal(isNewer(1, 2), false, 'an older publish must never downgrade');
});
