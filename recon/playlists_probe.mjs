#!/usr/bin/env node
// Playlist recon.
//
// Earlier probing of `/playlists/<slug>` used guessed slugs (liked-videos,
// watch-later, history) which 404, and `/playlists` which also 404s — so the
// conclusion "playlists are not public" was wrong. They are public; the slugs
// are opaque 20-character ids and there is simply no index page.
//
// This script pulls a real playlist page, extracts the SSR payload, and lists
// every endpoint the playlist chunk calls so the read path is separated from
// the write path.

import fs from 'node:fs';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const slug = process.argv[2] ?? 'e6c0fz4fzjpzl46u9hpc';
const url = `https://hanime.tv/playlists/${slug}`;

const res = await fetch(url, { headers: { 'user-agent': UA } });
console.log(`${url} -> ${res.status}`);

const html = await res.text();
fs.writeFileSync(`/tmp/playlist-${slug}.html`, html);
console.log(`html: ${html.length} bytes`);

// The payload rides on an astro island's `props` attribute, HTML-escaped.
const island = /astro-island[^>]*component-url="([^"]+)"[^>]*props="([^"]*)"/g;
let m;
while ((m = island.exec(html))) {
  const props = m[2].replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');
  if (!props.includes('playlist')) continue;
  console.log(`\nisland chunk: ${m[1]}`);
  // props is a JSON *string* in the attribute; it has to be parsed before the
  // [kind, value] decoding means anything.
  const decoded = decodeAstro(JSON.parse(props));
  // Shape is { playlist_slug, initial_data: {...}, is_viewer_owner, ... } — the
  // playlist fields live one level down, under initial_data.
  report(decoded.initial_data ?? decoded);
  break;
}

/**
 * Astro's island props encode values as [kind, value] pairs, where kind 0 is a
 * scalar and kind 1 is an array. Undo that so the payload is readable.
 */
function decodeAstro(value) {
  if (Array.isArray(value) && value.length === 2 && typeof value[0] === 'number') {
    const [kind, inner] = value;
    if (kind === 0) return decodeAstro(inner);
    if (kind === 1) return inner.map(decodeAstro);
    return inner;
  }
  if (Array.isArray(value)) return value.map(decodeAstro);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = decodeAstro(v);
    return out;
  }
  return value;
}

function report(data) {
  const p = data.playlist ?? {};
  const owner = data.playlist_owner ?? {};
  const videos = data.playlist_hentai_videos ?? [];

  console.log('\n--- playlist');
  console.log({
    id: p.id,
    slug: p.slug,
    title: p.title,
    visibility: p.visibility,
    count: p.count,
    total_duration: p.total_duration,
    custom_poster_url: p.custom_poster_url ?? null,
  });
  console.log('\n--- owner');
  console.log({
    id: owner.id,
    name: owner.name,
    slug: owner.slug,
    avatar_url: owner.avatar_url,
    channel_slug: owner.user_channel?.slug,
    channel_title: owner.user_channel?.title,
  });

  console.log(`\n--- items: ${videos.length}`);
  console.log('is_truncated:', data.is_truncated ?? null, '| list_size:', data.list_size ?? null);
  for (const v of videos.slice(0, 3)) {
    console.log(' ', {
      id: v.id,
      phv_id: v.phv_id,
      slug: v.slug,
      name: v.name,
      brand: v.brand,
      sequence: v.sequence,
      duration_in_ms: v.duration_in_ms,
    });
  }

  const keys = new Set();
  for (const v of videos) for (const k of Object.keys(v)) keys.add(k);
  console.log('\nitem fields:', [...keys].join(', '));
}

// Now find every endpoint the playlist chunk touches.
const chunkUrl = /astro-island[^>]*component-url="(\/_astro\/[A-Za-z0-9_.-]+\.js)"/g;
let chunk;
const seen = new Set();
while ((chunk = chunkUrl.exec(html))) seen.add(chunk[1]);

console.log('\n--- endpoints referenced by the page chunks');
for (const path of seen) {
  const src = await (await fetch(`https://hanime.tv${path}`, { headers: { 'user-agent': UA } })).text();
  if (!src.includes('playlist')) continue;
  const calls = new Set();
  for (const c of src.matchAll(/\$(get|post|put|delete)\(\s*`([^`]+)`/g)) calls.add(`${c[1].toUpperCase().padEnd(7)} ${c[2]}`);
  for (const c of src.matchAll(/\/api\/v\d+\/[a-z_]+(?:\/\$\{[^}]+\}[a-z_]*)?/g)) calls.add(`raw     ${c[0]}`);
  if (calls.size) {
    console.log(`\n${path}`);
    for (const c of [...calls].sort()) console.log('  ', c);
  }
}
