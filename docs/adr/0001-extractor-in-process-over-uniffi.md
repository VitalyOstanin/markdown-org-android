# ADR-0001: The extractor runs in-process over UniFFI, not as a subprocess

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

The agenda this application draws is produced by
[`markdown-org-extract`](https://github.com/VitalyOstanin/markdown-org-extract),
a Rust project that also has a command-line interface. The existing consumer,
the [`markdown-org-vscode`](https://github.com/VitalyOstanin/markdown-org-vscode)
extension, spawns that binary and reads its JSON.

An Android application cannot do the same. The platform ships no directory
that is both writable by the application and executable, so a bundled binary
has nowhere to run from; where a vendor does allow it, the practice differs
across vendors and API levels and cannot be relied on.

The alternative to spawning is a second implementation of the parsing in
Kotlin. That was rejected outright: the grammar of timestamps, repeaters and
planning lines is where the value of the extractor sits, and two
implementations of it drift — which is exactly what the cross-project review
found between the extension and the core for repeat semantics.

## Decision

The extractor is linked into the application as a Rust library and called
in-process. `rust/markdown-org-ffi` wraps it, `uniffi` generates the Kotlin
surface from that wrapper, and the result ships in the APK as
`libmarkdown_org_ffi.so` plus generated Kotlin.

The extractor was reshaped for this: it became a library crate with a thin CLI
over it (`markdown-org-extract` ADR-0025), so both consumers call the same
code.

## Consequences

- No serialise/parse round trip: the scan hands back structures the interface
  renders directly.
- No process to spawn, and nothing that depends on a vendor allowing execution
  from application storage.
- The build grows a cross-compilation step per ABI, and the APK carries a
  native library per ABI.
- The FFI boundary is now a compatibility surface of its own — see
  [ADR-0002](0002-project-core-types-at-the-boundary.md).
- A panic in Rust crossing the boundary must unwind rather than abort, which
  is why the release profile does not set `panic = "abort"`.

## References

- `rust/markdown-org-ffi/src/lib.rs` — the module documentation states the same
  reasoning at the point of use.
- `rust/Cargo.toml` — the release profile, and why it keeps unwinding.
- [`markdown-org-extract` ADR-0025](https://github.com/VitalyOstanin/markdown-org-extract/blob/master/docs/adr/0025-library-crate-with-thin-cli.md)
  — the library surface this decision asked for.
