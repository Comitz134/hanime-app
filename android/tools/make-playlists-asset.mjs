#!/usr/bin/env node
// Build the public-playlists dataset that ships inside the APK.
//
//   node android/tools/make-playlists-asset.mjs
//
// The full crawl index is 82 MB across ~11k playlists and ~10k item files, and
// it only exists because a long-running crawler built it server-side. That is
// not something a phone can reproduce, so a trimmed subset ships with the app
// and is searched in-process by PublicPlaylists.java.
//
// What goes in: the biggest public playlists, with their entries attached, so
// the screen browses, filters by owner/tag, and opens onto a real grid rather
// than an empty one. Regenerate after re-running the crawler.
//
// Bounds are deliberate: this file is on every cold start of the app.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.resolve(HERE, '../../server');
const INDEX = path.join(SERVER, 'data/playlist-index.json');
const ITEMS_DIR = path.join(SERVER, 'data/playlist-items');
const TARGET = path.resolve(HERE, '../app/src/main/assets/playlists.json');

/** How many of the largest playlists to ship. */
const PLAYLIST_LIMIT = 250;
/** Entries attached per playlist; enough to render a full grid. */
const ITEM_LIMIT = 40;
/** The precomputed search blob, trimmed — it is only used for substring hits. */
const ITEM_TEXT_CAP = 1500;

function loadItems(slug) {
  const file = path.join(ITEMS_DIR, `${slug}.json`);
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    const arr = Array.isArray(parsed) ? parsed : (parsed.items ?? []);
    return arr.slice(0, ITEM_LIMIT).map((i) => ({
      slug: i.slug,
      name: i.name,
      brand: i.brand ?? null,
      cover_url: i.cover_url ?? null,
      poster_url: i.poster_url ?? null,
      views: i.views ?? 0,
      duration_in_ms: i.duration_in_ms ?? 0,
      released_at: i.released_at ?? null,
    }));
  } catch {
    return [];
  }
}

function main() {
  if (!fs.existsSync(INDEX)) {
    console.error(`no crawl index at ${INDEX}; run the crawler first`);
    process.exit(2);
  }

  const index = JSON.parse(fs.readFileSync(INDEX, 'utf8'));
  const usable = Object.values(index.playlists ?? {})
    // Same gate the server's search uses.
    .filter((p) => p.fetched_at || p.title)
    .filter((p) => (p.visibility ?? 'public') === 'public')
    .sort((a, b) => (b.item_count ?? b.video_count ?? 0) - (a.item_count ?? a.video_count ?? 0))
    .slice(0, PLAYLIST_LIMIT);

  const playlists = usable.map((p) => ({
    slug: p.slug,
    title: p.title ?? '',
    cover_url: p.cover_url ?? '',
    video_count: p.video_count ?? p.count ?? 0,
    item_count: p.item_count ?? p.video_count ?? p.count ?? 0,
    views: p.views ?? 0,
    age_days: p.age_days ?? 0,
    owner_name: p.owner_name ?? '',
    owner_avatar_url: p.owner_avatar_url ?? '',
    owner_channel_slug: p.owner_channel_slug ?? '',
    visibility: p.visibility ?? 'public',
    total_duration_ms: p.total_duration_ms ?? 0,
    brands: p.brands ?? [],
    tags: p.tags ?? [],
    item_text: String(p.item_text ?? '').slice(0, ITEM_TEXT_CAP),
    items: loadItems(p.slug),
  }));

  const payload = {
    generated_at: new Date().toISOString(),
    source: 'server/data/playlist-index.json',
    playlists,
  };

  fs.mkdirSync(path.dirname(TARGET), { recursive: true });
  const json = JSON.stringify(payload);
  fs.writeFileSync(TARGET, json);

  const owners = new Set(playlists.map((p) => p.owner_channel_slug).filter(Boolean));
  const entries = playlists.reduce((n, p) => n + p.items.length, 0);
  console.log(`playlists asset: ${path.relative(process.cwd(), TARGET)}`);
  console.log(`  playlists ${playlists.length}  owners ${owners.size}  entries ${entries}`);
  console.log(`  size      ${(json.length / 1024 / 1024).toFixed(2)} MB`);
}

main();
