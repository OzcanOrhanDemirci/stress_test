# F-Droid and a reproducible build

Stress Test is a natural fit for F-Droid: it asks for no permissions,
cannot reach the network, and is MIT-licensed. Its dependence list is
free software throughout — AndroidX, Kotlin libraries, two OFL-licensed
typefaces — and its native code is built from source in the repository.
Nothing is downloaded at build time beyond what Gradle and Google's
Maven repositories publish.

This note is the path to being listed there, and the state of the
second thing F-Droid's users care about: a build anyone can repeat.

## The path in

F-Droid builds packages from source on its own build server. What that
takes from this repository:

1. A request for packaging ("RFP") on F-Droid's GitLab, or a metadata
   merge request in their `fdroiddata` repository. The metadata names
   the application id (`dev.ozcan.stress`), the license (MIT), the
   summary and descriptions (README.md has them, in English), and the
   versions to build.
2. The build server honours the versions the project pins itself:
   the Gradle wrapper (9.8.0), the Android Gradle Plugin (9.4.1) and
   `ndkVersion` (29.0.14206865) are all declared in the repository, and
   AGP installs exactly that NDK. The kernels' assembly is built by the
   assembler the measurements were made with, so a build that swapped
   it would be a different instrument, not only a different binary.
3. The release key is not needed: F-Droid signs packages with its own
   key. A debug-fallback build (see
   [RELEASE.md](RELEASE.md#the-key)) builds the same way their server
   would.

The languages are kept in the base package (`bundle { language { enableSplit = false } }`),
so an app bundle question does not arise; the app ships as a universal
`arm64-v8a` APK.

## The reproducible build

A build is reproducible when the same source produces the same bytes on
two machines. The tools are pinned already; what remains is to prove
it, and to keep it true:

```bash
git checkout v0.2.0
./gradlew :app:assembleRelease          # with the same signing key twice
sha256sum app/build/outputs/apk/release/app-release.apk
```

Two builds on two machines, signed with the same key, should agree on
that digest. They will not agree when one side used a different debug
key: the fallback signs with whatever `~/.android/debug.keystore`
holds, and that file is generated per machine. Compare release-signed
packages, or unsigned ones.

AGP normalises the archive's entry timestamps and order, and R8's
output is deterministic for the same inputs, so nothing in the build is
known to stand in the way. What is not yet proven is the statement
itself — it should be verified once, recorded in the release procedure,
and then checked whenever the toolchain pins move.

## Why bother

A reproducible build turns "the package came from here" into
something anyone with the source can check by rebuilding it. For an
app whose whole subject is measured truth, that is part of the story,
not a nicety.
