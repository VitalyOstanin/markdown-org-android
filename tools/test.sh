#!/usr/bin/env bash
# Runs the JVM tests: the agenda projections, which need neither a device nor
# the native library.
#
# Report: app/build/reports/tests/testDebugUnitTest/index.html
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

echo "==> testDebugUnitTest"
"${REPO_ROOT}/tools/gradle.sh" testDebugUnitTest "$@"
