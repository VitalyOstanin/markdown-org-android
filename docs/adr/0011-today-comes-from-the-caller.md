# ADR-0011: The core is told what "today" is; it never reads the clock

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

An agenda is relative to a day: what is overdue, what is due today, what has
slipped. If the code producing it reads the system clock, the same input
produces different output over time, and a test of it either freezes the clock
globally or is written against whatever day it runs on.

The ecosystem already settled this on the other side: the CLI takes
`--current-date`, and `markdown-org-vscode` ADR-0015 records that the consumer
pins the day. The FFI surface had no matching record.

## Decision

`scan_agenda` takes `current_date` as a `YYYY-MM-DD` string, and the core uses
it as today. Nothing below the boundary calls a clock. Under `Scope::Tasks`,
which has no date window, the value is ignored — and dropped explicitly rather
than forwarded, because the extractor rejects a date argument under a scope
that has none.

The application reads `LocalDate.now()` at the point it builds the agenda and
passes it down, the same as the marker line on the hour axis takes
`LocalTime.now()` as an argument rather than reading it inside the projection.

## Consequences

- The same directory and the same date always render the same agenda.
- Tests state the day they are about, with no global clock to freeze.
- The day boundary is the caller's business, including which timezone it is in
  — the timezone is a separate argument for exactly that reason.

## References

- `rust/markdown-org-ffi/src/lib.rs` — `scan_agenda` and its documentation.
- `app/src/main/kotlin/…/ui/AgendaViewModel.kt` — where the clock is read.
- [`markdown-org-vscode` ADR-0015](https://github.com/VitalyOstanin/markdown-org-vscode/blob/master/docs/adr/0015-pin-today-with-current-date.md)
  — the consumer side of the same contract.
