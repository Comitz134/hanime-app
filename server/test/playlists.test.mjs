// Playlist normalization is the one part of this proxy that cannot be tested
// against the live site: every playlist endpoint answers 404 without an account
// and keep-alive needs a real session cookie. So it is tested against synthetic
// payloads that cover each shape the site's own client is capable of emitting,
// with an injected catalog so nothing touches the network.

import test from 'node:test';
import assert from 'node:assert/strict';

import { buildIndex, searchPlaylists, allItems } from '../src/playlists.mjs';

// --------------------------------------------------------------------------
// fake catalog
// --------------------------------------------------------------------------

const CATALOG_ITEMS = [
  { id: 5, slug: 'yabai-fukushuu-yami-site-2', name: 'Yabai! Fukushuu Yami Site 2', brand: 'Suzuki Mirano', tags: ['bondage', 'horror'], views: 3301925, cover_url: 'https://cdn/5-cover.png', poster_url: 'https://cdn/5-poster.jpg', released_at: '2014-04-25T00:00:05.000Z' },
  { id: 11, slug: 'kanojo-saimin-1', name: 'Kanojo Saimin 1', brand: 'T-Rex', tags: ['mind control'], views: 2800000, cover_url: 'https://cdn/11-cover.png', poster_url: null, released_at: '2026-01-01T00:00:00.000Z' },
  { id: 42, slug: 'deco-x-deco-1', name: 'Deco x Deco 1', brand: 'Seven', tags: ['comedy'], views: 4200000, cover_url: 'https://cdn/42-cover.png', poster_url: null, released_at: '2026-02-01T00:00:00.000Z' },
];

function fakeCatalog() {
  return {
    items: CATALOG_ITEMS,
    byId: new Map(CATALOG_ITEMS.map((v) => [v.id, v])),
    bySlug: new Map(CATALOG_ITEMS.map((v) => [v.slug, v])),
  };
}

// --------------------------------------------------------------------------
// shape handling
// --------------------------------------------------------------------------

test('a declared playlist with no rows still appears, at zero items', async () => {
  const { playlists, stats } = await buildIndex(
    { playlists: [{ id: 1, slug: 'watch-later', title: 'Watch Later', is_mutable: false }] },
    fakeCatalog(),
  );
  assert.equal(playlists.length, 1);
  assert.equal(playlists[0].slug, 'watch-later');
  assert.equal(playlists[0].items.length, 0);
  assert.equal(stats.declared, 1);
  assert.equal(stats.orphan_rows, 0);
});

test('membership rows attribute by playlist_id', async () => {
  const { playlists } = await buildIndex(
    {
      playlists: [{ id: 7, slug: 'my-faves', title: 'My Faves' }],
      playlist_hentai_videos: [
        { playlist_id: 7, hentai_video_id: 5 },
        { playlist_id: 7, hentai_video_id: 11 },
      ],
    },
    fakeCatalog(),
  );
  const p = playlists.find((x) => x.slug === 'my-faves');
  assert.equal(p.items.length, 2);
  assert.equal(p.resolved, 2);
  assert.equal(p.items[0].title, 'Yabai! Fukushuu Yami Site 2');
});

test('membership rows attribute through a nested playlist object', async () => {
  const { playlists } = await buildIndex(
    {
      playlists: [{ id: 9, slug: 'weekend', title: 'Weekend' }],
      playlist_hentai_videos: [{ playlist: { id: 9, slug: 'weekend' }, hentai_video: { id: 42 } }],
    },
    fakeCatalog(),
  );
  const p = playlists.find((x) => x.slug === 'weekend');
  assert.equal(p.items.length, 1);
  assert.equal(p.items[0].slug, 'deco-x-deco-1');
});

test('membership rows attribute by playlist slug when no id is present', async () => {
  const { playlists } = await buildIndex(
    {
      playlists: [{ slug: 'weekend', title: 'Weekend' }],
      playlist_hentai_videos: [{ playlist_slug: 'weekend', hentai_video_id: 42 }],
    },
    fakeCatalog(),
  );
  assert.equal(playlists.find((x) => x.slug === 'weekend').items.length, 1);
});

test('unattributable rows become a synthetic collection, never disappear', async () => {
  const { playlists, stats } = await buildIndex(
    {
      playlists: [{ id: 3, slug: 'named', title: 'Named' }],
      like_dislike_playlist_hentai_videos: [{ hentai_video_id: 5 }],
      watch_later_playlist_hentai_videos: [{ hentai_video_id: 11 }],
    },
    fakeCatalog(),
  );
  const synthetic = playlists.filter((p) => p.synthetic);
  assert.equal(synthetic.length, 2, 'one bucket per collection');
  assert.equal(stats.orphan_rows, 2);
  assert.equal(stats.total_playlists, 3);
  assert.ok(synthetic.some((p) => p.title.toLowerCase().includes('like')));
});

test('rows missing from the catalog are kept and flagged, not dropped', async () => {
  const { playlists } = await buildIndex(
    {
      playlists: [{ id: 4, slug: 'mixed', title: 'Mixed' }],
      playlist_hentai_videos: [
        { playlist_id: 4, hentai_video_id: 5 },        // in catalog
        { playlist_id: 4, hentai_video_id: 999999 },   // not in catalog
      ],
    },
    fakeCatalog(),
  );
  const p = playlists.find((x) => x.slug === 'mixed');
  assert.equal(p.items.length, 2);
  assert.equal(p.resolved, 1);
  assert.equal(p.unresolved, 1);
  assert.equal(p.items[1].resolved, false);
});

