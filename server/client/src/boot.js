// boot.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, $$, esc } from './core.js';
import { loadBrands, loadFeatured, loadGrid, loadTags } from './data.js';
import { loadOwnIndex } from './pl-hits.js';
import { loadSession } from './playlists.js';
import { renderLibrary } from './positions.js';
import { wirePublicPlaylists } from './public-playlists.js';
import { makeDraggable } from './rails.js';
import { fillSettings } from './settings.js';
import { showView } from './views.js';


// broken covers collapse to the title rather than a torn frame
document.addEventListener('error', (e) => {
  if (e.target.tagName !== 'IMG') return;
  const fb = e.target.parentElement?.querySelector('.card-fallback');
  if (fb) { e.target.remove(); fb.hidden = false; } else { e.target.style.visibility = 'hidden'; }
}, true);

$$('.rail').forEach(makeDraggable);

// Boot. Deliberately an async IIFE rather than top-level await: this script is
// served to an installed Android app, and the WebView that ships with Android
// 11 is Chromium 83, where top-level await in a module is a syntax error. One
// syntax error kills the whole script, so the page came up blank. Everything
// from Chromium 61 runs when it is wrapped like this.
(async () => {
  // Before any network: the settings rows are local facts about the build,
  // not something a slow catalog should hold up.
  fillSettings();
  try {
    await Promise.all([loadTags(), loadBrands(), loadFeatured()]);
    await loadGrid();
  } catch (e) {
    $('#grid').innerHTML = `<p class="note">Could not reach the proxy — ${esc(e.message)}</p>`;
  }

  // The account's own playlists are fetched once, for name search later; a
  // device with no account simply answers with an empty list.
  loadOwnIndex().catch(() => {});

  // Playlists are independent of the catalog: a failure here must not blank the grid.
  wirePublicPlaylists();
  loadSession().catch(() => {});

  showView('browse');
  renderLibrary();
})();

