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
- Editing from the agenda: the keyword of a task, its priority, and moving a
  deadline to another day. Every edit is written to the note it came from and
  committed straight away, so a working copy is never left half-changed.
- Repeating tasks move to their next occurrence when they are marked done,
  following the repeater written in the timestamp rather than dropping it.
- Synchronisation with a git remote over HTTPS: clone on first use, then
  fast-forward; an edit is committed locally straight away. Nothing is pushed
  and nothing is merged — a checkout that has moved on both sides is reported
  rather than resolved. The token lives in the application's private
  preferences, which are not carried off the device by a backup, and is never
  written into the notes. The certificate bundle the sync trusts travels in
  the APK, because the vendored OpenSSL is built without a filesystem to read
  one from.
- English and Russian throughout, including the dates and the hours, which
  follow the locale of the device rather than the language of the build.
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
