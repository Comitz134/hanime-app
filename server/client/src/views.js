// views.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $$ } from './core.js';
import { renderLibrary } from './positions.js';

/* ------------------------------------------------------------------ views */
//
// The nav switches views in place. Nothing scrolls to a section any more: the
// active item in the menu is marked, and the page comes back to the top of the
// view it just opened.

const views = $$('.view');

// The markup already names the view the page opens on. Adopting it here means
// the boot call is not a *change* — otherwise it would scroll a reader who had
// already started moving while the catalog was still loading behind them.
document.body.dataset.view =
  (views.find((v) => v.dataset.active === 'true') ?? views[0]).dataset.view;

function showView(name) {
  const target = views.find((v) => v.dataset.view === name) || views[0];
  const changed = document.body.dataset.view !== target.dataset.view;
  views.forEach((v) => {
    if (v === target) v.dataset.active = 'true';
    else v.removeAttribute('data-active');
  });
  document.body.dataset.view = target.dataset.view;

  $$('.nav-link').forEach((b) => {
    if (b.dataset.go === target.dataset.view) b.setAttribute('aria-current', 'page');
    else b.removeAttribute('aria-current');
  });

  if (target.dataset.view === 'library') renderLibrary();
  // Opening a different view starts at its top; pressing the tab you are
  // already on must not throw away your place in it.
  if (changed) scrollTo({ top: 0 });
}

export { views, showView };
