# Contributing

Thank you for looking. This document says how the repository is worked in, so
that a change written by someone else arrives in the same shape as the ones
already here.

## Where this project came from

Stress Test began on 30 September 2026 as a personal test for a single phone, an
**Honor 400**, with one question: how much power can this phone really draw?
Version 0.2.0 turned it into an app for any Android phone. The Honor stayed on as
the reference device, and that history explains a few things worth knowing
before changing anything:

- Every load was chosen by measuring it on that phone, against the others, in
  watts drawn from the battery. The recipes stay as they are unless a
  measurement says otherwise (see [Measurements](#measurements)).
- The decision log, [docs/PLAN.md](docs/PLAN.md), and the measurement log,
  [docs/OLCUMLER.md](docs/OLCUMLER.md), are in Turkish, as are the commits made
  before the repository was opened. New commits are in English.

## Before you start

```bash
git clone https://github.com/OzcanOrhanDemirci/stress_test.git
cd stress_test
./gradlew :app:assembleDebug
```

You need a JDK of 17 or newer to run Gradle (Gradle provisions the JDK 21
toolchain itself), Android SDK Platform 36, NDK 29.0.14206865 and CMake 4.1.2.
The NDK is pinned because its assembler builds the CPU kernels and its `glslc`
compiles the shaders; a different NDK is a different measurement.

To run the app you need a 64-bit Android phone with Android 10 or later. An
x86_64 emulator image of Android 11 or later runs it through ARM translation,
which is enough to work on the screens; the emulator has no Vulkan 1.1, so the
GPU and cinematic modes are switched off there, and its power and temperature
readings mean nothing.

## The shape of a change

**One commit, one change.** A commit that fixes a bug and renames a variable is
two commits. `git log --oneline` should read like a list of decisions.

**Subjects follow [Conventional Commits](https://www.conventionalcommits.org/):**

```
type(scope): summary in the imperative, lowercase, no full stop
```

`type` is one of `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`,
`ci`, `chore`, `style` or `revert`. `scope` is the part of the app the change
lives in, such as `safety`, `scene`, `cpu`, `report` or `lab`, and may be
omitted when a change is genuinely project-wide. The whole subject stays within
80 characters, because a log is read at a glance.

This is checked rather than asked for. The pipeline runs it on every pull
request, and you can run it yourself before pushing:

```bash
.github/scripts/check-commit-subjects.sh
```

**The body says why.** A reviewer can read the diff. What they cannot read is
the option you rejected, and the measurement that settled it:

```
fix(safety): stop the chip at 110 °C rather than 105 °C

A full-load run on the reference phone held the GPU at its 95 °C throttling
point for 37 seconds, then the phone released the throttle and let it reach
107 °C. The 105 °C limit ended that run at 43 seconds, which made the app's
main test unusable with protection on.

110 °C is where the phone's own harder stage begins, 15 °C below its critical
trip point, so the limit still catches a phone whose own protection is no
longer keeping up.
```

**Branches are named after the change**, using the same type as its commits:
`fix/chip-limit`, `feat/startup-notice`, `docs/open-repository`.

## Pull requests

`main` takes no direct pushes, requires the verification pipeline to pass and
keeps a linear history. Everything lands through a pull request.

The [pull request template](.github/pull_request_template.md) asks four
questions, and the fourth is the one that matters most:

- **What** changed.
- **Why**, including the alternative you did not take.
- **Verification**: what you ran, and what you *looked at* on a phone. Not "it
  works".
- **Not verified**: what this change could break that nothing here checks.

A pull request that says what it did not prove is worth more than one that
implies it proved everything.

## Code

The conventions are not written down twice. Read the file you are changing and
match it; the [architecture section of the README](README.md#architecture) says
where things live. A few rules that are easy to miss:

- **Comments say why, never what.** The code already says what it does.
- **No placeholders, no notes to self, no dead code.**
- **Every user-visible string is a resource**, in both `values` and `values-tr`,
  with the same arguments in both; a test holds the two languages together. The
  one exception is the lab screen, which is reached only over adb and is in
  English.
- **Warnings are errors**, in Kotlin, in C++ (`-Wall -Wextra -Wshadow
  -Wconversion -Werror`) and in the shader compiler (`glslc -Werror`).
- **The CPU kernels are generated.** Change `tools/gen_kernels.py` and run it;
  never edit `app/src/main/cpp/cpu/kernels_*.S` by hand.
- **The scene shaders are shared** with `tools/scene_preview.py`, which renders
  them on a desktop GPU. A scene change can be looked at there first, but it is
  confirmed on a phone.
- **Nothing outside the app may start a load.** The launcher entry ignores
  extras; lab sessions go through an entry only the adb shell can open.
  [SECURITY.md](SECURITY.md) says why.

## Measurements

The criterion here is **power drawn from the battery, in watts**, and a claim
about it is a measurement, not an argument.

- A change that says a load draws more, or a scene costs less, comes with a lab
  session on a real phone: `tools/lab.mjs` starts it over the cable, the phone
  runs it on battery by itself, and `report` ranks the loads. Say which phone,
  and what the room and the phone's starting temperature were.
- A change to a device safety limit says what reading it was set against and on
  which phone, because a limit that is too low ends every test and a limit that
  is too high is no limit.
- Measurements made with the charger connected say nothing about power.

## Verification

```bash
./gradlew testDebugUnitTest                  # JVM tests
./gradlew lintDebug                          # Android Lint; an error fails the build
.github/scripts/check-commit-subjects.sh     # commit subjects
```

All three run on every pull request and all three must pass before a change can
reach `main`. The tests that need a phone run on a phone:

```bash
./gradlew connectedDebugAndroidTest          # every kernel, burner and scene, on the device
```

They are not in the pipeline because a build server has no ARM phone with a
Vulkan GPU; run them on yours when a change touches the native code, the
shaders or the engines, and say so in the pull request. The debug build installs
as a separate package (`dev.ozcan.stress.debug`), so the tests never touch an
installed release or its history.

The subject check can also run while a message is being written:

```bash
git config core.hooksPath .githooks
```

Beyond that, this project holds one rule above the rest:

> **Run it on the phone before saying it is done.**

A change that compiles, passes its tests and has never been looked at is not
finished. Install it, open the screen it touches, and say in the pull request
what you saw.

## Releasing

Releases are cut from `main` by pushing a tag. The version lives in
`gradle.properties` and nowhere else, and the pipeline refuses to publish a tag
that disagrees with it. [docs/RELEASE.md](docs/RELEASE.md) has the full
procedure, including what happens to the signing key.

## Reporting things

- **A defect or an idea:** open an [issue](https://github.com/OzcanOrhanDemirci/stress_test/issues).
- **A security problem:** do not open an issue. [SECURITY.md](SECURITY.md) says
  what to do instead.
- **Behaviour:** everyone taking part is held to the
  [Code of Conduct](CODE_OF_CONDUCT.md).
