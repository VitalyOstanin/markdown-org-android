#!/usr/bin/env bash
# Measures how much of the core the Rust tests reach, with cargo-llvm-cov, in
# the NDK container. The counterpart of tools/coverage.sh, which measures the
# Kotlin half with Kover.
#
# Prints the per-file summary and writes an HTML report:
#   rust/target/llvm-cov/html/index.html
#
# The whole workspace, same as tools/test-core.sh: the binding generator
# carries tests of its own.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly CACHE_VOLUME="${CACHE_VOLUME:-markdown-org-cargo}"
readonly TEST_THREADS="${TEST_THREADS:-8}"
# Coverage compiles the workspace a second time, with instrumentation, so
# this is the timeout of test-core.sh with room for that build.
readonly TIMEOUT="${TIMEOUT:-40m}"

ensure_ndk_image

run_core() {
    timeout "${TIMEOUT}" podman run --rm --network host "${proxy_run_args[@]}" "${limit_args[@]}" \
        -v "${REPO_ROOT}/rust:/src:z" -v "${CACHE_VOLUME}:/usr/local/cargo/registry" -w /src \
        "${NDK_IMAGE}" "$@"
}

echo "==> cargo llvm-cov --html"
run_core cargo llvm-cov --locked --html -j "${JOBS}" "$@" -- --test-threads "${TEST_THREADS}"

echo "==> summary"
# A second command rather than a second run: `--html` writes the report and
# prints nothing, and `report` reads the profiles the run above left behind
# instead of building and testing the workspace again.
run_core cargo llvm-cov report --summary-only

echo "==> done"
echo "rust/target/llvm-cov/html/index.html"
