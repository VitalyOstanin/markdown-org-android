# Architecture Decision Records

This directory holds the project's Architecture Decision Records (ADRs),
following the format proposed by Michael Nygard. Each ADR captures a single
architectural decision: the context that forced the choice, what was decided,
and the trade-offs that came with it.

The same format is used by the two sibling projects,
[`markdown-org-extract`](https://github.com/VitalyOstanin/markdown-org-extract)
and [`markdown-org-vscode`](https://github.com/VitalyOstanin/markdown-org-vscode),
so a decision can be followed across the three repositories.

## Table of Contents

- [Conventions](#conventions)
- [Index](#index)
- [Adding a new ADR](#adding-a-new-adr)

## Conventions

- Files are named `NNNN-kebab-case-title.md` with a four-digit zero-padded
  sequence number.
- ADRs are **immutable** once they leave `Status: Proposed`. To change a
  decision, write a new ADR that supersedes the old one and update both files'
  `Status` fields with cross-references. An amendment is recorded the same way:
  the new decision goes in a new ADR, and the existing one is touched only by a
  `Status` pointer to it, never by rewriting its body.
- Each ADR has the sections `Status`, `Context`, `Decision`, `Consequences`
  and, optionally, `References`. Keep the body to one or two screens.
- The index below mirrors the directory; keep it in sync when an ADR is added
  or changes status.
- ADRs are written in English, like the rest of the project's code and
  documentation.

## Index

| #    | Title                                                                                        | Status   |
| ---- | -------------------------------------------------------------------------------------------- | -------- |
| 0001 | [The extractor runs in-process over UniFFI](0001-extractor-in-process-over-uniffi.md)         | Accepted |
| 0002 | [The core's types are projected at the boundary](0002-project-core-types-at-the-boundary.md)  | Accepted |
| 0003 | [Build, then generate the bindings, then strip](0003-build-then-generate-then-strip.md)       | Accepted |
| 0004 | [The build toolchains live in containers](0004-build-only-in-a-container.md)                  | Accepted |
| 0005 | [libgit2 and OpenSSL are vendored](0005-vendored-tls-and-libgit2.md)                          | Accepted |
| 0006 | [Syncing fast-forwards or refuses](0006-fast-forward-only-sync.md)                            | Accepted |
| 0007 | [The access token is kept in ordinary private preferences](0007-token-in-plain-preferences.md) | Accepted |
| 0008 | [The agenda uses a palette of its own](0008-own-palette-over-dynamic-color.md)                | Accepted |
| 0009 | [Completing a repeating task follows upstream Org-mode](0009-repeat-semantics-on-completion.md) | Accepted |
| 0010 | [One operation at a time on the notes](0010-one-writer-for-the-working-copy.md)               | Accepted |
| 0011 | [The core is told what "today" is](0011-today-comes-from-the-caller.md)                       | Accepted |
| 0012 | [Rust edition 2021, and targetSdk one below compileSdk](0012-edition-and-sdk-levels.md)       | Accepted |
| 0013 | [The notes directory is a path, and may be outside the application's storage](0013-notes-directory-by-path.md) | Accepted |
| 0014 | [The notes are held between calls, and an edit names the file it changed](0014-the-notes-are-held-between-calls.md) | Accepted |
| 0015 | [What the APK carries is published as licence lists, not as an SBOM](0015-licence-lists-instead-of-an-sbom.md) | Accepted |
| 0016 | [The release is shrunk, and the APK is read back to check it](0016-shrink-the-release-and-read-the-apk-back.md) | Accepted |
| 0017 | [A repository is opened whoever the platform says owns its directory](0017-open-a-repository-the-platform-owns.md) | Accepted |
| 0018 | [Edits go back to the remote as a fast-forward push, or not at all](0018-edits-go-back-as-a-fast-forward-push.md) | Accepted |
| 0019 | [The directory holds the notes, and git is added to it](0019-the-directory-holds-the-notes-and-git-is-added-to-it.md) | Amended by 0024 |
| 0020 | [SSH remotes, with the server pinned by its host key](0020-ssh-remotes-with-a-pinned-host-key.md) | Accepted |
| 0021 | [A group is one rewrite per file, and it can be put back](0021-a-group-is-one-rewrite-and-can-be-put-back.md) | Accepted |
| 0022 | [Several collections, one agenda](0022-several-collections-one-agenda.md) | Accepted |
| 0023 | [A collection is a dot at the head of the row](0023-a-collection-is-a-dot-at-the-head-of-the-row.md) | Accepted |
| 0024 | [The application removes no file it did not write](0024-the-application-removes-no-file-it-did-not-write.md) | Accepted |
| 0025 | [The month grid is the core's answer, and the phone says where a week starts](0025-the-month-grid-is-the-core-s-answer.md) | Accepted |
| 0026 | [The day picked out of the month stands under the grid](0026-the-day-picked-out-of-the-month-stands-under-it.md) | Accepted |
| 0027 | [A refusal is reported in the words of whoever refused](0027-a-refusal-is-reported-in-the-words-of-whoever-refused.md) | Accepted |
| 0028 | [A note is handed to an editor rather than opened here](0028-a-note-is-handed-to-an-editor-rather-than-opened-here.md) | Accepted |
| 0029 | [An entry is edited here, a file is not](0029-an-entry-is-edited-here-a-file-is-not.md) | Accepted |
| 0030 | [A date written from nothing follows the file it lands in](0030-a-date-written-from-nothing-follows-the-file.md) | Accepted |
| 0031 | [Every edit carries what it takes to undo it](0031-every-edit-carries-what-it-takes-to-undo-it.md) | Accepted |
| 0032 | [A new task goes to a file the collection names](0032-a-new-task-goes-to-a-file-the-collection-names.md) | Accepted |
| 0033 | [An occurrence is cancelled in place and moved by an entry of its own](0033-an-occurrence-is-cancelled-in-place-and-moved-by-an-entry-of-its-own.md) | Accepted |
| 0034 | [Reminders are planned on the device and the plan is replaced whole](0034-reminders-are-planned-on-the-device-and-replaced-whole.md) | Accepted |

## Adding a new ADR

1. Take the next free number and name the file `NNNN-kebab-case-title.md`.
2. Copy the section layout from any existing record: `Status`, `Context`,
   `Decision`, `Consequences`, `References`.
3. Add a row to the index above.
4. If the record replaces or amends an earlier one, update that record's
   `Status` field to point here.
