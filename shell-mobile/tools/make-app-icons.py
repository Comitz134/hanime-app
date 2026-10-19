#!/usr/bin/env python3
"""Turn the SEKAI artwork this repository already carries into the phone app's icons.

One input set, one run, deterministic output — the same discipline
``make-sekai-icons.py`` uses for the desktop fork, from the same art, so the two
clients cannot drift apart.

Inputs, both already in this repository:

  shell/overrides/composeApp/src/desktopMain/resources/icons/app-icon-original-transparent.icns
      the tile: a rounded black square carrying the mark (purple sun behind a
      torii gate, a white mountain inside it, ripples below) with the wordmark
      underneath. Its 1024x1024 layer is what gets read.

  shell/overrides/composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png
      the wordmark's letters alone, white on transparency.

Outputs, all written into ``shell-mobile/overrides/`` so ``sync.sh`` lays them
over the upstream tree at their upstream paths:

  composeApp/src/androidMain/res/mipmap-<density>/ic_launcher*.webp
      the launcher: legacy, round, adaptive foreground, monochrome, and the five
      themed variants upstream's app-icon picker offers
  composeApp/src/androidMain/res/drawable-nodpi/ic_splash_logo*.webp
      the Android 12+ splash mark, once per theme
  composeApp/src/commonMain/composeResources/drawable/app_icon_*.png
      the picker's previews
  composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark*.png
      the in-app lockup: the mark, then the letters

The geometry is upstream's rather than invented — measured from NuvioMobile's own
assets, so a fork icon sits exactly where theirs sat: the mark fills 74% of a
tile preview, 43% of an adaptive foreground's canvas, and half the splash canvas;
the lockup is 1085x344 with the mark at the left edge and the letters beside it.
The five theme hues are upstream's too, sampled from their themed marks
(arctic blue 217, copper 26, emerald 150, rose gold 288, graphite a wash with no
hue at all).

  python shell-mobile/tools/make-app-icons.py
"""

from __future__ import annotations

import colorsys
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]  # hanime-app
DESKTOP = ROOT / "shell" / "overrides" / "composeApp" / "src"
TILE_SOURCE = DESKTOP / "desktopMain" / "resources" / "icons" / "app-icon-original-transparent.icns"
TEXT_SOURCE = (DESKTOP / "commonMain" / "composeResources" / "drawable"
               / "app_logo_wordmark.png")

OUT = ROOT / "shell-mobile" / "overrides" / "composeApp" / "src"
RES = OUT / "androidMain" / "res"
COMPOSE_DRAWABLE = OUT / "commonMain" / "composeResources" / "drawable"

# Launcher densities, exactly the ones upstream ships.
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_DP = 48       # ic_launcher / ic_launcher_round
FOREGROUND_DP = 108  # adaptive foreground / monochrome

# Upstream's themed marks, as hues in degrees. ``None`` means "no hue to keep".
THEMES: dict[str, float | None] = {
    "arctic_blue": 216.7,
    "copper": 25.8,
    "emerald": 150.4,
    "rose_gold": 288.4,
    "graphite": None,
}

# The app's gold theme is not a launcher theme — it has a wordmark of its own and
# no icon — so it is applied to the lockup only.
GOLD_HUE = 42.0

MARK_SHARE = 0.74   # of a tile preview's edge
FOREGROUND_SHARE = 0.43  # of an adaptive foreground's edge
SPLASH_SHARE = 0.50      # of the splash canvas's edge
SPLASH_SIZE = 1080
LOCKUP_HEIGHT = 344     # upstream's lockup canvas height
LOCKUP_GAP = 67         # where upstream's letters start after their mark
LOCKUP_TEXT_SHARE = 0.55  # of the canvas height; upstream's are 0.44 of a taller canvas
# The wordmark band under the mark inside the tile, in the tile's own 256ths —
# measured, not guessed: rows 26..177 are the mark, 188..214 the letters.
TILE_MARK_ROWS = (26, 178)
BRIGHT = 70         # sum of rgb above this counts as ink rather than tile


