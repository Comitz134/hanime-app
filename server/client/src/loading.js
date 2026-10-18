// loading.js
//
// The screens that have to wait, and what they draw while they wait.
//
// One rule runs through all of them: draw the thing that is coming, not a
// spinner in an empty box and not a word. The player draws its own 16:9 stage
// with the title's poster pushed back behind it, because that is what the
// reader is about to watch; the detail sheet draws the page it is about to
// become, using the same layout classes the real page uses, so nothing jumps
// when it arrives; a grid draws cards where cards are going; the reader draws
// page-shaped strips at the size its pages will be.
//
// The captions that do appear are the ones a reader can act on — a stage that
// says it is still looking after a few seconds is telling them the truth about
// a slow title, where "Buffering" only tells them the app is aware of time.

import { esc } from './core.js';

/* ------------------------------------------------------------------ shapes */

const line = (width = '') =>
  `<div class="skeleton sk-line${width ? ' ' + width : ''}"></div>`;

/** Poster cards, exactly the shape the grid's own cards take. */
export function skeletonCards(n) {
  return Array.from({ length: n }, () => `
    <div class="sk-card">
      <div class="skeleton"></div>
      ${line('mid')}${line('short')}
    </div>`).join('');
}

/**
 * The detail sheet while its record is in flight.
 *
 * Built out of the real page's own layout classes (`.detail-hero`,
 * `.detail-grid`, `.player-card`) rather than a copy of their geometry: the
 * skeleton is the page with its text replaced by bars, so the swap cannot
 * move anything.
 */
export function sheetSkeleton() {
  return `
    <div class="sheet-skel" role="status" aria-label="Loading this title">
      <div class="detail-hero">
        <div class="detail-poster"><div class="skeleton sk-poster"></div></div>
        <div class="detail-hero-text">
          ${line('short')}
          ${line('tall wide')}
          ${line('mid')}
          <div class="sk-chips">
            <div class="skeleton sk-chip"></div>
            <div class="skeleton sk-chip"></div>
            <div class="skeleton sk-chip"></div>
          </div>
        </div>
      </div>
      <div class="detail-grid">
        <div class="detail-main">
          <div class="player-card"><div class="skeleton sk-stage"></div></div>
          ${line('wide sk-sep')}
          ${line('mid')}
        </div>
        <div class="detail-side">
          ${line('short')}
          ${line('wide')}${line('wide')}${line('mid')}
          <div class="sk-chips">
            <div class="skeleton sk-chip"></div>
            <div class="skeleton sk-chip"></div>
          </div>
        </div>
      </div>
    </div>`;
}

/**
 * The player's stage before a stream exists — for both kinds of player.
 *
 * `cover` is what makes this a loading screen rather than a placeholder: the
 * poster the reader just clicked, blurred back behind the ring, at exactly the
 * size and ratio the video will occupy. `caption` is what the app is actually
 * doing, and it is allowed to change while the wait goes on (the films area
 * says so after a few seconds — see `stageStillLooking`).
 */
export function stageLoading({ cover = '', caption = 'Loading the player…' } = {}) {
  // The poster is an <img>, not a CSS background: a cover URL goes into the
  // document as an attribute, where `esc` can hold it, and no URL of anybody
  // else's can reach into a style block.
  const art = cover ? `<img class="stage-load-bg" src="${esc(cover)}" alt="">` : '';
  return `
    <div class="stage-load" role="status" aria-label="${esc(caption)}"${cover ? '' : ' data-empty="true"'}>
      ${art}
      <div class="ring"></div>
      <div class="cap">${esc(caption)}</div>
    </div>`;
}

/**
 * The veil over a <video> that has not drawn a frame yet.
 *
 * Same idea one level down: a video element with no frames is a black
 * rectangle, and a black rectangle reads as broken. The poster sits behind the
 * ring until `canplay`, and `pointer-events: none` keeps the controls usable
 * while it is there.
 */
export function videoWaitHtml({ cover = '' } = {}) {
  const art = cover ? `<img class="bg" src="${esc(cover)}" alt="">` : '';
  return `<div class="vid-wait" id="vid-wait" hidden>
    ${art}
    <div class="ring"></div>
  </div>`;
}

/** A list of items — a playlist, a public shelf — while its rows are in flight. */
export function listSkeleton(n = 6) {
  const row = `
    <div class="sk-row">
      <div class="skeleton sk-square"></div>
      <div class="sk-row-text">${line('mid')}${line('short')}</div>
    </div>`;
  return `<div class="sk-rows" role="status" aria-label="Loading this list">${
    Array.from({ length: n }, () => row).join('')
  }</div>`;
}

/** The manga reader's pages while the chapter is being fetched. */
export function readerSkeleton(n = 3) {
  const page = '<div class="rd-page rd-skel"><div class="skeleton sk-page"></div></div>';
  return Array.from({ length: n }, () => page).join('');
}

/* ----------------------------------------------------------------- actions */

/**
 * A stage caption that stays honest when the wait is longer than expected.
 *
 * The films resolver boots a browser to find a playlist, and a title whose
 * first door is dead takes a few seconds. Saying so is more useful than an
 * animation that looks the same whether it is working or stuck.
 */
export function stageStillLooking(slot, caption) {
  const cap = slot?.querySelector?.('.stage-load .cap');
  if (!cap) return () => {};
  const timer = setTimeout(() => { cap.textContent = caption; }, 4000);
  return () => clearTimeout(timer);
}
