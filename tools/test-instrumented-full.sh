#!/usr/bin/env bash
# Runs the instrumented tests from a cold start: builds the core for the
# emulator's ABI, boots the emulator, runs the tests, stops the emulator.
#
# tools/test-instrumented.sh does none of that on purpose — it expects a
# booted emulator and a core already built for its ABI, which is what an
# interactive session has. This wraps both so an unattended run needs one
# command and leaves nothing behind.
#
# Report: app/build/reports/androidTests/connected/index.html
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# The emulator runs x86_64. arm64-v8a stays in the list because it is what a
# phone needs, and dropping it here would make the next tools/build-app.sh
# rebuild it.
# Exported rather than passed as a command prefix: a prefix assignment to a
# readonly variable is an error, and build-core.sh reads it from the
# environment anyway.
ABIS="${ABIS:-arm64-v8a x86_64}"
export ABIS
readonly ABIS

# The emulator is a container, and a failed test run would otherwise leave it
# holding the adb port for the next attempt.
stop_emulator() {
    "${REPO_ROOT}/tools/run-emulator.sh" --stop > /dev/null 2>&1 || true
}
trap stop_emulator EXIT

"${REPO_ROOT}/tools/build-core.sh"
"${REPO_ROOT}/tools/run-emulator.sh"
"${REPO_ROOT}/tools/test-instrumented.sh" "$@"
