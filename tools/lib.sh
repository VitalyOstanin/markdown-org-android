# Shared by the scripts in this directory: the repository root, the pinned
# versions, the proxy plumbing and building an image the first time it is
# needed.
#
# Sourced, never executed; each script keeps its own `set -euo pipefail`.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT

# shellcheck source=versions.env
source "${REPO_ROOT}/tools/versions.env"

# The tag carries the version the image was built for, so a bump to
# versions.env asks for a new image instead of silently reusing the old one.
readonly NDK_IMAGE="${NDK_IMAGE:-localhost/markdown-org-ndk:${NDK_RELEASE}-${CARGO_ABOUT_VERSION}-${CARGO_LLVM_COV_VERSION}}"
readonly SDK_IMAGE="${SDK_IMAGE:-localhost/markdown-org-sdk:${ANDROID_COMPILE_SDK}}"
readonly EMULATOR_IMAGE="${EMULATOR_IMAGE:-localhost/markdown-org-emulator:${ANDROID_EMULATOR_API}}"

# A proxy listening on the host loopback is unreachable from a container on
# its own network, which is why every run and every build below shares the
# host's. HTTP_PROXY is set from the same value: curl and apt pick the
# variable matching the scheme of the URL, and the images fetch over both.
proxy_run_args=()
proxy_build_args=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    proxy_run_args+=(-e "HTTPS_PROXY=${HTTPS_PROXY}" -e "HTTP_PROXY=${HTTPS_PROXY}")
    proxy_build_args+=(--build-arg "HTTPS_PROXY=${HTTPS_PROXY}" --build-arg "HTTP_PROXY=${HTTPS_PROXY}")
fi

# How much of the machine a container may take. Left alone, a cargo build
# takes every core: the vendored libgit2 and OpenSSL are a lot of C to
# compile, and the machine has other work to do. Raise deliberately, with
# JOBS and MEMORY, rather than by default.
readonly JOBS="${JOBS:-8}"
readonly MEMORY="${MEMORY:-8g}"
limit_args=(--cpus "${JOBS}" --memory "${MEMORY}")

# ensure_image IMAGE CONTAINERFILE [build args...]
#
# podman build has no -e: run-time and build-time variables are different
# flags, and passing the run-time form here fails the build outright.
ensure_image() {
    local image="$1" containerfile="$2"
    shift 2

    if podman image exists "${image}"; then
        return 0
    fi

    echo "==> building ${image}"
    podman build --network host "${proxy_build_args[@]}" "$@" \
        -t "${image}" -f "${REPO_ROOT}/tools/${containerfile}" "${REPO_ROOT}/tools"
}

ensure_ndk_image() {
    ensure_image "${NDK_IMAGE}" Containerfile.ndk \
        --build-arg "NDK_RELEASE=${NDK_RELEASE}" \
        --build-arg "NDK_SHA256=${NDK_SHA256}" \
        --build-arg "CARGO_ABOUT_VERSION=${CARGO_ABOUT_VERSION}" \
        --build-arg "CARGO_ABOUT_SHA256=${CARGO_ABOUT_SHA256}" \
        --build-arg "CARGO_LLVM_COV_VERSION=${CARGO_LLVM_COV_VERSION}" \
        --build-arg "CARGO_LLVM_COV_SHA256=${CARGO_LLVM_COV_SHA256}"
}

ensure_sdk_image() {
    ensure_image "${SDK_IMAGE}" Containerfile.sdk \
        --build-arg "JDK_VERSION=${JDK_VERSION}" \
        --build-arg "ANDROID_PLATFORM=${ANDROID_PLATFORM}" \
        --build-arg "ANDROID_BUILD_TOOLS=${ANDROID_BUILD_TOOLS}" \
        --build-arg "CMDLINE_TOOLS_RELEASE=${CMDLINE_TOOLS_RELEASE}" \
        --build-arg "CMDLINE_TOOLS_SHA256=${CMDLINE_TOOLS_SHA256}" \
        --build-arg "GRADLE_VERSION=${GRADLE_VERSION}" \
        --build-arg "GRADLE_SHA256=${GRADLE_SHA256}"
}

# The name the emulator container runs under, shared by the script that
# starts it and by adb_cmd below.
readonly EMULATOR_NAME="${EMULATOR_NAME:-markdown-org-emulator}"

# adb against the emulator, without requiring platform-tools on the machine
# running these scripts.
#
# The host's adb is preferred when it exists: an interactive session already
# talks to it, and its server owns port 5037. Where it is missing, the call
# goes into the emulator container, which carries platform-tools of its own —
# without this, every check around the emulator silently produced no output
# and the wait for boot ran into its timeout while the emulator was up.
adb_cmd() {
    if command -v adb > /dev/null 2>&1; then
        adb "$@"
    else
        podman exec "${EMULATOR_NAME}" adb "$@"
    fi
}

# The emulator image is the SDK one plus a system image, so the base has to
# exist before it can be built.
ensure_emulator_image() {
    ensure_sdk_image
    ensure_image "${EMULATOR_IMAGE}" Containerfile.emulator \
        --build-arg "SDK_IMAGE=${SDK_IMAGE}" \
        --build-arg "ANDROID_EMULATOR_API=${ANDROID_EMULATOR_API}"
}
