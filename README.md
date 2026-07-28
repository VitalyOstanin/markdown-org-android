# markdown-org-android

An Android client for markdown files carrying Emacs Org-mode task markers —
the same format the [`markdown-org-vscode`](https://github.com/VitalyOstanin/markdown-org-vscode)
extension reads, kept in sync over git.

**Status: prototype.** The Rust core and its Kotlin bindings build and are
tested; there is no application on top of them yet.

## Table of contents

- [Layout](#layout)
- [How the core is reused](#how-the-core-is-reused)
- [Building the core](#building-the-core)
- [The generated Kotlin surface](#the-generated-kotlin-surface)
- [Testing](#testing)
- [Why the toolchain lives in a container](#why-the-toolchain-lives-in-a-container)

## Layout

```
markdown-org-android/
├── rust/
│   ├── markdown-org-ffi/     # UniFFI wrapper over markdown-org-extract
│   │   ├── src/lib.rs        # the exported surface and its type projection
│   │   └── tests/            # tests for the projection and error mapping
│   └── uniffi-bindgen/       # binding generator entry point
├── tools/
│   ├── Containerfile.ndk     # Rust + Android NDK + cargo-ndk
│   ├── Containerfile.ndk-build  # adds cmake/perl, needed once libgit2 is linked
│   └── build-core.sh         # build for the ABIs, then generate the bindings
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
follow. Two functions are exported:

- `scan(dir, options)` — walk a directory, return every task found;
- `scanAgenda(dir, scope, currentDate, timezone, includeDone, options)` —
  walk it and return the agenda for a day, week, month, or the flat task
  list.

`currentDate` is what the agenda treats as today. The caller passes it
rather than letting the library read the clock, so the same files render the
same agenda whenever they are asked for — the contract the CLI follows
through `--current-date`.

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

## Testing

```bash
cd rust && cargo test
```

The extractor has its own suite; these tests cover what this crate adds —
the projection onto the FFI types and the mapping of failures onto the error
enum a Kotlin caller catches.

Running the built library outside Android is not possible: it links against
Android's C library, so `libdl.so` is missing on a desktop Linux host. The
FFI path itself was exercised by generating Python bindings from a host
build and calling through them.

## Why the toolchain lives in a container

The NDK, the Rust Android targets and `cargo-ndk` add up to several hundred
megabytes of build-only tooling. Keeping them in a container image means the
host stays clean and the build is reproducible from `Containerfile.ndk`
rather than from someone's shell history.
