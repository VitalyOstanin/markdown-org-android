#!/usr/bin/env bash
# Runs the checks of the Kotlin half in the SDK container — the same two
# tasks CI fails on, so a push does not have to be the first time they are
# tried. The counterpart of tools/check-core.sh for the core.
#
#   tools/lint.sh            # check the formatting and run Android Lint
#   tools/lint.sh --format   # rewrite what the formatter can fix, then check
#
# Report: app/build/reports/lint-results-debug.html
set -euo pipefail

if [[ "${1:-}" == "--format" ]]; then
    shift
    set -- ktlintFormat "$@"
else
    set -- ktlintCheck lintDebug "$@"
fi

exec "$(dirname "${BASH_SOURCE[0]}")/gradle.sh" "$@"
