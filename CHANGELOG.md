# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Table of contents

- [\[Unreleased\]](#unreleased)
- [\[0.2.0\] - 2026-09-03](#020---2026-09-03)
- [\[0.1.0\] - 2026-08-25](#010---2026-08-25)

## [Unreleased]

### Added

- The README opens with what the application looks like: the day, the week and
  the month, in the light theme and the dark one, of the sample notes written
  on first run. A reader arriving at the repository had a description of the
  agenda and no picture of it.

- A phrase says what it changed, field by field. "перенеси на пятницу в 16:00
  и сделай срочной" is three fields at once, and "заметка записана" left the
  reader to open the note to see whether all three were heard. The line under
  the screen now names them with both values -- "Изменено: срочность B → A,
  дата 04.09.2026, время 16:00" -- and a field the phrase emptied is named as
  emptied. Dates and hours are written the way the reader's locale and clock
  write them; the keywords are not translated, being what the file says.
  A field named to the value the entry already carried is not listed: the core
  reports such a phrase as an edit that wrote nothing, and the line agrees.

### Changed

- The setting for which day a week starts on is named rather than begun. "A
  week begins on" ended mid-sentence and left the answer to finish it, which
  in Russian also put the days into a case of their own ("Понедельника"). It
  is "The first day of the week" now, and the answers are the days as they are
  called.

- Two more reminder settings say what they mean on their own. A lead time of
  zero was "At the moment", which named a moment the reader had to infer;
  it is "No lead time" now, in the same breath as the "5 minutes" and the
  "an hour" beside it, and the Russian drops a second borrowed "в сам". The
  digest's label was the first half of a sentence the button finished ("The
  day's digest at" / "09:00"), which reads as nothing at all where the label
  is shown by itself — in the search over the settings and in the list of
  explanations. It is a phrase of its own now.

- The second reminder is named by the time it is raised at rather than by "the
  hour itself". An entry set for 15:30 is not announced at an hour, and the
  Russian said it in words borrowed from the English besides. The switch, its
  subtitle and its hint now say "at the time it is set for"; the examples in
  the help say "with an hour of lead time" where they used to say "at an
  hour", which read as two different things at once.

- One set of words for a day and an hour, wherever they are asked for. The
  screen that writes a new task said "No date" and "Pick a time…" where the
  sheet over a task in the notes said "Take it off" and "Pick an hour…", and
  the hour of the daily digest was picked in a second dialog of its own whose
  buttons read "OK" and "Cancel" against the "Set" and "Cancel" of the one
  beside it. All three now ask in the same words and in the same dialog.

### Fixed

- A scan that was dropped no longer reads as work that failed. The reminder
  scheduler and the synchronisation both wrap their reads in `runCatching`,
  which catches every throwable — including the cancellation the reader causes
  by leaving the screen or by asking for the same thing again. Folded into a
  result, that cancellation reported notes that could not be read and a sync
  that failed, and left the coroutine planning for a caller that had gone.
  Both now let a cancellation out and answer with a failure only for the
  failures that are real.

- A reminder in the drawer states its hour on the clock the reader set. It was
  written by the locale alone, so a phone on `en-US` with 24-hour time turned
  on said "1:05 PM" in the notification and "13:05" on the agenda behind it.
  The hour now follows the same rule as every hour on screen: the setting of
  the device first, the conventions of the locale for everything else.

- The settings screen no longer accepts a remote address the synchronisation
  would refuse. A path holding an `@` in it -- `notes/me@host:repo.git` -- read
  as a login on a server here and as a directory in the core, so the address
  was stored, the working copy emptied with it, and the refusal arrived on the
  first sync afterwards. The screen now reads such an address the way the core
  does, and a test asks both about the same address rather than leaving the
  agreement to a line of documentation.

- The number a reminder is announced under always falls inside the range set
  aside for it. The number comes from a hash of the note and the line the entry
  sits on, and the absolute value of the smallest number a hash can be is
  itself -- still negative; one entry in four billion was therefore announced
  below that range, and would be every time it came round. Nothing else in the
  application numbers notifications there, so no reminder is known to have been
  lost to it, and the remainder is now taken in a way that has no sign to lose.

- An hour written with one digit no longer costs the reminder. A note may
  hold `9:00` -- org-mode writes it that way, and so does anyone typing a
  timestamp by hand -- and the plan read the hour strictly, dropped what it
  could not read, and said nothing: the notification simply never came. The
  date and the hour a note states are now read in one place, leniently enough
  for the shorter hour and strictly enough that `2026-02-30` is not a date;
  that also takes down the crash a day the calendar does not have caused under
  the sheet of date actions, where the reading had no guard at all.

- A task said with an hour and no day is written for the day that hour next
  comes round on. The rules read "позвонить врачу в 15:00" as an hour and
  nothing else, a planning line cannot hold an hour without a day, and the
  hour was dropped on the way to the file -- the entry arrived as a bare
  heading, with no screen in between for the loss to show on. It is written
  for today while the hour is still ahead and for tomorrow once it has gone
  by; a repeat said without a day starts today. The line that says the task
  was written says which day it was given and on what grounds.

- A repeater is read the one way the extractor reads it. The app kept a second
  reading of the same syntax for the token inside a timestamp, and the two had
  drifted apart: it took the working-day `+1wd` for no repeater at all, so an
  occurrence moved out of such a series carried the series' own repeat into the
  entry that replaces it, and it cut the unit off the step by byte, which a
  unit typed on a keyboard left in Russian is not a whole number of — a phrase
  said over an entry holding `+1н` took the whole edit down. The question is
  now put to the extractor.

- Two things happening at once no longer cost a reminder or a write. A fetch
  landing while an edit is being written, or two "Готово" buttons answered a
  second apart, used to run two walks over one working copy: whichever finished
  first could renumber the alarms while the other was still placing them,
  leaving reminders that never fire and alarms that nothing cancels. Every
  working copy is now written under one lock of its own directory, whatever
  holds it, and the count the alarms are cancelled by is kept in step with what
  the platform holds.

- The "Готово" button on a reminder says when it did not work. The
  notification goes down as the button is pressed — one that stays up reads as
  a press that did nothing — and everything after that used to be silent: an
  entry moved on another device, a collection removed since the reminder was
  planned, a note that could not be written, a service the platform would not
  start. All four looked exactly like success, and the entry left open was
  found days later by opening the agenda. A notice now takes the reminder's
  place, in its own words for each of them and opening the entry it is about.

- An undo that only half went back is no longer reported as done. Undoing a
  move puts two notes back — the one the entry left and the one it arrived in
  — and one of them returning while the other had changed left the entry in
  both notes or in neither, under a line saying the edit had been taken back.
  The screen now says that part of it stayed as it was, as it already did for
  a group action.

- Reminders that stop arriving now leave a trace. The part of the application
  that has no screen — the receiver woken by an alarm, the one woken by a
  restart, the service behind the "Готово" button — asked for a plan and threw
  the answer away, so a directory that could not be read, a nine-second budget
  that ran out and a refused alarm all looked the same from outside: nothing
  arrives, and the log says nothing either. Each of them is now written down
  with its cause, an alarm the platform refuses to make exact among them — the
  reminder still comes, within the hour instead of at its minute, and now says
  so. A choice about the reminders that could not be planned is answered on the
  settings screen itself rather than only in the log.

- An edit no longer plans the reminders on the frame it was tapped on. A full
  plan is hundreds of calls into the platform — one to cancel and one to place
  for every alarm held — and they ran wherever the plan was asked for: after
  every edit, from the screen that made it, and from the settings screen on
  every switch. The plan is now made away from the screen, and the settings
  screen plans by the index the agenda already holds rather than opening every
  collection again: three switches in a row used to be three walks of the
  notes, seconds each on a phone that keeps a thousand of them.

- A reminder preference changed on the way out of the screen takes effect all
  the same. The lead time, the hour of the digest and the switch itself are
  planned again the moment they are chosen, and the walk that plans them used
  to belong to the screen: leaving it at once wrote the choice down and left
  the alarms as they were, until the next fetch. The walk now outlives the
  screen, and a second choice made while the first is still being planned
  replaces it rather than racing it.

## [0.2.0] - 2026-09-03

### Added

- An entry is changed by saying what to change. The sheet a tap on a row opens
  now begins with a field and a microphone: "перенеси на пятницу в 16:00 и
  сделай срочной" moves the day, the hour and the priority at once, where the
  buttons under it are three taps and two dialogs of choice. A phrase can also
  say the keyword — "отметь выполненной", "в работу" — and empty a field:
  "убрать дату", "убрать время", "без повтора", "без приоритета". One write,
  one commit and one undo for the whole sentence.
  A word the rules do not know changes nothing at all and is named instead:
  applying the half that was understood would move a field nobody meant to
  name. The rules are the core's, and read both grammars whatever language the
  phone is set to.

- A mark beside the name of a setting, opening a screen about that one setting:
  what it does, why the answer matters, and one case told with names and
  numbers — which token scope a push needs, what a branch of the phone's own
  keeps two devices out of, what the digest at nine names and what it leaves to
  the entry with an hour. Twenty of the thirty items of the screen carry it; the
  ten whose label is the whole answer keep the tooltip they had. What the screen
  says is searched as text of its own setting, so a case remembered and a label
  forgotten still finds it. The explanation used to be a tooltip held open by a
  long press — a gesture nothing announced, over a line sized for a glance.

- The moment an entry was written at is marked under its heading, as org-mode's
  expiry convention writes it: `CREATED: [2026-09-01 Tue 14:01]`, inactive
  brackets so that no agenda shows the mark as a date to keep. To the minute,
  because that is what tells two entries written the same day apart. Every way
  of writing an entry on the phone leaves the mark — the form, a typed phrase, a
  spoken one — and the line is spelled the way the file spells the dates it
  already holds: bare where they are bare, in Russian where they are in Russian,
  without a weekday where they carry none.

- A phrase at the head of the creation screen. What is said in one sentence —
  "позвонить врачу завтра в 15:00, каждую неделю" — fills the heading, the day,
  the hour and the repeater at once, instead of a heading typed with two thumbs,
  a date picked out of a dialog, an hour picked out of a clock and a repeater
  chosen from a row of chips. The rules are the core's, so the phone and the
  editor extension read a phrase the same way, and both grammars are consulted
  whatever language the phone is set to. A second phrase refines what the first
  left rather than starting over, a field corrected by hand is the field it adds
  to, and nothing is written until Create — a sentence read wrong is a screen to
  correct.

- That phrase can be spoken rather than typed. `Speak` opens whatever the phone
  recognises speech with — the application behind the keyboard's microphone key
  — and what it heard joins the field, to be read by the same button a typed
  sentence is: a misheard word is one line to correct rather than nine fields.
  This application asks for no microphone permission and recognises nothing
  itself. The language is the one the phone is set to, a second attempt joins
  what the first left, and a phone with nothing to listen with says so in the
  field instead of hiding the button.

- A field at the head of the settings screen that filters it: what a query does
  not name is not drawn, headings of emptied stretches included, and an empty
  field is the screen as it always was. Case and `ё` are folded, a heading that
  matches carries the whole stretch under it, and the section folded away under
  "Access over SSH" opens while a query is active.

- The hour of a planning date is set and taken off after the fact. A day could
  be given, moved or cleared, but the hour an entry is held at could only be
  chosen while the entry was being written — changing one afterwards meant
  editing the file by hand. The hour goes into the timestamp the line already
  carries, leaving the date, the weekday in whatever language it was spelled,
  the repeater and the warning cookie as written; taking it off takes the space
  ahead of it with it. An entry carrying no planning line of that kind is
  refused rather than given a day nobody asked for.

- A collection says where its entries go, and an entry can leave the file it
  went into. The write position is a setting of the collection — the start of
  the file, after whatever header stands above the first heading, unless the
  collection says the end — and beside it the collection names a main file, the
  one an entry is carried into from the sheet. Any other markdown file of the
  same collection can be named instead, and a file that is not there yet is
  created. The whole entry travels: the heading, its planning lines, its
  property block, its text and everything nested under it. The receiving file
  is written first and a removal that fails takes that write back, so the one
  outcome the order rules out is the entry standing in neither file; the undo
  line takes both files back together.

- A microphone in the corner of the agenda, over the plus. A task said to it
  is written the moment the recogniser hands the sentence over — "позвонить
  врачу завтра в 15:00" becomes a heading, a day and an hour without a screen
  in between, and the line at the foot offers to take it back. Two actions
  rather than the five the creation screen takes, which is what a task thought
  of while walking has time for. The sentence goes through the rules a typed
  phrase does, so both ways of saying the same thing read the same; a phone
  with nothing to listen with says so and leaves the plus to write with.

### Changed

- Settings that take one answer out of a few are lists now rather than rows of
  chips: where a week begins, how long before a timed entry it is announced,
  and which end of the file an entry is written at. A row of chips paid a line
  of the screen for every answer that did not fit the width, and on a phone
  held upright "Воскресенья" was squeezed until its word broke into a column of
  single letters.

- The line that used to stand under a setting has moved into the tooltip its
  label already carried. Eight of them on a column of thirty settings spent a
  screenful of height on text that is read once, and both halves are now read
  the same way — by holding the setting's name.

- Where an entry is written now says what it is the start and the end of: "at
  the start of the file" rather than "at the start".

### Fixed

- A note handed to another application opens where it stands. The action built
  the file out of the path a task carries — which is relative to the directory
  its collection walked — so the URI pointed at a name in the root of the
  filesystem, and every editor refused a file that was not there. The failure
  read as the other application's own. The path also travels beside the URI
  now, for the editors that recover a path rather than read the stream: Markor
  refuses a provider it was not taught, and an extra it does not know costs an
  editor that reads the stream nothing.

- The line that offers to undo an edit no longer covers the buttons that write.
  It spans the bottom of the agenda, which is the corner the plus and the
  microphone stand in, so the offer to take an edit back took away the control
  the hand reaches for next. The buttons now stand above it for as long as it
  is there.

- The phrase field spans the creation form. A second button beside it left the
  field about a third of the width on a phone held upright, and the button's
  own label then broke over three lines; both buttons sit under the field now,
  aligned to the right.

- The line at the foot of the creation screen now names where the task is
  actually going. It read "written at the end" whatever the collection's write
  position said, and that position is the start of the file until a collection
  says otherwise — so the line contradicted the setting and the file both, for
  every collection that had never been told anything.

## [0.1.0] - 2026-08-25

Everything so far. The builds published before this one were prereleases of
this version, tagged `v0.1.0-build.<run>` and marked as such on the releases
page.

### Added

- An agenda over a directory of markdown notes, read through the same Rust
  core the editor extension uses. Two layouts: a list ordered by date, and a
  day laid out on a timeline against the hours it spans. What has no hour of
  its own rides above the axis as the same rows the list draws, one task
  apiece, so a task is set out one way wherever it is read.
- Four spans of the same agenda, chosen from the header: the day, the week
  around it, the month, and every task still open — the last one dated or not,
  which is the only place on the phone a task carrying no timestamp can be
  seen. The core is asked for the span on screen, so a week is one scan and
  not seven. A week shows all seven days, an empty Thursday included, because
  that is the answer to what is on Thursday; a month shows the days that have
  something on them. The hour axis draws one day, so the wider spans are read
  as the list and the layout switch steps aside while they are shown. The
  chosen span is remembered like the layout is.
- The month is drawn as a calendar, the way the editor extension draws it: one
  cell per day, filled out to whole weeks with the days its first and last
  weeks borrow from the months beside it. Those weeks are what the core is
  asked for, so a borrowed day carries its own tasks like any other cell
  rather than standing empty over a date the answer said nothing about; the
  weekday they begin on is the phone's own, not the core's fixed Monday. A
  cell counts what is dated to that day and nothing else, and takes the overdue colour from the date itself: it
  has gone by with planning still sitting on it. A meeting that has been and
  gone leaves no debt behind and no colour. A date still ahead that a deadline
  is coming due on is ringed rather than filled — a fill is what arrears take,
  and the day the reader is warned about is the day the deadline falls on,
  which a calendar is already showing. Of a deadline that repeats, the ring
  goes on the one occurrence the warning is about: every occurrence is the
  same line of the same note, so matched by the task alone the ring ran to the
  end of the grid, and a weekly deadline marked the rest of the month. What the count is made of is
  behind a long press, and the rows themselves are one tap away — a cell opens
  its day. Where a row is too short for a number above a chip — a phone held
  sideways — the two stand side by side rather than being sliced. That is what
  a month is read for and what a list of thirty-one days cannot show. The list
  stays available for the month behind a setting, `Draw the month as a
  calendar`, because the two answer different questions: the calendar says
  where the month is full, the list says what is in it.
- Under the calendar stands the day picked out of it: its date, how much it
  carries, and its rows, with a button that opens it in the day view. A tap on
  a cell picks the day rather than opening it, and until one is picked the
  panel shows today. The rows come from the answer the calendar itself was
  drawn from, so picking a day costs no reading of the notes. The grid takes
  the height its cells want instead of sharing the whole window between its
  weeks — a cell was twice as tall as it is wide, with its number and its
  count in a band across the middle — and a window with no room left for the
  panel, a phone on its side, keeps the whole month and leaves the panel out.
  The cell draws no outline of its own and takes a ground only where it has
  something to say: today is a disc under the number, the picked day a tint of
  the cell, a weekend a tint fainter still. A day in arrears counts in the
  deadline colour at container weight rather than at full strength, because
  half a month is usually behind the reader and twenty chips at full strength
  are what the eye lands on first.
- Moving the agenda off the day it opens on, a span at a time: an arrow on
  either side of the heading, and a sideways drag of the heading itself, which
  turns the plan the way a page is turned. A press on the heading comes back
  to today, however many steps were taken, and a button beside it says so:
  once the span on screen no longer holds the day being lived through, "Today"
  appears next to the arrows, brings the plan back and takes the list to the
  day itself rather than to the first of the seven. The flat list of tasks
  covers no dates and gets neither. What is shown moves; what is overdue is
  still overdue against the day being lived through, not against the day on
  screen.
- Editing from the agenda: the keyword of a task, its priority, and moving a
  deadline to another day. Every edit is written to the note it came from and
  committed straight away, so a working copy is never left half-changed.
- Repeating tasks move to their next occurrence when they are marked done,
  following the repeater written in the timestamp rather than dropping it.
- A single occurrence of a repeating task can be cancelled or moved without
  breaking the series apart, and the agenda reads it the way the editor does:
  a day named by the series' `EXDATE` holds nothing, and a day another entry
  stands in for — one naming the series through `SERIES_ID` and the occurrence
  through `RECURRENCE_ID` — is held by that entry alone rather than by both.
  The reading comes from the core the two clients share.
- Synchronisation with a git remote: clone on first use, then fetch and push.
  An edit is committed locally straight away, and a sync hands those commits
  to the remote as a fast-forward of its branch or not at all. Nothing is
  merged — a checkout that has moved on both sides is reported rather than
  resolved, and a push the server declines after a successful fetch is a
  state the header counts rather than a failed sync. The credentials live in
  the application's private preferences, which are not carried off the device
  by a backup, and are never written into the notes.
- Both transports a hosted remote offers. `https://` trusts the certificate
  bundle carried in the APK, because the vendored OpenSSL is built without a
  filesystem to read one from. `ssh://host/path` and `git@host:path` pin the
  server by its host key: an unvouched-for server stops the sync and reports
  the SHA-256 key it offered, a key contradicting the stored one stops it and
  says so, and the key is stored only when it is accepted. The device makes
  its own ed25519 pair, whose private half never leaves the preferences. The
  token and the key are offered only to the configured server, and a server is
  recognised by its name rather than by the spelling of it: capitals, lower
  case and punycode name one host, so a domain written outside ASCII is
  answered the same way an ASCII one is.
- The whole of the first setup from the phone. On a fresh install — before an
  address is entered and before the notes are declared local — the line under
  the header says the notes are kept on this device and offers the settings
  where a server is given; it goes for good as soon as either is answered.
  Inside the form, "where to issue a token" and "where to paste the key" open,
  in the browser of the device, the page of the host the address names: the
  exact page for GitHub and GitLab, the front page for a host whose paths this
  application does not know. Nothing is sent to those pages and nothing is read
  back — they are opened as the user, in the browser they already use.
- Notes kept on the device alone, with git added to them later rather than
  the directory being a working copy from the start. Saving an address no
  longer empties what is already there: the directory is taken into git as it
  stands. Nothing in the application removes a file it did not write — a
  directory holding a checkout of somewhere else is refused and left alone,
  with the way on named in the message.
- Several notes directories read as one agenda. A collection is a name, a
  directory and the settings that reach its server; a row carries a dot in
  the colour of the collection it came from, and the agenda can be narrowed
  to one of them.
- A filter by file tag, beside the one by collection and applied after it: the
  collections decide which directories are read, a tag decides which of their
  notes are shown. A tag matches the file name as a substring, never the path,
  and may both take (`include`) and refuse (`exclude`) — "everything about work
  except the archive" is one tag rather than two. The tags are declared in
  `.markdown-org/tags.json` inside a collection's directory, holding what the
  editor extension's setting holds and travelling with the notes through git,
  so both clients filter by the same names. Everything declared merges into one
  dictionary: a tag means the same wherever a note came from, refusing beats
  taking whichever directory said it, `!` collects what no tag took, and the
  order of the collections changes nothing. The menu shows the result — a line
  per pattern, what it takes or keeps out, and who declared it — because a
  merge nobody can see is a filter nobody can explain.
- Overdue entries grouped by how long ago they slipped — missed repeats, the
  past week, this year, older than a year — each band foldable and counted,
  with the oldest folded to start with. The bands sit under the day they are
  read against, below what is set for an hour and what has no hour of its own:
  a day carrying a year of arrears otherwise answers what is on today only
  after a scroll past all of them. The editor extension draws them in the same
  order. A whole band is answered in one move,
  which reads and writes each file once and commits once; a missed repeat is
  caught up rather than dragged to today, and the move can be put back from a
  snapshot of the files it touched.
- Only a date given as `SCHEDULED:` or `DEADLINE:` can be overdue. A timestamp
  written without a keyword is an event -- it is shown on its day and never
  carried into a later one -- so a class held every Monday since last autumn is
  on Mondays rather than standing as a year of arrears. This is what upstream
  Org-mode does, and it arrived with the 0.16.0 core.
- An overdue row states the year when the date it slipped from is not of the
  year on screen. Within the year it stays a day and a month, as the column is
  narrow and the year would be the same on every row; outside it the year is
  the whole of what the date says — `01.05` in the band that holds everything
  older than a year reads as the first of May just gone, when it is the first
  of May of 2021. The time column keeps a gap after itself whatever it holds:
  its width is a minimum, and a date long enough to fill it used to run into
  the collection dot beside it.
- The grouping itself is a setting: "Group a day into sections" in the
  settings screen, on by default. Off, the same rows are drawn in the same
  order with no headings, no counts and no group menus, and the height those
  headings took goes to the rows — which is what a phone has least of. A band
  that opens folded when the headings are on is drawn in full when they are
  off: without a heading there would be nothing to unfold it by. The tick
  takes effect where it stands, without waiting for the form to be saved.
- The header gives way on a screen with no height to spare. Set out one thing
  per row — the day, the date under it, the controls, the collections, the
  state of the checkout — it took two thirds of a phone held on its side and
  left the plan a row and a half. Below a window height of 480 dp the day and
  its date join the controls on one row, the sync line states the checkout,
  what is unsent and when the last run got through on another, and the header
  is left taking about a third of the screen. What the room is measured on is
  the window rather than the orientation, so a window shared with another
  application is set out the same way. Nothing is dropped: everything the
  roomy header states is still on screen, and the button a sync waiting on an
  answer offers stays whatever the height.
- A long press on a row spells the heading out in full, with the legend for
  the glyph and the priority badge under it, and names the collection the dot
  at its head stands for — worded as the extension words it.
- A long press on the rest of the agenda says what a control is for: the
  header icons, the layout switch, the chips of the filter, which name the
  directory each collection reads, the sync banner and the two lines under it,
  the notices above the list, and the band menu together with each of its
  actions.
- The same press answers on the other screens. Over a task it says what the
  action writes into the note — which keyword lands in the heading, what a
  repeating task moves instead of closing, and what a shifted date keeps: the
  time, the repeater and the weekday rewritten to match. In the settings it
  says what a button does to what is stored — which credential is forgotten,
  what a removed collection leaves on the device, what saving reads again —
  while the fields answer with a line under them instead, readable without a
  press. On the licence screen it says that the full text is behind the card.
- A tooltip goes away on a tap of its own area, beside the touch outside it
  and the Back key it already answered. What is read is also what is in the
  way, and reaching for it is the first thing tried.
- The agenda follows the wall clock rather than the moment it was built: the
  marker line carries the passing minute while the screen is watched, and a
  day turning over is what triggers a new scan.
- Notes written with a byte-order mark are read and edited like any other,
  including the first task of such a file.
- A priority cookie written away from the front of a heading is read as a
  priority and left where the author put it, so `## TODO Buy [#A] filter`
  shows its whole title instead of an empty row — the reading the editor
  extension gives and the one `org-agenda` prints. The rule comes from the
  extractor, which the application now reads through at 0.15.
- English and Russian throughout, including the dates and the hours, which
  follow the locale of the device rather than the language of the build, and
  the sample notes a fresh install is seeded with. Only the wording of the
  sample is translated: the keywords, the brackets and the dates around them
  are the format the core reads back.
- The notices of everything the APK carries, reachable from the settings
  screen: the licence of every crate compiled into the native library, of
  every Gradle dependency, and of the vendored libgit2 and OpenSSL.
- The version of the installed build is shown on the settings screen, together
  with the commit it was built from.
- The notes directory is a setting: notes already on the device can be read
  where they lie, including on the shared storage, and a checkout there is
  cloned, fast-forwarded and committed to like one in the application's own
  storage.
- The decisions behind all of the above, as Architecture Decision Records in
  `docs/adr/`, in the format the two sibling projects use.
- A push the server refuses is reported in the server's own words. Two
  refusals used to wear one sentence: the branch here being behind, which the
  next sync fixes by fetching first, and the server itself saying no — a
  protected branch, a hook, a key without write. Only the first was worded,
  and it was worded over the second, so a phone told to sync again did so
  forever while the reason went unread. The core now says which refusal it
  met, and what the server wrote beside it — the explanation arrives on the
  side channel, which nothing was listening to — and the banner words the
  branch that fell behind and quotes the server that declined.
- The banner's second line is no longer cut mid-sentence. Prose written here
  is shown whole however many lines it takes, because every word of it was
  chosen to be read and a translation is longer than the English it was
  measured against; what a server or a library wrote keeps its two lines. Both
  end in an ellipsis when they are cut, so a cut says that it is one.
- A task can be written here rather than only found: the corner of the plan
  opens a screen with a heading, a text, the keyword, the priority and a day
  from the same calendar the sheet uses. Once there is a day, the screen also
  asks the hour it is held at — from the clock the sheet moves an occurrence
  with — and whether it repeats: daily, weekly, monthly, yearly, or any
  repeater the format writes, typed into a field that answers what it spells
  while it is being typed rather than after the task has been composed. The
  four ready intervals are catch-up repeaters, so completing a task that was
  missed moves it to the next occurrence ahead of today instead of one step
  from the date in the file. Both go into the timestamp after the day, which
  is where org writes them. Where it goes is a setting of the
  collection — the file it receives new tasks in, `inbox.md` unless it is said
  otherwise, made by the first task written to it — so a creation asks which
  collection when there is more than one and nothing else. The entry is
  appended to the end of that file, which is what keeps two devices' additions
  merging cleanly, and it is written at the level the file writes its tasks at,
  with its date spelled the way the file spells the ones it already holds. A
  title that would read as a keyword or a priority is refused, because those
  are fields beside it. The task that was just written can be taken out again
  from the same line that offers an undo of an edit, and that is the only way
  an entry is removed here.
- Any single edit can be taken back: the line at the foot of the screen offers
  it for as long as it stands, and the note goes back to the bytes it held
  rather than to a line rebuilt from what the edit understood. That is what a
  group action has offered since it existed, and the same restore serves both.
  It matters most for the two edits that lose something otherwise unreachable
  from the phone — a date cleared with its time, its repeater and the weekday
  in the note's own language, and the rewritten text of an entry. A note a
  sync landed on, or one edited elsewhere, is left as it stands and the screen
  says so; the offer is dropped by the next edit, because it describes one
  state of one note. Undoing commits, naming the task it put back.
- The sheet a tap opens names the day the entry stands on, under its heading.
  A row counts days -- "in 1 day" -- and an anniversary repeating once a year
  counts them for months without ever naming the day it is counting towards;
  the date was on the long press alone, so reading it meant putting the sheet
  away and pressing the row again. A repeating row is answered with the day it
  stands on rather than the one after it: the long press names the occurrence
  after, because there the row is still on screen to say when it is, and the
  sheet covers the row it was opened from. A deadline coming due is named by
  the occurrence it counts towards, which is the one day it has -- the date in
  its file is the anchor of the series, years back for a yearly repeat.
- A planning date can be given, picked from a calendar, and taken off. Until
  now the sheet could only move a date the task already had, so a task that
  arrived without one had nothing to shift and a date cleared elsewhere could
  not be put back from the phone. A task with a date is offered the calendar
  and the way out of it; a task without one is asked which kind it is being
  given, because a day work starts and a day something is due are not
  interchangeable. A line written where there was none is spelled after the
  file it lands in — the same weekday language, the same inline-code framing,
  the same indentation as the dates already there — and joins the block under
  the heading rather than splitting it. The calendar is cut on the weekday the
  month grid is cut on. A line a manual edit left carrying both keywords at
  once is refused rather than half-cut.
- The text of an entry is edited here: the title of its heading, and the lines
  under it. Everything else on the sheet writes one line and stays where it is;
  this opens a screen with the two of them, and the core writes both back in
  one commit. What an action writes is left to the actions — a title that would
  read as a keyword, and a body line that would read as a planning line or as
  another heading, are refused rather than written, and an entry read and saved
  unchanged leaves the file byte for byte as it was. An entry longer than
  twenty thousand characters is not opened here but handed to the editor
  below: a field of that size answers a keystroke in seconds, which was
  measured rather than assumed.
- A task offers to open its note in another application. The agenda reads the
  notes and edits the line a task sits on; reading a note as a document, or
  reaching a section that carries no date at all — a shopping list, a page of
  keys — was something no screen here could do, and the file had to be hunted
  down through a file manager. The note now travels to whichever markdown
  editor the device has, as a `content://` URI granted read and write for the
  one launch, so the receiving application needs no storage permission over a
  directory this one was given by hand.
- A sync commits what another application wrote, and says that it did. Every
  sync already began by committing whatever the working copy held, so an edit
  made elsewhere leaves with the next one under a message nobody chose; the
  banner now names that rather than reporting an ordinary push. When the commit
  itself cannot be made the sync stops on a dirty checkout, and the banner
  offers to commit and go again — nothing else here moves a checkout out of
  that state.
- The reader is told what is coming, off the same agenda the screen draws. An
  entry held at an hour is announced ahead of it — a quarter of an hour by
  default, five minutes to an hour by choice, and again at the hour itself for
  the reader who wants both. What a day holds without an hour of its own —
  dated entries, deadlines inside the warning window the core opens, and how
  much is overdue — is announced once, in a digest at an hour the reader picks,
  read at the moment it is raised rather than when it was planned, so an entry
  closed in between is not named. Everything is off until it is switched on in
  the settings, where the two accesses the platform grants separately are asked
  for: notifications, and alarms to the minute. Without the second the platform
  delivers within the hour, and the settings say so rather than pretending the
  plan holds. The plan holds two days, is remade whenever a note may have moved
  — a fetch, an edit, a restart, the clock or the time zone being set — and is
  raised on two channels, so a meeting and a digest are silenced separately.
  Switching reminders off drops the alarms and takes back whatever they left
  in the drawer. A reminder is answered where it is read: tapping one opens the
  day it is about, with the entry it names picked out, and a reminder for an
  entry held at an hour carries two buttons — Later, which says it again in a
  quarter of an hour and touches no file, and Done, which closes the entry
  through the core, moving a repeater the way the sheet of the task does.
