# TODO

Work that is understood but deliberately not done yet.

## Table of contents

- [How long ago is "long ago"](#how-long-ago-is-long-ago)
- [One set of notes on more than one remote](#one-set-of-notes-on-more-than-one-remote)
- [Weekday names beyond Russian and English](#weekday-names-beyond-russian-and-english)
- [Tooltips beyond the agenda screen](#tooltips-beyond-the-agenda-screen)
- [Unicode normalisation when a heading can be typed](#unicode-normalisation-when-a-heading-can-be-typed)
- [Telling the reader what is coming](#telling-the-reader-what-is-coming)
- [Moving one occurrence of a repeating entry](#moving-one-occurrence-of-a-repeating-entry)
- [Publishing to the app stores](#publishing-to-the-app-stores)

## How long ago is "long ago"

The overdue backlog is cut at two fixed distances. A week tells this week's
slippage from the rest, and a year tells the rest from what is long gone
(`LONG_AGO_DAYS = 365L` in `ui/AgendaUiState.kt`, and the band the collapse
state folds first). Neither distance is a setting, and neither answer suits
everyone: a plan gone over every week wants the last band to start at a month,
while a backlog carried for years wants it later than a year.

Deciding the number is the small part. What the setting has to settle first:
whether both boundaries move or only the far one, whether a band can be turned
off rather than only folded, and what a band is called once its name stops
describing its span — "Overdue earlier this year" is wrong the moment the
boundary is three months.

The same split is drawn by the editor extension, and the bands are what a whole
backlog is answered through, so a boundary that differs between the two clients
would put one task in different bands depending on where it is read. The shape
is worth agreeing on across both before either implements it; a stored setting
is per device and does not travel with the notes, which is another thing to
decide — a threshold could equally live in `.markdown-org/` beside the tags and
be shared the way they are.

## One set of notes on more than one remote

Not the same as the collections the application already has. Those are several
sets of notes, each in its own repository and its own directory; this one is a
single set of notes kept on several servers at once — the same commits on
GitHub and on GitLab, so that one host being unreachable, throttled or gone
does not take the notes with it.

Git already models this: one working copy can carry several remotes, and the
commits are the same objects wherever they are pushed. What the application
does not have is anything to drive it — the settings hold one URL, and the
core's sync fetches from and fast-forwards against a single remote.

| № | Part          | What changes                                                                                                                             |
|---|---------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Settings      | A list of remotes for the one checkout, with one of them named as the one to pull from; a token per remote, since they are different hosts. |
| 2 | The core      | `sync_repository` takes the remotes rather than one URL: fetch and fast-forward from the primary, push to each of the others.               |
| 3 | Partial failure | A push that failed to one host while the others went through is not a failed sync — it is a state, and it has to be reported per remote and retried on the next run rather than silently dropped. |
| 4 | Divergence    | Two remotes can hold different histories once a push has failed to one of them for a while. Only fast-forward is on offer today, so the answer is to say which remote is behind rather than to merge. |
| 5 | The screens   | The banner names which remotes are current and which are behind, and the settings add and remove remotes.                                   |

Worth doing together with the section above, or after it: both replace the
single stored triple of URL, branch and token with a list, and writing that
migration twice is the part not worth repeating.

## Weekday names beyond Russian and English

Moving a date rewrites the weekday token to match, in the language the file
already uses. The two languages the ecosystem knows are Russian and English
(`SUPPORTED_LOCALES` in the extractor), and `weekday_like` in
`rust/markdown-org-ffi/src/planning.rs` refuses anything else with
`Unsupported` rather than replacing, say, Ukrainian `Нд` with Russian `Вс`.

For a file written in a third language that means its dates cannot be moved
from the phone at all. Adding one is a table in the extractor's `locale`
module plus the matching entry in `SUPPORTED_LOCALES`, so that reading and
writing agree; doing it here alone would let the application write names the
extractor cannot read back.

## Tooltips beyond the agenda screen

Every screen now carries them, and what is left is the wording rather than the
mechanism.

The agenda screen: the header icons, the layout switch, the collection chips,
the sync banner and the two lines under it, the scan notices, the band menu
and each of its items; the row tooltip names the collection the dot stands
for. The sheet of actions: what each action writes into the note — the
keyword, the cookie, the date and what stays as written. The settings screen:
every button, both checkboxes, the heading of the collections and the two
lines at the bottom; the fields answer with a line under them instead, which
is readable without a press. The licence screen: the card and the way back.
The entry editor and the screen that writes a new task, both added on
2026-08-19: the two buttons of each bar, and — on the creator — the heading of
every row of chips, which is where what a choice puts into the file is stated;
their fields answer with a line under them, for the reason the settings fields
do. A long press inside a text field belongs to selecting text, and a tooltip
there would take that gesture away.

Wording follows the VS Code extension where the two show the same thing, so
the same task reads alike on the phone and in the editor.

Two rules the layout imposes, both learnt from a test that failed:

| № | Rule                                                                                                     |
|---|-----------------------------------------------------------------------------------------------------------|
| 1 | A tag belongs on the tooltip rather than on what it wraps — the box merges the semantics of its content, and a `testTag` on a bare `Text` inside stops existing in the merged tree. A control with a semantics node of its own — a button, a chip — keeps its tag either way. |
| 2 | A weight must not be handed to the tooltip: the row measures its own children, and the box is not the button. Two buttons sharing a row are wrapped as a row, not one by one — the second one went off the screen otherwise. |

## Unicode normalisation when a heading can be typed

`Document::heading` compares the heading in the file with the one the caller
believes is there byte for byte. Today both come from the same scan and pass
through Kotlin without loss, so a NFC/NFD difference cannot arise.

A heading can be typed now — the entry editor of
[ADR-0029](docs/adr/0029-an-entry-is-edited-here-a-file-is-not.md) writes what
the keyboard hands over, and a system keyboard hands over decomposed
diacritics. Nothing is refused by it yet: the agenda re-reads the file it just
wrote, so both sides of the comparison keep coming from the same bytes. What it
does produce is a note that differs from the one another client wrote while
looking identical, and a `Stale` refusal as soon as the two forms meet — a
heading typed here against a task listed from a scan taken before.

The answer is the same as it was: normalise to NFC on the way in and compare
after normalising (`unicode-normalization`). The cost is a dependency, and with
it the licence notices the APK carries, which is why it is written down here
rather than done alongside the editor.

## Telling the reader what is coming

The agenda answers only while it is open. A note that says 15:00 is of use if
something says so at 14:45, and nothing does: the application holds no
notification, no channel and no alarm, and the manifest asks for neither
`POST_NOTIFICATIONS` nor `RECEIVE_BOOT_COMPLETED`.

### What a reminder means, per kind of entry

The notes carry three kinds of dated entry, and one rule does not fit them.

| № | Kind                                                | What the file holds                          | What a reminder is                                                                 |
|---|-----------------------------------------------------|----------------------------------------------|--------------------------------------------------------------------------------------|
| 1 | Timed entry                                         | a date and `HH:MM`, on `SCHEDULED`, `DEADLINE` or a bare active stamp | a moment exists, so: one alarm a lead time before it (15 minutes by default) |
| 2 | Dated entry with no time                            | a date alone                                 | there is no moment, so: one digest at an hour the reader picks, naming what the day holds |
| 3 | Deadline with a date                                | `DEADLINE` and a date, with or without `-Xd` | the warning window the core already opens, carried into the digest with the distance named |

Kind 3 needs no rule of its own for when to start warning: the core opens the
window from the `-Xd` the stamp may carry and falls back to
`DEADLINE_WARNING_DAYS = 14` (`src/agenda.rs:15` of the core), and from that day
it files the deadline into today's agenda. A reminder that reads the agenda
therefore inherits Org's own answer instead of inventing a second one. On the
day the deadline falls, a timed deadline is kind 1; an untimed one is named
first in the digest.

What is deliberately not reminded about:

| № | Left silent                              | Why                                                                          |
|---|------------------------------------------|--------------------------------------------------------------------------------|
| 1 | `DONE` and `CANCELLED` entries           | the work is over; the date is history                                          |
| 2 | An inactive `[...]` stamp                | inactive is the reader saying "not on the agenda" (core ADR-0014)              |
| 3 | An occurrence already past               | the digest counts the arrears; an alarm for them would fire for a backlog kept for years |
| 4 | Arrears, one by one                      | same reason: a count in the digest, not a notification each                    |

### How it would be scheduled

| № | Piece            | Choice                                                                                                              |
|---|------------------|-----------------------------------------------------------------------------------------------------------------------|
| 1 | Timed alarms     | `AlarmManager.setExactAndAllowWhileIdle`: a meeting reminded about forty minutes late is not a reminder                 |
| 2 | The digest       | an inexact window (`setWindow`, or `WorkManager`): "around nine" is what the reader asked for                           |
| 3 | Horizon          | schedule what falls within the next two days plus the following digest, and re-plan whenever one fires — a daily repeater over a year is thousands of alarms, and the platform caps what one application may hold |
| 4 | Re-plan triggers | application start; a sync that changed files; an edit made in the application; `BOOT_COMPLETED`; `TIME_SET`; `TIMEZONE_CHANGED`; `MY_PACKAGE_REPLACED` |
| 5 | Source of truth  | the core, through the same call the agenda makes — no second copy of the plan. A full re-scan on the phone measured 150 ms for 1000 files and 5000 tasks (31.07.2026, HONOR BVL-N49), so a receiver can afford to ask rather than cache |
| 6 | Channels         | two: timed entries and the daily digest, so either can be silenced without losing the other                            |
| 7 | Tapping one      | opens the agenda on that day with the entry selected                                                                   |
| 8 | Actions on one   | Done, which writes through the core and so moves a repeater the way the sheet does; Snooze, which re-arms the alarm and touches no file |

Permissions, all of which have to be re-checked against the platform
documentation before anything is written — these rules move, and the API levels
most of all:

| № | Permission               | From | Note                                                                                          |
|---|--------------------------|------|-------------------------------------------------------------------------------------------------|
| 1 | `POST_NOTIFICATIONS`     | 33   | asked at runtime; refused means the feature is off, not that the application is                  |
| 2 | `SCHEDULE_EXACT_ALARM`   | 31   | granted by the user in settings; `canScheduleExactAlarms()` before every plan, inexact when refused |
| 3 | `USE_EXACT_ALARM`        | 33   | needs no asking, but the store policy limits it to alarm and calendar applications — reachable only once publishing is settled |
| 4 | `RECEIVE_BOOT_COMPLETED` | any  | without it the plan dies at the first restart                                                    |

`minSdk` is 26, so both the older path (no runtime notification permission, no
exact-alarm gate) and the newer one have to work.

### What has to be decided before it is written

| № | Question                                                                                 |
|---|---------------------------------------------------------------------------------------------|
| 1 | Is the digest one notification for the day, or one per entry?                               |
| 2 | Does a timed entry also get an alarm at the moment itself, or only the lead time?           |
| 3 | Do reminders live on the device (a setting, like everything else the application stores) or in the notes (an `org-properties` key such as `REMINDER: 30m`, which the core already parses under ADR-0020 and which would mean the same in the extension later)? |
| 4 | What happens to the remaining alarms of a repeating entry completed early?                  |
| 5 | Do all collections take part, or is it per collection like the other per-collection settings? |

Testing: the decision of what to remind about and when is a pure function of the
agenda, the clock and the settings, so it is a unit test — the alarms and the
channels are the thin part around it. One instrumented test is still owed: that
a re-plan after a boot broadcast produces the same set as the one the
application produced while running.

## Moving one occurrence of a repeating entry

A repeating stamp is one line describing an endless series:
`<2026-08-20 Thu 15:00 +1w>` says English is at three every Thursday. It says
nothing about this Thursday being at six, and the file has nowhere to put
that.

What can be done today, and why neither is the answer:

| № | What the reader can do now                | What it costs                                                                        |
|---|-------------------------------------------|-----------------------------------------------------------------------------------------|
| 1 | Edit the date or time on the line          | the anchor moves, so every future occurrence moves with it — next Thursday is six too    |
| 2 | Drop the repeater and write a one-off      | the series is gone; it has to be typed back afterwards                                   |
| 3 | Add a second entry at the new time         | the day shows both, because nothing excludes the original occurrence                     |

### What Org itself does about this

Checked against the Org sources rather than recalled: the clone in
`~/devel/org-mode` at `6916affed` (2026-08-15), and the manual in the same tree.

| № | Mechanism                                         | What it gives                                                                                    | Where                                        |
|---|---------------------------------------------------|----------------------------------------------------------------------------------------------------|----------------------------------------------|
| 1 | The repeater itself (`+`, `++`, `.+`)             | nothing for exceptions: the section on repeated tasks describes shifting the base date on completion and warning periods, and names no way to exclude or move a single occurrence | `doc/org-manual.org`, "Repeated tasks"       |
| 2 | `org-clone-subtree-with-time-shift` (`C-c C-x c`) | the manual's own alternative to a repeater: N copies of the subtree with the dates shifted. When the original carries a repeater, the repeater is dropped from every clone, one extra clone holds the unshifted date, and the original is placed after the clones with its start shifted past the last one | `lisp/org.el:7729`; manual, end of "Repeated tasks" |
| 3 | The diary sexp `org-class`                        | the one exclusion Org has: an entry that applies on a weekday between two dates but skips named ISO weeks and holidays. Whole weeks only, and only for a sexp entry — not for `SCHEDULED` or `DEADLINE` | `lisp/org-agenda.el:6064`                    |
| 4 | The iCalendar export                              | only the cumulate repeater (`+`) becomes an `RRULE`; `++` and `.+` are warned about and exported without one. `EXDATE` appears in the file exactly once — a comment reading "TODO Add catch-up to supported repeaters (use EXDATE to implement)" | `lisp/ox-icalendar.el:887`, comment at `:896` |

So Org's answer is to stop having a series and have entries instead. Writing
about exactly this case — a training that is cancelled on holidays and moved on
occasion — Karl Voit gives up the repeater for the clone command, because with a
repeater he "would have the recurring event on my agenda without the possibility
to define exceptions" (<https://karl-voit.at/2017/01/15/org-clone-subtree-with-time-shift/>).

Two consequences for the options below.

1. An `EXDATE` or `MOVED` property is not something Org would understand. The
   notes are read by Emacs too, and there the series would still show the
   occurrence the property excludes. Whatever is invented is invisible outside
   this ecosystem, which is the cost the core's ADR-0012 exists to weigh —
   semantics are settled against Org's own Elisp, not around it.
2. There is an answer that needs no format change at all, and it is what the
   clone command amounts to for a single occurrence: complete the occurrence on
   the series entry, which moves the series to the next one, and write a
   one-off entry at the new time. The day then holds one entry at six, the
   series carries on from next week, and every reader — this client, the
   extension, Emacs — sees the same thing. What it costs is that the series
   records a completion that did not happen, and that the moved occurrence
   carries its own state.

### What everyone else does

Org is not the state of the art here, and its position is not the only one. Two
families of answer exist, and only one of them is a rule.

| № | Family                          | How the exception is stored                                                                 | Examples                                                                 |
|---|---------------------------------|----------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| 1 | A rule plus an override record  | the series stays a rule; a separate record names the occurrence it replaces, and a list names the occurrences that are gone | iCalendar `RRULE` + `RECURRENCE-ID` + `EXDATE`; Google Calendar's `instances` with `originalStartTime`; Microsoft Graph |
| 2 | Occurrences written out         | the series is a template, and each occurrence becomes a record of its own that can be edited alone | Taskwarrior (template and synthesised instances); Obsidian Tasks (completing one writes the next as a new line); todo.txt `rec:` in topydo and sleek; Org's own clone command |
| 3 | A rule and nothing else         | no per-occurrence exception exists; only whole-calendar rules such as holidays               | Remind (`OMIT`, `SKIP`, `SATISFY`, `OMITFUNC`); Org repeaters              |

What iCalendar settled on (RFC 5545) is worth reading in full, because it is what
every phone and every server already speaks: the occurrence is identified by the
start it would have had (`RECURRENCE-ID`), a replacement event carries the same
`UID` plus that identifier, `EXDATE` deletes occurrences outright, and
`RECURRENCE-ID;RANGE=THISANDFUTURE` splits the series in two — the original
truncated, a new one from that point.

Org's own iCalendar bridge does none of it: org-caldav gained recurring events in
September 2024 and its manual says plainly that "complex iCalender recurrences,
such as 'repeat on the 2nd Tuesday of each month until X date', are not
supported"; `EXDATE` appears in `ox-icalendar.el` only in a comment. So the Org
ecosystem has not answered this, rather than having answered it in the negative.

### What this means here

The compatibility argument against inventing something needs correcting, because
these notes are Markdown, not Org files: ADR-0002 of the core asks that a
timestamp line still parse in Emacs, not that Emacs run its agenda over this
directory. Nothing here is read by `org-agenda`, so a property Org does not know
costs recognisability, not interoperability.

That leaves the iCalendar model as the one to copy, written in the notation the
notes already have — the `org-properties` block the core parses under ADR-0020:

| № | Piece                    | In a note                                              | What it means                                                       |
|---|--------------------------|--------------------------------------------------------|------------------------------------------------------------------------|
| 1 | The series               | the heading with the repeating stamp, unchanged         | as today                                                              |
| 2 | An occurrence cancelled  | `EXDATE: 2026-08-20` on the series (a list, comma-separated) | the resolver skips that occurrence                                |
| 3 | An occurrence moved      | an ordinary entry at the new time, carrying `RECURRENCE-ID: 2026-08-20 15:00` and the series' `ID` | it replaces exactly that occurrence, and holds its own state, notes and clocks |
| 4 | Everything from here on  | later, if wanted: `RANGE: THISANDFUTURE` beside the identifier | splits the series rather than moving one of it                    |

Why this shape rather than a bare `MOVED:` line: it is the one the extension's
Google Calendar export can carry across without inventing a mapping, it separates
"gone" from "moved" the way the calendar world found necessary, and it keeps the
moved occurrence a real entry — with its own `DONE`, its own body, its own clock —
which the alternative of rewriting the series line cannot do.

What it costs: the core's occurrence resolver reads a second source; both clients
need a way to say "move just this one"; and a core older than the change ignores
the properties, so an old reader shows both the series occurrence and the moved
entry on that day.

Until any of it exists, the answer that needs no format change is the one the
clone command amounts to: complete the occurrence on the series, which moves the
series to the next one, and write a one-off entry at the new time.

The shape of an answer, none of which is chosen yet:

| № | Option                          | How it reads in a file                                                        | What it costs                                                                 |
|---|---------------------------------|-------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| 1 | Exception plus a separate entry | a property excluding the date, e.g. `EXDATE: 2026-08-20`, and an ordinary entry at the new time | the moved occurrence is a heading of its own, so its `DONE`, its notes and its clocks live apart from the series |
| 2 | Override in place               | a property naming the occurrence and its replacement, e.g. `MOVED: 2026-08-20 15:00 -> 2026-08-20 18:00` | the series stays one heading, but the core's occurrence resolver grows a second source of truth that every consumer has to read |
| 3 | No format change                | the client offers "detach this occurrence" and performs option 1 mechanically  | the convention still has to exist; this only decides who types it                |

Whatever is chosen belongs to the core rather than to this client: the
occurrence a row is drawn on, `timestamp_next` and `timestamp_next_after` are
all resolved there, and the extension reads the same fields. iCalendar answers
exactly this question with `EXDATE` and `RECURRENCE-ID`, and the extension
already maps a repeater to an `RRULE` for Google Calendar, so an override in
that shape would survive the round trip rather than being lost on the way out.

### Where this stands

Decided and written down: the extractor's ADR-0031 for the shape on disk, and
[ADR-0033](docs/adr/0033-an-occurrence-is-cancelled-in-place-and-moved-by-an-entry-of-its-own.md)
here for what this application writes and what it refuses. The questions the
options left open are answered by that shape: the original date shows nothing
at all, and a moved occurrence is an ordinary entry, so completing it is
completing an entry — the series is untouched and its own repeater is what
carries it forward.

| № | Part                                                        | State                                                                  |
|---|-------------------------------------------------------------|------------------------------------------------------------------------|
| 1 | The extractor leaves out an occurrence excluded or replaced | done: `EXDATE`, `SERIES_ID` and `RECURRENCE_ID` are read, and the next occurrence steps over them |
| 2 | `cancel_occurrence` and `move_occurrence` on the boundary   | done: `rust/markdown-org-ffi/src/occurrence.rs`, with the property block kept out of the entry editor |
| 3 | The actions on the task sheet                               | done: "Just this occurrence" under the date actions — move it, which asks for the day and then the hour, or cancel it |
| 4 | Saying that an entry replaces an occurrence                 | to do: a row standing in for one occurrence reads as an ordinary entry, and nothing says which series it came from |
| 5 | The pin on the extractor                                    | to do: until the version pinned in `rust/markdown-org-ffi/Cargo.toml` carries ADR-0031, a moved occurrence stands on the agenda twice |

What is left is the reading side. Until the pin on the extractor moves, a moved
occurrence stands on the agenda twice — the series still draws it, because the
version bundled here does not know about replacements — and an entry that
replaces one reads as an ordinary entry, with nothing saying which series it
came from.

## Publishing to the app stores

The only way to install the application today is the GitHub release: a tag
builds a signed APK and publishes it, and a phone downloads that file. That
asks the user to allow installs from an unknown source and leaves updates to
whoever remembers to check the releases page. A store does both for them.

Where the build already is:

| № | Piece                     | State                                                                                       |
|---|---------------------------|---------------------------------------------------------------------------------------------|
| 1 | Application id            | `io.github.vitalyostanin.markdownorg`, which any store will take as the package name         |
| 2 | Signing                   | a release keystore held as a CI secret, read from `APP_KEYSTORE_*`; a local build is unsigned |
| 3 | Artefact                  | APK only. Google Play takes an Android App Bundle, so `bundleRelease` has to be added         |
| 4 | Version                   | `versionCode` / `versionName` come from the tag, which is what a store increments against     |
| 5 | Licence notices           | already generated for the APK, and every store asks for the same list                         |
| 6 | Store listing             | `fastlane/metadata/android/{en-US,ru}` holds the title, both descriptions, the icon and four screenshots per language; no feature graphic and no privacy policy yet |
| 7 | Releases                  | every tag so far is a prerelease `v0.1.0-build.<run>`, and a store that tracks releases has nothing marked final to pick up |

The listing was written against the IzzyOnDroid policy, read on 2026-08-10
(<https://izzyondroid.org/docs/general/AppInclusionPolicy/>): fastlane
metadata with a short description, a full description, an icon and
screenshots; an APK signed with the developer's release key, carrying neither
`debuggable` nor `testOnly`, published on the releases page; at most 30 MB per
application, against the 24.8 MB the last build weighs; an OSI licence, which
MIT is; no proprietary component, tracker or analytics, of which the
application has none.

The icon is rendered from the launcher vector by `tools/store-icon.sh` rather
than drawn a second time. The screenshots were taken on the emulator, in both
languages, of the sample notes the application writes on first run — which are
written in the language of the device, so the Russian listing shows Russian
headings throughout. The emulator was put into Russian per application
(`cmd locale set-app-locales`) rather than as a whole: changing the system
language restarts the shell, and the emulator came back with an unresponsive
system UI over everything.

What each store wants. Every requirement below is from general knowledge and
has to be re-checked against the store's own documentation before anything is
submitted — these rules change often, and the numbers most of all.

| № | Store                       | What it costs and what it asks for                                                                                     |
|---|-----------------------------|------------------------------------------------------------------------------------------------------------------------|
| 1 | Google Play                 | a one-off developer fee, an AAB rather than an APK, Play App Signing, a data-safety declaration, a target API level no older than the current floor, and — for a personal account opened recently — a closed test with a number of testers over a number of days before production is unlocked |
| 2 | F-Droid                     | no fee and no account: a metadata file in their repository, a build from source on their own machines, and no proprietary dependency. Their build would have to reproduce the NDK and Rust toolchain this project pins |
| 3 | IzzyOnDroid                 | the lightest of them: a repository that publishes signed APKs on its releases page is enough, and the store tracks the tags. Closest to how the project already publishes |
| 4 | RuStore                     | a Russian developer account; takes an APK, which is what the build already produces |
| 5 | Huawei AppGallery           | an account and their own review; no Google Play Services in the application, which this one does not use anyway |
| 6 | Amazon Appstore, Galaxy Store | an account each, an APK, and a listing per store |

Two things have to be decided before any of it, and they are the reason this
is a note rather than a task:

1. **Who holds the signing key.** Google Play App Signing keeps the upload key
   separate from the one the store signs with, and an application published
   both there and elsewhere is then signed by two different keys — the same
   package cannot be updated across the two. The usual answer is to pick one
   distribution as the primary and let the others carry a different package
   name, or to publish everywhere with the project's own key and skip Play.
2. **Paying the developer fee.** The Google and Apple fees are charged in a way
   a Russian card does not satisfy, so the account itself is the obstacle
   rather than the review. Until that is answered, F-Droid, IzzyOnDroid and
   RuStore are the reachable ones.

The cheapest first step is IzzyOnDroid: it wants what the build already emits.
F-Droid is the one worth the work — it is where a notes application of this
kind is looked for — and the effort there is making the build reproduce on
their infrastructure, not the paperwork.

What is left before the request to include it can be filed:

| № | Step                                                                                        |
|---|---------------------------------------------------------------------------------------------|
| 1 | Cut a release that is not a prerelease, so there is a version for a store to track          |
| 2 | Write `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for that release      |
| 3 | Decide whether a feature graphic is worth drawing — the listing renders without one          |
| 4 | File the inclusion request, naming the repository and the tag pattern the APK is published under |
