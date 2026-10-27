---
id: manga-signer-one-answer-per-request
title: "A signed manga request gets its own answer, or the next one takes its token"
category: concept
status: active
tags: [manga, mangafire, signer, 403, webview]
created: "2026-10-27T18:42:05"
updated: "2026-10-27T18:42:12"
---

<!-- compiled_truth -->
Opening a manga sometimes answered `upstream http 403`, and tapping back in usually worked. The signer page parked every answer in one shared slot, so two requests in flight at once - a title page asks for the title and its chapter list together - overwrote each other, and the request carrying the other one's token was refused. `window.__mfSign` now returns an id and the answer is read back by that id.

## What the reader saw

On 52070e9f with 2.0.8: tap a manga from the Manga tab and the title page failed with `upstream http 403`; go back and tap again and it worked; two or three taps later it failed again. The report was worth trusting literally - the pattern is a race, so it fails by turns rather than always.

## Why

Mangafire's requests are signed in a hidden WebView (`mf-signer.html`): the page calls the site bundle's interceptor, which mints a `vrf` token from the request path. Java fired `window.__mfSign(path, params)` and then polled `window.__mfResult`, one slot for everyone. A title page asks for `/titles/<hid>` and `/titles/<hid>/chapters` at the same moment, so the second answer landed in the slot the first caller was about to read - and since the token belongs to the path it was minted for, whichever request read the other's token was answered 403.

Reproduced before fixing: three concurrent chapter loads, plus their title reads, all came back with the same token; the ones whose path did not match were 403.

## The fix

- `mf-signer.html`: `let mfSeq`, `const mfAnswers = Object.create(null)`; `__mfSign` returns `++mfSeq` synchronously and writes its answer under that id; `__mfResultFor(id)` returns it once and deletes it, so a poll cannot read a stale answer.
- `MangaSigner.sign`: takes the id from the `__mfSign` expression and polls `__mfResultFor(<id>)`. Nothing else in the seam changed - the query string and token still come back as `{qs, vrf}`.

Answers are still read by polling, because the interceptor can settle on a later turn of the event loop; the id is what keeps them apart, not a different wait.

## Verified on the phone

Phone 52070e9f, release `app.hanime.shell` 2.0.9 (209):

- 12 concurrent signed requests: 12 distinct tokens, no 403 (previously three concurrent loads produced one token and two 403s).
- The reader's own loop, four title pages tapped in and out back to back: no 403 in any of them, and no `403` line in the log for the run.
- The cause is Mangafire-only in practice, as the reader guessed: extension sources sign nothing, so a collision there was never possible.

Nothing else in the request path changed, so an unsigned source (an extension or a script) behaves exactly as before.


## Timeline

- time: 2026-10-27T18:42:05
  kind: decision
  summary: "Created this page: A signed manga request gets its own answer, or the next one takes its token"
  source: "mf-signer.html, MangaSigner.java, 2.0.9 session on 52070e9f"
  affects: [manga-signer-one-answer-per-request]

- time: 2026-10-27T18:42:12
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "mf-signer.html, MangaSigner.java, concurrent reproduction and four title opens on 52070e9f (2.0.9)"
  affects: [manga-signer-one-answer-per-request]

- time: 2026-10-27T18:42:12
  kind: note
  summary: "one shared answer slot handed one request another's path-bound token, which is the intermittent upstream 403; each signed request now keeps its own answer and 2.0.9 was checked with 12 concurrent requests and four back-to-back title opens on 52070e9f"
  source: "mf-signer.html, MangaSigner.java, adb on 52070e9f"
  affects: [manga-extension-repositories, phone-release-is-two-publishes]
