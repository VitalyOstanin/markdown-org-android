# ADR-0021: A group is one rewrite per file, and it can be put back

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-02).

## Context

The overdue entries are grouped by how long ago they slipped, and a group of
ten dates from three years ago is not read task by task — it is answered in one
move. Until now every answer was one tap on one row: a sheet, a date, and a
scroll back to where the list was, ten times over.

Repeating that ten times is not only tedious. Each single edit is its own read,
its own write and its own commit, so a band of twenty leaves twenty commits
that describe one decision, and twenty rewrites of files most of which hold
several of those tasks.

Acting on many notes at once also raises a question a single tap does not: what
if it was the wrong band. The notes are the user's own, several of the changes
are to dates they will not remember, and the directory may not be in git at all
— [ADR-0019](0019-the-directory-holds-the-notes-and-git-is-added-to-it.md) made
a plain directory a way to use the application rather than a state on the way
to one.

## Decision

A group action is one call into the core, and it hands back what it takes to
undo it.

- `applyToGroup(dir, targets, action, today)` takes the tasks of a band and one
  of three actions: move to today, drop the planning line, mark cancelled. The
  targets are grouped by file, each file is read once, every task in it is
  rewritten, and the file is written once. The application commits once for the
  whole group.
- Every change is computed before any is applied, and the changes are applied
  from the end of the file backwards, so dropping a line cannot move a line
  another change is aimed at.
- A task that cannot be edited is refused on its own and named, while the rest
  of the group goes through: `Moved` when the heading is no longer on the line
  the agenda saw, `NoPlanningLine` when the task carries no date of that kind,
  `Unsupported` for an edit this application does not make, `Unreadable` for a
  file that is gone or not UTF-8. Failing the whole band over one entry would
  leave the user to find which entry it was.
- A task already standing the way the action would leave it is neither changed
  nor refused. There is nothing wrong with it and nothing to do to it, and
  counting it as changed would claim a commit that does not exist.
- A missed repeat is caught up rather than dragged to today: moving it keeps
  the repeater and lands on the next occurrence its own interval gives, which
  is the rule `completeTask` already follows for one task.
- The undo is a snapshot in memory, not a git revert. `applyToGroup` returns
  each touched file as it was before and as the group left it; `revertBulk`
  writes the `before` back, but only where the file still holds the `after`.
  A file a sync or another edit has moved on is skipped and named.

A git revert would have been the other way to do it, and it was not taken: it
needs a repository, and a directory with no git is a supported way to keep the
notes. An undo available only to some users is worse than one that works the
same everywhere.

## Consequences

- The rollback holds the full text of every file the group touched, in memory,
  for as long as the offer stands. A band spanning several notes of tens of
  kilobytes is well inside what a phone holds; a group across a whole
  collection would not be, and no interface offers one.
- An undo cannot take away work done after the move. It can, though, leave the
  notes half-restored — some files back, some left as they are — and the
  application says so rather than reporting success.
- The undo is not itself undoable, and it is not offered when the group changed
  nothing.
- The history reads as one commit per decision: `Move 5 overdue tasks to
  today`, and `Undo the group edit of 2 notes` if it is put back. The revert
  is a commit of its own rather than a rewriting of the one before it.
- The screen gains a control per band heading. It sits outside the area that
  folds the band, so folding — what the heading is pressed for most — is
  unchanged, and a folded band can still be answered without unfolding it.

## References

- `rust/markdown-org-ffi/src/bulk.rs` — `apply_to_group`, `revert_bulk`,
  `RefusalReason`.
- `app/src/main/kotlin/…/core/NotesEditor.kt` — the group under the same lock
  and commit as a single edit.
- `app/src/main/kotlin/…/ui/GroupActions.kt` — the menu on a band heading and
  what the result offers.
- [ADR-0010](0010-one-writer-for-the-working-copy.md) — the lock a group runs
  under, unchanged.
- [ADR-0019](0019-the-directory-holds-the-notes-and-git-is-added-to-it.md) —
  why the undo may not depend on git.
