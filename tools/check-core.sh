#!/usr/bin/env bash
# Runs the formatting check and the lints of the core in the NDK container —
# the same two commands CI fails on, so a push does not have to be the first
# time they are tried.
#
#   tools/check-core.sh
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly CACHE_VOLUME="${CACHE_VOLUME:-markdown-org-cargo}"

ensure_ndk_image

run_core() {
    podman run --rm --network host "${proxy_run_args[@]}" \
        -v "${REPO_ROOT}/rust:/src:z" -v "${CACHE_VOLUME}:/usr/local/cargo/registry" -w /src \
        "${NDK_IMAGE}" "$@"
}

echo "==> cargo fmt --all --check"
run_core cargo fmt --all --check

echo "==> cargo clippy --all-targets -- -D warnings"
run_core cargo clippy --all-targets -- -D warnings

echo "==> done"
