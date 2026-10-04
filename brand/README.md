# Latchway brand

Everything visual lives here as SVG; `render.mjs` turns it into the PNGs
that `web/` serves. Change the SVG, re-render, commit both.

| File | What it is | Where it goes |
|---|---|---|
| `latch.svg` | The mark: a closed gate latch, ink on transparent | `web/latch.svg` (favicon), inline in every page, README |
| `icon.svg` | The mark on an ink tile, inset to the 66 % safe zone | `web/icon-192.png`, `web/icon-512.png`, `web/apple-touch-icon.png`; the Android launcher icon later |
| `og.svg` | 1200 × 630 social preview card | `web/og.png`, referenced by `og:image` on the landing page |

## Palette

| Token | Light | Dark | Use |
|---|---|---|---|
| paper | `#F7F8F6` | `#14202D` | page background |
| ink | `#1D2A3A` | `#E9EEF2` | text, posts of the mark |
| ink-soft | `#55606D` | `#A7B1BC` | secondary text |
| brass | `#B8862B` | `#D2A24A` | the latch bar, primary buttons, accents |
| brass-deep | `#96691C` | `#B8862B` | hover, small brass text |
| moss | `#4F7A5B` | `#7FB08C` | "online" |
| rust | `#A6472F` | `#D9785F` | "offline", errors |
| line | `#D9DDE1` | `#2B3A4A` | hairlines |
| wash | `#EDEFF1` | `#1B2A39` | cards, notes |

Brass is the only colour the brand owns; it should appear once per
screen, on the thing that matters (the latch bar, the main button).
Everything else is ink on paper.

## Type

System sans: `"Segoe UI Variable Text", "Segoe UI", -apple-system, Roboto,
"Helvetica Neue", system-ui, sans-serif`. No webfonts: the pages load
nothing from third parties and we are not going to start with a font.
Headings at weight 650 with slightly tight tracking; body at 17 px / 1.55.

## The name in text

"Latchway", one word, capital L. The domain is `latchway.app`.
