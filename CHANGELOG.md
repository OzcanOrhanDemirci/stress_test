# Changelog

Every released version, what changed in it and why.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the numbering follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Each version corresponds to a `v`-prefixed tag. From 0.2.0 each tag produces a
signed package through the release pipeline described in
[docs/RELEASE.md](docs/RELEASE.md).

## [Unreleased]

Nothing yet.

## [0.2.0] — 2026-10-02

Stress Test stops being a test for one phone and becomes an app for any Android
phone. The loads measured on the reference phone stay as they were; what changed
is everything around them: the app finds its way round other phones, guards the
phone it runs on, explains what it measured and lets the result leave the phone.

### Added

- **Every 64-bit phone with Android 10 or later.** The CPU's core count, core
  names and clusters are read from the phone, and a kernel that needs an
  instruction the CPU lacks is not offered. Thermal zones are grouped by their
  names on Qualcomm, MediaTek, Exynos, Tensor and Unisoc phones, and the GPU's
  load is read from whichever counter the phone exposes, or from the app's own
  frame times when it exposes none.
- **Graphics quality for the cinematic scenes: Low, Medium and High.** Medium is
  the scenes as they were tuned on a mid-range phone; High is for the newest
  flagships and drops a mid-range phone to a few frames a second; Low is for
  entry-level phones.
- **Device safety, on by default.** A test stops when the battery reaches
  47 °C, the CPU or GPU 110 °C, the case 48 °C, when Android reports a severe
  thermal status, or when the battery falls to 5 %, once the reading has held
  for three seconds. A hot phone or a low battery does not start a test, and the
  home screen says so before the button is pressed. Turning it off asks first.
- **A safety notice each time the app opens**: a test runs the phone at full
  capacity and makes it very hot, device safety cannot guarantee protection, and
  the responsibility is the user's. It can be turned off and back on.
- **Settings**: device safety, scene quality, the display during a test,
  vibration, the safety notice and the app's language.
- **English**, alongside Turkish, the only language of 0.1.0. The app follows
  the phone's language unless one is chosen in its settings.
- **An analysis of every run** in plain words: when the phone first throttled,
  how much speed it gave up, how hot the chip and the battery got, how fast the
  battery drained, whether any computation went wrong.
- **Charts that read out their values** under a finger: power, temperatures,
  clocks, work rate and frame rate.
- **A two-page PDF report** and the run's curves as CSV, shared or saved.
- **Two runs compared** side by side, with their curves on one chart.
- **A device screen**: the processor, cores and GPU, live readings, which
  sensors the app may read, and manual loads.

### Changed

- **The app is called Stress Test**, in every language and on every screen.
- **A redesigned interface**: a dark graphite theme with a heat gradient, glass
  cards, animated gauges and numbers, three tabs, and the Space Grotesk and
  JetBrains Mono typefaces.
- **The screen stays on only while a test runs**, rather than on every screen.
- **A CPU test's gauge appears with the load**, rather than behind the idle
  measurement's countdown.
- **The minimum Android version is 10** rather than 16, and the GPU code needs
  Vulkan 1.1 rather than 1.2; without it the GPU and cinematic modes are
  switched off and the CPU modes still run.
- **Release packages are signed with a release key** rather than the debug key.

### Fixed

- Turkish text in capitals no longer loses the dot of an "i".
- Starting the same test a second time no longer brings back the first run's
  state.
- A negative percentage in Turkish reads "-%6" rather than "%-6".

### Security

- **Lab sessions can only be started from the adb shell.** In 0.1.0 the launcher
  accepted the extras that start one, so any installed app could wake the
  screen and start a load. The launcher now ignores them, and lab sessions go
  through an entry that requires `android.permission.DUMP`.

## [0.1.0] — 2026-10-01

The first version, built for one phone, an Honor 400, around one question: how
much power can it really draw?

### Added

- Five modes: **Full load** (CPU and GPU together, 10.1 W on the reference
  phone), **Cinematic** (the CPU at full load under a real-time 3D scene),
  **CPU**, **GPU** and **Dry 100 %** (cores fully busy doing next to nothing,
  for comparison).
- CPU loads in generated AArch64 assembly that check their own results, chosen
  among candidates by the power each drew.
- Vulkan GPU burners and three ray-marched cinematic scenes: a reactor pool, a
  rain forest and a white world.
- Power measured from the battery ten times a second, and an analysis inside the
  app.
- Lab sessions, started over adb, that compare loads on battery by themselves.

[Unreleased]: https://github.com/OzcanOrhanDemirci/stress_test/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/OzcanOrhanDemirci/stress_test/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/OzcanOrhanDemirci/stress_test/releases/tag/v0.1.0
