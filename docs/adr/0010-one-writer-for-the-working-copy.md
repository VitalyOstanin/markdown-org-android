# ADR-0010: One operation at a time on the notes, and an edit commits itself

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

The notes directory is both what the agenda scans and what git clones,
fast-forwards and commits. Those operations arrive from different coroutines —
a refresh after an edit, a sync the user asked for, the seeding of the sample
on first launch, the wipe that precedes a change of remote — and
`Dispatchers.IO` is a pool of up to 64 threads, so without arrangement they run
at the same time rather than queue.

Concurrent access here is not a race over a variable. Two libgit2 operations
over one repository contend for `index.lock`; a wipe running under a clone
leaves half a checkout; a scan running under a fast-forward reads a mixture of
the old files and the new.

Separately: an edit that writes a file and leaves it uncommitted makes the
checkout dirty, and a dirty checkout is refused by the sync
([ADR-0006](0006-fast-forward-only-sync.md)). Postponing the commit to a timer
or a button would leave the application unable to sync until that happened.

## Decision

Every operation that touches the directory goes through `NotesArea.exclusive`,
which holds a `Mutex` and moves the work off the main thread. The lock is not
reentrant, and that is stated: a block must not call another operation that
takes it.

An edit commits immediately after the write, in the same exclusive block. The
commit indexes the whole working copy rather than the edited file alone,
because what the next sync refuses is any uncommitted change — including a note
captured outside the application.

The write itself goes to a temporary file beside the target and is renamed over
it, so an interrupted write leaves the notes as they were. The commit step
skips anything carrying the temporary prefix.

## Consequences

- No `index.lock` contention, no torn reads, no half-wiped checkout.
- Operations queue: a scan waits for a sync in flight. The interface shows that
  rather than hiding it.
- Cancelling a coroutine does not interrupt a call already inside the core; the
  wait is bounded by the core's own network timeouts.
- A note changed outside the application is swept into the next commit the
  application makes.

## References

- `app/src/main/kotlin/…/core/NotesArea.kt` — the contract and the reasoning.
- `app/src/main/kotlin/…/core/NotesStore.kt` — the lock.
- `rust/markdown-org-ffi/src/sync.rs` — `commit_changes`.
- `rust/markdown-org-ffi/src/document.rs` — the write-and-rename.
