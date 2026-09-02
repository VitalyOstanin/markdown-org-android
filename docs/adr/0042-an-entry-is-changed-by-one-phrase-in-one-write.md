# ADR-0042: An entry is changed by one phrase, in one write

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-09-02).

## Context

[ADR-0038](0038-a-phrase-fills-the-screen-and-the-core-reads-it.md) has a
sentence fill the creation screen. Changing an entry that already exists went
the other way: the sheet a tap on a row opens offers a button per field —
complete, cancel, reopen, a priority chip, two rows of date actions, an hour.

One change is one tap, which is short enough. Three changes are three taps and
two dialogs of choice, and each of them writes the file, commits it and adds a
line to the undo. The sentence naming all three is one the core can now read:
0.21.0 of the extractor answers with a keyword and with the fields a phrase
said to empty, beside the fields it filled.

Two things had to be decided here. Where the phrase goes on the screen — the
sheet is opened over the row it is about, so the entry is already chosen. And
what the phone does with a sentence the rules did not consume in full: for a
new entry the leftover becomes the heading, and an entry that exists has one
already.

## Decision

The sheet begins with a field and a microphone, above the buttons.

What is typed or heard goes to the core as one phrase, and what the core makes
of it is applied by one bridge operation, `apply_phrase`. One operation rather
than one per field: the whole sentence is one write, one commit and one line of
undo, where `set_status`, `set_priority` and `set_planning` in turn would be
three of each and would leave a half-applied entry behind if one of them
failed.

The operations underneath are the ones the buttons use — the same rewriting of
the heading and of the planning line — so a phrase cannot write a line no
button could.

A phrase the rules did not consume in full changes nothing, and the leftover is
named on screen. So is a phrase that named no field, and one whose hour has no
day to stand on.

Above the buttons rather than below them, because it can say what several of
them say at once; the buttons stay for the single change, which is one tap and
needs no sentence.

## Consequences

Three changes are one sentence, and one entry in the history. The buttons are
untouched: a task marked done is still one tap, and a phrase for that would be
the longer way round.

The refusal is strict — one unknown word and nothing is written. The
alternative is applying the half that was understood, and then the person has
to notice which field moved and which did not. What was not understood is
named, so the answer is to say it again in words the rules know.

`PhraseDraft` gains the keyword and the emptied fields, which is what makes the
draft usable for an edit as well as for a new entry. The screen that creates
one hands both over empty: a new entry's keyword is `TODO` whatever the phrase
says, and a field of an entry that does not exist yet cannot be emptied.

The pinned core moves to 0.21.0. The two new fields come from there, and an
older core has no `apply_phrase` at all.

## References

- [ADR-0038](0038-a-phrase-fills-the-screen-and-the-core-reads-it.md) — the
  phrase that fills the creation screen, and why the rules live in the core.
- [ADR-0039](0039-the-phone-listens-and-the-field-holds-what-it-heard.md) — the
  microphone this field reuses.
- ADR-0037 of `markdown-org-extract` — the keyword and the emptied fields the
  rules now answer with.
