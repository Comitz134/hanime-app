// The account's side of this phone is two translations and two merges, and all
// four are places where being wrong is silent: a title favourited here that the
// window never sees, a position that comes back as 0% because someone assumed
// seconds, a title deleted on the desktop that lives on in this phone for ever.
// None of that shows up as an error — only as two clients that disagree.
//
// So the model is asserted directly, against the real module: it is pure
// (no storage, no network, no DOM), which is exactly why it can be. What is
// *not* here is the transport and the wiring — those are the account server's
// own contract, and `accounts/probe.mjs` drives the same payloads through the
// live project instead.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  accountIdFor,
  slugFromAccountId,
  millis,
  seconds,
  libraryPayload,
  historyPayload,
  progressPayload,
  reconcileFavorites,
  reconcileHistory,
  mergePositions,
  animeIdFor,
  showIdFor,
  animeKeyFromId,
  seriesKeyFromId,
  filmKeyFromId,
  progressKeyFor,
  activityProgressPayload,
  activityProgressKey,
  activityTitleGroups,
  activityEntryForTitle,
  reconcileActivity,
  hasRealPosition,
  STARTED_POSITION_MS,
  CONTENT_TYPE,
  ID_PREFIX,
  PROFILE_ID,
} from '../client/src/account-model.js';

/** A favourite as library.js files it on this device. */
const filed = (slug, at, extra = {}) => ({ slug, name: slug.toUpperCase(), cover: `c/${slug}.jpg`, brand: '', at, ...extra });

/** One of this phone's positions: seconds, and `at` in epoch milliseconds. */
const watched = (t, d, at) => ({ t, d, at });

/** A library row as the account returns it. */
const row = (slug, addedAt, extra = {}) => ({
  content_id: `${ID_PREFIX}${slug}`,
  content_type: CONTENT_TYPE,
  name: `${slug.toUpperCase()} from the window`,
  poster: `p/${slug}.jpg`,
  added_at: addedAt,
  ...extra,
});

/** A progress row as the account returns it: milliseconds. */
const progressRow = (slug, lastWatched, { position, duration } = {}) => ({
  progress_key: `${ID_PREFIX}${slug}`,
  content_id: `${ID_PREFIX}${slug}`,
  content_type: CONTENT_TYPE,
  position: position ?? 60_000,
  duration: duration ?? 300_000,
  last_watched: lastWatched,
});

test('the ids this client sends are the ids the window knows', () => {
  assert.equal(accountIdFor('some-slug'), 'ha:ad:some-slug');
  assert.equal(accountIdFor('  spaced  '), 'ha:ad:spaced');
  // An id is only ever sent for a title; nothing else is a title.
  assert.equal(accountIdFor(''), '');
  assert.equal(accountIdFor(undefined), '');
  assert.equal(libraryPayload({ at: 1 }), null);
  assert.equal(progressPayload('', watched(10, 100, 5)), null);
});

test('an id from another area is passed over, not guessed at', () => {
  assert.equal(slugFromAccountId('ha:ad:some-slug'), 'some-slug');
  // The other areas have readers of their own (`animeKeyFromId` and the two
  // below), and MyAnimeList's ids have none: a `mal:` row is not a card this
  // client can draw. An episode's id carries a season and an episode in its
  // tail, which is not a slug — and not a title either.
  for (const id of ['ha:an:21', 'ha:fm:some-film', 'ha:ft:some-show', 'ha:ft:x:1:5', 'mal:21', 'some-slug', '', null]) {
    assert.equal(slugFromAccountId(id), null, `${id} was read as this client's own`);
  }
  assert.equal(slugFromAccountId('ha:ad:'), null);
  assert.equal(slugFromAccountId('ha:ad:a:b'), null);
});

test('a position crosses the wire in milliseconds and comes back in seconds', () => {
  // The desktop sends `lastPositionMs`/`durationMs`; this phone's own records
  // are what the <video> element says, in seconds.
  assert.equal(millis(90.4), 90_400);
  assert.equal(seconds(90_400), 90);
  assert.equal(seconds(90_450), 90);

  const payload = progressPayload('t', watched(90.4, 1800, 1_700_000_000_000));
  assert.equal(payload.position, 90_400);
  assert.equal(payload.duration, 1_800_000);
  assert.equal(payload.last_watched, 1_700_000_000_000);
  assert.equal(payload.progress_key, payload.content_id);
});

