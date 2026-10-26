---
id: phone-release-is-two-publishes
title: "A phone release is two publishes, and only the second reaches a phone"
category: decision
status: active
tags: [release, phone, github]
created: "2026-10-21T02:06:28"
updated: "2026-10-26T23:49:27"
---

<!-- compiled_truth -->
A phone release needs publish-apk.mjs and publish-github.mjs; only the GitHub upload is what an installed phone updates from.

## What was decided

A phone release is not one step. After bumping
`shell-mobile/overrides/iosApp/Configuration/Version.xcconfig`
(`CURRENT_PROJECT_VERSION` and `MARKETING_VERSION`) and building the signed
universal APK, both of these run:

1. `node scripts/publish-apk.mjs <apk> --notes "..."` - writes the manifest this
   checkout's own server answers with (`/api/app/version`) and copies the APK
   into `server/data/apk/`. Reachable only while this PC is up.
2. `node --env-file=../.env scripts/publish-github.mjs --repo Comitz134/hanime-app --notes "..."` -
   uploads the APK and `version.json` to the `v<version>` GitHub release. **This
   is the release a phone actually updates from.**

## Why

`/releases/latest` is the phone's manifest URL, and the phone app resolves it
from GitHub, not from this PC. Running only step 1 leaves every installed phone
on the previous build while both logs read as published - it has happened, which
is why `AGENTS.md` states the rule twice.

## How a release is verified

- `https://github.com/Comitz134/hanime-app/releases/latest/download/version.json`
  must name the version code just built (2.0.2 / 202 was checked this way).
- The release must not be a prerelease, or `/releases/latest` will not resolve to
  it.
- The APK keeps the old shell's package id (`app.hanime.shell`) and is signed
  with the release key from `android/keystore.properties` (gitignored, handed to
  Gradle through the environment). A build signed with anything else is not an
  upgrade, it is a reinstall.


## Timeline

- time: 2026-10-21T02:06:28
  kind: decision
  summary: "Created this page: A phone release is two publishes, and only the second reaches a phone"
  source: "AGENTS.md, session that shipped 2.0.2"
  affects: [phone-release-is-two-publishes]

- time: 2026-10-21T02:06:28
  kind: decision
  summary: "recorded the two-publish rule, the failure it prevents, and how a release is verified"
  source: "AGENTS.md standing instructions, publish-apk.mjs / publish-github.mjs"
  affects: [phone-release-is-two-publishes]

- time: 2026-10-21T02:06:46
  kind: decision
  summary: index summary now reads as a sentence
  source: reworded the opening so the index summary reads as a sentence
  affects: [phone-release-is-two-publishes]

- time: 2026-10-21T04:00:14
  kind: note
  summary: "2.0.3 (203) published through both scripts; the tag v2.0.3 points at the released commit 9fe24ea, and the notes now name that commit as the source (README requires it for GPL)"
  source: "publish-apk.mjs, publish-github.mjs, release notes PATCH"
  affects: [phone-release-is-two-publishes]

- time: 2026-10-25T00:44:27
  kind: note
  summary: "2.0.5 (205) published through both scripts from 6c00944; /releases/latest/download/version.json names 205, release is not a prerelease, and the release APK installed over adb this time (the 2.0.4 install gate did not recur)"
  source: "publish-apk.mjs, publish-github.mjs, adb install on 52070e9f"
  affects: [phone-release-is-two-publishes]

- time: 2026-10-25T01:14:14
  kind: note
  summary: "2.0.6 (206) published through both scripts from cda9c19; publish-apk and publish-github agreed on sha256 faf58d91, and /releases/latest/download/version.json names 206. The release APK was uninstalled and installed clean on 52070e9f first: Home opened on 5 catalogues with the seeded addon, and a search still reached Mangafire, MangaDex and Omega Scans."
  source: "publish-apk.mjs, publish-github.mjs, release URL, phone 52070e9f"
  affects: [phone-release-is-two-publishes]

- time: 2026-10-25T05:20:00
  kind: note
  summary: "2.0.7 (207) published through both scripts from 83fe322; both agreed on sha256 992c4491, /releases/latest/download/version.json names 207, the release is not a prerelease (APK 128,028,111 bytes + version.json), and the APK was clean-installed on 52070e9f first. HyperOS refused the fresh package with INSTALL_FAILED_USER_RESTRICTED until Developer options -> Install via USB was switched on and the adb server was restarted; upgrades of the already-installed debug package had worked all along, which is what made the gate look install-specific."
  source: "publish-apk.mjs, publish-github.mjs, GitHub API, phone 52070e9f"
  affects: [phone-release-is-two-publishes, manga-reader-panel-and-migration]

- time: 2026-10-26T23:49:27
  kind: note
  summary: "2.0.8 (208) published through both scripts from 3bb6e44; publish-apk and publish-github agreed on sha256 92c81e68, /releases/latest/download/version.json names 208 with size 128045483, the release is not a prerelease, and v2.0.8 points at 3bb6e44. The universal APK carries the account-carried shelf and MAL session, the last-chapter-number length rule and the global suggestion list. No phone was attached to adb at publish time, so no install was attempted."
  source: "publish-apk.mjs, publish-github.mjs, GitHub API, git ls-remote, adb devices"
  affects: [phone-release-is-two-publishes, account-carries-manga-shelf-and-mal, manga-length-is-last-chapter-number, manga-global-suggestions]
