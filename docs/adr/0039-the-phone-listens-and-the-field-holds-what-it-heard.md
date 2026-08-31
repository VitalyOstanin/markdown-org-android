# ADR-0039: The phone does the listening, and the field holds what it heard

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-31).

## Context

A phrase is faster said than typed, and on a phone it is faster by a lot: the
sentence ADR-0038 reads — "позвонить врачу завтра в 15:00, каждую неделю" — is
five seconds of speech and half a minute of thumbs. The field that takes such a
sentence is already on the creation screen; what was missing is a way of
filling it without the keyboard.

Two ways exist. The platform's `SpeechRecognizer` runs recognition inside the
calling application: it needs `RECORD_AUDIO`, a runtime request for it, a
notice explaining why a task manager wants the microphone, and handling of the
half-dozen states a recognition session goes through. The intent
`ACTION_RECOGNIZE_SPEECH` asks whichever application the phone recognises
speech with — the same one the keyboard's microphone key opens — and gets text
back; the microphone belongs to that application, not to this one.

Not every phone has one. An emulator image without the Google application has
nothing to answer the intent, and neither does a phone whose owner removed it.

## Decision

The phrase field gains a button that asks the phone's own recogniser through
`ACTION_RECOGNIZE_SPEECH`, and what comes back is put in the field. This
application asks for no microphone permission and runs no recognition of its
own.

What was heard joins what the field already holds rather than replacing it, so
a sentence can be said in two goes and a word corrected by hand before speaking
again is not lost.

Speaking fills the field and nothing else: the sentence is read into the nine
fields by the same button that reads a typed one. A misheard word is then a
line to correct rather than a form to correct — the text is still in one piece
when it is seen.

The language is not named in the intent. The recogniser then uses the language
the phone is set to, which is the one its owner speaks; naming the screen's
language would refuse Russian on a phone kept in English, and the rules that
read the phrase consult both grammars precisely so that it need not be.

The button is drawn on every phone. Where nothing answers the intent — asked of
the package manager, with the query declared in the manifest — the field says
so and the sentence is typed instead. A button that appears on some phones and
not on others is a difference nothing on the screen explains.

## Consequences

The recogniser is another application, and what it costs — accuracy, a network
round trip, a language pack — is its business and its owner's. This screen
gains no setting for any of it.

Dictation is unavailable on the emulator images the tests run on, so the
screen's half of the exchange is tested against a stand-in recogniser: where
the text lands, what a phone that cannot listen shows, and what a cancelled
attempt leaves behind. Driving a real recogniser would test the phone.

Whether the microphone itself is available — muted, held by a call, denied to
the recogniser — is not asked here. The recogniser answers that in its own
window, and a check on this side would duplicate it.

## References

- [ADR-0038](0038-a-phrase-fills-the-screen-and-the-core-reads-it.md) — the
  field this fills, and the rules that read it.
- [ADR-0001](0001-extractor-in-process-over-uniffi.md) — the reading itself
  happens in this process; only the listening is somebody else's.
- `app/src/main/kotlin/io/github/vitalyostanin/markdownorg/ui/Dictation.kt` —
  the intent and the answer to a phone that has nothing to answer it.
