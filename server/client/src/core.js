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

/* Fullscreen the page asks for itself.

   The shells (Android's WebView, the desktop window) hand a page's element
   fullscreen to the platform, and both of them are tested that way. What they
   cannot do is be a control: chrome's own button lived inside the video
   element, and the embeds behind the films area ship a permissions policy that
   denies fullscreen to their own document — `document.fullscreenEnabled` is
   false inside the frame, so their corner button is inert no matter what the
   page around it allows. A button out here expands the frame's wrapper
   instead, and the permission the parent holds does the rest.

   Returns false when this document cannot go fullscreen at all, so the caller
   can leave the button out rather than render a dead one. */
function bindFullscreen(btn, target, label = 'Fullscreen') {
  if (!btn) return false;
  const here = document.fullscreenEnabled !== false;
  if (!here || typeof (target()?.requestFullscreen) !== 'function') {
    btn.remove();
    return false;
  }
  const on = () => document.fullscreenElement != null;
  const paint = () => {
    btn.textContent = on() ? 'Exit fullscreen' : label;
    btn.setAttribute('aria-pressed', String(on()));
  };
  btn.onclick = () => {
    if (on()) {
      document.exitFullscreen?.()?.catch?.(() => {});
      return;
    }
    const el = target();
    // `navigationUI: 'hide'` is what stops a phone browser drawing its toolbar
    // over the video; a shell that ignores the hint loses nothing by it.
    el?.requestFullscreen?.({ navigationUI: 'hide' })?.catch?.(() => {
      btn.textContent = 'Fullscreen unavailable';
    });
  };
  document.addEventListener('fullscreenchange', paint);
  // An exit control *inside* the element that goes full: the button that
  // started this is outside it, and while the wrapper fills the screen the
  // rest of the document is not rendered at all, so it cannot be pressed.
  const exit = target()?.querySelector?.('[data-fs-exit]');
  if (exit) {
    exit.addEventListener('click', () => document.exitFullscreen?.()?.catch?.(() => {}));
  }
  paint();
  return true;
}

/* The stage an embed plays on: the frame itself, the fullscreen chip, and the
   exit control that lives *inside* the frame (while the wrapper fills the
   screen nothing outside it is drawn, least of all the chip that started it).

   Both embed areas — the normal anime one and films & series — ask for this
   rather than carrying their own copy, because the two bugs that made this
   necessary were identical in both: the embed's own corner button is inert
   wherever the host denies fullscreen to its own document, and the page that
   owns the frame is the only party that can still do something about it. */
const embedStageHtml = () => `
      <div class="lx-frame"><iframe id="lx-frame" title="Player"
        allow="autoplay; fullscreen; picture-in-picture; encrypted-media"
        allowfullscreen referrerpolicy="origin"></iframe>
        <button class="fs-exit" type="button" data-fs-exit>Exit fullscreen</button>
      </div>
      <div class="lib-tools"><button class="chip" data-player-fs type="button">Fullscreen</button></div>`;

/** Wire the stage's controls and hand back the frame, ready for a src. */
const mountEmbedStage = (slot) => {
  bindFullscreen($('[data-player-fs]', slot), () => $('.lx-frame', slot));
  return $('#lx-frame', slot);
};

export { $, $$, esc, state, api, fmtCount, clock, ago, bindFullscreen, embedStageHtml, mountEmbedStage };
