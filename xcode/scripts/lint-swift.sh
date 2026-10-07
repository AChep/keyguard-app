#!/bin/sh
# Checks (or rewrites, with --fix) the formatting of every first-party Swift source
# against the repository `.swift-format` configuration. Kotlin has ktlint and Detekt,
# Rust has rustfmt and Clippy; this is the equivalent gate for the Apple UI layer.
#
# Run by the "Check Swift Format" workflow, and locally before pushing Swift changes.
#
#   xcode/scripts/lint-swift.sh          # report violations, non-zero on failure
#   xcode/scripts/lint-swift.sh --fix    # rewrite the sources in place
#
# The roots are listed explicitly because the app directories also hold build output
# (`build/`, `.build-run/`) with vendored dependency sources that must not be touched.
# Generated L10n.swift is excluded explicitly for toolchains that do not support
# `.swift-format-ignore` files.
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
    set -- --in-place
else
    # `--strict` promotes the lint findings to a non-zero exit, so CI fails on them.
    set -- lint --strict
fi

# Preserve recursive swift-format's exclusion of hidden build directories.
# shellcheck disable=SC2086 # the roots are a deliberate word-split list.
exec find $ROOTS -name '.*' -prune -o \
    -type f -name '*.swift' \
    ! -path 'appleUi/Sources/KeyguardUI/Generated/L10n.swift' \
    -exec swift format "$@" --parallel --configuration .swift-format {} +
