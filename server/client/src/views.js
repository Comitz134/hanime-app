// views.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $$ } from './core.js';
import { renderMalShelves } from './mal-library.js';
import { pushNav } from './nav-history.js';
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
  // Recorded while the screen still holds the view being left, so back has
  // somewhere to return to. A restore calls this too, and history does not
  // record itself.
  if (changed) pushNav();
  views.forEach((v) => {
    if (v === target) v.dataset.active = 'true';
    else v.removeAttribute('data-active');
  });
  document.body.dataset.view = target.dataset.view;

  $$('.nav-link').forEach((b) => {
    if (b.dataset.go === target.dataset.view) b.setAttribute('aria-current', 'page');
    else b.removeAttribute('aria-current');
  });

  // Both halves of the Library refill on the way in: the device's own
  // records, and the account's shelves — which paint from the copy on the
  // device first, so opening the view is never a wait.
  if (target.dataset.view === 'library') {
    renderLibrary();
    renderMalShelves();
  }
  // Opening a different view starts at its top; pressing the tab you are
  // already on must not throw away your place in it.
  if (changed) scrollTo({ top: 0 });
}

export { views, showView };
