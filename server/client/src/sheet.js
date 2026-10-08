// sheet.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, $$, api, clock, esc, fmtCount } from './core.js';
import { cardHtml } from './cards.js';
import { lib, recordHistory, remember } from './library.js';
import { clearPosition, favPill, savePosition } from './positions.js';
import { dropClosedEntry, isApplying, pushNav } from './nav-history.js';
import { playlistsForVideo } from './public-playlists.js';
import { shellCall, updateBackState } from './shell.js';
import { views } from './views.js';

/* ------------------------------------------------------------------ detail */

let activeHls = null;
/** The video and the title it belongs to, so leaving can remember them. */
let activeVid = null;
let activeSlug = null;

/** What the sheet is showing — kind, its id, and the chapter if the reader
 *  is open — so the back stack can reopen exactly this later. */
let sheetOrigin = null;

const sheetState = () => ($('#sheet').classList.contains('open') ? sheetOrigin : null);

/** The reader moves between chapters; the origin moves with it. */
const setSheetOrigin = (origin) => { sheetOrigin = origin; };

/** The detail page is one surface for both areas: opening it is shared. */
function openSheet(kind, ref, chapter) {
  // Recorded before anything on screen changes: a snapshot taken after the
  // open would describe the sheet that is already there.
  pushNav();
  sheetOrigin = kind ? { kind, ref: String(ref), chapter: chapter ? String(chapter) : null } : null;
  const sheet = $('#sheet');
  sheet.classList.add('open');
  document.body.style.overflow = 'hidden';
  sheet.scrollTop = 0;
  updateBackState();
  $('#sheet-body').innerHTML = '<div class="center-spin"><div class="spinner"></div></div>';
  $('#sheet-count').hidden = true;
  return sheet;
}

function closeSheet() {
  // Leaving is the single best moment to remember where the video got to: the
  // next five-second tick may never arrive, and this is exactly where a person
  // stopped watching.
  if (activeSlug && activeVid) savePosition(activeSlug, activeVid);
  shellCall('setPlaying', false);

  const vid = $('#vid');
  if (vid) { vid.pause(); vid.removeAttribute('src'); vid.load(); }
  if (activeHls) { activeHls.destroy(); activeHls = null; }
  // The anime area plays through an embed frame; closing the page has to stop
  // that too, or audio keeps running behind the view the reader came back to.
  const frame = document.getElementById('lx-frame');
  if (frame) { frame.removeAttribute('src'); frame.remove(); }
  activeVid = null;
  activeSlug = null;
  sheetOrigin = null;
  $('#sheet').classList.remove('open');
  document.body.style.overflow = '';
  document.body.dataset.pip = 'false';
  // Closing is a way of navigating too. When it lands on the state at the
  // top of the stack, the entry that opened the sheet cancels out; a back
  // press spent on an already-visible state is a press that did nothing.
  // (Applying a snapshot — a real back press — has already popped.)
  if (!isApplying()) dropClosedEntry();
  updateBackState();
}

async function openVideo(slug) {
  openSheet('video', slug);

  let v;
  try {
    v = await api('/api/videos/' + encodeURIComponent(slug));
  } catch (e) {
    $('#sheet-body').innerHTML = `<p class="note">Could not load this title — ${esc(e.message)}</p>`;
    return;
  }

  // The heart on this page and any later history entry both draw from the
  // record the catalog just returned, so it is filed before anything renders.
  remember(v);

  $('#sheet-count').hidden = false;
  $('#sheet-count').textContent = fmtCount(v.views) + ' views';

  const tags = (v.tags ?? []).map((t) => `<span class="hero-tag">${esc(t)}</span>`).join('');
  const desc = esc((v.description ?? '').replace(/<[^>]+>/g, ' ').replace(/\s{2,}/g, ' ').trim());

  // The page is laid out the way a series page reads: the title and its facts
  // in a header across the top, the player and the reading matter side by
  // side below it — not a card floating over the page it came from.
  $('#sheet-body').innerHTML = `
    <div class="detail-hero">
      <div class="detail-poster"><img src="${esc(v.cover ?? '')}" alt="" loading="lazy"></div>
      <div class="detail-hero-text">
        <div class="detail-kicker">${esc(v.brand ?? 'Unknown studio')}</div>
        <h2 class="detail-title">${esc(v.name)}</h2>
        <div class="detail-meta">${fmtCount(v.views)} views · ${fmtCount(v.likes)} likes${
          v.released_at ? ' · ' + esc(v.released_at.slice(0, 4)) : ''}</div>
        <div class="detail-actions">${favPill(slug)}</div>
      </div>
    </div>
    <div class="detail-grid">
      <div class="detail-main">
        <div class="player-card">
          <div id="player-slot"><div class="center-spin"><div class="spinner"></div></div></div>
        </div>
        <div id="in-playlists" hidden></div>
        <section id="detail-recs" hidden>
          <div class="sec-head"><div class="sec-head-l"><h2 id="rec-head">More from this studio</h2></div></div>
          <div class="rail" id="rec-rail" aria-label="Recommended"></div>
        </section>
      </div>
      <aside class="detail-side">
        <div class="mal-slot" data-mal-kind="anime" data-mal-title="${esc(v.name)}"></div>
        <div class="detail-tags">${tags}</div>
        <p class="detail-desc">${desc}</p>
      </aside>
    </div>`;

  // Reverse lookup: which public playlists carry this title. Independent of
  // playback, so a failure here never delays the stream.
  playlistsForVideo(slug).catch(() => {});

  // Recommended reads from the same catalog the grid uses: the studio that
  // made this one, so the rail is never a guess.
  loadStudioRecs(v.brand, slug).catch(() => {});

  await mountPlayer(slug);
}

