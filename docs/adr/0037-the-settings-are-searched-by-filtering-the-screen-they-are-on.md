# ADR-0037: The settings are searched by filtering the screen they are on

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-31).

## Context

The settings of a collection are one scrolling column: what the collection is
called, where it is fetched from, what reaches the server, where the notes are
kept and which file receives a task, how the agenda is drawn, what the reader is
told is coming, and what a report about this build would carry. Thirty items
across seven stretches of screen, four of which carry a heading and three of
which do not.

Finding one of them means knowing which stretch it is in and scrolling to it.
That knowledge is what the reader who is looking for a setting does not have —
the setting is remembered by what it does ("the week starts on Monday", "the
file new tasks go into"), and where it stands on the screen is exactly what has
been forgotten.

Two other shapes were considered. A list of results replacing the screen would
mean a second way of drawing everything, a scroll-to-position and a highlight
to get back to the setting itself, and no way to change the setting from the
results. Scrolling to the first match without hiding anything is the least work,
but it leaves the rest of the column around what was found, which is the problem
being solved.

## Decision

A field at the head of the settings screen filters the screen itself. What is
typed is compared against what the reader sees of each item — its label, the
line under it, the labels of the chips it offers — and the items that do not
answer are not drawn, headings of emptied stretches included. An empty field is
the screen as it always was, so the reader who never types in it sees no change.

A heading is searched like any other text, and a query naming one carries the
whole stretch under it: asking for "reminders" is asking for all of them.

Matching folds case and treats `ё` as `е`; which way a resource spells that
letter is not something to have to guess at while typing.

What can be searched is a list — `settingsCatalogue` — held apart from the
composables that draw the screen, naming each item by its tag, the stretch it
stands in, and the string resources it is read by. A heading is drawn before the
items under it, so whether anything inside a stretch survived the query has to
be answerable before that stretch starts drawing, which a per-composable flag
cannot do.

The match travels as a `CompositionLocal` rather than as a parameter: it is one
answer the whole screen reads, and threading it by hand would add an argument to
sections that already carry a dozen, and to the sections nested inside those.

Values are not searched — the address of a repository, the name of a file, the
branch. A private key and a token stand on this screen, and a search that reads
what is typed into fields would have to carve out exceptions for them; the
labels and their explanations are what the reader is looking for anyway.

## Consequences

An item added to the screen and left out of the catalogue is an item the search
never offers, and the screen gives no sign of it. Two tests hold this together:
one in the JVM suite checks that every entry is reachable by its own label and
that no stretch is empty, and one on the device checks the tags against the
screen itself.

The tooltip behind a long press is not searched. It is the longest text on the
screen and the least often read; putting it in would make a query match items
whose visible words say nothing of the kind.

The fields hidden by a query are hidden, not emptied: the form's state stands
apart from what is drawn, so Save under an active query saves everything that
was typed before it, including into fields the query hid.

The SSH section, which is folded away under its heading, opens while a query is
active: what was found is behind the fold, and a heading is not an answer to
having searched for the field under it.

The title of the screen and the paragraph under it are dropped while a query is
typed. They say what the screen as a whole is for, which is not an answer to a
query.

## References

- [ADR-0022](0022-several-collections-one-agenda.md) — the settings are a
  collection's, which is what makes the screen as long as it is.
- `app/src/main/kotlin/io/github/vitalyostanin/markdownorg/ui/SettingsSearch.kt`
  — the catalogue, the match and the field.
