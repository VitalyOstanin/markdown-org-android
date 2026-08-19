# ADR-0029: An entry is edited here, a file is not

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-19).

## Context

[ADR-0028](0028-a-note-is-handed-to-an-editor-rather-than-opened-here.md) sends
a whole note to an editor of the user's choosing, and that stands. What it does
not answer is the smallest edit of all: a title with a typo in it, or a line of
context to add under a task. Handing the file to another application for that
means leaving this one, finding the place, editing, coming back, and waiting
for a sync to notice — for a change of two words.

Every other edit here rewrites exactly one line, and the reason is in the file
rather than in the interface: the notes are a git checkout merged line by line,
and an edit that reaches past what was asked for turns a merge that would have
been automatic into a conflict.

Two measurements bound what a field on this screen can hold. A text field of
Compose hands its content to the platform's autofill service on every change,
over a transaction bounded at about a megabyte: a run put the failure between
483 and 617 KB, and at 676 KB it took the application down. The same run put a
keystroke at 6 seconds for that size, at about a second for 134 KB, and at a
third of a second for 25 KB. A field holding a whole file is therefore not slow
but unusable, whatever is done about the transaction.

## Decision

The sheet opens a screen for the text of one entry: the title of its heading,
and the lines under it. The core reads and writes both in one call
(`read_entry`, `set_entry`), in one write and one commit.

What the screen does not touch is what an action writes: the keyword, the
priority cookie, the planning lines and the closing date. A title that would
read as a keyword and a body line that would read as a planning line or as
another heading are refused by the core rather than written.

The body is what stands after the last planning line of the entry and before
the next heading, with the blank separators at either end left to the file. An
entry read and written back unchanged leaves the file byte for byte as it was,
including its line endings.

An entry whose body is longer than 20 000 characters is not opened here; it is
sent to ADR-0028's editor instead. Below that, the field declares itself
outside autofill — `ContentDataType.None` and an unchanging `FillableData` —
because what bounds the size is the user's file rather than this interface.

## Consequences

A typo is fixed where it is read, and a note can be given a line of context
without leaving the agenda. The edit is one entry wide, so two people editing
two entries of one file still merge automatically.

The bound is stated rather than discovered: an entry above the limit says why
it did not open, and the way on is the same one ADR-0028 offers. Whether 20 000
is the right number is a question a device answers, not this document.

An editor for a whole file remains out of scope, and the measurements say what
it would cost: not a screen but a reimplementation of what a native `EditText`
does — the text kept out of the autofill and accessibility transactions, and
the highlighting bounded to what is on screen.

## References

- [ADR-0028: A note is handed to an editor rather than opened here](0028-a-note-is-handed-to-an-editor-rather-than-opened-here.md)
- [ADR-0010: One operation at a time on the notes](0010-one-writer-for-the-working-copy.md)
- [ADR-0014: The notes are held between calls, and an edit names the file it changed](0014-the-notes-are-held-between-calls.md)
