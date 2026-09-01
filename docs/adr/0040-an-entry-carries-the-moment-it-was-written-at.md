# ADR-0040: An entry carries the moment it was written at, and the caller names it

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-09-01).

## Context

An entry in this format says when it is planned for and, once it is finished,
when it was closed. What it never said is when it came into being. Org-mode has
a convention for that — `org-expiry` writes `CREATED: [2026-09-01 Tue 14:01]`
under the heading — and the extractor has read such a line since its ADR-0014,
which also fixes the brackets: inactive `[...]`, so that no agenda takes the
mark for a date to keep.

Reading it is all that was done. Nothing this application writes ever left the
mark: not the creation screen, not a typed phrase, not a spoken one. The bridge
knew the keyword only well enough to step over it: a `SCHEDULED` added to an
entry that already carried a `CREATED` line joins the block of keyword lines
below it instead of splitting it.

So an entry written here could not answer when it was added. Two things want
that answer: a note read months later, where "when did this appear" is the
difference between a task deferred and a task just thought of; and a phrase
read wrong, where when the entry was written is what identifies the entries
worth re-reading.

The editor extension had the same gap on its side, with a manual
`Insert Created Timestamp` command and nothing automatic.

## Decision

Every entry this application writes carries the moment it was written at,
marked under the heading the way `org-expiry` marks it and in the inactive
brackets ADR-0014 fixed. The mark is not a setting and not a choice on the
creation screen: an entry that came into being has a moment it came into being
at, and one written without the mark could not gain a true one afterwards.

To the minute rather than to the day. A day alone cannot tell apart two entries
written the same day, which is exactly the pair a reader wants ordered when
looking at what was captured and in what order. The hour is written even where
the file spells its planning dates without one: a planning date carries an hour
only where the entry is held at a time, and this mark always has one to carry.

The line stands above the planning line. That is the order `org-expiry` writes
them in, and the order the bridge already steps over them in when a planning
date is added to an entry that carries a mark: what the entry is, then when it
is meant to happen.

It is spelled the way the file spells the dates it already holds. The sample is
the one a planning line follows (`sample_planning`): a file writing its dates
bare gets a bare line, a file writing them in Russian gets a Russian weekday, a
file carrying no weekdays gets none. A note written one way does not acquire a
second way through an entry added on the phone.

The moment comes from the caller rather than from a clock read inside the
bridge, as every other date in this crate does — `complete` takes today as an
argument for the same reason. The same call has to write the same file, a test
has to be able to name the moment, and a screen left open over midnight marks
the moment its own clock holds rather than one the reader never saw.

A creation moment that is not one is refused with the file exactly as it was,
like every other value the bridge reads before opening the file. A bare date is
among them: the mark carries the minute, so `YYYY-MM-DD` alone is not a value
this takes.

## Consequences

Every entry written on the phone gains a line. A file that held only headings
and planning lines now holds a third kind, and a reader comparing an old entry
with a new one sees the difference — the mark is not written retroactively, and
entries created before this release have none. Nothing reads the absence as an
error: an entry without the mark is an entry whose creation day is unknown.

The editor extension writes the mark for entries created from a phrase
(its ADR-0024), so a note written on either side reads alike. Its manual
`Insert Created Timestamp` stays what it was, for entries typed into the editor
by hand — which this application has no counterpart for.

The mark is one more line between the heading and the planning line on screen,
where the agenda shows neither: what a row shows is the heading and the
planning date, and the mark is read in the file.

## References

- [ADR-0036: Where an entry is written is the collection's to say](0036-where-an-entry-is-written-is-the-collections-to-say.md)
- [ADR-0038: A phrase fills the screen and the core reads it](0038-a-phrase-fills-the-screen-and-the-core-reads-it.md)
- [Extractor ADR-0014: Active and inactive timestamps](https://github.com/VitalyOstanin/markdown-org-extract/blob/master/docs/adr/0014-active-and-inactive-timestamps.md)
- [Editor ADR-0024: An entry carries the moment it was written at](https://github.com/VitalyOstanin/markdown-org-vscode/blob/master/docs/adr/0024-an-entry-carries-the-moment-it-was-written-at.md)
