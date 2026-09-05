# ADR-0045: The minute a reminder is held to rests on a permission of its own

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-09-05). Amends the exact-alarm part of
[ADR-0034](0034-reminders-are-planned-on-the-device-and-replaced-whole.md):
`USE_EXACT_ALARM` is declared, where that record decided against declaring it.
Everything else ADR-0034 settled — planning on the device, the two-day horizon,
the plan replaced whole, the nine-second budget, the two channels — is
unchanged.

## Context

A reminder set a quarter of an hour ahead was reported as arriving seventeen
minutes ahead. The lead moment is a subtraction of minutes and nothing rounds
it; the alarms the phone held sat exactly a quarter of an hour before their
entries; and measured live, the notification for an entry at 22:40 arrived
within a second of 22:25. The report did not reproduce.

What the measurement did show is worse than the report. Over half an hour, with
no setting touched, the same alarms were exact at 21:55, inexact at 22:09
(`window=+1h0m0s0ms`), and exact again at 22:14. `SCHEDULE_EXACT_ALARM` was not
granted at any point in that half hour. The exactness, when there was any, came
from the phone's exemption from battery optimisation, which the platform
accepts as a reason of its own — the alarms carried `exactAllowReason=allow-listed` —
and withdraws on terms the application is not told. While it is withdrawn,
`canScheduleExactAlarms` answers false, the plan falls back to
`setAndAllowWhileIdle`, and a reminder for a meeting can arrive an hour late.

The settings screen does not catch this. It asks the same question, but asks it
while the screen is drawn rather than while the plan is made, so it reports the
access as held for alarms that were placed without it. A reader whose reminder
is an hour late has nothing on the screen that says why.

ADR-0034 declined `USE_EXACT_ALARM` on the reading that the store policy limits
it to alarm and calendar applications and that this is neither. The reading was
wrong about the application. It shows an agenda, and everything it announces is
an entry of that agenda held at an hour the reader wrote — which is what the
policy describes. The calendar and the clock this phone ships with, and the
third-party calendar on it, all declare that permission and hold it granted.

## Decision

`USE_EXACT_ALARM` is declared. From Android 13 it is granted at install, needs
no asking, and cannot be withdrawn while the application is installed, so the
minute no longer depends on the allowlist or on anything else the phone decides
for its own reasons.

`SCHEDULE_EXACT_ALARM` stays for Android 12, where the newer permission does
not exist, and is capped at `maxSdkVersion="32"` so that only that platform
sees it. What the reader is asked for is therefore the platform's own
distinction and not a choice of ours: nothing on Android 13 and later, the
settings screen's block on Android 12.

Nothing else moves. The settings screen keeps that block and keeps hiding it
wherever the platform answers that exact alarms are held, which from Android 13
it always does. The receiver keeps listening for the permission state changing,
a broadcast only Android 12 sends. `ReminderAlarms` keeps asking for an exact
alarm and falling back to an inexact one, because Android 12 can still refuse.
The digest keeps its hour-wide window: an exact alarm is a ration spent on the
entries a minute matters to.

## Consequences

- A reminder arrives at the minute on Android 13 and later, whatever the phone
  decides about battery optimisation, and stops depending on a grant the
  application cannot observe changing.
- The permission is declared to the store with the use it is put to, at every
  release. A release that fails to declare it is a release whose reminders are
  again an hour wide — the failure is on the listing, not in the code, and
  nothing in the build catches it.
- The permission appears on the store listing and in the system's list for the
  application, without a switch beside it, because there is nothing to switch.
- On Android 12 nothing changes: the reader is asked as before and refusal
  still means an hour-wide window.
- A test reads the manifest, so that neither the declaration nor the cap can be
  dropped by an edit that meant something else.

## References

- [ADR-0034: Reminders are planned on the device and the plan is replaced whole](0034-reminders-are-planned-on-the-device-and-replaced-whole.md)
- [ADR-0035: A reminder is answered where it is read](0035-a-reminder-is-answered-where-it-is-read.md)
- Android developers: "Schedule alarms" — exact alarm permissions and the
  policy for `USE_EXACT_ALARM`
