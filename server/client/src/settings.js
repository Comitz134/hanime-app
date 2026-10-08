// settings.js — the Settings view.
//
// Three concerns live here. The rows that report the build can only be filled
// by the shell (only Android knows the version it installed), and the actions
// hand control back to Android: an interactive update check, the native
// screen where the update source is chosen, and the current page in the real
// browser. All of them — plus a plain reload — used to sit behind the floating
// ⋮ button over the top-right of the page; this view is where they belong.
// In a plain browser there is no Shell object, so the view says that plainly
// instead of showing buttons that would do nothing — the same guard every
// other bridge call in this client uses.

import { $ } from './core.js';
import { shellApi, shellCall } from './shell.js';

function fillSettings() {
  const inApp = !!shellApi();
  const version = shellCall('versionName');
  const code = shellCall('versionCode');
  const server = shellCall('serverUrl');
  $('#set-version').textContent = inApp ? `${version} (${code})` : 'web copy';
  $('#set-server').textContent = inApp ? (server || '') : location.origin;
  $('#set-check').hidden = !inApp;
  $('#set-server-settings').hidden = !inApp;
  // Reloading means the same thing in both copies; handing the page to the
  // browser is something only the app can do.
  $('#set-browser').hidden = !inApp;
  const note = $('#set-note');
  note.hidden = inApp;
  if (!inApp) {
    note.textContent = 'This is the browser copy — update checks and the '
      + 'update source live in the Android app.';
  }
}

// Interactive by design: the app answers with its own dialog — an update
// offered, or this build already the newest — rather than the page speaking
// for it. Outside the app the button is hidden and this is a no-op.
$('#set-check').addEventListener('click', () => shellCall('checkForUpdate'));
$('#set-server-settings').addEventListener('click', () => shellCall('openServerSettings'));
$('#set-browser').addEventListener('click', () => shellCall('openInBrowser'));
// Same document, same origin — the WebView reloads its bundled client, the
// browser its own copy.
$('#set-reload').addEventListener('click', () => location.reload());

// Refilled on the way in: the server can change behind this view, in the
// native settings screen the second button opens.
document.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go && go.dataset.go === 'settings') fillSettings();
});

export { fillSettings };
