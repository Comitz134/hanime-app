// menu.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $ } from './core.js';
import { menuIsOpen, updateBackState } from './shell.js';

/* The three-line button: one popover holding every section and every
   appearance choice, so the pill itself stays buttons and a phone never has
   to swipe the navbar to find the rest of the app. */
function setMenuOpen(open) {
  $('#nav-menu').hidden = !open;
  $('#menu-toggle').setAttribute('aria-expanded', String(open));
  updateBackState();
}

$('#menu-toggle').onclick = () => setMenuOpen(!menuIsOpen());

export { setMenuOpen };
