# ADR-0024: The application removes no file it did not write

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-11). Amends [ADR-0019](0019-the-directory-holds-the-notes-and-git-is-added-to-it.md),
which kept emptying the directory as an action of its own.

## Context

[ADR-0019](0019-the-directory-holds-the-notes-and-git-is-added-to-it.md) took
the destruction out of the form and left it as a stated action: a directory
holding a checkout of another repository was reported, and next to the report
stood a button that emptied the directory so the configured remote could be
cloned into it.

The button was there because `git clone` refuses a directory that is not
empty. That is a requirement of one operation, and it was answered by removing
whatever stood in its way.

What stands in its way is not the application's. The notes directory is a
path in the settings, and with access to all files that path can be any
directory of the device — a documents folder, a download folder, another
application's working copy. One press over such a directory removes files this
application never wrote, and a confirmation dialog does not change what is
being confirmed: it only moves the responsibility for it.

## Decision

The application removes no file it did not write itself. There is no operation
anywhere in it that empties the notes directory.

- `NotesArea.reset` and its implementation are gone, and with them the view
  model's `replaceNotes`, the button beside the banner, and the message that
  reported a wipe which only half happened.
- A directory that is a working copy of another address stays a refusal. The
  address is stored, the directory is untouched, and the message names the two
  ways on: point the application at a different directory, or empty this one
  outside the application.
- The rule is guarded rather than documented. A test walks the application's
  Kotlin sources and fails on any call that takes a file off the disk, with one
  exception named per file: the crash log, which the application writes into
  its own storage.

## Consequences

- The path from "notes on the device" to "notes in a repository" no longer has
  a step that destroys anything, at any point, for any answer.
- Pointing the application at a directory that already holds somebody else's
  checkout cannot be resolved from the phone alone: another directory has to be
  chosen, or that one emptied by a file manager. This is accepted — the whole
  of the first setup is otherwise reachable from the device, and this case is a
  directory chosen by mistake rather than a step of setting up.
- The crash log is the only file the application deletes, and any second such
  file has to be added to the guard's list, which is where the decision is
  read.

## References

- `app/src/test/kotlin/…/build/FileDeletionTest.kt` — the guard, and the list
  of files the application owns.
- `app/src/main/kotlin/…/core/NotesArea.kt` — the operations the working copy
  offers, none of which removes anything.
- `app/src/main/kotlin/…/ui/NotesSettings.kt` — `saveSettings`, and what a
  checkout of another address leads to.
- [ADR-0019](0019-the-directory-holds-the-notes-and-git-is-added-to-it.md) —
  the record this amends.
