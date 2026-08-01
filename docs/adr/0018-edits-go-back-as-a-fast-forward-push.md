# ADR-0018: Edits go back to the remote as a fast-forward push, or not at all

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-01).

## Context

[ADR-0006](0006-fast-forward-only-sync.md) left pushing out on purpose: what
the application committed stayed on the device until something else sent it.
Nothing else ever did. An edit made on the phone was written, committed and
then invisible everywhere but here.

That asymmetry does not stay harmless. Every local commit puts the checkout
ahead of the remote, and a remote that then moves leaves the two diverged —
which `sync_repository` refuses. The application would end up unable to fetch
because of edits it made itself and never sent, and the only way out would be
a workstation.

The push itself is the same operation as the fetch in reverse, and the same
rule fits it: hand the remote a fast-forward of its branch, or hand it nothing.
A force push is not among the things a phone should offer — it rewrites history
the user cannot inspect from here.

## Decision

The core gains `push_changes`, and a sync is fetch-then-push.

- The refspec is `refs/heads/<branch>:refs/heads/<branch>` with no leading `+`:
  the remote takes it as a fast-forward or refuses it.
- A refusal is `SyncError::Rejected`, carrying the branch as a field for the
  same reason `Diverged` does. It arrives two ways — libgit2 stopping before it
  sends anything, and the server declining the reference through the push
  callback — and both are reported as the same thing.
- `RepoStatus` gains `unpushed`: commits on the branch that `origin/<branch>`
  does not hold, counted against the remote-tracking reference as the last
  fetch left it. A branch the remote does not have at all counts as wholly
  unpushed.
- Nothing is pushed when `unpushed` is zero, so the call costs a comparison of
  two references on a checkout that is level.
- The Kotlin side reports the two halves separately (`SyncRun`): a push refused
  after a fetch that went through is not a failed sync. The notes did come
  forward, and the banner says what still needs the user.
- The retry rule is the fetch's, unchanged: only a network failure is repeated.
  A refusal needs a fetch first, and repeating it changes nothing.

## Consequences

- An edit made on the phone reaches the remote on the next sync, and a commit
  made with no network is counted in the header until it does.
- The application can now write to a remote. The token was already sent with
  every fetch, so this adds no secret to the wire, but it does mean a
  misconfigured address is offered commits as well as credentials — which is
  why the address guard runs before a push exactly as before a fetch.
- A diverged history still needs a workstation. The push refuses, the fetch
  refuses, and the application says which of the two happened.
- `unpushed` is as fresh as the last fetch. A remote that moved since is not
  known here, and the refusal is what says so.

## References

- `rust/markdown-org-ffi/src/sync.rs` — `push_changes`, `unpushed_on`,
  `SyncError::Rejected`.
- `rust/markdown-org-ffi/tests/sync.rs` — an edit reaching a bare remote, a
  remote that moved on refusing it, and what the count does either way.
- `app/src/main/kotlin/…/core/NotesSync.kt` — `SyncRun` and the order of the
  two halves.
- [ADR-0006](0006-fast-forward-only-sync.md) — the fetch half, and the
  asymmetry this record closes.
