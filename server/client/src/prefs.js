// prefs.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, $$ } from './core.js';

/* ------------------------------------------------------------------ prefs
   How the app looks is the reader's call, not the build's: theme, accent and
   motion live in localStorage under their own key, guarded exactly like the
   library — a refused or corrupt store keeps the defaults instead of taking
   the page down, and a self-update carries the choices across. */
const PREFS_KEY = 'htv:prefs:v1';
const DEFAULT_PREFS = { theme: 'dark', accent: 'amber', motion: 'full' };
const prefs = Object.assign({}, DEFAULT_PREFS);

function loadPrefs() {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    if (raw) Object.assign(prefs, JSON.parse(raw));
  } catch (e) { /* an unreadable store keeps the defaults */ }
}
function savePrefs() {
  try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); }
  catch (e) { /* a refused write leaves the choice session-only */ }
}

function prefersLight() {
  try { return window.matchMedia('(prefers-color-scheme: light)').matches; }
  catch (e) { return false; }
}

function applyPrefs() {
  // "system" is a preference, not a theme: what reaches the DOM is always one
  // of the two palettes, so every rule below has exactly one thing to match.
  const theme = prefs.theme === 'system' ? (prefersLight() ? 'light' : 'dark') : prefs.theme;
  document.body.dataset.theme = theme;
  document.body.dataset.accent = prefs.accent;
  document.body.dataset.motion = prefs.motion;
  document.documentElement.style.scrollBehavior = prefs.motion === 'reduced' ? 'auto' : '';

  $$('#theme-seg [data-theme-pick]').forEach((b) =>
    b.setAttribute('aria-pressed', String(b.dataset.themePick === prefs.theme)));
  $$('#accent-row [data-accent-pick]').forEach((b) =>
    b.setAttribute('aria-pressed', String(b.dataset.accentPick === prefs.accent)));
  const motion = $('#pref-motion');
  if (motion) motion.checked = prefs.motion === 'reduced';
}

loadPrefs();
applyPrefs();

// A device switched to light mode while the app sits open should follow.
try {
  window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
    if (prefs.theme === 'system') applyPrefs();
  });
} catch (e) { /* older engines: the choice still applies on next open */ }

$('#theme-seg').addEventListener('click', (e) => {
  const pick = e.target.closest('[data-theme-pick]');
  if (!pick) return;
  prefs.theme = pick.dataset.themePick;
  savePrefs();
  applyPrefs();
});
$('#accent-row').addEventListener('click', (e) => {
  const pick = e.target.closest('[data-accent-pick]');
  if (!pick) return;
  prefs.accent = pick.dataset.accentPick;
  savePrefs();
  applyPrefs();
});
$('#pref-motion').addEventListener('change', (e) => {
  prefs.motion = e.target.checked ? 'reduced' : 'full';
  savePrefs();
  applyPrefs();
});

export { PREFS_KEY, DEFAULT_PREFS, prefs, loadPrefs, savePrefs, prefersLight, applyPrefs };
