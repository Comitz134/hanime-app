#!/usr/bin/env node
// Build and publish the desktop app, as the window's own update source.
//
//   node scripts/publish-desktop.mjs --pack              build, zip, upload
//   node scripts/publish-desktop.mjs --pack --dry-run     build and zip only
//   node scripts/publish-desktop.mjs                      upload the last zip
//   node scripts/publish-desktop.mjs --pack --notes "..."
//
// Why this exists: the window checks GitHub — not this machine — for a newer
// build, so a release has to exist somewhere a reader can reach. The window is
// built from `shell/` (a NuvioDesktop fork) and what it installs is the
// jpackage *app-image*: a folder, not something an installer keeps in step.
// So the artifact is a zip of that folder, with a Node runtime beside the
// launcher, and updating is replacing the folder.
//
// Node travels inside the zip because the window is a front end to `server/`,
// which is Node: without it the app would open for a reader who has never
// installed Node and then report that it could not start its own server. The
// server's *source* is already inside the app's jar; only the runtime is added.
//
// The release is published as a PRERELEASE on purpose. This repository is also
// where the phone app is published, and the phone reads its manifest from
// `releases/latest` — a normal release here would become "latest" and hand the
// phone a manifest with no APK in it. A prerelease keeps `/releases/latest`
// pointing at the phone's release while the window, which lists prereleases,
// still finds its own.
//
// Required environment (only to upload):
//   GITHUB_TOKEN       a personal access token with `contents: write`
//   GITHUB_REPOSITORY  `owner/repo` (or pass --repo)
//
// Optional: GITHUB_API_URL, GITHUB_UPLOADS_URL, GITHUB_SERVER_URL,
//           JAVA_HOME (to build), HANIME_NODE (a node.exe to bundle).

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { resolveRepo, releaseTag } from './publish-github.mjs';
// The token lives in the checkout's `.env`, which the server loads for itself —
// importing it means a publish works from a plain shell instead of only from one
// where the file happened to be sourced.
import '../src/env.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');            // hanime-app
const SHELL = path.join(ROOT, 'shell');
const TREE = path.join(SHELL, '.upstream');          // the build tree overrides land in
const OUT_DIR = path.join(ROOT, 'server/data/desktop');

const argv = process.argv.slice(2);
const has = (flag) => argv.includes(flag);
const val = (flag, fallback) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const pack = has('--pack');
const dryRun = has('--dry-run');
const skipBuild = has('--no-build');
const notes = val('--notes', null);
const repoArg = val('--repo', null);
const nodeOverride = val('--node', null) ?? process.env.HANIME_NODE ?? null;

/** The platform this release is for. One key, because one host builds it. */
export const PLATFORM = 'windows-x64';

// ------------------------------------------------------------------ pure bits

/**
 * The version the app itself carries, read from the file the build reads.
 *
 * Not from the built app's own metadata: jpackage rewrites the version into a
 * form Windows installers accept (a leading zero becomes one, and a prerelease
 * tag is dropped), so `.jpackage.xml` says `1.1.29` where the app, and therefore
 * the update check, says `0.1.29-alpha`. Publishing the jpackage form would put
 * a version on the release that the app never compares itself against — and a
 * check that compares `1.1.29` with `0.1.29-alpha` decides the release is older
 * than the build it is running.
 */
export function readDesktopVersion(text) {
  const props = new Map();
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const at = trimmed.indexOf('=');
    if (at <= 0) continue;
    props.set(trimmed.slice(0, at).trim(), trimmed.slice(at + 1).trim());
  }
  const name = props.get('VERSION_NAME');
  const code = Number.parseInt(props.get('VERSION_CODE') ?? '', 10);
  if (!name) throw new Error('DesktopVersion.properties has no VERSION_NAME');
  if (!Number.isInteger(code) || code <= 0) {
    throw new Error(`DesktopVersion.properties has no usable VERSION_CODE (got "${props.get('VERSION_CODE')}")`);
  }
  return { versionName: name, versionCode: code };
}

/** The zip's own name on the release: platform and version, nothing else. */
export function desktopAssetName(versionName, platform = PLATFORM) {
  const [os, arch] = platform.split('-');
  const pretty = os === 'windows' ? 'Windows' : os === 'macos' ? 'macOS' : 'Linux';
  return `SEKAI-${pretty}-${arch}-${versionName}.zip`;
}

