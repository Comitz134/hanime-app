#!/usr/bin/env node
// Read and touch the app's WebView over the DevTools socket.
//
//   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
//
//   node tools/webview-tap.mjs --list
//   node tools/webview-tap.mjs --text                     the page's own words
//   node tools/webview-tap.mjs --find "button, a[href]"   every match, with its box
//   node tools/webview-tap.mjs --tap "article.card"       a real touch, centred
//   node tools/webview-tap.mjs --at 540,960
//   node tools/webview-tap.mjs --wheel 600                scroll what is under the middle
//   node tools/webview-tap.mjs --type "liar liar"         into whatever is focused
//
//   ...add --frame flixcloud.cc to any of those to work inside the player frame.
//
// Why this exists next to webview-probe.mjs: that one runs a page script and
// reads its answer, which is enough to inspect a page and not enough to *use*
// one. Fullscreen, playback and every other media path in the client are gated
// on user activation, and `element.click()` from a script is not a user: the
// request is refused before it reaches the shell. So the button has to be
// pressed the way a thumb presses it — a touch event at its own coordinates —
// which is what this sends. The --find and --text modes are the other half:
// with a phone in hand and no way to see the screen, the DOM is the eyes.
//
// The player is a cross-origin iframe the app never sees into, and it is not a
// separate target — it runs in-process — so `--frame` reaches it the only way
// the protocol offers: Runtime.enable names an execution context per frame, and
// an evaluate can be sent to one of them. A control found there is measured in
// the frame's own viewport, so a tap is dispatched at the frame's page offset
// plus its own box: the offset comes from DOM.getFrameOwner walked up to the
// main frame, which is what makes an iframe control pressable at all.

const argv = process.argv.slice(2);
const has = (flag) => argv.includes(flag);
const val = (flag, fallback) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const PORT = process.env.WEBVIEW_PROBE_PORT || '9222';
const MATCH = val('--target', process.env.WEBVIEW_PROBE_ORIGIN || 'hanime.tv');
const FRAME = val('--frame', null);

const list = await (await fetch(`http://localhost:${PORT}/json/list`)).json();
if (has('--list')) {
  for (const t of list) console.log(`${t.type ?? 'page'}\t${t.url}`);
  process.exit(0);
}

// The page itself (not the hidden signer, not an embed). Two targets share the
// app's origin — `https://hanime.tv/` and the invisible `signer.html` the shell
// keeps for its WASM module — and the signer is listed first, so "contains" is
// not enough: an exact URL wins, then a top-level page over a frame, then the
// shorter URL.
const candidates = list.filter((t) => t.url.includes(MATCH));
const target = candidates.slice().sort((a, b) => {
  if (a.url === MATCH) return -1;
  if (b.url === MATCH) return 1;
  if (a.type === 'iframe' && b.type !== 'iframe') return 1;
  if (b.type === 'iframe' && a.type !== 'iframe') return -1;
  return a.url.length - b.url.length;
})[0];
if (!target) {
  console.error(`no target matching "${MATCH}". targets:\n  `
    + list.map((t) => `${t.type ?? 'page'} ${t.url}`).join('\n  '));
  process.exit(1);
}

const ws = new WebSocket(target.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
const contexts = [];
let closed = false;
ws.onclose = () => { closed = true; for (const [, r] of pending) r({ closed: true }); pending.clear(); };
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method === 'Runtime.executionContextCreated') contexts.push(m.params.context);
};
await new Promise((r) => { ws.onopen = r; });

// Runtime.enable is also what names the frames: every execution context the
// page already has is replayed on enable, which is how a cross-origin frame
// becomes reachable at all.
ws.send(JSON.stringify({ id: 0, method: 'Runtime.enable' }));
await new Promise((r) => setTimeout(r, 400));

function send(method, params = {}) {
  const i = ++id;
  const reply = new Promise((r) => pending.set(i, r));
  ws.send(JSON.stringify({ id: i, method, params }));
  return Promise.race([
    reply,
    new Promise((r) => setTimeout(() => r({ timeout: true }), 30_000)),
  ]);
}

/** The context to evaluate in: the frame asked for, or the page's own. */
function contextId() {
  if (!FRAME) return undefined;
  // `origin` is the frame's own origin, which is what a caller knows; the
  // signer and the app share one, so a frame is always named by its host.
  const match = contexts.find((c) => (c.origin ?? '').includes(FRAME));
  if (!match) {
    throw new Error(`no frame matching "${FRAME}". frames: `
      + contexts.map((c) => c.origin).join(', '));
  }
  return match.id;
}

async function evaluate(expression) {
  const params = { expression, returnByValue: true, awaitPromise: true };
  const ctx = contextId();
  if (ctx !== undefined) params.contextId = ctx;
  const r = await send('Runtime.evaluate', params);
  if (r.timeout || r.closed) throw new Error(r.closed ? 'socket closed' : 'timed out');
  if (r.result?.exceptionDetails) {
    throw new Error(r.result.exceptionDetails.text + ' '
      + (r.result.exceptionDetails.exception?.description ?? ''));
  }
  return r.result?.result?.value;
}

/** A selector quoted for the page, since it arrives from the shell. */
const quote = (s) => JSON.stringify(s);

/** Where an element is, in the coordinates of whoever is running this. */
const boxOf = (sel, index = 0) => `
  (() => {
    const els = [...document.querySelectorAll(${quote(sel)})];
    const el = els[${index}];
    if (!el) return null;
    const r = el.getBoundingClientRect();
    const label = el.getAttribute('aria-label') || el.title || el.innerText || el.id || el.className;
    return JSON.stringify({
      x: Math.round(r.x + r.width / 2),
      y: Math.round(r.y + r.height / 2),
      w: Math.round(r.width), h: Math.round(r.height),
      label: String(label ?? '').replace(/\\s+/g, ' ').trim().slice(0, 80),
    });
  })()`;

