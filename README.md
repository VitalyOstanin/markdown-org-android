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

- [Layout](#layout)
- [How the core is reused](#how-the-core-is-reused)
- [Building the core](#building-the-core)
- [Building the application](#building-the-application)
- [Running it on an emulator](#running-it-on-an-emulator)
- [The generated Kotlin surface](#the-generated-kotlin-surface)
- [Colour](#colour)
- [Testing](#testing)
- [Why the toolchain lives in a container](#why-the-toolchain-lives-in-a-container)

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
│   ├── Containerfile.ndk     # Rust + Android NDK + cargo-ndk
│   ├── Containerfile.ndk-build  # adds cmake/perl, needed once libgit2 is linked
│   ├── Containerfile.sdk     # JDK + Android SDK + Gradle, for the APK
│   ├── Containerfile.emulator   # adds the emulator and a system image
│   ├── build-core.sh         # build for the ABIs, then generate the bindings
│   ├── build-app.sh          # assemble the APK
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
# All three ABIs, release, stripped
ABIS="arm64-v8a armeabi-v7a x86_64" tools/build-core.sh

# Just the one that matters for a device, keeping symbols for debugging
ABIS=arm64-v8a STRIP=0 tools/build-core.sh
```

Behind a proxy, export `HTTPS_PROXY` — the script passes it through and runs
the container on the host network, so a proxy on the host loopback is
reachable.

Output:

| № | Path                                          | What it is                       |
|---|-----------------------------------------------|----------------------------------|
| 1 | `rust/jniLibs/<abi>/libmarkdown_org_ffi.so`   | loaded by the application        |
| 2 | `generated/uniffi/markdown_org_ffi/*.kt`      | the Kotlin surface               |

Library sizes, release, stripped:

| № | ABI           | Size    |
|---|---------------|---------|
| 1 | `armeabi-v7a` | 3.08 MB |
| 2 | `arm64-v8a`   | 4.79 MB |
| 3 | `x86_64`      | 5.09 MB |

Around 13 MB for all three, which is why per-ABI APKs (or an App Bundle)
matter here rather than one universal APK.

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

`compileSdk` and `targetSdk` are separate knobs: the first decides which APIs
are visible at compile time, the second which runtime behaviour the
application opts into.

## Running it on an emulator

```bash
tools/run-emulator.sh                       # starts headless, waits for boot
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.vitalyostanin.markdownorg/.MainActivity
tools/run-emulator.sh --stop
```

The container shares the host network, so the host's `adb` reaches the
emulator without going inside. `/dev/kvm` is passed through — without it qemu
falls back to software emulation and the boot takes tens of minutes.

An emulator is x86_64; how fast the core parses on ARM has to be measured on
a device.

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
cd rust && cargo test
```

The extractor has its own suite; these tests cover what this crate adds —
the projection onto the FFI types, the mapping of failures onto the error
enum a Kotlin caller catches, the sync against a repository on disk, and the
editing surface. The editing tests assert on the whole file rather than on
the line under test: an edit that disturbs a neighbouring line is exactly the
failure that turns a merge into a conflict, and an assertion scoped to one
line would not see it.

Running the built library outside Android is not possible: it links against
Android's C library, so `libdl.so` is missing on a desktop Linux host. The
FFI path itself was exercised by generating Python bindings from a host
build and calling through them.

## Why the toolchain lives in a container

The NDK, the Rust Android targets and `cargo-ndk` add up to several hundred
megabytes of build-only tooling. Keeping them in a container image means the
host stays clean and the build is reproducible from `Containerfile.ndk`
rather than from someone's shell history.
