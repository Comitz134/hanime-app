---
id: desktop-releases-tagged-namespaced
title: "Desktop releases are prereleases tagged desktop-v<version>"
category: decision
status: active
tags: [release, desktop, github]
created: "2026-10-21T02:06:28"
updated: "2026-10-21T02:06:46"
---

<!-- compiled_truth -->
Desktop builds ship as GitHub prereleases tagged desktop-v<version>, so the phone's manifest URL keeps resolving to phone releases.

## What was decided

Desktop releases are published as **prereleases** tagged `desktop-v<version>`,
never as a bare `v<version>`.

## Why

The two clients version themselves independently in one repository, so a bare
tag belongs to whichever line reached that number first - phone 1.0.9 already
existed when the desktop line reached 1.0.9, and `publish-desktop.mjs` merged its
zip and `desktop.json` into that phone release and replaced its notes.

The prerelease flag is a second, separate protection: `/releases/latest` is the
**phone's** manifest URL and resolves by position, so a desktop release that
counted as "latest" would hand phones a desktop zip. The window strips the
`desktop-v` prefix before comparing versions (`releaseVersionTag` in the
updater), so the namespace costs nothing on the client side.


## Timeline

- time: 2026-10-21T02:06:28
  kind: decision
  summary: "Created this page: Desktop releases are prereleases tagged desktop-v<version>"
  source: "AGENTS.md, commit 8bf43cf"
  affects: [desktop-releases-tagged-namespaced]

- time: 2026-10-21T02:06:28
  kind: decision
  summary: recorded why the desktop line namespaces its tags and stays a prerelease
  source: "AGENTS.md, publish-desktop.mjs"
  affects: [desktop-releases-tagged-namespaced]

- time: 2026-10-21T02:06:46
  kind: decision
  summary: index summary now reads as a sentence
  source: reworded the opening so the index summary reads as a sentence
  affects: [desktop-releases-tagged-namespaced]
