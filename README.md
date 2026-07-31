# markdown-org-android

An Android client for markdown files carrying Emacs Org-mode task markers —
the same format the [`markdown-org-vscode`](https://github.com/VitalyOstanin/markdown-org-vscode)
extension reads, kept in sync over git.

**Status: early.** The Rust core, its Kotlin bindings and a Compose
application that renders the agenda all build. The agenda syncs over git and
takes point edits — status, priority, a planning date, completion. Until a
remote is configured the notes are a sample the application writes on first
run. Nothing has been run on a physical device yet.

## Table of contents

- [Requirements](#requirements)
- [Layout](#layout)
- [How the core is reused](#how-the-core-is-reused)
- [What an edit refuses to do](#what-an-edit-refuses-to-do)
- [What a sync does with the checkout](#what-a-sync-does-with-the-checkout)
- [The certificate bundle a sync trusts](#the-certificate-bundle-a-sync-trusts)
- [Where the token may travel](#where-the-token-may-travel)
- [Building the core](#building-the-core)
- [Building the application](#building-the-application)
- [Running it on an emulator](#running-it-on-an-emulator)
- [Continuous integration](#continuous-integration)
- [Versions and what changed](#versions-and-what-changed)
  - [Rolling back a build](#rolling-back-a-build)
- [The generated Kotlin surface](#the-generated-kotlin-surface)
- [Colour](#colour)
- [Testing](#testing)
- [Environment variables](#environment-variables)
- [Why the toolchain lives in a container](#why-the-toolchain-lives-in-a-container)
- [Decisions](#decisions)
- [Licence](#licence)

## Requirements

| № | What                           | Needed for                                                        |
|---|--------------------------------|-------------------------------------------------------------------|
| 1 | [podman](https://podman.io/)   | every build; the toolchain never touches the host                 |
| 2 | `adb` (Android platform-tools) | installing on a device or emulator, and picking the target        |
| 3 | Access to `/dev/kvm`           | the emulator; without it qemu boots in software for tens of minutes |
| 4 | Around 11 GB of disk           | the images: NDK 4.3 GB, SDK 1.2 GB, emulator 6.9 GB including the SDK layers |

The images are built on first use — no separate step, but the first run of
each script takes a while and says which image it is building. Behind a
proxy, export `HTTPS_PROXY`: the scripts pass it to both the image build and
the container, and run on the host network so a proxy on the loopback is
reachable.

The pinned versions of the SDK, the NDK, the JDK and Gradle live in
[`tools/versions.env`](tools/versions.env) — read by the scripts, by the
Gradle build and by the CI workflow, so the three cannot drift apart.

## Layout

```
markdown-org-android/
├── .github/workflows/        # build.yml — build, test, publish; audit.yml — advisories
├── app/                      # the Compose application
│   ├── src/main/kotlin/…/
│   │   ├── core/             # the bridge to the Rust core, and where notes live
│   │   └── ui/               # the agenda screen and the palette
│   ├── src/main/assets/      # cacert.pem, and the licence list the app shows
│   └── src/sharedTest/       # task fixtures both test suites build on
├── rust/
│   ├── markdown-org-ffi/     # UniFFI wrapper over markdown-org-extract
│   │   ├── src/lib.rs        # scanning, the type projection, the shared error mapping
│   │   ├── src/document.rs   # reading a note and writing one line back
│   │   ├── src/edit.rs       # the status and the priority cookie
│   │   ├── src/planning.rs   # SCHEDULED and DEADLINE, and completing a repeat
│   │   ├── src/sync.rs       # clone, fast-forward, commit, the state of the checkout
│   │   └── tests/            # tests for the projection and error mapping
│   └── uniffi-bindgen/       # binding generator entry point
├── tools/
│   ├── versions.env          # the pinned SDK, NDK, JDK and Gradle versions
│   ├── lib.sh                # shared by the scripts: proxy, images, versions
│   ├── Containerfile.ndk     # Rust + Android NDK + cargo-ndk
│   ├── Containerfile.sdk     # JDK + Android SDK + Gradle, for the APK
│   ├── Containerfile.emulator   # adds the emulator and a system image
│   ├── build-core.sh         # build for the ABIs, then generate the bindings
│   ├── test-core.sh          # the Rust tests, in the NDK image
│   ├── check-core.sh         # cargo fmt --check and clippy, in the same image
│   ├── gradle.sh             # any Gradle task, in the SDK image
│   ├── lint.sh               # ktlint and Android Lint, the Kotlin half
│   ├── build-app.sh          # assemble the APK
│   ├── test.sh               # the JVM tests of the application
│   ├── test-instrumented.sh  # the instrumented tests, on a booted emulator
│   ├── test-instrumented-full.sh  # the same from a cold start, unattended
│   ├── coverage.sh           # what the JVM tests reach, as a Kover report
│   ├── coverage-core.sh      # what the Rust tests reach, as an llvm-cov report
│   ├── licenses.sh           # collects the notices; --check fails on a stale one
│   ├── run-app.sh            # assemble, install and start in one command
│   └── run-emulator.sh       # start the headless emulator and wait for boot
├── rust/jniLibs/<abi>/       # build output, not committed
└── generated/                # generated Kotlin, not committed
```

## How the core is reused

Task extraction is not reimplemented. The application calls
[`markdown-org-extract`](https://github.com/VitalyOstanin/markdown-org-extract)
— the same Rust code the CLI and the VS Code extension run — through
[UniFFI](https://github.com/mozilla/uniffi-rs) bindings.

In-process, not as a subprocess: Android does not let an application spawn
the CLI, and a subprocess would also mean serialising to JSON and parsing it
back for data the same process is about to render.

`markdown-org-ffi` is a thin projection layer. It does not re-export the
extractor's own types; it flattens them into records UniFFI can carry, so
the extractor stays free to change its internals and only this crate has to
follow. Reading the notes:

- `scan(dir, options)` — walk a directory, return every task found;
- `scanAgenda(dir, scope, currentDate, timezone, includeDone, options)` —
  walk it and return the agenda for a day, week, month, or the flat task
  list.

The application only ever calls the second one. The first is exported all the
same: the two prepare the walk through the same code, and a scan reachable
only from the tests would drift away from the agenda beside it.

`currentDate` is what the agenda treats as today. The caller passes it
rather than letting the library read the clock, so the same files render the
same agenda whenever they are asked for — the contract the CLI follows
through `--current-date`.

Writing to them, one line at a time:

- `setStatus(target, status)` — set, replace or clear the keyword;
- `setPriority(target, priority)` — the same for the `[#A]` cookie;
- `shiftPlanning(target, keyword, days)` — move a `SCHEDULED` or `DEADLINE`
  date;
- `completeTask(target, today)` — mark done, or move a repeating task to its
  next occurrence and leave it open, following upstream Org-mode's
  `org-auto-repeat-maybe`;
- `commitChanges(dir, message, author)` — commit the working copy.

There is no text editor and no whole-file write: each call rewrites exactly
one line, keeping the rest of the file byte-for-byte. That is what lets git
merge an edit made on the phone with one made on a laptop instead of
reporting a conflict. `target` carries the file, the line and the heading the
caller believes is there — a file that moved on since the agenda was built is
refused rather than overwritten.

Keeping them in step with a remote:

- `syncRepository(request)` — clone into an empty directory, fast-forward
  afterwards; it never merges and never pushes;
- `repositoryStatus(dir)` — the remote, the branch, the head commit and
  whether anything is uncommitted, read without touching the network;
- `holdsRepository(dir)` — whether the directory is a checkout at all, which
  is how "not set up yet" is told from "set up and behind";
- `loadCaBundle(pem)` — hand the certificate authorities over, once per
  process.

What a sync refuses to do, and what it retries, is the section
[below](#what-a-sync-does-with-the-checkout). On the Kotlin side
`core/NotesSync.kt` calls these under the lock on the notes directory, and
`core/SyncSettings.kt` holds the remote, the branch and the token the settings
screen writes.

The grammar itself stays in the extractor: it reports where each token of a
heading or a timestamp sits (`parseHeadingLine`, `parseTimestampParts`), and
this crate splices the replacement in. A second copy of those rules here
would drift from the one that reads the files.

## What an edit refuses to do

The notes are the user's files and live in a git checkout, so every write
either lands whole or does not happen:

| № | Situation                                    | What happens                                                                     |
|---|----------------------------------------------|----------------------------------------------------------------------------------|
| 1 | Any write                                    | Written to a temporary beside the note and renamed over it, so an interrupted write leaves the original untouched. The note keeps its permissions. |
| 2 | The rewritten line equals the one in the file | Nothing is written and the outcome reports `changed: false`.                     |
| 3 | A date leaving the four-digit years          | `InvalidDate`. Outside `1000..=9999` a year is printed signed and of another width, which no reader of these files accepts. |
| 4 | A weekday in neither Russian nor English     | `Unsupported`. Rewriting Ukrainian `Нд` as Russian `Вс` is a change of language nobody asked for. |
| 5 | The file is not UTF-8                        | `NotUtf8`, apart from `Io`: converting the file is what fixes it.                 |
| 6 | The file name is not UTF-8                   | Refused before the core is called — the path arrives with U+FFFD and names nothing on disk. |
| 7 | The heading on that line is not the one the agenda saw | `Stale`, the one failure mode that would damage notes.                  |

A weekday is rewritten in the language, length and case it was written in:
`Вт` stays `Вт`, `Tuesday` stays a full name, `вт` stays lowercase.

The walk behind an agenda reports what it skipped — files not in UTF-8, files
it could not read, files past the size cap, paths that are not UTF-8, and a
truncated list — and the agenda shows that above the entries. Without it a
note in CP1251 simply disappears: no tasks, no reason, no sign.

## What a sync does with the checkout

A sync clones once and fast-forwards afterwards; it never merges, and it never
pushes. What it does in the situations that are not a plain fast-forward:

| № | Situation                                          | What happens                                                                                  |
|---|----------------------------------------------------|-----------------------------------------------------------------------------------------------|
| 1 | The remote has no commits yet                      | Cloned all the same. The status reports the branch with an empty head, and the first edit here becomes the first commit. |
| 2 | The branch in the settings is not the one on disk  | The checkout is moved onto it, creating the local branch from what was fetched. The directory is not wiped — commits made on the device would go with it. |
| 3 | The checkout has commits the remote does not       | `Diverged`. Merging belongs with editing that does not exist here yet.                          |
| 4 | Anything is uncommitted, tracked or not            | `Dirty`, before the tree is touched. The checkout runs with `force()`, so an untracked note would otherwise be overwritten by one arriving under the same name. |
| 5 | A temporary from an interrupted write is left over | Ignored by the check above: nothing else would ever clean it up, and it would block every sync from then on. |
| 6 | The network fails                                  | Retried up to three times, waiting 0.5 s then 1 s. Rejected credentials, a divergence and a dirty checkout are not retried — they need someone to act first. |
| 7 | The connection hangs                               | Bounded: 15 s to connect, 60 s per request. Without them the wait is whatever the operating system decides. |
| 8 | The remote URL changes                             | The directory is emptied and cloned again, and the stored token is dropped with it — it was issued by the host that is being left. |

## The certificate bundle a sync trusts

The TLS stack the core syncs over is vendored, and Android has no
`/etc/ssl/certs` for it to read: nothing on the device tells it which
authorities to trust. `app/src/main/assets/cacert.pem` is what does — Mozilla's
root certificates as curl extracts them, around 180 kB, the snapshot of
16 July 2026 that the header of the file names. It is handed to the core once
per process rather than with every sync (`loadCaBundle`), because the store it
goes into lives as long as the process.

Refreshing it is replacing the file, from
[curl.se/docs/caextract.html](https://curl.se/docs/caextract.html):

```bash
curl -o app/src/main/assets/cacert.pem https://curl.se/ca/cacert.pem
curl -s https://curl.se/ca/cacert.pem.sha256 |
  sed 's| cacert.pem| app/src/main/assets/cacert.pem|' | sha256sum -c
```

This copy is the trust store for git traffic, so a root Mozilla withdraws stays
trusted here until the file is replaced and a new APK is released — nothing
else on the device would notice. The `certificates` job of
[`.github/workflows/audit.yml`](.github/workflows/audit.yml) fails once the
file has gone 180 days without an update, which is the reminder to run the two
commands above.

## Where the token may travel

The access token is sent as the HTTP password, so what the address says decides
where it goes. Both rules below are in the core rather than only on the settings
screen: `SyncRequest` is the FFI surface, and whoever calls it gets the same
answer the screen does.

| № | Rule                                                                    | Why                                                                                              |
|---|-------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| 1 | Only `https://`, `file://` and an absolute path are accepted            | `http://` and `git://` carry the token in the clear, and Android's ban on cleartext traffic does not reach libgit2 over a vendored OpenSSL. Refused before a connection is opened, so nothing leaves the device. |
| 2 | The token is offered to the configured host and to no other             | libgit2 asks per request, and a redirect asks for somewhere else. git itself does not follow credentials across hosts either. |
| 3 | Credentials written into the address are moved into the token           | `https://x:<token>@host/repo.git` is what a copied clone command looks like; the address field is shown in the clear, the token field is not. |
| 4 | Whatever the core quotes back is masked before it reaches the screen    | libgit2's messages carry the address it was given, credentials included.                          |

The token is stored in the application's private `SharedPreferences` with
`allowBackup="false"`; it is never read back into the form, and a "forget the
saved token" checkbox is the way to clear it.

## Building the core

Requires [podman](https://podman.io/) and nothing else; the first run builds
the container image, which downloads around 700 MB of NDK.

```bash
# Both ABIs the APK carries, release, stripped
ABIS="arm64-v8a x86_64" tools/build-core.sh

# Just the one that matters for a device, keeping symbols for debugging
ABIS=arm64-v8a STRIP=0 tools/build-core.sh
```

Output:

| № | Path                                          | What it is                       |
|---|-----------------------------------------------|----------------------------------|
| 1 | `rust/jniLibs/<abi>/libmarkdown_org_ffi.so`   | loaded by the application        |
| 2 | `generated/uniffi/markdown_org_ffi/*.kt`      | the Kotlin surface               |

Library sizes, release, stripped:

| № | ABI         | Size     |
|---|-------------|----------|
| 1 | `arm64-v8a` | 10.76 MB |
| 2 | `x86_64`    | 11.56 MB |

Most of that is vendored: libgit2 and the TLS stack it syncs over are built
into the library because neither is present on an Android device. The APK
carries both ABIs and comes to around 31 MB.

The order in `build-core.sh` is not incidental. UniFFI keeps the interface
metadata in the library's symbol table, so the bindings must be generated
before the library is stripped; the entry points the application calls live
in the dynamic symbol table and survive stripping. Building with
`strip = "symbols"` instead fails with `No UniFFI metadata found`.

## Building the application

The core has to be built first: the APK packages the libraries and compiles
the Kotlin the binding generator produced.

```bash
ABIS="arm64-v8a x86_64" tools/build-core.sh
tools/build-app.sh                  # debug
VARIANT=release tools/build-app.sh
```

Output is `app/build/outputs/apk/<variant>/app-<variant>.apk`. The Gradle
cache lives in a named podman volume, so only the first run downloads the
dependency graph.

| № | Setting      | Value | Why                                                                          |
|---|--------------|-------|------------------------------------------------------------------------------|
| 1 | `compileSdk` | 37    | androidx.lifecycle 2.11 refuses to be consumed by a project compiled below it |
| 2 | `targetSdk`  | 36    | what Google Play requires of new applications from 31.08.2026                 |
| 3 | `minSdk`     | 26    | `java.time` without desugaring, and the agenda is date arithmetic throughout  |

`compileSdk` comes from `tools/versions.env` along with the build tools and
the JDK, since the image has to ship the platform the build asks for;
`minSdk` and `targetSdk` are decisions of the application alone and stay in
`app/build.gradle.kts`.

`compileSdk` and `targetSdk` are separate knobs: the first decides which APIs
are visible at compile time, the second which runtime behaviour the
application opts into.

## Running it on an emulator

```bash
tools/run-emulator.sh                       # starts headless, waits for boot
tools/run-app.sh                            # assemble, install, start
tools/run-emulator.sh --stop
```

`run-emulator.sh` builds the emulator image the first time it is called, and
the SDK image it extends before that — around 7 GB and a good while, once.
`run-app.sh` is the short loop for a change to the interface; the three steps
it replaces are still available separately (`tools/build-app.sh`, `adb
install -r`, `adb shell am start`).

The container shares the host network, so the host's `adb` reaches the
emulator without going inside. `/dev/kvm` is passed through — without it qemu
falls back to software emulation and the boot takes tens of minutes.

An emulator is x86_64; how fast the core parses on ARM has to be measured on
a device.

## Continuous integration

[`.github/workflows/build.yml`](.github/workflows/build.yml) builds the same
two ABIs and publishes the APK. Four jobs:

| № | Job            | What it does                                                                                 |
|---|----------------|-----------------------------------------------------------------------------------------------|
| 1 | `check`        | `cargo fmt --check`, `cargo clippy -D warnings` and `cargo test` for the host                  |
| 2 | `build`        | the core for both ABIs, ktlint and Android Lint, the unit tests, the APK and its signature     |
| 3 | `instrumented` | the emulator tests, against the core `build` produced; skipped on a pull request               |
| 4 | `publish`      | the release carrying the APK — a job of its own, so the token that writes never builds sources |

Every cargo command carries `--locked`: Cargo re-resolves the lock file
without saying so when a manifest and the lock disagree, and what gets
published would then differ from what the repository records.

The core is built by `tools/build-core.sh` with `NATIVE=1`, which runs the
steps directly instead of in a container. The workflow does not repeat them:
the order of build, binding generation and stripping is the one thing that
must not drift between the two, since generating from a stripped library
fails.

What comes out depends on the trigger:

| № | Trigger              | Variant | Published as                                                          |
|---|----------------------|---------|-----------------------------------------------------------------------|
| 1 | push to `master`     | release | prerelease `v<version>-build.<run number>`                            |
| 2 | tag `v*`             | release | release under that tag, with the notes CHANGELOG.md holds for it      |
| 3 | `workflow_dispatch`  | release | under the tag the `release_tag` input names; a release unless `prerelease` is set |
| 4 | pull request         | debug   | build artefact only                                                   |

A push publishes a prerelease of the version being worked towards, not a
release of it: `v0.1.0-build.42` is a version by the rules of semver and sorts
below `v0.1.0`, which is what a tool comparing the two has to conclude. A run
dispatched by hand publishes whatever `release_tag` names, as a release unless
the `prerelease` input says otherwise.

Tags are annotated: the workflow creates the tag itself and pushes it before
asking for the release, because `gh release create` on a tag that does not
exist creates a lightweight one — a ref with no author, no date and no
message. A release cut by hand is tagged the same way (`git tag -a v0.1.0 -m
'...'`) and pushed; the workflow then publishes under it.

## Versions and what changed

| № | Where                             | What it says                                                     |
|---|-----------------------------------|-------------------------------------------------------------------|
| 1 | `appVersionName` in `gradle.properties` | the version being worked towards, raised by hand when one is cut |
| 2 | `-PappVersionCode`, from the run number | what Android orders builds by; every published APK gets its own |
| 3 | `-PappCommit`, the short sha      | which commit an installed build was made from                     |
| 4 | [`CHANGELOG.md`](CHANGELOG.md)    | what changed, in the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form |

A build from a working copy keeps version code `1` and reports its commit as
`working copy`; nothing but CI is meant to produce an APK for anyone else. All
three are shown at the bottom of the settings screen, so a build can be named
without reaching for `adb`.

`tools/release-notes.sh` prints the section CHANGELOG.md holds for a version,
which is what the notes of a release are made of. A prerelease has no section
of its own and gets the list of commits GitHub generates instead.

### Rolling back a build

Every release keeps its APK, so the way back from a build that turned out to
be broken is the previous one. The version code of an older build is lower
than the installed one, and Android refuses that by default:

```sh
adb install -r -d path/to/older.apk    # -d allows the downgrade
```

The settings, the token and the working copy of the notes survive it. What
does not survive is uninstalling: the token lives in the Android keystore and
the notes are cloned again from the remote, so a reinstall asks for both. A
sync that misbehaves is switched off in the settings — clearing the address
stops it without touching anything already committed.

A pull request builds the debug variant: it has no access to the signing key
and does not need one. Everything else is signed with the release key, which
has to stay the same from build to build — an APK signed with a different key
does not install over the one already on the phone, and registering the
application id under Google's developer verification pins it to that key.

Four repository secrets carry it: `APP_KEYSTORE_BASE64`,
`APP_KEYSTORE_PASSWORD`, `APP_KEYSTORE_ALIAS`, `APP_KEY_PASSWORD`. The key is
decoded into `RUNNER_TEMP`, outside the workspace, so that no later step can
pick it up as a build input.

The instrumented job takes the libraries and the generated Kotlin as an
artefact from `build` rather than building them again, so it needs neither
the NDK nor a Rust toolchain: an emulator boot is what it costs, and that is
why it runs on a push and not on every pull request. `publish` waits for it,
so nothing goes out over a failed run of the tests that load the core.

Actions are pinned by commit SHA with the tag in a comment, and the runner is
`ubuntu-24.04` rather than `ubuntu-latest`: an image that moves under an
unchanged commit makes a build that passed once impossible to reproduce.

A second workflow answers what a build cannot:
[`.github/workflows/audit.yml`](.github/workflows/audit.yml) checks the Rust
dependencies against the advisory database and the certificate bundle against
its age, on a change, on a schedule and by hand. libgit2 and OpenSSL are
compiled into the native library, so an advisory against either reaches a phone
through a release of this project and through nothing else — and it is
published when nobody is committing.

## The generated Kotlin surface

Names arrive idiomatic — `scanAgenda`, `filesProcessed` — and the Rust
documentation comes across as KDoc. Errors are a sealed class:

```kotlin
val agenda = try {
    scanAgenda(
        dir = notesDir.absolutePath,
        scope = Scope.DAY,
        currentDate = "2026-03-02",
        timezone = "Europe/Moscow",
        includeDone = false,
        options = Options(),
    )
} catch (e: ExtractException.InvalidDirectory) {
    …
}
```

Loading the library needs [JNA](https://github.com/java-native-access/jna),
and the library name has to start with `lib` — hence `markdown-org-ffi`
producing `libmarkdown_org_ffi.so`. The crate must also not be named
`android`: `libandroid.so` is a system library.

## Colour

The scheme is written out, not derived from the device wallpaper. Dynamic
colour puts every role on one hue, and the agenda uses colour to tell one
kind of entry from another — a deadline from something merely scheduled, a
repeating task from a one-off. Both themes are drawn separately rather than
inverted: the tone lightens in the dark theme while the container darkens.

Beyond the Material roles the agenda carries its own set — deadline,
scheduled, repeat, done, cancelled — in `ui/theme/AgendaColors.kt`, reached
through `LocalAgendaColors`. The values are the ones the VS Code extension
uses, so the same file reads the same way in both.

Contrast was measured against WCAG 2.1: text pairs clear 4.5, rails and
glyphs clear 3.0. A container fill sits below 3.0 by design — it is a
backdrop, and the meaning is carried by the glyph, the rail and the label,
each of which clears the threshold on its own.

## Testing

```bash
tools/test-core.sh        # the Rust tests, in the NDK image
tools/check-core.sh       # cargo fmt --check and clippy, what CI fails on
tools/lint.sh             # ktlint and Android Lint; --format rewrites what it can
tools/test.sh             # the JVM tests of the application
tools/run-emulator.sh && tools/test-instrumented.sh   # the instrumented ones
tools/test-instrumented-full.sh   # the same, from a cold start
tools/coverage.sh         # what the JVM tests reach, as a Kover report
tools/coverage-core.sh    # what the Rust tests reach, as an llvm-cov report
```

`test-instrumented-full.sh` is the unattended form of the two commands above
it: it builds the core for the emulator's ABI, boots the emulator, runs the
tests and stops the emulator afterwards, whether they passed or not. The two
separate commands stay the shorter way around an interactive session, where
the emulator is already up and the core already built.

The instrumented tests need the core built for the emulator's own ABI, which
is `x86_64`, while `build-core.sh` builds `arm64-v8a` alone unless `ABIS` says
otherwise — and it clears `rust/jniLibs` first, so a build for the phone
removes what the emulator needs:

```bash
ABIS="arm64-v8a x86_64" tools/build-core.sh
```

What a run leaves behind, for the failure the console line does not explain:

| № | Script                  | Report                                                 |
|---|-------------------------|--------------------------------------------------------|
| 1 | `test.sh`               | `app/build/reports/tests/testDebugUnitTest/index.html`  |
| 2 | `test-instrumented.sh`  | `app/build/reports/androidTests/connected/index.html`   |
| 3 | `lint.sh`               | `app/build/reports/lint-results-debug.html`             |
| 4 | `coverage.sh`           | `app/build/reports/kover/htmlDebug/index.html`          |
| 5 | `coverage-core.sh`      | `rust/target/llvm-cov/html/index.html`                  |

`test-instrumented.sh` reads the device's ABI and refuses to run when the
matching library is missing, rather than letting the tests that load the core
fail as `NoClassDefFoundError` on `UniffiLib` — a message that says nothing
about what is absent.

Every run is bounded: each JVM test by `testOptions` in
`app/build.gradle.kts`, each instrumented one by the runner's `timeout_msec`,
and each script by a `TIMEOUT` around the whole thing. JUnit 4 interrupts
nothing on its own and libtest has no per-test timeout at all, so without
these a test that stops making progress holds the machine until something
else kills it.

The extractor has its own suite; these tests cover what this crate adds —
the projection onto the FFI types, the mapping of failures onto the error
enum a Kotlin caller catches, the sync against a repository on disk, and the
editing surface. The editing tests assert on the whole file rather than on
the line under test: an edit that disturbs a neighbouring line is exactly the
failure that turns a merge into a conflict, and an assertion scoped to one
line would not see it.

The application has two suites of its own. The JVM ones
(`tools/gradle.sh testDebugUnitTest`) cover the projections onto the screen
and the order the view model puts work in — the notes directory is a git
working copy, so a scan, a clone, an edit and the wipe before a change of
remote must not overlap, and stand-ins for the core make that assertable
without a device. The instrumented ones
(`tools/gradle.sh connectedDebugAndroidTest`) need an emulator and are the
only ones that load the native library.

Running the built library outside Android is not possible: it links against
Android's C library, so `libdl.so` is missing on a desktop Linux host. The
FFI path itself was exercised by generating Python bindings from a host
build and calling through them.

## Environment variables

Every script reads its configuration from the environment, with the default
in the script itself. The versions of the toolchain are not here — they live
in `tools/versions.env`.

| № | Variable         | Read by                                        | Default                                   |
|---|------------------|------------------------------------------------|-------------------------------------------|
| 1 | `HTTPS_PROXY`    | all container scripts, through `tools/lib.sh`   | unset; also passed as `HTTP_PROXY`        |
| 2 | `ABIS`           | `build-core.sh`                                 | `arm64-v8a`                               |
| 3 | `PROFILE`        | `build-core.sh`                                 | `release`                                 |
| 4 | `STRIP`          | `build-core.sh`                                 | `1`; `0` keeps the symbols for debugging  |
| 5 | `NATIVE`         | `build-core.sh`                                 | `0`; `1` runs on the host, as CI does     |
| 6 | `VARIANT`        | `build-app.sh`                                  | `debug`                                   |
| 7 | `ANDROID_SERIAL` | `test-instrumented.sh`, `run-app.sh`, `gradle.sh` | the booted emulator, from `adb devices` |
| 8 | `NAME`           | `run-emulator.sh`                               | `markdown-org-emulator`                   |
| 9 | `BOOT_TIMEOUT`   | `run-emulator.sh`                               | `300` seconds                             |
| 10 | `NDK_IMAGE`, `SDK_IMAGE`, `EMULATOR_IMAGE` | `tools/lib.sh`       | `localhost/markdown-org-*` tagged by version |
| 11 | `CACHE_VOLUME`   | `gradle.sh`, `test-core.sh`, `check-core.sh`    | `markdown-org-gradle` / `markdown-org-cargo` |
| 12 | `KEY_VOLUME`     | `gradle.sh`                                     | `markdown-org-android-home`, the debug signing key |
| 13 | `JOBS`, `MEMORY` | `tools/lib.sh` — every container                | `8` cores and `8g`; also `cargo -j`       |
| 14 | `TEST_THREADS`   | `test-core.sh`                                  | `8`                                       |
| 15 | `TIMEOUT`        | every script that runs tests                    | `20m` JVM, `30m` core, `40m` instrumented |

The container limits are deliberate: left alone a cargo build takes every
core on the machine, and the vendored libgit2 and OpenSSL are a lot of C to
compile. Raise them by hand when nothing else is running.

## Why the toolchain lives in a container

The NDK, the Rust Android targets and `cargo-ndk` add up to several hundred
megabytes of build-only tooling. Keeping them in a container image means the
host stays clean and the build is reproducible from `Containerfile.ndk`
rather than from someone's shell history.

## Decisions

The choices this README describes in passing — calling the extractor
in-process, projecting its types at the boundary, the order the core is built
in, vendored TLS, fast-forward-only syncing, where the token lives, the palette
— are recorded as Architecture Decision Records in [`docs/adr/`](docs/adr/),
each with the context that forced it and what it cost. The two sibling
projects, [`markdown-org-extract`](https://github.com/VitalyOstanin/markdown-org-extract)
and [`markdown-org-vscode`](https://github.com/VitalyOstanin/markdown-org-vscode),
keep theirs in the same format.

## Licence

`SPDX-License-Identifier: MIT` — the full text is in [LICENSE](LICENSE).

The published APK carries more than this repository, and each part comes under
its own terms:

| № | What                                     | Under                                      | How it gets in                       |
|---|------------------------------------------|--------------------------------------------|--------------------------------------|
| 1 | libgit2                                  | GPL-2.0 with a linking exception            | vendored, statically linked          |
| 2 | OpenSSL                                  | Apache-2.0                                  | vendored, statically linked          |
| 3 | `markdown-org-extract` and ~150 crates   | mostly MIT and Apache-2.0                   | compiled into the native library     |
| 4 | the UniFFI runtime                       | MPL-2.0                                     | the same                             |
| 5 | Compose, AndroidX, kotlinx-coroutines    | Apache-2.0                                  | Gradle dependencies                  |
| 6 | JNA                                      | LGPL-2.1-or-later or Apache-2.0, at the recipient's choice — taken here under Apache-2.0 | Gradle dependency |
| 7 | `cacert.pem`                             | MPL-2.0 (Mozilla's NSS data)                | packaged as an asset                 |

The full notices are in [NOTICE](NOTICE), and the same list is in the
application itself — settings, then "Licences of what is inside". Neither is
written by hand: `tools/licenses.sh` collects the native half with
[cargo-about](https://github.com/EmbarkStudios/cargo-about) and the vendored
sources, and the [licensee](https://github.com/cashapp/licensee) plugin
collects the Gradle half into the APK while it is assembled. A list kept by
hand would be right on the day it was written.

```bash
tools/licenses.sh           # rewrite NOTICE and the bundled list
tools/licenses.sh --check   # what CI runs: fails if either is stale
```

`licensee` also fails the build on a licence outside the list in
`app/build.gradle.kts`, so a dependency whose terms nobody looked at cannot
reach a published APK.
