#!/bin/sh
# Regenerates the native String Catalog (Localizable.xcstrings) + L10n.swift from the
# shared Compose `strings.xml` / `plurals.xml`, so the SwiftUI layer's localized strings
# never drift from the single source of truth. The generated files are committed; this
# keeps them fresh on every build.
#
# Run by the shared scheme before any macOS/iOS dependency compiles its catalog
# (see xcode/keyguard-common.yml).
set -eu

cd "$SRCROOT/.."
./gradlew :common:generateAppleStrings
python3 xcode/scripts/generate-info-plist-strings.py
