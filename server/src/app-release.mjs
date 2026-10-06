// The app's update source.
//
// The Android shell asks `GET /api/app/version` on every cold start. Whatever
// is published here is what installed apps will offer to download, so the
// manifest is written by a script that reads the version straight out of the
// APK rather than by hand — see scripts/publish-apk.mjs. A hand-edited version
// number that disagrees with the binary is the classic way a self-updater
// breaks.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

export const APK_DIR =
  process.env.APK_DIR ?? path.resolve(HERE, '../data/apk');
export const MANIFEST_FILE = path.join(APK_DIR, 'release.json');

/** Characters an APK file name may contain. Anything else is refused outright. */
const SAFE_FILE = /^[A-Za-z0-9._-]+\.apk$/;

/**
 * The published release, or null when this server has no app release.
 *
 * A malformed manifest reads as "no release" rather than throwing: a client
 * checking for updates should get a clean "nothing here" instead of a 500 it
 * has to interpret.
 */
export function readRelease() {
  try {
    if (!fs.existsSync(MANIFEST_FILE)) return null;
    const manifest = JSON.parse(fs.readFileSync(MANIFEST_FILE, 'utf8'));

    if (!Number.isInteger(manifest.version_code) || manifest.version_code <= 0) return null;
    if (typeof manifest.file !== 'string' || !SAFE_FILE.test(manifest.file)) return null;
    if (!fs.existsSync(path.join(APK_DIR, manifest.file))) return null;

    return manifest;
  } catch {
    return null;
  }
}

/** Absolute path to the published APK, or null if the manifest names nothing real. */
export function apkPath(fileName) {
  if (typeof fileName !== 'string' || !SAFE_FILE.test(fileName)) return null;
  const full = path.resolve(APK_DIR, fileName);
  // Belt and braces: the name pattern already excludes separators, and this
  // guarantees the resolved path is still inside the directory.
  if (!full.startsWith(path.resolve(APK_DIR) + path.sep)) return null;
  return fs.existsSync(full) ? full : null;
}

/**
 * What the updater endpoint returns.
 *
 * `apk_url` is deliberately relative: the app already knows which base it
 * reached this endpoint on, and an absolute URL baked in at publish time would
 * break the moment the same server is reached by LAN address on one device and
 * tunnel hostname on another.
 */
export function versionPayload() {
  const release = readRelease();
  if (!release) {
    return {
      ok: true,
      configured: false,
      hint: 'No app release is published on this server yet. Publish one with server/scripts/publish-apk.mjs.',
    };
  }

  return {
    ok: true,
    configured: true,
    version_code: release.version_code,
    version_name: release.version_name ?? String(release.version_code),
    apk_url: `/api/app/apk/${release.file}`,
    size: release.size ?? null,
    sha256: release.sha256 ?? null,
    notes: release.notes ?? null,
    published_at: release.published_at ?? null,
    // Advisory only. The app compares version_code; this is for display.
    min_version_code: release.min_version_code ?? null,
  };
}

/** Streams the APK. Range requests are supported so a resume works. */
export function streamApk(fileName, req, res) {
  const file = apkPath(fileName);
  if (!file) {
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not_found', file: fileName }));
    return;
  }

  const stat = fs.statSync(file);
  const range = req?.headers?.range;

  const headers = {
    'content-type': 'application/vnd.android.package-archive',
    'accept-ranges': 'bytes',
    'cache-control': 'no-store',
    'content-disposition': `attachment; filename="${path.basename(file)}"`,
  };

  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (m) {
      const start = m[1] === '' ? 0 : Number(m[1]);
      const end = m[2] === '' ? stat.size - 1 : Number(m[2]);
      if (start <= end && end < stat.size) {
        res.writeHead(206, {
          ...headers,
          'content-range': `bytes ${start}-${end}/${stat.size}`,
          'content-length': end - start + 1,
        });
        fs.createReadStream(file, { start, end }).pipe(res);
        return;
      }
    }
  }

  res.writeHead(200, { ...headers, 'content-length': stat.size });
  fs.createReadStream(file).pipe(res);
}
