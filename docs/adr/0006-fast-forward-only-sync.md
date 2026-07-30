# ADR-0006: Syncing fast-forwards or refuses; it never merges

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

The application owns a git working copy in its own private storage and pulls
it forward from a remote. Edits made here are committed immediately
([ADR-0010](0010-one-writer-for-the-working-copy.md)), so the local branch can
carry commits of its own.

When both sides have moved, git offers a merge. A merge on a phone has no
resolution the user could act on: there is no editor for a conflicted file,
the notes are markdown whose conflicts are semantic rather than textual, and a
merge commit made blind would push a state nobody reviewed to the remote.

## Decision

`sync_repository` clones into an empty directory on first use and
fast-forwards afterwards. Anything else is reported rather than resolved:

- both sides moved — `SyncError::Diverged`, carrying the branch name as a
  field so the interface can put it into a sentence of its own language;
- the working copy has uncommitted changes — `SyncError::Dirty`, carrying how
  many;
- an untracked file the update would overwrite stops the sync rather than
  being overwritten.

The network side is bounded: 15 s to connect, 60 s per request, set once per
process.

## Consequences

- The application never produces a commit the user did not cause.
- A diverged checkout has to be resolved elsewhere — on a workstation — and
  the application says so instead of hiding it.
- Pushing is not part of this decision: what the application commits stays
  local until something else sends it. That asymmetry is deliberate while the
  editing surface is small.

## References

- `rust/markdown-org-ffi/src/sync.rs` — `sync_repository`, the error variants,
  `use_timeouts`.
- `app/src/main/kotlin/…/core/NotesSync.kt` — the Kotlin side and the retry of
  transient failures.
