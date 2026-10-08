// Guards the web client, which is bundled by esbuild from client/ sources.
// Assertions run against the committed bundle (public/index.html) — the file
// that actually ships — so they are written against code and structure, never
// against the bundler's comment or quote style.
//
// This exists because of a real bug: a CSS block was pasted inside the
// <script> tag while adding the public-playlist section. The markup looked
// fine, the stylesheet was untouched, and the only symptom was a single
// "SyntaxError: Unexpected token '.'" in the browser console that killed the
// entire client. Nothing on the server caught it. These tests do.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CLIENT = path.resolve(HERE, '../public/index.html');
const html = fs.readFileSync(CLIENT, 'utf8');

function inlineScripts(source) {
  const out = [];
  const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g;
  for (const m of source.matchAll(re)) out.push(m[1]);
  return out;
}

function stylesheets(source) {
  const out = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/g;
  for (const m of source.matchAll(re)) out.push(m[1]);
  return out;
}

test('the client has exactly one inline script and one stylesheet', () => {
  assert.equal(inlineScripts(html).length, 1);
  assert.equal(stylesheets(html).length, 1);
});

test('every inline script parses as an ES module', () => {
  const scripts = inlineScripts(html);
  assert.ok(scripts.length > 0, 'no inline script found — did the client move?');

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'htv-client-'));
  try {
    scripts.forEach((body, i) => {
      const file = path.join(dir, `inline-${i}.mjs`);
      fs.writeFileSync(file, body);
      // --check parses without executing; a CSS brace or stray token thrown in
      // here fails loudly instead of silently blanking the page.
      execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' });
    });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('no CSS leaks into the script and no JS leaks into the stylesheet', () => {
  for (const css of stylesheets(html)) {
    assert.ok(!css.includes('function '), 'a function body ended up in the stylesheet');
    assert.ok(!css.includes('=>'), 'an arrow function ended up in the stylesheet');
  }
  for (const js of inlineScripts(html)) {
    // A selector block such as `.pp-owner {` is the shape that got pasted in.
    assert.ok(!/^\s*\.[a-z-]+\s*\{/m.test(js), 'a CSS rule ended up inside the script');
    assert.ok(!js.includes('{{'), 'template braces look doubled');
  }
});

test('every element id the client queries exists in its markup', () => {
  const script = inlineScripts(html)[0];
  const styled = stylesheets(html)[0];

  // Ids the script reaches for by selector, e.g. $('#pp-rail'), getElementById,
  // or document.getElementById.
  const queried = new Set();
  for (const m of script.matchAll(/\$\(\s*'#([A-Za-z0-9_-]+)'/g)) queried.add(m[1]);
  for (const m of script.matchAll(/getElementById\(\s*'([A-Za-z0-9_-]+)'/g)) queried.add(m[1]);
  for (const m of script.matchAll(/\$\(\s*`#([A-Za-z0-9_-]+)`/g)) queried.add(m[1]);

  const defined = new Set();
  for (const m of html.matchAll(/\sid="([A-Za-z0-9_-]+)"/g)) defined.add(m[1]);

  const missing = [...queried].filter((id) => !defined.has(id));
  assert.deepEqual(missing, [], `the client queries ids that do not exist: ${missing.join(', ')}`);

  // The public-playlist section is the point of this change; make sure its
  // moving parts are present and not accidentally renamed.
  for (const id of ['public-playlists', 'pp-q', 'pp-rail', 'pp-note', 'pp-count', 'pp-clear', 'pp-more']) {
    assert.ok(defined.has(id), `missing #${id}`);
  }
  assert.ok(styled.includes('.pp-owner'), 'public-playlist card styles are not in the stylesheet');
  assert.ok(!styled.includes('.pp-tag em {') || styled.includes('button.pp-tag'), 'tag chip styles malformed');
});

test('the nav switches views, and every view it points at exists', () => {
  // The nav used to be three anchors into one long page. It is now a set of
  // buttons, and the failure mode of a typo is silent: the button does nothing
  // and the reader is left staring at the previous view.
  const targets = [...html.matchAll(/data-go="([a-z]+)"/g)].map((m) => m[1]);
  const views = [...html.matchAll(/data-view="([a-z]+)"/g)].map((m) => m[1]);

  assert.deepEqual(targets, ['anime', 'browse', 'genres', 'studios', 'playlists', 'library', 'settings']);
  for (const t of targets) {
    assert.ok(views.includes(t), `the nav points at a view that does not exist: ${t}`);
  }
  // Exactly one view is active on load, or the page opens blank — and it is
  // the anime area, which is what the reader asked to open on.
  assert.equal((html.match(/data-view="anime" data-active="true"/g) || []).length, 1);
  assert.equal((html.match(/data-view="[a-z]+" data-active="true"/g) || []).length, 1);
  const script0 = inlineScripts(html)[0];
  assert.ok(/showView\((['"])anime\1\)/.test(script0), 'the boot does not open on the anime area');
  // The opening shelf cannot wait for a menu click that never comes.
  assert.ok(script0.includes('ensureAnime()'), 'the opening anime shelf is never loaded');
  // And nothing may scroll to a section any more.
  assert.ok(!/href="#(browse|genres|studios)"/.test(html), 'the nav still anchors into the page');

  for (const id of ['library', 'lib-q', 'lib-clear', 'lib-search-shell', 'lib-warn',
    'fav-grid', 'fav-count', 'fav-note', 'hist-grid', 'hist-count', 'hist-note',
    'hist-clear', 'pl-hits', 'pl-hits-rail', 'pl-hits-count']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
});

test('the client never scrolls to a control, it moves the control to the reader', () => {
  // Two complaints led here, both real. The nav used anchors into one long
  // page, so "Studios" flung the page down to the studios rail. Then tapping
  // the search icon focused the toolbar's input — and focusing an element
  // scrolls it into view, so the page moved again. The pill now turns into a
  // search field in place, and nothing keeps a scroll-into-view path.
  const script = inlineScripts(html)[0];
  assert.ok(!script.includes('scrollIntoView'), 'the client scrolls to a section again');

  const focusCalls = [...script.matchAll(/\.focus\(([^)]*)\)/g)];
  assert.ok(focusCalls.length > 0, 'no focus() calls found — did the wiring move?');
  for (const call of focusCalls) {
    assert.ok(
      /preventScroll:\s*true/.test(call[1]),
      `focus() without preventScroll would jump the page: ${call[0]}`,
    );
  }

  for (const id of ['nav-search', 'nav-q', 'nav-q-close']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  assert.ok(
    html.includes('.nav[data-searching="true"] .nav-search'),
    'the pill never turns into a search field',
  );
});

test('the library is stored under one guarded key', () => {
  // Favorites and history are the only state the app keeps. A storage that
  // refuses to write (private mode, a full quota) must degrade to a session
  // list with a visible warning, not take the whole client down with it.
  const script = inlineScripts(html)[0];
  assert.ok(/try \{[^}]*localStorage\.getItem/.test(script), 'the localStorage read is unguarded');
  assert.ok(/try \{[^}]*localStorage\.setItem/.test(script), 'the localStorage write is unguarded');
  assert.ok(script.includes("htv:library:v1"), 'the library key moved; devices would lose their list');
  assert.ok(script.includes('libSaved'), 'a refused write is never surfaced to the reader');
});

test('the inline script has no top-level await, which breaks older WebViews', () => {
  // This is not hypothetical. The client is served to an installed Android app,
  // and the WebView bundled with Android 11 is Chromium 83 — top-level await in
  // a module needs 89. It failed there as "Uncaught SyntaxError: Unexpected
  // reserved word" and the entire page, not just one feature, came up blank.
  //
  // The check is a real parse rather than a regex: wrapping the body in a
  // non-async function makes any await outside an async context a syntax error,
  // while awaits inside async functions stay legal. That is exactly the rule.
  const script = inlineScripts(html)[0];
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'htv-await-'));
  try {
    const file = path.join(dir, 'wrapped.js');
    fs.writeFileSync(file, `(function () {\n${script}\n})();\n`);
    assert.doesNotThrow(
      () => execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' }),
      'the client uses await outside an async function (top-level await)',
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the client avoids APIs newer than the WebView it ships inside', () => {
  // Chromium 83 is the floor: Android 11's bundled WebView, which is what an
  // un-updated device runs. Each entry names the version that introduced it.
  const script = inlineScripts(html)[0];
  const tooNew = [
    [/\|\|=/, 'logical assignment ||= (Chrome 85)'],
    [/&&=/, 'logical assignment &&= (Chrome 85)'],
    [/\?\?=/, 'nullish assignment ??= (Chrome 85)'],
    [/\.replaceAll\(/, 'String.prototype.replaceAll (Chrome 85)'],
    [/\.at\(\s*-?\d/, 'Array.prototype.at (Chrome 92)'],
    [/\.findLast/, 'Array.prototype.findLast (Chrome 97)'],
    [/\.toSorted\(/, 'Array.prototype.toSorted (Chrome 110)'],
    [/Object\.hasOwn/, 'Object.hasOwn (Chrome 93)'],
    [/structuredClone/, 'structuredClone (Chrome 98)'],
    [/\.groupBy\(/, 'Array.prototype.groupBy (Chrome 117)'],
  ];

  for (const [re, label] of tooNew) {
    assert.ok(!re.test(script), `the client uses ${label}, which breaks WebView 83`);
  }
});

test('the client calls the public playlist endpoints the server actually serves', () => {
  const script = inlineScripts(html)[0];
  const calls = [...script.matchAll(/['"`](\/api\/public\/[A-Za-z0-9/_-]*)/g)].map((m) => m[1]);
  assert.ok(calls.some((c) => c.startsWith('/api/public/playlists')), 'no public playlist call');
  assert.ok(calls.some((c) => c.startsWith('/api/public/crawl')), 'no crawl call');
  assert.ok(calls.some((c) => c.startsWith('/api/public/videos/')), 'no reverse-lookup call');
});

test('the reskin tokens are still the measured ones', () => {
  const css = stylesheets(html)[0];
  // Values read off the reference with getComputedStyle, not estimated.
  const expected = {
    '--background': '0 0% 6.7%',
    '--foreground': '0 0% 93.3%',
    '--card': '0 0% 9.8%',
    '--primary': '29.5 100% 88%',
  };
  for (const [token, value] of Object.entries(expected)) {
    assert.ok(css.includes(`${token}: ${value}`), `${token} drifted from ${value}`);
  }
});

// ---------------------------------------------------------------------------
// Two areas share one client: normal anime (AniList catalog + Lunar episodes)
// behind /api/anime/*, and everything from hanime.tv behind an 18+ label.
// ---------------------------------------------------------------------------

test('the normal anime area has its own view, search and shelf', () => {
  assert.ok(html.includes(' data-view="anime"'), 'no view for the anime area');
  for (const id of ['anime-browse', 'anime-grid', 'anime-q', 'anime-clear',
    'anime-note', 'anime-count', 'anime-pager', 'anime-prev', 'anime-next',
    'anime-pageinfo']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }

  const script = inlineScripts(html)[0];
  // The catalog, episodes and player all arrive through this server: the
  // browser must never need a direct line to either upstream (Lunar answers
  // only its own Origin).
  assert.ok(script.includes('/api/anime/search'), 'the shelf never asks for the catalog');
  assert.ok(/\/api\/anime\/\$\{[^}]+\}\/player\?ep=/.test(script), 'no player route');
  assert.ok(script.includes('/api/anime/'), 'no detail route');
  assert.ok(/function openAnime\(/.test(script), 'nothing opens an anime title');
  assert.ok(script.includes('data-anime'), 'anime cards carry no id');
  // The search pill follows the reader into the anime area instead of
  // yanking them into the 18+ catalog.
  assert.ok(/viewIs\(["']anime["']\)[\s\S]{0,120}?searchAnime\(/.test(script),
    'the pill search ignores the anime area');
});

test('the detail page is a page, not a card floating over one', () => {
  // The layout the reader asked for: title and facts in a header, player and
  // reading matter in two columns below, episodes in the sidebar.
  const script = inlineScripts(html)[0];
  for (const marker of ['detail-hero', 'detail-grid', 'detail-main', 'detail-side',
    'detail-poster', 'detail-kicker', 'ep-list', 'ep-card', 'lx-frame']) {
    assert.ok(script.includes(marker), `the detail page never builds ${marker}`);
  }
  // One surface for both areas: the anime player reuses the sheet's open and
  // close, so the back contract covers it without a second one.
  assert.ok(/function openSheet\(\)/.test(script), 'the detail page has no shared opener');
  assert.ok(/document\.getElementById\(["']lx-frame["']\)/.test(script),
    'closing the page leaves the embed playing');
  assert.ok(/class="detail-hero"[\s\S]{0,400}?detail-title/.test(script),
    'the title is not in the header');
});

test('the two areas are labelled where the reader can see them', () => {
  // Everything from hanime.tv is adult content and must say so before it is
  // opened: in the menu, and on the section itself.
  assert.ok(html.includes('18+ · hanime'), 'the menu does not label the adult area');
  assert.ok(/All Titles <span class="area-chip">18\+<\/span>/.test(html),
    'the adult grid carries no 18+ label');
  assert.ok(/Anime <span class="area-chip">normal<\/span>/.test(html),
    'the anime shelf carries no label');
  assert.ok(html.includes('.area-chip'), 'no stylesheet rule draws the area label');
});

// ---------------------------------------------------------------------------
// The client is generated: client/{template.html,styles/,src/} are the source
// of truth, and both served copies are outputs of client/build.mjs. This guard
// replaces the old byte-identity test — instead of checking that two hand-kept
// copies match each other, it checks that both match what the sources build to
// right now, so a stale bundle is caught before release.
// ---------------------------------------------------------------------------

const ANDROID = path.resolve(HERE, '../../android');

test('the committed bundle is up to date with its sources', async () => {
  const { render } = await import('../client/build.mjs');
  const fresh = await render();
  assert.equal(
    html,
    fresh,
    'server/public/index.html is stale — regenerate it: cd server && npm run build:client',
  );
  if (!fs.existsSync(ANDROID)) return; // server-only checkout
  const bundled = path.join(ANDROID, 'app/src/main/assets/index.html');
  assert.ok(fs.existsSync(bundled), `missing ${bundled} — run npm run build:client`);
  assert.equal(
    fs.readFileSync(bundled, 'utf8'),
    fresh,
    'android/app/src/main/assets/index.html is stale — regenerate it: cd server && npm run build:client',
  );
});

test('the client plays video through its own hls.js, not through a CDN', () => {
  // Playback used to load hls.min.js from jsdelivr. That is a third party
  // deciding whether this app works: a blocked or DNS-suffixed CDN, or a
  // withdrawn version, stops video while every other screen looks fine.
  const script = inlineScripts(html)[0];
  assert.ok(!/cdn\.jsdelivr\.net/.test(html), 'the client still loads something from a CDN');
  assert.ok(/<script src="hls\.min\.js"><\/script>/.test(html),
    'the client does not load the bundled hls.js');
  // The source is `window.Hls?.isSupported()`; esbuild prints it lowered and
  // re-quoted, so match the two halves rather than the spelling.
  assert.ok(/window\.Hls/.test(script) && /\.isSupported\(\)/.test(script),
    'the hls.js usage moved — this test no longer guards what it claims to');

  const vendored = path.resolve(HERE, '../public/hls.min.js');
  assert.ok(fs.existsSync(vendored), `missing ${vendored}`);
  const source = fs.readFileSync(vendored, 'utf8');

  // The playback path ships to the same WebViews as the rest of the client, so
  // it has to clear the same bar: Chromium 83 is Android 11's bundled WebView.
  const tooNew = [
    [/\|\|=/, 'logical assignment ||= (Chrome 85)'],
    [/&&=/, 'logical assignment &&= (Chrome 85)'],
    [/\?\?=/, 'nullish assignment ??= (Chrome 85)'],
    [/\.replaceAll\(/, 'String.prototype.replaceAll (Chrome 85)'],
    [/\.at\(\s*-?\d/, 'Array.prototype.at (Chrome 92)'],
    [/structuredClone/, 'structuredClone (Chrome 98)'],
    [/\bstatic\s*\{/, 'static blocks (Chrome 94)'],
    [/\bclass\s+\w+\s*\{[^}]*#[a-z]\w*\s*[=(]/, 'private class members (Chrome 74)'],
  ];
  for (const [re, label] of tooNew) {
    assert.ok(!re.test(source), `the bundled hls.js uses ${label}, which breaks WebView 83`);
  }

  // And the app must bundle the same file the server serves, or the shipped
  // build plays through a copy that no test ever looked at.
  if (!fs.existsSync(ANDROID)) return;
  const bundled = path.join(ANDROID, 'app/src/main/assets/hls.min.js');
  assert.ok(fs.existsSync(bundled), `missing ${bundled} — copy public/hls.min.js into assets`);
  assert.equal(fs.readFileSync(bundled, 'utf8'), source,
    'the bundled hls.js is not the file this server serves');
});

test('back closes what is open, and the page decides that, not the shell', () => {
  // The app used to exit out from under an open title sheet: a single-page
  // client never grows a WebView history, so there was nothing for back to walk
  // through. The page now reports whether it has something to dismiss and
  // answers when asked.
  const script = inlineScripts(html)[0];
  assert.ok(script.includes('window.__shellBack'), 'the shell has no way to ask the page');
  assert.ok(script.includes('setBackEnabled'), 'the page never reports its back state');
  assert.ok(/function updateBackState\(\)/.test(script), 'the back state is never computed');

  // Both things that take over the screen have to be covered by it.
  assert.ok(/classList\.contains\(["']open["']\)/.test(script), 'the sheet is not part of the back state');
  assert.ok(/dataset\.searching === ["']true["']/.test(script), 'the search field is not part of it');

  // Moving backwards through state is not allowed to introduce a scroll jump;
  // the sheet is fixed and must stay that way.
  assert.ok(!script.includes('history.pushState'),
    'a history entry was added — that is the WebView-back approach this replaced');
});

test('a title remembers where it was left', () => {
  const script = inlineScripts(html)[0];
  for (const name of ['savePosition', 'clearPosition', 'progressOf', 'cleanPositions']) {
    assert.ok(new RegExp(`function ${name}\\(`).test(script), `missing ${name}()`);
  }
  // Written while playing, and at the two moments that matter.
  assert.ok(/addEventListener\(["']timeupdate["']/.test(script), 'nothing records progress while playing');
  assert.ok(/addEventListener\(["']pause["'],[\s\S]{0,160}?savePosition/.test(script),
    'pausing does not record it');
  assert.ok(/if \(activeSlug && activeVid\) savePosition/.test(script),
    'closing the sheet does not record it');
  // Resumed on open, and finished titles are not resumed.
  assert.ok(/saved\.t > 5/.test(script), 'nothing resumes a part-way title');
  assert.ok(/t >= d - 10/.test(script), 'a finished title is still kept as unfinished');
  // Stored under the same guarded key as the library, so one broken storage
  // cannot lose half the state.
  assert.ok(script.includes("htv:library:v1"), 'positions moved away from the library key');
  assert.ok(script.includes('positions'), 'nothing is stored for a title position');

  for (const id of ['cont-head', 'cont-rail', 'cont-count', 'cont-note']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  assert.ok(html.includes('.card-progress'), 'no stylesheet rule draws the progress bar');
});

test('the library can leave the device and come back', () => {
  const script = inlineScripts(html)[0];
  for (const id of ['lib-export', 'lib-import', 'lib-msg']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  assert.ok(script.includes('exportLibrary'), 'nothing asks the shell to export');
  assert.ok(script.includes('importLibrary'), 'nothing asks the shell to import');
  assert.ok(script.includes('window.__shellLibraryImport'), 'the shell has nothing to hand a file to');

  // The file is not trusted: a parse failure must land in a message rather than
  // in the console, and a merge must never replace what is on the device.
  assert.ok(/try \{[^}]*JSON\.parse\(text\)/.test(script), 'import parses unguarded');
  assert.ok(/haveFav/.test(script), 'an import overwrites the existing favourites');
  assert.ok(/byslug\.get\(e\.slug\)/.test(script), 'an import does not merge history by title');
});

test('the navbar is a menu, not a strip you have to swipe', () => {
  // The section strip lived inside the pill and scrolled sideways on a phone:
  // everything past Browse — Genres, Studios, Playlists, Library — was only
  // reachable by dragging the navbar, which nobody discovers. The pill now
  // keeps its buttons and hands every section to a three-line menu.
  assert.ok(html.includes(' id="menu-toggle"'), 'no three-line button on the pill');
  assert.ok(html.includes(' id="nav-menu"'), 'the button has nowhere to open');
  assert.ok(!html.includes('class="nav-links"'), 'the swipeable strip is still in the nav');
  // The pill carries two buttons: the sort shortcut that sat between menu and
  // search duplicated the Browse toolbar's own control, and the reader asked
  // for the menu and the glass to trade places.
  assert.ok(!html.includes('id="sort-toggle"'), 'the sort shortcut is still on the pill');
  assert.ok(html.indexOf('id="search-toggle"') < html.indexOf('id="menu-toggle"'),
    'search and menu have not traded places');

  const script = inlineScripts(html)[0];
  assert.ok(/function setMenuOpen\(/.test(script), 'nothing opens or closes the menu');
  // The menu is a thing on screen, so back closes it before leaving the app —
  // same contract as the sheet and the search field.
  assert.ok(/function menuIsOpen\(/.test(script), 'the menu reports no state');
  assert.ok(/menuIsOpen\(\)[\s\S]{0,200}?setMenuOpen\(false\)/.test(script),
    '__shellBack never closes the menu');
  // Choosing a section is a decision: the menu must not stay over the view it
  // just opened. (The source says this with a trailing comment; the bundle
  // keeps only the code.)
  assert.ok(/if \(menuIsOpen\(\)\) setMenuOpen\(false\)/.test(script),
    'picking a section leaves the menu open');
  assert.ok(html.includes('.nav-menu '), 'no stylesheet rule draws the menu');
});

test("appearance is the reader's, and it survives a restart", () => {
  // Theme and accent used to be the build's business: wanting a light screen
  // meant rebuilding the app. The choices live in their own guarded key, so a
  // refused storage keeps the defaults and a self-update carries the rest.
  const script = inlineScripts(html)[0];
  const css = stylesheets(html)[0];

  assert.ok(script.includes('htv:prefs:v1'), 'the prefs key moved');
  assert.ok(/function applyPrefs\(/.test(script), 'nothing applies the prefs');
  assert.ok(script.includes('document.body.dataset.theme'), 'the theme never reaches the DOM');
  assert.ok(script.includes('document.body.dataset.accent'), 'the accent never reaches the DOM');
  assert.ok(script.includes("prefers-color-scheme"), 'System does not follow the system');
  // Guarded like the library: a storage that refuses must not break the page.
  assert.ok(/try \{[\s\S]*?localStorage\.setItem\(PREFS_KEY/.test(script),
    'the prefs write is unguarded');

  for (const id of ['theme-seg', 'accent-row', 'pref-motion']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
    assert.equal((html.match(new RegExp(`id="${id}"`, 'g')) || []).length, 1,
      `#${id} exists more than once — the control was copied, not moved`);
  }
  // They moved out of the menu popover and into the settings view; a reader
  // who opens the menu looking for Theme must find it in Settings instead.
  const settingsAt = html.indexOf('data-view="settings"');
  assert.ok(settingsAt > 0, 'no settings view to hold the appearance controls');
  assert.ok(settingsAt < html.indexOf('id="theme-seg"'),
    'the theme control is not inside the settings view');
  // The themes are token swaps on the body, not a second stylesheet.
  assert.ok(css.includes('body[data-theme="light"]'), 'no light palette');
  assert.ok(css.includes('body[data-accent="'), 'no accent palettes');
  assert.ok(css.includes('body[data-motion="reduced"]'), 'motion cannot be calmed');
  for (const name of ['amber', 'rose', 'violet', 'sky', 'mint']) {
    assert.ok(html.includes(`data-accent-pick="${name}"`), `missing accent ${name}`);
  }
});

test('settings is a view of its own: build facts, update checks, the shell', () => {
  // Appearance moved here from the menu; the update controls come from the
  // Shell bridge. Both areas live behind one menu item rather than in the
  // popover, so a reader can find them without the menu closing on them.
  assert.ok(html.includes(' data-view="settings"'), 'no settings view');
  for (const id of ['settings', 'set-version', 'set-server', 'set-check', 'set-server-settings',
    'set-reload', 'set-browser', 'set-note']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  const script = inlineScripts(html)[0];
  for (const call of ['versionName', 'versionCode', 'serverUrl', 'checkForUpdate',
    'openServerSettings', 'openInBrowser']) {
    assert.ok(new RegExp(`['"]${call}['"]`).test(script),
      `the settings view never asks the shell for ${call}`);
  }
  // The four actions that used to sit behind the floating ⋮ button are all
  // here now, and the button itself is gone from both copies of the app.
  assert.ok(script.includes('location.reload()'), 'nothing reloads the page from Settings');
  assert.ok(html.includes('Update source'), 'the update source action lost its name');
  // Outside the app there is no Shell object: the view must fall back to
  // what the browser knows instead of showing buttons that do nothing.
  assert.ok(/shellApi\(\)/.test(script), 'nothing decides between app and browser');
  assert.ok(/\[data-go\]/.test(script), 'the view never refills when it is opened');
});

test('the anime area filters the catalog upstream, not after the fact', () => {
  // A page of 24 has to be a page of matches: the filters ride the query to
  // AniList (both backends carry them) instead of sifting one page by hand.
  for (const id of ['anime-genre', 'anime-format', 'anime-status', 'anime-filter-clear']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  const script = inlineScripts(html)[0];
  for (const key of ['genre', 'format', 'status']) {
    assert.ok(new RegExp(`params\\.set\\(['"]${key}['"]`).test(script),
      `the ${key} filter never reaches the query`);
  }
  // Option values are AniList's enums — anything else would fail upstream.
  assert.ok(html.includes('value="NOT_YET_RELEASED"'), 'the status options are not upstream enums');
  assert.ok(html.includes('value="TV_SHORT"'), 'the format options are not upstream enums');
  assert.ok(html.includes('<option>Action</option>'), 'the genre options are not upstream genres');
});

test('the genre picker is a cloud you can search, and it says what is on', () => {
  // The old picker was thirty chips in a strip you dragged sideways, with no
  // way to find a tag that was not among them. It now wraps, narrows as you
  // type, and keeps the selected chips in view whatever the filter says.
  for (const id of ['tag-q', 'tag-q-clear', 'tag-count', 'tag-note', 'tag-search-shell']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  assert.ok(html.includes('class="tag-cloud"'), 'the genre list is still a strip to drag sideways');
  const script = inlineScripts(html)[0];
  assert.ok(/function renderTags\(/.test(script), 'nothing repaints the genre cloud');
  assert.ok(/function setTagQuery\(/.test(script), 'the filter box is not wired to the cloud');
  // Selected chips survive the filter — hiding the reader's own choices would
  // make them impossible to take back off.
  assert.ok(/!\s*tagQuery\s*\|\|[^|]*\|\|\s*[A-Za-z_$][\w$]*\(t\.name\)/.test(script),
    'a selected tag can be filtered out of the cloud');
});

test('picture in picture is offered only when the shell supports it', () => {
  const script = inlineScripts(html)[0];
  assert.ok(script.includes('pictureInPictureSupported'), 'the page never asks about PiP support');
  assert.ok(/['"]enterPip['"]/.test(script), 'the page cannot ask for the small window');
  assert.ok(script.includes('window.__shellPip'), 'the shell cannot tell the page it is in PiP');
  assert.ok(script.includes("setVideoAspect"), 'the PiP window is never shaped to the video');
  // The button is in the player, which only exists once a stream resolved.
  assert.ok(html.includes('id="pip-btn"'), 'the player has no PiP button');
  assert.ok(html.includes('body[data-pip="true"]'), 'no stylesheet rule shrinks the page to the video');
});

// The signer bundle is emscripten output using `??=`, which arrived in
// Chrome 85. It is downgraded by android/tools/make-signer-asset.mjs; if that
// step is skipped the module fails to parse, the signature never appears, and
// every video fails to resolve with no error on screen but a 502.
test('the bundled signer parses on the WebViews this app runs inside', () => {
  if (!fs.existsSync(ANDROID)) return; // server-only checkout
  const bundled = path.join(ANDROID, 'app/src/main/assets/htv-signer.js');
  assert.ok(fs.existsSync(bundled), `missing ${bundled}`);
  const source = fs.readFileSync(bundled, 'utf8');
  assert.ok(!source.includes('??='), 'logical assignment survives: regenerate the asset');
  assert.ok(!source.includes('||='), 'logical assignment survives: regenerate the asset');
  assert.ok(!source.includes('&&='), 'logical assignment survives: regenerate the asset');
  assert.ok(!/static\s*\{/.test(source), 'static block survives: regenerate the asset');
  assert.ok(
    !/[#]\w+\s*[({=]/.test(source),
    'private class member survives: regenerate the asset',
  );
});
