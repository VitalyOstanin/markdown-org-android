# ADR-0012: Rust edition 2021, and targetSdk one below compileSdk

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

Two settings in this project are not the newest available, and both look like
oversights until the reason is written down.

**Rust edition.** The workspace declares `edition = "2021"` while the minimum
supported toolchain is 1.91; edition 2024 has been available since 1.85, so the
edition could be raised without moving the MSRV. The MSRV itself is not a
choice here: it is the oldest toolchain that compiles the workspace with the
locked dependencies, and CI checks it by building on exactly that version.

**SDK levels.** `compileSdk = 37` with `targetSdk = 36`. The two mean different
things: `compileSdk` is which API the code is compiled against, `targetSdk` is
which behaviour changes the application opts into at runtime. The newer
`compileSdk` is forced by a dependency — `androidx.lifecycle` 2.11 requires it —
and is not a statement about the platform the application was tested on.

## Decision

Edition stays 2021 for now. Edition 2024 changes rules the core is exposed to —
`unsafe_op_in_unsafe_fn` among them, and this crate calls into libgit2 through
`unsafe` blocks — so the move is a change with its own verification, not a
line in a manifest. It is worth doing on its own, not as a side effect of an
unrelated edit.

`targetSdk` follows the platform the application is actually exercised
against, and rises deliberately after the behaviour changes of a release have
been looked at — not automatically with whatever `compileSdk` a dependency
demands.

## Consequences

- A reader comparing the two numbers finds a reason rather than guessing.
- The edition move stays on the list as work of its own.
- Raising `targetSdk` remains a decision with a check behind it; a dependency
  bump cannot make it silently.

## References

- `rust/Cargo.toml` — the edition and the MSRV, with the note that the MSRV
  follows the lock file.
- `app/build.gradle.kts` — the two SDK levels and the dependency behind the
  higher one.
- `tools/Containerfile.sdk` — the same pairing on the image side.
