# TODO

Work that is understood but deliberately not done yet.

## Table of contents

- [SSH remotes](#ssh-remotes)
- [Weekday names beyond Russian and English](#weekday-names-beyond-russian-and-english)
- [Notes carrying a byte-order mark](#notes-carrying-a-byte-order-mark)
- [Unicode normalisation when a heading can be typed](#unicode-normalisation-when-a-heading-can-be-typed)

## SSH remotes

Only `https://` remotes (and a local path) are accepted today; `remoteUrlProblem`
in `app/src/main/kotlin/io/github/vitalyostanin/markdownorg/core/RemoteUrl.kt`
refuses `ssh://` and `git@host:path` before anything is stored, because saving a
remote empties the working copy and the failure would otherwise surface only
after that.

Two things are missing, not one:

| № | Missing                                                                | What it takes                                                             |
|---|------------------------------------------------------------------------|---------------------------------------------------------------------------|
| 1 | The transport. `git2` is built with `default-features = false` and the `https` feature only, so libgit2 carries no libssh2 and does not register the protocol. | Add the `ssh` and `ssh_key_from_memory` features, vendor libssh2, and check what it adds to the library — the vendored stack is already most of the 10 MB per ABI. |
| 2 | Somewhere to keep a key. The settings hold a URL, a branch and a token; there is no private key, no passphrase and no host key. | An import path in the settings screen, storage in the application's own directory, and a `certificate_check` callback — without one the connection accepts any server that answers. |

Tests would need an ssh server to clone from, which the instrumented suite does
not have today.

Worth doing when a repository that offers no token authentication has to be
used. GitLab and GitHub both take a personal access token over https, which is
what the core already sends.

## Weekday names beyond Russian and English

Moving a date rewrites the weekday token to match, in the language the file
already uses. The two languages the ecosystem knows are Russian and English
(`SUPPORTED_LOCALES` in the extractor), and `weekday_like` in
`rust/markdown-org-ffi/src/planning.rs` refuses anything else with
`Unsupported` rather than replacing, say, Ukrainian `Нд` with Russian `Вс`.

For a file written in a third language that means its dates cannot be moved
from the phone at all. Adding one is a table in the extractor's `locale`
module plus the matching entry in `SUPPORTED_LOCALES`, so that reading and
writing agree; doing it here alone would let the application write names the
extractor cannot read back.

## Notes carrying a byte-order mark

A file that starts with U+FEFF keeps it: it is read as part of the first line
and written back with it. Its first heading is not editable, because the
extractor anchors the heading grammar at the start of the line and a line
beginning with the mark never becomes a task — so nothing reaches an edit.

Stripping the mark on read and restoring it on write would only help once the
extractor skips it as well. Until then the two would disagree about which
line the file starts with.

## Unicode normalisation when a heading can be typed

`Document::heading` compares the heading in the file with the one the caller
believes is there byte for byte. Today both come from the same scan and pass
through Kotlin without loss, so a NFC/NFD difference cannot arise.

It can as soon as a heading is typed — a system keyboard hands over decomposed
diacritics — and a visually identical heading would then be refused as
`Stale`. The answer is to compare after NFC normalisation
(`unicode-normalization`), and it is worth adding together with whatever first
lets a heading be entered.
