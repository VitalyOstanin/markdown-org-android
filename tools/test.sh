#!/usr/bin/env bash
# Runs the JVM tests: the agenda projections, which need neither a device nor
# the native library.
#
# Report: app/build/reports/tests/testDebugUnitTest/index.html
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Each test is bounded by testOptions in app/build.gradle.kts; this bounds
# everything around them — a Gradle task that stops making progress, or a
# daemon waiting on a lock that is never released.
readonly TIMEOUT="${TIMEOUT:-20m}"

echo "==> testDebugUnitTest"
timeout "${TIMEOUT}" "${REPO_ROOT}/tools/gradle.sh" testDebugUnitTest "$@"
