# ADR-0014: The notes are held between calls, and an edit names the file it changed

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-31).

## Context

Every agenda used to be a walk of the notes directory. That is the right shape
for the first one — the tasks have to come from somewhere — and the wrong shape
for the ones after an edit. A tap that moves a task by a day rewrites one line
of one file, and the agenda that follows re-read and re-parsed every other note
to find out what they still said.

Measured on the device the application is developed against, with the notes on
its shared storage:

| Notes                 | Rows on screen | The edit | The agenda after it |
|-----------------------|----------------|----------|---------------------|
| 1000 files, 5000 tasks  | 2774           | 35–38 ms | 1383–1684 ms        |
| 5000 files, 25 000 tasks | 5525          | 31 ms    | 3091–3196 ms        |

The walk is between 97 and 99 per cent of what the user waits for. It does not
grow with the collection past the extractor's cap of 10 000 tasks, which is why
five times the files cost twice the time rather than five times it — but a
second and a half is already a wait, and the tap that caused it changed one
line.

Where the time goes is not the sorting: on the same sets, a scan that only
lists tasks and one that builds a day agenda differ by less than the noise. It
is reading and parsing files, and after an edit all but one of them were parsed
to the same answer as before.

The extractor exposes what a single file needs — `extract_tasks_with_counter`,
the weekday mappings, the size cap — so holding tasks between calls does not
mean a second implementation of the parser. What it does mean is state, and
state that can be wrong: notes change without this application being told, most
of all when a fetch fast-forwards the checkout.

## Decision

The FFI crate gains `NotesIndex`, an object that holds the tasks of one
directory between calls, and the application asks it for agendas.

- The constructor walks the directory. `rescan` walks it again, replacing
  everything held. `refresh_file` re-reads one file and replaces the tasks that
  came from it. `agenda` puts what is held through the extractor's filter.
- Agendas are built by one function whether the tasks came from a fresh walk or
  from the index, so the two cannot answer differently. That property is what
  makes the index worth having, and it is a test.
- What the index means by "held" is stated rather than guessed: it notices no
  change it was not told about. The application tells it in three places — an
  edit names the file it wrote, a sync drops everything because a
  fast-forward rewrites files unseen, and a change of directory drops
  everything because the held notes belong to the previous one.
- A file that has gone, cannot be read, or is not UTF-8 is not a failure of
  `refresh_file`: its tasks are dropped and the rest stands, which is what the
  walk would have done with it. A failed re-read is not shown to the user
  either — the next full scan reads that file along with the rest.
- The task cap applies to the index as a whole after a re-read, so it never
  holds more than a walk of the same directory would have found.
- The index belongs to `AgendaSource`, which compares the directory it was
  built over against the current one on every call. It is not a global, and it
  is released when the directory changes.

## Consequences

- An edit costs the file it changed. What the user waits for after a tap is the
  write, the commit and the rebuilding of the list — the walk is gone from that
  path.
- The application holds the tasks of the collection in memory for as long as the
  directory is in use. At the cap that is 10 000 tasks; on the sets above the
  process stayed within its ordinary footprint, and the alternative was reading
  the same data off storage every few seconds.
- A note changed by another application on the device is not noticed until
  something invalidates the index — a sync, a change of directory, or the next
  start. Before this it would have been picked up by the next agenda. That is
  the cost of the decision, and it is stated in the interface rather than left
  to be discovered.
- Building an agenda now clones the held tasks, because the extractor's filter
  consumes what it is given. That is the one cost that grows with the
  collection on every agenda, and it is milliseconds against the seconds it
  replaces.

## References

- `rust/markdown-org-ffi/src/index.rs` — the index and what it promises.
- `rust/markdown-org-ffi/tests/index.rs` — that an agenda from the index is the
  agenda from a walk, and what a re-read does with a file that has gone.
- `app/src/main/kotlin/…/core/AgendaSource.kt` — where the index lives and when
  it is dropped.
- [ADR-0010](0010-one-writer-for-the-working-copy.md) — the lock every call
  here still goes through.
