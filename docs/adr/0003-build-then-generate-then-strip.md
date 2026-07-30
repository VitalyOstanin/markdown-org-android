# ADR-0003: Build, then generate the bindings, then strip

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

`uniffi` in library mode reads the interface metadata out of a built library:
the generator is pointed at `libmarkdown_org_ffi.so` and produces the Kotlin
from what it finds there. That metadata lives in the library's symbol table.

Stripping symbols is otherwise wanted — around 0.6 MB per ABI, in an APK that
carries one library per ABI. Two ways of stripping were tried and both broke
the generation:

- `strip = "symbols"` in the release profile removes the metadata before the
  generator ever sees it, and it fails with "No UniFFI metadata found";
- stripping in place before generating has the same effect, and a cached build
  makes it worse: `cargo-ndk` copies out of `target/` only when the artefact
  there is newer, so a stripped copy from an earlier run survives and the
  failure appears without anything having changed.

## Decision

The order in `tools/build-core.sh` is fixed and is the reason the script
exists:

1. remove `rust/jniLibs` from any earlier run;
2. build for the requested ABIs with `--locked`;
3. generate the Kotlin from one of the built libraries — the metadata is
   architecture-agnostic, so any ABI will do;
4. strip with `llvm-strip --strip-all`, which leaves `.dynsym` — the entry
   points the application calls — and removes `.symtab`, where the metadata was.

The release profile sets `strip = "debuginfo"`, never `"symbols"`.
`STRIP=0` keeps the symbols for debugging native code.

## Consequences

- The bindings and the shipped library always come from the same build.
- `cargo` alone does not produce a usable core; the script is the interface.
- A future maintainer changing the release profile has a written reason not to
  set `strip = "symbols"`, and the manifest states it at the line itself.

## References

- `tools/build-core.sh` — the four steps, with the cache caveat.
- `rust/Cargo.toml` — the release profile.
