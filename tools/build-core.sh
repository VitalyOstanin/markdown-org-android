#!/usr/bin/env bash
# Builds the Rust core for the requested Android ABIs and generates the
# Kotlin bindings from the result.
#
# By default everything runs inside the container image built from
# Containerfile.ndk: the NDK and the Rust Android targets never touch the
# host. Set NATIVE=1 when the toolchain is already on the machine and podman
# is not — that is what CI does. The steps are the same either way, and the
# order of the last three is what this script exists for. Output:
#   rust/jniLibs/<abi>/libmarkdown_org_ffi.so   — loaded by the app
#   generated/uniffi/markdown_org_ffi/*.kt      — the Kotlin surface
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly ABIS="${ABIS:-arm64-v8a}"
readonly PROFILE="${PROFILE:-release}"
readonly NATIVE="${NATIVE:-0}"

mkdir -p "${REPO_ROOT}/generated"

# core_dir and out_dir are the paths the commands below are given. They differ
# between the two modes, so every path handed to run_core has to go through
# them rather than being written out.
if [[ "${NATIVE}" == "1" ]]; then
    core_dir="${REPO_ROOT}/rust"
    out_dir="${REPO_ROOT}/generated"
    run_core() { ( cd "${core_dir}" && "$@" ); }
else
    core_dir=/src
    out_dir=/out
    ensure_ndk_image
    # The container has no route to a proxy listening on the host loopback
    # unless it shares the host network — building the image needs the same,
    # which is why both go through tools/lib.sh.
    run_core() {
        podman run --rm --network host "${proxy_run_args[@]}" "${limit_args[@]}" \
            -v "${REPO_ROOT}/rust:/src:z" -v "${REPO_ROOT}/generated:/out:z" -w /src \
            "${NDK_IMAGE}" "$@"
    }
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
# --locked throughout: Cargo re-resolves the lock file without saying so when
# a manifest and the lock disagree, and what gets built then differs from
# what the repository records — for a release that is published, silently.
run_core cargo ndk "${targets[@]}" -o "${core_dir}/jniLibs" build \
    "--${PROFILE}" --locked -j "${JOBS}" -p markdown-org-ffi

# Library mode reads the UniFFI metadata out of a built library. The metadata
# is architecture-agnostic, so the bindings can be generated from any of the
# ABIs just built.
first_abi="${ABIS%% *}"
echo "==> generating the Kotlin bindings from ${first_abi}"
run_core cargo run -q --locked -p uniffi-bindgen -- generate \
    --library "${core_dir}/jniLibs/${first_abi}/libmarkdown_org_ffi.so" \
    --language kotlin --out-dir "${out_dir}" --no-format

# Now that the metadata has been read, it can go: it lives in .symtab and is
# useless at runtime, while the entry points the app calls are in .dynsym and
# survive. Worth around 0.6 MB per ABI. Set STRIP=0 to keep the symbols when
# debugging a crash in the native code.
if [[ "${STRIP:-1}" == "1" ]]; then
    echo "==> stripping the libraries"
    run_core bash -c 'for so in '"${core_dir}"'/jniLibs/*/*.so; do
        "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-all "${so}"
    done'
fi

echo "==> done"
find "${REPO_ROOT}/rust/jniLibs" "${REPO_ROOT}/generated" -type f 2>/dev/null | sed "s|${REPO_ROOT}/||"
