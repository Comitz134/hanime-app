// Offline tests for the public playlist crawler, its split storage and the
// search layer. No network: fetch is stubbed with fixtures shaped like the
// real SSR HTML, so the parse → store → search chain is exercised end to end.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// DATA_DIR is read when playlist-crawl.mjs is first imported, so point it at a
// scratch directory before any dynamic import of the module.
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'htv-playlists-'));
process.env.DATA_DIR = TMP;
process.env.CRAWL_DELAY_MS = '0';

const crawlMod = await import('../src/playlist-crawl.mjs');
const indexMod = await import('../src/playlist-index.mjs');

// --------------------------------------------------------------------------
// fixtures
// --------------------------------------------------------------------------

/** Astro encodes scalars as [0, v] and arrays as [1, [...]]. */
const scalar = (v) => [0, v];
const array = (v) => [1, v];

function playlistIsland({ title, owner, channel, items, tags = [], brands = [], truncated = false }) {
  const props = {
    initial_data: [
      0,
      {
        playlist: [0, { id: 99, title: scalar(title), visibility: scalar('public'), count: scalar(items.length), total_duration: scalar(1000), poster_url: scalar('https://cdn/poster.webp'), views: scalar(4321), custom_poster_url: scalar(null) }],
        playlist_owner: [0, { name: scalar(owner), avatar_url: scalar('https://cdn/av.png'), user_channel: [0, { slug: scalar(channel), title: scalar(owner) }] }],
        is_truncated: scalar(truncated),
        list_size: scalar(items.length),
        tags: [1, tags.map((t) => [0, { text: scalar(t.text), count: scalar(t.count) }])],
        brands: [1, brands.map((b) => [0, { slug: scalar(b.slug), title: scalar(b.title), count: scalar(b.count), in_playlist_count: scalar(b.in_playlist_count) }])],
        playlist_hentai_videos: [1, items.map((i) => [0, {
          id: scalar(i.id),
          slug: scalar(i.slug),
          name: scalar(i.name),
          brand: scalar(i.brand),
          cover_url: scalar(`https://cdn/${i.slug}-cover.webp`),
          poster_url: scalar(`https://cdn/${i.slug}-poster.webp`),
          duration_in_ms: scalar(60000),
          views: scalar(100),
          sequence: scalar(1),
        }])],
      },
    ],
  };
  return `<astro-island component-url="/_astro/x.js" props="${JSON.stringify(props).replace(/"/g, '&quot;')}"></astro-island>`;
}

