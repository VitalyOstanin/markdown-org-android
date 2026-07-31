#!/usr/bin/env bash
# Reads a built APK back and checks that shrinking left the path into the core
# intact.
#
#   tools/check-apk.sh app/build/outputs/apk/release/app-release.apk
#
# Why this and not a test run: the core is reached through JNA, by name, so R8
# sees none of it and app/proguard-rules.pro is what holds it. Instrumented
# tests would be the stronger check, but they cannot run against the shrunk
# build — AGP leaves out of the test APK whatever the application already
# ships, so every class R8 drops from the application takes the runner with it
# (androidx.tracing.Trace, then kotlin.LazyKt, and on). This reads the APK for
# the names that must survive, which is the failure those rules exist to
# prevent: a build that installs and dies at the first call into the core.
#
# The names below are what the rules keep, on both sides of the boundary: the
# JNA interface the bindings declare, the JNA class it implements, and two of
# the symbols the native library exports — a function and a method on the
# index. A renamed or removed one of these means the APK is broken in the way
# nothing else here would notice.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly APK="${1:-${REPO_ROOT}/app/build/outputs/apk/release/app-release.apk}"

if [[ ! -f "${APK}" ]]; then
    echo "error: no APK at ${APK}" >&2
    echo "       tools/build-core.sh && VARIANT=release tools/build-app.sh" >&2
    exit 1
fi

required=(
    'uniffi/markdown_org_ffi/UniffiLib'
    'com/sun/jna/Library'
    'uniffi_markdown_org_ffi_fn_func_scan_agenda'
    'uniffi_markdown_org_ffi_fn_method_notesindex_agenda'
)

echo "==> reading $(basename "${APK}")"
# Every dex of the APK at once: which one a class lands in is R8's business.
names="$(mktemp)"
trap 'rm -f "${names}"' EXIT
unzip -p "${APK}" '*.dex' | strings > "${names}"

status=0
for name in "${required[@]}"; do
    if grep -qF -- "${name}" "${names}"; then
        echo "    ok      ${name}"
    else
        echo "    MISSING ${name}" >&2
        status=1
    fi
done

if [[ "${status}" -ne 0 ]]; then
    echo "==> the shrunk APK no longer carries the path into the core" >&2
    echo "    add what is missing to app/proguard-rules.pro" >&2
    exit 1
fi

echo "==> the path into the core survived the shrinking"
