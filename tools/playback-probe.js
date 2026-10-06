// Staged diagnostic: reports what happens at each step of opening a title, and
// returns quickly instead of waiting on anything that may never arrive.
(async () => {
  const out = {};
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  const grid = document.querySelector('#grid');
  out.gridChildren = grid ? grid.children.length : -1;
  out.skeletons = document.querySelectorAll('#grid .skeleton').length;
  out.total = (document.querySelector('#total-count') || {}).textContent;
  out.hlsLoaded = typeof window.Hls !== 'undefined';
  out.hlsSupported = typeof window.Hls !== 'undefined' ? !!window.Hls.isSupported() : null;

  const card = grid && grid.firstElementChild;
  if (!card) { out.error = 'no card'; return JSON.stringify(out, null, 1); }
  out.cardText = (card.innerText || '').replace(/\s+/g, ' ').slice(0, 40);
  card.click();

  await wait(4000);
  out.sheetOpen = !!document.querySelector('#sheet.open');
  out.sheetBodyHead = ((document.querySelector('#sheet-body') || {}).textContent || '')
    .replace(/\s+/g, ' ').slice(0, 220);
  out.vidMounted = !!document.querySelector('#vid');
  out.notes = [...document.querySelectorAll('.note')]
    .map((n) => n.textContent).filter(Boolean).slice(0, 3);

  if (!out.vidMounted) return JSON.stringify(out, null, 1);

  const vid = document.querySelector('#vid');
  out.hasSrcAttr = vid.getAttribute('src');
  out.usingMse = !out.hasSrcAttr;
  vid.muted = true;
  const p = vid.play();
  if (p && p.catch) p.catch((e) => { out.playRejected = String(e); });

  await wait(6000);
  out.t1 = +(vid.currentTime || 0).toFixed(3);
  out.w1 = vid.videoWidth;
  out.rs1 = vid.readyState;
  out.buffered1 = vid.buffered.length ? +vid.buffered.end(vid.buffered.length - 1).toFixed(2) : 0;

  await wait(6000);
  out.t2 = +(vid.currentTime || 0).toFixed(3);
  out.w2 = vid.videoWidth;
  out.rs2 = vid.readyState;
  out.buffered2 = vid.buffered.length ? +vid.buffered.end(vid.buffered.length - 1).toFixed(2) : 0;
  out.advanced = out.t2 > out.t1;
  out.paused = vid.paused;
  out.mediaErrorCode = vid.error ? vid.error.code : null;

  return JSON.stringify(out, null, 1);
})()
