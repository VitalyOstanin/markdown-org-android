# ADR-0022: Several collections, one agenda

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-02).

## Context

Notes are not all kept in one place. A work repository and a personal one, a
shared directory and a private one, a repository on a server and a directory
that is only on the device — the tasks in them are due on the same days and
are read at the same moment, in the morning, on one screen.

Until now the application held one directory, one remote, one branch and one
token, and a second repository meant a second installation. The core walked a
single directory, the working copy was a single lock, and the settings screen
was about the only collection there was.

Two things had to be decided before the rest followed: where an agenda over
several directories is merged, and how a task on screen says which directory
it came from — because every edit has to find its way back to that one and to
no other.

## Decision

The application works with a set of collections rather than one directory. A
collection is a name, a directory and the settings that reach its server;
everything that touches notes belongs to one of them, and the agenda over them
is a single agenda.

- The set is stored in a preference file of its own, and the settings of each
  collection in a file of its own. The first collection keeps the file a
  single-directory version of the application wrote, so an upgrade moves
  nothing: the remote, the branch, the token and the pinned host key stay where
  they were written. A copy that failed half way through could otherwise leave a
  device with two sets of credentials, or with none.
- A device that has never added a second collection gets exactly the screen it
  had. No marks on the rows, no filter above them, and the sample note is still
  seeded — for one collection only, because dropping a file of ours into a
  directory somebody added on purpose is not the same act as filling an empty
  first install.
- The merge belongs to the core. `markdown-org-extract` 0.13 walks a set of
  directories and returns one agenda, with each task carrying the root it came
  from; the client does not concatenate per-directory results. The ordering,
  the day buckets and the deduplication are the walk's business, and two
  clients doing it apiece would be two different agendas.
- A task is addressed by the pair of its root and its file. The same relative
  path exists in more than one collection, so an edit or a re-read that named
  the file alone would strike whatever note sits at that path in whichever
  directory was asked first. The root a task carries is the canonical path, and
  it is resolved once per collection when the set is put to use — a comparison
  that canonicalised on every edit would be a read of storage on the main
  thread.
- Every collection keeps its own working copy and its own lock. Work that spans
  the set takes the locks in the order of the set, so two such requests cannot
  deadlock against each other. A sync run is one collection at a time: each
  holds its own directory while it runs, and a phone syncing three repositories
  at once spends the radio on all of them and finishes none of them sooner. A
  collection that fails does not stop the ones after it, and the screen reports
  what each of them answered.
- A group action is split by collection, so each directory is still one rewrite
  and one commit however many of them the band covers — the promise of
  [ADR-0021](0021-a-group-is-one-rewrite-and-can-be-put-back.md), kept across
  the set. The undo carries the root beside each file for the same reason an
  edit does.
- Nesting is refused by the client. The core deduplicates exact roots and
  nothing else, so a directory inside another collection would have its notes
  read twice, and an edit would then act on one of the two copies on screen.
- Removing a collection stops it being read and erases its settings. The
  directory is left exactly as it is: it may be a repository holding commits
  that exist nowhere else, and a screen that offers to stop showing notes must
  not be the screen that deletes them. The last collection cannot be removed —
  an agenda over nothing has no way back except a reinstall.

Splitting the agenda into a section per collection was considered and rejected:
it breaks the single time axis the Time layout is built on. A collection shows
as a coloured mark at the end of the row, and as a chip in the filter above the
list.

## Consequences

- The filter is a view over the sections already scanned, so turning a
  collection off is a regroup rather than another walk of the directories.
- It is not stored between launches. It hides tasks, and a device that opens on
  an agenda missing half of them with no memory of why is worse off than one
  that starts with everything shown.
- The header of the settings screen is about one collection, and the sync
  banner is about the run as a whole. Over several collections the last answer
  of the run cannot stand for it, so the banner carries a line per collection
  as well.
- The scan grows with the set: two collections of a thousand notes are two
  thousand notes walked. What that costs is already logged per refresh.
- The colours come from a palette of six, taken by position in the set. A
  seventh collection repeats a colour, and reordering the set changes which
  collection has which colour — the name beside the dot is what identifies it.

## References

- `rust/markdown-org-ffi/src/index.rs` — the index over a set of roots, and the
  re-read addressed by root and file.
- `app/src/main/kotlin/…/core/NotesCollections.kt` — the set, its rules and
  where it is stored.
- `app/src/main/kotlin/…/core/CollectionsInUse.kt` — a working copy, settings,
  a writer and a syncer per collection.
- [ADR-0010](0010-one-writer-for-the-working-copy.md) — the lock each
  collection keeps, and the order they are taken in.
- [ADR-0013](0013-notes-directory-by-path.md) — why a collection is a path.
- [ADR-0021](0021-a-group-is-one-rewrite-and-can-be-put-back.md) — the group
  action this splits by collection.
