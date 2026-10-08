// The ad guard is a promise the app makes: no ads, no popups — so it gets a
// behavioural test, not just a presence check. ad-guard.js has no imports and
// never touches a real browser API that Node lacks, which means its source can
// be executed verbatim against a small DOM stub and its refusals asserted:
// an injected overlay anchor must never enter the DOM, a tap on one must be
// cancelled, window.open must be dead, and the MutationObserver sweep must
// catch anchors that arrive the way mangafire's chunk delivers its own:
// appended under the body, full-screen, target=_blank.
//
// The swept-out counter the guard keeps (window.__adGuard.blocked) is also
// what the on-device probe reads, so a regression here is one the emulator
// run would see too.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const GUARD = path.resolve(HERE, '../client/src/ad-guard.js');
const BUNDLE = path.resolve(HERE, '../public/index.html');
const source = fs.readFileSync(GUARD, 'utf8');

/** A DOM small enough to read and large enough to lie to the guard with. */
function boot() {
  class TextNode {
    constructor(data) { this.nodeType = 3; this.data = data; }
  }

  class El {
    constructor(tag) {
      this.nodeType = 1;
      this.tagName = String(tag).toUpperCase();
      this.children = [];
      this.attrs = {};
      this.parentNode = null;
    }
    getAttribute(n) {
      return Object.prototype.hasOwnProperty.call(this.attrs, n) ? this.attrs[n] : null;
    }
    setAttribute(n, v) { this.attrs[n] = String(v); }
    remove() {
      if (!this.parentNode) return;
      const i = this.parentNode.children.indexOf(this);
      if (i >= 0) this.parentNode.children.splice(i, 1);
      this.parentNode = null;
    }
    #land(c) {
      if (c.parentNode) c.parentNode.removeChild
        ? c.parentNode.removeChild(c)
        : c.remove();
      c.parentNode = this;
    }
    appendChild(c) { this.#land(c); this.children.push(c); return c; }
    insertBefore(c, ref) {
      this.#land(c);
      const i = this.children.indexOf(ref);
      if (i < 0) this.children.push(c);
      else this.children.splice(i, 0, c);
      return c;
    }
    removeChild(c) {
      const i = this.children.indexOf(c);
      if (i >= 0) this.children.splice(i, 1);
      c.parentNode = null;
      return c;
    }
    append(...nodes) {
      for (const n of nodes) {
        this.appendChild(typeof n === 'string' ? new TextNode(n) : n);
      }
    }
    prepend(...nodes) {
      const made = nodes.map((n) => (typeof n === 'string' ? new TextNode(n) : n));
      for (const c of this.children) c.parentNode = null;
      this.children = [];
      this.append(...made);
    }
    replaceChildren(...nodes) {
      for (const c of this.children) c.parentNode = null;
      this.children = [];
      this.append(...nodes);
    }
    querySelectorAll(sel) {
      // The guard only ever asks for 'a[href]'.
      assert.equal(sel, 'a[href]', 'the guard asked for an unexpected selector');
      const out = [];
      const walk = (n) => {
        for (const c of n.children) {
          if (c.tagName === 'A' && c.getAttribute('href') !== null) out.push(c);
          walk(c);
        }
      };
      walk(this);
      return out;
    }
  }

  const listeners = {};
  const document = {
    documentElement: new El('html'),
    addEventListener(type, fn) {
      (listeners[type] || (listeners[type] = [])).push(fn);
    },
  };
  const body = new El('body');
  document.documentElement.appendChild(body);

  let observerCallback = null;
  class MutationObserver {
    constructor(cb) { observerCallback = cb; }
    observe() { /* observation target is irrelevant to the assertions */ }
  }

  const window = {
    location: { origin: 'https://hanime.tv' },
    open: () => 'a real window',   // what a browser would hand back
  };

  const sandbox = { Element: El, document, window, MutationObserver, setTimeout, URL, console };
  vm.runInNewContext(source, sandbox, { filename: 'ad-guard.js' });

  const adAnchor = (href) => {
    const a = new El('a');
    a.setAttribute('href', href);
    a.setAttribute('target', '_blank');
    a.setAttribute('style', 'position: fixed;top: 0;left: 0;width: 100%;'
      + 'height: 100%;display: block;z-index: 9999999;cursor: auto');
    return a;
  };

  return {
    window, document, body, El,
    blocked: () => window.__adGuard.blocked,
    fireClick: (handlers, event) => handlers.forEach((fn) => fn(event)),
    clickHandlers: () => listeners.click || [],
    sweep: (nodes) => observerCallback && observerCallback([{ addedNodes: nodes }]),
    adAnchor,
  };
}

