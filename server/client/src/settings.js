// settings.js — the Settings view.
//
// Two concerns live here. The rows that report the build can only be filled
// by the shell (only Android knows the version it installed), and the two
// actions hand control back to Android: an interactive update check, and the
// native settings screen where the server URL is chosen. In a plain browser
// there is no Shell object, so the view says that plainly instead of showing
// buttons that would do nothing — the same guard every other bridge call in
// this client uses.

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
  const note = $('#set-note');
  note.hidden = inApp;
  if (!inApp) {
    note.textContent = 'This is the browser copy — update checks and server '
      + 'settings live in the Android app.';
  }
}

// Interactive by design: the app answers with its own dialog — an update
// offered, or this build already the newest — rather than the page speaking
// for it. Outside the app the button is hidden and this is a no-op.
$('#set-check').addEventListener('click', () => shellCall('checkForUpdate'));
$('#set-server-settings').addEventListener('click', () => shellCall('openServerSettings'));

// Refilled on the way in: the server can change behind this view, in the
// native settings screen the second button opens.
document.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go && go.dataset.go === 'settings') fillSettings();
});

export { fillSettings };
