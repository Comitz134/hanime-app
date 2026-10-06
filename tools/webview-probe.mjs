#!/usr/bin/env node
// Attach to the app's WebView over the DevTools socket and run a page script.
//
//   node hanime-app/tools/webview-probe.mjs <page-script.js>
//
// Requires:
//   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
//   and WebView debugging enabled (debug builds only).
//
// The page script must evaluate to a string (usually JSON.stringify of a
// results object). A result is printed on stdout; failures exit non-zero.

import fs from 'node:fs';

const scriptPath = process.argv[2];
if (!scriptPath) {
  console.error('usage: webview-probe.mjs <page-script.js>');
  process.exit(2);
}

const script = fs.readFileSync(scriptPath, 'utf8');
const list = await (await fetch('http://localhost:9222/json')).json();
const target = list.find((t) => t.url === 'https://hanime.tv/');
if (!target) {
  console.error('no https://hanime.tv/ target. targets: '
    + list.map((t) => t.url).join(', '));
  process.exit(1);
}

const ws = new WebSocket(target.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
const logs = [];
let closed = false;

// A page reload or a dead WebView closes the socket; without this the probe
// would just hang until the timeout and report nothing useful.
ws.onclose = () => {
  closed = true;
  for (const [, r] of pending) r({ closed: true });
  pending.clear();
};

ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) {
    pending.get(m.id)(m);
    pending.delete(m.id);
    return;
  }
  if (m.method === 'Runtime.consoleAPICalled') {
    logs.push(`console.${m.params.type} `
      + m.params.args.map((a) => a.value ?? a.description).join(' ').slice(0, 400));
  }
  if (m.method === 'Runtime.exceptionThrown') {
    const d = m.params.exceptionDetails;
    logs.push(`EXCEPTION ${d.exception?.description ?? d.text}`.slice(0, 500));
  }
};

await new Promise((r) => { ws.onopen = r; });
ws.send(JSON.stringify({ id: 0, method: 'Runtime.enable' }));

const i = ++id;
const reply = new Promise((r) => pending.set(i, r));
ws.send(JSON.stringify({
  id: i,
  method: 'Runtime.evaluate',
  params: { expression: script, returnByValue: true, awaitPromise: true },
}));

const timeout = new Promise((r) => setTimeout(() => r({ timeout: true }), 60_000));
const result = await Promise.race([reply, timeout]);
if (result.closed) {
  console.error('SOCKET CLOSED: the page navigated or the WebView went away');
  if (logs.length) console.error(logs.join('\n'));
  process.exit(5);
}
if (result.timeout) {
  console.error('TIMEOUT: page script did not settle within 60s');
  if (logs.length) console.error(logs.join('\n'));
  process.exit(3);
}

const d = result.result?.result;
if (d && typeof d.value === 'string') {
  console.log(d.value);
  if (logs.length) console.error('--- page console ---\n' + logs.join('\n'));
  ws.close();
} else {
  console.error('page script threw: '
    + JSON.stringify(result.result?.exceptionDetails ?? result.result, null, 1));
  if (logs.length) console.error(logs.join('\n'));
  ws.close();
  process.exit(4);
}
