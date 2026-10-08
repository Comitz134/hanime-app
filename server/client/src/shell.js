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

export { shellApi, shellCall, backOpen, menuIsOpen, updateBackState };
