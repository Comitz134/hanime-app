---
id: phone-release-is-two-publishes
title: "A phone release is two publishes, and only the second reaches a phone"
category: decision
status: active
tags: [release, phone, github]
created: "2026-10-21T02:06:28"
updated: "2026-10-21T04:00:14"
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
