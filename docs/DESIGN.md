# Interface design

The interface of Stress Test from version 0.3.0 on. The owner drew its theme in Google Stitch (three screens: home,
running cinematic, result); this document turns that theme and the 0.2.0 interface into one design language, and every
screen follows it. Where a screen needs something not described here, it is built from these parts, not new ones.

## Character

A measuring instrument, not a game launcher. Think of a bench power analyser or an oscilloscope: dark, flat, precise,
numbers first, every panel squared off by a thin rule. Modern and engineered; nothing that reads as decoration.

What 0.2.0 had and this design removes: glass cards, glows, orange-to-red gradients on buttons and tabs, the pill-shaped
floating tab bar, springy count-up animations, large rounded corners. What it keeps: the dark graphite ground, orange for
power and heat, a cool blue for the second series, Space Grotesk for titles, monospaced numbers, the two-tone wordmark.

## Colour

Dark theme only. One accent carries meaning: orange is power, heat and the primary action. Blue is the second series,
selection of a setting, and information. Green, amber and red are verdicts and states only.

| Token | Value | Use |
|---|---|---|
| `Background` | `#0B0D11` | screen ground |
| `SurfaceLow` | `#101319` | bottom bar, chart plot area, segmented track |
| `Surface` | `#141820` | panels |
| `SurfaceHigh` | `#1B212C` | selected segment, pressed panel, tile inside a panel |
| `SurfaceHighest` | `#232A38` | meter tracks, disabled fills |
| `Outline` | `#2C3545` | the 1 dp rule around every panel, dividers |
| `OutlineStrong` | `#3D4759` | outlined buttons, focused fields |
| `Text` | `#E6E9EF` | values and titles |
| `TextDim` | `#8A94A6` | labels, secondary text |
| `TextFaint` | `#5A6375` | axis labels, captions, hints |
| `Accent` | `#FF6B2C` | power, primary button, the power series, selected duration |
| `OnAccent` | `#1A0B04` | text and icons on `Accent` |
| `AccentTint` | `Accent` at 12 % | fill behind a selected orange item |
| `Cool` | `#38BDF8` | second series (temperature), selected setting, info |
| `CoolTint` | `Cool` at 12 % | fill behind a selected blue item |
| `Good` | `#22C55E` | safe, no errors, protection on |
| `Warn` | `#F59E0B` | warnings, chip temperature in the warning band |
| `Bad` | `#EF4444` | stops, errors, limits reached |
| `Danger` | `#8E0F14` | the stop button's fill (text `#FFE4E1`) |

Temperature values are coloured by band, not by decoration: below the warning threshold `Text`, in the warning band
`Warn`, at the stop threshold `Bad` (thresholds from `SafetyPolicy`). Power values are `Accent`.

No gradients on controls or panels. The only gradients allowed are data: the soft fill under a chart line (`Accent` 22 %
to 0 %) and the scrim over a scene image so text on it stays legible.

## Type

| Role | Font | Size / line / weight | Notes |
|---|---|---|---|
| Display | Space Grotesk | 28 / 32 / Bold, -0.25 | screen hero titles ("BLACK HOLE" on the home panel), uppercase allowed |
| Title | Space Grotesk | 20 / 26 / SemiBold | screen titles in the top bar |
| Heading | Space Grotesk | 16 / 22 / SemiBold | panel titles, mode names |
| Body | Instrument Sans | 14 / 20 / Regular | sentences, insights, explanations |
| Body small | Instrument Sans | 12 / 16 / Regular | supporting lines |
| Label | Instrument Sans | 12 / 16 / SemiBold, +0.6, UPPERCASE | section labels ("TEST MODE"), tile labels ("POWER") |
| Number XL | Space Mono | 40 / 44 / Bold, -0.5 | the one hero value of a screen (max power) |
| Number L | Space Mono | 28 / 32 / Bold, -0.5 | live power, chip temperature |
| Number M | Space Mono | 20 / 24 / Bold | tile values |
| Number S | Space Mono | 14 / 18 / Bold | values in rows, legends |
| Code | Space Mono | 12 / 16 / Regular | technical captions: `fp32_l2 + GPU FP32`, `HONOR 400 // SM7550 // ADRENO 720`, axis labels |

Units follow the number in the same font at about 60 % size and `TextDim` ("8.9" then " W"). Fonts are bundled (OFL,
licences in `docs/licenses/`); uppercase text goes through the locale-aware uppercase the app already uses, so Turkish
gets "İ".

## Shape and space

- Radius: panels 8 dp, buttons and segments 6 dp, chips and tags 4 dp, meters square. No pills, no circles except dots.
- Every panel: `Surface` fill and a 1 dp `Outline` rule. A selected panel: 1 dp `Cool` rule (or `Accent` where the
  selection is the power choice, such as the test mode) and the matching tint behind it. No shadows, no elevation.
