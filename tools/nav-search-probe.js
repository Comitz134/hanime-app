// Device probe for the pill's search field: the navbar itself becomes the
// search box when the magnifier is tapped, the page does not move, and typing
// there answers with results.
//
//   node tools/webview-probe.mjs tools/nav-search-probe.js
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const q = (s) => document.querySelector(s);
  const shown = (s) => getComputedStyle(q(s)).display !== 'none';
  const out = {};

  for (let i = 0; i < 40 && !document.querySelectorAll('#grid .card').length; i++) await sleep(500);
  out.gridCards = document.querySelectorAll('#grid .card').length;

  const nav = q('#nav');
  const field = q('#nav-q');
  out.before = {
    searching: nav.dataset.searching || 'false',
    linksShown: shown('.nav-links'),
    fieldShown: shown('#nav-search'),
    scrollY: scrollY,
  };

  // Tap the magnifier in the pill. This is the button that used to scroll the
  // page down to the toolbar's search box.
  q('#search-toggle').click();
  await sleep(400);
  out.opened = {
    searching: nav.dataset.searching,
    expanded: q('#search-toggle').getAttribute('aria-expanded'),
    linksShown: shown('.nav-links'),
    fieldShown: shown('#nav-search'),
    focused: document.activeElement === field,
    scrollY: scrollY,
  };

  // Type where the finger already is.
  field.value = 'pandora';
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await sleep(2600);
  out.typed = {
    mirrorInToolbar: q('#q').value,
    view: document.body.dataset.view,
    hitsHidden: q('#pl-hits').hidden,
    hitsCount: q('#pl-hits-count').textContent,
    hits: [].map.call(document.querySelectorAll('#pl-hits-rail .pl-name'),
      (e) => e.textContent.trim()).slice(0, 3),
    gridTotal: q('#total-count').textContent,
    scrollY: scrollY,
  };

  // The × clears the query while there is one, then puts the links back.
  q('#nav-q-close').click();
  await sleep(500);
  out.afterFirstClose = {
    stillSearching: nav.dataset.searching,
    field: field.value,
    mirrorInToolbar: q('#q').value,
  };
  q('#nav-q-close').click();
  await sleep(300);
  out.closed = {
    searching: nav.dataset.searching,
    expanded: q('#search-toggle').getAttribute('aria-expanded'),
    linksShown: shown('.nav-links'),
    fieldShown: shown('#nav-search'),
    scrollY: scrollY,
  };

  return JSON.stringify(out);
})()
