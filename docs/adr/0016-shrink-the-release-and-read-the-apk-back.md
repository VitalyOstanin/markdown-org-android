# ADR-0016: The release is shrunk, and the APK is read back to check it

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-31).

## Context

The published APK is 30.9 MB. Two thirds of that are the native libraries of
the two ABIs, which shrinking does not touch; the dex is 7.65 MB, and most of
it is Compose and AndroidX of which this application calls a corner. Shrinking
takes the dex to 1.20 MB and the resources from 0.49 to 0.17 MB — 6.8 MB, a
fifth of the download.

R8 was off because nothing checked a shrunk build still worked. The core is
reached through JNA: the generated layer declares an interface whose methods
JNA binds to symbols in the native library, and hands Rust structures whose
fields JNA reads by reflection. None of that is reachable to the analysis, so
a missing keep rule produces an APK that installs and dies at the first call
into the core.

The obvious check — run the instrumented tests against the shrunk variant —
does not work, and not for want of keep rules. AGP leaves out of the test APK
whatever the application under test already ships, so every class R8 drops
from the application takes the runner down with it. In order: the runner died
on `androidx.tracing.Trace`, then on `kotlin.LazyKt`, each a class the
application carries but never calls. Keeping them means keeping, in the
published build, whatever the test dependencies happen to reach — which is the
opposite of what the shrinking is for. Slack's Keeper plugin exists for exactly
this, inferring the rules from the compiled tests; its last release is from
June 2024 and says nothing about the current AGP.

## Decision

R8 and resource shrinking are on for `release`. The rules that hold the
boundary live in `app/proguard-rules.pro`: JNA's own four lines from its FAQ,
the two interfaces the generated layer implements, and the generated package
itself.

What checks them is `tools/check-apk.sh`, which reads the built APK back and
fails when a name that must survive is not in the dex — the JNA interface, the
JNA class it implements, and two symbols of the native library. CI runs it on
the release APK after the signature check.

The instrumented tests keep running against `debug`.

## Consequences

- The download drops by 6.8 MB, and the dex by a factor of six.
- A broken keep rule fails the build rather than the first call into the core,
  as long as it breaks one of the names the script reads. A rule that stopped
  covering something else — a field of a structure, a callback added later —
  is not caught: the check is a smoke test, not a run.
- Anything new on the FFI boundary belongs in the keep rules, and its name
  belongs in the script.
- The stack traces of a release build are obfuscated. The mapping is in
  `app/build/outputs/mapping/release/`, and it is not published anywhere yet;
  a crash report from a user would have to be retraced against the mapping of
  that exact build.
- Splitting the APK by ABI would save more than this did (11.5 MB of the 23
  the libraries take) and is independent of it.

## References

- `app/proguard-rules.pro` — the rules and why each is there.
- `tools/check-apk.sh` — what is read back, and why it is read rather than run.
- [android/android-test#2167](https://github.com/android/android-test/issues/2167)
  — the state of keep rules for androidx.test.
- [Keeper](https://github.com/slackhq/keeper) — the plugin that would close the
  gap, if it turns out to work with this toolchain.
