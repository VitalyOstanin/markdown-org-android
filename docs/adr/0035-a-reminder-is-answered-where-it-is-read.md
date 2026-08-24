# ADR-0035: A reminder is answered where it is read

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-24).

## Context

ADR-0034 settled what is announced and when. What it left out was what the
reader can do about it. A reminder said its piece and opened the agenda as it
stood: the day it was about had to be found by hand, and closing the entry it
named took a tap to open, a tap to find, and a sheet.

Three things were left over, and each has a platform limit behind it.

Where a tap goes. The screen is one activity with no addresses inside it, so a
notification could not name a day, let alone an entry within it. The agenda has
had `showDay` all along; what was missing was a way to hand it an entry, since
a heading names nothing — two notes can carry the same words, and a repeating
entry carries its own in every day it falls on.

What a button can do. Closing an entry is a write through the core: the working
copy is opened, the note is edited, the change is committed. A broadcast
receiver is given nine seconds and is killed along with its process if it
overstays, which is not a budget a commit fits into on storage that is slow or
a collection that is large. Putting one off, by contrast, is an alarm and
nothing else.

How the digest is held. Exact alarms are a ration the platform counts and a
permission the reader may withhold; the digest asks for an hour of the day,
which is a request for "around nine" rather than for nine exactly. Holding it
among the exact ones spent part of that ration on the one reminder that does
not need it.

## Decision

A notification carries the day it is about, and the timed one carries the entry
within it. The address is the file, the line and the collection — what the core
hands out and what an edit is aimed at — packed as extras of an intent naming
the activity, marked single top so a screen already open is handed the address
rather than replaced. The agenda holds the address until a day containing it
has been read, then picks the entry out; an entry that has since moved or been
closed leaves nothing selected, and the address is dropped either way, so a
rotation does not reopen what the reader has shut.

A timed reminder carries two buttons. Later re-arms the alarm a quarter of an
hour ahead and touches no file. It is held aside from the plan, under numbers
above the ones the plan uses: the plan is replaced whole whenever the notes may
have moved (ADR-0034), and a reminder pushed to later is not in the notes at
all — held among the plan it would be dropped by the next fetch.

Done closes the entry through the core, from a short foreground service rather
than from the receiver. The platform allows such a service to be started in
answer to a notification the reader has just pressed, its type needs no
permission of its own, and its limit is minutes rather than seconds. The
service reads the agenda again before writing: hours can separate the plan from
the press, and the entry may have been closed on another device in between.

The digest is never an exact alarm, whatever the access allows. Entries held at
a minute keep the ration; the digest is delivered within the platform's window
of the hour the reader chose.

## Consequences

A reminder is now a thing to answer rather than only to read. Closing an entry
from the drawer is one press, and the repeater moves the way it does from the
sheet, because it is the same call into the core.

The service is visible while it runs — the platform requires it — so a second
notification appears for a second or two on a channel of its own, at the lowest
importance a foreground service is allowed. A reader who finds it intrusive can
silence that channel without silencing reminders.

Two behaviours where there was one: entries are exact when the access allows,
the digest never is. This is the cost of not spending the ration on it, and the
settings screen already says what an entry loses when the access is refused.

Reminders held aside are not counted among the plan and are not cancelled with
it. Switching reminders off cancels the plan and takes back the notifications
raised, but a reminder already pushed to later will still fire once. It is one
alarm, and the notification it raises is the one the reader asked to see again.

## References

- ADR-0034: Reminders are planned on the device and the plan is replaced whole
- Android developers, "Foreground service types": `shortService` needs no
  permission of its own
- Android developers, "Restrictions on starting a foreground service from the
  background": starting one by interacting with a notification is exempt
