#!/bin/sh
# Build KeyguardShared, generate strings, and run XcodeGen before this script.
set -eu
cd "$(dirname "$0")/../.."
xcodebuild -project macosApp/Keyguard.xcodeproj \
  -scheme 'Keyguard Billing Tests' -destination 'platform=macOS' \
  -parallel-testing-enabled NO -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 30 -maximum-test-execution-time-allowance 60 test "$@"
