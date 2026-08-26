# ADR-0036: Where an entry is written is the collection's to say, and an entry can move between files

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-25).

Amends ADR-0032 on one point: where inside the receiving file a new task goes.
Everything else that record decided — which file receives new tasks, the level
the heading is written at, the checks on the title and on the file name —
stands as written.

## Context

ADR-0032 settled where a new task goes: a file each collection names, and the
end of that file. The end was chosen for the merge — the collections are git
checkouts merged line by line, and two devices appending on the same day merge
without a conflict.

Using it showed what the end costs the reader. A file that has been kept for a
year opens on a note written a year ago, and what was written today is a scroll
away. The reader who added the task is the reader who comes looking for it the
same evening, and on a phone the distance is measured in screenfuls.

The merge argument is real but not universal. It applies to a collection edited
on two devices between syncs; a collection edited on one is never in that
position, and its owner pays the scrolling for a conflict that cannot happen.

A second thing that use showed: the file a task is written into is not the file
it stays in. The receiving file is where a task lands when there is nothing to
say about it yet; once there is, it belongs among the notes it is about. Until
now the only way to file it was to open the note in another editor and move the
lines by hand — see ADR-0028 — which is a text editor's work and loses the
history of the move.

## Decision

Where in a file an entry is written becomes a setting of the collection, beside
the file that receives new tasks. Two answers: at the start of the file, before
its first heading and after whatever stands above it — an introduction, a
property block, a YAML front matter — or at the end, after everything it holds.
The default is the start; a collection edited on two devices between syncs can
say the end and get what ADR-0032 decided.

The same setting places an entry that is moved. A reader who wants what is new
at the top of a file wants it there however the entry arrived.

A collection also names a main file, or leaves it unnamed. The sheet of a task
offers to carry the entry there in one tap when the entry is not already in it,
and to carry it into any markdown file of the collection through a list. A
collection with no main file simply makes no such offer.

What travels is the entry as a reader means it: the heading, its planning
lines, its property block, its text, and every heading nested under it, up to
the next heading of the same level or shallower. The text travels byte for
byte — no heading is deepened to fit under what it lands beside, and nothing is
renumbered — so git records a removal and an addition of the same lines.

The move is bounded to one collection. Between two of them it would be two
checkouts, two commits and two undos, and a second step that failed would leave
the entry in both places or in neither.

Both files are written, the receiving one first. A failure to remove the entry
from the file it left is answered by putting the receiving file back, so the
outcome of a failed move is the notes as they were rather than an entry that
exists twice — and never an entry that exists nowhere.

## Consequences

Two devices that both add a task to a collection set to write at the start now
conflict on the same lines, where appending never did. This is the cost of the
default and the reason the other answer stays: the setting is one tap, and the
collection that needs the merge says so.

A file whose first heading is not its first line keeps its opening. What counts
as the opening is decided by the first heading, with a YAML front matter
stepped over whole — a comment inside one begins with `#` at the start of a
line and would otherwise read as a heading, and an entry written above it would
land inside the front matter.

An entry moved to the start of a file may end up nested under a heading
shallower than itself: a `###` entry written above a `#` title now sits inside
that title's section. This is accepted rather than fixed by rewriting the level,
because the alternative is a move that changes the text — and then git shows a
rewrite where the reader made a move.

Undoing a move puts two files back rather than one, and the pair goes back in
the order it was written. The undo is refused for a file written to since —
ADR-0031's rule, applied twice — so a move can go half back, which is reported
as it is for a group.

The sheet reads the files of a collection off storage when it opens. A walk of
the directory is not something to hold a tap for, so the move actions appear a
moment after the sheet does, and a collection whose directory cannot be read
offers none.

## References

- ADR-0028: A note is handed to an editor rather than opened here
- ADR-0031: Every edit carries what it takes to undo it
- ADR-0032: A new task goes to a file the collection names
