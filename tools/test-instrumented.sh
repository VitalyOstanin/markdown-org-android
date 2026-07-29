#!/usr/bin/env bash
# Runs the instrumented tests on the emulator: the Compose screens and the
# one test that loads the native core.
#
# Start the emulator first with tools/run-emulator.sh. Both containers share
# the host network, so the adb inside this one talks to the same device.
#
# Report: app/build/reports/androidTests/connected/index.html
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# The target is pinned to the emulator. Without this the Gradle task installs
# on every attached device, and a phone plugged into USB for something else
# would get the test APK too.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    ANDROID_SERIAL="$(adb devices | awk '/^emulator-[0-9]+\tdevice$/ { print $1; exit }')"
fi

if [[ -z "${ANDROID_SERIAL}" ]]; then
    echo "error: no booted emulator — run tools/run-emulator.sh first" >&2
    adb devices >&2
    exit 1
fi

if [[ "${ANDROID_SERIAL}" != emulator-* ]]; then
    echo "error: ANDROID_SERIAL=${ANDROID_SERIAL} is not an emulator; refusing" >&2
    exit 1
fi

export ANDROID_SERIAL
echo "==> connectedDebugAndroidTest on ${ANDROID_SERIAL}"
"${REPO_ROOT}/tools/gradle.sh" connectedDebugAndroidTest "$@"
