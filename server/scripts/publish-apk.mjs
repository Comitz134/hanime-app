#!/usr/bin/env node
// Publish an APK as the update source for installed apps.
//
//   node scripts/publish-apk.mjs ../android/app/build/outputs/apk/release/app-release.apk
//   node scripts/publish-apk.mjs path/to/app.apk --notes "Playlists tab, faster search"
//   node scripts/publish-apk.mjs path/to/app.apk --dry-run
//
// The version code is read from the APK with aapt2, never from an argument.
// That is the whole point: the number the updater compares must come from the
// binary it is about to install, or an app can be told to update to itself.
//
// Requires aapt2 on PATH or in the toolchain documented in
// README-ANDROID-BUILD.md. Set AAPT2 to point at it explicitly.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const APK_DIR = process.env.APK_DIR ?? path.resolve(HERE, '../data/apk');

const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const val = (f, d) => {
  const i = argv.indexOf(f);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};

// The positional argument, skipping any value that belongs to a flag.
const flagValues = new Set([val('--notes', null)].filter(Boolean));
const apkArg = argv.find((a) => !a.startsWith('--') && !flagValues.has(a));
const notes = val('--notes', '');
const dryRun = has('--dry-run');
const force = has('--force');

if (!apkArg) {
  console.error('usage: publish-apk.mjs <path-to.apk> [--notes "..."] [--dry-run] [--force]');
  process.exit(2);
}

const apk = path.resolve(apkArg);
if (!fs.existsSync(apk)) {
  console.error(`no such APK: ${apk}`);
  process.exit(2);
}

// --------------------------------------------------------------------- aapt2

function findAapt2() {
  if (process.env.AAPT2) return process.env.AAPT2;
  const candidates = [
    'aapt2',
    'aapt2.exe',
    path.join(process.env.ANDROID_HOME ?? '', 'build-tools', process.env.BUILD_TOOLS ?? '34.0.0', 'aapt2.exe'),
    'F:/atc/android-sdk/build-tools/34.0.0/aapt2.exe',
  ];
  for (const c of candidates) {
    if (!c) continue;
    try {
      execFileSync(c, ['version'], { stdio: 'pipe' });
      return c;
    } catch {
      /* try the next */
    }
  }
  return null;
}

const aapt2 = findAapt2();
if (!aapt2) {
  console.error('aapt2 not found. Set AAPT2=/path/to/aapt2 or add it to PATH.');
  process.exit(2);
}

// apksigner lives beside aapt2 in build-tools, so whatever aapt2 worked is the
// directory to look in. Node on Windows will not resolve an MSYS path like
// /f/atc/..., so the resolved path is used verbatim.
function findApksigner() {
  if (process.env.APKSIGNER) return process.env.APKSIGNER;
  const dir = path.dirname(aapt2);
  for (const name of ['apksigner.bat', 'apksigner']) {
    const p = path.join(dir, name);
    if (fs.existsSync(p)) return p;
  }
  return null;
}