test('a favourite becomes the row the window reads', () => {
  const payload = libraryPayload(filed('t', 1_600_000_000_000));
  assert.deepEqual(payload, {
    content_id: 'ha:ad:t',
    content_type: 'movie',
    name: 'T',
    poster: 'c/t.jpg',
    poster_shape: 'POSTER',
    added_at: 1_600_000_000_000,
  });
  // A record with no cover still files: the window draws its own fallback.
  assert.equal(libraryPayload({ slug: 't', at: 1 }).poster, null);
  // No stamp is 0 rather than now, so two clients ordering by it agree.
  assert.equal(libraryPayload({ slug: 't' }).added_at, 0);
});

test('a position that is not a position is never sent', () => {
  // A junk record, a duration of nothing, a title watched to the end — the
  // device's own rule is that a finished title has no position, and a row that
  // said otherwise would come back as "continue watching" on the window.
  for (const bad of [{ t: 10, d: 0, at: 1 }, { t: 10, d: 5, at: 1 }, { t: -1, d: 10, at: 1 }, { t: 'x', d: 10, at: 1 }]) {
    assert.equal(progressPayload('t', bad), null, `${JSON.stringify(bad)} was sent`);
  }
  assert.equal(progressPayload('t', null), null);
  assert.equal(progressPayload('t', 'nonsense'), null);
});

test('a favourite this device filed while signed out is pushed', () => {
  const mine = filed('mine', 500);
  const { favorites, push } = reconcileFavorites([mine], []);

  // Kept as it is: the mark that says the account owns it is put on when the
  // push lands (account-sync.js), which is also what stops it being sent twice.
  assert.deepEqual(favorites, [mine]);
  assert.deepEqual(push.map((e) => e.slug), ['mine']);
});

test('a favourite the account has and this phone does not is added', () => {
  const { favorites, push } = reconcileFavorites([], [row('theirs', 900)]);

  assert.deepEqual(favorites, [{
    slug: 'theirs',
    name: 'THEIRS from the window',
    cover: 'p/theirs.jpg',
    brand: '',
    at: 900,
    from: 'account',
  }]);
  assert.deepEqual(push, []);
});

test('a title the account no longer has is gone here too', () => {
  // It was in the library, filed by the account, and the account has dropped it
  // — the reader removed it in the desktop window.
  const { favorites, push } = reconcileFavorites(
    [{ ...filed('gone', 900), from: 'account' }],
    [],
  );
  assert.deepEqual(favorites, []);
  assert.deepEqual(push, []);
});

test('a row the account has never seen is kept even when the account is empty', () => {
  // The same shape as the check above, and the mark is the whole difference: a
  // row the *device* filed is this reader's, and a pull must not delete it.
  const { favorites } = reconcileFavorites([filed('mine', 1)], []);
  assert.deepEqual(favorites.map((e) => e.slug), ['mine']);
});

test('a title in both is one title, and it is the account\'s now', () => {
  const { favorites, push } = reconcileFavorites([filed('both', 100)], [row('both', 300)]);

  assert.equal(favorites.length, 1);
  assert.equal(favorites[0].from, 'account');
  assert.equal(favorites[0].at, 300, 'the newer of the two stamps is the one that orders the shelf');
  assert.deepEqual(push, [], 'a title the account already has is not sent again');
});

test('the account\'s copy of a title wins when it is the newer one', () => {
  const older = reconcileFavorites([filed('t', 100)], [row('t', 400)]);
  assert.equal(older.favorites[0].name, 'T from the window');
  assert.equal(older.favorites[0].cover, 'p/t.jpg');

  // And the other way round: this device's own record of a title the account
  // knows nothing new about keeps the name it has.
  const newer = reconcileFavorites([filed('t', 500)], [row('t', 100)]);
  assert.equal(newer.favorites[0].name, 'T');
  assert.equal(newer.favorites[0].cover, 'c/t.jpg');
});

test('the shelf comes out newest first, whether a row came from here or there', () => {
  const { favorites } = reconcileFavorites(
    [filed('old', 100), filed('new', 800)],
    [row('middle', 400)],
  );
  assert.deepEqual(favorites.map((e) => e.slug), ['new', 'middle', 'old']);
});

test('rows this client cannot draw are ignored on the way in', () => {
  const { favorites, push } = reconcileFavorites(
    [],
    [row('mine', 500, { content_id: 'ha:an:21' }), { content_id: null }, {}, row('real', 100)],
  );
  assert.deepEqual(favorites.map((e) => e.slug), ['real']);
  assert.deepEqual(push, []);
});

