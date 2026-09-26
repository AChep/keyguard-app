#!/usr/bin/env bash
set -euo pipefail

objc_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

# Keep the framework list in sync with desktopLibNative/src/build.rs.
xcrun clang -fobjc-arc \
  "$objc_root/desktop_lib.m" \
  "$objc_root/tests/power_events_test.m" \
  -framework ApplicationServices -framework AppKit -framework Carbon \
  -framework Foundation -framework LocalAuthentication -framework Security \
  -framework UserNotifications -o "$test_dir/power-events-test"

"$test_dir/power-events-test"
