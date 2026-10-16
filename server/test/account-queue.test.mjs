// The queue is what stands between a phone and the account server, and every
// rule it keeps is one a reader would notice if it broke: a video's position
// writes must not become a request each, a heart pressed and released must not
// leave the title filed, and a call that fails must not lose what it carried.
//
// So this drives the real module with timers the test owns — the clock is a
// number the test moves, not a wall it waits on — because a backoff is fifteen
// seconds of real time otherwise. The sender is a stub that can be made to fail
// on demand, which is the only way to see what happens to entries that were on
// the wire when it did.
//
// The module itself is pure (no storage, no DOM, no imports), so it is loaded
// as it ships rather than through the bundle.

import test from 'node:test';
import assert from 'node:assert/strict';

import { createAccountQueue } from '../client/src/account-queue.js';

/** A clock the test moves by hand, holding everything `schedule` was given. */
function clock() {
  let now = 0;
  let seq = 0;
  const due = new Map();
  return {
    schedule(fn, ms) {
      const id = `t${++seq}`;
      due.set(id, { fn, at: now + ms });
      return id;
    },
    cancel(id) { due.delete(id); },
    get now() { return now; },
    /** The timestamps a call happened at, for the backoff assertions. */
    get pending() { return due.size; },
    async run(ms) {
      const until = now + ms;
      for (;;) {
        const next = [...due.entries()]
          .filter(([, timer]) => timer.at <= until)
          .sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        const [id, timer] = next;
        due.delete(id);
        now = timer.at;
        await timer.fn();
      }
      now = until;
    },
  };
}

/**
 * A sender that records its calls, and can fail or hang on command.
 *
 * `hold` makes the *next* call wait for the test to release or fail it, which
 * is the only way to write inside the window where a call is on the wire. It is
 * one-shot: a call the test has not asked to hold resolves on its own, or a
 * test that holds one call would hold every call after it and hang.
 */
function sender() {
  const calls = [];
  const state = { fail: 0, hold: false, held: null };
  const send = (kind, entries) => {
    calls.push({ kind, entries });
    if (state.fail > 0) {
      state.fail -= 1;
      return Promise.reject(new Error('the network was not there'));
    }
    if (!state.hold) return Promise.resolve();
    state.hold = false;
    return new Promise((resolve, reject) => { state.held = { resolve, reject }; });
  };
  const take = () => {
    const held = state.held;
    state.held = null;
    return held;
  };
  return {
    send,
    calls,
    state,
    release: () => take()?.resolve(),
    failHeld: (message = 'the network was not there') => take()?.reject(new Error(message)),
  };
}

function queueWith({ fail = 0 } = {}) {
  const times = clock();
  const senderStub = sender();
  senderStub.state.fail = fail;
  const queue = createAccountQueue({
    send: senderStub.send,
    schedule: times.schedule,
    cancel: times.cancel,
    holdMs: 2000,
    retryDelays: [5000, 15000, 60000, 300000],
  });
  return { queue, times, ...senderStub };
}

test('a change waits a moment, then goes as one call', async () => {
  const { queue, times, calls } = queueWith();
  queue.set('library', 'ha:ad:a', { id: 'a' });

  await times.run(1999);
  assert.equal(calls.length, 0, 'the write went out without waiting for company');

  await times.run(1);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].kind, 'library');
  assert.deepEqual(calls[0].entries, [{ key: 'ha:ad:a', payload: { id: 'a' } }]);
  assert.equal(queue.pending(), 0);
});

test('writes about one title collapse into the newest', async () => {
  const { queue, times, calls } = queueWith();
  // Six seconds of watching, at the four ticks that would have been four requests.
  queue.set('progress', 'ha:ad:a', { position: 5000 });
  queue.set('progress', 'ha:ad:a', { position: 10000 });
  queue.set('progress', 'ha:ad:a', { position: 15000 });

  await times.run(2000);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].entries, [{ key: 'ha:ad:a', payload: { position: 15000 } }]);
});

test('a filing and then a removal is a removal, and the other way round is a filing', async () => {
  const on = queueWith();
  on.queue.set('library', 'ha:ad:a', { name: 'A' });
  on.queue.set('library', 'ha:ad:a', null);
  await on.times.run(2000);
  assert.deepEqual(on.calls[0].entries, [{ key: 'ha:ad:a', payload: null }]);

  const off = queueWith();
  off.queue.set('library', 'ha:ad:a', null);
  off.queue.set('library', 'ha:ad:a', { name: 'A' });
  await off.times.run(2000);
  assert.deepEqual(off.calls[0].entries, [{ key: 'ha:ad:a', payload: { name: 'A' } }]);
});

test('each dataset is one call of its own, and they travel together', async () => {
  const { queue, times, calls } = queueWith();
  queue.set('library', 'ha:ad:a', { name: 'A' });
  queue.set('library', 'ha:ad:b', { name: 'B' });
  queue.set('progress', 'ha:ad:a', { position: 1 });
  queue.set('history', 'ha:ad:c', { opened_at: 5 });

  await times.run(2000);
  assert.deepEqual(calls.map((c) => c.kind).sort(), ['history', 'library', 'progress']);
  assert.equal(calls.find((c) => c.kind === 'library').entries.length, 2,
    'two titles of one dataset are one call, not two');
});