test('the same title twice in one pull is one row, the newer one', () => {
  const { favorites } = reconcileFavorites([], [row('t', 100), row('t', 700, { poster: 'new.jpg' })]);
  assert.equal(favorites.length, 1);
  assert.equal(favorites[0].at, 700);
  assert.equal(favorites[0].cover, 'new.jpg');
});

test('the merge does not write into the records it was given', () => {
  const local = [filed('t', 100)];
  const remote = [row('t', 300)];
  const before = JSON.parse(JSON.stringify({ local, remote }));
  reconcileFavorites(local, remote);
  mergePositions({ t: watched(1, 10, 5) }, [progressRow('t', 9)]);
  assert.deepEqual({ local, remote }, before);
});

test('the later watching of a title is the one both sides keep', () => {
  const mineLater = mergePositions({ t: watched(30, 600, 900) }, [progressRow('t', 100)]);
  assert.deepEqual(mineLater.positions.t, watched(30, 600, 900));
  assert.deepEqual(mineLater.push.map((e) => e.slug), ['t']);

  const theirsLater = mergePositions({ t: watched(30, 600, 100) }, [progressRow('t', 900)]);
  // Their row was written in milliseconds; this one is stored in seconds.
  assert.deepEqual(theirsLater.positions.t, watched(60, 300, 900));
  assert.deepEqual(theirsLater.push, []);

  // The same moment is not a reason to send anything anywhere.
  const same = mergePositions({ t: watched(30, 600, 500) }, [progressRow('t', 500)]);
  assert.deepEqual(same.positions.t, watched(30, 600, 500));
  assert.deepEqual(same.push, []);
});

test('watching that only happened here is on its way to the window', () => {
  const { positions, push } = mergePositions({ solo: watched(10, 100, 50) }, []);
  assert.deepEqual(positions.solo, watched(10, 100, 50));
  assert.deepEqual(push.map((e) => e.slug), ['solo']);
  // A position this device would not keep is not sent either.
  assert.deepEqual(mergePositions({ bad: { t: 9, d: 9, at: 5 } }, []).push, []);
});

test('a title finished on the other client is not "continue watching" here', () => {
  const { positions } = mergePositions({}, [progressRow('done', 900, { position: 300_000, duration: 300_000 })]);
  assert.deepEqual(positions, {});

  // And a row for another area is not this client's to draw.
  const foreign = mergePositions({}, [{ ...progressRow('an', 900), content_id: 'ha:an:21' }]);
  assert.deepEqual(foreign.positions, {});
  assert.deepEqual(foreign.push, []);
});

test('the same title twice in one progress pull is one title', () => {
  const { positions } = mergePositions({}, [
    progressRow('t', 100, { position: 10_000 }),
    progressRow('t', 900, { position: 200_000, duration: 400_000 }),
  ]);
  assert.deepEqual(positions.t, watched(200, 400, 900));
});

test('junk rows are not positions, and do not erase a real one', () => {
  const { positions } = mergePositions(
    { t: watched(30, 600, 900) },
    [
      // A row with nothing in it, a row whose duration is nothing, and a title
      // finished somewhere else — each one newer than this device's own record,
      // which is exactly when a bad row would win.
      { content_id: `${ID_PREFIX}t`, content_type: CONTENT_TYPE, position: null, duration: null, last_watched: 1_000 },
      progressRow('t', 1_000, { position: 5, duration: 0 }),
      progressRow('t', 1_000, { position: 300_000, duration: 300_000 }),
    ],
  );
  assert.deepEqual(positions.t, watched(30, 600, 900), 'a row that is not a position overwrote one that was');
});

/* ------------------------------------------------------------- history ---- */
// What was opened, which the account keeps instead of one device: the list the
// Library view draws, and the one that has to survive a reinstall.

/** One opening as this device files it. */
const opened = (slug, at, extra = {}) => ({ slug, name: slug.toUpperCase(), cover: `c/${slug}.jpg`, brand: 'Studio', at, ...extra });

/** One opening as the account returns it. */
const historyRow = (slug, openedAt, extra = {}) => ({
  content_id: `${ID_PREFIX}${slug}`,
  content_type: CONTENT_TYPE,
  name: `${slug.toUpperCase()} from the account`,
  poster: `p/${slug}.jpg`,
  brand: 'Studio',
  opened_at: openedAt,
  ...extra,
});

