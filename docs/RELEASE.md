# Releasing

## What the pipeline does

Pushing a tag that starts with `v` runs `.github/workflows/release.yml`, which:

1. Checks that the tag matches `stress.versionName` in `gradle.properties`, and
   stops if it does not.
2. Installs the pinned NDK and CMake.
3. Restores the signing key from repository secrets into the runner's temporary
   directory, and stops if they are not there.
4. Runs the unit tests.
5. Assembles the release build: minified, resources shrunk, signed.
6. Confirms which key was used by reading the certificate's SHA-256 digest out
   of the finished package, and stops if it is not the release key's.
7. Takes the release notes out of this version's section in
   [CHANGELOG.md](../CHANGELOG.md), and stops if there is none.
8. Publishes the release with the package attached as
   `stress-test-<version>.apk`.
9. Keeps the R8 mapping file as a build artefact for ninety days.
10. Deletes the key.

```bash
# gradle.properties says stress.versionName=0.2.0 and CHANGELOG.md has a [0.2.0] section
git tag -a v0.2.0 -m "Stress Test 0.2.0"
git push origin v0.2.0
```

The same workflow can be started by hand from the Actions tab. That is a dry
run: every step up to and including the certificate check and the release notes,
then the package is kept as an artefact for seven days instead of being
published. Run it before the first tag of a version, and after any change to the
pipeline.

## Why the tag is checked against the source

The tag is what people quote. The version inside the package is what a phone
compares against when deciding whether an update is newer. A release where those
two disagree is worse than no release, because it is wrong in a way nobody
notices until an update silently refuses to install.

They come from one place, `gradle.properties`, and the pipeline refuses to
publish a tag that disagrees with it. `stress.versionCode` goes up by one with
every release.

## Why the certificate is checked

A package signed with the debug key installs and runs, and is quietly the wrong
package: it cannot update an installation of the released one, and the mistake
only becomes visible to whoever already has it installed. The pipeline compares
the certificate's digest, not its name, because a name can be typed into any key.

## The key

The signing key is not in this repository and never has been. It is read from
`keystore.properties`, which is ignored, or from four environment variables:

| Variable | Meaning |
| --- | --- |
| `STRESS_KEYSTORE_FILE` | Path to the key store |
| `STRESS_KEYSTORE_PASSWORD` | Its password |
| `STRESS_KEY_ALIAS` | The alias inside it |
| `STRESS_KEY_PASSWORD` | The password of that alias |

On the build server the store itself comes from the `STRESS_KEYSTORE_BASE64`
secret, holding it base64 encoded, and the other three from secrets of the same
names. It is written to the runner's temporary directory for the length of the
job and deleted afterwards.

When none of this is present, the release build is signed with the debug key.
That is deliberate: anyone can clone this repository and produce a working
package. What they cannot produce is one that updates an installation of a
released one.

**If the key is lost, a released installation can never be updated again.** Not
by anyone, including its author. It is worth more than the source, because the
source can be rewritten.

## Building a release by hand

```bash
./gradlew :app:assembleRelease
```

The result is at `app/build/outputs/apk/release/app-release.apk`. Confirm what
signed it before giving it to anyone:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Measure with a release build

A debug build has no minification and carries extra checking. Power, frame
times and temperatures are measured on a release build, the package people will
actually install; the lab sessions in `tools/lab.mjs` start the release package
for that reason.
