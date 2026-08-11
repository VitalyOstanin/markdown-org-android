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
  day laid out on a timeline against the hours it spans.
- Four spans of the same agenda, chosen from the header: the day, the week
  around it, the month, and every task still open — the last one dated or not,
  which is the only place on the phone a task carrying no timestamp can be
  seen. The core is asked for the span on screen, so a week is one scan and
  not seven. A week shows all seven days, an empty Thursday included, because
  that is the answer to what is on Thursday; a month shows the days that have
  something on them. The hour axis draws one day, so the wider spans are read
  as the list and the layout switch steps aside while they are shown. The
  chosen span is remembered like the layout is.
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
  with the oldest folded to start with. A whole band is answered in one move,
  which reads and writes each file once and commits once; a missed repeat is
  caught up rather than dragged to today, and the move can be put back from a
  snapshot of the files it touched.
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
- The agenda follows the wall clock rather than the moment it was built: the
  marker line carries the passing minute while the screen is watched, and a
  day turning over is what triggers a new scan.
- Notes written with a byte-order mark are read and edited like any other,
  including the first task of such a file.
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
