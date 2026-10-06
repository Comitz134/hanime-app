# lunarx.to token sheet

Extracted from the running page at `https://lunarx.to/anime` on 2026-10-06 via
`getComputedStyle(document.documentElement)` — not eyeballed off a screenshot.
Active theme is `dark summer-dark` (from `document.documentElement.className`).

## Colour tokens

shadcn-style bare HSL triplets, consumed as `hsl(var(--token))`.

| Token                | Active value      | Resolves to        |
| -------------------- | ----------------- | ------------------ |
| `--background`       | `0 0.0% 6.7%`     | `#111111`          |
| `--foreground`       | `0 0.0% 93.3%`    | `#eeeeee`          |
| `--card`             | `0 0.0% 9.8%`     | `#191919`          |
| `--card-foreground`  | `0 0.0% 93.3%`    | `#eeeeee`          |
| `--popover`          | `0 0.0% 9.8%`     | `#191919`          |
| `--muted`            | `0 0.0% 13.3%`    | `#222222`          |
| `--muted-foreground` | `0 0.0% 70.6%`    | `#b4b4b4`          |
| `--accent`           | `0 0.0% 16.5%`    | `#2a2a2a`          |
| `--accent-foreground`| `0 0.0% 93.3%`    | `#eeeeee`          |
| `--border`           | `45 14.3% 11.0%`  | `#201e18`          |
| `--input`            | `0 0.0% 28.2%`    | `#484848`          |
| `--primary`          | `29.5 100% 88.0%` | warm peach         |
| `--primary-foreground`| `183.2 54.3% 6.9%`| near-black teal  |
| `--ring`             | `29.5 100% 88.0%` | matches primary    |
| `--secondary`        | `28.2 17.5% 19.0%`| warm grey-brown    |
| `--brand-400`        | `29.5 100% 89.2%` |                    |
| `--brand-500`        | `29.5 100% 88.0%` |                    |
| `--brand-600`        | `29 85% 76.0%`    |                    |
| `--radius`           | `.5rem`           | 8px                |

Note the brand ramp is warm (hue 29, orange) while the base greys are hue 0
except `--border` and `--secondary`, which carry a slight warm cast (hue 45 and
28). That warm lith on an otherwise neutral dark is most of the "feel".

Theme variants present in the CSS (`--background: 335 20% 9%` = plum,
`344 60% 98%` / `45 78% 97%` = light creams, `0 0% 100%` / `0 0% 3.9%` = stock
shadcn light/dark) are not active on this route.

## Typography

- Body/UI: **Geist Mono** everywhere, no proportional face. `--font-geist-mono`.
- Display headings: `font-serif` = `ui-serif, Georgia, Cambria, "Times New Roman", Times, serif`.
  Hero `h2` renders at 48px / weight 400 / `tracking-tight` / `text-balance`.
- Pixel faces are loaded (`GeistPixelSquare`, `GeistPixelGrid`, `GeistPixelCircle`,
  `GeistPixelTriangle`, `GeistPixelLine`, all weight 500) via a `.variable` class on
  `<html>`, used for the wordmark rather than body copy.
- Geist loads as a variable font spanning 100–900.

## Layout

```
fixed nav   fixed left-0 right-0 z-[9999] top-2 pt-[env(safe-area-inset-top)] pointer-events-none
            └─ flex justify-center pt-6
               └─ pill: flex items-center gap-3 backdrop-blur-md border border-white/10
                        shadow-lg py-2 px-2 rounded-full
                        mobile: w-[80%] justify-between   desktop: w-auto
                        logo svg: h-6 w-6 text-brand-300

container   w-full max-w-[1600px] mx-auto px-2 sm:px-4 lg:px-6 py-4 sm:py-6
            space-y-4 sm:space-y-6 lg:space-y-8

section hdr flex items-center gap-2 mb-4  +  h2 text-xl sm:text-2xl font-semibold
divider     shrink-0 bg-border h-[1px] w-full
footer      border-t bg-card/30 mt-12  └─ container py-6 sm:py-8
```

## Components

**Poster card** — portrait 2:3, three fixed sizes:

| Breakpoint | Size         |
| ---------- | ------------ |
| base       | 110 × 165 px |
| `sm`       | 150 × 225 px |
| `lg`       | 190 × 285 px |

```html
<div class="pointer-events-auto relative h-[165px] w-[110px]
            sm:h-[225px] sm:w-[150px] lg:h-[285px] lg:w-[190px] cursor-grab">
  <div class="overflow-hidden rounded-lg border border-white/[0.08]
              data-[center=true]:border-white/20
              data-[center=true]:shadow-2xl data-[center=true]:shadow-black/60
              sm:rounded-xl">
    <img class="rounded-[inherit] object-cover" />
  </div>
</div>
```

Border is `white/[0.08]` at rest, `white/20` plus `shadow-2xl shadow-black/60`
when active. Radius 8px → 12px at `sm`.

**Card row** — `flex space-x-2 sm:space-x-3 pb-4`, drag-scrolled (`cursor-grab`,
`touch-action: pan-y`). Rows carry a centred 3D coverflow treatment: non-centre
cards sit at `translate3d(±112%, 10px, 0) rotate(∓10deg) scale(0.87)` with a
450 ms `cubic-bezier(0.16, 1, 0.3, 1)` transition.

**Search input** — `w-full h-11 rounded-2xl bg-transparent pl-10 pr-10 text-[13px]
font-medium placeholder:text-muted-foreground/50`, 16px radius, 44px tall.

**Pill link** — `text-sm font-semibold px-6 py-3 rounded-full transition-all duration-300`,
14px text, 600 weight.

**Buttons** default to `rounded-full` with no background; size 14px / weight 500.

## Motion

- Nav fades and translates in on mount (`opacity: 0` → `transform: none`).
- The logo mark carries a small idle wobble: `translateY(-2px) rotate(-4.8deg)`.
- Hero `h2` enters with `translate-y-2 opacity-0` → settled, staggered per slide.
- Hero has a slowly drifting aurora/meteor background (`aurora 8s ease-in-out
  infinite alternate`, `meteor 5s linear infinite`, `pulse-slow 3s`).
- Marquee utilities exist (`marquee var(--duration) infinite linear`) for the
  social feed strips.
- Hero slide transition: `opacity linear 760ms, visibility linear 760ms`.

## Assets

- Posters/banners come from `s4.anilist.co`, proxied through `lunarx.to/_next/image`
  with `w=` and `q=70`. Poster path shape:
  `/file/anilistcdn/media/anime/cover/large/<id>.jpg`
- No custom icon font. Icons are inline SVG; the nav mark is a small stroked
  glyph at `stroke-width: 0.00024` (a hairline outline originally derived from a
  filled path).
