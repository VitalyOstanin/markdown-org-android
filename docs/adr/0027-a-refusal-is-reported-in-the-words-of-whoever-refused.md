# ADR-0027: A refusal is reported in the words of whoever refused

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-18).

## Context

`SyncError::Rejected` covered two different events under one name. One is the
branch here being behind the branch on the remote: libgit2 compares what the
server advertises against what is here and stops before sending anything. The
other is the server itself declining what was sent — a protected branch, a
pre-receive hook, a key or a token without write.

Only the first was worded, and that wording was shown for both: "origin/master
holds commits this device does not; sync again to take them first". Against the
second it is false in every clause, and it sends the reader to do the one thing
that cannot help. A phone whose token lacked the right to push to a protected
branch was told to sync again, did so three times over an hour, and got the
same sentence each time; the actual reason — `pre-receive hook declined`, and
beside it the server's own "you are not allowed to push code to protected
branches on this project" — was reachable only by reading the device log over
a cable.

The server's explanation is not in the refusal at all. What comes back with the
reference is a phrase from the protocol; the sentence a person can act on
arrives on the side channel, the same one that carries a fetch's progress, and
nothing here was listening to it.

The banner's second line also cut its text at two lines with no ellipsis, so
even a sentence worth reading arrived as a fragment ending mid-clause. Two
lines fit the English strings the limit was measured against; the Russian ones
are about a third longer.

## Decision

The core says which of the two refusals it met. `Rejected` carries `stale`:
true when the branch here is behind and another sync answers it, false when the
server declined. What the server wrote on the side channel is collected and
joined to the status, so `detail` is the mechanism and the reason together.

The banner words the first case and quotes the second. Prose written in this
project is worded here, over the branch name, in the reader's language;
whatever a server or a library wrote is shown as it arrived, in the language it
arrived in, because no sentence written here knows what it says.

A detail worded here is drawn in as many lines as it takes, and a verbatim one
keeps its two. Both are ellipsised, so a cut is visible as a cut.

## Consequences

- A refusal the user can act on states what to do, and one they cannot names
  who refused and why. Neither is dressed as the other.
- The banner grows by a line or two when a server is talkative. That is the
  size of the reason; a banner that hides it costs a cable and a log reader.
- Server text is not translated, so a Russian interface can show an English
  sentence. It is the server's sentence: translating it would mean guessing at
  what a hook meant.
- `stale` is a field of the FFI surface, so the two clients must both read it
  to word the two cases apart.

## References

- `rust/markdown-org-ffi/src/sync.rs` — `SyncError::Rejected`, the side-channel
  callback and `told`, which joins the status to what the server said.
- `app/src/main/kotlin/.../ui/SyncUiState.kt` — which of the two the banner
  words and which it quotes.
- `app/src/main/kotlin/.../ui/AgendaScreen.kt` — how many lines a detail gets.
- `app/src/androidTest/kotlin/.../core/NotesSyncRoundTripTest.kt` — the
  exchange in both directions, and the refusal a fetch answers.
