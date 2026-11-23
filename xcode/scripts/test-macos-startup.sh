#!/bin/sh
# Uses the actual application scene/delegate with an isolated, vault-free service fixture.
# A required GUI lane must fail, rather than skip, when WindowServer is unavailable.
set -eu
cd "$(dirname "$0")/../.."
swift -e 'import CoreGraphics; guard let session = CGSessionCopyCurrentDictionary() as? [String: Any], session[kCGSessionOnConsoleKey as String] as? Bool == true else { fatalError("Startup UI tests require an active macOS console session") }'
xcodebuild -project macosApp/Keyguard.xcodeproj \
  -scheme 'Keyguard Startup Tests' -destination 'platform=macOS' \
  -parallel-testing-enabled NO -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 30 -maximum-test-execution-time-allowance 60 test "$@"
