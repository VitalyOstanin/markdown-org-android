# Project rules for Claude Code

Architectural decisions live in [`docs/adr/`](docs/adr/). This file lists the
ones that bear on day-to-day work, with pointers to the full text and the
reasoning.

## Table of contents

- [Decisions in force](#decisions-in-force)
- [Working on the core](#working-on-the-core)
- [Working on the application](#working-on-the-application)
- [Background](#background)

## Decisions in force

- The extractor is linked in and called in-process; there is no subprocess and
  no second parser in Kotlin. See
  [ADR-0001](docs/adr/0001-extractor-in-process-over-uniffi.md).
- Types crossing the FFI boundary are this project's own, converted from the
  extractor's in one place. A value the interface reasons about is a variant,
  not a string. See [ADR-0002](docs/adr/0002-project-core-types-at-the-boundary.md).
- Build, then generate the bindings, then strip — in that order, and never
  `strip = "symbols"`. See [ADR-0003](docs/adr/0003-build-then-generate-then-strip.md).
- Build toolchains live in containers, driven by `podman`; the host stays
  clean. Versions come from `tools/versions.env`. See
  [ADR-0004](docs/adr/0004-build-only-in-a-container.md).
- libgit2 and OpenSSL are vendored, and the CA bundle crosses as PEM text
  because the vendored OpenSSL has no file IO. See
  [ADR-0005](docs/adr/0005-vendored-tls-and-libgit2.md).
- Syncing fast-forwards or refuses with a named error; it never merges and
  never pushes. See [ADR-0006](docs/adr/0006-fast-forward-only-sync.md).
- The access token lives in ordinary private preferences with
  `allowBackup="false"`; `EncryptedSharedPreferences` is deprecated and is not
  to be reintroduced. See [ADR-0007](docs/adr/0007-token-in-plain-preferences.md).
- The palette is the project's own; do not add a dynamic-colour branch. See
  [ADR-0008](docs/adr/0008-own-palette-over-dynamic-color.md).
- Org-mode semantics — repeaters above all — are verified against upstream
  Emacs Org-mode Elisp before code or tests are written, not recalled by
  analogy. The repeat rules and the two deliberate divergences are in
  [ADR-0009](docs/adr/0009-repeat-semantics-on-completion.md).
- Everything touching the notes directory goes through `NotesArea.exclusive`,
  and an edit commits itself in the same block. See
  [ADR-0010](docs/adr/0010-one-writer-for-the-working-copy.md).
- The core never reads a clock: "today" is an argument. See
  [ADR-0011](docs/adr/0011-today-comes-from-the-caller.md).
- Rust edition and the SDK levels are as they are on purpose; read
  [ADR-0012](docs/adr/0012-edition-and-sdk-levels.md) before raising either.

## Working on the core

- Tests come with the change, not after it. The core has unit tests beside the
  code and surface tests in `rust/markdown-org-ffi/tests/`.
- `tools/check-core.sh` runs `cargo fmt --check`, clippy with `-D warnings` and
  the tests, in the container.
- The toolchain is pinned in `rust/rust-toolchain.toml` to the version the NDK
  image carries. Bumping the image means bumping that line.
- Changing anything on the FFI surface means rebuilding the core before the
  instrumented tests: the bindings are generated from a built library, and the
  library is built only for the ABIs named in `ABIS`. The emulator runs
  `x86_64`, a device `arm64-v8a` — `ABIS="arm64-v8a x86_64" tools/build-core.sh`
  covers both.

## Working on the application

- `tools/test.sh` runs the JVM tests, `tools/lint.sh` ktlint and Android Lint,
  `tools/test-instrumented.sh` the instrumented suite on a running emulator.
- User-visible strings are resources, in both `values/` and `values-ru/`.
  Dates, times and plurals go through the platform's formatting, not through
  string concatenation.
- The interface reads state from the view model; a composable does not call the
  core.

## Background

- Code, comments, documentation, commit messages and `TODO.md` are written in
  English. The repository is private, but the code reads as if it were not.
- `docs/__check/` holds local review artefacts and is not part of the
  repository's documentation.
