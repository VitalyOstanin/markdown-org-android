# ADR-0009: Completing a repeating task follows upstream Org-mode

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

A task can carry a repeater: `SCHEDULED: <2026-07-30 ++7d>`. Marking such a
task done does not mean it is finished — it means this occurrence is, and the
next one is due later. Emacs Org-mode implements this in
`org-auto-repeat-maybe` (lisp/org.el), and the three repeater forms behave
differently: `+N` takes exactly one step even when the result is still in the
past, `++N` keeps stepping until it passes today, `.+N` restarts from today.

An interface that treated "done" as a plain keyword change would leave a
repeating task with a date in the past and a `DONE` keyword — a state Org-mode
never produces, and one the agenda would keep showing as overdue.

## Decision

Completing a task is one operation in the core (`complete_task`), not a status
change the interface composes. It rewrites the planning lines according to the
repeater and leaves the keyword open; a task without a repeater simply becomes
`DONE`. The rules were read from upstream rather than recalled.

Two divergences from upstream are deliberate, both in the direction of leaving
the user's file alone:

- upstream deletes a `SCHEDULED` line that carries no repeater when the task
  repeats elsewhere; this application keeps it;
- upstream also shifts plain timestamps in the task's body; this application
  moves only the planning lines under the heading.

The VS Code extension of the same ecosystem does not do this: its
`markdown-org.setDone` rewrites the keyword only. The same repeating task
therefore ends in different states depending on which client closed it. The
asymmetry is recorded here rather than left to be read as a defect; closing it
means calling the shared repeater code from the extension.

## Consequences

- A repeating task closed on the phone behaves as it does in Emacs.
- Completion cannot be expressed as "set the status"; the interface calls a
  distinct operation, and the FFI surface carries it.
- The ecosystem is inconsistent until the extension adopts the same rule, and
  that inconsistency is documented in both projects rather than in neither.

## References

- `rust/markdown-org-ffi/src/planning.rs` — the module documentation states the
  three forms and the two divergences.
- [`markdown-org-vscode`](https://github.com/VitalyOstanin/markdown-org-vscode)
  — `src/commands/taskStatus.ts`, the keyword-only behaviour.
