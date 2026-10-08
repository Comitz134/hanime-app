// ad-guard.js — this app shows no ads and opens no popups. Not "usually":
// the doors are welded shut, here at the DOM itself.
//
// Why this exists: the manga area drives mangafire.to through a chunk of
// their own shipped bundle (mangafire-polyfill.js). That chunk does not only
// sign listing requests — it also carries their popunder machinery. On
// evaluation it appends a full-screen invisible `<a target="_blank">` over
// the whole viewport (z-index: 9999999) pointing at an ad network, so every
// tap becomes an ad click. Their payload strings are encrypted inside the
// vendor file and cannot be grepped out with confidence, so instead every
// door the payload can use is closed here:
//
//   1. insertion hooks  — no external anchor ever enters the DOM, whichever
//                         path it arrives by (appendChild, innerHTML's
//                         resulting subtree, a MutationObserver sweep);
//   2. a capture click   — anything that still manages to sit under a finger
//                          never navigates;
//   3. window.open       — no popunder opens in the browser copy either;
//   4. the app's Java side swallows external navigations as a last resort
//      (MainActivity.shouldOverrideUrlLoading), so even a hypothetical
//      `location.href = ...` from vendor code goes nowhere.
//
// The rule is absolute on purpose: this client renders no external links at
// all. An in-app link to somewhere else is a bridge call ("Open in
// browser"), never an anchor — so any external anchor in the DOM is by
// definition not ours and gets removed. The MyAnimeList login is a redirect
// (location assignment), not an anchor, so it passes untouched.
//
// This module runs before every other import in main.js: the guard must
// patch the prototypes before the vendor chunk evaluates. It imports nothing.

let blocked = 0;

const mark = (why) => {
  blocked += 1;
  if (window.__adGuard) window.__adGuard.blocked = blocked;
  return why;
};

/**
 * External = an absolute (or protocol-relative) URL whose ORIGIN differs
 * from ours. Compared by parsed origin, not by prefix: `https://hanime.tv.
 * evil.example/` must not pass for home just because it starts with it.
 */
const external = (href) => {
  if (!/^(?:https?:)?\/\//i.test(href)) return false;
  try {
    return new URL(href, window.location.origin).origin !== window.location.origin;
  } catch (e) {
    return true;   // unparsable absolute URL: foreign by definition
  }
};

/** True when this node is itself an external anchor. */
const isAdAnchor = (el) => !!(
  el && el.nodeType === 1 && el.tagName === 'A' && external(el.getAttribute('href') || '')
);

/** Remove every external anchor found inside a node (node itself excluded). */
function purge(root) {
  if (!root || root.nodeType !== 1 || !root.querySelectorAll) return;
  const found = root.querySelectorAll('a[href]');
  for (const a of found) {
    if (isAdAnchor(a)) {
      mark('purged');
      a.remove();
    }
  }
}

/** Insertion gate: bad anchors never land; clean subtrees pass after a sweep. */
function admit(node) {
  if (isAdAnchor(node)) {
    mark('dropped');
    return false;
  }
  purge(node);
  return true;
}

for (const name of ['appendChild', 'insertBefore']) {
  const original = Element.prototype[name];
  Element.prototype[name] = function guarded(node, ...rest) {
    if (!admit(node)) return node;
    return original.call(this, node, ...rest);
  };
}

for (const name of ['append', 'prepend']) {
  const original = Element.prototype[name];
  Element.prototype[name] = function guarded(...nodes) {
    const keep = nodes.filter((n) => admit(n));
    return original.call(this, ...keep);
  };
}

{
  const original = Element.prototype.replaceChildren;
  Element.prototype.replaceChildren = function guarded(...nodes) {
    return original.call(this, ...nodes.filter((n) => admit(n)));
  };
}

// Belt for anything innerHTML-shaped: sweep whatever appears, once it appears.
const sweep = (nodes) => {
  for (const node of nodes) {
    if (node.nodeType !== 1) continue;
    if (isAdAnchor(node)) { mark('observed'); node.remove(); continue; }
    purge(node);
  }
};
const startSweep = () => {
  if (!document.documentElement) { setTimeout(startSweep, 0); return; }
  new MutationObserver((records) => {
    for (const r of records) sweep(r.addedNodes);
  }).observe(document.documentElement, { childList: true, subtree: true });
};
startSweep();

// Capture-phase click: a finger on an external anchor does nothing, ever.
const cancel = (e) => {
  const path = typeof e.composedPath === 'function' ? e.composedPath() : [e.target];
  for (const el of path) {
    if (el && el.nodeType === 1 && el.tagName === 'A' && external(el.getAttribute('href') || '')) {
      mark('click');
      e.preventDefault();
      e.stopPropagation();
      return;
    }
  }
};
document.addEventListener('click', cancel, true);
document.addEventListener('auxclick', cancel, true);

// The browser copy would happily open a popunder; there is no legitimate
// window.open caller in this client, so there is no legitimate window.open.
window.open = () => {
  mark('window.open');
  return null;
};

// Observability for tests and probes: how many attempts were stopped.
window.__adGuard = { blocked };
