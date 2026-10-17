#!/usr/bin/env node
// A stand-in for GitHub's release API and its asset downloads, on loopback.
//
//   node tools/update-fixture.mjs --zip ../server/data/desktop/SEKAI-Windows-x64-1.0.1.zip \
//        --version 1.0.1 --tag v1.0.1
//
// Point a running copy of the app at it with `SEKAI_UPDATE_API=http://127.0.0.1:8790`
// and it answers what an update check asks for: the releases list, and the bytes of
// the asset that list names.
//
// It exists because a packaged window has nowhere to print. The launcher is a GUI
// executable with no console, so "did the installed app actually look for an update,
// and what did it get back" cannot be read off the screen it draws — it can only be
// read off the machine it asked. Every request is therefore logged, and the log is
// the evidence.
//
//   --zip <path>        the asset to serve (required)
//   --version <name>    the version the release claims (default 1.0.1)
//   --tag <tag>         the release tag (default v<version>)
//   --code <n>          version_code, for a client that reads it (default 2)
//   --port <n>          loopback port (default 8790)
//   --notes <text>      release notes
//   --prerelease        mark the release a prerelease (default: yes)
//   --stable            mark it a normal release instead
//   --owner/--repo      the repository the check will name
//   --other-release     also list a phone-only release above this one, as the
//                       shared repository really does

import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';

const argv = process.argv.slice(2);
const has = (flag) => argv.includes(flag);
const val = (flag, fallback) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const zipPath = val('--zip', null);
if (!zipPath) {
  console.error('update-fixture: --zip <path> is required');
  process.exit(2);
}
const zip = path.resolve(zipPath);
if (!fs.existsSync(zip)) {
  console.error(`update-fixture: no such file: ${zip}`);
  process.exit(2);
}

const version = val('--version', '1.0.1');
const tag = val('--tag', `v${version}`);
const code = Number.parseInt(val('--code', '2'), 10);
const port = Number.parseInt(val('--port', '8790'), 10);
const owner = val('--owner', 'Comitz134');
const repo = val('--repo', 'hanime-app');
const prerelease = !has('--stable');
const notes = val('--notes', `Fixture release ${version}.`);
const includePhoneRelease = has('--other-release');

const zipBytes = fs.readFileSync(zip);
const assetName = path.basename(zip);
const sha256 = crypto.createHash('sha256').update(zipBytes).digest('hex');
const base = () => `http://127.0.0.1:${port}`;

const log = (line) => console.log(`[${new Date().toISOString()}] ${line}`);

const releases = () => {
  const desktop = {
    tag_name: tag,
    name: `SEKAI ${version}`,
    body: notes,
    draft: false,
    prerelease,
    html_url: `${base()}/releases/tag/${tag}`,
    assets: [
      {
        name: assetName,
        browser_download_url: `${base()}/dl/${encodeURIComponent(assetName)}`,
        size: zipBytes.length,
        content_type: 'application/zip',
      },
      {
        name: 'desktop.json',
        browser_download_url: `${base()}/dl/desktop.json`,
        size: 0,
        content_type: 'application/json',
      },
    ],
  };
  if (!includePhoneRelease) return [desktop];
  // The phone's release, newest-first above the desktop one: the same repository
  // publishes both, and a check that only ever looked at the newest release would
  // find nothing here.
  return [
    {
      tag_name: 'v9.9.9',
      name: 'Phone release',
      body: '',
      draft: false,
      prerelease: false,
      html_url: `${base()}/releases/tag/v9.9.9`,
      assets: [
        { name: 'sekai-9.9.9.apk', browser_download_url: `${base()}/dl/sekai-9.9.9.apk`, size: 1, content_type: 'application/vnd.android.package-archive' },
        { name: 'version.json', browser_download_url: `${base()}/dl/version.json`, size: 1, content_type: 'application/json' },
      ],
    },
    desktop,
  ];
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, base());
  log(`${req.method} ${url.pathname}${url.search}`);

  if (url.pathname.startsWith('/repos/')) {
    const body = JSON.stringify(releases());
    res.writeHead(200, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(body),
    });
    res.end(body);
    return;
  }

  if (url.pathname.startsWith('/dl/')) {
    const name = decodeURIComponent(url.pathname.slice('/dl/'.length));
    if (name === assetName) {
      res.writeHead(200, {
        'content-type': 'application/zip',
        'content-length': zipBytes.length,
        'accept-ranges': 'bytes',
      });
      if (req.method === 'HEAD') return res.end();
      res.end(zipBytes);
      return;
    }
    if (name === 'desktop.json') {
      const body = JSON.stringify({ ok: true, version_name: version, version_code: code, sha256 }, null, 2);
      res.writeHead(200, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
      return res.end(body);
    }
    log(`  (no such asset: ${name})`);
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end('{"error":"not_found"}');
    return;
  }

  if (url.pathname.startsWith('/releases/tag/')) {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('fixture release page\n');
    return;
  }

  res.writeHead(404, { 'content-type': 'application/json' });
  res.end('{"error":"not_found"}');
});

server.listen(port, '127.0.0.1', () => {
  log(`serving ${assetName} (${zipBytes.length} bytes, sha256 ${sha256})`);
  log(`release ${tag} — ${prerelease ? 'prerelease' : 'stable'}, version_code ${code}`);
  log(`point the app at it with SEKAI_UPDATE_API=${base()}`);
});