test('a playlist inherits a cover from its first resolvable item', async () => {
  const { playlists } = await buildIndex(
    {
      playlists: [{ id: 6, slug: 'nocover', title: 'No Cover' }],
      playlist_hentai_videos: [{ playlist_id: 6, hentai_video_id: 11 }],
    },
    fakeCatalog(),
  );
  assert.equal(playlists[0].cover, 'https://cdn/11-cover.png');
});

test('an empty payload yields an empty index rather than throwing', async () => {
  const { playlists, stats } = await buildIndex({}, fakeCatalog());
  assert.deepEqual(playlists, []);
  assert.equal(stats.total_playlists, 0);
});

test('a null payload yields an empty index rather than throwing', async () => {
  const { playlists } = await buildIndex(null, fakeCatalog());
  assert.deepEqual(playlists, []);
});

test('a catalog failure degrades to unresolved rows, not a crash', async () => {
  const empty = { items: [], byId: new Map(), bySlug: new Map() };
  const { playlists } = await buildIndex(
    { playlists: [{ id: 1, slug: 'p', title: 'P' }], playlist_hentai_videos: [{ playlist_id: 1, hentai_video_id: 5 }] },
    empty,
  );
  assert.equal(playlists[0].items.length, 1);
  assert.equal(playlists[0].items[0].resolved, false);
});

// --------------------------------------------------------------------------
// search
// --------------------------------------------------------------------------

async function index() {
  return buildIndex(
    {
      playlists: [
        { id: 1, slug: 'horror-night', title: 'Horror Night', is_mutable: true },
        { id: 2, slug: 't-rex-picks', title: 'T-Rex Picks', is_mutable: true },
        { id: 3, slug: 'empty', title: 'Empty Shelf', is_mutable: true },
      ],
      playlist_hentai_videos: [
        { playlist_id: 1, hentai_video_id: 5 },
        { playlist_id: 2, hentai_video_id: 11 },
        { playlist_id: 2, hentai_video_id: 42 },
      ],
    },
    fakeCatalog(),
  );
}

test('an empty query returns every playlist untouched', async () => {
  const { playlists, matched } = searchPlaylists(await index(), '');
  assert.equal(playlists.length, 3);
  assert.equal(matched, null);
  assert.equal(playlists[0].match, undefined);
});

test('a title match returns the whole playlist, not a filtered subset', async () => {
  const { playlists } = searchPlaylists(await index(), 'horror');
  assert.equal(playlists.length, 1);
  assert.equal(playlists[0].slug, 'horror-night');
  assert.equal(playlists[0].match, 'title');
  assert.equal(playlists[0].items.length, 1);
});

test('a title search is case-insensitive and matches mid-word', async () => {
  const { playlists } = searchPlaylists(await index(), 'T-REX');
  assert.equal(playlists.length, 1);
  assert.equal(playlists[0].slug, 't-rex-picks');
  assert.equal(playlists[0].items.length, 2);
});

test('a content match narrows to the hits and marks them', async () => {
  const { playlists } = searchPlaylists(await index(), 'kanojo');
  assert.equal(playlists.length, 1);
  assert.equal(playlists[0].slug, 't-rex-picks');
  assert.equal(playlists[0].match, 'item');
  assert.equal(playlists[0].items.length, 1, 'only the matching item is shown');
  assert.equal(playlists[0].items[0].slug, 'kanojo-saimin-1');
});

test('content search reaches studio names and tags', async () => {
  const byBrand = searchPlaylists(await index(), 'suzuki');
  assert.equal(byBrand.playlists.length, 1);
  assert.equal(byBrand.playlists[0].slug, 'horror-night');

  const byTag = searchPlaylists(await index(), 'comedy');
  assert.equal(byTag.playlists.length, 1);
  assert.equal(byTag.playlists[0].items[0].slug, 'deco-x-deco-1');
});

test('title matches sort ahead of content matches', async () => {
  const idx = await buildIndex(
    {
      playlists: [
        { id: 1, slug: 'a', title: 'Alpha' },
        { id: 2, slug: 'b', title: 'Beta' },
      ],
      playlist_hentai_videos: [
        { playlist_id: 1, hentai_video_id: 11 }, // Kanojo Saimin
        { playlist_id: 2, hentai_video_id: 5 },  // Yabai...
      ],
    },
    fakeCatalog(),
  );
  const { playlists } = searchPlaylists(idx, 'kanojo');
  assert.equal(playlists.length, 1);
  assert.equal(playlists[0].slug, 'a');
});

test('a query that matches nothing returns an empty list, not everything', async () => {
  const { playlists } = searchPlaylists(await index(), 'zzzz-no-such-thing');
  assert.deepEqual(playlists, []);
});

test('an empty playlist is findable by title but has no items', async () => {
  const { playlists } = searchPlaylists(await index(), 'empty shelf');
  assert.equal(playlists.length, 1);
  assert.equal(playlists[0].items.length, 0);
});

// --------------------------------------------------------------------------
// flattening
// --------------------------------------------------------------------------

test('allItems dedupes across playlists and records every membership', async () => {
  const idx = await buildIndex(
    {
      playlists: [
        { id: 1, slug: 'one', title: 'One' },
        { id: 2, slug: 'two', title: 'Two' },
      ],
      playlist_hentai_videos: [
        { playlist_id: 1, hentai_video_id: 5 },
        { playlist_id: 2, hentai_video_id: 5 }, // same video, second playlist
        { playlist_id: 2, hentai_video_id: 42 },
      ],
    },
    fakeCatalog(),
  );
  const flat = allItems(idx);
  assert.equal(flat.length, 2, 'video 5 appears once');
  const five = flat.find((i) => i.video_id === 5);
  assert.deepEqual(five.in_playlists.map((p) => p.slug).sort(), ['one', 'two']);
});
