#!/usr/bin/env bash
# Measures how much of the Kotlin sources the JVM tests reach, with Kover.
#
# Report: app/build/reports/kover/html/index.html
#
# No threshold is enforced: the number is here to be looked at, and the
# instrumented tests — the ones that cover the screens and the calls into the
# core — are not counted, so a low figure is expected.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

readonly TIMEOUT="${TIMEOUT:-20m}"

echo "==> koverHtmlReportDebug"
timeout "${TIMEOUT}" "${REPO_ROOT}/tools/gradle.sh" koverHtmlReportDebug "$@"

echo "==> done"
echo "app/build/reports/kover/htmlDebug/index.html"
