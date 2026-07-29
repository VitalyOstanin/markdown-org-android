#!/usr/bin/env bash
# Collects what the published APK carries besides this project, and writes it
# where both the application and a reader of the repository can find it.
#
#   tools/licenses.sh           # regenerate the notices
#   tools/licenses.sh --check   # fail if what is committed is out of date
#
# Two halves make up the list. The Kotlin one is the Gradle graph, collected
# by the licensee plugin at build time and packaged as assets/licenses.json —
# nothing here has to do anything about it. The other is the native library:
# the crates the core links, plus libgit2, OpenSSL and the bundle of root
# certificates, none of which appear in the Gradle graph at all.
#
# Written to:
#   app/src/main/assets/licenses-core.json   read by the licences screen
#   NOTICE                                   the same list, for a reader here
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly CACHE_VOLUME="${CACHE_VOLUME:-markdown-org-cargo}"
# A cold run compiles nothing but does download the crates it has to read the
# licences out of.
readonly TIMEOUT="${TIMEOUT:-30m}"

readonly ASSET="${REPO_ROOT}/app/src/main/assets/licenses-core.json"
readonly NOTICE="${REPO_ROOT}/NOTICE"

check=0
if [[ "${1:-}" == "--check" ]]; then
    check=1
    shift
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# The FFI crate alone, not the workspace: the binding generator beside it runs
# on the machine doing the building and is not in the APK, and it drags in clap
# and a template engine of its own. The vendored sources are read from the same
# registry the build compiles out of — the licence that matters is the one in
# the crate actually built, not whatever upstream publishes today.
readonly COLLECT='
    set -euo pipefail
    cargo about generate --format json --locked --fail \
        --manifest-path markdown-org-ffi/Cargo.toml -o "${OUT}/crates.json"
    cp "$(ls -d "${REGISTRY}"/src/*/libgit2-sys-*/libgit2 | head -1)/COPYING" "${OUT}/libgit2.txt"
    cp "$(ls -d "${REGISTRY}"/src/*/openssl-src-*/openssl/LICENSE.txt | head -1)" "${OUT}/openssl.txt"
    ls -d "${REGISTRY}"/src/*/libgit2-sys-* "${REGISTRY}"/src/*/openssl-src-* \
        | sed "s|.*/||" > "${OUT}/vendored.txt"
'

echo "==> cargo about"
if [[ "${NATIVE:-0}" == "1" ]]; then
    # CI, where the toolchain is on the machine and podman is not. cargo-about
    # is installed by the workflow, at the version tools/versions.env pins.
    (
        cd "${REPO_ROOT}/rust"
        OUT="${work}" REGISTRY="${CARGO_HOME:-${HOME}/.cargo}/registry" \
            timeout "${TIMEOUT}" bash -c "${COLLECT}"
    )
else
    ensure_ndk_image
    timeout "${TIMEOUT}" podman run --rm --network host "${proxy_run_args[@]}" "${limit_args[@]}" \
        -v "${REPO_ROOT}/rust:/src:z" -v "${CACHE_VOLUME}:/usr/local/cargo/registry" \
        -v "${work}:/out:z" -w /src \
        -e OUT=/out -e REGISTRY=/usr/local/cargo/registry \
        "${NDK_IMAGE}" \
        bash -c "${COLLECT}"
fi

# The version of each vendored project, as the crate that carries it names it:
# libgit2-sys-0.18.7+1.9.6 vendors libgit2 1.9.6.
vendored_version() {
    grep "^$1-" "${work}/vendored.txt" | head -1 | sed 's/.*+//'
}

echo "==> assembling"
# One entry per distinct licence text: two crates under MIT carry different
# copyright lines, and dropping one of them would drop an attribution.
jq -n \
    --slurpfile crates "${work}/crates.json" \
    --rawfile libgit2 "${work}/libgit2.txt" \
    --rawfile openssl "${work}/openssl.txt" \
    --arg libgit2Version "$(vendored_version libgit2-sys)" \
    --arg opensslVersion "$(vendored_version openssl-src)" \
    '
    def component(name; version; url): {name: name, version: version, url: url};

    # The certificate bundle is Mozilla data under the same licence uniffi
    # brings in, so the text is already here rather than a copy of its own.
    def mpl: $crates[0].licenses | map(select(.id == "MPL-2.0")) | first | .text;

    ($crates[0].licenses | map({
        id: .id,
        name: .name,
        text: .text,
        usedBy: [.used_by[] | component(.crate.name; .crate.version; (.crate.repository // ""))]
    }))
    + [
        {
            id: "GPL-2.0-only WITH libgit2 linking exception",
            name: "GNU General Public License v2.0 with the libgit2 linking exception",
            text: $libgit2,
            usedBy: [component("libgit2"; $libgit2Version; "https://github.com/libgit2/libgit2")]
        },
        {
            id: "Apache-2.0",
            name: "Apache License 2.0",
            text: $openssl,
            usedBy: [component("openssl"; $opensslVersion; "https://github.com/openssl/openssl")]
        },
        {
            id: "MPL-2.0",
            name: "Mozilla Public License 2.0",
            text: mpl,
            usedBy: [component("Mozilla CA certificate store"; "2026-07-16"; "https://curl.se/docs/caextract.html")]
        }
    ]
    | map(.usedBy |= (unique_by(.name + .version) | sort_by(.name, .version)))
    | sort_by(.id, (.usedBy[0].name), .text)
    ' > "${work}/licenses-core.json"

{
    cat <<'HEADER'
markdown-org-android
Copyright (c) 2026 Vitaly Ostanin

This product is licensed under the MIT licence; see LICENSE.

The published APK carries more than the sources of this repository. What
follows is every component compiled into or packaged with the native library,
the licence it is under and the full text of that licence. The Kotlin
dependencies — Compose, AndroidX, kotlinx-coroutines and JNA — are listed in
the application itself, under Licences, from a list the build collects out of
the Gradle graph; JNA is dual-licensed and is taken here under Apache-2.0.

Generated by tools/licenses.sh. Do not edit by hand.

HEADER

    jq -r '
        (map("  " + .id + " — " + ([.usedBy[].name] | join(", "))) | join("\n")),
        "",
        (map(
            "================================================================\n"
            + .name + " (" + .id + ")\n"
            + "Applies to: " + ([.usedBy[] | .name + " " + .version] | join(", ")) + "\n"
            + "================================================================\n\n"
            + .text
        ) | join("\n\n"))
    ' "${work}/licenses-core.json"
} > "${work}/NOTICE"

if [[ "${check}" -eq 1 ]]; then
    status=0
    diff -q "${work}/licenses-core.json" "${ASSET}" > /dev/null || status=1
    diff -q "${work}/NOTICE" "${NOTICE}" > /dev/null || status=1

    if [[ "${status}" -ne 0 ]]; then
        echo "==> the notices are out of date; run tools/licenses.sh" >&2
        exit 1
    fi

    echo "==> up to date"
    exit 0
fi

cp "${work}/licenses-core.json" "${ASSET}"
cp "${work}/NOTICE" "${NOTICE}"

echo "==> wrote app/src/main/assets/licenses-core.json and NOTICE"
