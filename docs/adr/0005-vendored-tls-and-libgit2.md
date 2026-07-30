# ADR-0005: libgit2 and OpenSSL are vendored, and the certificates cross as text

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

The notes are kept in step with a remote over git. Android carries neither
libgit2 nor a system OpenSSL an application may link against, so both have to
travel with the application.

Vendoring OpenSSL brings a second problem. `openssl-src` configures the
Android builds with `no-stdio`, so the library has no `BIO_new_file`. That
means `GIT_OPT_SET_SSL_CERT_LOCATIONS` fails with "BIO lib" for any path, no
matter how readable the file is — Android also has no `/etc/ssl/certs`, which
is where the vendored stack looks by default. Without certificates every
`https://` fetch fails on verification.

## Decision

`git2` is built with `vendored-libgit2` and `vendored-openssl`. The CA bundle
ships as an asset (`app/src/main/assets/cacert.pem`) and is handed to the core
as PEM **text**: the core parses it and adds the certificates one by one
through `GIT_OPT_ADD_SSL_X509_CERT`, which is what libgit2 documents for
exactly this case.

Loading happens once per process, under a lock, because the store is global to
libgit2. A failure is not remembered: a half-filled store would fail every
later connection, so the next attempt reads the bundle again.

The line the vendored OpenSSL is built from is pinned in
`rust/markdown-org-ffi/Cargo.toml` as a build dependency, since the TLS stack
inside the APK reaches a phone only through a release of this project.

## Consequences

- The application talks to `https://` remotes on a device that offers it
  nothing to link against.
- The APK carries a TLS stack, and updating it is this project's job — an
  advisory against OpenSSL is a release here, not a system update.
- `LIBGIT2_NO_VENDOR` in the environment silently sends the build looking for a
  system libgit2; the manifest warns about it at the dependency.
- Certificate loading costs one copy of ~180 kB across the FFI boundary per
  process.

## References

- `rust/markdown-org-ffi/src/sync.rs` — `load_ca_bundle`, `use_ca_bundle`.
- `app/src/main/kotlin/…/core/NotesSync.kt` — the once-per-process guard on the
  Kotlin side.
- `rust/markdown-org-ffi/Cargo.toml` — the vendoring features and the OpenSSL
  line.