/** What the release page and any other client can read. */
export function desktopManifest({ versionName, versionCode, asset, size, sha256, notes: releaseNotes }) {
  return {
    ok: true,
    configured: true,
    platform: PLATFORM,
    version_name: versionName,
    version_code: versionCode,
    asset,
    size,
    sha256,
    notes: releaseNotes ?? null,
    published_at: new Date().toISOString(),
  };
}

// ---------------------------------------------------------------- the zip

const crcTable = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let c = -1;
  for (let i = 0; i < buffer.length; i += 1) c = crcTable[(c ^ buffer[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

/** DOS date/time, for an entry's timestamp. */
function dosDateTime(date) {
  const time = ((date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() / 2)) & 0xffff;
  const day = (((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()) & 0xffff;
  return { time, day };
}

/**
 * Every file under `dir`, as zip entry names rooted at `rootName`.
 *
 * Sorted, so the same tree always produces the same archive: two builds of one
 * release are then byte-identical, which is what makes a published sha256 worth
 * anything to whoever downloaded it.
 */
export function zipEntries(dir, rootName) {
  const entries = [];
  const walk = (current, prefix) => {
    const children = fs.readdirSync(current, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1));
    for (const child of children) {
      const full = path.join(current, child.name);
      const name = `${prefix}/${child.name}`;
      if (child.isDirectory()) {
        walk(full, name);
      } else if (child.isFile()) {
        entries.push({ name, full });
      }
    }
  };
  walk(dir, rootName);
  return entries;
}

/**
 * Write those entries as a zip. No dependency, and no external tool: Windows —
 * the only host that can build this app's release — has no `zip` on it, and a
 * release that can only be produced on some machines is a release that stops
 * being produced.
 *
 * Deflate rather than store: the tree is 250 MB of a JRE, jars and a Node
 * runtime, every one of which compresses well, and the download size is what a
 * reader actually feels.
 */
export function writeZip(outFile, entries, { level = 9 } = {}) {
  const chunks = [];
  const central = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = Buffer.from(entry.name, 'utf8');
    const data = fs.readFileSync(entry.full);
    const deflated = zlib.deflateRawSync(data, { level });
    // A stored entry only when deflating made it bigger, which happens for
    // already-compressed bytes; the method byte has to match what was written.
    const useDeflate = deflated.length < data.length;
    const payload = useDeflate ? deflated : data;
    const method = useDeflate ? 8 : 0;
    const { time, day } = dosDateTime(fs.statSync(entry.full).mtime);
    const crc = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);          // version needed
    local.writeUInt16LE(0x0800, 6);      // UTF-8 names
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(day, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(payload.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);

    chunks.push(local, nameBytes, payload);

    const header = Buffer.alloc(46);
    header.writeUInt32LE(0x02014b50, 0);
    header.writeUInt16LE(20, 4);         // version made by
    header.writeUInt16LE(20, 6);         // version needed
    header.writeUInt16LE(0x0800, 8);
    header.writeUInt16LE(method, 10);
    header.writeUInt16LE(time, 12);
    header.writeUInt16LE(day, 14);
    header.writeUInt32LE(crc, 16);
    header.writeUInt32LE(payload.length, 20);
    header.writeUInt32LE(data.length, 24);
    header.writeUInt16LE(nameBytes.length, 28);
    header.writeUInt16LE(0, 30);         // extra
    header.writeUInt16LE(0, 32);         // comment
    header.writeUInt16LE(0, 34);         // disk
    header.writeUInt16LE(0, 36);         // internal attrs
    header.writeUInt32LE(0, 38);         // external attrs
    header.writeUInt32LE(offset, 42);    // local header offset
    central.push(header, nameBytes);

    offset += local.length + nameBytes.length + payload.length;
  }

  const centralBuffer = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBuffer.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);

  fs.writeFileSync(outFile, Buffer.concat([...chunks, centralBuffer, end]));
  return fs.statSync(outFile).size;
}

/** sha256 of a file, as the app and the release page both state it. */
export function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

// ---------------------------------------------------------------- the build

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} exited ${result.status}`);
  }
}

/** java, from JAVA_HOME when it is set (the build needs JDK 17). */
function javaCommand() {
  const home = process.env.JAVA_HOME;
  if (home) {
    const exe = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(exe)) return exe;
  }
  return 'java';
}

/**
 * The wrapper, run through java rather than through `gradlew`.
 *
 * `gradlew.bat` cannot be started directly on Windows — it is a batch file, so it
 * needs a shell, and a shell brings its own argument splitting: this repository's
 * checkout path contains a space, and `cmd.exe` will not even find the file when
 * the working directory is on another drive. The wrapper's jar entry point is the
 * same launcher without either problem, on every platform.
 */
function runGradle(args) {
  run(
    javaCommand(),
    ['-classpath', path.join(TREE, 'gradle/wrapper/gradle-wrapper.jar'),
     'org.gradle.wrapper.GradleWrapperMain', ...args],
    { cwd: TREE },
  );
}

/** The Node runtime that will travel inside the release. */
function nodeRuntime() {
  const candidate = nodeOverride ?? process.execPath;
  if (!fs.existsSync(candidate)) throw new Error(`no Node runtime at ${candidate}`);
  return candidate;
}

/**
 * The version the built app actually carries, read from the generated source
 * the Kotlin compile takes its constants from.
 *
 * This is not the same question as what the properties file says. Gradle reads
 * `DesktopVersion.properties` at *configuration* time, and this build turns the
 * configuration cache on — so a cached configuration hands the generator the
 * number from the previous release, and the app inside a perfectly good-looking
 * v1.0.1 zip declares itself 1.0.0. It then sees v1.0.1 as newer on every
 * launch: an update that downloads itself in a loop and never stops offering.
 * So the published version is settled against the compiled one, not assumed.
 */
function compiledDesktopVersion() {
  const file = path.join(
    TREE, 'composeApp/build/generated/runtime-config/kotlin/com/nuvio/app/core/build/AppVersionConfig.kt',
  );
  if (!fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8');
  const name = /DESKTOP_VERSION_NAME\s*=\s*"([^"]*)"/.exec(text)?.[1] ?? null;
  const code = Number.parseInt(/DESKTOP_VERSION_CODE\s*=\s*(\d+)/.exec(text)?.[1] ?? '', 10);
  return name ? { name, code: Number.isInteger(code) ? code : null } : null;
}

/** Where the build put the app-image. */
function builtAppImage() {
  const base = path.join(TREE, 'composeApp/build/compose/binaries/main/app');
  if (!fs.existsSync(base)) {
    throw new Error(`no app-image under ${base} — the build did not reach createDistributable`);
  }
  const found = fs.readdirSync(base).filter((name) => name === 'SEKAI' || name === 'SEKAI.app');
  if (found.length === 0) throw new Error(`no SEKAI app-image under ${base}`);
  return path.join(base, found[0]);
}

export function packDesktop({ withBuild = true } = {}) {
  if (!fs.existsSync(path.join(TREE, '.git'))) {
    throw new Error(`no build tree at ${TREE} — run shell/sync.sh first`);
  }

  // The same two steps shell/install-desktop.sh performs, in the same order, so
  // a release is built from exactly what an install of this tree would run.
  //
  // Copied here rather than by shelling out to `cp`: a Windows checkout can sit
  // under a path with spaces in it, and a shell that splits on them turns this
  // into a copy into a file called `scrape`.
  console.log(`publish-desktop: applying overrides to ${path.relative(ROOT, TREE)}`);
  fs.cpSync(path.join(SHELL, 'overrides'), TREE, { recursive: true });

  if (withBuild) {
    console.log('publish-desktop: :composeApp:createDistributable (this takes a few minutes)');
    // No configuration cache on purpose: the version is read while Gradle
    // configures, so reusing a cached configuration would stamp the previous
    // release's number into this one — see compiledDesktopVersion above. The
    // cost is a few seconds of configuration on a build that takes minutes.
    runGradle([':composeApp:createDistributable', '--console=plain', '--no-configuration-cache']);
  }

  const appImage = builtAppImage();
  const version = readDesktopVersion(
    fs.readFileSync(path.join(TREE, 'composeApp/Configuration/DesktopVersion.properties'), 'utf8'),
  );

  // A release whose app disagrees with its own tag is worse than no release:
  // the app compares against the number it carries, so it would offer itself
  // this same update forever. So the number is settled against what the app
  // actually carries, and a disagreement stops the pack.
  //
  // Checked whenever the build tree has a stamped version at all — including
  // `--no-build`, which republishes the last zip: that is exactly the case where
  // the thing on disk and the version being published can drift apart.
  const compiled = compiledDesktopVersion();
  if (compiled && compiled.name !== version.versionName) {
    throw new Error(
      `the built app calls itself ${compiled.name} but this release is ${version.versionName}: ` +
      'bump DesktopVersion.properties and rebuild (the build stamps the version it was configured with)',
    );
  }
  if (compiled) console.log(`publish-desktop: the built app carries ${compiled.name}`);

  const assetName = desktopAssetName(version.versionName);
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const outFile = path.join(OUT_DIR, assetName);

  // The Node runtime travels beside the launcher — `node/node.exe`, where
  // HanimeServer looks first, so a reader who has never installed Node gets a
  // working app from an unzip. It is added to the archive rather than copied
  // into the build output: the build directory is what `install-desktop.sh`
  // installs from on the machine that built it, that machine already has Node,
  // and it should hold what Gradle produced and nothing else.
  const nodeExe = nodeRuntime();
  const entries = zipEntries(appImage, 'SEKAI');
  entries.push({ name: 'SEKAI/node/node.exe', full: nodeExe });
  // Sorted by name so the archive is a function of its contents, not of the
  // order a directory happened to be walked in.
  entries.sort((a, b) => (a.name < b.name ? -1 : 1));
  console.log(`publish-desktop: bundling node/node.exe — ${fs.statSync(nodeExe).size} bytes`);
  console.log(`publish-desktop: zipping ${entries.length} files from ${path.relative(ROOT, appImage)}`);
  const size = writeZip(outFile, entries);
  const digest = sha256File(outFile);

  const manifest = desktopManifest({
    versionName: version.versionName,
    versionCode: version.versionCode,
    asset: assetName,
    size,
    sha256: digest,
    notes,
  });
  const manifestFile = path.join(OUT_DIR, 'desktop.json');
  fs.writeFileSync(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`);

  console.log(`publish-desktop: ${path.relative(ROOT, outFile)} — ${size} bytes`);
  console.log(`publish-desktop: sha256 ${digest}`);
  console.log(`publish-desktop: version ${version.versionName} (${version.versionCode})`);

  return { ...version, assetName, file: outFile, size, sha256: digest, manifest, manifestFile };
}

