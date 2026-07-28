#!/usr/bin/env bash
# Runs Gradle tasks inside the container built from Containerfile.sdk.
#
# Every other script here goes through this one, so the image name, the proxy
# handling and the cache volume are stated once.
#
#   tools/gradle.sh assembleDebug
#   tools/gradle.sh testDebugUnitTest --info
set -euo pipefail

readonly IMAGE="${IMAGE:-localhost/markdown-org-sdk:37}"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# A named volume keeps the Gradle cache between runs; without it every build
# re-downloads the whole dependency graph.
readonly CACHE_VOLUME="${CACHE_VOLUME:-markdown-org-gradle}"
# The debug signing key lives in ~/.android and is generated on first use. In
# a --rm container that means a new key every build, and the next install
# fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE against the copy already on
# the device. A volume keeps one key.
readonly KEY_VOLUME="${KEY_VOLUME:-markdown-org-android-home}"

proxy_args=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    proxy_args+=(-e "HTTPS_PROXY=${HTTPS_PROXY}" -e "HTTP_PROXY=${HTTPS_PROXY}")
fi

env_args=()
# Passed through so an instrumented run installs on the device it was aimed
# at, and not on everything adb can reach.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    env_args+=(-e "ANDROID_SERIAL=${ANDROID_SERIAL}")
fi

if ! podman image exists "${IMAGE}"; then
    echo "==> building ${IMAGE}"
    podman build --network host "${proxy_args[@]/#-e /--build-arg }" \
        -t "${IMAGE}" -f "${REPO_ROOT}/tools/Containerfile.sdk" "${REPO_ROOT}/tools"
fi

if [[ ! -d "${REPO_ROOT}/generated" ]]; then
    echo "error: generated/ is missing — run tools/build-core.sh first" >&2
    exit 1
fi

# --network host for the same reason the core build needs it: a proxy on the
# host loopback is not reachable from the container's own network. It is also
# how the container reaches an emulator started by tools/run-emulator.sh.
exec podman run --rm --network host "${proxy_args[@]}" "${env_args[@]}" \
    -v "${REPO_ROOT}:/src:z" -v "${CACHE_VOLUME}:/gradle" \
    -v "${KEY_VOLUME}:/root/.android" -w /src \
    "${IMAGE}" \
    ./gradlew --no-daemon "$@"
