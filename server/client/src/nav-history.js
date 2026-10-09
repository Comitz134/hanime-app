// nav-history.js — the app's own back stack.
//
// The phone's back button walks this the way a PC browser's back walks its
// history: one step per press, through every view switch, opened title and
// reader page, down to the state the app booted in — where it stops. It is a
// stack of snapshots taken *before* each move, so popping one restores what
// was on screen, scroll position included.
//
// Deliberately not history.pushState: the WebView keeps its own history
// separately, and two stacks that both claim to be "back" disagree the first
// time they differ (the old client was bitten by exactly this). One stack,
// owned here, answered by window.__shellBack — and because a press at the
// bottom is still an answer, back never reaches the point where the shell
// would leave the app.

import { openAnime } from './anime.js';
import { openManga } from './manga.js';
import { openShow } from './movies.js';
import { openPlaylist } from './playlists.js';
import { openPublicPlaylist } from './public-playlists.js';
import { closeSheet, openVideo, sheetState } from './sheet.js';
import { updateBackState } from './shell.js';
import { showView } from './views.js';

/** States to return to, oldest first; the current state is the live screen. */
let past = [];
/** True while a snapshot is being applied — history does not record itself. */
let applying = false;

const depth = () => past.length;
const isApplying = () => applying;

/** The screen as it is right now: where we are, what is open, how far down. */
function snapshot() {
  return {
    view: document.body.dataset.view,
    sheet: sheetState(),
    y: scrollY | 0,
  };
}

/** Record the current screen before navigating away from it. */
function pushNav() {
  if (applying) return;
  past.push(snapshot());
  updateBackState();
}

/**
 * The sheet closed by something other than a back press (a chip that jumps to
 * another section, say). If the top of the stack is where closing landed, the
 * entry that opened the sheet cancels out — otherwise the next back press
 * would be spent restoring a state that is already on screen.
 */
function dropClosedEntry() {
  if (applying) return;
  const top = past[past.length - 1];
  if (top && !top.sheet && top.view === document.body.dataset.view) past.pop();
}

function openFor(s) {
  if (s.kind === 'video') openVideo(s.ref);
  else if (s.kind === 'anime') openAnime(s.ref, s.chapter ? Number(s.chapter) : 1);
  else if (s.kind === 'manga') openManga(s.ref, s.chapter ?? null);
  else if (s.kind === 'show') openShow(s.ref, s.chapter ?? null);
  else if (s.kind === 'playlist') openPlaylist(s.ref);
  else if (s.kind === 'public') openPublicPlaylist(s.ref);
}

/** Put a snapshot back on screen: sheet first, then the view under it. */
function apply(s) {
  const cur = sheetState();
  if (!s.sheet) {
    if (cur) closeSheet();
  } else if (!cur || cur.kind !== s.sheet.kind || cur.ref !== s.sheet.ref
      || (cur.chapter ?? null) !== (s.sheet.chapter ?? null)) {
    openFor(s.sheet);
  }

  const changed = document.body.dataset.view !== s.view;
  showView(s.view);
  // A browser returns you to where you were, not to the top of the page.
  if (changed) scrollTo({ top: s.y || 0 });
}

/** One step backwards. False only when there is no step left to take. */
function goBack() {
  if (applying) return false;
  const s = past.pop();
  if (!s) return false;
  applying = true;
  try {
    apply(s);
  } finally {
    applying = false;
  }
  updateBackState();
  return true;
}

export { depth, dropClosedEntry, goBack, isApplying, pushNav, snapshot };