// ------------------------------------------------------------------ upload

async function gh(url, { method = 'GET', body } = {}) {
  const token = requireToken();
  const res = await fetch(url, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      accept: 'application/vnd.github+json',
      'x-github-api-version': '2022-11-28',
      ...(body === undefined ? {} : { 'content-type': 'application/json; charset=utf-8' }),
    },
    ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url} -> HTTP ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

function requireToken() {
  const token = (process.env.GITHUB_TOKEN ?? '').trim();
  if (!token) throw new Error('missing GITHUB_TOKEN — needed to upload a release');
  return token;
}

async function uploadAsset(uploads, owner, repo, releaseId, name, data, contentType) {
  const url = `${uploads}/repos/${owner}/${repo}/releases/${releaseId}/assets?name=${encodeURIComponent(name)}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${requireToken()}`,
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
}

/**
 * Publish the built zip as its own release, tagged with the app's version.
 *
 * Re-publishing the same version replaces its assets rather than failing: a
 * release is rebuilt whenever the tree moves, and a tag that already exists is
 * a normal thing to meet.
 */
export async function publishDesktop(build, options = {}) {
  const { owner, repo } = resolveRepo(options.repo ?? repoArg ?? process.env.GITHUB_REPOSITORY);
  const api = (process.env.GITHUB_API_URL ?? 'https://api.github.com').replace(/\/+$/, '');
  const uploads = (process.env.GITHUB_UPLOADS_URL ?? 'https://uploads.github.com').replace(/\/+$/, '');
  const serverUrl = (process.env.GITHUB_SERVER_URL ?? 'https://github.com').replace(/\/+$/, '');

  const tag = releaseTag(build.versionName);
  const body = [
    build.manifest.notes ?? `SEKAI ${build.versionName} for Windows.`,
    '',
    `version_code: ${build.versionCode}`,
    `platform: ${PLATFORM}`,
    `sha256: \`${build.sha256}\``,
    '',
    '## How to install',
    '',
    '1. Download the zip below.',
    `2. Unzip it anywhere — Desktop, Documents, a USB stick.`,
    '3. Run `SEKAI.exe`. It carries its own Java runtime and its own Node, so',
    '   nothing has to be installed first, and no terminal window is needed.',
    '',
    'Windows may warn about an unknown publisher: the build is not code-signed,',
    'so "More info" → "Run anyway" is expected the first time.',
    '',
    'Once it is running, the app updates itself — this page is for the first',
    'download. Keep the folder together: an update replaces it in place.',
  ].join('\n');

  const existing = await gh(`${api}/repos/${owner}/${repo}/releases/tags/${encodeURIComponent(tag)}`)
    .catch((e) => {
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
      body: { tag_name: tag, name: tag, body, prerelease: true },
    });
    const assets = await gh(`${api}/repos/${owner}/${repo}/releases/${releaseId}/assets`);
    for (const asset of assets ?? []) {
      if (asset.name === build.assetName || asset.name === 'desktop.json') {
        await gh(`${api}/repos/${owner}/${repo}/releases/assets/${asset.id}`, { method: 'DELETE' });
      }
    }
  } else {
    const created = await gh(`${api}/repos/${owner}/${repo}/releases`, {
      method: 'POST',
      body: { tag_name: tag, name: tag, body, draft: false, prerelease: true },
    });
    releaseId = created.id;
    htmlUrl = created.html_url;
  }

  const zipBytes = fs.readFileSync(build.file);
  const zipAsset = await uploadAsset(
    uploads, owner, repo, releaseId, build.assetName, zipBytes, 'application/zip',
  );
  await uploadAsset(
    uploads, owner, repo, releaseId, 'desktop.json',
    Buffer.from(`${JSON.stringify(build.manifest, null, 2)}\n`, 'utf8'), 'application/json',
  );

  return {
    tag,
    repo: `${owner}/${repo}`,
    release_url: htmlUrl,
    asset_url: zipAsset.browser_download_url,
    manifest_url: `${serverUrl}/${owner}/${repo}/releases/download/${encodeURIComponent(tag)}/desktop.json`,
    bytes_uploaded: zipBytes.length,
    manifest: build.manifest,
  };
}

