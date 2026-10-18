# Loading surfaces

Screenshots of every screen the app draws while it is waiting for something,
because none of them can be seen without running the app: they last a second or
two, on screens you have to navigate to, and the one that matters most (a title
whose first door is dead) only appears when a third party is slow.

Open [index.html](index.html) to review them, or look at `shots/` directly.
[index.html](index.html) and [measurements.json](measurements.json) are
generated; this file, `harness.html`, `build.mjs` and `assets/` are the source.

    node docs/loading-surfaces/build.mjs

Nothing to install: it uses the Chrome or Edge already on the machine, serves
the checkout on a loopback port for the length of the run, and writes the
shots, the measurements and the gallery. Set `CHROME_PATH` if neither browser is
where it expects.

## The pictures are the app's own

The harness does not re-draw the app. `harness.html` imports
`server/client/src/loading.js` and asks it for each surface — `sheetSkeleton()`,
`stageLoading()`, `videoWaitHtml()`, `skeletonCards()`, `listSkeleton()`,
`readerSkeleton()`, `stageStillLooking()` — links the client's own stylesheets
in the build's own order, and reads the veil, the nav and the detail sheet out of
`server/client/template.html` at run time. The captions are the ones the callers
pass (`Finding a stream…` from `sheet.js`, `Looking for a stream…` from
`movies.js`, `Loading the player…` from `anime.js`), and `stage-still` is
produced by actually calling `stageStillLooking()` and waiting four seconds for
it, not by writing its sentence into the markup.

Two surfaces are drawn from markup the app builds rather than markup that exists
in the document — the reader's seam between chapters (`src/manga.js`) and the
tracking box while it waits (`src/mal.js`) — and one is a state `account.js` puts
a shipped card into. The names those three are made of are checked against the
sources that emit them, so a rename there fails this run instead of leaving a
picture of a state the app no longer has.

## What each picture is checked for before it is written

A screenshot that cannot be trusted is worse than none, and none of these can be
checked by looking at them, so every run asserts:

* **facts off the live DOM** — the stage is 16:9 and its caption is the real
  one, the cover behind it is `blur(22px)`, the detail grid is two columns and
  its stage block is 16:9, the strip layout is page-shaped (2:3), the grid is 18
  cards and 54 shimmering blocks, the reader is hiding the sheet's top bar;
* **the file's own header** — the PNG's dimensions are read out of its IHDR, so
  a stunted capture fails instead of being committed;
* **the pixels, read back through the same browser** — a flat image, a stage
  with no cover behind it, or a page that never drew fails the run;
* **liveness** — the animations are held at one fixed time for the picture (that
  is why two runs look the same) and then let go, and the surface is captured
  again: a surface whose second capture is identical to its first was not
  drawing. The two surfaces with nothing animating in them record `0 anim`
  instead, which is itself the point of those pictures.

`measurements.json` is what came back — the facts, the pixel statistics and the
animation count per surface, as recorded on the run that produced the shots in
`shots/`.

## Stand-ins

* `assets/poster.svg` and `assets/page.svg` stand in for artwork. The app blurs
  and darkens whatever cover a title has and draws whatever pages a chapter has;
  these are only so the frames have something to blur or draw. Nothing about the
  stage's geometry, blur, ring, caption or the reader's page shape depends on
  them.
* Geist Mono is fetched from Google Fonts, as the app does. Offline, the
  fallbacks in `--font-mono` take over, exactly as they do in the app.
* Animations are held at 700ms. A still cannot show timing, so the four-second
  caption change is shown as its end state and the coming and going of the video
  overlay is not shown at all.

Sizes: the text-carrying surfaces are captured at 2× (retina), the three stage
shots at 1× — a blurred backdrop at 2× is three quarters of a megabyte of PNG
that shows nothing more.

## The surfaces

| # | surface | what it is |
|---|---|---|
| 01 | first-paint | the boot veil, over the whole viewport until the catalog answers or 15s pass |
| 02 | stage-films | the stage an embed plays on, poster blurred behind the ring |
| 03 | stage-still | the same stage after four seconds, saying it is still looking |
| 04 | stage-no-poster | the stage with no cover to put behind it |
| 05 | video-wait | the overlay over a `<video>` that has not drawn a frame |
| 06 | detail-sheet | the detail page the sheet is about to become, at desktop width |
| 07 | detail-sheet-phone | the same at phone width — one column, small poster |
| 08 | grid | 18 skeleton cards and the real nav under the real container |
| 09 | playlist-sheet | a playlist's rows while they are in flight |
| 10 | reader-chapter | a chapter's pages before any has arrived |
| 11 | reader-double | those strips in a two-page layout: they inherit it |
| 12 | reader-seam | the join between chapters, with the ring while the next arrives |
| 13 | mal-slot-loading | the tracking box waiting on MyAnimeList |
| 14 | settings-signin | the account card mid-sign-in: one line of text, two disabled buttons |
| 15 | reader-page-pending | one page decoded, two still page-shaped shimmers |

## Known gaps

Three of these are not what the rest of the folder is. **12** is a spinner in a
named band; **13** is two words and nothing else; **14** is a line of text and two
disabled buttons, with nothing on the card moving at all — each of those three
records `0 anim` or a spin and nothing else in `measurements.json`, and the
checks fail if one of them ever gains a shape, so the picture gets taken again.
They are photographed as they are rather than left out.

Two more waiting states are not pictured, because they are labels inside
controls rather than surfaces: the playlist cookie check in Settings writes
`Checking…` under its field, and the public playlists' More button renames itself
`Scanning…` while a crawl runs. They are the same kind of state — words in place
of a shape — in a place where a shape would be odd.

A still also cannot show the parts of these surfaces that are about time: the
ring turning, the shimmer sweeping, the boot veil fading out, the change from
`Finding a stream…` to `Still looking — some titles take a few seconds` after
four seconds (shown here only as the after).