/**
 * How far a frame is from the top-left of the page, by walking its owners.
 *
 * Input cannot be dispatched into a frame: it goes to the page, at page
 * coordinates, and the browser routes it. So a control inside a frame is
 * pressed at the sum of its own box and every frame edge above it. The walk is
 * what makes a nested frame land on its button rather than near it.
 */
async function frameOffset(frameId) {
  await send('DOM.enable');
  const tree = await send('Page.getFrameTree');
  const parents = new Map();
  const walk = (node, parent) => {
    parents.set(node.frame.id, parent);
    for (const child of node.childFrames ?? []) walk(child, node.frame.id);
  };
  walk(tree.result.frameTree, null);

  let offset = { x: 0, y: 0 };
  let current = frameId;
  while (parents.get(current)) {
    const owner = await send('DOM.getFrameOwner', { frameId: current });
    // getFrameOwner answers with a *backend* node id, and getBoxModel takes it
    // only in its own field: handed over as `nodeId` it finds no node at all and
    // the model comes back empty, which reads as a frame sitting at 0,0 — and a
    // tap that lands nowhere near the button it was aimed at.
    const backendNodeId = owner.result?.backendNodeId;
    const model = backendNodeId
      ? await send('DOM.getBoxModel', { backendNodeId })
      : await send('DOM.getBoxModel', { nodeId: owner.result?.nodeId });
    const quad = model.result?.model?.content;
    if (!quad) break;
    offset = { x: offset.x + quad[0], y: offset.y + quad[1] };
    current = parents.get(current);
  }
  return offset;
}

async function tapAt(x, y) {
  const point = { x, y, radiusX: 2, radiusY: 2, force: 1 };
  const start = await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] });
  await new Promise((r) => setTimeout(r, 60));
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  // Fall back to a mouse click when the view refuses touch: the call still
  // counts as a user gesture, which is the part the media paths need.
  if (start.error) {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
    return 'mouse';
  }
  return 'touch';
}

let code = 0;
try {
  if (has('--text')) {
    const limit = Number(val('--limit', '2500'));
    console.log(await evaluate(`document.body.innerText.replace(/\\n{2,}/g,'\\n').slice(0, ${limit})`));
  } else if (has('--find')) {
    const sel = val('--find');
    const out = await evaluate(`
      (() => {
        const els = [...document.querySelectorAll(${quote(sel)})].slice(0, 40);
        return els.map((el, i) => {
          const r = el.getBoundingClientRect();
          const label = el.getAttribute('aria-label') || el.title || el.innerText || el.id || el.className;
          return [i, el.tagName.toLowerCase(), Math.round(r.x), Math.round(r.y),
                  Math.round(r.width) + 'x' + Math.round(r.height),
                  String(label ?? '').replace(/\\s+/g, ' ').trim().slice(0, 70)].join('\\t');
        }).join('\\n');
      })()`);
    console.log(out || '(no match)');
  } else if (has('--eval')) {
    const out = await evaluate(val('--eval'));
    console.log(typeof out === 'string' ? out : JSON.stringify(out, null, 1));
  } else if (has('--tap')) {
    // Every --tap in one run is performed in order, in one connection: a player
    // that hides its controls after a few seconds cannot be operated by a tool
    // that takes longer than that to start, so revealing the bar and pressing a
    // button on it have to be the same breath. Repeat the flag for each press —
    // `--tap a --tap b` — since a bare second value is indistinguishable from
    // the argument of another flag (`--index`).
    const selectors = [];
    for (let i = 0; i < argv.length; i += 1) {
      if (argv[i] === '--tap' && argv[i + 1] && !argv[i + 1].startsWith('--')) {
        selectors.push(argv[i + 1]);
      }
    }
    const index = Number(val('--index', '0'));
    let frameId = null;
    if (FRAME) {
      const ctx = contexts.find((c) => (c.origin ?? '').includes(FRAME));
      frameId = ctx.auxData.frameId;
    }
    for (const sel of selectors) {
      const raw = await evaluate(boxOf(sel, index));
      if (!raw) { console.error(`nothing matches ${sel}`); code = 1; break; }
      const b = JSON.parse(raw);
      let { x, y } = b;
      if (frameId) {
        const off = await frameOffset(frameId);
        x += off.x;
        y += off.y;
      }
      const how = await tapAt(x, y);
      console.log(`${how} at ${x},${y} — ${b.w}x${b.h} "${b.label}"`);
      if (selectors.length > 1) await new Promise((r) => setTimeout(r, 300));
    }
  } else if (has('--at')) {
    const [x, y] = val('--at').split(',').map(Number);
    console.log(`${await tapAt(x, y)} at ${x},${y}`);
  } else if (has('--wheel')) {
    const dy = Number(val('--wheel'));
    const centre = JSON.parse(await evaluate('JSON.stringify([Math.round(innerWidth/2), Math.round(innerHeight/2)])'));
    await send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: centre[0], y: centre[1], deltaX: 0, deltaY: dy });
    console.log(`wheel ${dy} at ${centre.join(',')}`);
  } else if (has('--type')) {
    const text = val('--type');
    await send('Input.insertText', { text });
    console.log(`typed "${text}"`);
  } else {
    console.error('nothing to do: pass one of --list --text --find --eval --tap --at --wheel --type');
    code = 2;
  }
} catch (e) {
  console.error(`failed: ${e.message}`);
  code = 3;
}
ws.close();
process.exit(code);
