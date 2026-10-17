// Where a desktop release lives, and why it is not where a phone release lives.
//
// One repository publishes two clients that number themselves independently, so
// a bare `v<version>` tag is a name both lines can reach: desktop 1.0.9 and
// phone 1.0.9 are the same string, and the second publish to land there merged
// itself into the first one's release page — its zip and manifest beside the
// phone's APK, its notes replacing the phone's. The tag namespace exists to make
// that impossible, and the window's update check reads tags through the same
// transformation, so both halves are tested here.

import test from 'node:test';
import assert from 'node:assert/strict';

const desktop = await import('../scripts/publish-desktop.mjs');
const gh = await import('../scripts/publish-github.mjs');

test('a desktop release is tagged in its own namespace', () => {
  assert.equal(desktop.desktopReleaseTag('1.0.9'), 'desktop-v1.0.9');
  assert.equal(desktop.desktopReleaseTag('1.0.10'), 'desktop-v1.0.10');
});

test('an already-v version name is not doubled in the namespace', () => {
  assert.equal(desktop.desktopReleaseTag('v1.0.9'), 'desktop-v1.0.9');
  assert.equal(desktop.desktopReleaseTag(' 1.0.9 '), 'desktop-v1.0.9');
});

test('a desktop release with no version name cannot be tagged', () => {
  for (const bad of ['', '   ', null, undefined]) {
    assert.throws(() => desktop.desktopReleaseTag(bad), /no version_name/, `accepted ${JSON.stringify(bad)}`);
  }
});

test('a desktop tag can never equal a phone tag', () => {
  // The whole point of the namespace, stated as the property that must hold for
  // every version either line will ever use — including versions both lines
  // share, which is exactly the case that broke.
  for (const version of ['1.0.9', '1.0.10', '2', '10.3.4']) {
    assert.notEqual(desktop.desktopReleaseTag(version), gh.releaseTag(version));
  }
});

test('the window reads a desktop tag back as a plain version', () => {
  // What the update check compares its own version against. Returning the tag
  // as-is would leave `desktop-v1.0.10` parsed as no version at all, which is an
  // update the window can never see.
  assert.equal(desktop.desktopTagVersion('desktop-v1.0.9'), '1.0.9');
  assert.equal(desktop.desktopTagVersion('desktop-v1.0.10'), '1.0.10');
  assert.equal(desktop.desktopTagVersion('  Desktop-V2.0.0 '), '2.0.0');
});

test('every tag the publisher makes reads back as the version it names', () => {
  // The publish refuses a tag that fails this, so the property has to hold for
  // every version shape the properties file can hold — not just the current one.
  for (const version of ['1.0.9', '1.0.10', '0.1.29-alpha', '2.0.0-rc.1', '12.34.56']) {
    assert.equal(desktop.desktopTagVersion(desktop.desktopReleaseTag(version)), version);
  }
});

test('a tag that is not a desktop tag has no desktop version', () => {
  for (const tag of ['v1.0.9', 'desktop', 'desktop-', 'channel-beta', '', null, undefined]) {
    assert.equal(desktop.desktopTagVersion(tag), null, `claimed ${JSON.stringify(tag)}`);
  }
});

test('the zip is named for the platform and the version, not the tag', () => {
  assert.equal(
    desktop.desktopAssetName('1.0.9'),
    'SEKAI-Windows-x64-1.0.9.zip',
  );
});
