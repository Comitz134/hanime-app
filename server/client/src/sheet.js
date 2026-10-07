// sheet.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, $$, api, clock, esc, fmtCount } from './core.js';
import { lib, recordHistory, remember } from './library.js';
import { clearPosition, favPill, savePosition } from './positions.js';
import { playlistsForVideo } from './public-playlists.js';
import { shellCall, updateBackState } from './shell.js';
import { views } from './views.js';

/* ------------------------------------------------------------------ detail */

let activeHls = null;
/** The video and the title it belongs to, so leaving can remember them. */
let activeVid = null;
let activeSlug = null;

function closeSheet() {
  // Leaving is the single best moment to remember where the video got to: the
  // next five-second tick may never arrive, and this is exactly where a person
  // stopped watching.
  if (activeSlug && activeVid) savePosition(activeSlug, activeVid);
  shellCall('setPlaying', false);

  const vid = $('#vid');
  if (vid) { vid.pause(); vid.removeAttribute('src'); vid.load(); }
  if (activeHls) { activeHls.destroy(); activeHls = null; }
  activeVid = null;
  activeSlug = null;
  $('#sheet').classList.remove('open');
  document.body.style.overflow = '';
  document.body.dataset.pip = 'false';
  updateBackState();
}

async function openVideo(slug) {
  const sheet = $('#sheet');
  sheet.classList.add('open');
  document.body.style.overflow = 'hidden';
  sheet.scrollTop = 0;
  updateBackState();
  $('#sheet-body').innerHTML = '<div class="center-spin"><div class="spinner"></div></div>';
  $('#sheet-count').hidden = true;

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

  $('#sheet-body').innerHTML = `
    <div class="player-card">
      <div id="player-slot"><div class="center-spin"><div class="spinner"></div></div></div>
    </div>
    <div class="detail-head">
      <h2 class="detail-title">${esc(v.name)}</h2>
      <div class="detail-meta">${esc(v.brand ?? 'Unknown studio')} · ${fmtCount(v.views)} views · ${fmtCount(v.likes)} likes${
        v.released_at ? ' · ' + esc(v.released_at.slice(0, 4)) : ''}</div>
      <div class="detail-actions">${favPill(slug)}</div>
      <div class="detail-tags">${(v.tags ?? []).map((t) => `<span class="hero-tag">${esc(t)}</span>`).join('')}</div>
      <p class="detail-desc">${esc((v.description ?? '').replace(/<[^>]+>/g, ' ').replace(/\s{2,}/g, ' ').trim())}</p>
    </div>
    <div id="in-playlists" hidden></div>`;

  // Reverse lookup: which public playlists carry this title. Independent of
  // playback, so a failure here never delays the stream.
  playlistsForVideo(slug).catch(() => {});

  await mountPlayer(slug);
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

export { activeHls, activeVid, activeSlug, closeSheet, openVideo, mountPlayer };
