# ADR-0004: The build toolchains live in containers, not on the host

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-30).

## Context

Building this project needs an Android NDK, Rust targets for the Android
architectures, cmake, make and perl for the vendored libgit2 and OpenSSL, a
JDK, the Android SDK and Gradle. That is several gigabytes of tooling that
nothing else on a workstation uses, and much of it — the SDK licences, the
`ANDROID_HOME` layout — is awkward to keep alongside other work.

The GUI application on the workstation is a separate matter: the host is a
development machine, not a build machine.

## Decision

Every build step runs inside a container image built from a `Containerfile` in
`tools/`, driven by `podman`:

- `Containerfile.ndk` — Rust, the NDK, the Android targets, `cargo-ndk`, and
  the licence collector;
- `Containerfile.sdk` — JDK, Android SDK, Gradle, for the APK;
- `Containerfile.emulator` — the above plus an emulator and a system image.

The versions the images are built from live in one file, `tools/versions.env`,
read by the scripts, by the Gradle build and by the CI workflow. Base images
are pinned by digest as well as by tag.

`NATIVE=1` runs the same steps against a toolchain already on the machine.
That is what CI uses, where the runner is disposable and an image would be
rebuilt for nothing.

## Consequences

- A clean workstation builds the project with `podman` and nothing else.
- The first run downloads around 700 MB of NDK, and images take disk.
- Anything the build needs has to be added to a `Containerfile`, not installed
  ad hoc — which is what keeps the two paths (container and `NATIVE=1`) from
  drifting.
- Container and host see different paths, so every path handed to a build
  command goes through a variable rather than being written out.

## References

- `tools/Containerfile.ndk`, `tools/Containerfile.sdk`,
  `tools/Containerfile.emulator`.
- `tools/lib.sh` — image building, proxy handling and resource limits shared by
  the scripts.
- `tools/versions.env` — the single place the pinned versions live.
