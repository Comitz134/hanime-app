#!/usr/bin/env node
// Grow the public playlist index from the shell.
//
//   node scripts/crawl-playlists.mjs                 # next 60 unscanned videos
//   node scripts/crawl-playlists.mjs --count 300     # next 300
//   node scripts/crawl-playlists.mjs --all           # every unscanned video (~3400)
//   node scripts/crawl-playlists.mjs --slug a,b,c    # specific video slugs
//   node scripts/crawl-playlists.mjs --status        # print the index summary
//
// Safe to stop with Ctrl-C: the index is written after each phase, and
// videos_scanned is a permanent skip list, so a rerun continues rather than
// starting over.

import { crawl, loadIndex, saveIndex, indexStats } from '../src/playlist-crawl.mjs';
import { getCatalog } from '../src/hanime.mjs';

const argv = process.argv.slice(2);
const has = (flag) => argv.includes(flag);
const val = (flag, fallback) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

if (has('--status')) {
  console.log(JSON.stringify(indexStats(), null, 2));
  process.exit(0);
}

const index = loadIndex();
const seen = new Set(index.videos_scanned);

// `--refetch` clears the fetched marker on playlists that predate a field this
// crawler now stores (studio and tag rollups, the playlist's own poster), so a
// rerun backfills them instead of leaving older rows thinner than new ones.
if (has('--refetch')) {
  let cleared = 0;
  for (const rec of Object.values(index.playlists)) {
    if (rec.fetched_at && !rec.tags) { delete rec.fetched_at; delete rec.items_file; cleared++; }
  }
  saveIndex(index);
  console.log(`refetch: cleared ${cleared} playlist(s) missing rollup data`);
}

let seeds = [];
if (val('--slug', null)) {
  seeds = val('--slug').split(',').map((s) => s.trim()).filter(Boolean);
} else {
  const cat = await getCatalog();
  const pending = cat.items.filter((v) => !seen.has(v.slug)).map((v) => v.slug);
  const want = has('--all') ? pending.length : Number(val('--count', 60));
  seeds = pending.slice(0, want);
}

// No new video pages is not the end of the work: discovered playlists still
// need their own pages fetched before their entries are searchable. Only stop
// when there is neither a video page nor a playlist page outstanding.
const pendingPlaylists = Object.values(index.playlists).filter((p) => !p.fetched_at && !p.items_file).length;
const pendingChannels = Object.keys(index.channels).filter((s) => !index.channels[s].fetched_at).length;

if (!seeds.length && !pendingPlaylists && !pendingChannels) {
  console.log('nothing left to scan — every video page, playlist and channel is indexed.');
  console.log(JSON.stringify(indexStats(), null, 2));
  process.exit(0);
}

console.log(
  `video pages: ${seeds.length} | playlist pages outstanding: ${pendingPlaylists}`
  + ` | channel pages outstanding: ${pendingChannels}`);
const started = Date.now();

const out = await crawl({
  videoSlugs: seeds,
  maxPlaylists: Number(val('--max-playlists', Math.max(seeds.length * 4, 200))),
  maxChannels: Number(val('--max-channels', 40)),
  expandChannels: !has('--no-channels'),
  onProgress: (m) => console.log(`  ${m}`),
});

console.log(`\nfinished in ${((Date.now() - started) / 1000).toFixed(1)}s`);
console.log(JSON.stringify(out.stats, null, 2));
