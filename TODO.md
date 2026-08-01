# TODO

Work that is understood but deliberately not done yet.

## Table of contents

- [A local directory as the store, with git added later](#a-local-directory-as-the-store-with-git-added-later)
- [Acting on a whole overdue group at once](#acting-on-a-whole-overdue-group-at-once)
- [Several note repositories at once](#several-note-repositories-at-once)
- [One set of notes on more than one remote](#one-set-of-notes-on-more-than-one-remote)
- [SSH remotes](#ssh-remotes)
- [Weekday names beyond Russian and English](#weekday-names-beyond-russian-and-english)
- [Notes carrying a byte-order mark](#notes-carrying-a-byte-order-mark)
- [Unicode normalisation when a heading can be typed](#unicode-normalisation-when-a-heading-can-be-typed)

## A local directory as the store, with git added later

Notes on the device with no git at all, and git turned on over the same
directory later without the notes being lost on the way.

Half of this already works by accident: the notes directory is a plain
directory until a remote is configured, and a fresh install seeds a sample into
it. What is missing is that the state is treated as "not set up yet" rather
than as a way to use the application. The directory is modelled as the working
copy of a remote, and saving an address empties it —
`AgendaViewModel.saveSettings` calls `NotesStore.reset` whenever `remoteUrl`
changes, because the core clones into an empty directory. A user
who kept notes locally and then entered an address would lose them.

| № | Part          | What changes                                                                                                                                            |
|---|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Settings      | The store is local or remote-backed, said outright, rather than inferred from an empty address. Local is a configured state: no sync, no banner asking for a remote, no retry timer. |
| 2 | The screens   | Sync controls absent rather than failing in the local case, and the settings screen offers "start tracking this directory in git" instead of an address field that wipes on save. |
| 3 | The core      | A path that takes a directory holding files: `git init`, add everything, one commit, then `remote add` and the first fetch — as opposed to `sync_repository`, which only clones into an empty directory and fast-forwards. |
| 4 | Unrelated histories | The commit made from the directory and whatever the remote already holds share no ancestor. Fast-forward cannot join them, so the choice has to be offered and named: keep the local notes and push them (needs push, which does not exist yet), take the remote and set the local ones aside, or refuse and say why. |
| 5 | Wiping        | `reset` stops being what a settings change does. Emptying the directory is only right when the user asked to replace its contents, and that has to be a separate, stated action. |

Worth doing before the several-repositories work above: it changes what a
"source" is, and the migration that turns one stored triple into a list should
already know about the local case.

## Acting on a whole overdue group at once

The overdue entries are grouped by how long ago they slipped, and each group is
still worked through one task at a time. A group of ten dates from three years
ago is not read task by task — it is answered in one move, and today every one
of those moves is a sheet, a date and a scroll back to where the list was.

Todoist names the pattern: a "Reschedule" beside the overdue section that moves
everything to today, or offers a date per task, and that is what stops the
backlog from being postponed one day at a time until it is unreadable.

| № | Part            | What changes                                                                                                     |
|---|-----------------|--------------------------------------------------------------------------------------------------------------------|
| 1 | The core        | An edit that takes several tasks rather than one, so a group of twenty is one rewrite of the file and one commit rather than twenty of each. |
| 2 | Failure         | A task whose heading moved under the edit fails on its own without taking the rest of the group down; what did not apply has to be named. |
| 3 | The screens     | An action on the group header — move to today, drop the date, mark cancelled — and an undo, since the move rewrites notes wholesale. |
| 4 | Repeats         | A missed repeat is not rescheduled but caught up: moving it to today has to keep the repeater and follow the same rule as the single-task edit. |

Worth doing once the grouping settles: the groups are what an action would
apply to, and they are new.

## Several note repositories at once

The settings hold one remote, one branch and one token, and the working copy is
a single directory. Notes are kept in more than one place — a repository on one
git server and another on a different one, work and personal, a shared one and
a private one — and the agenda should be the tasks of all of them together
rather than of whichever one is configured at the moment.

What it takes, in the order the parts depend on each other:

| № | Part                    | What changes                                                                                                                               |
|---|-------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Settings                | A list of sources rather than one triple of URL, branch and token, each with a name; a migration from the single remote already stored.       |
| 2 | Working copies          | One directory per source instead of the single one, and the mutual exclusion that guards the working copy taken per source rather than once. |
| 3 | Scanning                | The agenda scans every source and merges the results, keeping which source a task came from so it can be shown and edited in the right one.  |
| 4 | Synchronisation         | Sync becomes a run over the sources, with a per-source outcome: one failing remote must not hide that the others are up to date.             |
| 5 | The screens             | The source shown beside a task, a filter by source, and settings that add and remove sources rather than editing the one.                    |

Worth doing once the single-repository case is settled: every part above is
work on top of code that is still moving, and the migration has to be written
only once.

## One set of notes on more than one remote

Not the same as the section above. That one is several sets of notes, each in
its own repository; this one is a single set of notes kept on several servers
at once — the same commits on GitHub and on GitLab, so that one host being
unreachable, throttled or gone does not take the notes with it.

Git already models this: one working copy can carry several remotes, and the
commits are the same objects wherever they are pushed. What the application
does not have is anything to drive it — the settings hold one URL, and the
core's sync fetches from and fast-forwards against a single remote.

| № | Part          | What changes                                                                                                                             |
|---|---------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Settings      | A list of remotes for the one checkout, with one of them named as the one to pull from; a token per remote, since they are different hosts. |
| 2 | The core      | `sync_repository` takes the remotes rather than one URL: fetch and fast-forward from the primary, push to each of the others.               |
| 3 | Partial failure | A push that failed to one host while the others went through is not a failed sync — it is a state, and it has to be reported per remote and retried on the next run rather than silently dropped. |
| 4 | Divergence    | Two remotes can hold different histories once a push has failed to one of them for a while. Only fast-forward is on offer today, so the answer is to say which remote is behind rather than to merge. |
| 5 | The screens   | The banner names which remotes are current and which are behind, and the settings add and remove remotes.                                   |

Worth doing together with the section above, or after it: both replace the
single stored triple of URL, branch and token with a list, and writing that
migration twice is the part not worth repeating.

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