async function loadStudioRecs(brand, slug) {
  if (!brand) return;
  const data = await api(`/api/videos?brand=${encodeURIComponent(brand)}&per_page=12`);
  const items = (data.data ?? []).filter((v) => v.slug !== slug);
  if (!items.length) return;
  const rail = $('#rec-rail');
  const recs = $('#detail-recs');
  if (!rail || !recs) return;
  $('#rec-head').textContent = `More from ${brand}`;
  rail.innerHTML = items.map((v) => cardHtml(v)).join('');
  recs.hidden = false;
}

async function mountPlayer(slug) {
  const slot = $('#player-slot');
  let sources;
  try {
    ({ sources } = await api(`/api/videos/${encodeURIComponent(slug)}/sources`));
  } catch (e) {
    slot.innerHTML = `<p class="note">Could not resolve a stream — ${esc(e.message)}</p>`;
    return;
  }
  if (!sources?.length) {
    slot.innerHTML = '<p class="note">No playable source was returned for this entry.</p>';
    return;
  }

  // A title counts as watched once a stream for it actually resolves, so a
  // page that never played is not left behind in the history.
  recordHistory(slug);

  const pipWanted = shellCall('pictureInPictureSupported') === true;

  slot.innerHTML = `
    <video id="vid" controls playsinline preload="metadata"></video>
    <div class="quality">${sources
      .map((s, i) => `<button class="q-btn" data-i="${i}" aria-pressed="${i === 0}">${esc(s.label)}</button>`)
      .join('')}</div>
    <div class="lib-tools">
      ${pipWanted ? '<button class="chip" id="pip-btn" type="button">Picture in picture</button>' : ''}
      <span class="sec-count" id="player-note"></span>
    </div>`;

  const vid = $('#vid');
  activeVid = vid;
  activeSlug = slug;

  // Chrome and Firefox ship no native HLS demuxer; Safari and every iOS browser
  // do. Prefer Media Source Extensions when they exist, because some Android
  // WebViews answer `maybe` to canPlayType('application/vnd.apple.mpegurl')
  // while implementing no demuxer at all — the element then reports
  // readyState 4 and never decodes a single frame. Leaving the native route for
  // browsers with no MSE keeps iOS on its hardware path.
  const attach = (url) => {
    if (activeHls) { activeHls.destroy(); activeHls = null; }
    if (window.Hls?.isSupported()) {
      activeHls = new window.Hls({ enableWorker: true, lowLatencyMode: false });
      activeHls.on(window.Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return;
        slot.insertAdjacentHTML('beforeend', `<p class="note">Playback error — ${esc(data.details)}</p>`);
      });
      activeHls.loadSource(url);
      activeHls.attachMedia(vid);
      return;
    }
    vid.src = url;
  };

  attach(sources[0].url);

  // ---- picking up where this title was left ------------------------------
  const saved = lib.positions[slug];
  if (saved && saved.t > 5) {
    vid.addEventListener('loadedmetadata', () => {
      try { vid.currentTime = saved.t; } catch (e) { /* seek refused: start over */ }
      const note = $('#player-note');
      if (note) note.textContent = `resumed from ${clock(saved.t)}`;
    }, { once: true });
  }

  let lastSaved = 0;
  vid.addEventListener('timeupdate', () => {
    const now = Date.now();
    if (now - lastSaved < 5000) return;
    lastSaved = now;
    savePosition(slug, vid);
  });
  vid.addEventListener('ended', () => clearPosition(slug));

  // The shell keeps the screen awake for a video, shrinks the app to the small
  // window when the user leaves, and shapes that window like the video.
  const tellAspect = () => {
    if (vid.videoWidth) shellCall('setVideoAspect', vid.videoWidth, vid.videoHeight);
  };
  vid.addEventListener('loadedmetadata', tellAspect);
  vid.addEventListener('play', () => { shellCall('setPlaying', true); tellAspect(); });
  vid.addEventListener('pause', () => { shellCall('setPlaying', false); savePosition(slug, vid); });

  const pipBtn = $('#pip-btn');
  if (pipBtn) pipBtn.onclick = () => shellCall('enterPip');

  $$('.q-btn', slot).forEach((btn) => {
    btn.onclick = () => {
      const pos = vid.currentTime, wasPlaying = !vid.paused;
      vid.addEventListener('loadedmetadata', () => {
        if (pos > 0) vid.currentTime = pos;
        if (wasPlaying) vid.play().catch(() => {});
      }, { once: true });
      attach(sources[Number(btn.dataset.i)].url);
      $$('.q-btn', slot).forEach((b) => { b.setAttribute('aria-pressed', String(b === btn)); });
    };
  });
}

export { activeHls, activeVid, activeSlug, closeSheet, openSheet, openVideo, mountPlayer, sheetState, setSheetOrigin };
