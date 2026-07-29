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

# The core is built per ABI, and tools/build-core.sh builds arm64-v8a alone
# unless told otherwise. Without the emulator's own ABI the tests that load
# the library fail as NoClassDefFoundError on UniffiLib, which says nothing
# about what is missing.
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ ! -f "${REPO_ROOT}/rust/jniLibs/${abi}/libmarkdown_org_ffi.so" ]]; then
    echo "error: the core is not built for ${abi}, which is what ${ANDROID_SERIAL} runs" >&2
    echo "       ABIS=\"arm64-v8a ${abi}\" tools/build-core.sh" >&2
    exit 1
fi

echo "==> connectedDebugAndroidTest on ${ANDROID_SERIAL}"
# Per-test the runner is bounded by timeout_msec; this bounds the run,
# including the install and the emulator going unresponsive under it.
timeout "${TIMEOUT:-40m}" "${REPO_ROOT}/tools/gradle.sh" connectedDebugAndroidTest "$@"
