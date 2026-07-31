# ADR-0017: A repository is opened whoever the platform says owns its directory

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-31). Amends
[ADR-0013](0013-notes-directory-by-path.md), which made the notes directory a
path that may lie outside the application's storage.

## Context

A notes directory on the shared storage is read and written without trouble,
and every git operation on it is refused:

```
repository path '/storage/emulated/0/Documents/notes' is not owned by current user
```

libgit2 compares the owner of the working directory and of `.git` against the
current user, and returns `GIT_EOWNER` when they differ
(`validate_ownership`, `src/libgit2/repository.c`). Under
`/storage/emulated/0` they always differ: those files reach an application
through a layer that reports an owner of its own, not the uid of whoever is
reading. The comparison therefore refuses every directory outside the
application's own storage, whatever is in it and whoever put it there — and
with it the clone, the fast-forward and the commit that follows an edit. What
the user sees is a note that was written and not committed, and a checkout
that is dirty from then on, which
[ADR-0006](0006-fast-forward-only-sync.md) makes a refusal to sync. The
directory chosen in ADR-0013 was, in effect, read-only as far as git is
concerned.

Three ways out were considered.

**Turn the check off.** One setting, `GIT_OPT_SET_OWNER_VALIDATION`, applied
once per process.

**Name the directory as safe.** The equivalent of `git config --global --add
safe.directory <path>`: libgit2 reads `safe.directory` out of the global
configuration and accepts a directory listed there
(`validate_ownership_config`). It means pointing libgit2 at a configuration
file of the application's own (`GIT_OPT_SET_SEARCH_PATH`) and rewriting it
whenever the directory changes. The set it would allow is the same one
directory the application opens anyway — nothing walks into a repository the
user did not name — so the two options differ in cost, not in what they
permit. The cost is not only the file: the comparison is by string against a
path libgit2 has resolved, so a directory entered as `/sdcard/Documents/notes`
is matched as `/storage/emulated/0/Documents/notes` and an entry written from
what was typed does not match at all.

**Refuse git outside the application's storage.** Honest, and it takes the
main reason for choosing a directory away: notes already on the device,
synced with a computer through git.

What the check defends against is a repository left by another user of a
shared machine, whose `.git/config` git reads and runs a command out of —
`core.pager`, `core.sshCommand`. Neither half of that applies here. libgit2
runs nothing from a configuration file: it creates `hooks/` and never executes
anything in it, has no external clean/smudge filters, and the single place in
the library that starts a process is the ssh transport, which this build does
not compile in — `git2` is taken with `https` alone
([ADR-0005](0005-vendored-tls-and-libgit2.md)). And an Android application has
the device to itself as far as uids go: the shared storage is reached only
through a permission granted by hand, and the directory is the one the user
pointed at.

## Decision

The core turns the owner check off, once per process, before it opens a
repository.

- `open_directories_owned_by_the_platform` in `sync.rs` applies
  `set_verify_owner_validation(false)` under a lock, the way the network
  timeouts are applied. Every entry point that opens a repository goes through
  `open`, and the clone applies it too.
- The setting is not turned back on. It is global to libgit2, and there is no
  point in the process at which restoring it would mean anything: nothing else
  in the process uses the library.
- The state is covered on both sides. `rust/markdown-org-ffi/tests/owner.rs`
  hands a repository's directory to another user and checks that libgit2
  refuses it and the core opens it — in a file of its own, because the setting
  is global and the refusal can only be seen before it is applied. On a device,
  `SharedStorageRepositoryTest` puts a checkout under `Documents`, taking all
  files access through the instrumentation, and commits into it; that the
  directory is not owned by the test process is stated as an assumption, since
  it is a property of the storage rather than of this project. The
  application's own directory on that storage is no substitute — it is reported
  as owned by the application and never produced the refusal.

## Consequences

- A notes directory on the shared storage can be cloned into, fast-forwarded
  and committed to. The feature ADR-0013 describes works as it is written
  there.
- The check is off for the directory inside the application's own storage as
  well. The owner has always matched there, so nothing changes; what is lost
  is the guard against a future in which it stops matching.
- A `.git` somebody else leaves in the notes directory is opened, and the
  `origin` in it is the one a sync fetches from. Nothing of the user's goes to
  it — the token is only ever offered to the address in the settings — and
  writing into that directory takes the same all-files access that would let
  the notes be rewritten outright. This is accepted rather than solved.
- An unreadable or damaged repository still fails, and says so. Only the owner
  comparison is gone; everything else libgit2 checks when it opens a
  repository stands.

## References

- `rust/markdown-org-ffi/src/sync.rs` — `open`,
  `open_directories_owned_by_the_platform`.
- `rust/markdown-org-ffi/tests/owner.rs` — the refusal and its absence, on the
  host.
- `app/src/androidTest/kotlin/…/core/SharedStorageRepositoryTest.kt` — a
  checkout on the shared storage of a device.
- [`git_libgit2_opts`](https://libgit2.org/docs/reference/main/common/git_libgit2_opts.html)
  — `GIT_OPT_SET_OWNER_VALIDATION` and what it governs.
- [`safe.directory`](https://git-scm.com/docs/git-config#Documentation/git-config.txt-safedirectory)
  — the escape git itself offers from the same check.
