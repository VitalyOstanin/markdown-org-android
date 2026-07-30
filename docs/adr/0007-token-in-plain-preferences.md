# ADR-0007: The access token is kept in ordinary private preferences

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

Syncing with a private remote needs an access token, and the token has to
survive a restart. The obvious candidate, `EncryptedSharedPreferences` from
`androidx.security`, is deprecated: the library was retired, and the
documented advice is to use ordinary `SharedPreferences`, because on a device
with file-based encryption the application's private storage is already
encrypted at rest and the extra layer bought little beyond a dependency that
is no longer maintained.

The Keystore-backed alternatives that remain solve a different problem — a
secret that must survive a compromise of the application's own storage — and
none of them protect against a user who has root on their own phone, which is
the only realistic reader here.

## Decision

The token is written into the application's private `SharedPreferences` file
(`sync`), alongside the remote URL and the branch. The manifest sets
`android:allowBackup="false"`, so the file is not carried off the device by a
platform backup.

Around it:

- the token is never read back into the settings form; the form shows whether
  one is stored, not its value;
- a "forget the saved token" checkbox is the way to clear it;
- credentials written into the address (`https://x:<token>@host/repo.git`) are
  moved into the token field rather than kept in the URL;
- the token is offered only to the configured host, and anything the core
  quotes back is masked before it reaches the screen.

## Consequences

- No dependency on a retired library, and no Keystore ceremony for a secret
  that does not need it.
- A user with root, or an unlocked device with a debugger attached, can read
  the token. That is stated rather than argued away.
- Losing the application's storage loses the token, which is the intended
  behaviour: the remote is re-configured from scratch.

## References

- `app/src/main/kotlin/…/core/SyncSettings.kt` — the store and the reasoning
  at the class.
- `app/src/main/AndroidManifest.xml` — `allowBackup="false"`.
- `rust/markdown-org-ffi/src/sync.rs` — `credentials_for`, which decides who
  the token is offered to.
