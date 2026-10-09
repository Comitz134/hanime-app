// The bundle's entry point. esbuild starts here; evaluation order follows this
// import list as a partial order — a module's own imports are hoisted before
// its body, so cross-module dependencies can pull a module forward.
//
// That is safe because of one rule the sections keep: no module's top-level
// code calls a function from another module. Top-level statements are DOM
// listener registrations, plain initialisers and the boot IIFE in boot.js,
// which is listed last and imports everything it drives.
// Ad guard first: it must patch the DOM before any other module's top-level
// code runs — vendor chunks included (see ad-guard.js for what it stops).
import './ad-guard.js';
import './core.js';
import './prefs.js';
import './sorts.js';
import './shell.js';
import './nav-history.js';
import './hero.js';
import './cards.js';
import './data.js';
import './public-playlists.js';
import './playlists.js';
import './sheet.js';
import './activity.js';
import './library.js';
import './positions.js';
import './pl-hits.js';
import './views.js';
import './rails.js';
import './menu.js';
import './settings.js';
import './mal.js';
import './anime.js';
import './manga.js';
import './movies.js';
import './mal-library.js';
import './wiring.js';
import './boot.js';