// ---------------------------------------------------------------------- cli

async function main() {
  const built = pack ? packDesktop({ withBuild: !skipBuild }) : readLastPack();

  if (dryRun) {
    console.log('');
    console.log('dry run — nothing uploaded');
    console.log(`  file      ${path.relative(ROOT, built.file)}`);
    console.log(`  bytes     ${built.size}`);
    console.log(`  sha256    ${built.sha256}`);
    console.log(`  version   ${built.versionName} (${built.versionCode})`);
    console.log(`  tag       ${releaseTag(built.versionName)} (prerelease)`);
    console.log('');
    console.log(JSON.stringify(built.manifest, null, 2));
    return;
  }

  const result = await publishDesktop(built);
  console.log('');
  console.log(`published ${result.tag} to ${result.repo} (prerelease)`);
  console.log(`  release  ${result.release_url}`);
  console.log(`  asset    ${result.asset_url} (${result.bytes_uploaded} bytes)`);
  console.log(`  manifest ${result.manifest_url}`);
}

/** The zip from the last --pack, for publishing without rebuilding. */
function readLastPack() {
  const manifestFile = path.join(OUT_DIR, 'desktop.json');
  if (!fs.existsSync(manifestFile)) {
    throw new Error(`nothing packed yet — run with --pack first (no ${path.relative(ROOT, manifestFile)})`);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  const file = path.join(OUT_DIR, manifest.asset);
  if (!fs.existsSync(file)) throw new Error(`the manifest names a missing zip: ${manifest.asset}`);
  return {
    versionName: manifest.version_name,
    versionCode: manifest.version_code,
    assetName: manifest.asset,
    file,
    size: fs.statSync(file).size,
    sha256: sha256File(file),
    manifest,
    manifestFile,
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main().catch((e) => {
    console.error(e.message ?? e);
    process.exit(1);
  });
}