- Spacing on a 4 dp grid: page margin 16, gap between panels 12, inside a panel 16, between a label and its value 4.
- Touch targets at least 48 dp. The narrowest real screen is 360 dp wide and 780 dp tall (the reference Honor 400);
  every screen is checked there, and longer content scrolls with its decision buttons fixed at the bottom.

## Parts

- **Top bar.** Title (Title style) with a Code-style caption under it (`FULL LOAD // 05:00 // HONOR 400`); back arrow and
  actions are 40 dp square outlined icon buttons (`OutlineStrong`, radius 6). The home top bar carries the wordmark
  instead of a title.
- **Wordmark.** "STRESS" in `Text` and "TEST" in `Accent`, Space Grotesk Bold, tight tracking, preceded by a small
  `Accent` bar (4 × 16 dp). The version follows in Code style `TextDim` where there is room.
- **Section label.** Label style in `TextDim`, optional trailing Code caption on the right (`MAX: 10.1 W`).
- **Panel.** As above. A panel's title row is Heading style; its body follows the parts below.
- **Metric tile.** Label (top left) and a 6 dp status dot (top right, the value's colour when live), the value in Number M
  or L, and under it a 3 dp meter or a Code-style caption. Tiles sit in rows of two or three with 8 dp gaps.
- **Meter.** 3 dp bar, `SurfaceHighest` track, flat fill in the value's colour, square ends. No gradient, no glow.
- **Primary button.** `Accent` fill, `OnAccent` label, Label style at 14 sp, leading icon, height 52, radius 6.
- **Stop button.** `Danger` fill, light label, square stop icon, same size.
- **Secondary button.** `Surface` fill, 1 dp `OutlineStrong`, `Text` label and icon.
- **Segmented control.** `SurfaceLow` track with a 1 dp `Outline`; the selected segment `SurfaceHigh` with a 1 dp
  `Cool` rule and `Cool` label; others `TextDim`. Used for graphics quality and chart series.
- **Choice chips** (duration): a row of equal outlined cells, Code-style labels (`1m 5m 15m 30m ∞`); the selected one
  `AccentTint` fill, `Accent` rule and label.
- **Status chip.** 4 dp radius, 1 dp rule and label in the state's colour, 10 % tint, optional 14 dp icon: `110°C
  PROTECTION` in `Good`, `STOPPED` in `TextDim`, `SAFETY STOP` in `Bad`.
- **Scene card.** The scene's still image (bundled `drawable-nodpi/scene_*.webp`, captured on the reference phone) with a
  bottom scrim and the name; selected: 1 dp `Cool` rule and the name in `Cool`.
- **Chart.** Plot area `SurfaceLow`; horizontal grid dashed 1 dp `Outline`; axis labels Code style `TextFaint`; the power
  line 2 dp `Accent` with its soft fill, a second series dashed 1.5 dp `Cool`. Touch and drag shows a vertical 1 dp
  `TextDim` cursor, a dot on each line, and a readout strip above the plot (`t = 01:42` left, `9.6 W // 96 °C` right in
  the series' colours). Series tabs above the plot are a segmented control.
- **Insight row.** A sentence (Body) on the left, a Number S value on the right in its verdict colour, rows divided by
  `Outline` rules.
- **Stability arc.** A 240° arc, 6 dp stroke, `SurfaceHighest` track and `Cool` fill, the percentage in Number M at its
  centre and the label under it. Replaces the 0.2.0 gauges; no needle.
- **Bottom bar.** Full width, `SurfaceLow`, 1 dp `Outline` rule on top, four destinations: Test, History, Device,
  Settings. The selected item's icon and label in `Accent`, others `TextDim`; no pill behind it.
- **Dialogs and sheets.** A panel on a 60 % black scrim, Heading title, Body text, buttons right-aligned or full width at
  the bottom; the start-up safety notice keeps its rule that only the text scrolls.
- **Scene viewport** (running a cinematic or preview load). The scene fills the screen behind everything, as in 0.2.0, so
  the GPU's work does not depend on the panels; the upper part shows it framed by four thin corner brackets (`TextDim`,
  16 dp arms), and the lower part is covered by opaque panels. A tap hides the panels, as before.

## Motion

State changes 120 ms ease-out; a screen or panel entering 240 ms with `cubic-bezier(0.2, 0, 0, 1)`, leaving 160 ms.
Numbers change in place (monospaced digits); a count-up is allowed once, when a result first opens, and lasts at most
600 ms without overshoot. Nothing pulses except the recording dot of a running test (1 s period).

## Language

Texts stay as the app has them, in English and Turkish, plain and exact. Labels are short nouns ("POWER", "CHIP",
"BATTERY"); sentences say what was measured and what could not be. When a value cannot be read on a phone the place shows
"—" and, once per screen, a sentence saying why; never a made-up number.
