#!/usr/bin/env bash
# Builds the debug APK, installs it and starts the activity — the short loop
# for a change to the interface, in place of the four commands it used to be.
#
# Start the emulator first with tools/run-emulator.sh, or attach a device.
# adb takes the target from ANDROID_SERIAL, so a phone plugged in for
# something else is not touched when that is set.
#
#   tools/run-app.sh
#   ANDROID_SERIAL=emulator-5554 tools/run-app.sh
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Debug only: a release APK built here is unsigned, and installing it fails
# with INSTALL_PARSE_FAILED_NO_CERTIFICATES rather than anything explanatory.
readonly APK="${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
readonly ACTIVITY="io.github.vitalyostanin.markdownorg/.MainActivity"

"${REPO_ROOT}/tools/build-app.sh" "$@"

if [[ ! -f "${APK}" ]]; then
    echo "error: ${APK} is missing" >&2
    exit 1
fi

echo "==> installing"
adb install -r "${APK}"

echo "==> starting ${ACTIVITY}"
adb shell am start -n "${ACTIVITY}"
