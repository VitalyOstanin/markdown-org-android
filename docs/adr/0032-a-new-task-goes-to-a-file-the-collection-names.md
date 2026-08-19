# ADR-0032: A new task goes to a file the collection names

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-19).

## Context

Every operation in this application until now has started from a task the
agenda showed: a heading, in a file, on a line. Writing a task that is not
there yet starts from nothing, and the first question is where it goes.

Nothing in a new task answers that. A title, a keyword, a priority and a date
say what the task is, not which of the user's notes it belongs in — and the
collections this application reads are somebody's own files, arranged for
reasons it cannot see. Filing by tag, by date or by the note last edited would
be a guess made on every creation, and a task filed into the wrong note is a
task its owner will look for.

Where inside the file is the second question, and it has an answer the notes
themselves impose: the collections are git checkouts merged line by line, and
two devices adding a task on the same day have to merge without a conflict.

## Decision

Each collection names the file it receives new tasks in — one setting, beside
the directory it reads, defaulting to `inbox.md` and made by the first task
written to it. The screen that writes a task states the file rather than
offering a path to type; where more than one collection is set up, it asks
which of them receives the task and nothing more.

The entry is appended to the end of that file. Nothing above it is touched, so
the merge of two devices' additions is the merge of two appends.

What is written follows the file, the way a planning line already does (see
ADR-0030). The heading is written at the level the file writes its tasks at —
the shallowest level carrying a keyword, one below the shallowest heading in a
file that has none yet, and the top level in a file that has no headings at
all. The date, when there is one, is spelled the way the file spells the dates
it already holds.

The title is read back with the grammar that will read it out of the file and
refused when it turns into a keyword or a priority: those are fields of the
screen, and typing `TODO ring the dentist` must not set a status.

The file has to end in `.md`. The walk behind the agenda reads that glob and
nothing else, so a task written anywhere else would stand on the agenda after
the write — the note it went into is re-read by name — and be gone at the next
full scan, with the file still holding it.

## Consequences

A task can be made from the agenda without leaving the application, and where
it lands is a decision made once in the settings rather than on every creation.

The receiving file grows and nothing prunes it. That is the same property the
notes already have, and the entries in it are ordinary entries: they can be
edited, dated, completed and moved by hand into whatever note they belong in.

A creation is undone the way an edit is (ADR-0031), and it is the only way this
application removes an entry. Undoing the first task written into a file that
did not exist leaves the file behind, empty: the restore writes files, it does
not remove them.

A collection whose receiving file names a directory that is not there refuses
the write and says so. The directory is not created — a name nobody made a
directory for is a typo in the settings, and making it would leave the typo in
place with an empty tree beside it.

## References

- [ADR-0029: An entry is edited here, a file is not](0029-an-entry-is-edited-here-a-file-is-not.md)
- [ADR-0030: A date written from nothing follows the file it lands in](0030-a-date-written-from-nothing-follows-the-file.md)
- [ADR-0031: Every edit carries what it takes to undo it](0031-every-edit-carries-what-it-takes-to-undo-it.md)
