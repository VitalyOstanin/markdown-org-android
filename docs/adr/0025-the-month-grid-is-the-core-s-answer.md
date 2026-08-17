# ADR-0025: The month grid is the core's answer, and the phone says where a week starts

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-18).

## Context

The month calendar was laid out on the device. The core was asked for the
month — `Scope.MONTH`, the days between the 1st and the last — and
`buildMonthGrid` then filled that month out to whole weeks, borrowing the
leading days from the month before and the trailing ones from the month after.

Those borrowed cells stood for dates the answer said nothing about. A task
scheduled on the 30th of the previous month is in that month's agenda, so the
cell showing the 30th was drawn empty, its count denied work the day view
showed as soon as the cell was tapped. A month ending mid-week borrows up to
six days at each end, so the effect is not a corner case.

Where those weeks are cut is also not the phone's rule to invent. The core
groups a week from a weekday when it builds the week span, and a grid cut on a
different boundary would put the month into different weeks than the week span
steps through. Two implementations of one rule, in two languages.

The core answers the question directly since 0.17.0: `AgendaScope::MonthGrid`
returns the whole weeks the anchor month touches, beginning on the day
`week_start` names, borrowed days carrying their tasks (extract ADR-0028 and
ADR-0030). It reads no locale of its own and falls back to Monday,
deliberately: the same notes must render the same agenda wherever they are
read. The one answer it refuses for a grid is `today` — a week anchored on the
day being rendered leaves the columns undefined.

## Decision

The calendar asks for `Scope.MONTH_GRID` and lays out the days it receives, in
the order they arrive. `buildMonthGrid` reads each day's date for what the
answer does not carry — whether the date falls outside the anchor month,
whether it is a weekend, whether it is today — and computes no dates of its
own.

The weekday a week begins on is the phone's answer, not the core's default. It
is a stored setting (`A week begins on`: the phone's own, Monday, Sunday)
whose own default is the system locale, and it is passed to every span drawn
in weeks — the week span as well as the grid.

The list reading of a month keeps asking for `Scope.MONTH`: a list of the days
either side of the month would be answering about months nobody asked for.
Switching between the two readings therefore costs a scan, which it did not
before.

## Consequences

- A day at the edge of the calendar shows what is dated to it, and its cell
  agrees with the day it opens.
- The rule "which weeks does this month touch" has one implementation, in the
  core, shared with the editor extension.
- Two scans that did not happen before: switching the month between calendar
  and list, and changing where a week starts while a week or a calendar is on
  screen. Both are filtering over notes already held by `NotesIndex`, not a
  walk of the directory.
- The setting is one more thing to keep: the phone's own answer is right for
  most readers, and the two fixed values exist for the reader whose habit and
  whose locale disagree.
- The core is pinned at 0.17.0 or later. An older one has no grid scope at all.

## References

- `app/src/main/kotlin/.../ui/MonthGrid.kt` — the layout.
- `app/src/main/kotlin/.../ui/AgendaViewModel.kt` — which scope is asked for,
  and the stored week start.
- `app/src/main/kotlin/.../ui/WeekStart.kt`, `core/UiSettings.kt` — the
  setting and where it is kept.
- `rust/markdown-org-ffi/src/lib.rs` — `Scope::MonthGrid` and the `week_start`
  argument across the boundary.
- markdown-org-extract ADR-0028 (the first day of the week and the grid),
  ADR-0029 (the occurrence after the rendered day) and ADR-0030 (an explicit
  window in the month grid).
