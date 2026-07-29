#!/usr/bin/env bash
# Runs the Rust tests of the core, on the host architecture, inside the NDK
# container. The whole workspace, which is what CI runs — the binding
# generator carries tests of its own.
#
# The tests never reach the network: the sync ones work against a repository
# on disk, which is the same code path up to the transport.
#
# Formatting and lints are tools/check-core.sh, the other half of what CI
# requires.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# A named volume for the cargo registry and the build directory; a vendored
# OpenSSL and libgit2 are minutes of work to rebuild from scratch.
readonly CACHE_VOLUME="${CACHE_VOLUME:-markdown-org-cargo}"
readonly TEST_THREADS="${TEST_THREADS:-8}"
# libtest has no per-test timeout, so the whole run is bounded from outside.
# Generous enough for a cold build of the vendored dependencies, which is the
# slow case; a run that exceeds it is stuck, not slow.
readonly TIMEOUT="${TIMEOUT:-30m}"

ensure_ndk_image

echo "==> cargo test"
timeout "${TIMEOUT}" podman run --rm --network host "${proxy_run_args[@]}" "${limit_args[@]}" \
    -v "${REPO_ROOT}/rust:/src:z" -v "${CACHE_VOLUME}:/usr/local/cargo/registry" -w /src \
    "${NDK_IMAGE}" \
    cargo test --locked -j "${JOBS}" "$@" -- --test-threads "${TEST_THREADS}"
