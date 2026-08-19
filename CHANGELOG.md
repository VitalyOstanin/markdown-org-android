# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Table of contents

- [\[Unreleased\]](#unreleased)

## [Unreleased]

Everything so far. The application has not been released under a version of
its own yet: every build published to date is a prerelease of `0.1.0`, tagged
`v0.1.0-build.<run>` and marked as such on the releases page.

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
  from the same calendar the sheet uses. Where it goes is a setting of the
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
