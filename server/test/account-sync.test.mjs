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
  // The desktop's anime, films and series rows, and MyAnimeList's own ids: this
  // client cannot resolve any of them to a card, and an episode's id carries a
  // season and an episode in its tail, which is not a slug.
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
