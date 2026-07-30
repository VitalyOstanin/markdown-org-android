# ADR-0008: The agenda uses a palette of its own, not dynamic color

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

Material 3 on Android 12 and later offers dynamic color: the scheme is derived
from the user's wallpaper. It makes an application look native, and it takes
the choice of hue away from the application.

This agenda spends that choice. A row's colour says what state a task is in —
overdue, due today, scheduled, done, cancelled — and priority is a second
signal on top. Those distinctions have to hold in both light and dark, and
they have to stay distinguishable from each other; a scheme derived from a
wallpaper can collapse two of them into neighbouring shades of one hue, and
there is nothing the application can do about it at runtime.

## Decision

The theme declares its own light and dark schemes from a fixed palette, and
does not call `dynamicLightColorScheme` / `dynamicDarkColorScheme`. Status
colours are held apart from the Material scheme in a composition local of the
project's own, so a row's meaning is not read out of `primary`/`secondary`
by accident.

The palette is deliberately colourful rather than a single hue with tints:
distinct hues are what make the states tell apart at a glance.

## Consequences

- The agenda looks the same on every device, and a screenshot in the README is
  what a user sees.
- The application does not follow the system accent, which some users expect.
- Contrast is this project's responsibility: the palette is checked against
  WCAG contrast rather than inherited from the platform.

## References

- `app/src/main/kotlin/…/ui/theme/Theme.kt` — the two schemes.
- `app/src/main/kotlin/…/ui/theme/AgendaColors.kt` — the status colours and the
  composition local carrying them.
