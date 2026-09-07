# ADR-0046: Both halves of a move carry a weekday

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-09-07). Amends the spelling decided by
[ADR-0044](0044-the-occurrence-a-move-names-is-a-timestamp.md): a `MOVED` line
carries a weekday on both of its days even where the series' planning line
names none, which that record decided against. Everything else ADR-0044
settled — the address written as an inactive timestamp, the bare date still
read, a line found by the day it names and rewritten in the new form — is
unchanged.

## Context

ADR-0044 spelt the address after the series: the indentation of the planning
line, and its weekday where it writes one. A series written `<2026-08-06
15:00 +1w>` therefore produced `MOVED: [2026-08-20] -> <2026-08-22 15:00>`.

What a move says is that one occurrence of a series is held on another day, and
the day it is held on is a day the reader chose. A move is written in one
place — the note — and read in three: by this application, by the extension,
and by whoever opens the file. For the last of those a date in digits alone
says nothing about the step: `[2026-08-20] -> <2026-08-22>` and
`[2026-08-20] -> <2026-08-29>` look equally plausible, and a move that landed
on the wrong day of the week is not visible until the agenda is opened.

The weekday is what makes it visible, and it costs nothing to write: the
extractor reads it in whatever language and length the file uses, and ignores
it when the date and the weekday disagree — the date is what a day is.

Where the note names no weekday anywhere, one has to be picked. English is
what the extractor itself falls back to, and the date beside it names the day
in any case.

## Decision

Both halves of a `MOVED` line carry a weekday, whatever the series' planning
line writes:

````text
## TODO English
`SCHEDULED: <2026-08-06 15:00 +1w>`
`MOVED: [2026-08-20 Thu] -> <2026-08-22 Sat 15:00>`
````

Where the spelling comes from, in order:

1. the weekday of the series' own planning line, written as that line writes
   it;
2. failing that, the first weekday written anywhere in the note, in that
   language and length;
3. failing that, English.

The rest of the line is spelt as before: the indentation and the framing of
the dated lines the file already holds, the address inactive and the day it is
held on active.

## Consequences

- A move written into a note of bare dates gains a weekday the note does not
  otherwise use. The date beside it names the day, so nothing depends on the
  choice; a reader who dislikes it edits the line.
- The two clients write the same line for the same move, which is what keeps a
  note edited on the phone and on the desktop from drifting apart in spelling.
- A planning line written into such a note still goes without a weekday: the
  rule is about the move, where the step is what has to be readable, and not
  about every dated line.
- A weekday that disagrees with its date is ignored by the extractor, so a
  wrong guess cannot move an occurrence anywhere.

## References

- [ADR-0043: A move is a line of the series](0043-a-move-is-a-line-of-the-series.md)
- [ADR-0044: The occurrence a move names is written as an inactive timestamp](0044-the-occurrence-a-move-names-is-a-timestamp.md)
- markdown-org-extract ADR-0039: the occurrence a move names is written as an
  inactive timestamp
