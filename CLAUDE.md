# Project rules for Claude Code

Architectural decisions live in [`docs/adr/`](docs/adr/). This file lists the
ones that bear on day-to-day work, with pointers to the full text and the
reasoning.

## Table of contents

- [Decisions in force](#decisions-in-force)
- [Working on the core](#working-on-the-core)
- [Working on the application](#working-on-the-application)
- [Background](#background)

## Decisions in force

- The extractor is linked in and called in-process; there is no subprocess and
  no second parser in Kotlin. See
  [ADR-0001](docs/adr/0001-extractor-in-process-over-uniffi.md).
- Types crossing the FFI boundary are this project's own, converted from the
  extractor's in one place. A value the interface reasons about is a variant,
  not a string. See [ADR-0002](docs/adr/0002-project-core-types-at-the-boundary.md).
- Build, then generate the bindings, then strip — in that order, and never
  `strip = "symbols"`. See [ADR-0003](docs/adr/0003-build-then-generate-then-strip.md).
- Build toolchains live in containers, driven by `podman`; the host stays
  clean. Versions come from `tools/versions.env`. See
  [ADR-0004](docs/adr/0004-build-only-in-a-container.md).
- libgit2 and OpenSSL are vendored, and the CA bundle crosses as PEM text
  because the vendored OpenSSL has no file IO. See
  [ADR-0005](docs/adr/0005-vendored-tls-and-libgit2.md).
- A sync fast-forwards or refuses with a named error; it never merges. See
  [ADR-0006](docs/adr/0006-fast-forward-only-sync.md).
- The access token lives in ordinary private preferences with
  `allowBackup="false"`; `EncryptedSharedPreferences` is deprecated and is not
  to be reintroduced. See [ADR-0007](docs/adr/0007-token-in-plain-preferences.md).
- The palette is the project's own; do not add a dynamic-colour branch. See
  [ADR-0008](docs/adr/0008-own-palette-over-dynamic-color.md).
- Org-mode semantics — repeaters above all — are verified against upstream
  Emacs Org-mode Elisp before code or tests are written, not recalled by
  analogy. The repeat rules and the two deliberate divergences are in
  [ADR-0009](docs/adr/0009-repeat-semantics-on-completion.md).
- Everything touching the notes directory goes through `NotesArea.exclusive`,
  and an edit commits itself in the same block. See
  [ADR-0010](docs/adr/0010-one-writer-for-the-working-copy.md).
- The core never reads a clock: "today" is an argument. See
  [ADR-0011](docs/adr/0011-today-comes-from-the-caller.md).
- Rust edition and the SDK levels are as they are on purpose; read
  [ADR-0012](docs/adr/0012-edition-and-sdk-levels.md) before raising either.
- The notes directory is a path, and a chosen one may sit outside the
  application's storage — which is what all files access is declared for. The
  system document picker is not an alternative: the core opens the directory
  by path. See [ADR-0013](docs/adr/0013-notes-directory-by-path.md).
- The tasks of the directory in use are held between calls, and the index
  notices no change it was not told about: an edit names the file it wrote, and
  a sync or a change of directory drops everything. Anything else that writes
  to the notes has to say which of the two it is. See
  [ADR-0014](docs/adr/0014-the-notes-are-held-between-calls.md).
- What the APK carries is published as the two licence lists it already holds,
  and no SBOM is generated. Reach for one when somebody is there to read it —
  see [ADR-0015](docs/adr/0015-licence-lists-instead-of-an-sbom.md).
- The release variant is shrunk by R8. What the shrinking cannot see is the
  path into the core, which JNA binds by name: anything new on that boundary
  belongs in `app/proguard-rules.pro`, and its name belongs in the list
  `tools/check-apk.sh` reads back out of the built APK. The instrumented tests
  cannot cover this — see
  [ADR-0016](docs/adr/0016-shrink-the-release-and-read-the-apk-back.md).
- The core opens a repository whoever owns its directory: libgit2's owner check
  refuses the whole of the shared storage, where a chosen notes directory
  lives. The setting is applied once per process in `sync.rs`, and every entry
  point that opens a repository goes through `open`. See
  [ADR-0017](docs/adr/0017-open-a-repository-the-platform-owns.md).
- Edits go back to the remote the way they come in: a push that fast-forwards
  or is refused, with the commits left on the device and the fetch reported
  apart from the push. See
  [ADR-0018](docs/adr/0018-edits-go-back-as-a-fast-forward-push.md).
- The directory is where the notes live, and git is added to it: `git init`
  over what is there, one commit of it, then the remote. A directory that is
  already a working copy of another address is refused. See
  [ADR-0019](docs/adr/0019-the-directory-holds-the-notes-and-git-is-added-to-it.md),
  amended by ADR-0024 below.
- `ssh://` is a supported address in both spellings, its key travels in the
  request and is offered to the configured endpoint alone, and the server is
  pinned by its host key. See
  [ADR-0020](docs/adr/0020-ssh-remotes-with-a-pinned-host-key.md).
- A group action is one call into the core and one rewrite per file, and it
  hands back what undoing it takes. A task it cannot edit is refused on its own
  while the rest of the group goes through. See
  [ADR-0021](docs/adr/0021-a-group-is-one-rewrite-and-can-be-put-back.md).
- The application works with a set of collections rather than one directory,
  and the merge of their agendas belongs to the core, not to the client. See
  [ADR-0022](docs/adr/0022-several-collections-one-agenda.md).
- A row says which collection it came from with a dot in that collection's
  colour at its head; a device with one collection sees the screen it always
  had. See [ADR-0023](docs/adr/0023-a-collection-is-a-dot-at-the-head-of-the-row.md).
- The application removes no file it did not write itself, and there is no
  operation anywhere in it that empties the notes directory. The rule is
  guarded by a test over the Kotlin sources rather than left to documentation.
  See [ADR-0024](docs/adr/0024-the-application-removes-no-file-it-did-not-write.md).
- The month calendar is laid out from the days the core answered with, not
  from a month the client fills out to whole weeks itself, and the weekday a
  week begins on is the phone's answer passed to the core rather than the
  core's fixed Monday. See
  [ADR-0025](docs/adr/0025-the-month-grid-is-the-core-s-answer.md).
- The month grid takes the height its cells want rather than sharing the whole
  window, and the day picked out of it stands under the grid with its rows. A
  tap on a cell picks a day; the button under the date opens it. See
  [ADR-0026](docs/adr/0026-the-day-picked-out-of-the-month-stands-under-it.md).
- A push the remote refuses is reported by whoever refused it: the branch that
  fell behind is worded here, and a server that declined is quoted as it wrote.
  See
  [ADR-0027](docs/adr/0027-a-refusal-is-reported-in-the-words-of-whoever-refused.md).
- A note is read in full by handing it to another application, not by an editor
  of this one: the task sheet offers it as a `content://` URI granted for the
  one launch. What that application writes is committed by the next sync, which
  says so, because the commit is one nobody asked for. See
  [ADR-0028](docs/adr/0028-a-note-is-handed-to-an-editor-rather-than-opened-here.md).
- The text of one entry is edited here, and nothing wider: the title of its
  heading and the lines under it, written back in one commit. What an action
  writes — the keyword, the cookie, the dates — is refused if it is typed, and
  an entry past twenty thousand characters goes to the editor above instead. See
  [ADR-0029](docs/adr/0029-an-entry-is-edited-here-a-file-is-not.md).
- A planning date is set, moved and taken off by one operation, and a line
  written where there was none is spelled after the file it lands in: the same
  weekday language, framing and indentation as the dates already there. A line
  a manual edit left carrying two keywords is left to be edited by hand. See
  [ADR-0030](docs/adr/0030-a-date-written-from-nothing-follows-the-file.md).
- Every edit hands back what the note held before it and after it, and one
  press at the foot of the screen writes the first back — the same restore the
  group action makes, refused where the note has moved on since. The offer is
  the last tap only, and it is dropped by the next edit. See
  [ADR-0031](docs/adr/0031-every-edit-carries-what-it-takes-to-undo-it.md).
- A task written from nothing goes to the end of the file its collection names
  in the settings, at the level that file writes its tasks at. Where it goes is
  decided once rather than guessed per task, and the end of the file is what
  keeps two devices' additions merging without a conflict. See
  [ADR-0032](docs/adr/0032-a-new-task-goes-to-a-file-the-collection-names.md).

- One occurrence of a repeating entry is cancelled by an `EXDATE` on the
  series, and moved by an entry of its own carrying `SERIES_ID` and
  `RECURRENCE_ID` — iCalendar's answer, in the extractor's property block. The
  identifier a series is named by comes from the caller, and the property block
  under the planning lines is not the body the entry editor hands over. See
  [ADR-0033](docs/adr/0033-an-occurrence-is-cancelled-in-place-and-moved-by-an-entry-of-its-own.md).

- What the reader is told is coming is decided on the device, from the same
  agenda the screen draws: an entry held at an hour is announced ahead of it,
  and everything else a day holds is announced once, in a digest read at the
  moment it is raised. The plan holds two days and is replaced whole whenever a
  note may have moved. See
  [ADR-0034](docs/adr/0034-reminders-are-planned-on-the-device-and-replaced-whole.md).

## Working on the core

- Tests come with the change, not after it. The core has unit tests beside the
  code and surface tests in `rust/markdown-org-ffi/tests/`.
- `tools/check-core.sh` runs `cargo fmt --check`, clippy with `-D warnings` and
  the tests, in the container.
- The toolchain is pinned in `rust/rust-toolchain.toml` to the version the NDK
  image carries. Bumping the image means bumping that line.
- Changing anything on the FFI surface means rebuilding the core before the
  instrumented tests: the bindings are generated from a built library, and the
  library is built only for the ABIs named in `ABIS`. The emulator runs
  `x86_64`, a device `arm64-v8a` — `ABIS="arm64-v8a x86_64" tools/build-core.sh`
  covers both.

## Working on the application

- `tools/test.sh` runs the JVM tests, `tools/lint.sh` ktlint and Android Lint,
  `tools/test-instrumented.sh` the instrumented suite on a running emulator.
- User-visible strings are resources, in both `values/` and `values-ru/`.
  Dates, times and plurals go through the platform's formatting, not through
  string concatenation.
- The interface reads state from the view model; a composable does not call the
  core.

## Background

- Code, comments, documentation, commit messages and `TODO.md` are written in
  English. The repository is private, but the code reads as if it were not.
- `docs/__check/` holds local review artefacts and is not part of the
  repository's documentation.
