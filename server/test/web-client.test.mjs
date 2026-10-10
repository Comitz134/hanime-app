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

  // Twice on purpose: the three areas appear once as buttons on the bar —
  // the reader's everyday switch, one tap instead of open-read-tap — and once
  // in the menu, which keeps every section for the places you go to rather
  // than the places you switch between.
  assert.deepEqual(targets, [
    'anime', 'manga', 'movies', 'browse',
    'anime', 'manga', 'movies', 'browse', 'genres', 'studios', 'playlists', 'library', 'settings',
  ]);
  for (const t of targets) {
    assert.ok(views.includes(t), `the nav points at a view that does not exist: ${t}`);
  }

  // The bar's copies are the icon ones, and they carry `.nav-link` so showView
  // marks the current area exactly as it marks the menu's own item — one
  // source of truth for where you are, whichever door you came in by.
  const areas = html.match(/<div class="nav-areas"[\s\S]*?\n\s*<\/div>/)?.[0] ?? '';
  assert.ok(areas, 'the areas are not on the bar at all');
  assert.ok(areas.includes('icon-btn nav-link'),
    'the area buttons are not marked, so nothing would show which area is open');
  assert.match(areas, /data-go="anime"[\s\S]*?data-go="manga"[\s\S]*?data-go="movies"[\s\S]*?data-go="browse"/,
    'the areas are not on the bar in order');
  assert.ok(!areas.includes('nav-menu-item'), 'the area buttons are menu items');
  // The search field takes the whole pill when it opens, so the areas have to
  // know to step aside — without this rule they sit under the input.
  assert.ok(html.includes('.nav[data-searching="true"] .nav-areas'),
    'the area buttons have no room rule while searching');
  assert.ok(html.includes('.nav-areas'), 'no stylesheet draws the area buttons');
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
  assert.ok(/function openSheet\(/.test(script), 'the detail page has no shared opener');
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
  assert.ok(/Manga <span class="area-chip">mangafire<\/span>/.test(html),
    'the manga shelf does not say where its catalog comes from');
  assert.ok(html.includes('.area-chip'), 'no stylesheet rule draws the area label');
});

// ---------------------------------------------------------------------------
// The manga area reads mangafire.to: the client signs and fetches the catalog
// itself (their CORS is wide open), and only reader images pass through a
// backend, because their image CDN refuses hotlinks.
// ---------------------------------------------------------------------------

