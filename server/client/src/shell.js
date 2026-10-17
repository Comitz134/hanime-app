// shell.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, state } from './core.js';
import { setMenuOpen } from './menu.js';
import { depth, goBack } from './nav-history.js';
import { closeSheet } from './sheet.js';
import { setSearchOpen } from './wiring.js';

/* ------------------------------------------------------------------ shell */
//
// Inside the Android app the page is given a `Shell` object (ShellBridge in
// MainActivity.java). In a plain browser there is none, so every call is
// guarded — the same file is served to both.

const shellApi = () => {
  try { return window.Shell ?? null; } catch (e) { return null; }
};

function shellCall(name, ...args) {
  const shell = shellApi();
  if (!shell || typeof shell[name] !== 'function') return undefined;
  try { return shell[name](...args); } catch (e) { return undefined; }
}

/* Whether the shell can answer a call, asked before the call is made. The
   return value of shellCall cannot answer it: a Java void method comes back as
   undefined, exactly like a method that is not there, and the difference is
   the whole question for anything with a second way of doing the same job. */
function shellCan(name) {
  const shell = shellApi();
  return !!shell && typeof shell[name] === 'function';
}

/* Back belongs to whatever is on screen, and only this file knows what that is.
   The shell asks before it hands the press over: the flag below is set on a
   change of state rather than on every render, so typing does not talk to
   Java. "Something to go back to" now includes the history stack — a step
   deeper than the screen the app opened on. */
let backOpen = null;

/* The menu is a thing on screen like the sheet and the search field, so it is
   part of the same state: back closes it before it is allowed to leave. */
function menuIsOpen() {
  return $('#menu-toggle').getAttribute('aria-expanded') === 'true';
}

function updateBackState() {
  const open = depth() > 0
    || $('#sheet').classList.contains('open')
    || menuIsOpen()
    || $('#nav').dataset.searching === 'true';
  if (open === backOpen) return;
  backOpen = open;
  shellCall('setBackEnabled', open);
}

/* One press, one step — and always an answer. The transient things (search,
   menu) close first because they are not pages; then the history stack walks
   back a step the way a browser's back does, until it reaches the state the
   app booted in, where it simply stops. It never answers "nothing": the shell
   takes that to mean the press is free to leave the app, and back must never
   leave the app. */
window.__shellBack = () => {
  if ($('#sheet').classList.contains('open')) {
    if (!goBack()) closeSheet();   // no step to walk — just let the page go
    return true;
  }
  if (menuIsOpen()) { setMenuOpen(false); return true; }
  if ($('#nav').dataset.searching === 'true') { setSearchOpen(false); return true; }
  goBack();
  return true;
};

/* The shell says when the small window opens, so the page can show only the
   video in it. */
window.__shellPip = (on) => { document.body.dataset.pip = String(!!on); };

/* ------------------------------------------------------------------ insets
   Where the phone's own bars are, as custom properties the stylesheets read.

   The app draws under the status and navigation bars — that is what makes a
   phone's screen the whole screen instead of a frame with strips of its own
   background above and below. What the page needs in exchange is the size of
   those bars, and there are two answers: the browser's
   `env(safe-area-inset-*)`, which is the display *cutout*, and the shell's own
   measurement, which is the real bar. On a phone with a hole in its screen at
   the top the first is right; on a phone whose status bar is an ordinary band
   it is zero while the bar is very much there, and a header laid out on it
   alone sits under the clock. So the shell's number is published here and the
   stylesheets take the larger of the two.

   The shell's numbers are the window's own, which are device pixels; a
   stylesheet is in CSS pixels, and the two differ by exactly the display
   density — 94 physical pixels of status bar is 34 of page on a phone at
   2.75×. Handing the physical number straight to CSS is not a small error:
   it reserves nearly three times the bar's height at the top and pushes every
   fixed control further down the screen the denser the phone is.

   A bar the shell has taken away arrives as 0, so there is nothing to decide
   here — a fullscreen view gets the whole screen.

   Read again on every resize and whenever the page comes back to the front:
   unfolding a foldable is a resize, rotating is a resize, and a fullscreen
   view takes the bars away without either.
*/
function applyShellInsets() {
  const raw = shellCall('systemBars');
  const [top, bottom, visible] = String(raw ?? '').split(',');
  const density = window.devicePixelRatio > 0 ? window.devicePixelRatio : 1;
  const px = (value) => {
    const device = Number(value);
    return Number.isFinite(device) && device > 0 ? Math.round(device / density) : 0;
  };
  const style = document.documentElement.style;
  style.setProperty('--shell-inset-top', `${px(top)}px`);
  style.setProperty('--shell-inset-bottom', `${px(bottom)}px`);
  // Whether a bar is on screen at all, for the few places where the *cutout*
  // answer is the wrong one. `env(safe-area-inset-top)` keeps reporting the
  // notch after the status bar is hidden — the hole in the glass does not move
  // — so the larger of the two would keep a reading pill 35px down a screen it
  // has all of. The reader's chrome takes the bar's word instead.
  document.documentElement.dataset.shellBars = visible === 'false' ? 'hidden' : 'shown';
}

window.__shellInsets = applyShellInsets;

if (shellCan('systemBars')) {
  applyShellInsets();
  // Resize covers rotation and a foldable unfolding; the visible check covers
  // coming back from the app switcher, where bars can have changed shape.
  window.addEventListener('resize', applyShellInsets);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) applyShellInsets(); });
  // Fullscreen is asked for by the page and applied by the shell a moment
  // later, so the answer is read after it has had that moment.
  document.addEventListener('fullscreenchange', () => setTimeout(applyShellInsets, 250));
}

export { shellApi, shellCall, shellCan, applyShellInsets, backOpen, menuIsOpen, updateBackState };
