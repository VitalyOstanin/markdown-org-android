# ADR-0033: An occurrence is cancelled in place and moved by an entry of its own

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-21).

## Context

A repeating timestamp describes an endless series and has nowhere to say that
one of its occurrences is different. The class held at three on Thursdays is
at six this week; the one after that is off. Editing the planning line moves
every occurrence from now on, and completing the task moves the series forward
— neither says anything about one date.

Org-mode has no answer to this. Its repeaters, its warning period and
`org-clone-subtree-with-time-shift` were read for one, and the nearest thing
upstream offers is materialising copies of the entry. iCalendar has an answer
that calendars have interoperated on for twenty years, and the extractor
adopted it in its ADR-0031: `EXDATE` for an occurrence that is gone,
`RECURRENCE-ID` for one that moved, written with the `org-properties` keys of
its ADR-0020. What this application needs is the writing side of that, and the
two questions it raises here: who invents the identifier a series is named by,
and where the entry that replaces an occurrence is written.

## Decision

Two operations on the boundary, beside the ones that edit a task:

`cancel_occurrence` adds the date to the series' own `EXDATE`, in the property
block under its planning lines — made where the entry has none. The series is
not otherwise touched; it goes on repeating and the agenda leaves out the one
day.

`move_occurrence` writes a second entry, at the end of the file the series
lives in, carrying `SERIES_ID` and `RECURRENCE_ID`. Nothing is excluded as
well: a replacement suppresses the occurrence it names, which is the split RFC
5545 makes between an occurrence that is gone and one that moved. The series
gains an `ID` if it has none.

The identifier a series is given comes from the caller, for the reason "today"
does (ADR-0011): a value drawn here would make the same call write a different
file each time, and no test could say what the file holds.

The replacement is the series' entry held once at another time, and is written
as such. Its heading is the series' heading as it stands — level, keyword and
priority cookie included — and its planning line is the series' own line
rewritten token by token: the same keyword, indentation, inline-code framing
and weekday language, with the date and the time changed and the repeater
taken off. A warning cookie stays: a deadline moved is still warned about the
same number of days ahead.

An entry repeating on two dates at once — a `SCHEDULED` and a `DEADLINE` that
both carry a repeater — is refused rather than guessed at. Which of the two an
occurrence is counted by decides what the replacement carries, and a wrong
guess writes a wrong date into somebody's notes.

The property block under the planning lines is not the body of the entry. The
entry editor (ADR-0029) steps over it, so what an action wrote is not handed
back as text to be retyped, and one keystroke in the editor cannot undo an
action. A block written further down, among the text, is text: it was put
there by hand and goes back the way it came.

## Consequences

An occurrence can be moved without breaking the series, and cancelled without
touching it — from the agenda, as a tap, with the same undo every other edit
carries (ADR-0031).

Two entries now describe one series, and both are ordinary entries. The
replacement can be edited, dated, completed and undone like any other, which
is also how an occurrence moved twice is moved again: the second move is an
ordinary date edit of the entry now standing on that day.

The suppression is the extractor's to apply, and it reaches as far as one scan
does. Until the version pinned here is one that carries its ADR-0031, a moved
occurrence stands on the agenda twice — once from the series, once from the
entry replacing it. Bumping the pin is what closes that.

Cancelling a date the series does not fall on is not refused. Whether a date is
an occurrence is the repeater's answer, and the caller is the agenda, asking
about a day it drew the series on; a date that is not one leaves an `EXDATE`
that suppresses nothing.

Only the file being written to is looked at for an occurrence already replaced.
A replacement in another note is out of reach of an operation that opens one
file, and the second entry it would leave is visible on the agenda rather than
silent.

## References

- [ADR-0011: The core never reads a clock](0011-today-comes-from-the-caller.md)
- [ADR-0029: An entry is edited here, a file is not](0029-an-entry-is-edited-here-a-file-is-not.md)
- [ADR-0030: A date written from nothing follows the file it lands in](0030-a-date-written-from-nothing-follows-the-file.md)
- [ADR-0031: Every edit carries what it takes to undo it](0031-every-edit-carries-what-it-takes-to-undo-it.md)
- [ADR-0032: A new task goes to a file the collection names](0032-a-new-task-goes-to-a-file-the-collection-names.md)
- RFC 5545, sections 3.8.5.1 (`EXDATE`) and 3.8.4.4 (`RECURRENCE-ID`)
