# ADR-0038: A phrase fills the creation screen, and the core is what reads it

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-31).

## Context

Creating a task asks for nine things: a heading, a keyword, a priority, a day,
an hour, a repeater, a note, a collection and a file. A person adding one knows
all of them at once and in one sentence — "позвонить врачу завтра в 15:00,
каждую неделю" — and on a phone that sentence turns into a heading typed with
two thumbs, a date picked out of a dialog, an hour picked out of a clock and a
repeater chosen from a row of chips.

The rules that read such a sentence exist: `refine_entry` in the extractor,
released in 0.20.0, beside the grammar of the timestamps it produces. What was
missing was the crossing — the sentence reaching those rules from a screen, and
the fields coming back.

## Decision

The creation screen carries a phrase field at its head, and a button beside it
hands what was typed to the core. The fields the core names are filled in; the
screen is otherwise the screen it was, and every field stays editable by hand.

The draft travels in both directions. What is handed over is what the screen
currently shows, and what comes back is the refined draft — so a second phrase
refines what the first left rather than starting over, and a field corrected by
hand between two phrases is the field the second one adds to. The accumulating
is the core's: a client merging fields itself would sooner or later disagree
with the rules about which of them a phrase named, and the two clients would
disagree with each other.

Both grammars are consulted whatever language the phone is set to. A phone in
one language is still spoken to in the other, and the cost of consulting both is
a rule set that does not fire.

The reference day is handed in, not read from the clock inside the rules: which
day it is where the phone stands is the caller's to answer, as it already is for
every other date the application writes.

Nothing is written until Create. The phrase fills the screen and the screen is
what writes, so a sentence the rules misread is a screen to correct rather than
a file to put back.

The field is emptied after each phrase: what is said next is a new sentence, not
an edit of the last one.

## Consequences

The client holds no grammar of its own. A phrasing the rules do not know is not
lost, only unsorted — text the rules do not consume becomes the heading, which
on an empty draft is "whatever is left over is what this task is called".

The floor on the core moves to 0.20: the screen calls a function that release
was the first to carry.

A draft carrying a field the rules cannot read back — a date that is not a date,
an hour that is not an hour, a repeater that spells nothing — is refused with
the draft untouched, rather than having those fields quietly dropped. The screen
cannot compose one, since it builds every field out of a picker, but the
boundary does not depend on that being true of every future caller.

Voice is not part of this. Dictation puts text in a field, and the field is
already here; what it needs is the microphone, which is its own decision.

## References

- [ADR-0001](0001-extractor-in-process-over-uniffi.md) — the rules run in this
  process, so reading a phrase costs no round trip.
- [ADR-0011](0011-today-comes-from-the-caller.md) — "tomorrow" means nothing
  without saying tomorrow from when.
- [ADR-0029](0029-an-entry-is-edited-here-a-file-is-not.md) — the screen edits
  an entry; a phrase is one more way of filling it.
- `rust/markdown-org-ffi/src/phrase.rs` — the boundary crossing.
