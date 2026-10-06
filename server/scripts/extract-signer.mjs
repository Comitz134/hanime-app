#!/usr/bin/env node
// Refreshes vendor/ from the live site.
//
// Run this when upstream starts rejecting signatures (the bundle hash changes
// whenever they rebuild the client):
//
//   node scripts/extract-signer.mjs
//
// It scrapes the homepage for AppConfig + the vendor script URL, downloads the
// bundle, pulls the base64 wasm out of `findWasmBinary()`, and writes both into
// vendor/. Then re-run the server.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const VENDOR = path.resolve(HERE, '../vendor');

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const home = await fetch('https://hanime.tv/', { headers: { 'user-agent': UA } });
if (!home.ok) throw new Error(`homepage fetch failed: ${home.status}`);
const html = await home.text();

const appConfig = /window\.AppConfig=(\{.*?\});/.exec(html);
if (appConfig) {
  console.log('AppConfig:', JSON.stringify(JSON.parse(appConfig[1]), null, 2));
} else {
  console.warn('no AppConfig found in homepage — layout may have changed');
}

const vendorMatch = /https:\/\/hanime-cdn\.com\/js\/vendor\.[a-f0-9]+\.min\.js/.exec(html);
if (!vendorMatch) throw new Error('vendor bundle URL not found in homepage');
const vendorUrl = vendorMatch[0];
console.log('vendor bundle:', vendorUrl);

const bundle = await fetch(vendorUrl, { headers: { 'user-agent': UA } });
if (!bundle.ok) throw new Error(`vendor fetch failed: ${bundle.status}`);
const source = await bundle.text();

if (!source.includes('window.ssignature')) {
  throw new Error('bundle no longer contains the ssignature ASM_CONST — scheme changed, re-analyse by hand');
}

const b64 = /base64Decode\("([A-Za-z0-9+/=]{100,})"\)/.exec(source);
if (!b64) throw new Error('embedded wasm not found — bundle layout changed');

fs.mkdirSync(VENDOR, { recursive: true });
fs.writeFileSync(path.join(VENDOR, 'htv-signer.js'), source);
fs.writeFileSync(path.join(VENDOR, 'htv-signer.wasm'), Buffer.from(b64[1], 'base64'));

const wasmBytes = Buffer.from(b64[1], 'base64');
console.log(`wrote vendor/htv-signer.js (${source.length} bytes)`);
console.log(`wrote vendor/htv-signer.wasm (${wasmBytes.length} bytes)`);

// Sanity: the derived wasm must expose the export the glue calls on_window_event with.
const mod = new WebAssembly.Module(wasmBytes);
const exports_ = WebAssembly.Module.exports(mod).map((e) => e.name);
console.log('wasm exports:', exports_.join(', '));
if (!exports_.includes('B')) console.warn('export "B" (on_window_event) is missing — glue mapping changed');
