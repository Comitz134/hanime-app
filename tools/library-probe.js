// Device probe for the library build. Run it through webview-probe.mjs:
//
//   node tools/webview-probe.mjs tools/library-probe.js
//
// It answers four questions that only a real WebView can answer: does the nav
// switch views in place, does the heart write to the app's own storage, does
// a resolved stream land in the history, and does typing a playlist's name
// surface that playlist. Expects a clean install (no stored library).
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const q = (s) => document.querySelector(s);
  const texts = (sel) => [].map.call(document.querySelectorAll(sel), (e) => e.textContent.trim());
  const stored = () => {
    try { return JSON.parse(localStorage.getItem('htv:library:v1') || 'null'); } catch { return null; }
  };
  const out = { url: location.href };

  for (let i = 0; i < 40 && !document.querySelectorAll('#grid .card').length; i++) await sleep(500);
  out.gridCards = document.querySelectorAll('#grid .card').length;
  out.hearts = document.querySelectorAll('#grid .card .fav-btn').length;
  if (!out.gridCards) return JSON.stringify(Object.assign(out, { error: 'no grid cards' }));

  // 1. the nav switches views in place instead of scrolling to a section
  scrollTo(0, 1200);
  await sleep(200);
  out.scrolledTo = scrollY;
  q('.nav-link[data-go="library"]').click();
  await sleep(400);
  out.nav = {
    bodyView: document.body.dataset.view,
    libraryDisplay: getComputedStyle(q('.view[data-view="library"]')).display,
    browseDisplay: getComputedStyle(q('.view[data-view="browse"]')).display,
    current: texts('.nav-link[aria-current="page"]'),
    scrollY: scrollY,
  };

  // Pressing the tab you are already on must leave you where you are.
  scrollTo({ top: 900 });
  await sleep(1200);
  out.sameTab = { scrolledTo: scrollY };
  q('.nav-link[data-go="library"]').click();
  await sleep(400);
  out.sameTab.scrollY = scrollY;

  // 2. the heart files the title without opening it
  q('.nav-link[data-go="browse"]').click();
  await sleep(300);
  const slug = q('#grid .card').dataset.slug;
  q('#grid .card .fav-btn').click();
  await sleep(300);
  out.favorite = {
    slug,
    pressed: q('#grid .card .fav-btn').getAttribute('aria-pressed'),
    sheetStayedClosed: !q('#sheet').classList.contains('open'),
    saved: (stored() || {}).favorites,
  };

  // 3. it is in the library view, under its own name
  q('.nav-link[data-go="library"]').click();
  await sleep(300);
  out.library = {
    count: q('#lib-count').textContent,
    favCards: document.querySelectorAll('#fav-grid .card').length,
    favTitles: texts('#fav-grid .card-title'),
    historyNote: q('#hist-note').hidden ? null : q('#hist-note').textContent,
  };

  // 4. typing a playlist's name finds the playlist
  q('.nav-link[data-go="browse"]').click();
  await sleep(200);
  const box = q('#q');
  box.value = 'pandora';
  box.dispatchEvent(new Event('input', { bubbles: true }));
  await sleep(2500);
  out.byName = {
    heading: q('#pl-hits .sec-head h2').textContent,
    count: q('#pl-hits-count').textContent,
    hidden: q('#pl-hits').hidden,
    names: texts('#pl-hits-rail .pl-name').slice(0, 4),
    gridTotal: q('#total-count').textContent,
  };

  // 5. opening a title and resolving a stream records it in the history
  q('#search-clear').click();
  for (let i = 0; i < 30 && !document.querySelectorAll('#grid .card').length; i++) await sleep(500);
  q('#grid .card').click();
  for (let i = 0; i < 30 && !q('#vid'); i++) await sleep(500);
  out.history = {
    title: q('#sheet-body .detail-title') ? q('#sheet-body .detail-title').textContent : null,
    hasPlayer: !!q('#vid'),
    favPill: q('#sheet-body .detail-actions [data-fav]')
      ? q('#sheet-body .detail-actions [data-fav]').querySelector('.fav-label').textContent
      : null,
    saved: ((stored() || {}).history || []).map((h) => h.name),
  };

  return JSON.stringify(out);
})()
