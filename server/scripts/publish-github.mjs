#!/usr/bin/env node
// Publish a built release to GitHub Releases, as the app's public update source.
//
//   node scripts/publish-github.mjs
//   node scripts/publish-github.mjs --repo you/hanime-app --notes "..."
//   node scripts/publish-github.mjs --dry-run
//
// Why this exists: the Android shell checks a URL on every cold start, and that
// URL has to be reachable from a phone that is nowhere near your machine. The
// server's own /api/app/version only answers while your PC is up. A GitHub
// Release answers from anywhere, and its asset URLs are plain downloads.
//
// What it uploads, as two assets on a `v<versionName>` release:
//
//   <file>.apk        the signed APK, byte for byte as built
//   version.json      the manifest the app parses
//
// The manifest is the same shape /api/app/version returns, with one deliberate
// difference: `apk_url` is ABSOLUTE. The manifest is fetched from
// `github.com/releases/download/...` while the APK lives on the same release,
// so a relative path would have to be guessed — and the app only treats a URL
// as relative when it is one.
//
// Required environment:
//   GITHUB_TOKEN       a personal access token with `contents: write`
//   GITHUB_REPOSITORY  `owner/repo` (or pass --repo)
//
// Optional:
//   GITHUB_API_URL     default https://api.github.com
//   GITHUB_UPLOADS_URL default https://uploads.github.com
//   GITHUB_SERVER_URL  default https://github.com
//
// The version number is read from the release manifest, which publish-apk.mjs
// wrote by parsing the APK with aapt2 — never passed in by hand.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { readRelease, apkPath } from '../src/app-release.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const val = (f, d) => {
  const i = argv.indexOf(f);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};

const dryRun = has('--dry-run');
const notesOverride = val('--notes', null);
const repoArg = val('--repo', null);

// ------------------------------------------------------------------ pure bits

/** Release tag. The leading v keeps it a valid tag and readable in a list. */
export function releaseTag(versionName) {
  const name = String(versionName ?? '').trim();
  if (!name) throw new Error('release has no version_name');
  return name.startsWith('v') ? name : `v${name}`;
}

/**
 * The manifest the Android updater parses. Mirrors versionPayload() from
 * src/app-release.mjs, with `apk_url` made absolute for release assets.
 */
export function buildManifest(release, apkUrl, notes = null) {
  if (!release) throw new Error('nothing to publish');
  return {
    ok: true,
    configured: true,
    version_code: release.version_code,
    version_name: release.version_name ?? String(release.version_code),
    apk_url: apkUrl,
    size: release.size ?? null,
    sha256: release.sha256 ?? null,
    notes: notes ?? release.notes ?? null,
    published_at: release.published_at ?? null,
    min_version_code: release.min_version_code ?? null,
  };
}

/** Download URL for one asset of a release. */
export function assetDownloadUrl({ serverUrl, owner, repo, tag, name }) {
  const base = String(serverUrl ?? '').replace(/\/+$/, '');
  return `${base}/${owner}/${repo}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`;
}

/**
 * The URL to configure on devices.
 *
 * Deliberately NOT the version-pinned path above: a phone pointed at
 * `.../download/v1.0.2/version.json` would keep checking v1.0.2 for ever and
 * never learn about v1.0.3. GitHub's `/releases/latest/download/<asset>`
 * always redirects to the newest non-draft, non-prerelease, so one constant
 * URL survives every future publish — which is exactly what a cold-start
 * check needs.
 */
export function latestManifestUrl({ serverUrl, owner, repo, name = 'version.json' }) {
  const base = String(serverUrl ?? '').replace(/\/+$/, '');
  return `${base}/${owner}/${repo}/releases/latest/download/${encodeURIComponent(name)}`;
}

