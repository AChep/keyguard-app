#!/bin/sh
# Generate the Safari Web Extension Xcode project from the extension/ folder.
#
# This script must be run ONCE on macOS with Xcode installed.
# The generated project is then committed to the repo for CI builds.
#
# Usage: ./safari/generate-project.sh

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXTENSION_DIR="$REPO_ROOT/extension"
OUTPUT_DIR="$REPO_ROOT/safari"

# Check for Xcode
if ! command -v xcrun >/dev/null 2>&1; then
  echo "ERROR: xcrun not found. This script must be run on macOS with Xcode installed."
  exit 1
fi

# Check for the packager tool
if ! xcrun safari-web-extension-packager --help >/dev/null 2>&1; then
  echo "ERROR: safari-web-extension-packager not found."
  echo "Make sure Xcode 15+ is installed."
  echo "On older Xcode, try: xcrun safari-web-extension-converter --help"
  exit 1
fi

echo "==> Generating Safari Web Extension project..."
echo "    Extension source: $EXTENSION_DIR"
echo "    Output:           $OUTPUT_DIR"

xcrun safari-web-extension-packager "$EXTENSION_DIR" \
  --app-name "Keyguard Autofill" \
  --bundle-identifier "com.artemchep.keyguard.autofill" \
  --macos-only \
  --copy-resources \
  --swift \
  --force \
  --no-open \
  --no-prompt \
  --project-location "$OUTPUT_DIR"

echo ""
echo "==> Project generated in $OUTPUT_DIR"
echo "    You can now open the .xcodeproj in Xcode to test."
echo ""
echo "Next steps:"
echo "  1. Open safari/Keyguard Autofill.xcodeproj in Xcode"
echo "  2. Select the 'Keyguard Autofill' scheme"
echo "  3. Build and run (Cmd+R) to test in Safari"
echo "  4. Enable the extension in Safari > Settings > Extensions"
echo ""
echo "For CI builds, use: ./safari/build-safari-extension.sh"
