#!/usr/bin/env bash
# Assembles the APK inside the container built from Containerfile.sdk.
#
# The Rust core must be built first — tools/build-core.sh produces the
# libraries and the Kotlin bindings this build consumes. Output:
#   app/build/outputs/apk/<variant>/app-<variant>.apk
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly VARIANT="${VARIANT:-debug}"

task="assemble${VARIANT^}"
echo "==> ${task}"
"${REPO_ROOT}/tools/gradle.sh" "${task}" "$@"

echo "==> done"
find "${REPO_ROOT}/app/build/outputs/apk" -name '*.apk' 2>/dev/null | sed "s|${REPO_ROOT}/||"