test('an opening becomes a history row, brand and all', () => {
  assert.deepEqual(historyPayload(opened('t', 1_700_000_000_000)), {
    content_id: 'ha:ad:t',
    content_type: 'movie',
    name: 'T',
    poster: 'c/t.jpg',
    brand: 'Studio',
    opened_at: 1_700_000_000_000,
  });
  // A title with no brand still files — the card's own line goes without one.
  assert.equal(historyPayload({ slug: 't', at: 5 }).brand, null);
  assert.equal(historyPayload({ slug: 't', at: 5 }).poster, null);
  assert.equal(historyPayload({ at: 5 }), null);
});

test('history in the account and history on the phone become one list', () => {
  const { history, push } = reconcileHistory(
    [opened('here', 500)],
    [historyRow('there', 900)],
  );

  // Newest first, whoever opened it.
  assert.deepEqual(history.map((e) => e.slug), ['there', 'here']);
  assert.equal(history[0].from, 'account');
  assert.equal(history[0].at, 900);
  assert.deepEqual(push.map((e) => e.slug), ['here'], 'the opening the account has never heard of is not sent');
});

test('a list cleared elsewhere is cleared here', () => {
  const kept = reconcileHistory([opened('mine', 1)], []);
  assert.deepEqual(kept.history.map((e) => e.slug), ['mine']);
  assert.deepEqual(kept.push.map((e) => e.slug), ['mine']);

  // The same row, marked as the account's — and gone from the account, which is
  // what the phone's own clear (or another phone's) leaves behind.
  const gone = reconcileHistory([{ ...opened('mine', 1), from: 'account' }], []);
  assert.deepEqual(gone.history, []);
  assert.deepEqual(gone.push, []);
});

test('the account\'s copy of an opening wins when it is the newer one', () => {
  const newer = reconcileHistory([opened('t', 100)], [historyRow('t', 400)]);
  assert.equal(newer.history[0].name, 'T from the account');
  assert.equal(newer.history[0].at, 400);

  const mine = reconcileHistory([opened('t', 500)], [historyRow('t', 100)]);
  assert.equal(mine.history[0].name, 'T');
  assert.equal(mine.history[0].at, 500);
});

test('the phone keeps a shelf, and the account keeps the list', () => {
  // Two hundred openings on the account, none of them ever made here.
  const remote = Array.from({ length: 200 }, (_, i) => historyRow(`t${i}`, 1000 + i));
  const { history } = reconcileHistory([], remote, 50);

  assert.equal(history.length, 50, 'the cap is not the one asked for');
  assert.deepEqual(history.map((e) => e.slug), Array.from({ length: 50 }, (_, i) => `t${199 - i}`),
    'the newest openings are not the ones kept');
  // No cap asked for is no cap: the merge is the model's, the shelf's size is
  // the caller's.
  assert.equal(reconcileHistory([], remote).history.length, 200);
});

test('the same title opened twice is one row, and the later opening is it', () => {
  const twice = reconcileHistory([opened('t', 900)], [historyRow('t', 100)]);
  assert.equal(twice.history.length, 1);
  assert.equal(twice.history[0].at, 900);

  const pulled = reconcileHistory([], [historyRow('t', 100), historyRow('t', 900, { name: 'later' })]);
  assert.equal(pulled.history.length, 1);
  assert.equal(pulled.history[0].name, 'later');
});

test('an opening from another area is passed over, like every other row', () => {
  const { history } = reconcileHistory([], [historyRow('t', 5, { content_id: 'ha:an:21' })]);
  assert.deepEqual(history, []);
});

test('the client speaks the profile and the area the window does', () => {
  // The wire's own constants, asserted because they are a contract with another
  // client rather than a choice this file can make on its own.
  assert.equal(ID_PREFIX, 'ha:ad:');
  assert.equal(CONTENT_TYPE, 'movie');
  assert.equal(PROFILE_ID, 1);
});

/* ------------------------------------------- continue watching, both ways -- */
// The anime and films areas play inside somebody else's document in a frame, so
// the phone can never know the second — only which episode was opened. That is
// still the window's own record, and the ids below are the window's own ids
// (`server/src/addon.mjs` builds them), so one row is the same row on both
// sides. These tests are about the two things that must not drift: the id/unit
// of the write, and which episode a card stands for on the pull.

