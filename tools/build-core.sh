#!/usr/bin/env bash
# Builds the Rust core for the requested Android ABIs and generates the
# Kotlin bindings from the result.
#
# Everything runs inside the container image built from Containerfile.ndk:
# the NDK and the Rust Android targets never touch the host. Output:
#   rust/jniLibs/<abi>/libmarkdown_org_ffi.so   — loaded by the app
#   generated/uniffi/markdown_org_ffi/*.kt      — the Kotlin surface
set -euo pipefail

readonly IMAGE="${IMAGE:-localhost/markdown-org-ndk:r27d}"
readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ABIS="${ABIS:-arm64-v8a}"
readonly PROFILE="${PROFILE:-release}"

# The container has no route to a proxy listening on the host loopback
# unless it shares the host network.
proxy_args=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    proxy_args+=(-e "HTTPS_PROXY=${HTTPS_PROXY}" -e "HTTP_PROXY=${HTTPS_PROXY}")
fi

if ! podman image exists "${IMAGE}"; then
    echo "==> building ${IMAGE}"
    podman build -t "${IMAGE}" -f "${REPO_ROOT}/tools/Containerfile.ndk" "${REPO_ROOT}/tools"
fi

targets=()
for abi in ${ABIS}; do
    targets+=(-t "${abi}")
done

# Stripped libraries from an earlier run have to go before anything is built.
# cargo-ndk copies out of target/ only when the artefact there is newer, so a
# cached build leaves the stripped copy in place — and the binding generator
# then fails with "No UniFFI metadata found", because stripping is exactly
# what removes that metadata.
rm -rf "${REPO_ROOT}/rust/jniLibs"

echo "==> building the core for: ${ABIS} (${PROFILE})"
podman run --rm --network host "${proxy_args[@]}" \
    -v "${REPO_ROOT}/rust:/src:z" -w /src \
    "${IMAGE}" \
    cargo ndk "${targets[@]}" -o /src/jniLibs build "--${PROFILE}" -p markdown-org-ffi

# Library mode reads the UniFFI metadata out of a built library. The metadata
# is architecture-agnostic, so the bindings can be generated from any of the
# ABIs just built.
first_abi="${ABIS%% *}"
echo "==> generating the Kotlin bindings from ${first_abi}"
mkdir -p "${REPO_ROOT}/generated"
podman run --rm --network host "${proxy_args[@]}" \
    -v "${REPO_ROOT}/rust:/src:z" -v "${REPO_ROOT}/generated:/out:z" -w /src \
    "${IMAGE}" \
    cargo run -q -p uniffi-bindgen -- generate \
        --library "/src/jniLibs/${first_abi}/libmarkdown_org_ffi.so" \
        --language kotlin --out-dir /out --no-format

# Now that the metadata has been read, it can go: it lives in .symtab and is
# useless at runtime, while the entry points the app calls are in .dynsym and
# survive. Worth around 0.6 MB per ABI. Set STRIP=0 to keep the symbols when
# debugging a crash in the native code.
if [[ "${STRIP:-1}" == "1" ]]; then
    echo "==> stripping the libraries"
    podman run --rm -v "${REPO_ROOT}/rust/jniLibs:/libs:z" "${IMAGE}" \
        bash -c 'for so in /libs/*/*.so; do
            "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-all "${so}"
        done'
fi

echo "==> done"
find "${REPO_ROOT}/rust/jniLibs" "${REPO_ROOT}/generated" -type f 2>/dev/null | sed "s|${REPO_ROOT}/||"