let badging;
try {
  badging = execFileSync(aapt2, ['dump', 'badging', apk], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
} catch (e) {
  console.error(`aapt2 could not read ${apk}: ${e.message}`);
  process.exit(1);
}

const packageLine = /^package: (.*)$/m.exec(badging)?.[1] ?? '';
const attr = (name) => new RegExp(`${name}='([^']*)'`).exec(packageLine)?.[1] ?? null;

const versionCode = Number(attr('versionCode'));
const versionName = attr('versionName');
const applicationId = attr('name');

if (!Number.isInteger(versionCode) || versionCode <= 0) {
  console.error(`could not read a versionCode from ${apk}`);
  process.exit(1);
}

// ---------------------------------------------------------------- provenance

const bytes = fs.readFileSync(apk);
const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');

// Refuse to publish an unsigned APK: it would install on nobody's device, and
// the failure would surface as a confusing "app not installed" on the phone.
let signature = null;
let signatureError = null;
const apksigner = findApksigner();

/**
 * apksigner.bat is a wrapper around `java -jar lib/apksigner.jar`. Invoking the
 * jar directly avoids shelling out to a .bat, which Node refuses to spawn
 * without a shell on modern Windows (the EINVAL you get otherwise is opaque).
 */
function apksignerCommand() {
  if (apksigner && apksigner.endsWith('.jar')) return { cmd: javaExe(), pre: ['-jar', apksigner] };
  const jar = apksigner ? path.join(path.dirname(apksigner), 'lib', 'apksigner.jar') : null;
  if (jar && fs.existsSync(jar)) {
    const java = javaExe();
    if (java) return { cmd: java, pre: ['-jar', jar] };
  }
  return apksigner ? { cmd: apksigner, pre: [], shell: true } : null;
}

function javaExe() {
  const home = process.env.JAVA_HOME;
  if (home) {
    const candidate = path.join(home, 'bin', 'java.exe');
    if (fs.existsSync(candidate)) return candidate;
  }
  return 'java';
}

const signerCmd = apksignerCommand();
if (!signerCmd) {
  signatureError = 'apksigner not found beside aapt2; set APKSIGNER';
} else {
  try {
    const out = execFileSync(signerCmd.cmd, [...signerCmd.pre, 'verify', '--print-certs', apk], {
      encoding: 'utf8',
      stdio: 'pipe',
      env: process.env,
      shell: Boolean(signerCmd.shell),
    });
    const dn = /certificate DN: (.*)$/m.exec(out)?.[1]?.trim() ?? null;
    const digest = /certificate SHA-256 digest: (.*)$/m.exec(out)?.[1]?.trim() ?? null;
    signature = dn ? { signer_dn: dn, cert_sha256: digest } : null;
    if (!signature) signatureError = 'apksigner reported no signer';
  } catch (e) {
    // Keep the real reason: "not signed" and "could not run apksigner" are
    // different problems and must not look the same.
    signature = null;
    signatureError = (e.stderr ? String(e.stderr).trim() : null) || e.message;
  }
}
if (!signature && !has('--allow-unsigned')) {
  console.error('the APK does not verify as signed — refusing to publish.');
  if (signatureError) console.error(`reason: ${signatureError}`);
  console.error('(an unsigned APK installs on no device; pass --allow-unsigned only to test the plumbing)');
  process.exit(1);
}

// ------------------------------------------------------------------- manifest

fs.mkdirSync(APK_DIR, { recursive: true });
const manifestFile = path.join(APK_DIR, 'release.json');

const existing = fs.existsSync(manifestFile)
  ? JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
  : null;

if (existing && existing.version_code === versionCode && !force) {
  console.log(`version ${versionCode} (${versionName}) is already published — pass --force to republish`);
  process.exit(0);
}

// Only move forward. An older APK published over a newer manifest would make
// every installed app see a "newer" build that is actually behind.
if (existing && versionCode < existing.version_code && !force) {
  console.error(`refusing to publish ${versionCode}: ${existing.version_code} is already published.`);
  console.error('installed apps would be offered an older build. Pass --force if that is really what you want.');
  process.exit(1);
}

const targetName = `${applicationId}-${versionCode}.apk`;
const target = path.join(APK_DIR, targetName);

const manifest = {
  application_id: applicationId,
  version_code: versionCode,
  version_name: versionName,
  file: targetName,
  size: bytes.length,
  sha256,
  // A republish keeps its own notes; a new version carries whatever was passed.
  notes: notes || (existing?.version_code === versionCode ? existing.notes ?? '' : ''),
  published_at: new Date().toISOString(),
  signature,
};

if (dryRun) {
  console.log('dry run — nothing written');
  console.log(JSON.stringify(manifest, null, 2));
  process.exit(0);
}

fs.copyFileSync(apk, target);
fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2) + '\n');

// Drop the previous build. Keeping every version around grows without bound and
// nothing links to them: the manifest names exactly one file.
if (existing?.file && existing.file !== targetName) {
  const old = path.join(APK_DIR, existing.file);
  if (fs.existsSync(old)) fs.rmSync(old);
}

console.log(`published ${applicationId} ${versionName} (versionCode ${versionCode})`);
console.log(`  file    ${target}  (${(bytes.length / 1024).toFixed(0)} KB)`);
console.log(`  sha256  ${sha256}`);
console.log(`  signed  ${signature ? signature.signer_dn : 'NOT SIGNED'}`);
console.log(`  serving GET /api/app/version -> version_code ${versionCode}`);
