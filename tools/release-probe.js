// Runs inside the app's page (release build) and measures the three things the
// standalone app has to do: browse, show the bundled playlists, and play video.
(async () => {
  const out = {};
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---------------------------------------------------------------- browse
  out.total = (document.querySelector('#total-count') || {}).textContent;
  out.gridCards = document.querySelectorAll('#grid > *').length;
  out.firstCard = ((document.querySelector('#grid > *') || {}).innerText || '')
    .replace(/\s+/g, ' ').slice(0, 46);

  // ------------------------------------------------------------ playlists
  out.plCards = document.querySelectorAll('.pl-card').length;
  out.plNote = ((document.querySelector('#pl-note') || {}).textContent || '').trim().slice(0, 120);
  out.notes = [...document.querySelectorAll('.note')]
    .map((n) => n.textContent.replace(/\s+/g, ' ').trim()).filter(Boolean).slice(0, 4);

  // -------------------------------------------------------------- playback
  const grid = document.querySelector('#grid');
  if (grid && grid.firstElementChild) {
    grid.firstElementChild.click();
    for (let i = 0; i < 24 && !document.querySelector('#vid'); i++) await wait(500);
    const vid = document.querySelector('#vid');
    if (vid) {
      out.playback = {};
      out.playback.mounted = true;
      out.playback.mse = String(vid.getAttribute('src') || '').startsWith('blob:');
      vid.muted = true;
      const p = vid.play();
      if (p && p.catch) p.catch((e) => { out.playback.playRejected = String(e); });
      await wait(6000);
      out.playback.t1 = +(vid.currentTime || 0).toFixed(3);
      out.playback.w1 = vid.videoWidth;
      out.playback.rs1 = vid.readyState;
      await wait(6000);
      out.playback.t2 = +(vid.currentTime || 0).toFixed(3);
      out.playback.w2 = vid.videoWidth;
      out.playback.advanced = out.playback.t2 > out.playback.t1;
      out.playback.paused = vid.paused;
      out.playback.buffered = vid.buffered.length
        ? +vid.buffered.end(vid.buffered.length - 1).toFixed(2) : 0;
      out.playback.mediaError = vid.error ? vid.error.code : null;
    } else {
      out.playback = { error: 'no <video> mounted' };
    }
  } else {
    out.playback = { error: 'grid empty' };
  }

  return JSON.stringify(out, null, 1);
})()
