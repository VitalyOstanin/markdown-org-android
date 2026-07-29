# markdown-org-android

An Android client for markdown files carrying Emacs Org-mode task markers —
the same format the [`markdown-org-vscode`](https://github.com/VitalyOstanin/markdown-org-vscode)
extension reads, kept in sync over git.

**Status: early.** The Rust core, its Kotlin bindings and a Compose
application that renders the agenda all build. The agenda syncs over git and
takes point edits — status, priority, a planning date, completion. Until a
remote is configured the notes are a sample the application writes on first
run. Nothing has been run on a physical device yet.

## Table of contents

- [Requirements](#requirements)
- [Layout](#layout)
- [How the core is reused](#how-the-core-is-reused)
- [Building the core](#building-the-core)
- [Building the application](#building-the-application)
- [Running it on an emulator](#running-it-on-an-emulator)
- [Continuous integration](#continuous-integration)
- [The generated Kotlin surface](#the-generated-kotlin-surface)
- [Colour](#colour)
- [Testing](#testing)
- [Environment variables](#environment-variables)
- [Why the toolchain lives in a container](#why-the-toolchain-lives-in-a-container)

## Requirements

| № | What                           | Needed for                                                        |
|---|--------------------------------|-------------------------------------------------------------------|
| 1 | [podman](https://podman.io/)   | every build; the toolchain never touches the host                 |
| 2 | `adb` (Android platform-tools) | installing on a device or emulator, and picking the target        |
| 3 | Access to `/dev/kvm`           | the emulator; without it qemu boots in software for tens of minutes |
| 4 | Around 11 GB of disk           | the images: NDK 4.3 GB, SDK 1.2 GB, emulator 6.9 GB including the SDK layers |

The images are built on first use — no separate step, but the first run of
each script takes a while and says which image it is building. Behind a
proxy, export `HTTPS_PROXY`: the scripts pass it to both the image build and
the container, and run on the host network so a proxy on the loopback is
reachable.

The pinned versions of the SDK, the NDK, the JDK and Gradle live in
[`tools/versions.env`](tools/versions.env) — read by the scripts, by the
Gradle build and by the CI workflow, so the three cannot drift apart.

## Layout

```
markdown-org-android/
├── app/                      # the Compose application
│   └── src/main/kotlin/…/
│       ├── core/             # the bridge to the Rust core, and where notes live
│       └── ui/               # the agenda screen and the palette
├── rust/
│   ├── markdown-org-ffi/     # UniFFI wrapper over markdown-org-extract
│   │   ├── src/lib.rs        # the exported surface and its type projection
│   │   └── tests/            # tests for the projection and error mapping
│   └── uniffi-bindgen/       # binding generator entry point
├── tools/
│   ├── versions.env          # the pinned SDK, NDK, JDK and Gradle versions
│   ├── lib.sh                # shared by the scripts: proxy, images, versions
│   ├── Containerfile.ndk     # Rust + Android NDK + cargo-ndk
│   ├── Containerfile.sdk     # JDK + Android SDK + Gradle, for the APK
│   ├── Containerfile.emulator   # adds the emulator and a system image
│   ├── build-core.sh         # build for the ABIs, then generate the bindings
│   ├── test-core.sh          # the Rust tests, in the NDK image
│   ├── check-core.sh         # cargo fmt --check and clippy, in the same image
│   ├── gradle.sh             # any Gradle task, in the SDK image
│   ├── build-app.sh          # assemble the APK
│   ├── test.sh               # the JVM tests of the application
│   ├── test-instrumented.sh  # the instrumented tests, on a booted emulator
│   ├── coverage.sh           # what the JVM tests reach, as a Kover report
│   ├── run-app.sh            # assemble, install and start in one command
│   └── run-emulator.sh       # start the headless emulator and wait for boot
├── rust/jniLibs/<abi>/       # build output, not committed
└── generated/                # generated Kotlin, not committed
```

## How the core is reused

Task extraction is not reimplemented. The application calls
[`markdown-org-extract`](https://github.com/VitalyOstanin/markdown-org-extract)
— the same Rust code the CLI and the VS Code extension run — through
[UniFFI](https://github.com/mozilla/uniffi-rs) bindings.

In-process, not as a subprocess: Android does not let an application spawn
the CLI, and a subprocess would also mean serialising to JSON and parsing it
back for data the same process is about to render.

`markdown-org-ffi` is a thin projection layer. It does not re-export the
extractor's own types; it flattens them into records UniFFI can carry, so
the extractor stays free to change its internals and only this crate has to
follow. Reading the notes:

- `scan(dir, options)` — walk a directory, return every task found;
- `scanAgenda(dir, scope, currentDate, timezone, includeDone, options)` —
  walk it and return the agenda for a day, week, month, or the flat task
  list.

`currentDate` is what the agenda treats as today. The caller passes it
rather than letting the library read the clock, so the same files render the
same agenda whenever they are asked for — the contract the CLI follows
through `--current-date`.

Writing to them, one line at a time:

- `setStatus(target, status)` — set, replace or clear the keyword;
- `setPriority(target, priority)` — the same for the `[#A]` cookie;
- `shiftPlanning(target, keyword, days)` — move a `SCHEDULED` or `DEADLINE`
  date;
- `completeTask(target, today)` — mark done, or move a repeating task to its
  next occurrence and leave it open, following upstream Org-mode's
  `org-auto-repeat-maybe`;
- `commitChanges(dir, message, author)` — commit the working copy.

There is no text editor and no whole-file write: each call rewrites exactly
one line, keeping the rest of the file byte-for-byte. That is what lets git
merge an edit made on the phone with one made on a laptop instead of
reporting a conflict. `target` carries the file, the line and the heading the
caller believes is there — a file that moved on since the agenda was built is
refused rather than overwritten.

The grammar itself stays in the extractor: it reports where each token of a
heading or a timestamp sits (`parseHeadingLine`, `parseTimestampParts`), and
this crate splices the replacement in. A second copy of those rules here
would drift from the one that reads the files.

## Building the core

Requires [podman](https://podman.io/) and nothing else; the first run builds
the container image, which downloads around 700 MB of NDK.

```bash
# Both ABIs the APK carries, release, stripped
ABIS="arm64-v8a x86_64" tools/build-core.sh

# Just the one that matters for a device, keeping symbols for debugging
ABIS=arm64-v8a STRIP=0 tools/build-core.sh
```

Output:

| № | Path                                          | What it is                       |
|---|-----------------------------------------------|----------------------------------|
| 1 | `rust/jniLibs/<abi>/libmarkdown_org_ffi.so`   | loaded by the application        |
| 2 | `generated/uniffi/markdown_org_ffi/*.kt`      | the Kotlin surface               |

Library sizes, release, stripped:

| № | ABI         | Size     |
|---|-------------|----------|
| 1 | `arm64-v8a` | 10.76 MB |
| 2 | `x86_64`    | 11.56 MB |

Most of that is vendored: libgit2 and the TLS stack it syncs over are built
into the library because neither is present on an Android device. The APK
carries both ABIs and comes to around 31 MB.

The order in `build-core.sh` is not incidental. UniFFI keeps the interface
metadata in the library's symbol table, so the bindings must be generated
before the library is stripped; the entry points the application calls live
in the dynamic symbol table and survive stripping. Building with
`strip = "symbols"` instead fails with `No UniFFI metadata found`.

## Building the application

The core has to be built first: the APK packages the libraries and compiles
the Kotlin the binding generator produced.

```bash
ABIS="arm64-v8a x86_64" tools/build-core.sh
tools/build-app.sh                  # debug
VARIANT=release tools/build-app.sh
```

Output is `app/build/outputs/apk/<variant>/app-<variant>.apk`. The Gradle
cache lives in a named podman volume, so only the first run downloads the
dependency graph.

| № | Setting      | Value | Why                                                                          |
|---|--------------|-------|------------------------------------------------------------------------------|
| 1 | `compileSdk` | 37    | androidx.lifecycle 2.11 refuses to be consumed by a project compiled below it |
| 2 | `targetSdk`  | 36    | what Google Play requires of new applications from 31.08.2026                 |
| 3 | `minSdk`     | 26    | `java.time` without desugaring, and the agenda is date arithmetic throughout  |

`compileSdk` comes from `tools/versions.env` along with the build tools and
the JDK, since the image has to ship the platform the build asks for;
`minSdk` and `targetSdk` are decisions of the application alone and stay in
`app/build.gradle.kts`.

`compileSdk` and `targetSdk` are separate knobs: the first decides which APIs
are visible at compile time, the second which runtime behaviour the
application opts into.

## Running it on an emulator

```bash
tools/run-emulator.sh                       # starts headless, waits for boot
tools/run-app.sh                            # assemble, install, start
tools/run-emulator.sh --stop
```

`run-emulator.sh` builds the emulator image the first time it is called, and
the SDK image it extends before that — around 7 GB and a good while, once.
`run-app.sh` is the short loop for a change to the interface; the three steps
it replaces are still available separately (`tools/build-app.sh`, `adb
install -r`, `adb shell am start`).

The container shares the host network, so the host's `adb` reaches the
emulator without going inside. `/dev/kvm` is passed through — without it qemu
falls back to software emulation and the boot takes tens of minutes.

An emulator is x86_64; how fast the core parses on ARM has to be measured on
a device.

## Continuous integration

[`.github/workflows/build.yml`](.github/workflows/build.yml) builds the same
two ABIs and publishes the APK. Two jobs:

| № | Job     | What it does                                                                      |
|---|---------|-----------------------------------------------------------------------------------|
| 1 | `check` | `cargo fmt --check`, `cargo clippy -D warnings` and `cargo test` for the host      |
| 2 | `build` | the core for both ABIs, the unit tests, the APK, and the release that carries it   |

The core is built by `tools/build-core.sh` with `NATIVE=1`, which runs the
steps directly instead of in a container. The workflow does not repeat them:
the order of build, binding generation and stripping is the one thing that
must not drift between the two, since generating from a stripped library
fails.

What comes out depends on the trigger:

| № | Trigger              | Variant | Published as                                     |
|---|----------------------|---------|--------------------------------------------------|
| 1 | push to `master`     | release | prerelease `v<version>.<run number>`             |
| 2 | tag `v*`             | release | release under that tag                           |
| 3 | `workflow_dispatch`  | release | prerelease, or the tag given as an input         |
| 4 | pull request         | debug   | build artefact only                              |

A pull request builds the debug variant: it has no access to the signing key
and does not need one. Everything else is signed with the release key, which
has to stay the same from build to build — an APK signed with a different key
does not install over the one already on the phone, and registering the
application id under Google's developer verification pins it to that key.

Four repository secrets carry it: `APP_KEYSTORE_BASE64`,
`APP_KEYSTORE_PASSWORD`, `APP_KEYSTORE_ALIAS`, `APP_KEY_PASSWORD`. The key is
decoded into `RUNNER_TEMP`, outside the workspace, so that no later step can
pick it up as a build input.

Actions are pinned by commit SHA with the tag in a comment, and the runner is
`ubuntu-24.04` rather than `ubuntu-latest`: an image that moves under an
unchanged commit makes a build that passed once impossible to reproduce.

## The generated Kotlin surface

Names arrive idiomatic — `scanAgenda`, `filesProcessed` — and the Rust
documentation comes across as KDoc. Errors are a sealed class:

```kotlin
val agenda = try {
    scanAgenda(
        dir = notesDir.absolutePath,
        scope = Scope.DAY,
        currentDate = "2026-03-02",
        timezone = "Europe/Moscow",
        includeDone = false,
        options = Options(glob = null, locale = null, maxTasks = null),
    )
} catch (e: ExtractException.InvalidDirectory) {
    …
}
```

Loading the library needs [JNA](https://github.com/java-native-access/jna),
and the library name has to start with `lib` — hence `markdown-org-ffi`
producing `libmarkdown_org_ffi.so`. The crate must also not be named
`android`: `libandroid.so` is a system library.

## Colour

The scheme is written out, not derived from the device wallpaper. Dynamic
colour puts every role on one hue, and the agenda uses colour to tell one
kind of entry from another — a deadline from something merely scheduled, a
repeating task from a one-off. Both themes are drawn separately rather than
inverted: the tone lightens in the dark theme while the container darkens.

Beyond the Material roles the agenda carries its own set — deadline,
scheduled, repeat, done, cancelled — in `ui/theme/AgendaColors.kt`, reached
through `LocalAgendaColors`. The values are the ones the VS Code extension
uses, so the same file reads the same way in both.

Contrast was measured against WCAG 2.1: text pairs clear 4.5, rails and
glyphs clear 3.0. A container fill sits below 3.0 by design — it is a
backdrop, and the meaning is carried by the glyph, the rail and the label,
each of which clears the threshold on its own.

## Testing

```bash
tools/test-core.sh        # the Rust tests, in the NDK image
tools/check-core.sh       # cargo fmt --check and clippy, what CI fails on
tools/test.sh             # the JVM tests of the application
tools/run-emulator.sh && tools/test-instrumented.sh   # the instrumented ones
tools/coverage.sh         # what the JVM tests reach, as a Kover report
```

Every run is bounded: each JVM test by `testOptions` in
`app/build.gradle.kts`, each instrumented one by the runner's `timeout_msec`,
and each script by a `TIMEOUT` around the whole thing. JUnit 4 interrupts
nothing on its own and libtest has no per-test timeout at all, so without
these a test that stops making progress holds the machine until something
else kills it.

The extractor has its own suite; these tests cover what this crate adds —
the projection onto the FFI types, the mapping of failures onto the error
enum a Kotlin caller catches, the sync against a repository on disk, and the
editing surface. The editing tests assert on the whole file rather than on
the line under test: an edit that disturbs a neighbouring line is exactly the
failure that turns a merge into a conflict, and an assertion scoped to one
line would not see it.

The application has two suites of its own. The JVM ones
(`tools/gradle.sh testDebugUnitTest`) cover the projections onto the screen
and the order the view model puts work in — the notes directory is a git
working copy, so a scan, a clone, an edit and the wipe before a change of
remote must not overlap, and stand-ins for the core make that assertable
without a device. The instrumented ones
(`tools/gradle.sh connectedDebugAndroidTest`) need an emulator and are the
only ones that load the native library.

Running the built library outside Android is not possible: it links against
Android's C library, so `libdl.so` is missing on a desktop Linux host. The
FFI path itself was exercised by generating Python bindings from a host
build and calling through them.

## Environment variables

Every script reads its configuration from the environment, with the default
in the script itself. The versions of the toolchain are not here — they live
in `tools/versions.env`.

| № | Variable         | Read by                                        | Default                                   |
|---|------------------|------------------------------------------------|-------------------------------------------|
| 1 | `HTTPS_PROXY`    | all container scripts, through `tools/lib.sh`   | unset; also passed as `HTTP_PROXY`        |
| 2 | `ABIS`           | `build-core.sh`                                 | `arm64-v8a`                               |
| 3 | `PROFILE`        | `build-core.sh`                                 | `release`                                 |
| 4 | `STRIP`          | `build-core.sh`                                 | `1`; `0` keeps the symbols for debugging  |
| 5 | `NATIVE`         | `build-core.sh`                                 | `0`; `1` runs on the host, as CI does     |
| 6 | `VARIANT`        | `build-app.sh`                                  | `debug`                                   |
| 7 | `ANDROID_SERIAL` | `test-instrumented.sh`, `run-app.sh`, `gradle.sh` | the booted emulator, from `adb devices` |
| 8 | `NAME`           | `run-emulator.sh`                               | `markdown-org-emulator`                   |
| 9 | `BOOT_TIMEOUT`   | `run-emulator.sh`                               | `300` seconds                             |
| 10 | `NDK_IMAGE`, `SDK_IMAGE`, `EMULATOR_IMAGE` | `tools/lib.sh`       | `localhost/markdown-org-*` tagged by version |
| 11 | `CACHE_VOLUME`   | `gradle.sh`, `test-core.sh`, `check-core.sh`    | `markdown-org-gradle` / `markdown-org-cargo` |
| 12 | `KEY_VOLUME`     | `gradle.sh`                                     | `markdown-org-android-home`, the debug signing key |
| 13 | `JOBS`, `MEMORY` | `tools/lib.sh` — every container                | `8` cores and `8g`; also `cargo -j`       |
| 14 | `TEST_THREADS`   | `test-core.sh`                                  | `8`                                       |
| 15 | `TIMEOUT`        | every script that runs tests                    | `20m` JVM, `30m` core, `40m` instrumented |

The container limits are deliberate: left alone a cargo build takes every
core on the machine, and the vendored libgit2 and OpenSSL are a lot of C to
compile. Raise them by hand when nothing else is running.

## Why the toolchain lives in a container

The NDK, the Rust Android targets and `cargo-ndk` add up to several hundred
megabytes of build-only tooling. Keeping them in a container image means the
host stays clean and the build is reproducible from `Containerfile.ndk`
rather than from someone's shell history.