def load_tile() -> Image.Image:
    """The tile art at its largest layer (1024), transparent outside the corners."""
    with Image.open(TILE_SOURCE) as im:
        return im.convert("RGBA")


def load_mark(tile: Image.Image) -> Image.Image:
    """The mark alone: cropped off the tile above the wordmark, black keyed out.

    Inside the tile the mark sits on black, so its alpha is its own brightness —
    which keeps the sun's glow and the letters' antialiasing intact instead of
    cutting them off at a threshold.
    """
    scale = tile.width / 256.0
    top, bottom = (int(round(r * scale)) for r in TILE_MARK_ROWS)
    band = tile.crop((0, top, tile.width, bottom))

    ink = Image.new("L", band.size, 0)
    src, dst = band.load(), ink.load()
    for y in range(band.height):
        for x in range(band.width):
            r, g, b, _ = src[x, y]
            dst[x, y] = 255 if r + g + b > BRIGHT else 0
    box = ink.getbbox()
    if box is None:
        raise SystemExit("no mark found in the tile's upper band")
    band = band.crop(box)

    # Un-premultiply against black: alpha is the brightest channel, colour is what
    # is left once that alpha is divided back out.
    out = Image.new("RGBA", band.size, (0, 0, 0, 0))
    src, dst = band.load(), out.load()
    for y in range(band.height):
        for x in range(band.width):
            r, g, b, _ = src[x, y]
            a = max(r, g, b)
            if a == 0:
                continue
            dst[x, y] = (min(255, r * 255 // a), min(255, g * 255 // a),
                         min(255, b * 255 // a), a)
    return out


def load_text() -> Image.Image:
    """The wordmark's letters, cropped to themselves.

    The source file has slack around the letters; a lockup measures from the
    mark's edge to the first letter, so the slack has to go before it is placed.
    """
    with Image.open(TEXT_SOURCE) as im:
        art = im.convert("RGBA")
    box = art.split()[-1].point(lambda v: 255 if v > 20 else 0).getbbox()
    return art.crop(box) if box else art


def recolor(im: Image.Image, hue: float | None) -> Image.Image:
    """Keep the art's shading, move its hue; ``None`` is the graphite wash."""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if s < 0.06:  # the whites and the blacks are not the accent
                continue
            if hue is None:
                s *= 0.18
            else:
                h = hue / 360.0
            nr, ng, nb = colorsys.hsv_to_rgb(h, s, v)
            px[x, y] = (int(round(nr * 255)), int(round(ng * 255)), int(round(nb * 255)), a)
    return out


def fit(im: Image.Image, share: float, canvas: int) -> Image.Image:
    """The art scaled so its longest edge is ``share`` of ``canvas``, centred."""
    target = max(1, int(round(canvas * share)))
    scale = target / max(im.width, im.height)
    scaled = im.resize((max(1, round(im.width * scale)), max(1, round(im.height * scale))),
                       Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.alpha_composite(scaled, ((canvas - scaled.width) // 2, (canvas - scaled.height) // 2))
    return out


def silhouette(mark: Image.Image, canvas: int) -> Image.Image:
    """The mark's shape in one colour — what Android tints the monochrome layer."""
    shape = fit(mark, FOREGROUND_SHARE, canvas)
    out = Image.new("RGBA", shape.size, (255, 255, 255, 0))
    out.putalpha(shape.split()[-1])
    return out


def round_icon(tile: Image.Image, size: int) -> Image.Image:
    """The tile cut to a circle, which is what a round launcher asks for."""
    art = tile.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(art, (0, 0), mask)
    return out


def tile_preview(mark: Image.Image, size: int = 256) -> Image.Image:
    """The picker's square: black tile, mark centred — upstream's 74% of the edge."""
    out = Image.new("RGB", (size, size), (0, 0, 0))
    out.paste(fit(mark, MARK_SHARE, size).convert("RGB"), (0, 0),
              fit(mark, MARK_SHARE, size).split()[-1])
    return out


def lockup(mark: Image.Image, text: Image.Image) -> Image.Image:
    """The mark at the left, the letters beside it, and nothing else.

    Upstream's lockup is 1085x344 because their letters happen to fill it. Ours
    is cut to its own content instead of padded out to that width: every call
    site sizes this by height (`Modifier.height(...)`) and lets the width follow,
    so trailing transparency would only shift the lockup off-centre.
    """
    height = LOCKUP_HEIGHT
    m = mark.resize((max(1, round(mark.width * height / mark.height)), height), Image.LANCZOS)
    text_height = max(1, round(height * LOCKUP_TEXT_SHARE))
    t = text.resize((max(1, round(text.width * text_height / text.height)), text_height),
                    Image.LANCZOS)

    out = Image.new("RGBA", (m.width + LOCKUP_GAP + t.width, height), (0, 0, 0, 0))
    out.alpha_composite(m, (0, (height - m.height) // 2))
    out.alpha_composite(t, (m.width + LOCKUP_GAP, (height - t.height) // 2))
    return out


def save(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.suffix == ".webp":
        im.save(path, "WEBP", quality=92, method=6)
    else:
        im.save(path, "PNG", optimize=True)


def main() -> int:
    for source in (TILE_SOURCE, TEXT_SOURCE):
        if not source.is_file():
            print(f"make-app-icons.py: missing source art: {source}", file=sys.stderr)
            return 1

    tile = load_tile()
    mark = load_mark(tile)
    text = load_text()
    print(f"mark {mark.width}x{mark.height}, letters {text.width}x{text.height}")

    variants: dict[str, Image.Image] = {"original": mark}
    for theme, hue in THEMES.items():
        variants[theme] = recolor(mark, hue)

    written = 0
    for density, factor in DENSITIES.items():
        legacy = max(1, round(LEGACY_DP * factor))
        foreground = max(1, round(FOREGROUND_DP * factor))
        folder = RES / f"mipmap-{density}"

        # The tile as-is: the art already carries its own rounded corners and the
        # wordmark, exactly as the desktop's icon does.
        save(tile.resize((legacy, legacy), Image.LANCZOS), folder / "ic_launcher.webp")
        save(round_icon(tile, legacy), folder / "ic_launcher_round.webp")
        save(fit(mark, FOREGROUND_SHARE, foreground), folder / "ic_launcher_foreground.webp")
        save(silhouette(mark, foreground), folder / "ic_launcher_monochrome.webp")
        written += 4

        for theme, art in variants.items():
            if theme == "original":
                continue
            save(recolor(tile, THEMES[theme]).resize((legacy, legacy), Image.LANCZOS),
                 folder / f"ic_launcher_{theme}.webp")
            save(fit(art, FOREGROUND_SHARE, foreground),
                 folder / f"ic_launcher_{theme}_foreground.webp")
            written += 2

    splash = RES / "drawable-nodpi"
    save(fit(mark, SPLASH_SHARE, SPLASH_SIZE), splash / "ic_splash_logo.webp")
    written += 1
    for theme, art in variants.items():
        if theme == "original":
            continue
        save(fit(art, SPLASH_SHARE, SPLASH_SIZE), splash / f"ic_splash_logo_{theme}.webp")
        written += 1

    save(tile_preview(mark), COMPOSE_DRAWABLE / "app_icon_original.png")
    written += 1
    for theme, art in variants.items():
        if theme == "original":
            continue
        save(tile_preview(art), COMPOSE_DRAWABLE / f"app_icon_{theme}.png")
        written += 1

    save(lockup(mark, text), COMPOSE_DRAWABLE / "app_logo_wordmark.png")
    save(lockup(mark, text), COMPOSE_DRAWABLE / "app_logo_wordmark_original.png")
    save(lockup(recolor(mark, GOLD_HUE), text), COMPOSE_DRAWABLE / "app_logo_wordmark_gold.png")
    written += 3
    for theme, art in variants.items():
        if theme == "original":
            continue
        save(lockup(art, text), COMPOSE_DRAWABLE / f"app_logo_wordmark_{theme}.png")
        written += 1

    print(f"wrote {written} files under {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
