# ADR-0023: A collection is a dot at the head of the row

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-02). Replaces the mark described in
[ADR-0022](0022-several-collections-one-agenda.md); the rest of that decision
stands.

## Context

ADR-0022 put the mark at the end of the row: a coloured dot and the
collection's name, after the heading had taken its width.

On a phone held upright there is no such width. With a second collection
added, the agenda read

```
↺ Английский в…       ● Заметки  27.07  6 дней
⊙ Записаться на выходны…  ● Заметки  02.03
```

— every heading cut where it stopped saying anything, and the same word
repeated down the whole screen. The name was worth its width when a row was
the only place a collection was named; it is not, now that a chip per
collection sits above the list.

## Decision

The mark is a dot, in the collection's colour, at the head of the row —
before the glyph that says what kind of entry it is. No name beside it.

- The name stays the mark's spoken description, so a screen reader still says
  which collection a row came from where the colour says nothing.
- The names are read against the chips above the list, which carry them in
  the same colours.
- The dots line up in a column down the left edge, which is what makes a
  collection legible at a glance rather than a label to be read row by row.
- A row filled with a solid tone — an overdue one — takes its dot from the
  palette of the other theme rather than from the row's text colour. The fill
  inverts the lightness of the surface, so the palette of the theme goes flat
  on it; one colour for every collection there would leave the overdue rows,
  which are most of a screen, saying nothing about where they came from. Every
  tone of both palettes clears 3:1 against what it sits on.
- Nothing changes for a device with one collection: no marks, no chips, the
  screen it always had.

## Consequences

The heading gets the width back — the width it had before collections
existed, less a dot and a gap.

Two collections in colours a reader cannot tell apart are now harder to
separate, since the row no longer spells the name out. The palette is six
tones picked to differ in both themes, and the chips remain a way to check;
a reader who needs certainty can turn a collection off and see what leaves.

A row says less on its own: a screenshot of the agenda no longer carries the
names of the collections in it.

## References

- [ADR-0022](0022-several-collections-one-agenda.md) — several collections,
  one agenda
- `CollectionMark` in `ui/AgendaCommon.kt`
- `AgendaColors` in `ui/theme/AgendaColors.kt` — the two palettes and which
  surface each is read on
- `CollectionMarksTest` — the marks, and a device with one collection
- `AgendaPaletteTest` — the contrast of every tone against what it sits on
