# ADR-0020: SSH remotes, with the server pinned by its host key

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-08-01).

## Context

[ADR-0005](0005-vendored-tls-and-libgit2.md) built the core with the `https`
feature of `git2` and nothing else, and `ensure_supported` refused every other
network scheme. The reason given for refusing `ssh://` was twofold: the
transport was not compiled in, and SSH authenticates the server by a host key
rather than by a chain a certificate authority signs — with nothing to check
that key against, whoever answers is believed.

The first half was a build decision, not a law. Adding the `ssh` feature pulls
in `libssh2-sys`, which compiles libssh2 from vendored sources against the
OpenSSL already vendored here, and it cross-compiles for the Android ABIs
without further work. It costs 211 KiB per ABI in the installed library —
measured as 11 416 024 bytes against 11 200 048.

The second half is real and does not go away by compiling something. It has an
answer, though, and it is the one every SSH client uses: pin the server by the
key it presented, ask a human the first time, and refuse when a stored key is
contradicted. That is a decision the application can put to the user, unlike a
merge.

The demand itself is ordinary: a key is what a phone can hold without a
password manager, several servers accept the same one, and an access token
that expires is a worse fit for a device that syncs in the background.

## Decision

`ssh://` is a supported address, in both spellings, and the server is pinned.

- `ensure_supported` accepts `ssh://host/path` and `user@host:path`. `git://`
  stays refused: it neither encrypts nor authenticates anything.
- The key travels in the request (`SyncRequest::ssh_key`, with
  `ssh_passphrase`) and is handed to libgit2 from memory. It is stored beside
  the token, under [ADR-0007](0007-token-in-plain-preferences.md), and never
  written to a file to be read back.
- The key is offered only to the configured endpoint, the rule the token
  already follows. The two spellings of one address reduce to the same
  endpoint, so a key given for `git@host:notes.git` is offered to
  `ssh://git@host/notes.git` and to nothing else.
- The server is checked in `certificate_check`: its key is hashed to
  `SHA256:<base64>` — the spelling `ssh-keygen -lf` prints — and compared with
  `SyncRequest::known_host`. A TLS certificate is passed through to libgit2,
  which has the CA bundle to judge it with.
- A host that does not match stops the sync and is reported as a question
  rather than a failure: `UnknownHost` when nothing was stored, `HostChanged`
  when a stored key was contradicted. Both carry the key that answered, which
  the agenda shows.
- The key is stored only on a press. Recording whatever answered the first
  time would pin nothing — the first time is exactly when a wrong server would
  be believed.
- A stored server key belongs to the address and is dropped with it. The
  device's own key is not: it is added to as many servers as its owner likes.
- SHA-1 is not accepted as a fallback hash. A server old enough to offer no
  SHA-256 host key is not one to pin the notes to.
- `generate_ssh_key` makes an ed25519 pair on the device. The private half is
  PKCS#8 PEM, which is what libssh2 tries first
  (`_libssh2_ed25519_new_private_frommemory` calls `PEM_read_bio_PrivateKey`
  before it looks for the OpenSSH container); the public half is assembled as
  the single `ssh-ed25519 AAAA…` line a server's settings page takes.

## Consequences

- The installed library grows by about 211 KiB per ABI, and libssh2's notice
  joins the licence list the APK carries.
- A first sync with an SSH remote cannot succeed unattended: it stops on the
  host key and waits for someone to accept it. That is the point, and it is
  what background syncing will have to account for.
- A server rebuilt with new host keys stops syncing until the change is
  accepted by hand, exactly as `ssh` on a workstation would.
- The key is only as protected as the application's private storage, the same
  as the token — see ADR-0007. A passphrase stored beside the key it opens
  adds nothing against someone holding the unlocked device; it is supported
  because keys issued elsewhere often have one.
- Android's Keystore is not used. It does not hand out private key material,
  and libssh2 has no callback for signing outside itself, so a key held there
  could not be used at all.

## References

- `rust/markdown-org-ffi/src/sync.rs` — `ensure_supported`, `credentials_for`,
  `HostTrust`, `generate_ssh_key`.
- `app/src/main/kotlin/…/core/RemoteUrl.kt` — the same rules, stated where the
  address is typed.
- `app/src/main/kotlin/…/ui/AgendaViewModel.kt` — `trustHost`, and what a
  stored server key is dropped with.
- [ADR-0005](0005-vendored-tls-and-libgit2.md) — the vendored build this
  extends, and the scheme allowlist it set.
- [ADR-0007](0007-token-in-plain-preferences.md) — where secrets are kept.
