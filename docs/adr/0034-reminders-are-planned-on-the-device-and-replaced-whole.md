# ADR-0034: Reminders are planned on the device and the plan is replaced whole

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-22). The exact-alarm part is amended by
[ADR-0045](0045-the-minute-is-held-by-a-permission-of-its-own.md):
`USE_EXACT_ALARM` is declared after all. Everything else below stands.

## Context

The agenda says what is coming, and until now saying it required opening the
application. An entry held at an hour is the case where that is not enough: a
meeting at ten is of no use on a screen nobody looked at by half past.

Three things had to be settled before anything could be written.

Where the choices live. A lead time written into the notes — `REMINDER: 30m`
beside the timestamp — would be read by the core, would have to mean the same
in the editor extension, and would travel between devices, announcing a phone's
meeting on a laptop. Nothing is known yet about whether one lead time for
everything is even enough to want the notation.

What is announced. The agenda holds three kinds of dated entry, and only one of
them has a moment: an entry with an hour. A dated entry without an hour and a
deadline inside its warning window belong to a day, not to a minute, and a
notification per entry for those is a morning of notifications.

How the plan is kept true. What is announced comes out of the notes, and the
notes change on other devices: an entry closed on a laptop reaches this phone
at the next fetch, hours after the alarm for it was set. The platform holds
alarms as an opaque list, keyed by the numbers they were scheduled under, and
nothing in it can be asked what an alarm was for.

## Decision

The choices are per device, kept in preferences of their own: whether anything
is announced, how long before a timed entry, whether the hour itself is
announced as well, and the hour of the day's digest. Nothing about reminders is
written into the notes.

An entry held at an hour is announced ahead of it, once, and again at the hour
when the reader asks for both. Everything else the day holds — dated entries
without an hour, deadlines within their warning window, arrears — is announced
once a day, in one digest, whose contents are read at the moment it is raised
rather than when it was planned.

The plan is worked out by a pure function of the agenda, the clock and the
choices, and holds two days. It is made again whenever a note may have moved:
the settings changed, a fetch landed, an entry was edited here, an alarm fired,
the phone restarted, the application was replaced, the clock or the time zone
was set, or the exact-alarm access was granted or withdrawn. Making it again
replaces every alarm held rather than reconciling it against the one before.

The reminders read the agenda through the same index the screen reads, and know
nothing of the view model: what the agenda tells them is that the notes may
have moved.

## Consequences

A reminder is a property of this device. A second phone reading the same
collections announces on its own terms, and neither knows what the other did.
Reinstalling loses the choices with the preferences.

An entry closed elsewhere stops being announced as soon as the fetch lands, and
not before: between the two the alarm stands and can fire. The digest is spared
this because it reads the day it is about when it fires.

The plan is bounded, at sixty-four timed alarms across two days, and every
firing plans again — an entry beyond the bound is picked up once the alarms
ahead of it have gone. A daily repeater over a year costs the two days it falls
in rather than the year.

Announcing at the minute needs an access this application asks for and may not
get. Without it the platform still delivers, within an hour of the time asked
for, which is a reminder for a day and not for a meeting; the settings say so
where the switch is rather than pretending the plan holds. `USE_EXACT_ALARM`,
which is granted without asking, is not declared: the store policy limits it to
alarm and calendar applications.

The work after an alarm arrives is given nine seconds and then abandoned. A
collection large enough to be walked more slowly than that keeps whatever
alarms it had — the notification for the alarm that fired is raised first, and
the next occasion plans again. The alternative is a receiver killed with its
process for overstaying.

Notifications are raised on two channels rather than one, so a digest and a
meeting can be silenced separately. What the reader sets there stands: a
channel's importance cannot be raised from the application once it exists.

## References

- [ADR-0011: Today comes from the caller](0011-today-comes-from-the-caller.md)
- [ADR-0014: The notes are held between calls](0014-the-notes-are-held-between-calls.md)
- [ADR-0022: Several collections, one agenda](0022-several-collections-one-agenda.md)
- [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms)
