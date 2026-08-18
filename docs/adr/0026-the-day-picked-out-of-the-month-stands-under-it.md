# ADR-0026: The day picked out of the month stands under the grid

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-18).

## Context

The weeks of the calendar shared whatever height was left below the header, so
six of them divided about 590 dp on a phone held upright: a cell 44 dp wide and
92 dp tall. What a cell holds — the day number and the count under it — is
about 46 dp, so half of every cell was empty ground inside a rounded outline,
and the grid read as a wall of tall boxes rather than as a month.

Squaring the cell answers that and asks the next question: what the freed third
of the screen is for. Left empty it reads as a screen that failed to finish
drawing.

The cell itself had grown loud in a second way. Forty-two outlines are the
first thing the eye finds on a grid whose subject is the numbers inside them,
today was a filled tile rather than a date, and the count of a day already
behind the reader took the deadline tone at full strength — on a month half
gone by, twenty glowing chips.

The rows of every day are already on the phone: `Scope.MONTH_GRID` answers with
the tasks of each day it lists, and the grid uses only their number. Showing
what a day holds therefore costs no scan and no call — only the room to draw
it in.

## Decision

The grid takes the height its cells want (`Sizes.monthCell`, 56 dp) and no
more, and what is left below it is a panel showing the day picked out of the
grid: its date, its count, its rows, and a button that opens it in the Day
view. Rows come from the answer the grid was drawn from and follow the same
rule as the count — what is dated to that day, arrears and warnings excluded,
so the panel and the chip above it cannot disagree.

A tap on a cell picks its day rather than opening it. The pick is remembered
across a rotation and forgotten when the reader pages to another month, and
until something is picked the panel shows today.

A window with less than `Sizes.monthPanelMin` (132 dp) left over — a phone on
its side — leaves the panel out entirely and gives the grid the height. A panel
showing one row of a day is not the day, and the whole month on screen is what
the calendar is for.

The cell draws no outline of its own. It takes a ground only where it has
something to say: today is a disc under the number, the picked day a tint of
the whole cell, a weekend a tint fainter still. The count of a day in arrears
takes the deadline role's container rather than its tone.

## Consequences

- The calendar answers what a day holds without leaving the month, which is
  what the reader was looking at the shape of the month to find.
- Opening a day costs one more tap than it did. That tap is what makes the
  first one free: picking a day used to mean leaving the screen and coming
  back.
- The grid is quieter: no outlines, one mark per state, and a month half gone
  by no longer glows.
- A landscape phone loses nothing it had — it never had the room for a panel —
  but the two layouts of the same screen now differ in more than cell height.
- `Sizes` gains a fixed cell height, a floor for the panel and a gap inside the
  cell. The grid stops being elastic, so a very tall window leaves the panel
  taller rather than the cells.

## References

- `app/src/main/kotlin/.../ui/AgendaMonthLayout.kt` — the grid, the panel and
  the cell.
- `app/src/main/kotlin/.../ui/theme/Dimens.kt` — `monthCell`, `monthPanelMin`,
  `monthHeaderRow`, `monthTodayDisc`, `monthCellGap`.
- `app/src/androidTest/kotlin/.../ui/AgendaMonthTest.kt` — what a tap does and
  what a short window drops.
- ADR-0025 — where the days of the grid come from, which this record does not
  change.