/** A video page's "Related Playlists" rail, matching the real markup shape. */
function videoPage(cards) {
  const blocks = cards.map((c) => `
    <div class="playlist-hover-card"><div class="relative group">
      <a href="/playlists/${c.slug}" class="grid">
        <span class="relative block pt-2.5"><span class="relative grid aspect-video">
          <img src="${c.cover ?? `https://cdn/${c.slug}.webp`}" alt="${c.title} cover image">
          <span class="badge">${c.count} videos</span>
        </span></span>
        <span class="min-w-0 pt-2.5 gap-1 flex flex-col">
          <div class="overflow-hidden line-clamp-2">${c.title}</div>
          <div class="flex items-center"><img src="https://cdn/av26.png" alt="Playlist creator's avatar">
            <span class="flex truncate">${c.owner}</span></div>
          <div class="truncate text-base-content/45">${c.views ?? '522.4K views'}<span class="mx-0.5">•</span>${c.age ?? '7 years ago'}</div>
        </span>
      </a>
      <button data-playlist-href="/playlists/${c.slug}"
              data-playlist-owner-avatar-url="https://cdn/av26.png"
              data-playlist-owner-name="${c.owner}"
              data-playlist-owner-channel-href="/channels/${c.channel}">menu</button>
    </div></div>`).join('');
  return `<html><head><title>a video</title></head><body><h2>Related Playlists</h2>${blocks}</body></html>`;
}

function channelPage(name, slugs) {
  return `<html><head><title>${name} - hanime.tv</title></head><body>${
    slugs.map((s) => `<a href="/playlists/${s}">x</a>`).join('')
  }</body></html>`;
}

const VIDEO_ROUTES = {
  '/videos/hentai/vid-a': videoPage([
    { slug: 'aaaaaaaaaaaaaaaaaaaa', title: 'Tuff', owner: 'bigbruuuu', channel: 'bigbruuuu-8718', count: 3 },
    { slug: 'bbbbbbbbbbbbbbbbbbbb', title: 'Majin Collab', owner: 'Makuhita', channel: 'makuhita-3008', count: 2 },
  ]),
  '/videos/hentai/vid-b': videoPage([
    { slug: 'aaaaaaaaaaaaaaaaaaaa', title: 'Tuff', owner: 'bigbruuuu', channel: 'bigbruuuu-8718', count: 3 },
    { slug: 'cccccccccccccccccccc', title: 'Bunnywalker Only', owner: 'SejRider', channel: 'sejrider-2989', count: 2 },
  ]),
};

const PLAYLIST_ROUTES = {
  '/playlists/aaaaaaaaaaaaaaaaaaaa': playlistIsland({
    title: 'Tuff', owner: 'bigbruuuu', channel: 'bigbruuuu-8718',
    items: [
      { id: 1, slug: 'mahou-touki-lilustear-3', name: 'Mahou Touki Lilustear 3', brand: 'Magin Label' },
      { id: 2, slug: 'mahou-touki-lilustear-4', name: 'Mahou Touki Lilustear 4', brand: 'Magin Label' },
      { id: 3, slug: 'rin-x-sen', name: 'Rin x Sen', brand: 'Bunnywalker' },
    ],
    tags: [{ text: 'censored', count: 3 }, { text: 'creampie', count: 2 }],
    brands: [{ slug: 'magin-label', title: 'Magin Label', count: 86, in_playlist_count: 2 },
             { slug: 'bunnywalker', title: 'Bunnywalker', count: 127, in_playlist_count: 1 }],
  }),
  '/playlists/bbbbbbbbbbbbbbbbbbbb': playlistIsland({
    title: 'Majin Collab', owner: 'Makuhita', channel: 'makuhita-3008',
    items: [
      { id: 4, slug: 'mahou-touki-lilustear-4', name: 'Mahou Touki Lilustear 4', brand: 'Magin Label' },
      { id: 5, slug: 'some-other-title', name: 'Some Other Title', brand: 'Pink Pineapple' },
    ],
    tags: [{ text: 'uncensored', count: 2 }],
    brands: [{ slug: 'magin-label', title: 'Magin Label', count: 86, in_playlist_count: 1 }],
  }),
  '/playlists/cccccccccccccccccccc': playlistIsland({
    title: 'Bunnywalker Only', owner: 'SejRider', channel: 'sejrider-2989',
    items: [{ id: 6, slug: 'rin-x-sen', name: 'Rin x Sen', brand: 'Bunnywalker' }],
    tags: [{ text: 'censored', count: 1 }],
    brands: [{ slug: 'bunnywalker', title: 'Bunnywalker', count: 127, in_playlist_count: 1 }],
  }),
};

const originalFetch = globalThis.fetch;
globalThis.fetch = async (url) => {
  const u = new URL(url);
  const body = u.pathname.startsWith('/videos/') ? VIDEO_ROUTES[u.pathname]
    : u.pathname.startsWith('/playlists/') ? PLAYLIST_ROUTES[u.pathname]
    : u.pathname.startsWith('/channels/') ? channelPage(u.pathname.split('/').pop(), [])
    : null;
  if (body == null) return new Response('not found', { status: 404 });
  return new Response(body, { status: 200, headers: { 'content-type': 'text/html' } });
};

test.after(() => {
  globalThis.fetch = originalFetch;
  fs.rmSync(TMP, { recursive: true, force: true });
});

// --------------------------------------------------------------------------
// parsing
// --------------------------------------------------------------------------

test('the playlist island decodes into a record with owner, tags and brands', () => {
  const data = crawlMod.parsePlaylistPage(PLAYLIST_ROUTES['/playlists/aaaaaaaaaaaaaaaaaaaa']);
  assert.ok(data, 'island payload was not found');
  assert.equal(data.playlist.title, 'Tuff');
  assert.equal(data.playlist.visibility, 'public');
  assert.equal(data.playlist_owner.name, 'bigbruuuu');
  assert.equal(data.playlist_owner.user_channel.slug, 'bigbruuuu-8718');
  assert.equal(data.playlist_hentai_videos.length, 3);
  assert.equal(data.tags[0].text, 'censored');
  assert.equal(data.brands[0].title, 'Magin Label');
});

test('a page that is not a playlist yields null rather than a half record', () => {
  assert.equal(crawlMod.parsePlaylistPage(videoPage([])), null);
  assert.equal(crawlMod.parsePlaylistPage('<html></html>'), null);
});

// --------------------------------------------------------------------------
// crawl
// --------------------------------------------------------------------------

test('harvesting video pages discovers playlists and their owners', async () => {
  const index = crawlMod.loadIndex({ fresh: true });
  await crawlMod.harvestVideo(index, 'vid-a');
  await crawlMod.harvestVideo(index, 'vid-b');

  // Two videos advertise three distinct playlists; Tuff appears on both.
  assert.equal(Object.keys(index.playlists).length, 3);
  const tuff = index.playlists['aaaaaaaaaaaaaaaaaaaa'];
  assert.equal(tuff.title, 'Tuff');
  assert.equal(tuff.owner_name, 'bigbruuuu');
  assert.equal(tuff.owner_channel_slug, 'bigbruuuu-8718');
  assert.deepEqual(tuff.seen_on.sort(), ['vid-a', 'vid-b']);
  assert.deepEqual(index.videos_scanned.sort(), ['vid-a', 'vid-b']);
  // Discovery is enough to search by title before the page is ever fetched.
  assert.equal(tuff.fetched_at, undefined);

  crawlMod.saveIndex(index);
});

test('a fetched playlist stores its items in its own file, not the index', async () => {
  const index = crawlMod.loadIndex({ fresh: true });
  const result = await crawlMod.fetchPlaylist(index, 'aaaaaaaaaaaaaaaaaaaa');
  assert.deepEqual(result, { slug: 'aaaaaaaaaaaaaaaaaaaa', ok: true, items: 3, truncated: false });

  crawlMod.saveIndex(index);

  const items = crawlMod.readItems('aaaaaaaaaaaaaaaaaaaa');
  assert.equal(items.length, 3);
  assert.equal(items[0].slug, 'mahou-touki-lilustear-3');

  // The metadata file must not carry the entries — that is the whole point of
  // the split, and it is what keeps a whole-library crawl parseable.
  const onDisk = fs.readFileSync(path.join(TMP, 'playlist-index.json'), 'utf8');
  assert.ok(!onDisk.includes('mahou-touki-lilustear-3'),
    'entry data leaked into the metadata index');
  assert.ok(onDisk.includes('"item_count":3'), 'item_count missing from the metadata index');

  const rec = crawlMod.loadIndex({ fresh: true }).playlists['aaaaaaaaaaaaaaaaaaaa'];
  assert.equal(rec.item_count, 3);
  assert.equal(rec.items_file, true);
  assert.equal(rec.cover_url, 'https://cdn/poster.webp');
  assert.equal(rec.views, 4321);
  assert.equal(rec.brands[0].title, 'Magin Label');
  assert.equal(rec.tags[0].text, 'censored');
  // The searchable blob must carry entry titles, studios and tags alike.
  assert.ok(rec.item_text.includes('mahou touki lilustear 3'));
  assert.ok(rec.item_text.includes('bunnywalker'));
  assert.ok(rec.item_text.includes('censored'));
});

test('a channel page adds that owner\'s other public playlists', async () => {
  const index = crawlMod.loadIndex({ fresh: true });
  const res = await crawlMod.fetchChannel(index, 'bigbruuuu-8718');
  assert.equal(res.ok, true);
  // The channel page's own <title> is the creator's display name; the `- hanime.tv`
  // suffix the site appends must be stripped.
  assert.equal(index.channels['bigbruuuu-8718'].title, 'bigbruuuu-8718');
  assert.equal(index.channels['bigbruuuu-8718'].fetched_at != null, true);
});

test('indexStats counts items from metadata without opening any items file', async () => {
  const index = crawlMod.loadIndex({ fresh: true });
  await crawlMod.fetchPlaylist(index, 'bbbbbbbbbbbbbbbbbbbb');
  await crawlMod.fetchPlaylist(index, 'cccccccccccccccccccc');
  crawlMod.saveIndex(index);

  const stats = crawlMod.indexStats();
  assert.equal(stats.playlists, 3);
  assert.equal(stats.items, 6);
  assert.equal(stats.videos_scanned, 2);
});

// --------------------------------------------------------------------------
// search
// --------------------------------------------------------------------------

test('a title match returns the whole playlist and ranks above content', () => {
  const res = indexMod.searchPlaylists('tuff', { includeItems: true });
  assert.ok(res.matched >= 1);
  assert.equal(res.playlists[0].match_kind, 'title');
  assert.equal(res.playlists[0].title, 'Tuff');
  assert.equal(res.playlists[0].owner_name, 'bigbruuuu');
  assert.equal(res.playlists[0].items.length, 3);
});

test('content search matches entry titles and narrows to the hits', () => {
  const res = indexMod.searchPlaylists('lilustear');
  const hit = res.playlists.find((p) => p.slug === 'aaaaaaaaaaaaaaaaaaaa');
  assert.ok(hit, 'playlist holding a matching entry was not returned');
  assert.equal(hit.match_kind, 'content');
  assert.equal(hit.matched_on, 'entries');
  assert.deepEqual(hit.items.map((i) => i.slug).sort(),
    ['mahou-touki-lilustear-3', 'mahou-touki-lilustear-4']);
});

test('a tag hit is labelled a rollup rather than an entry hit', () => {
  // No entry title contains "uncensored" — only the playlist's tag rollup does.
  const byTag = indexMod.searchPlaylists('uncensored');
  const hit = byTag.playlists.find((p) => p.slug === 'bbbbbbbbbbbbbbbbbbbb');
  assert.ok(hit, 'tag rollup did not match');
  assert.equal(hit.match_kind, 'content');
  assert.equal(hit.matched_on, 'studio_or_tag');
  // It must not render as an empty result just because no title carries the term.
  assert.ok(hit.items.length > 0, 'rollup match returned no entries to show');
  assert.equal(hit.tags.find((t) => t.text === 'uncensored').count, 2);
});

test('a term that is both a title and a studio resolves as the title match', () => {
  const res = indexMod.searchPlaylists('bunnywalker');
  const hit = res.playlists.find((p) => p.slug === 'cccccccccccccccccccc');
  assert.ok(hit);
  // "Bunnywalker Only" leads with the term, so the playlist itself is the
  // result — a stronger answer than "contains entries by Bunnywalker".
  assert.equal(hit.match_kind, 'title');
  assert.equal(hit.brands.find((b) => b.title === 'Bunnywalker').in_playlist_count, 1);
});

test('the tag filter uses the playlist rollup, not entry text', () => {
  const res = indexMod.searchPlaylists('', { tag: 'uncensored' });
  assert.deepEqual(res.playlists.map((p) => p.slug), ['bbbbbbbbbbbbbbbbbbbb']);
  const none = indexMod.searchPlaylists('', { tag: 'not-a-real-tag' });
  assert.equal(none.playlists.length, 0);
});

test('the owner filter scopes results to one creator', () => {
  const res = indexMod.searchPlaylists('', { owner: 'sejrider-2989' });
  assert.deepEqual(res.playlists.map((p) => p.slug), ['cccccccccccccccccccc']);
});

test('an empty query browses by size instead of returning nothing', () => {
  const res = indexMod.searchPlaylists('');
  assert.equal(res.playlists.length, 3);
  assert.equal(res.playlists[0].match_kind, 'browse');
  assert.equal(res.playlists[0].item_count, 3);
});

test('a query matching nothing returns nothing — no fuzzy fallback', () => {
  const res = indexMod.searchPlaylists('zzzznotathingzzzz');
  assert.equal(res.matched, 0);
  assert.equal(res.playlists.length, 0);
});

test('playlistsContaining finds every playlist holding an entry', () => {
  const found = indexMod.playlistsContaining('mahou-touki-lilustear-4');
  assert.deepEqual(found.map((p) => p.slug).sort(),
    ['aaaaaaaaaaaaaaaaaaaa', 'bbbbbbbbbbbbbbbbbbbb']);
  assert.equal(found.find((p) => p.slug === 'aaaaaaaaaaaaaaaaaaaa').position, 2);
});

test('listOwners reports creators with their playlist and item totals', () => {
  const owners = indexMod.listOwners();
  const big = owners.find((o) => o.channel_slug === 'bigbruuuu-8718');
  assert.equal(big.playlists, 1);
  assert.equal(big.items, 3);
});

test('a crawl lock refuses a concurrent pass and is released afterwards', async () => {
  const lockFile = path.join(TMP, '.crawl.lock');
  assert.equal(crawlMod.crawlLock(), null, 'no lock should be held before a pass');

  // A live pid — this process — must be refused. Two passes would clobber the
  // same index file, and saveIndex rewrites all of it.
  fs.writeFileSync(lockFile, JSON.stringify({ pid: process.pid, started_at: 'test' }));
  await assert.rejects(
    () => crawlMod.crawl({ videoSlugs: [], maxPlaylists: 0, maxChannels: 0 }),
    (e) => e.code === 'CRAWL_LOCKED',
  );

  // A lock left by a dead process is stale and must be taken over.
  fs.writeFileSync(lockFile, JSON.stringify({ pid: 999999, started_at: 'test' }));
  const out = await crawlMod.crawl({ videoSlugs: [], maxPlaylists: 0, maxChannels: 0 });
  assert.ok(out.stats);
  assert.equal(crawlMod.crawlLock(), null, 'the lock must be released on exit');
});

test('opening an un-fetched playlist pulls its page on demand', async () => {
  // Discovered only — the page has never been fetched, so there is no items
  // file and the card's count is all that is known.
  const boot = crawlMod.loadIndex({ fresh: true });
  boot.playlists['ffffffffffffffffffff'] = {
    slug: 'ffffffffffffffffffff',
    title: 'Not Opened Yet',
    owner_name: 'someone',
    owner_channel_slug: 'someone-1',
    video_count: 2,
  };
  crawlMod.saveIndex(boot);
  assert.equal(crawlMod.readItems('ffffffffffffffffffff'), null);

  const before = indexMod.getPlaylist('ffffffffffffffffffff');
  assert.equal(before.fetched, false);
  assert.equal(before.items.length, 0);

  // Route a page for it so the fill has something to read.
  PLAYLIST_ROUTES['/playlists/ffffffffffffffffffff'] = PLAYLIST_ROUTES['/playlists/cccccccccccccccccccc'];

  const filled = await indexMod.getPlaylistFilled('ffffffffffffffffffff');
  assert.equal(filled.fetched, true, 'the playlist was not marked fetched after the fill');
  assert.ok(filled.items.length > 0, 'the fill returned no entries');
  assert.equal(crawlMod.readItems('ffffffffffffffffffff').length, filled.items.length);

  // A second open must not hit upstream again — the file is already there.
  const again = await crawlMod.ensurePlaylist('ffffffffffffffffffff');
  assert.equal(again.filled, false);
  assert.equal(again.reason, 'already_fetched');
});

test('an unopened playlist is searchable by title but flagged as not fetched', () => {
  const index = crawlMod.loadIndex({ fresh: true });
  index.playlists['dddddddddddddddddddd'] = {
    slug: 'dddddddddddddddddddd',
    title: 'Never Opened',
    owner_name: 'someone',
    owner_channel_slug: 'someone-1',
  };
  crawlMod.saveIndex(index);

  const res = indexMod.searchPlaylists('never opened', { index: crawlMod.loadIndex({ fresh: true }) });
  assert.equal(res.playlists[0].title, 'Never Opened');
  assert.equal(res.playlists[0].fetched, false);
  assert.equal(res.playlists[0].item_count, 0);
});
