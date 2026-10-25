---
id: phone-ships-no-addon-by-default
title: A phone install starts with no content sources until its own manifest is added
category: concept
status: active
tags: [phone, addons, home, release]
created: "2026-10-25T01:11:09"
updated: "2026-10-25T01:11:36"
---

<!-- compiled_truth -->
A freshly installed phone build starts with zero addons, so Home shows upstream's empty state ("No content sources"). The app's catalogues come from its own in-process server, and that server is added by hand as http://127.0.0.1:8787/addon/manifest.json. Anything that uninstalls the app wipes the reader's addons with the rest of its data, so a release check that starts from a clean install leaves the phone with an empty Home and no sign that adding one manifest is the answer.


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