test('a call that fails keeps its entries and tries again', async () => {
  const { queue, times, calls } = queueWith({ fail: 1 });
  queue.set('library', 'ha:ad:a', { name: 'A' });

  await times.run(2000);
  assert.equal(calls.length, 1, 'the first attempt did not happen');
  assert.equal(queue.pending(), 1, 'the entry was dropped by a failure');

  await times.run(4999);
  assert.equal(calls.length, 1, 'the retry came early');
  await times.run(1);
  assert.equal(calls.length, 2, 'the retry never came');
  assert.equal(queue.pending(), 0);
  assert.deepEqual(calls[1].entries, calls[0].entries, 'the retry carried something else');

  // And nothing more: a call that landed is not retried further.
  await times.run(600000);
  assert.equal(calls.length, 2);
});

test('the wait between attempts grows, and stops growing', async () => {
  const { queue, times, calls } = queueWith({ fail: 5 });
  queue.set('library', 'ha:ad:a', { name: 'A' });

  await times.run(2000);      // the first attempt
  const marks = [times.now];
  for (const wait of [5000, 15000, 60000, 300000]) {
    await times.run(wait - 1);
    assert.equal(calls.length, marks.length, `the attempt after ${wait}ms came early`);
    await times.run(1);
    marks.push(times.now);
  }
  assert.equal(calls.length, 5);
  assert.equal(queue.pending(), 1, 'the entry should still be waiting after four failures');
});

test('an entry written while a call is in flight survives the call failing', async () => {
  const { queue, times, calls, state, failHeld } = queueWith();
  state.hold = true;

  queue.set('library', 'ha:ad:a', { name: 'older' });
  const first = queue.flushNow();
  // The reader presses the heart again while the first write is on the wire.
  queue.set('library', 'ha:ad:a', { name: 'newer' });
  failHeld();
  await first.catch(() => {});

  await times.run(5000);
  const sent = calls.flatMap((c) => c.entries);
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[sent.length - 1], { key: 'ha:ad:a', payload: { name: 'newer' } },
    'the failed copy of an entry overwrote the newer one');
  assert.equal(queue.pending(), 0);
});

test('a flush waits for what is in flight, then sends what is left', async () => {
  const { queue, times, calls, state, release } = queueWith();
  state.hold = true;

  queue.set('library', 'ha:ad:a', { name: 'A' });
  const first = queue.flushNow();
  queue.set('library', 'ha:ad:b', { name: 'B' });
  const second = queue.flush();            // waits out A, then owes B its own call

  release();
  await Promise.all([first, second]);

  const keys = calls.flatMap((c) => c.entries.map((e) => e.key));
  assert.deepEqual(keys, ['ha:ad:a', 'ha:ad:b']);
  assert.equal(queue.pending(), 0);
  // Nothing is left to send, so no timer is left behind either.
  await times.run(600000);
  assert.equal(calls.length, 2);
});

test('a flush with nothing pending does not call the sender', async () => {
  const { queue, times, calls } = queueWith();
  const result = await queue.flush();
  assert.deepEqual(result, { sent: 0 });
  await times.run(600000);
  assert.equal(calls.length, 0);
});

test('a list the reader emptied drops what was queued about it', async () => {
  const { queue, times, calls } = queueWith();
  queue.set('history', 'ha:ad:a', { opened_at: 1 });
  queue.set('progress', 'ha:ad:a', { position: 1 });
  queue.set('library', 'ha:ad:a', { name: 'A' });

  queue.clear('history');
  queue.clear('progress');
  await times.run(2000);

  assert.deepEqual(calls.map((c) => c.kind), ['library']);
});

test('signing out empties the queue rather than leaving it to fail', async () => {
  const { queue, times, calls } = queueWith();
  queue.set('library', 'ha:ad:a', { name: 'A' });
  queue.set('progress', 'ha:ad:a', { position: 1 });
  queue.clear();

  await times.run(600000);
  assert.equal(calls.length, 0);
  assert.equal(queue.pending(), 0);
});

test('a payload of nothing is a deletion, not a missing write', async () => {
  const { queue, times, calls } = queueWith();
  // `undefined` is what a caller gets from a record that has no payload: the
  // queue's answer is to delete the key, never to send the string "undefined".
  queue.set('progress', 'ha:ad:a', undefined);
  await times.run(2000);
  assert.deepEqual(calls[0].entries, [{ key: 'ha:ad:a', payload: null }]);
});

test('a write with no kind or no key is not an entry to send', async () => {
  const { queue, times, calls } = queueWith();
  queue.set('', 'ha:ad:a', { name: 'A' });
  queue.set('library', '', { name: 'A' });
  await times.run(600000);
  assert.equal(calls.length, 0);
  assert.equal(queue.pending(), 0);
});
