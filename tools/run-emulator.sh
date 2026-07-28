#!/usr/bin/env bash
# Starts the headless emulator and waits until it has finished booting.
#
# The container shares the host network, so the emulator's adb port is the
# host's 5555 and the host adb talks to it directly — install, logcat and
# screencap all run from outside the container.
#
#   tools/run-emulator.sh            # start and wait for boot
#   tools/run-emulator.sh --stop     # stop it
set -euo pipefail

readonly IMAGE="${IMAGE:-localhost/markdown-org-emulator:36}"
readonly NAME="${NAME:-markdown-org-emulator}"
readonly BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"

if [[ "${1:-}" == "--stop" ]]; then
    podman rm -f "${NAME}" > /dev/null 2>&1 || true
    echo "==> stopped"
    exit 0
fi

if podman container exists "${NAME}"; then
    podman rm -f "${NAME}" > /dev/null
fi

echo "==> starting ${NAME}"
# /dev/kvm is what makes this usable: without it qemu falls back to software
# emulation and the boot takes tens of minutes.
podman run -d --rm --name "${NAME}" --network host --device /dev/kvm "${IMAGE}" > /dev/null

echo "==> waiting for boot (up to ${BOOT_TIMEOUT}s)"
deadline=$((SECONDS + BOOT_TIMEOUT))
adb start-server > /dev/null 2>&1 || true
while (( SECONDS < deadline )); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        echo "==> booted after $((SECONDS))s"
        adb devices
        exit 0
    fi
    sleep 5
done

echo "error: the emulator did not finish booting in ${BOOT_TIMEOUT}s" >&2
podman logs --tail 20 "${NAME}" >&2 || true
exit 1
