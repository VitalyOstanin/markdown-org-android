# ADR-0019: The directory holds the notes, and git is added to it

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-01).

## Context

The notes directory was modelled as the working copy of a remote. Everything
about it followed from that: the core clones into an empty directory, so saving
an address emptied the directory first, and a directory with no address was
"not configured yet" rather than a place notes are kept.

Half of local storage worked by accident. Until an address was given, the
directory was an ordinary directory of files, the agenda read it, and edits
were written to it — with no repository, so nothing was committed and nothing
was ever lost. The moment an address was saved, the same directory was emptied
to make room for the clone, and whatever had been written there was gone.

Two states were being conflated: a directory nobody has said anything about,
and a directory whose notes are meant to stay on this device. The first is what
a first launch looks like and is worth an invitation to configure a remote; the
second is a decision, and repeating the invitation over it is noise.

There is also no way back once a directory is a checkout of somewhere else.
Pointing the application at another address is a legitimate thing to want, and
it does mean replacing what is on disk — but it is a different act from filling
in a form.

## Decision

The directory is where the notes live. A repository is something that is added
to it, and adding one never destroys what is there.

- The core gains `adopt_directory`: `git init` over the directory as it stands,
  one commit of whatever it holds, `remote add`, then fetch. Its outcome is
  `Adoption`, with three cases — `Published` (the remote was empty and has been
  handed the notes), `Took` (this side had none and the remote's are checked
  out), `Unrelated` (both sides hold notes and share no history).
- An empty tree with no parent is not committed. An empty directory has nothing
  of its own, so it takes the remote's notes — which is what a clone did.
- `Unrelated` is a question, not a failure. Nothing is written over while it
  stands, and the answer is `take_remote_notes`: the remote's notes are checked
  out and the local commit is kept as the branch `notes-kept-on-this-device`.
  Keeping it costs nothing and any git client can read it.
- A directory that is already a working copy of another address is refused. The
  application does not add a second `origin` over someone else's repository.
- Emptying the directory is its own action — `replaceNotes` — reached by a
  button next to the message that says the directory holds another checkout. It
  is never a side effect of saving the form.
- Keeping the notes on this device is stored as a choice of its own
  (`storesLocally`), distinct from "no address given". A settled state is
  either of the two, and the interface stops asking for an address once one of
  them holds.

## Consequences

- Notes written before a remote was configured survive the configuring, and
  become the first commit of the repository rather than something to restore
  from a backup.
- The one destructive operation on the notes is behind a press that says what
  it does, and the form no longer has a destructive effect at all.
- A device with notes and a server with notes can be reconciled without a
  workstation in the simple cases, and in the case that needs a decision the
  device says so and offers the decision.
- `notes-kept-on-this-device` accumulates if the answer is given more than
  once; each answer keeps its own commit under a name the user can find. The
  application does not delete these, and never reads them back.
- The first launch can end without any address at all, which is the state a
  user who only wants a to-do list on the phone is in.

## References

- `rust/markdown-org-ffi/src/sync.rs` — `adopt_directory`, `take_remote_notes`,
  `Adoption`, `KEPT_BRANCH`.
- `rust/markdown-org-ffi/tests/sync.rs` — a directory of notes becoming a
  checkout, an empty one taking the remote's, and what unrelated histories do.
- `app/src/main/kotlin/…/ui/AgendaViewModel.kt` — where saving the form now
  leads, and `replaceNotes` as the only thing that empties the directory.
- [ADR-0006](0006-fast-forward-only-sync.md) and
  [ADR-0018](0018-edits-go-back-as-a-fast-forward-push.md) — the two halves of
  a sync, which this record leaves as they are.
