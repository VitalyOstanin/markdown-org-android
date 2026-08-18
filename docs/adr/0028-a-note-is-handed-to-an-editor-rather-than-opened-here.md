# ADR-0028: A note is handed to an editor rather than opened here

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-19).

## Context

The application reads notes and edits the one line a task sits on. It has no
way to show a note as a document, and no way to reach a section that carries no
date at all — a shopping list, a page of keys — because such a section never
enters an agenda. Until now the only way to read one on the phone was to open
the file in some other application, found by hand through a file manager.

Meanwhile a perfectly good markdown editor is a tap away on most devices, and
the notes are ordinary files in a directory the user chose. What was missing
was the offer, not the capability.

Growing an editor here is a large piece of work with no end to it: a text area
is the start, and then comes finding, folding, tables, images, and every other
thing a person expects of an editor. None of it is what this application is
for.

## Decision

The task sheet offers to open the note in another application. The note travels
as a `content://` URI from a `FileProvider` of this application, granted read
and write for the one launch, under `ACTION_VIEW` and `text/markdown`.

A `file://` URI is not an option: the platform refuses it outright, and it
would also require the receiving application to hold a storage permission over
a directory the user may have put anywhere. The provider's paths name the
filesystem root, because the notes directory is wherever it was chosen to be.

`ACTION_VIEW` rather than `ACTION_EDIT`: editors declare the first and few
declare the second, so the chooser would be empty on a device that has an
editor on it. The write permission travels with the URI either way.

This application does not become an editor, now or later.

## Consequences

- Reading a note in full, and reaching a section no agenda shows, is a tap
  from the task rather than a hunt through a file manager.
- What the other application writes is committed by the next sync, not by the
  editor. Every sync begins by committing whatever the working copy holds —
  that commit was already there, for edits of this application's own that
  failed to commit at the time — and an edit made elsewhere is an uncommitted
  change like any other. It therefore goes up under a message nobody chose,
  which is why a run that settled one says so on screen rather than leaving an
  entry in the history the user cannot account for.
- Between the edit and that sync the checkout is dirty, so an edit made
  elsewhere and left there is not on the server. That is the state the button's
  hint describes.
- When the commit itself cannot be made, the sync stops on a dirty checkout and
  the banner offers to try both again — there is nothing else in the
  application that moves a checkout out of that state.
- The chooser is the platform's, so which editor opens is the user's standing
  choice, not a setting of this application.
- A device with nothing that opens markdown gets a sentence saying so.

## References

- `app/src/main/kotlin/.../ui/ExternalNote.kt` — the URI, the type and the
  flags.
- `app/src/main/AndroidManifest.xml`, `res/xml/note_paths.xml` — the provider.
- `app/src/androidTest/kotlin/.../core/NotesSyncRoundTripTest.kt` — what
  becomes of an edit this application did not make.
