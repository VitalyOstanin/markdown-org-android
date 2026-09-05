# ADR-0044: The occurrence a move names is written as an inactive timestamp

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-09-05). Amends the written form of
[ADR-0043](0043-a-move-is-a-line-of-the-series.md): what stands before the
arrow is an inactive timestamp rather than a bare date. The bare date is still
read, so files this application has already written keep working. Everything
else ADR-0043 decided — where the line stands, what the target may carry, one
line per occurrence, no identifier on the series — is unchanged.

## Context

ADR-0043 wrote the day being moved bare and the day it moves to bracketed:

````text
`MOVED: 2026-08-20 -> <2026-08-22 Sat 18:00>`
````

The asymmetry was deliberate: a bare date is not a timestamp, so an editor
walking dates cannot nudge the address of the occurrence by accident.

Reading the line showed what that costs. It is written in two notations, which
is what a reader notices first. And the protection is paid for whenever it is
wrong: correcting *which* occurrence moved means typing over text, in a line
where the other date is edited with the controls the application offers for
dates.

The two halves are not the same kind of thing, and the form should say so. The
day before the arrow is an address — which occurrence of the series the line is
about. The timestamp after it is when the occurrence is actually kept. Org
already draws that distinction: an active timestamp feeds the agenda, an
inactive one is a date written down.

## Decision

`move_occurrence` writes the occurrence as an **inactive** timestamp, spelt the
way the series' planning line is:

````text
## TODO English
`SCHEDULED: <2026-08-06 Thu 15:00 +1w>`
`MOVED: [2026-08-20 Thu] -> <2026-08-22 Sat 18:00>`
````

Both halves are timestamps now, and the brackets say which is which: the
address is inactive, the time kept is active. Where the series' planning line
carries no weekday, neither does the address.

A line already in the file is found by the day it names, in either form, and
rewritten in the new one. A move written before this record is therefore
brought onto the new form the next time that occurrence moves, and never
rewritten otherwise: what a file says is what it says.

## Consequences

- The line is written in one notation, and a client that edits dates with keys
  or controls reaches both of them.
- Two written forms are read where one was written before. The reader carries
  the bare date for as long as files hold it, which is indefinitely.
- The extractor refuses an address written active, named to the hour, or
  carrying a repeater or a warning cookie (its ADR-0039); this application
  never writes one, and a hand-written line that does is reported through the
  channel the other exceptions use.
- The core dependency moves to the extractor release that reads the new form.
  An older extractor reads the line as prose and draws the occurrence on the
  day it was moved away from.

## References

- [ADR-0043: A move is a line of the series](0043-a-move-is-a-line-of-the-series.md)
- markdown-org-extract ADR-0039: the occurrence a move names is written as an
  inactive timestamp — the form this record follows
- [ADR-0029: An entry is edited here, a file is not](0029-an-entry-is-edited-here-a-file-is-not.md)
