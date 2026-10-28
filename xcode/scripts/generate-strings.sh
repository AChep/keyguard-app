#!/bin/sh
# Regenerates the native String Catalog (Localizable.xcstrings) + L10n.swift from the
# shared Compose `strings.xml` / `plurals.xml`, so the SwiftUI layer's localized strings
# never drift from the single source of truth. The generated files are git-ignored.
#
# Run by generate-projects.sh, because XcodeGen and SwiftPM need the files before any
# build starts, and by the shared scheme before any macOS/iOS dependency compiles its
# catalog (see xcode/keyguard-common.yml).
set -eu

cd "$(dirname "$0")/../.."
./gradlew :common:generateAppleStrings
python3 xcode/scripts/generate-info-plist-strings.py
