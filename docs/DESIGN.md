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

## Two pages per screen (revision of 2026-11-25)

The owner's verdict on the first build: the look is right, but every screen showed everything, "her şey her yerde", and
because everything was a box, what can be pressed did not stand apart from what is only shown. His rule since: each
screen is two pages. "Sade isteyen sade kullanır, mühendislik isteyen listeden bakar." His second set of Stitch examples
(home, running cinematic, result, each ending in one "Mühendislik ..." row) shows the simple page.

**Simple page** (what a screen opens on). Only the essentials, in wide full-width panels, with air between them; at most
one row of two or three tiles. Rough budget: one hero value or hero card, two to four secondary values, one chart at
most, one sentence of verdict, the decision buttons. Everything else moves to the engineering page; nothing is lost.

**Engineering page** (one tap from the simple page). Every value and detail the screen knows, as an aesthetic log: a
readable, coloured, monospaced list, not boxes. Reached by the **engineering row**, the last item of the simple page (on
the run screen, the row above the stop button): a full-width control, `ENGINEERING VIEW` / `MÜHENDİSLİK GÖRÜNÜMÜ` in
Label style, a Code caption on the right with what it holds (`42 VALUES`), a trailing chevron. It opens as a page of its
own with a back arrow (top bar title: the screen's name, caption `ENGINEERING`); on the run screen it opens as a sheet
over the lower part so the scene keeps running, and closing it returns to the simple page.

The log's grammar:

- **Section header**: Code style, `TextDim`, uppercase, the name followed by a 1 dp `Outline` rule to the right edge and
  a count or summary at the end: `THERMAL ──────────────── 14 ZONES`. Sections in a fixed order per screen.
- **Line**: one value per line, 32 dp tall, no box. Key on the left in Code style `TextDim`; value right-aligned in
  Number S, coloured by meaning (power `Accent`, temperature by band, verdicts `Good`/`Warn`/`Bad`, plain values `Text`),
  unit in `TextDim`; optional status tag at the far right in 10 sp Code: `OK` `Good`, `N/A` `TextFaint`, `WARN` `Warn`,
  `STOP` `Bad`. Raw source in a second line under the key when it helps (`/sys/class/thermal/thermal_zone12`,
  `TextFaint`).
- **Event line** (results and live runs): timestamp first (`01:18`), an event tag (`THROTTLE`, `SAFETY`, `GPU`,
  `ERROR`, `PLUG`) in its colour, then the sentence in Body small: `01:18  THROTTLE  big cluster 2400 → 2016 MHz`.
- **Unavailable**: the line stays, value `—`, tag `N/A`, and the reason once under the section.
- Charts sit in the log as their own section, full width, every series the screen has; the simple page keeps at most
  the one that matters.
- The page is read top to bottom like a report: summary lines first, then sections, then raw values. Long pages may
  carry a row of section shortcuts at the top (segmented, scrolls the list).

### What each simple page keeps

| Screen | Simple page | Engineering page |
|---|---|---|
| Home (Test) | top bar with wordmark and protection chip; one hero card (selected mode, or scene image with name and quality); test modes as controls, charge test among them; scene picker when cinematic; duration chips; one-line notices that block or redirect a start (hot phone, plugged cable, no Vulkan); start button; engineering row | live readings (power, every temperature, clocks, GPU load, battery), the workload's kernels and burners, scene quality and internal resolution, device line, every notice in full |
| Run | scene (cinematic) or a calm dark ground; progress bar with elapsed / total; three large values (power W, chip °C, frame rate or work rate); one notice line when one applies (drawn below quality, safety warning); engineering row; stop | sheet: every live reading by section (power and battery, thermal zones, clusters and clocks, GPU, workload and frame time, errors), events so far |
| Result | header (mode, date, duration, end state chip); peak and sustained W; battery % from → to; hottest chip; the power chart; one verdict sentence (the first insight); engineering row; CSV and PDF actions | every insight, every metric, every chart with its series, Honor reference, requested and drawn quality and scale, events timeline, device and app version |
| Charge run / result | W into the battery (hero); battery %, battery temperature, time to full (run) or sustained W, energy, % per 10 min (result); the W-against-% chart (result); verdict sentence; engineering row; stop / CSV | current, voltage, plug type, idle baseline and estimated input, slowdown point, findings with thresholds, both charts, reading cadence |
| History | list of runs, each a control row: mode icon, mode and scene, date, peak W, chevron; compare action in the top bar | a run's engineering page is its result's |
| Compare | A and B headers; peak W, sustained W, hottest chip, battery drain side by side with the difference; one overlaid power chart; verdict sentence; engineering row | every compared metric, every overlaid chart, both runs' settings |
| Device | phone, chip, GPU and Vulkan, battery level and temperature; one fit line ("This phone gives 12 of 14 readings") with the missing ones named; engineering row | every cluster and clock, every thermal zone with its path and status, sensors, raw battery values, GPU counters, what the app can and cannot read and why; refresh every 2 s |
| Settings | setting rows (device safety, scene quality, display during a test, vibration, safety notice, language, clear history); an About row | About (readout); advanced: extra GPU burner, manual load tests |

## Pressable and readout

One glance must tell a control from a reading. **A rule around something means it can be pressed.**

- **Readout** (information): no outline. Hero and group panels are a flat `Surface` fill with no rule; values inside
  them are separated by space or a hairline. Readouts never show a chevron, never show a pressed state, and their
  labels are Label style `TextDim`. Log lines are readouts.
- **Control** (anything that acts or navigates): `SurfaceHigh` fill, 1 dp `OutlineStrong` rule, radius 6, a pressed
  state (fill one step lighter), and a visible affordance: a chevron for navigation, a switch, radio or check for a
  setting, an icon plus a verb for an action, or the primary/secondary button shape. A selected control keeps its
  `Cool` (or `Accent`) rule and tint.
- **Settings** are rows of controls: the setting's name (Heading 16) and one line of what it does (Body small,
  `TextDim`) on the left, the control on the right (switch, current value plus chevron, or a segmented control under
  it). Information inside Settings (about, version, licences) is readout.
- A list row that opens something (a history run, a scene, a section) is a control: rule, chevron, pressed state.

This overrides the **Panel** and **Metric tile** parts below where they disagree: panels that only show information
lose their rule; the rule belongs to controls.

## Live values

Readings that move faster than a person reads are shown at a calm rate. On the Device screen, clocks, temperatures,
current, voltage and loads refresh once every 2 s (the owner's choice), numbers change in place. The run screen's live
values keep their once-a-second rate; its engineering sheet too.

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
- **Secondary button.** `SurfaceHigh` fill, 1 dp `OutlineStrong`, `Text` label and icon.
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
