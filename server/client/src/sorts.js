// sorts.js — the orders a shelf can be put in, and the one the reader picked.
//
// The app keeps several kinds of recency and each shelf holds a different one:
// MyAnimeList's list answers `updated_at` (its own list activity), the device
// answers when a chapter or an episode was actually opened, and a favorite
// answers when it was filed. "Organize by last read" therefore means something
// slightly different on every shelf — which is exactly why the *words* live
// here, once, and the shelves only say which of them they can honour.
//
// A shelf is a name with an ordered list of orders; the first is its default.
// `sorted(name, rows)` puts rows in the picked order, `fillSorts()` fills every
// `<select data-sort-for="name">` on the page with that shelf's words and the
// remembered pick, and a change is remembered and announced as an `htv:sort`
// event so the shelf that owns the rows can redraw them.
//
// Nothing here knows what a MAL entry or a chapter is: the comparators read
// conventional fields — `at`/`updatedAt` for when, `title`/`name` for what,
// `progress`/`number` and `total` for how far, `score`, `status` — and a row
// that lacks one simply ties on it.

const KEY = 'htv:sorts:v1';

// Same guarded reads as the rest of the client: private-mode storage that
// throws must not take the page down.
function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch (e) {
    return fallback;
  }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* unwritable */ }
}

/**
 * Every order a shelf can offer, and the words it prints for them. Short on
 * purpose: these ride a section header on a phone, where a sentence in a
 * select is a select nobody can read.
 *
 * "Progress" means furthest along first (a title at 80 of 100 chapters leads
 * one at chapter 3) and falls back to the raw number where a total is not
 * known; "Score" means highest first. Both are stated in COMPARE, where they
 * are actually implemented.
 */
const ORDERS = {
  last: 'Last read',
  updated: 'Last updated',
  watched: 'Last watched',
  added: 'Recently added',
  title: 'Title A–Z',
  progress: 'Progress',
  score: 'Score',
  status: 'Status',
};

/**
 * The shelves. Each is the orders it offers, in the order the control shows
 * them — the first one is what a reader who has never picked gets.
 */
const SECTIONS = {
  // The account's own lists.
  'mal-anime': { orders: ['watched', 'title', 'progress', 'score', 'status'] },
  'mal-manga': { orders: ['last', 'title', 'progress', 'score', 'status'] },
  'mal-adult': { orders: ['updated', 'title', 'progress', 'score'] },
  // What this device was doing.
  'watch-anime': { orders: ['watched', 'title', 'progress'] },
  'read-manga': { orders: ['last', 'title', 'progress'] },
  'show-watch': { orders: ['watched', 'title', 'progress'] },
  // The 18+ shelf's own records.
  cont: { orders: ['watched', 'title', 'progress'] },
  fav: { orders: ['added', 'title'] },
  hist: { orders: ['watched', 'title'] },
};

/** The words a shelf prints for one of its orders. */
const labelOf = (key) => ORDERS[key] ?? key;

const when = (row) => Number(row.at ?? row.updatedAt) || 0;
const titleOf = (row) => String(row.title ?? row.name ?? row.slug ?? '');
const doneOf = (row) => Number(row.progress ?? row.number) || 0;
const pctOf = (row) => {
  const total = Number(row.total) || 0;
  return total > 0 ? Math.min(1, doneOf(row) / total) : 0;
};

// The statuses in the order a reader thinks of them: what is being followed
// now, then what is paused, then what is done, then what is queued, then what
// was let go. Anything unrecognised (a device row has no MAL status at all)
// sorts after every real one.
const STATUS_RANK = {
  watching: 0, reading: 0, on_hold: 1, completed: 2,
  plan_to_watch: 3, plan_to_read: 3, dropped: 4,
};
const rankOf = (row) => STATUS_RANK[row.status] ?? 5;

/** Newest first — "last read", "last watched", "recently added" are one idea. */
const recent = (a, b) => when(b) - when(a);

const COMPARE = {
  last: recent,
  updated: recent,
  watched: recent,
  added: recent,
  title: (a, b) => titleOf(a).localeCompare(titleOf(b), undefined, { sensitivity: 'base' })
    || recent(a, b),
  progress: (a, b) => pctOf(b) - pctOf(a) || doneOf(b) - doneOf(a) || recent(a, b),
  score: (a, b) => (Number(b.score) || 0) - (Number(a.score) || 0) || recent(a, b),
  status: (a, b) => rankOf(a) - rankOf(b) || recent(a, b),
};

const chosen = load(KEY, {});

/** The order a shelf is currently in — the picked one, or its default. */
function choiceOf(name) {
  const section = SECTIONS[name];
  if (!section) return 'last';
  return section.orders.includes(chosen[name]) ? chosen[name] : section.orders[0];
}

/** The rows, in that order. Always a copy: the caller's list is not touched. */
function sorted(name, rows) {
  const cmp = COMPARE[choiceOf(name)] ?? recent;
  return (rows ?? []).slice().sort(cmp);
}

/** Fills every sort control on the page: this shelf's words, this shelf's pick. */
function fillSorts() {
  document.querySelectorAll('[data-sort-for]').forEach((sel) => {
    const name = sel.dataset.sortFor;
    const section = SECTIONS[name];
    if (!section) return;
    const pick = choiceOf(name);
    sel.innerHTML = section.orders
      .map((key) => `<option value="${key}"${key === pick ? ' selected' : ''}>${labelOf(key)}</option>`)
      .join('');
    sel.value = pick;
  });
}

/** Any other remembered choice — the catalog's own order, the shelf the
 *  Library was left on. One key, so a reader's picks live together. */
function remember(name, value) {
  chosen[`!${name}`] = value;
  save(KEY, chosen);
}
function remembered(name, fallback) {
  const value = chosen[`!${name}`];
  return value === undefined ? fallback : value;
}

document.addEventListener('change', (e) => {
  const sel = e.target.closest ? e.target.closest('[data-sort-for]') : null;
  if (!sel || !SECTIONS[sel.dataset.sortFor]) return;
  const name = sel.dataset.sortFor;
  chosen[name] = sel.value;
  save(KEY, chosen);
  sel.dataset.sorted = sel.value;
  // The shelf that owns these rows redraws them; this module never touches
  // anybody else's markup.
  document.dispatchEvent(new CustomEvent('htv:sort', { detail: { name } }));
});

export {
  KEY as SORT_KEY, ORDERS, SECTIONS, choiceOf, sorted, fillSorts,
  remember, remembered, when,
};
