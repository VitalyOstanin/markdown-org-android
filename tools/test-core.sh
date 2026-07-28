#!/usr/bin/env bash
# Runs the Rust tests of the core, on the host architecture, inside the NDK
# container.
#
# The tests never reach the network: the sync ones work against a repository
# on disk, which is the same code path up to the transport.
set -euo pipefail

readonly IMAGE="${IMAGE:-localhost/markdown-org-ndk:r27d}"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# A named volume for the cargo registry and the build directory; a vendored
# OpenSSL and libgit2 are minutes of work to rebuild from scratch.
readonly CACHE_VOLUME="${CACHE_VOLUME:-markdown-org-cargo}"

proxy_args=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    proxy_args+=(-e "HTTPS_PROXY=${HTTPS_PROXY}" -e "HTTP_PROXY=${HTTPS_PROXY}")
fi

if ! podman image exists "${IMAGE}"; then
    echo "==> building ${IMAGE}"
    podman build --network host "${proxy_args[@]/#-e /--build-arg }" \
        -t "${IMAGE}" -f "${REPO_ROOT}/tools/Containerfile.ndk" "${REPO_ROOT}/tools"
fi

echo "==> cargo test"
podman run --rm --network host "${proxy_args[@]}" \
    -v "${REPO_ROOT}/rust:/src:z" -v "${CACHE_VOLUME}:/usr/local/cargo/registry" -w /src \
    "${IMAGE}" \
    cargo test -p markdown-org-ffi "$@"
