// account-queue.js — what holds a write until it can be sent.
//
// Two things this phone's writes need that one request per change does not
// give. They come in bursts — a video writes its position every few seconds
// while it is playing, and one sync can find a dozen titles the account has
// never seen — and the network comes and goes, on a phone more than anywhere.
// So a change does not become a request here: it becomes a *pending entry*,
// keyed by the thing it is about, and a flush turns whatever is pending into
// one call per dataset.
//
//   * Two writes about one title inside the window are one entry, and the
//     newer one is the one that survives: the last position written is the
//     only one worth sending.
//   * A filed title and then a removal is a removal; a removal and then a
//     filing is a filing. The newest intent per key wins, whatever the order.
//   * A flush that fails keeps its entries and tries again on a backoff, so a
//     tunnel, a lost signal or a sleeping laptop costs latency and nothing
//     else. Nothing is dropped until it has been acknowledged.
//   * One flush is in flight at a time; entries that arrive during it are the
//     next flush's, never a second copy of the same call.
//
// It is a factory rather than a module-level singleton, and it knows nothing
// about the account, the RPCs or the DOM: the sender, the timer and the clock
// are all handed in. That is what lets `test/account-queue.test.mjs` drive it
// with timers it controls, instead of sleeping its way through a backoff — and
// what keeps the interesting rules ("what happens when the second write lands
// while the first is on the wire") in one small place.

/** A payload of `null` is a deletion: the entry says "this is gone". */
function createAccountQueue({
  send,
  schedule = (fn, ms) => setTimeout(fn, ms),
  cancel = (handle) => clearTimeout(handle),
  // How long the first change waits for company. Shorter than the five seconds
  // between a player's position writes, so watching a video is a request per
  // write at most and a burst of them is one call.
  holdMs = 2000,
  // A failed attempt waits longer each time, and stops growing here: a phone
  // that comes back to signal should not have to wait five minutes for it to
  // notice, and one that has not should not be knocking every second.
  retryDelays = [5000, 15000, 60000, 300000],
  onError = () => {},
} = {}) {
  if (typeof send !== 'function') throw new Error('createAccountQueue needs a send');

  /** kind → (key → payload). The newest payload for a key is the entry. */
  const pending = new Map();
  let timer = null;
  let inflight = null;
  let failures = 0;

  const bucket = (kind) => {
    let held = pending.get(kind);
    if (!held) { held = new Map(); pending.set(kind, held); }
    return held;
  };

  const sizeOf = (kind) => {
    if (kind) return pending.get(kind)?.size ?? 0;
    let total = 0;
    for (const held of pending.values()) total += held.size;
    return total;
  };

  /** What is pending, bucket by bucket, and out of the queue. */
  function take() {
    const taken = new Map();
    for (const [kind, held] of pending) {
      if (held.size) taken.set(kind, held);
    }
    pending.clear();
    return taken;
  }

  /**
   * The taken entries go back only where nothing newer has arrived: an entry
   * that was written again while the call was in the air is that writer's
   * latest word, and the failed copy is older than it.
   */
  function restore(taken) {
    for (const [kind, held] of taken) {
      const bucketNow = bucket(kind);
      for (const [key, payload] of held) {
        if (!bucketNow.has(key)) bucketNow.set(key, payload);
      }
    }
  }

  function hold() {
    if (timer !== null || inflight) return;
    timer = schedule(() => { timer = null; run().catch(() => {}); }, holdMs);
  }

  async function run() {
    if (inflight) return inflight;
    if (timer !== null) { cancel(timer); timer = null; }

    const taken = take();
    if (!taken.size) return { sent: 0 };

    let count = 0;
    for (const held of taken.values()) count += held.size;

    inflight = (async () => {
      // One call per dataset, in parallel: they are independent rows through
      // independent functions, and a slow one must not hold up another.
      const kinds = [...taken.entries()];
      try {
        await Promise.all(kinds.map(([kind, held]) => send(kind, [...held.entries()].map(([key, payload]) => ({ key, payload })))));
      } catch (e) {
        restore(taken);
        const wait = retryDelays[Math.min(failures, retryDelays.length - 1)];
        failures += 1;
        try { onError(e); } catch (ignored) { /* the queue outlives its reporter */ }
        timer = schedule(() => { timer = null; run().catch(() => {}); }, wait);
        throw e;
      }
      failures = 0;
      return { sent: count };
    })();

    try {
      return await inflight;
    } finally {
      inflight = null;
      // Writes that arrived while the call was on the wire: hold them briefly
      // like any other, so a video's next few seconds join the same call.
      if (sizeOf()) hold();
    }
  }

  return {
    /** Records one change: `payload` is what to send, or null to delete the key. */
    set(kind, key, payload) {
      if (!kind || !key) return;
      bucket(kind).set(key, payload ?? null);
      hold();
    },
    /** Forgets entries nobody should send any more — a sign-out, a cleared list. */
    clear(kind) {
      if (kind) pending.delete(kind);
      else pending.clear();
    },
    /** True when there is anything to send. */
    pending: sizeOf,
    /**
     * Sends what is pending now, and answers when it is acknowledged. A failure
     * rejects (the caller reports it) and the entries stay queued for the retry
     * that has already been scheduled.
     */
    async flush() {
      // What this call is for is the entries still here, so an attempt already
      // on the wire is waited out first — and re-checked, because "in flight"
      // is cleared by the attempt that owns it, not by the waiter.
      for (let attempt = 0; attempt < 50; attempt += 1) {
        if (inflight) { await inflight.catch(() => {}); continue; }
        if (!sizeOf()) return { sent: 0 };
        return run();
      }
      return { sent: 0 };
    },
    /** The same, but the caller has already decided nothing is in flight. */
    flushNow: run,
  };
}

export { createAccountQueue };
