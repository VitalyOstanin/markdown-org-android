# ADR-0031: Every edit carries what it takes to undo it

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-19).

## Context

A group action has been undoable since it existed: it hands back what each file
held before and after, and the undo writes the first back only where the file
still holds the second. The reasoning was that a move over twenty notes is not
something to be sure about in advance.

Single edits had none of that, and by now there are eight of them on one sheet
— done, three keywords, a priority, two shifts, a date from a calendar, and the
text of the entry itself. They sit a finger's width apart, they are made on a
phone, and every one of them writes to a file in a repository that is shared
between devices. Taking a wrong tap back meant opening the note in another
application, finding the line, and remembering what it said.

Two of them lose something that is not otherwise recoverable. Clearing a date
takes the whole line out, with its time, its repeater and the weekday in the
language the note was written in; rewriting the entry replaces the body. Both
went through git, so the text is not gone — but reaching it means a checkout on
another machine.

The application had no undo stack, and building one is a different question
from this one: a stack is state that has to survive the process, agree with the
files after a sync, and be explained to the user. What the group action already
had is smaller than that and covers the tap just made, which is the one that
gets taken back.

## Decision

Every edit hands back the pair the group action hands back: the note as it
stood before, and as the edit left it. The pair lives on the outcome of the
edit (`EditOutcome::rollback`, `CompleteOutcome::rollback`), and it is `None`
exactly where nothing was written.

The undo is one function for both — `revert_files(dir, rollback)` — because a
group is several files and one tap is one, which is a difference in how many
rather than in kind. It restores a file only while that file still holds what
was written into it, and names the ones it left alone. The snapshot pair and
the restore moved out of the group's module into `undo.rs` with it.

One tap's undo is offered where the group's is: the line at the foot of the
screen, for as long as it stands. It commits, like every other edit here, and
the commit names the task — the history reads as a move and its reversal rather
than as a note changing twice for no stated reason.

The offer is dropped as soon as another edit is made. It describes one state of
one note, and an offer left standing over a later edit would put the note back
past a change nobody asked to lose.

## Consequences

Any single edit can be taken back with one press, including the two that lose
text: the note goes back to the bytes it held, so a cleared `SCHEDULED` line
returns with its Russian weekday, its time and its repeater intact.

An undo cannot take away work done after the edit. A note a sync landed on, or
one edited in another application, is left as it stands and the screen says so
— which is also why the offer cannot be relied on as a general undo: it is the
last tap, and only while the note has not moved on.

Every write now reads the file as text twice, before and after, and holds both
in memory until the offer is dropped. For a note of the size the editor already
refuses to open — the threshold is 20 000 characters — that is a few hundred
kilobytes; for a note the size of a whole file of notes it is proportional to
the file. Accepted: the alternative is a diff, which is a second grammar to
keep and to get right, over an edit that touches one line.

Undoing is itself an edit and produces its own commit, so the history carries
both. Nothing undoes an undo: the note is back where it was, and the way
forward is to make the edit again.

## References

- [ADR-0014: The notes are held between calls, and an edit names the file it changed](0014-the-notes-are-held-between-calls.md)
- [ADR-0029: An entry is edited here, a file is not](0029-an-entry-is-edited-here-a-file-is-not.md)
- [ADR-0030: A date written from nothing follows the file it lands in](0030-a-date-written-from-nothing-follows-the-file.md)
