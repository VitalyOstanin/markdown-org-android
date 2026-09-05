# ADR-0043: A move is a line of the series

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-09-05). Supersedes the move half of
[ADR-0033](0033-an-occurrence-is-cancelled-in-place-and-moved-by-an-entry-of-its-own.md)
— the entry carrying `SERIES_ID` and `RECURRENCE_ID` — for what this
application writes; that shape is still read, and files already carrying it
keep working. The `EXDATE` half of ADR-0033 is untouched: an occurrence that is
gone is still cancelled the way it was.

Amended by
[ADR-0044](0044-the-occurrence-a-move-names-is-a-timestamp.md) (2026-09-05):
the occurrence before the arrow is written as an inactive timestamp rather
than a bare date; the bare date is still read.

## Context

ADR-0033 wrote a moved occurrence the way iCalendar does: a second entry at the
end of the file, carrying the `ID` of the series and the start the occurrence
would have had. Using it on real notes showed what it costs the reader, and the
cost is not in the model but in the file:

- The entry is written at the end, which is where two devices can both append
  without conflicting. A reader looking at the series sees no sign that one of
  its occurrences moved; the answer is thousands of lines away.
- Its heading is copied from the series, and a heading copied to the end of a
  file lands under a different parent. Nothing reads headings for hierarchy in
  these notes, but a person does, and to a person it now says something false.
- Two entries with the same title, one of them a stub of the other, is what the
  reader has to keep in mind when editing either.

The extractor answered this in its ADR-0038, and this application writes the
files it reads.

## Decision

`move_occurrence` writes a line of the series entry:

````text
## TODO English
`SCHEDULED: <2026-08-06 Thu 15:00 +1w>`
`MOVED: 2026-08-20 -> <2026-08-22 Sat 18:00>`
````

Before the arrow is the occurrence, as `YYYY-MM-DD`. After it is where the
occurrence is held instead, as an active timestamp, which may name a weekday,
an hour and a range of hours. It carries neither a repeater nor a warning
cookie: one occurrence does not repeat, and how far ahead a deadline warns
belongs to the series. The line is spelt from the series' own planning line —
its indentation, its weekday language — and framed as inline code, the way
every timestamp in these notes is.

The line goes under the last dated line of the entry, so that the dates of one
entry stay together, and it is a keyword line rather than body: the entry
editor (ADR-0029) steps over it, for the reason it steps over a planning line
and the property block.

The series is not touched and gains no `ID`. The pair that needed one —
`SERIES_ID` matching an `ID` — existed to point across entries, and a line
inside the entry points at nothing. The caller therefore no longer passes an
identifier, and ADR-0011's question of who invents one does not arise for a
move.

An occurrence moved a second time rewrites what already stands for it: the
`MOVED` line where there is one, and — where the file still holds the older
shape — the planning line of the entry standing in for that occurrence, where
it stands. Two answers for one day are a file with no answer at all.

## Consequences

The reader sees a moved occurrence where the series is. Nothing is appended to
the end of the file, and no heading is copied to a place it does not belong to.

A moved occurrence loses what a separate entry gave it: its own state, body,
priority and clocks. Marking one occurrence done, or writing a note under it,
is no longer expressible — that is the trade this decision makes, and the
reason ADR-0033's shape is still read rather than dropped. Moving an occurrence
again is no longer an ordinary date edit of a second entry either; it is the
move operation, applied twice.

The agenda draws the occurrence on the day it went to only from the version of
the extractor that carries its ADR-0038. Until the pin is bumped, a move is
written correctly and shows up nowhere.

## References

- [ADR-0011: The core never reads a clock](0011-today-comes-from-the-caller.md)
- [ADR-0029: An entry is edited here, a file is not](0029-an-entry-is-edited-here-a-file-is-not.md)
- [ADR-0033: An occurrence is cancelled in place and moved by an entry of its own](0033-an-occurrence-is-cancelled-in-place-and-moved-by-an-entry-of-its-own.md)
- RFC 5545, section 3.8.4.4 (`RECURRENCE-ID`) — the model the older shape speaks