test('the manga area has its own view, shelf and reader', () => {
  assert.ok(html.includes(' data-view="manga"'), 'no view for the manga area');
  for (const id of ['manga-browse', 'manga-search-shell', 'manga-q', 'manga-clear',
    'manga-filters', 'manga-type', 'manga-status', 'manga-genre', 'manga-filter-clear',
    'manga-grid', 'manga-note', 'manga-count', 'manga-pager', 'manga-prev', 'manga-next',
    'manga-pageinfo']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }

  const script = inlineScripts(html)[0];
  // The catalog is fetched by the client straight from mangafire — no Node
  // or Java catalog proxy exists for it, by design.
  assert.ok(script.includes('https://mangafire.to/api'), 'the catalog never reaches mangafire');
  assert.ok(script.includes('vrf'), 'the listing requests are never signed');
  // Their image CDN 403s hotlinks, so reader pages go through the proxy on
  // both backends; covers do not need it and stay on their real URLs.
  assert.ok(script.includes('/api/manga/page'), 'reader pages are never proxied');
  assert.ok(script.includes('static.mfcdn.nl') || script.includes('poster'),
    'covers lost their direct CDN source');
  // The surface: cards that open a title, a title page, a chapter reader.
  assert.ok(/function openManga\(/.test(script), 'nothing opens a manga title');
  assert.ok(/function openChapter\(/.test(script), 'nothing opens a chapter');
  assert.ok(script.includes('data-manga'), 'manga cards carry no id');
  assert.ok(script.includes('data-mfch'), 'chapters carry no id');
  assert.ok(script.includes('ensureManga()'), 'the shelf is never loaded');
  assert.ok(script.includes('/filter-options'), 'the filters never ask upstream what exists');
  // Option values are upstream's own enums — anything else would be refused.
  assert.ok(html.includes('value="on_hiatus"'), 'the status options are not upstream enums');
  assert.ok(html.includes('value="manhwa"'), 'the type options are not upstream enums');
  // One surface, like the other two areas: the detail renders into the shared
  // sheet, so the back contract covers it without a second one.
  assert.ok(/openSheet\((['"])manga\1/.test(script),
    'the manga title page does not use the shared sheet');
  assert.ok(/class="ch-list"|ch-list/.test(script), 'the title page has no chapter list');
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

test('back walks a stack like a browser, down to the state the app opened in', () => {
  // The reader asked for back to keep stepping — view, title, chapter, back
  // down to how the app started — without ever leaving the app. That is an
  // owned stack of snapshots, not the WebView's history: one entry per move,
  // popped one press at a time.
  const script = inlineScripts(html)[0];
  assert.ok(/function pushNav\(/.test(script), 'nothing records where back would return to');
  assert.ok(/function goBack\(/.test(script), 'nothing walks the stack');
  assert.ok(/function dropClosedEntry\(/.test(script),
    'closing the sheet does not cancel the entry that opened it');
  assert.ok(/function snapshot\(/.test(script), 'the stack stores no snapshots');
  assert.ok(script.includes('s.y'), 'restoring never returns the reader to their place in the page');

  // __shellBack must answer every press. A "false" anywhere in it tells the
  // shell the press is free — and a free press leaves the app.
  const start = script.indexOf('window.__shellBack');
  const end = script.indexOf('window.__shellPip');
  assert.ok(start >= 0 && end > start, 'the shell back handler moved');
  const handler = script.slice(start, end);
  assert.ok(handler.includes('return true'), '__shellBack does not answer every press');
  assert.ok(!handler.includes('return false'), '__shellBack reports an unhandled press — back could exit');
  // Every surface that opens is recorded: views, sheets, chapters.
  assert.ok(/function showView\([\s\S]{0,400}?pushNav\(/.test(script),
    'switching views is not a step back can take');
  assert.ok(/function openSheet\([\s\S]{0,200}?pushNav\(/.test(script),
    'opening the sheet is not a step back can take');
});

test('the shell never hands back to the operating system', () => {
  // The Java side of the same contract: handleBack() used to end in a
  // return false, which the dispatcher answered with finish(). Both are gone —
  // at the bottom of the stack the press is spent, and the app stays.
  if (!fs.existsSync(ANDROID)) return; // server-only checkout
  const main = fs.readFileSync(
    path.join(ANDROID, 'app/src/main/java/app/hanime/shell/MainActivity.java'), 'utf8');
  const start = main.indexOf('private boolean handleBack()');
  const end = main.indexOf('picture in picture', start);
  assert.ok(start >= 0 && end > start, 'handleBack() moved — this test no longer guards it');
  const body = main.slice(start, end);
  assert.ok(!body.includes('return false'),
    'handleBack can decline the press — the OS would take it and close the app');
  assert.ok(!/if\s*\(\s*!handleBack\(\)\s*\)/.test(main),
    'the back dispatcher still finishes the activity when nothing took the press');
  assert.ok(!/\bfinish\(\);/.test(main), 'MainActivity still finishes itself somewhere');
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

// Tracking is one concern across three areas — anime (AniList's idMal),
// manga (mangafire's malId) and the 18+ shelf (resolved by title) — and it
// must never leave the app: the login is a navigation, the exchange and the
// API are pipes through our own backends, and the tokens stay client-side.
test('MyAnimeList tracking reaches every area without leaving the app', () => {
  const script = inlineScripts(html)[0];

  // The settings card, and the four ids its states are written into.
  for (const id of ['mal-card', 'mal-user', 'mal-link', 'mal-unlink', 'mal-note']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }

  // The login: MAL's authorize endpoint, PKCE with the only challenge
  // method it supports, and a redirect back to this copy's own origin.
  assert.ok(/myanimelist\.net\/v1\/oauth2\/authorize/.test(script),
    'no authorize endpoint');
  assert.ok(/code_challenge_method:\s*["']plain["']/.test(script),
    'PKCE must use MAL\'s plain challenge');
  assert.ok(/location\.origin/.test(script),
    'the redirect must return to the origin that started the flow');
  assert.ok(/await handleMalCallback\(\)/.test(script),
    'the redirect lands nowhere: the code is never exchanged');

  // Everything else crosses our own pipes — MAL answers no browser origin.
  assert.ok(/\/api\/mal\/token/.test(script), 'the exchange misses the pipe');
  assert.ok(/`\/api\/mal\/v2\$\{path\}`/.test(script), 'the API misses the pipe');
  assert.ok(/grant_type:\s*["']refresh_token["']/.test(script),
    'a one-hour access token with no refresh is a one-hour feature');

  // A slot in each area's detail sheet: anime and 18+ by AniList's idMal,
  // manga by mangafire's own malId on the title.
  assert.ok(/data-mal-kind="manga"/.test(script), 'manga detail has no tracking slot');
  const animeSlots = script.match(/data-mal-kind="anime"/g) ?? [];
  assert.ok(animeSlots.length >= 2,
    'the anime and 18+ detail pages each need a tracking slot');
  assert.ok(/data-mal-id="\$\{[^}]*malId/.test(script),
    'a known MAL id must reach the slot instead of a title search');

  // MAL's own status spellings — anything else is refused upstream.
  const statuses = ['watching', 'completed', 'on_hold', 'dropped', 'plan_to_watch',
    'reading', 'plan_to_read'];
  for (const status of statuses) {
    const quoted = script.includes(`"${status}"`) || script.includes(`'${status}'`);
    assert.ok(quoted, `missing MAL status ${status}`);
  }

  // Progress writes the field MAL reads: episodes watched for anime,
  // chapters read for manga.
  assert.ok(script.includes('num_watched_episodes'), 'anime progress has no field');
  assert.ok(script.includes('num_chapters_read'), 'manga progress has no field');

  // The list-status route is spelled with underscores, and reading one's own
  // status is a *media* route (the status route takes writes only — a GET is
  // `405 method_not_allowed`). Both were wrong in the shipped client: every
  // write and every read answered `404 not_found`, so a title could be added
  // and nothing landed, silently.
  assert.ok(!script.includes('/mylist_status'),
    'the list-status route is missing its underscores — MAL does not serve it');
  assert.ok(/\/\$\{kind\}\/\$\{id\}\/my_list_status/.test(script),
    'nothing writes list status on MAL\'s own path');
  assert.ok(/fields=my_list_status/.test(script),
    'the reader\'s own status is never read back (the status route has no GET)');
});

// ---------------------------------------------------------------------------
// The Library is three shelves, and each answers a different question: what
// the account watches, what it reads, and the adult titles it tracks next to
// what this device saved. All three read the account's real list — statuses,
// progress and all — and every tap lands in one of the app's own areas, never
// in a browser.
// ---------------------------------------------------------------------------

test('the library has a shelf per area, one of them open at a time', () => {
  // The markup only: the script and the stylesheet both name the same
  // attributes, so a count over the whole file would count the client's own
  // selectors and the tab's own style rule.
  const markup = html
    .replace(/<script(?![^>]*\bsrc=)[^>]*>[\s\S]*?<\/script>/g, '')
    .replace(/<style[^>]*>[\s\S]*?<\/style>/g, '');
  for (const tab of ['anime', 'manga', 'adult']) {
    assert.ok(html.includes(`data-lib-tab="${tab}"`), `missing the ${tab} tab`);
    assert.ok(html.includes(`data-lib-panel="${tab}"`), `missing the ${tab} panel`);
    for (const id of [`mal-${tab}-grid`, `mal-${tab}-note`, `mal-${tab}-count`]) {
      assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
    }
  }
  assert.equal((markup.match(/data-lib-tab=/g) || []).length, 3, 'more tabs than shelves');
  assert.equal((markup.match(/data-lib-panel=/g) || []).length, 3, 'more panels than tabs');
  assert.equal((markup.match(/aria-selected="true"/g) || []).length, 1,
    'exactly one shelf is open on load');
  assert.equal((markup.match(/data-lib-panel="[a-z]+" role="tabpanel"[^>]*hidden/g) || []).length, 2,
    'the two shelves that are not open must start hidden');

  // The device's own records were not moved out of the Library — they are the
  // 18+ shelf's floor: continue watching, favorites, history, and the tools
  // that move them on and off the device.
  const adultAt = markup.indexOf('data-lib-panel="adult"');
  const settingsAt = markup.indexOf('data-view="settings"');
  assert.ok(adultAt > 0, 'no adult panel');
  for (const id of ['lib-q', 'lib-export', 'lib-import', 'lib-msg', 'lib-warn',
    'cont-rail', 'fav-grid', 'hist-grid']) {
    const at = markup.indexOf(` id="${id}"`);
    assert.ok(at > adultAt && at < settingsAt, `#${id} is not inside the 18+ shelf`);
  }
});

test('the shelves read the account\'s own list, adult entries included', () => {
  const script = inlineScripts(html)[0];

  // Both lists, one call each — and under `/users/@me/`, which is the part
  // that shipped wrong once: `/v2/animelist` is not an endpoint at all, MAL
  // answered `404 {"error":"not_found"}` and the shelves said ``could not
  // read''. The path is asserted, not just the word.
  assert.ok(/\/users\/@me\/\$\{kind === ["']manga["'] \? ["']mangalist["'] : ["']animelist["']\}/.test(script),
    'the list is read from a path MAL does not serve');
  assert.ok(script.includes('animelist'), 'the anime list is never read');
  assert.ok(script.includes('mangalist'), 'the manga list is never read');
  // MAL hides black-flagged entries from list answers unless it is asked for
  // them — without this the 18+ shelf would always be empty.
  assert.ok(script.includes('nsfw=true'), 'adult entries would be withheld');
  for (const field of ['list_status{', 'node{', 'num_episodes_watched', 'num_chapters_read',
    'main_picture', 'num_episodes', 'nsfw', 'genres']) {
    assert.ok(script.includes(field), `the list call never asks for ${field}`);
  }
  // A list entry is { node, list_status }, so the fields are named both as
  // those sub-sections and as the keys inside them: MAL answers unknown field
  // names with a 200 and no key (verified against the live API), so a miss
  // here would silently empty every shelf instead of failing loudly.
  assert.ok(/list_status\{status,score,num_episodes_watched,updated_at\}/.test(script),
    'the anime list status is not asked for');
  assert.ok(/list_status\{status,score,num_chapters_read,updated_at\}/.test(script),
    'the manga list status is not asked for (chapters, not episodes)');

  // Long lists are a list of pages, and MAL answers the next one as a full
  // URL: it must be reduced to the path the pipe carries its token on, never
  // requested as an absolute URL of its own.
  assert.ok(script.includes('paging'), 'the list never pages');
  assert.ok(script.includes('api\\.myanimelist\\.net\\/v2'),
    'a next-page URL would be requested as an absolute URL, bypassing the pipe');

  // Two answers to "is this adult": MAL's own black flag, and the genre.
  assert.ok(/nsfw === ["']black["']/.test(script), 'the black flag is ignored');
  assert.ok(/toLowerCase\(\) === ["']hentai["']/.test(script), 'the Hentai genre is ignored');

  // Cached on the device with an expiry, and forgotten when the account is
  // unlinked — a shared phone must not keep the previous account's list.
  assert.ok(script.includes('mal.lists'), 'the list is not kept on the device');
  assert.ok(/LIST_TTL_MS/.test(script), 'the copy on the device never goes stale');
  assert.ok(/drop\(LISTS_KEY\)/.test(script), 'unlinking leaves the list behind');
});

test('a shelf card opens in the area it belongs to, not in a browser', () => {
  const script = inlineScripts(html)[0];

  // A MAL entry is not one of our ids, so each shelf resolves the title
  // against its own catalog first — AniList for anime, mangafire for manga,
  // the 18+ catalog for the adult shelf.
  assert.ok(/\/api\/anime\/search\?q=\$\{encodeURIComponent\(title\)\}/.test(script),
    'an anime shelf entry is never matched against the catalog');
  assert.ok(/mangaSearch\(\{ q: title/.test(script), 'a manga shelf entry is never matched');
  assert.ok(/\/api\/videos\?per_page=8&q=\$\{encodeURIComponent\(title\)\}/.test(script),
    'an adult shelf entry is never matched');

  // And the sheet that opens is the area's own — the same opener a tap on the
  // catalog's card uses, so back, the reader and the player all still apply.
  assert.ok(/openTarget\(kind, id\)/.test(script), 'no single place opens a shelf entry');
  assert.ok(script.includes('data-mal-open'), 'a shelf card carries no target');

  // A title is matched once: the answer is kept by title, and a shelf with no
  // account asks MAL for nothing at all.
  assert.ok(script.includes('mal.open'), 'every tap re-searches the same title');
  assert.ok(/if \(!configured\(\) \|\| !linked\(\)\) \{\s*sayUnlinked\(\);\s*return;/.test(script),
    'an unlinked shelf still calls MyAnimeList');
  assert.ok(/Link MyAnimeList in Settings/.test(script),
    'an empty shelf does not say how to fill it');

  // The 18+ shelf is everything adult on the account, both kinds: a list that
  // tracks adult manga is exactly the list this shelf exists for. A card
  // therefore carries the shelf (where its messages belong) *and* the kind
  // (which catalog a tap resolves against) — mixing them sent an 18+ match
  // failure to the Anime panel's note, where nobody would see it.
  assert.ok(/\[\]\.concat\(anime\.filter\(\(e\) => e\.adult\), manga\.filter\(\(e\) => e\.adult\)\)/.test(script)
    || /anime\.filter\(\(e\) => e\.adult\), \.\.\.manga\.filter\(\(e\) => e\.adult\)/.test(script),
    'the 18+ shelf no longer holds both kinds');
  assert.ok(script.includes('data-mal-kind'), 'a shelf card does not say which area it belongs to');
  assert.ok(/data-mal-open|openMalEntry/.test(script), 'a shelf card carries no target');

  // The shelves refill on the way into the view, like the device's records.
  assert.ok(/renderMalShelves\(\)/.test(script), 'the shelves are never rendered');
  assert.ok(/showView[\s\S]{0,80}?library[\s\S]{0,200}?renderMalShelves/.test(script) ||
    /renderLibrary\(\);\s*renderMalShelves\(\);/.test(script),
    'opening the Library does not fill its shelves');
  assert.ok(html.includes('.lib-tab'), 'no stylesheet rule draws the shelf tabs');
});

// Watching and reading is what the app already knows; this is that knowledge
// going back the other way, so the account's list stays current without the
// reader opening MyAnimeList at all.
test('opening an episode or a chapter advances the list it belongs to', () => {
  const script = inlineScripts(html)[0];

  // Both openers write, at the moment the thing actually opens — a resolved
  // player for an episode, a rendered reader for a chapter. Read as the claim
  // rather than as the source's own spelling: the bundler downlevels `?.` and
  // renames one area's `dState` to keep the two apart, so what is asserted is
  // that the write carries the episode, and that it carries the chapter's own
  // number — the chapter is read into a local before the call, which is what
  // the device's own record of it needs too.
  // The call is read up to its own semicolon: downleveling puts parentheses
  // inside the argument list, so a naive `[^)]*` would stop mid-call.
  const episodeWrite = script.match(/autoAdvance\(["']anime["'][\s\S]{0,240}?;/);
  assert.ok(episodeWrite, 'opening an episode never reaches the list');
  assert.ok(/,\s*ep\s*,/.test(episodeWrite[0]),
    'the episode that opened is not the number written to the list');
  const chapterWrite = script.match(/autoAdvance\(["']manga["'][\s\S]{0,240}?;/);
  assert.ok(chapterWrite, 'opening a chapter never reaches the list');
  assert.ok(/\.number/.test(chapterWrite[0]),
    'the chapter that opened is not the number written to the list');

  // Forward only, and once per number: re-opening the same episode is not
  // another request, and a list that is further along is never walked back.
  assert.ok(/autoWritten\[key\] >= n/.test(script), 'the progress can go backwards');
  assert.ok(/have >= n/.test(script), 'a list already ahead would be rewritten');

  // A title that is not on the list is added as watching/reading; reaching the
  // last episode or chapter marks it completed — the same rule MAL's own
  // clients apply.
  assert.ok(/kind === ["']manga["'] \? ["']reading["'] : ["']watching["']/.test(script),
    'an untracked title is not filed as watching/reading');
  assert.ok(/n >= total \? ["']completed["']/.test(script),
    'reaching the last episode or chapter does not finish the entry');

  // A write must never be able to break playback — the awaited write sits in a
  // try whose catch is empty, so nothing from MAL reaches the player — and it
  // only ever rides the account's own token: an unlinked build writes nothing.
  assert.ok(/await setEntry\(kind, malId, \{ status, progress: n \}\);[\s\S]{0,60}?catch \(e\) \{\s*\}/.test(script),
    'a failed write would surface as a playback error');
  assert.ok(/if \(!configured\(\) \|\| !linked\(\) \|\| !PROGRESS\[kind\]/.test(script),
    'an unlinked account is still written to');
});

// Organizing: every shelf can be put in an order the reader picks, and the
// words of those orders are the point — "last read" on the manga shelf means
// the same kind of fact as "last updated" on the account's list.
test('every Library shelf offers orders, and the pick is remembered', () => {
  const script = inlineScripts(html)[0];

  // The orders live in one table; every shelf names the ones it can honour.
  // The bundler prints non-ASCII as \u escapes (charset ascii), so a label is
  // looked for in both spellings — the escape is the same string at runtime.
  const has = (text) => script.includes(text) || script.includes(text.replace(
    /[^\x00-\x7F]/g,
    (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`,
  ));
  for (const label of ['Last read', 'Last watched', 'Last updated', 'Recently added',
    'Title A–Z', 'Progress', 'Score', 'Status']) {
    assert.ok(has(label), `the order “${label}” is not offered anywhere`);
  }

  // Every control in the markup has a shelf behind it: a control with no
  // section is a dropdown that would never fill, and a shelf with no control
  // is an order nobody can pick.
  const controls = [...html.matchAll(/data-sort-for="([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(controls.slice().sort(),
    ['cont', 'fav', 'hist', 'mal-adult', 'mal-anime', 'mal-manga', 'read-manga',
      'show-watch', 'watch-anime'],
    'the Library does not offer an order on every shelf');
  for (const name of controls) {
    // A key is quoted when it cannot be a bare identifier (`mal-anime`), and
    // printed as-is when it can (`cont`) — both spellings are the same table.
    assert.ok(new RegExp(`["']${name}["']\\s*:|\\b${name}\\s*:`).test(script),
      `no orders are declared for the ${name} shelf`);
  }

  // Filled once at boot, remembered on change, and the shelves redraw from the
  // copy on the device — re-reading a list of titles to sort it is a request
  // nobody asked for.
  assert.ok(/fillSorts\(\)/.test(script), 'the order controls are never filled');
  assert.ok(/htv:sorts:v1/.test(script), 'the picked orders are not remembered');
  assert.ok(/new CustomEvent\(["']htv:sort["']/.test(script), 'an order change is never announced');
  assert.ok(/htv:sort[\s\S]{0,200}?paint\(malCache\(\)\)/.test(script),
    'sorting the account\u2019s shelves re-reads the network instead of the device');
  // And the catalog's own order survives a restart, as a value the select
  // actually offers rather than whatever string was in storage.
  assert.ok(/option.*?includes\(pick\)|includes\(pick\)/.test(script),
    'a remembered catalog order is trusted without checking the options');
});

// The device's own memory of what was watched and read: MyAnimeList knows
// where a list stands, not which chapter was on screen last night.
// AniList files every season of a series as its own title — the same story,
// split into entries whose names differ only by "2nd Season" — which is why a
// catalog of anime reads like a list of unrelated shows. The app says
// otherwise: the server walks the SEQUEL/PREQUEL chain, and the title page
// draws it under the player.
test('a season page links the rest of its series', () => {
  const script = inlineScripts(html)[0];

  // The row is there, hidden, and only a chain longer than one season fills
  // it: a strip of one chip answers a question nobody asked.
  assert.ok(/class="seasons" id="seasons" hidden/.test(html),
    'the seasons row is not in the markup, or shows before it has anything');
  assert.ok(/id="season-rail"/.test(html), 'the seasons row has nowhere to draw');
  assert.ok(html.includes('.season-rail'), 'no stylesheet draws the seasons row');
  assert.ok(/chain\.length < 2/.test(script), 'a single season would become a strip of one');

  // Asked for, drawn from the chain, and opening one takes the same path a
  // card does — the sheet, the player, the stack back walks.
  assert.ok(/\/seasons/.test(script), 'the season chain is never asked for');
  assert.ok(/data-season=/.test(script), 'a season chip carries no id to open');
  assert.ok(/openAnime\(Number\(season\.dataset\.season\)\)/.test(script),
    'tapping a season does not open that title the way a card does');
  // And the answer belongs to the title still on screen: a slow walk must not
  // repaint a page the reader has already moved on from. The binding is
  // matched loosely because the bundler renames one area's `dState` to keep
  // the two apart (as the auto-advance test above already notes).
  assert.ok(/String\(dState\d*\.id\) !== String\(id\)/.test(script),
    'a slow chain would repaint a page that moved on');
});

test('the device keeps its own last-read and last-watched record', () => {
  const script = inlineScripts(html)[0];

  assert.ok(/htv:activity:v1/.test(script), 'nothing is kept about what was opened');

  // The write guard and the read filter have to agree on what counts as a
  // record. A prologue is chapter 0 on mangafire: written with `n < 0`
  // allowed and then filtered out with `> 0`, it is stored and never seen,
  // and the rail just looks broken — which is what the phone showed.
  assert.ok(/if \(!theId \|\| !Number\.isFinite\(n\) \|\| n < 0\) return/.test(script),
    'a chapter numbered 0 is refused when it is written');
  assert.ok(/Number\(e\.number\) >= 0/.test(script),
    'the read filter disagrees with the write guard — a chapter 0 record is invisible');

  const chapterRecord = script.match(/noteOpen\(["']manga["'][\s\S]{0,400}?\}\)/);
  assert.ok(chapterRecord, 'opening a chapter is not recorded');
  assert.ok(/number:\s*Number\(/.test(chapterRecord[0]) && /ch[\s\S]{0,20}?\.number/.test(chapterRecord[0]),
    'the chapter number is not kept');
  assert.ok(/ref:[\s\S]{0,30}?ch[\s\S]{0,20}?\.id/.test(chapterRecord[0]),
    'the chapter id is not kept — the rail could not resume the chapter it names');

  const episodeRecord = script.match(/noteOpen\(["']anime["'][\s\S]{0,400}?\}\)/);
  assert.ok(episodeRecord, 'playing an episode is not recorded');
  assert.ok(/number:\s*ep\b/.test(episodeRecord[0]), 'the episode number is not kept');
  assert.ok(/total:/.test(episodeRecord[0]),
    'an episode count is not kept — the card has no progress to draw');

  // Two rails, one per area, each with an order of its own and a way to drop
  // an entry; both open through the area's own sheet rather than a second copy
  // of that logic.
  assert.ok(html.includes('id="read-rail"') && html.includes('id="watch-rail"'),
    'the device rails are not in the markup');
  assert.ok(/data-rail-clear=/.test(html), 'a rail cannot be emptied');
  assert.ok(/setOpener\(["']anime["'], openAnime\)/.test(script)
    && /setOpener\(["']manga["'], openManga\)/.test(script),
    'the rails do not open what they list through the areas themselves');

  // A remembered chapter that upstream no longer carries falls back to the
  // title page instead of the spinner it used to leave behind — and a chapter
  // number still finds its chapter when the id cannot.
  assert.ok(/findIndex\(\(?c\)?\s*=>\s*Number\(c\.id\) === Number\(chapter\)\)/.test(script),
    'a remembered chapter id is never checked against the chapter list');
  assert.ok(/findIndex\(\(?c\)?\s*=>\s*Number\(c\.number\) === Number\(chapter\)\)/.test(script),
    'a remembered chapter number has no fallback');
});

// The films & series area: f-movies.org through this server's own routes.
// Its detail page shares one surface with the anime area, and its player is
// the site's three embeds built from the title's own id — the client must
// never hold a stored URL, because there is not one to go stale.
test('the movies area searches, opens and plays through its own routes', () => {
  const script = inlineScripts(html)[0];

  // The view exists exactly once and is not the one the app boots on; the
  // shelf asks the catalog route, and every id the module queries is in the
  // markup (the missing-ids test above guards the rest).
  assert.equal((html.match(/data-view="movies"/g) || []).length, 1,
    'the movies view is missing or duplicated');
  assert.ok(/api\/fmovies\/search\?/.test(script), 'the movies shelf never asks for the catalog');
  for (const id of ['movies-grid', 'movies-q', 'movies-note', 'movies-pager',
    'movies-type', 'movies-count', 'movies-search-shell', 'movies-clear',
    'movies-prev', 'movies-next', 'movies-pageinfo', 'movies-filter-clear']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }

  // A card opens its title through the same sheet the other areas use, and
  // the back stack can return to exactly this page — kind, ref, chapter.
  assert.ok(/data-show=/.test(script), 'a show card carries no id to open');
  assert.ok(/openShow\(card\.dataset\.show\)/.test(script),
    'tapping a card does not open the title');
  assert.ok(/kind === (["'])show\1/.test(script) && /openShow\(s\.ref/.test(script),
    'the back stack cannot reopen a show page');

  // A series draws its seasons as a strip; season chips and episode rows
  // each act on the title currently open, not on a remembered one.
  assert.ok(/data-show-season=/.test(script), 'a season chip carries no number');
  assert.ok(/data-show-ep=/.test(script), 'an episode row carries no number');
  assert.ok(/episodes\?season=/.test(script), 'episodes are never asked for by season');

  // The player is the route's answer, asked with the season and episode it
  // needs; the frame keeps the id the sheet's close already knows how to
  // stop — one player, one way to end it — and the three servers are pickable.
  assert.ok(/\/player\?\$\{params\}/.test(script), 'the player route is never asked for');
  assert.ok(/season: String\(/.test(script) && /episode: String\(/.test(script),
    'the player is asked without the season and episode it needs');
  assert.ok(/id="lx-frame"/.test(script), 'the embed frame lost the id closing depends on');
  assert.ok(/data-fx-src=/.test(script), 'the three servers cannot be picked');

  // Those hosts rotate and go dark (two of the three did within a day), so the
  // doors arrive with the server's verdict on each. The one that answers is
  // what opens, and one that did not is a button that says so, not a black
  // frame the reader has to guess at.
  assert.ok(/const live = sources\.filter\(\(s\) => s\.ok !== false\)/.test(script),
    'a door that did not answer could still be the one that opens');
  assert.ok(/const first = live\[0\] \?\? sources\[0\]/.test(script),
    'the frame does not open the first door that answered');
  assert.ok(/s\.ok === false[\s\S]{0,80}disabled/.test(script),
    'a door that did not answer is offered as pressable');

  // A slow answer that lands after the reader moved on must not repaint.
  assert.ok(/seq !== openSeq/.test(script),
    'a late answer would repaint a page that moved on');

  // The pill searches whatever area is on screen — this one included.
  assert.ok(/viewIs\((["'])movies\1\)/.test(script) && /searchShows\(state\.q\)/.test(script),
    'the pill does not search the movies area');
});

// The movies area keeps what the other two areas keep: the device's own rail
// answering "where was I?", written the moment a player resolves, and a
// library of favorites and opened titles that no account and no server ever
// sees. The rail lives in the area's own view, and opens through the area's
// own sheet.
test('the movies area keeps a library and a continue-watching rail', () => {
  const script = inlineScripts(html)[0];

  // The rail's four elements are in the movies view, with the order control
  // every other shelf has, and the kind is registered so it paints, sorts
  // and empties like the anime and manga rails.
  for (const id of ['show-watch-rail', 'show-watch-head', 'show-watch-count', 'show-watch-note']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  assert.ok(/data-sort-for="show-watch"/.test(html), 'the rail has no order control');
  assert.ok(/(["'])show-watch\1: \{ orders:/.test(script),
    'no orders are declared for the show rail');
  assert.ok(/data-rail-clear="show"/.test(html), 'the rail cannot be emptied');
  assert.ok(/noteOpen\((["'])show\1/.test(script),
    'playing an episode is not recorded for the movies area');
  assert.ok(/setOpener\((["'])show\1, openShow\)/.test(script),
    'the rail does not open what it lists through the area itself');
  // A movie travels with total 0 — no fake progress bar on a film.
  assert.ok(/total: dState\d*\.type === (["'])tv\1 \? dState\d*\.episodes\.length : 0/.test(script),
    'a movie would draw a progress bar it has no progress for');

  // The library is device-local under its own key: favorites with a heart on
  // the page, history of every title opened, and both grids drawn from the
  // same cards as the shelf.
  assert.ok(/htv:shows:v1/.test(script), 'the shows library keeps nothing on the device');
  assert.ok(/data-show-fav/.test(script), 'a show page carries no favorite control');
  for (const id of ['movies-library', 'show-fav-grid', 'show-fav-count', 'show-fav-note',
    'show-hist-grid', 'show-hist-count', 'show-hist-note', 'show-hist-clear']) {
    assert.ok(html.includes(` id="${id}"`), `missing #${id}`);
  }
  // And the record of what was opened is written where the opening is.
  assert.ok(/function noteShowOpen\(\)/.test(script), 'opening a title is never remembered');
});