test('the shipped bundle evaluates the guard before the vendor chunk', () => {
  const html = fs.readFileSync(BUNDLE, 'utf8');
  const guardAt = html.indexOf('__adGuard');
  assert.ok(guardAt > -1, 'the ad guard is missing from the committed bundle');
  // RXxgAQ is the vendor chunk's obfuscator signature (mangafire's polyfill).
  const vendorAt = html.indexOf('RXxgAQ');
  assert.ok(vendorAt > -1, 'the vendor chunk marker moved — update this test');
  assert.ok(guardAt < vendorAt, 'the guard must be installed before the vendor chunk evaluates');
});

test('a full-screen ad anchor never enters the DOM', () => {
  const t = boot();
  const ad = t.adAnchor('https://sowve.com/4/739684b2a2af3eaf9109e8ffbaec4993');
  const returned = t.body.appendChild(ad);
  assert.equal(returned, ad, 'appendChild must still return its argument');
  assert.deepEqual(t.body.children, [], 'the ad anchor was admitted');
  assert.equal(t.blocked(), 1, 'the attempt was not counted');
});

test('ad anchors hide inside a subtree, and are stripped there', () => {
  const t = boot();
  const container = new t.El('div');
  const text = new t.El('span');
  const ad = t.adAnchor('https://sowve.com/4/x');
  container.appendChild(text);
  container.appendChild(ad);
  t.body.appendChild(container);
  assert.deepEqual(t.body.children, [container], 'the clean part of the subtree is dropped too');
  assert.deepEqual(container.children, [text], 'the ad anchor inside was stripped');
  assert.equal(t.blocked(), 1);
});

test('our own links — relative and same-origin — are untouched', () => {
  const t = boot();
  const own = new t.El('a');
  own.setAttribute('href', '/watch/123');
  const absolute = new t.El('a');
  absolute.setAttribute('href', 'https://hanime.tv/browse');
  t.body.appendChild(own);
  t.body.appendChild(absolute);
  assert.deepEqual(t.body.children, [own, absolute]);
  assert.equal(t.blocked(), 0);
});

test('a look-alike origin does not pass for home', () => {
  const t = boot();
  const look = new t.El('a');
  look.setAttribute('href', 'https://hanime.tv.evil.example/x');
  t.body.appendChild(look);
  assert.deepEqual(t.body.children, [], 'prefix matching let a foreign origin through');
  assert.equal(t.blocked(), 1);
});

test('window.open is dead in every copy', () => {
  const t = boot();
  assert.equal(t.window.open('https://sowve.com/4/x'), null);
  assert.equal(t.blocked(), 1);
});

test('a tap on an external anchor is cancelled even if it slipped in', () => {
  const t = boot();
  // Bypass every insertion hook the way an innerHTML parse would.
  const ad = t.adAnchor('https://sowve.com/4/739684b2a2af3eaf9109e8ffbaec4993');
  t.body.children.push(ad);
  ad.parentNode = t.body;

  let prevented = false;
  let stopped = false;
  t.fireClick(t.clickHandlers(), {
    target: ad,
    composedPath: () => [ad, t.body],
    preventDefault: () => { prevented = true; },
    stopPropagation: () => { stopped = true; },
  });
  assert.ok(prevented, 'the click still navigated');
  assert.ok(stopped, 'the click still bubbled');
  assert.equal(t.blocked(), 1);
});

test('a tap on our own link is passed through', () => {
  const t = boot();
  const own = new t.El('a');
  own.setAttribute('href', '/settings');
  let prevented = false;
  t.fireClick(t.clickHandlers(), {
    target: own,
    composedPath: () => [own, t.body],
    preventDefault: () => { prevented = true; },
    stopPropagation: () => { throw new Error('our own link must bubble'); },
  });
  assert.equal(prevented, false);
  assert.equal(t.blocked(), 0);
});

test('the observer sweep removes anchors that arrive any other way', () => {
  const t = boot();
  const container = new t.El('div');
  const ad = t.adAnchor('https://sowve.com/4/x');
  container.children.push(ad);
  ad.parentNode = container;
  t.sweep([container]);
  assert.deepEqual(container.children, [], 'the sweep left an ad anchor behind');
  assert.equal(t.blocked(), 1);
});
