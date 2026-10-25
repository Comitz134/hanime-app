---
id: phone-ships-no-addon-by-default
title: A phone install starts with no content sources until its own manifest is added
category: concept
status: active
tags: [phone, addons, home, release]
created: "2026-10-25T01:11:09"
updated: "2026-10-25T01:41:51"
---

<!-- compiled_truth -->
A fresh install seeds the app's own loopback addon (http://127.0.0.1:8787/addon/manifest.json) into the first profile's addon list before the first composition, once, so Home opens on catalogues instead of "No content sources". The once-only flag is the SharedPreferences file sekai_addon_seed key default_addon_seeded. The seed does nothing when the profile already has addons, and an addon the reader removes afterwards stays removed.


## Timeline

- time: 2026-10-25T01:11:09
  kind: decision
  summary: "Created this page: A phone install starts with no content sources until its own manifest is added"
  source: session recovering 2.0.5 install
  affects: [phone-ships-no-addon-by-default]

- time: 2026-10-25T01:11:16
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "phone 52070e9f after 2.0.5, addons screen"
  affects: [phone-ships-no-addon-by-default]

- time: 2026-10-25T01:11:36
  kind: note
  summary: "Restored the reader's Home on 52070e9f by adding the local manifest through Settings > Content & discovery > Addons; the catalogs came back at once, so the empty Home was a missing addon and not a broken one."
  source: "phone 52070e9f, addons screen and Home"
  affects: [phone-ships-no-addon-by-default]

- time: 2026-10-25T01:41:51
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "DefaultAddonSeed.kt, MainActivity.onCreate"
  affects: [phone-ships-no-addon-by-default]

- time: 2026-10-25T01:41:51
  kind: note
  summary: "Seeded the local addon on first run and checked it on a fresh install of the debug package: Home showed 5 catalogues with 1 addon before anything was added by hand, and removing the addon then relaunching left Home empty, so the flag holds."
  source: "phone 52070e9f, com.nuviodebug.com fresh install"
  affects: [phone-ships-no-addon-by-default]
