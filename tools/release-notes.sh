#!/usr/bin/env bash
# Prints the notes of one release: what CHANGELOG.md says about a version.
#
#   tools/release-notes.sh 0.1.0    # the section of that version
#   tools/release-notes.sh          # the version gradle.properties is at
#
# A version with no section of its own falls back to Unreleased, which is
# where the changes of a version being worked towards are collected. The
# heading itself is left out: the release carries the version in its title
# already.
#
# Used by the publish job of .github/workflows/build.yml, so that the notes of
# a release are the lines somebody wrote rather than a list of commit
# subjects.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT

readonly CHANGELOG="${REPO_ROOT}/CHANGELOG.md"

version="${1:-}"
if [[ -z "${version}" ]]; then
    version="$(sed -n 's/^appVersionName=\(.*\)$/\1/p' "${REPO_ROOT}/gradle.properties")"
fi
readonly version

# Everything between the heading of this version and the next heading of the
# same level, with the surrounding blank lines taken off.
section() {
    awk -v heading="## [$1]" '
        index($0, heading) == 1 { inside = 1; next }
        inside && /^## / { exit }
        inside { print }
    ' "${CHANGELOG}" | sed -e '/./,$!d' -e :a -e '/^\n*$/{$d;N;ba' -e '}'
}

notes="$(section "${version}")"
if [[ -z "${notes}" ]]; then
    notes="$(section Unreleased)"
fi

if [[ -z "${notes}" ]]; then
    echo "no section for ${version} and no Unreleased section in CHANGELOG.md" >&2
    exit 1
fi

printf '%s\n' "${notes}"