/** One of the embed areas' progress rows, as the account returns it. */
const watchRow = (contentId, lastWatched, extra = {}) => ({
  progress_key: contentId,
  content_id: contentId,
  content_type: 'series',
  video_id: contentId,
  season: null,
  episode: null,
  position: 0,
  duration: 0,
  last_watched: lastWatched,
  ...extra,
});

test('an anime opened here is a row the window can resume', () => {
  assert.equal(animeIdFor(21), 'ha:an:21');
  assert.equal(animeIdFor(''), '');
  assert.equal(showIdFor('tv/breaking-bad-1396'), 'ha:ft:breaking-bad-1396');
  assert.equal(showIdFor('movie/dune-2021'), 'ha:fm:dune-2021');
  assert.equal(showIdFor('tv/'), '');
  assert.equal(showIdFor('an/21'), '', 'an area that is not this one is not a show');

  // Back the other way, for the pull — and an episode's tail is not the key.
  assert.equal(animeKeyFromId('ha:an:21'), '21');
  assert.equal(animeKeyFromId('ha:an:21:1:5'), '21');
  assert.equal(seriesKeyFromId('ha:ft:x'), 'x');
  assert.equal(filmKeyFromId('ha:fm:y'), 'y');
  assert.equal(animeKeyFromId('ha:ad:x'), null);

  const anime = activityProgressPayload({ kind: 'anime', id: 21, number: 5, at: 1_700_000_000_000 });
  assert.deepEqual(anime, {
    // The window's own key rule: one row per episode, named after the episode.
    progress_key: 'ha:an:21_s1e5',
    content_id: 'ha:an:21',
    content_type: 'series',
    video_id: 'ha:an:21:1:5',
    season: 1,
    episode: 5,
    // One second: the smallest claim that means "watched", and no duration,
    // because an embed cannot report one.
    position: 1_000,
    duration: 0,
    last_watched: 1_700_000_000_000,
  });
  assert.equal(STARTED_POSITION_MS, 1_000);

  const episode = activityProgressPayload({ kind: 'show', id: 'tv/x', ref: 's2e5', at: 10 });
  assert.equal(episode.progress_key, 'ha:ft:x_s2e5');
  assert.equal(episode.video_id, 'ha:ft:x:2:5');
  assert.equal(episode.season, 2);
  assert.equal(episode.episode, 5);

  // A film has no episode to resume: it travels as the title itself.
  const film = activityProgressPayload({ kind: 'show', id: 'movie/y', at: 10 });
  assert.equal(film.progress_key, 'ha:fm:y');
  assert.equal(film.content_type, 'movie');
  assert.equal(film.video_id, 'ha:fm:y');
  assert.equal(film.season, null);
  assert.equal(film.episode, null);
});

test('a chapter is not a row the window could ever show', () => {
  // Manga keeps its own store, and the window has no reader at all.
  assert.equal(activityProgressPayload({ kind: 'manga', id: 'm', number: 3, at: 5 }), null);
  assert.equal(activityProgressKey({ kind: 'manga', id: 'm', number: 3 }), null);
  // And an episode that is not a number is not a row either.
  assert.equal(activityProgressPayload({ kind: 'anime', id: 21 }), null);
  assert.equal(activityProgressPayload({ kind: 'anime', id: 21, number: 0.5 }), null);
  assert.equal(activityProgressPayload({ kind: 'show', id: 'an/21', ref: 's1e1' }), null);
});

test('the window\'s watching arrives here as this phone\'s own card', () => {
  const rows = [
    watchRow('ha:an:21', 900, { progress_key: 'ha:an:21_s1e5', episode: 5, season: 1 }),
    watchRow('ha:an:21', 100, { progress_key: 'ha:an:21_s1e4', episode: 4, season: 1 }),
    // The 18+ shelf has its own list and must not be drawn on this rail.
    watchRow('ha:ad:some-slug', 5_000),
    // A row with no moment it was watched is not a row.
    watchRow('ha:fm:none', 0),
  ];
  const groups = activityTitleGroups(rows);
  assert.deepEqual([...groups.keys()], ['ha:an:21']);
  assert.deepEqual(groups.get('ha:an:21').map((r) => r.episode), [5, 4], 'the newest row is not first');

  const entry = activityEntryForTitle(groups.get('ha:an:21'), { episodes: 12 });
  assert.equal(entry.kind, 'anime');
  assert.equal(entry.id, '21');
  assert.equal(entry.number, 5);
  assert.equal(entry.at, 900);
  assert.equal(entry.from, 'account');
  // Every row the card stands for travels with it, so taking it off the rail
  // takes the account's rows with it rather than putting it straight back.
  assert.deepEqual(entry.keys, ['ha:an:21_s1e5', 'ha:an:21_s1e4']);

  const series = activityEntryForTitle([
    watchRow('ha:ft:x', 800, { progress_key: 'ha:ft:x_s2e5', season: 2, episode: 5 }),
  ]);
  assert.equal(series.kind, 'show');
  assert.equal(series.id, 'tv/x', 'a series card opens `type/slug`, the way the view does');
  assert.equal(series.number, 5);
  assert.equal(series.ref, 's2e5', 'and at the season the window was at, not at its first');

  const film = activityEntryForTitle([watchRow('ha:fm:y', 800)]);
  assert.equal(film.kind, 'show');
  assert.equal(film.id, 'movie/y');
  assert.equal(film.ref, null);
});