/** Parse `owner/repo` out of the flag or GITHUB_REPOSITORY. */
export function resolveRepo(raw) {
  const value = String(raw ?? '').trim();
  const m = /^([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/.exec(value);
  if (!m) {
    throw new Error(
      `expected a repository as "owner/repo", got "${value}" — pass --repo or set GITHUB_REPOSITORY`,
    );
  }
  return { owner: m[1], repo: m[2] };
}

// -------------------------------------------------------------------- http

function requireEnv(name) {
  const v = process.env[name];
  if (!v || !v.trim()) throw new Error(`missing ${name}`);
  return v.trim();
}

async function gh(url, { method = 'GET', body, headers = {} } = {}) {
  const token = requireEnv('GITHUB_TOKEN');
  const init = {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      accept: 'application/vnd.github+json',
      'x-github-api-version': '2022-11-28',
      ...headers,
    },
  };
  if (body !== undefined) {
    init.headers['content-type'] =
      headers['content-type'] ?? 'application/json; charset=utf-8';
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }

  const res = await fetch(url, init);
  const text = await res.text();
  if (!res.ok) {
    // Never echo the token, but do echo what GitHub said — it is usually the
    // whole answer (403 rate limit, 404 missing repo scope, 422 bad name).
    throw new Error(`${method} ${url} -> HTTP ${res.status}: ${text.slice(0, 300)}`);
  }
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

// ------------------------------------------------------------------ publish

export async function publish(options = {}) {
  const notes = options.notes ?? notesOverride;
  const isDryRun = options.dryRun ?? dryRun;

  const release = readRelease();
  if (!release) {
    throw new Error(
      'no release is published yet — run scripts/publish-apk.mjs first',
    );
  }
  const apk = apkPath(release.file);
  if (!apk) throw new Error(`manifest names a missing APK: ${release.file}`);

  const { owner, repo } = resolveRepo(
    options.repo ?? repoArg ?? process.env.GITHUB_REPOSITORY,
  );
  const api = (process.env.GITHUB_API_URL ?? 'https://api.github.com').replace(/\/+$/, '');
  const uploads =
    (process.env.GITHUB_UPLOADS_URL ?? 'https://uploads.github.com').replace(/\/+$/, '');
  const serverUrl = process.env.GITHUB_SERVER_URL ?? 'https://github.com';

  const tag = releaseTag(release.version_name);
  const apkName = release.file;
  const manifestName = 'version.json';

  const apkUrl = assetDownloadUrl({ serverUrl, owner, repo, tag, name: apkName });
  const manifest = buildManifest(release, apkUrl, notes);
  const manifestJson = JSON.stringify(manifest, null, 2);

  const bodyText = [
    notes ?? release.notes ?? '',
    '',
    `version_code: ${manifest.version_code}`,
    `sha256: \`${manifest.sha256}\``,
    '',
    'Install or update by installing the APK attached below.',
  ].join('\n');

  if (isDryRun) {
    return {
      dry_run: true,
      tag,
      repo: `${owner}/${repo}`,
      apk: { name: apkName, url: apkUrl, size: manifest.size },
      manifest_url: assetDownloadUrl({
        serverUrl, owner, repo, tag, name: manifestName,
      }),
      // What a device should actually be pointed at.
      stable_url: latestManifestUrl({ serverUrl, owner, repo, name: manifestName }),
      manifest,
    };
  }

  // Find an existing release for this tag so re-publishing the same version
  // (after fixing a bad build, say) replaces assets instead of 422ing.
  const existing = await gh(
    `${api}/repos/${owner}/${repo}/releases/tags/${encodeURIComponent(tag)}`,
  ).catch((e) => {
    if (String(e.message).includes('HTTP 404')) return null;
    throw e;
  });

  let releaseId;
  let htmlUrl;
  if (existing) {
    releaseId = existing.id;
    htmlUrl = existing.html_url;
    await gh(`${api}/repos/${owner}/${repo}/releases/${releaseId}`, {
      method: 'PATCH',
      body: { tag_name: tag, name: tag, body: bodyText },
    });
    // Replace both assets so the manifest can never outlive its APK.
    const assets = await gh(`${api}/repos/${owner}/${repo}/releases/${releaseId}/assets`);
    for (const a of assets ?? []) {
      if (a.name === apkName || a.name === manifestName) {
        await gh(`${api}/repos/${owner}/${repo}/releases/assets/${a.id}`, {
          method: 'DELETE',
        });
      }
    }
  } else {
    const created = await gh(`${api}/repos/${owner}/${repo}/releases`, {
      method: 'POST',
      body: { tag_name: tag, name: tag, body: bodyText, draft: false, prerelease: false },
    });
    releaseId = created.id;
    htmlUrl = created.html_url;
  }

  const uploadAsset = async (name, data, contentType) => {
    const url = `${uploads}/repos/${owner}/${repo}/releases/${releaseId}/assets?name=${encodeURIComponent(name)}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${requireEnv('GITHUB_TOKEN')}`,
        accept: 'application/vnd.github+json',
        'content-type': contentType,
        'content-length': String(data.length),
      },
      body: data,
    });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`upload ${name} -> HTTP ${res.status}: ${text.slice(0, 300)}`);
    }
    return res.json();
  };

  const apkBytes = fs.readFileSync(apk);
  const apkAsset = await uploadAsset(
    apkName,
    apkBytes,
    'application/vnd.android.package-archive',
  );
  await uploadAsset(manifestName, Buffer.from(manifestJson, 'utf8'), 'application/json');

  return {
    tag,
    repo: `${owner}/${repo}`,
    release_url: htmlUrl,
    apk_url: apkAsset.browser_download_url ?? apkUrl,
    manifest_url: assetDownloadUrl({ serverUrl, owner, repo, tag, name: manifestName }),
    stable_url: latestManifestUrl({ serverUrl, owner, repo, name: manifestName }),
    manifest,
    bytes_uploaded: apkBytes.length,
  };
}

// ---------------------------------------------------------------------- cli

async function main() {
  try {
    const result = await publish();
    if (result.dry_run) {
      console.log(`dry run — nothing uploaded for ${result.tag} on ${result.repo}`);
      console.log(`  apk      ${result.apk.url} (${result.apk.size} bytes)`);
      console.log(`  manifest ${result.manifest_url}`);
      console.log(JSON.stringify(result.manifest, null, 2));
      return;
    }
    console.log(`published ${result.tag} to ${result.repo}`);
    console.log(`  release  ${result.release_url}`);
    console.log(`  apk      ${result.apk_url} (${result.bytes_uploaded} bytes)`);
    console.log(`  sha256   ${result.manifest.sha256}`);
    console.log('');
    console.log('Set this on devices once (⋮ menu → Update source):');
    console.log(`  ${result.stable_url}`);
    console.log('  (stable — it always resolves to the newest release, so it never');
    console.log('   needs changing again)');
    console.log('');
    console.log(`  per-release copy: ${result.manifest_url}`);
  } catch (e) {
    console.error(e.message ?? e);
    process.exit(1);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
