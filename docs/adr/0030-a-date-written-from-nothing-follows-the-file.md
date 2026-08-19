# ADR-0030: A date written from nothing follows the file it lands in

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-19).

## Context

Until now the agenda could move a planning date but not give one. A task that
arrived from the notes without `SCHEDULED:` or `DEADLINE:` had no date to shift,
and a date cleared elsewhere could not be put back from the phone — the way out
of the agenda was one-directional, and the only way back in was another editor.

Every date this application had written so far was a rewrite: the line existed,
and only the day inside it changed. Writing the line itself is a different
question, because a planning line has a spelling, and these notes do not agree
on one. They are markdown rather than org files, so the timestamp is usually
framed in inline code (`` `SCHEDULED: <2026-08-19 Wed>` ``) to keep a renderer
away from it; the weekday is written in the language of the note, in full or
abbreviated; some notes carry no weekday at all, some indent the line under a
list item. A house style imposed here would show up as a line unlike its
neighbours in somebody's file.

The reverse direction has a hazard of its own. A keyword is recognised at the
start of a line, so a line a manual edit left holding both — `SCHEDULED: <...>
DEADLINE: <...>` — reads as the first one and hides the second. Removing "the
line with the SCHEDULED on it" would take the deadline with it.

## Decision

One operation writes and clears: `set_planning(target, keyword, date)`, with
`null` for the day taking the line out. Setting and clearing are one question on
screen — which day this is planned for, if any — and one call in the core, so a
date cleared and set again lands where the first one was.

A line that already exists is only rewritten in its date: the time, the
repeater and the warning cookie stay as written, which is what a shift already
did.

A line written from nothing is spelled after a sample: the entry's own other
planning line first, then the first one anywhere in the file. From it come the
indentation, the inline-code framing, and whether a weekday is written and in
what language and length. A file with no planning line at all leaves nothing to
follow, and the canonical form is written — framed, with an abbreviated English
weekday. The new line joins the block of keyword lines under the heading rather
than splitting it.

Clearing removes only a line that carries this keyword, its timestamp and
nothing else. A line holding more than that is refused, and says so.

The calendar the day is picked in takes its first weekday from the setting the
month grid already uses, carried in the locale through the Unicode `fw`
keyword, and checked rather than trusted: a platform that ignores the keyword
leaves the phone's own locale in place rather than a third answer.

## Consequences

A date can be given, moved and taken away without leaving the agenda, and the
notes keep looking like themselves — a Russian note keeps Russian weekdays, a
note without weekdays gains no weekday.

The spelling follows a sample, so the first date written into a file that has
none sets the pattern the rest of that file will follow. That is the canonical
form, which is what the extension writes too.

A line a manual edit left carrying two keywords is not editable here at all —
neither shifted, which was already so, nor cleared. It is left to be edited by
hand rather than half-cut.

The calendar's first weekday depends on the platform honouring an extension of
the locale. Where it does not, the calendar is cut the way the phone's locale
cuts it while the agenda's own grid follows the setting — a visible difference,
and the honest one: the alternative is a calendar in another language.

## References

- [ADR-0014: The notes are held between calls, and an edit names the file it changed](0014-the-notes-are-held-between-calls.md)
- [ADR-0029: An entry is edited here, a file is not](0029-an-entry-is-edited-here-a-file-is-not.md)
