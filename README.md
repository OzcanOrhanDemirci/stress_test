<div align="center">

# Stres

**A stress test and benchmark for one phone, built to find the most power it can really draw, and to look good while it does.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![C++20](https://img.shields.io/badge/C%2B%2B-20-00599C?logo=cplusplus&logoColor=white)](app/src/main/cpp)
[![AArch64 assembly](https://img.shields.io/badge/AArch64-assembly-555555)](app/src/main/cpp/cpu)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.2-AC162C?logo=vulkan&logoColor=white)](app/src/main/cpp/gpu)
[![Min SDK](https://img.shields.io/badge/minSdk-36-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)
[![Device](https://img.shields.io/badge/device-Honor%20400%20%C2%B7%20Snapdragon%207%20Gen%203-FF6A21)](#the-device)
[![Status](https://img.shields.io/badge/status-complete-success)](#status)

**10.1 W** at full load · **2.6×** the power of an "idle 100 %" on the same cores · three cinematic scenes · everything analysed on the phone

[The question](#the-question) · [Results](#results) · [Modes](#modes) · [Scenes](#the-cinematic-scenes) · [How it measures](#how-power-is-measured) · [How it loads](#how-the-load-is-made) · [Architecture](#architecture) · [Building](#building)

*[Türkçe](README.tr.md)*

<br />

<img src="docs/images/cover.jpg" alt="One frame of each cinematic scene, on the phone: the reactor pool, the rain forest, the white world" width="760" />

</div>

---

A private, single-device project: it targets one **Honor 400** and nothing else,
is never published, and lives as an APK on that phone. That narrowness is the
point. Every number below was measured on the device the code was written for,
and every choice in the code was made against those measurements rather than
against a generic phone.

## The question

Most stress tests on a phone report the same thing: every core at 100 %. That
number says how busy a core is, not how hard it is working. A core can be fully
occupied by a chain of dependent integer additions and leave almost all of its
units idle; it can also be kept busy by vector multiply-adds streaming operands
from the cache, with the arithmetic units, the load path and the cache all
running at once. Both read as 100 %. Only one of them heats the phone.

It is the difference between Cinebench and Prime95, and this project measures
it. On this phone:

| What runs on all eight cores | Reported load | Battery power |
| --- | --- | --- |
| Dependent integer additions (`dry`, the "idle 100 %") | 100 % | **2.46 W** |
| FP32 fused multiply-add, operands streamed from L2 (`fp32_l2`) | 100 % | **6.39 W** |
| The same, plus the GPU's FP32 burner (**Full load**) | 100 % + GPU 100 % | **10.1 W** ¹ |

<sub>The first two rows are one session, at room temperature: the same 100 %, 2.6 times the power.
¹ Measured in a later session with the phone on a metal plate in front of a fan, which sustains
more power; in that session `fp32_l2` alone drew 7.10 W.</sub>

So the single criterion here is **power drawn from the battery, in watts**. A
load is only "the maximum" if nothing measured draws more. There are no thermal
cut-offs and no protective throttling of its own: the phone's own governors do
whatever they do, and the result screen shows when and how much.

The same lesson turned up on the GPU later. A ray-marched scene keeps the GPU
100 % busy yet draws 2.95 W; an FP32 multiply-add burner on the same GPU draws
5.45 W. Busy is not working, there either.

## Results

All figures are battery power from Android's `BatteryManager`, measured with the
phone unplugged, in sessions the phone runs by itself (see
[How power is measured](#how-power-is-measured)). The full record, with every
session's conditions, is in [docs/OLCUMLER.md](docs/OLCUMLER.md).

**CPU kernels, all eight cores, mean of the first 30 s, two runs each in random order, room temperature:**

| Kernel | What it exercises | Power |
| --- | --- | --- |
| `fp32_l2` | FP32 FMA, operands streamed from a 256 KiB buffer | **6.39 W** |
| `i8_mmla` | INT8 matrix multiply-accumulate (SMMLA) | 5.27 W |
| `fp64_gemm` | FP64 FMA, 8×6 outer product from L1 | 5.08 W |
| `mixed` | FMA, integer chain, multiplier and stores at once | 4.95 W |
| `bf16_mmla` | BF16 matrix multiply-accumulate (BFMMLA) | 4.80 W |
| `fp32_dram` | FP32 FMA, operands streamed from 32 MiB | 4.76 W |
| `fp32_gemm` | FP32 FMA, 8×12 outer product from L1 | 4.54 W |
| `memcopy` | Copying 32 MiB: pure memory bandwidth | 4.13 W |
| `i8_dot` | INT8 dot product (SDOT) | 3.95 W |
| `fp32_reg` | FP32 FMA in registers only | 3.93 W |
| `dry` | Dependent integer additions | **2.46 W** |

Two things stand out. Arithmetic alone is not what draws the power:
register-only FMA keeps the FMA units full and stays at 3.93 W. Moving the data
is. And the buffer has a sweet spot: the same kernel draws 6.62 W at 128 KiB,
**7.10 W at 256 KiB**, 6.82 W at 512 KiB, then falls to 4.8 W at 1 and 2 MiB,
where the cores mostly wait for memory. (The second set of numbers comes from a
session with the phone on a metal plate in front of a fan, which sustains more
power; within a session the ranking is what counts.)

**GPU burners, alone:** FP32 ALU 5.57 W · FP16 ALU 3.70 W · blending 3.09 W ·
texture sampling 3.03 W · memory bandwidth 2.73 W.

**Together:** `fp32_l2` on the CPU with the FP32 burner on the GPU is the
highest measured, **10.09 W and 10.13 W** in two sessions. The CPU gives up some
frequency to the shared budget (A510 / A715 / prime settle near
1.1 / 1.8 / 2.1 GHz) and the total is still the highest.

## Modes

| Mode | Load | On the screen | Measured |
| --- | --- | --- | --- |
| **Full load** | `fp32_l2` + GPU FP32 burner | a load gauge (the burner keeps the GPU) | **10.1 W** |
| **Cinematic** | `fp32_l2` + a scene | one of three scenes, chosen on the home screen | 8.9 W with the pool |
| **CPU** | `fp32_l2` on all eight cores | a gauge | 7.1 W (6.4 W uncooled) |
| **GPU** | GPU FP32 burner | a load gauge | 5.5 W |
| **Dry 100 %** | `dry` | a gauge | 2.5 W (uncooled) |

<sub>Measured with the phone on a metal plate in front of a fan unless marked uncooled.</sub>

A run lasts 5, 15 or 30 minutes, or until stopped. It starts with 10 seconds at
rest to measure what the phone draws doing nothing, then drives the display at
full brightness and its highest refresh rate, the same way every time, so runs
compare.

Why the scenes are a mode of their own rather than the background of Full load
is a measured decision: with the scene on screen, the burner has no frame time
left and full load drops to 8.97 W, 88.5 % of the maximum. The rule written
before the measurement was 97 %, so the scenes became their own mode
([docs/PLAN.md](docs/PLAN.md), §13/7).

## The cinematic scenes

The second goal of the project, added halfway: *the most impressive picture this
phone can show*. Each scene is rendered at 45 % of the screen's resolution and
built up to full resolution over time: every frame moves its samples by a
sub-pixel jitter and reseeds its random effects, a temporal pass reprojects the
previous frames with the camera and clips them against the new one, and a
Catmull-Rom filter scales the result to the screen. The phone's GPU has about
1 TFLOPS of arithmetic but only about 16 GB/s of memory bandwidth, so every
effect is chosen to cost arithmetic rather than memory.

### Pool

<img src="docs/images/scene-pool.jpg" alt="The pool scene on the phone: the ring over the pool, the fuel rods under water, the ring's spikes close up, the core from above" width="100%" />

A research reactor seen from its pool deck. One full-screen ray-marching shader
draws the hall, the spiked containment ring and the fuel assembly glowing
Cherenkov blue under the water. The water surface is a 256 × 256 height field
solved with the wave equation 60 times a second on the GPU: bubbles bursting
over the core start rings that cross, reflect off the pool wall and scatter round
the control-rod tubes, and the surface bends the light into caustics on the
floor. 16,384 GPU particles (rising bubbles, sparks circling the ring), bloom,
depth of field per shot, and a pulse: once a loop the reactor flashes as a TRIGA
does. **~48 ms a frame, ~20 fps.**

### Forest

<img src="docs/images/scene-forest.jpg" alt="The forest scene on the phone: towards the light, from under the crowns in the rain, ferns close up, rising in the clearing" width="100%" />

A temperate rain forest after rain. Unlike the pool this one is geometry: up to
676 spruce and beech on a jittered grid, their branches and needle sprays, ferns
and fallen trunks.
None of it is stored. A vertex shader builds every vertex from its draw and
instance numbers (vertex pulling), and a fragment shader cuts each card to the
outline of a spray of needles or a cluster of leaves. The sun's shadow map is
drawn once; a light pass walks each pixel's ray through the mist against it and
draws the shafts of light. 8,192 rain streaks catch the light inside the beams,
and rings spread on the puddles. **~31 to 39 ms a frame, ~25 to 30 fps.**

### White

<img src="docs/images/scene-white.jpg" alt="The white scene on the phone: through a gate, a ribbed glass tunnel, the room of stacked cubes, the void of floating cubes" width="100%" />

About clarity rather than drama: an endless white space, a polished floor that
mirrors everything, and a camera flying without a cut through a void of floating
cubes, a glass corridor, a ribbed glass tunnel and a room walled with stacked
cubes. The glass is thin and pale green-blue, deeper at a slant, so the vast
white world stays in view through it. No depth of field, no colour fringing, no
grain, almost no bloom, and a light sharpening on top of the upscale.
**~38 ms a frame, ~27 fps.**

## The app

<img src="docs/images/app.jpg" alt="The home screen with the scene chooser, a cinematic run with its readout, the result screen, the load gauge of Full load" width="100%" />

Everything is analysed on the phone, and nothing is exported. The result of a
run shows peak and sustained power, average, idle, energy used, the battery's
percentage before and after, how long a full battery would last at this load,
computation errors, maximum temperatures per cluster, GPU, memory and battery,
when each cluster first throttled and how stable the CPU's work rate stayed, and
charts of power, temperature, frequency and work over time. A history keeps
every run; a diagnostics screen shows what the app can read on this phone.

The screenshots above were taken with the charger connected, which is why the
power fields read *invalid*: while charging, the battery's current says nothing
about what the phone draws, and the app refuses to show it.

## How power is measured

The phone gives an app no access to its battery sysfs; the only source is
`BatteryManager`. Its fields on this phone needed measuring before they could be
trusted:

- `CURRENT_NOW` is in **milliamperes** (not microamperes), negative while
  discharging, updated once a second. The app infers the unit and the sign from
  the readings rather than assuming them.
- `CHARGE_COUNTER` is in **milliampere-hours** (not microampere-hours) and moves
  every 30 seconds or so; the voltage arrives only with the battery broadcast,
  about as often. A unit assumption here once made the battery-life estimate
  wrong by a factor of a thousand.
- Any sample taken while plugged in is marked invalid.

A sampler reads power, every thermal zone, the frequency of each cluster and the
GPU's busy counter ten times a second and keeps a bounded log.

**Comparing loads fairly** needs the cable out (a charging battery says nothing
about what the phone draws), and this phone drops adb over TCP and wireless
debugging alike the moment the cable is unplugged. So comparisons run as **lab sessions** the phone runs by itself:
`tools/lab.mjs` starts a session over the cable, the phone waits until it is
unplugged, then for each load in random order it waits until its CPUs have
cooled below a set temperature, measures 10 seconds at rest and 60 seconds under
load, and saves the samples. Plugged back in, `lab.mjs pull` and `report`
bring the results back and rank them.

## How the load is made

**CPU.** Eleven kernels, plus four buffer-size variants of the winner, written
in AArch64 assembly by a generator (`tools/gen_kernels.py`) so that every one has
the same signature and the same discipline:

- Each batch of iterations ends in a **digest** of its results, compared against
  a golden value. A kernel that heats the phone by computing the wrong thing is
  caught: computation errors are counted and shown with every run.
- Workers are **pinned** to their core. Qualcomm's `core_ctl` parks big cores
  at idle and sometimes under load, so pinning is retried every batch and
  misplaced batches are counted.
- A workload is written as text, `0-3:i8_mmla,4-7:fp32_l2+gpu_fp32`, so the same
  string drives the app's modes, the lab sessions and the tests.

**GPU.** A Vulkan 1.2 engine with five burners: FP32 and FP16 arithmetic,
texture sampling, memory bandwidth and blending. Three frames are kept in
flight; three timestamps split every frame into burner time and visible-pass
time, and the number of burner dispatches is sized each frame so that burner
plus scene fill the target frame time. Each dispatch's results are checked
against the first. One driver behaviour had to be found by bisecting a shader:
the Adreno 720 driver refuses a compute pipeline that copies a whole struct out
of a storage buffer (`VK_ERROR_UNKNOWN`); `particle_params.glsl` explains it, and
every pipeline that fails to build is now named in the log.

## Architecture

```
app/src/main
├── java/dev/ozcan/stress
│   ├── engine/      CPU and GPU engines, the workload grammar, the kernel and burner catalogues
│   ├── telemetry/   the 10 Hz sampler, battery and thermal readers, sysfs layout
│   ├── analysis/    power from current and voltage, windowed statistics, work rates
│   ├── run/         a stress run: modes, the controller, analysis, records and their store
│   ├── lab/         self-running comparison sessions: specs, runner, analysis, CSV
│   └── ui/          Compose screens: home, run, result, history, diagnostics, lab; charts
└── cpp
    ├── cpu/         the kernels (generated AArch64 assembly), their table, the worker threads
    ├── gpu/         the Vulkan helpers, the burner engine, the cinematic scene renderer
    │   └── shaders/ GLSL: burners, the three scenes, TAA, bloom, depth of field, the finish
    ├── sense/       a native reader for thermal zones, frequencies and the GPU's busy counter
    └── jni_bridge.cpp
tools/
├── gen_kernels.py   writes the kernels' assembly
├── lab.mjs          starts lab sessions, pulls their results, ranks them
└── scene_preview.py renders the scenes on the development machine's GPU
docs/
├── PLAN.md          the plan, the device's measured facts, every decision and its reason
└── OLCUMLER.md      every measurement, with its conditions
```

The scene shaders are plain GLSL shared by the phone's renderer and a desktop
preview. `tools/scene_preview.py` runs the phone's own shaders on the
development machine's GPU through OpenGL: the scene, the water simulation and the
particles, the bloom chain, the depth of field and the final pass, at the
phone's resolution. What it shows has matched the phone's screenshots each time
they were compared, so the look of a scene could be worked on without the phone,
and the phone was used to confirm and to measure.

## Decisions worth reading

Recorded in full in [docs/PLAN.md](docs/PLAN.md), section 13, each with the
measurement that settled it.

| | |
| --- | --- |
| **One criterion: watts** | Not "100 %", not a score. A load wins only by drawing more measured power from the battery. |
| **No protections** | No temperature cut-off and no automatic stop. The phone's own governors act; the result shows when. |
| **Analysis on the phone, no export** | The phone that ran the test is where its result is read. |
| **Scenes are their own mode** | A scene drew 88.5 % of full load against a 97 % rule written before the measurement. |
| **Compute-heavy, memory-light effects** | Measured ~1 TFLOPS against ~16 GB/s: ray marching, procedural geometry and a shadow map drawn once, not big textures. |
| **A gauge, not a trefoil** | With three scenes the app reads as a benchmark, not a reactor. |
| **Off-chip loads left to the user** | The torch, the modem or the camera can be switched on by hand during a run; the recipes stay on the chip. |

## What is verified

```bash
./gradlew testDebugUnitTest            # 55 JVM tests
./gradlew connectedDebugAndroidTest    # 15 tests on the phone
```

The JVM tests cover the power arithmetic (unit and sign inference, windowed
statistics), the workload grammar, the lab specification, sample logs and
formatting. The tests on the phone check what only the phone can: that every
kernel's digest repeats exactly across runs and core types and changes with the
work done, that every core burns pinned and without errors, that every GPU
burner runs without errors and fills its frame, that **every scene** starts and
draws frames, that the sensor layout is discoverable, and that every mode's
recipe is a valid workload on the phone's real kernel and burner tables.

Warnings are errors throughout: Kotlin, C++ (`-Wall -Wextra -Wshadow
-Wconversion -Werror`) and the shader compiler (`glslc -Werror`).

## Building

```bash
git clone https://github.com/OzcanOrhanDemirci/stress_test.git
cd stress_test
./gradlew :app:installRelease
```

Requires JDK 17 or newer to run Gradle (the one bundled with Android Studio
works), Android SDK Platform 36, NDK 29.0.14206865 and CMake 4.1.2; the
shaders are compiled with the NDK's `glslc`. The build targets `arm64-v8a`
only, and `minSdk` is 36: this is built for one phone running Android 16. The
release build is signed with the debug key; it is never distributed.

### The device

| | |
| --- | --- |
| Phone | Honor 400 (DNY-NX9), Android 16 |
| SoC | Qualcomm Snapdragon 7 Gen 3 (SM7550) |
| CPU | 4 × Cortex-A510 at 1.8 GHz · 3 × Cortex-A715 at 2.4 GHz · 1 × Cortex-A715 at 2.63 GHz |
| GPU | Adreno 720, Vulkan 1.3 |
| Screen | 1264 × 2736, 60 / 90 / 120 Hz |

### The tools

```bash
node tools/lab.mjs start --loads "dry;fp32_l2;fp32_l2+gpu_fp32@preview" --repeat 2   # cable in, then unplug
node tools/lab.mjs pull && node tools/lab.mjs report                               # cable back in
python tools/scene_preview.py --scene white --times 3,18,30,44                     # moderngl, pillow, numpy
```

## Technology

| Concern | Choice |
| --- | --- |
| App | Kotlin 2.4.20, Jetpack Compose with Material 3, coroutines, kotlinx.serialization |
| Native | C++20 and AArch64 assembly through the NDK and CMake, JNI |
| Graphics | Vulkan 1.2, GLSL compiled to SPIR-V at build time and embedded |
| Build | Gradle 9.8.0, Android Gradle Plugin 9.4.1 |
| SDK | compile, target and minimum 36 (Android 16) |
| Tools | Node.js (lab sessions), Python with moderngl (scene preview, kernel generator) |

## Status

**Complete.** A hobby project, built between 30 September and 1 October 2026,
and kept here as a record. It is not maintained, it accepts no
contributions, and it stays private.

## Licence

No licence is granted. All rights reserved.

## Author

**Özcan Orhan Demirci** · Flutter and Android developer, İzmir ·
[github.com/OzcanOrhanDemirci](https://github.com/OzcanOrhanDemirci)
