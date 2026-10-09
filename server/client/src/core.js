// core.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.


const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const state = {
  page: 0, perPage: 30, q: '', tags: [], brand: '', sort: 'released_at_unix:desc',
  pages: 1, total: 0, featured: [], slide: 0,
};

const api = async (path) => {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path} → ${res.status}`);
  return res.json();
};

const fmtCount = (n) => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : String(n);
const clock = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;

/* When something happened, in the words a shelf prints: "just now", "3h ago",
   and a plain date once it is old enough that hours stop meaning anything.
   A shelf that says "last read 3h ago" answers a question a row of numbers
   cannot — whether the title was picked up tonight or last spring. */
const ago = (at) => {
  const then = Number(at);
  if (!Number.isFinite(then) || then <= 0) return '';
  const mins = Math.floor((Date.now() - then) / 60000);
  if (mins < 0) return '';
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(then).toISOString().slice(0, 10);
};

export { $, $$, esc, state, api, fmtCount, clock, ago };
