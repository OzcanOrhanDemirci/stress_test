# Security

## What this application can reach

Knowing the shape of the application is most of the answer to what can go wrong
with it, so it is worth stating plainly.

- **It asks for no permissions.** The manifest declares none. One appears in the
  built package, `dev.ozcan.stress.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`: it
  is added during the build by `androidx.core`, it is declared at the
  `signature` protection level and it is named after this application, so
  nothing outside this package can hold it. That one is all of them, and reading
  them out of the package is the way to confirm it:

  ```bash
  aapt2 dump permissions stress-test-0.2.0.apk
  ```

- **It cannot reach the network.** Without the `INTERNET` permission there is no
  way for it to send anything anywhere. There is no backend, no account, no
  analytics, no crash reporting and no advertising.
- **What it reads, it reads from the phone itself:** the battery's current,
  voltage and temperature through `BatteryManager`, the thermal zones, CPU
  frequencies and the GPU's busy counter from the files the kernel exposes to
  every app, and Android's thermal status. It writes nothing outside its own
  storage.
- **Everything it keeps is on the device**, in the application's private storage:
  the runs and the settings. Backup is off, so the runs do not leave the phone
  with a cloud backup. A report leaves only when you share or save it, through
  Android's own share sheet or file picker.

## The load itself

This application exists to make a phone work as hard as it can, which makes it
hot. That is the purpose rather than a defect, but it puts two things in the
security boundary:

- **Device safety** is on by default and stops a test before the battery, the
  case or the chip gets dangerously hot. It reads the phone's sensors and cannot
  do better than they do; the app says so each time it opens. A way to make a
  test run past a safety limit while device safety is on is a security problem
  and should be reported as one.
- **Nothing outside the app can start a load.** The launcher entry ignores every
  extra it is given. The entry for lab sessions, which can wake the screen and
  run a load for as long as it is told, requires `android.permission.DUMP`,
  which the adb shell holds and no installed app can be granted. A way for
  another app to start a load is a security problem.

## The signing key

The one secret this project has is the release signing key. It has never been in
this repository and never will be. It is read from an ignored
`keystore.properties` or from environment variables, and on the build server it
comes from repository secrets, is written to the runner's temporary directory for
the length of the job and is deleted afterwards.
[docs/RELEASE.md](docs/RELEASE.md) describes this in full.

## Supported versions

The most recent release is the supported one. Older versions receive nothing.

| Version | Supported |
| --- | --- |
| 0.2.x | Yes |
| < 0.2 | No |

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Use GitHub's
[private vulnerability reporting](https://github.com/OzcanOrhanDemirci/stress_test/security/advisories/new),
or write to **ozcanorhandem@gmail.com** with `stress_test security` in the
subject.

Please include what you found, the version or commit you found it in, the phone
and Android version, and the steps to reach it. A report that can be reproduced
is worth far more than one that has to be guessed at.

You can expect an acknowledgement within **three days** and an assessment within
**seven**. If the report is valid you will be told what the fix is and when it
ships, and you will be credited in the [changelog](CHANGELOG.md) unless you
would rather not be.

## Verifying a package

Every release is signed with the same key. Before installing a package that
claims to be this application, check what signed it:

```bash
apksigner verify --print-certs stress-test-0.2.0.apk
```

The certificate should read:

```
CN=Ozcan Orhan Demirci, OU=Stress Test, O=Ozcan Orhan Demirci, L=Izmir, ST=Izmir, C=TR
SHA-256: b1dbd74228412c2fb79ce71cb36e735c975c4664fa0851527be2b3889b06d9a5
```

A package signed with anything else did not come from here. A package built from
this source without the key installs and runs, signed with a debug key; it cannot
update an installation of a released one, and a released one cannot update it.
