# ADR-0002: The core's types are projected at the boundary, not re-exported

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

With the extractor linked in ([ADR-0001](0001-extractor-in-process-over-uniffi.md)),
its Rust types could in principle be handed to `uniffi` directly. They are
shaped for parsing, not for a screen: nested structures, types `uniffi` cannot
carry, and fields the interface never reads.

Re-exporting them would also make every internal change of the extractor a
change of this application's Kotlin surface, for no benefit to either side.

## Decision

`rust/markdown-org-ffi` declares its own records — `Task`, `Options`,
`AgendaResult`, `TimestampType` and the error enums — and converts from the
extractor's types in one place. The projection is flat: what the interface
needs, in shapes `uniffi` can carry.

A value that the interface has to reason about is a variant rather than a
string. `TimestampType` is the worked example: the extractor states the kind of
a timestamp as text, and against text the Kotlin compiler had nothing to check.

## Consequences

- The extractor stays free to evolve its internals; the conversion in this
  crate is the only place that follows.
- Every field the interface needs has to be added in two places — the record
  here and the conversion — which is the cost of the isolation.
- Kotlin gets exhaustive `when` over the kinds that cross the boundary, and an
  unknown value maps to absence rather than to a crash.

## References

- `rust/markdown-org-ffi/src/lib.rs` — the records and the conversion.
- `rust/markdown-org-ffi/tests/ffi_surface.rs` — tests written against the
  projected surface.
