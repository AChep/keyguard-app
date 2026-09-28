#!/bin/sh
# Checks (or rewrites, with --fix) the formatting of every first-party Swift source
# against the repository `.swift-format` configuration. Kotlin has ktlint and Detekt,
# Rust has rustfmt and Clippy; this is the equivalent gate for the Apple UI layer.
#
# Run by the "Check Lint" workflow, and locally before pushing Swift changes.
#
#   xcode/scripts/lint-swift.sh          # report violations, non-zero on failure
#   xcode/scripts/lint-swift.sh --fix    # rewrite the sources in place
#
# The roots are listed explicitly because the app directories also hold build output
# (`build/`, `.build-run/`) with vendored dependency sources that must not be touched.
# Generated code opts out through `.swift-format-ignore` next to it.
set -eu

cd "$(dirname "$0")/../.."

ROOTS="\
appleUi/Package.swift \
appleUi/Sources \
appleUi/Tests \
appleAutofill \
iosApp/iosApp \
macosApp/macosApp \
xcode/BillingTests \
xcode/PrivacyTests"

if [ "${1:-}" = "--fix" ]; then
    # shellcheck disable=SC2086 # the roots are a deliberate word-split list.
    exec swift format --in-place --parallel --recursive --configuration .swift-format $ROOTS
fi

# `--strict` promotes the lint findings to a non-zero exit, so CI fails on them.
# shellcheck disable=SC2086 # the roots are a deliberate word-split list.
exec swift format lint --strict --parallel --recursive --configuration .swift-format $ROOTS
