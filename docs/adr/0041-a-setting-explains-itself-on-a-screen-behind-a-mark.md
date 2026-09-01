# ADR-0041: A setting explains itself on a screen behind a mark, and the obvious ones say nothing

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted, 2026-09-01.

## Context

The settings of a collection are one column of thirty items. What each of them
decides was written in a tooltip held open by a long press on its label: a
gesture nothing on the screen announces, over a line of text sized for a glance.

Two things followed from that. Nothing on the screen said which settings had an
explanation and which had none, so the gesture was tried at random or not at
all. And what would fit was one paragraph — the tooltip carried the line that
used to stand under the control plus, for seven settings, a second line saying
why the answer matters. A question like which branch is fetched, or what an
access token is allowed to do, does not fit in a line and was left half said.

The screen also has a search over it (ADR-0037), and what a setting is for is
what the reader looks for it by: "inbox.md", "token issued by the host". None of
that was searchable, because a tooltip is not text of the item it hangs on.

## Decision

A setting that needs explaining carries a mark beside its name, and the mark
opens a screen of its own: the name of the setting, what it does, why the answer
matters, and one case told with names and numbers.

Three paragraphs rather than one, because a setting is understood from a case
sooner than from a definition. The first is the line the tooltip already carried,
so nothing written for the tooltip is written twice; the other two are new.

Twenty of the thirty items carry the mark. The ten that do not — the button that
opens the system picker, the tick that forgets a stored key, which weekday a week
starts on, the line naming the build — are ones whose label is the whole answer,
and three paragraphs there would say what the label already says. They keep the
tooltip they had, where they had one: a short line is worth keeping for a setting
that is read at a glance, and there is nothing to replace it with.

The explanations are a list apart from the screen, `settingHelp` in
`ui/SettingHelp.kt`, keyed by the same tags the search catalogue uses. An item is
therefore explained, searched and drawn under one name, and the mark is drawn
only where the list has an entry: a mark on the screen is a promise that
something opens.

The screen is drawn over the form rather than in place of it. The fields are
typed into and not yet saved, and a screen that took their place would take what
was typed with it.

What the screen says is searched as text of the item it belongs to. A reader who
remembers the case and not the label finds the setting by the case.

## Consequences

Every explanation is four string resources in two languages, and a setting added
to the screen has to be given one or listed among the ones that answer for
themselves — `SettingHelpTest` holds the two lists against each other and fails
either way.

The screen keeps two ways of asking what a setting is for: a mark for twenty of
them, a long press for the rest. That is the price of not writing three
paragraphs about a weekday. The mark and the tooltip are never both on one label.

The agenda is untouched. A long press there says what a row or a button does,
where the answer is one line and the screen has no room for anything else.

## References

- [ADR-0037: The settings are searched by filtering the screen they are on](0037-the-settings-are-searched-by-filtering-the-screen-they-are-on.md)
- [ADR-0036: Where an entry is written is the collection's to say](0036-where-an-entry-is-written-is-the-collections-to-say.md)
