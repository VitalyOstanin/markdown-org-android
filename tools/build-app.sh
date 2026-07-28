#!/usr/bin/env bash
# Assembles the APK inside the container built from Containerfile.sdk.
#
# The Rust core must be built first — tools/build-core.sh produces the
# libraries and the Kotlin bindings this build consumes. Output:
#   app/build/outputs/apk/<variant>/app-<variant>.apk
set -euo pipefail

readonly IMAGE="${IMAGE:-localhost/markdown-org-sdk:37}"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly VARIANT="${VARIANT:-debug}"
# A named volume keeps the Gradle cache between runs; without it every build
# re-downloads the whole dependency graph.
readonly CACHE_VOLUME="${CACHE_VOLUME:-markdown-org-gradle}"

proxy_args=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    proxy_args+=(-e "HTTPS_PROXY=${HTTPS_PROXY}" -e "HTTP_PROXY=${HTTPS_PROXY}")
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

task="assemble${VARIANT^}"
echo "==> ${task}"
# --network host for the same reason the core build needs it: a proxy on the
# host loopback is not reachable from the container's own network.
podman run --rm --network host "${proxy_args[@]}" \
    -v "${REPO_ROOT}:/src:z" -v "${CACHE_VOLUME}:/gradle" -w /src \
    "${IMAGE}" \
    ./gradlew --no-daemon "${task}" "$@"

echo "==> done"
find "${REPO_ROOT}/app/build/outputs/apk" -name '*.apk' 2>/dev/null | sed "s|${REPO_ROOT}/||"
