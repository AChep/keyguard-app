#!/bin/sh
# Generate the iOS and macOS Xcode projects from their XcodeGen specs. The projects
# are not committed: XcodeGen names a group after the checkout folder, so every
# checkout would produce a different project file.
set -eu
cd "$(dirname "$0")/../.."
if ! command -v xcodegen >/dev/null 2>&1; then
    echo "error: XcodeGen is not installed. Install it with: brew install xcodegen" >&2
    exit 1
fi
xcodegen generate --quiet --spec iosApp/project.yml
xcodegen generate --quiet --spec macosApp/project.yml
