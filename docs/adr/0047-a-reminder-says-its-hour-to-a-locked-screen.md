# ADR-0047: A reminder says its hour to a locked screen, not its entry

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-09-07).

## Context

A reminder is raised with the heading of the entry as its title, and the
morning digest carries the first few headings of the day in its expanded text.
Both are read off the reader's own notes, which are what this application is
for: a heading can be anything a person writes down about their own life.

Where those headings are shown is the platform's to decide, and the decision
turns on two values. A notification carries a `visibility`, and the reader
chooses in the system settings whether a locked screen shows notifications in
full, hides what is marked private, or shows none of them. `Notification.Builder`
builds with `VISIBILITY_PRIVATE` already — nothing in this application ever set
it — so a reader who asked the phone to hide private content is not being shown
the headings today. What such a reader is shown instead is a line the platform
writes itself, saying that the content is hidden. It says nothing about when
the entry is, and a reminder that cannot say that is a reminder that has to be
unlocked to be read.

The other end of the range is worth naming as well. `VISIBILITY_SECRET` keeps
the notification off a secure lock screen entirely, and a reminder nobody sees
until the phone is unlocked has stopped doing the one thing it was raised for:
an entry announced a quarter of an hour ahead is announced so it can be
answered within that quarter of an hour, at a glance.

Nothing in the repository recorded any of this. The visibility was inherited
rather than chosen, and an edit that named a different value would have changed
what a locked screen shows without anything in the tree disagreeing.

## Decision

Every notification this application raises is private, said out loud rather
than inherited, and carries a public version of itself.

The public version names no entry. A timed reminder says the hour it is about
-- "Starts at 15:00" under a title that reads "A reminder" -- and the digest
says the counts it is made of, which are numbers of entries rather than any of
them. Neither carries the buttons: an answer is given from a screen the reader
has already unlocked. The expanded list of headings belongs to the private
notification alone.

The choice of what a locked screen shows stays where the platform put it, with
the reader and their phone. This application adds no setting of its own: the
system's is per phone rather than per application, it is where a reader who
cares about this has already been, and a second switch beside it would have to
explain which of the two wins.

## Consequences

- A reader whose phone hides private notifications sees the hour of a reminder
  and the size of a day, and no heading, without unlocking.
- A reader whose phone shows notifications in full sees what they saw before:
  this record changes nothing for them, because the choice is theirs.
- Every path that raises a notification has to say what a locked screen may
  show: the pair is a parameter without a default, so a path added later
  cannot leave it out and quietly fall back to the platform's line.
- The screen-sharing case comes with it: the platform conceals a private
  notification while the screen is shared, by the same value.
- An instrumented test reads the visibility and the public version back off
  the raised notification, so neither can be dropped by an edit meaning
  something else.

## References

- [ADR-0034: Reminders are planned on the device and the plan is replaced whole](0034-reminders-are-planned-on-the-device-and-replaced-whole.md)
- [ADR-0035: A reminder is answered where it is read](0035-a-reminder-is-answered-where-it-is-read.md)
- Android developers: "Notifications" -- lock screen visibility and public
  versions