test('a finished episode continues at the next one, when there is one', () => {
  const done = (episode, episodes, position = 540_000, duration = 600_000) => activityEntryForTitle(
    [watchRow('ha:an:22', 900, {
      progress_key: `ha:an:22_s1e${episode}`, season: 1, episode, position, duration,
    })],
    { episodes },
  );

  assert.equal(done(5, 12).number, 6, 'a finished episode is not where the card resumes');
  assert.equal(done(12, 12), null, 'the last episode is not a next one');
  // Nothing here knows how many episodes the series has, and guessing "there is
  // another" is how a card opens onto a player with nothing behind it.
  assert.equal(done(5, 0), null);
  // The window's own rule, mirrored: ninety percent, and never for a duration
  // that is a placeholder rather than an episode.
  assert.equal(done(5, 12, 500_000, 600_000).number, 5, 'eighty-three percent is not finished');
  assert.equal(done(5, 12, 60_000, 60_000).number, 5, 'a minute is not a real episode');
});

test('a title dropped in the window is dropped here, and a newer one is sent', () => {
  const here = [
    { kind: 'anime', id: '21', title: 'Mine', cover: 'c/mine.jpg', number: 3, at: 100 },
    // On this rail because the account had it, and the account no longer does:
    // the reader finished it there.
    { kind: 'anime', id: '22', title: 'Gone', number: 4, at: 50, from: 'account' },
  ];
  const there = [
    { kind: 'anime', id: '21', number: 4, at: 900, from: 'account' },
  ];

  const { entries, push } = reconcileActivity(here, there);

  assert.deepEqual(entries.map((e) => e.id), ['21'], 'a title the account dropped is still on this rail');
  // The account carries the episode; this side carries the name and cover,
  // because a progress row does not keep either.
  assert.equal(entries[0].number, 4);
  assert.equal(entries[0].title, 'Mine');
  assert.equal(entries[0].cover, 'c/mine.jpg');
  assert.equal(entries[0].at, 900);
  // Nothing to send: the account's copy is the newer watching.
  assert.deepEqual(push, []);
});

test('an episode opened here after the account\'s last word is sent', () => {
  // Same title, later episode, on this phone: the window has to hear about it or
  // the two rails keep two different episodes of one show.
  const { entries, push } = reconcileActivity(
    [{ kind: 'anime', id: '21', title: 'T', number: 7, at: 900 }],
    [{ kind: 'anime', id: '21', number: 5, at: 100, from: 'account' }],
  );
  assert.equal(entries[0].number, 7);
  assert.deepEqual(push.map((e) => e.number), [7]);

  // And a title the account has never heard of goes the same way.
  const fresh = reconcileActivity([{ kind: 'show', id: 'movie/y', title: 'Y', number: 1, at: 5 }], []);
  assert.deepEqual(fresh.push.map((e) => e.id), ['movie/y']);
});

test('a real position is never replaced by an opening', () => {
  // The window stopped twelve minutes into an episode; the phone opening the
  // same episode knows only that it was opened, and writing one second over the
  // window's row would send the reader back to the start of it.
  assert.equal(hasRealPosition({ position: 720_000, duration: 1_440_000 }), true);
  assert.equal(hasRealPosition({ position: 720_000, duration: 0 }), true);
  assert.equal(hasRealPosition({ position: STARTED_POSITION_MS, duration: 0 }), false);
  assert.equal(hasRealPosition({ position: 0, duration: 0 }), false);
  assert.equal(hasRealPosition(null), false);
});
