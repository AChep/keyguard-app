#!/bin/sh
# Build the Safari Web Extension for distribution.
#
# This script builds the Safari extension using xcodebuild on macOS.
# It produces an unsigned .app (for testing) and optionally a signed .app (for distribution).
#
# Usage:
#   ./safari/build-safari-extension.sh              # unsigned (test)
#   ./safari/build-safari-extension.sh --sign        # signed with development cert
#
# Environment:
#   EXTENSION_VERSION  — version string (e.g. "20260816")
#   CERT_IDENTITY      — signing identity for --sign mode (e.g. "Developer ID Application: ...")

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAFARI_DIR="$REPO_ROOT/safari"
DIST_DIR="$REPO_ROOT/dist"
VERSION="${EXTENSION_VERSION:-0.0.0}"

# Find the Xcode project
XCODEPROJ=$(find "$SAFARI_DIR" -name "*.xcodeproj" -maxdepth 1 | head -1)
if [ -z "$XCODEPROJ" ]; then
  echo "ERROR: No .xcodeproj found in $SAFARI_DIR"
  echo "Run ./safari/generate-project.sh first to generate the project."
  exit 1
fi

PROJECT_NAME=$(basename "$XCODEPROJ" .xcodeproj)
echo "==> Building Safari extension: $PROJECT_NAME v${VERSION}"

# Determine scheme name (the converter creates a scheme named after the app)
SCHEME="$PROJECT_NAME"

# Check for xcodebuild
if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "ERROR: xcodebuild not found. This script must be run on macOS with Xcode."
  exit 1
fi

# Build configuration
BUILD_DIR="$SAFARI_DIR/build"
CONFIGURATION="Release"

# Determine signing mode
SIGN_MODE="unsigned"
CODE_SIGN_ARGS="CODE_SIGN_IDENTITY=\"-\" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO"

if [ "${1:-}" = "--sign" ]; then
  SIGN_MODE="signed"
  if [ -z "${CERT_IDENTITY:-}" ]; then
    echo "ERROR: CERT_IDENTITY must be set when using --sign"
    echo "Example: CERT_IDENTITY='Developer ID Application: Your Name (TEAMID)'"
    exit 1
  fi
  CODE_SIGN_ARGS="CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=\"${CERT_IDENTITY}\""
fi

echo "    Configuration: $CONFIGURATION"
echo "    Sign mode:     $SIGN_MODE"
echo "    Build dir:     $BUILD_DIR"

# Clean previous build
rm -rf "$BUILD_DIR"

# Build
xcodebuild \
  -project "$XCODEPROJ" \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -derivedDataPath "$BUILD_DIR" \
  $CODE_SIGN_ARGS \
  build \
  2>&1 | tail -20

# Find the built .app
APP_PATH=$(find "$BUILD_DIR" -name "${PROJECT_NAME}.app" -maxdepth 4 -type d | grep -v "Extension" | head -1)
APPEX_PATH=$(find "$BUILD_DIR" -name "*.appex" -maxdepth 6 -type d | head -1)

if [ -z "$APP_PATH" ]; then
  echo "ERROR: Built .app not found in $BUILD_DIR"
  find "$BUILD_DIR" -name "*.app" 2>/dev/null
  exit 1
fi

echo "    Built app:  $APP_PATH"

# For unsigned mode, apply ad-hoc signature (Safari requires at least this)
if [ "$SIGN_MODE" = "unsigned" ]; then
  echo "    Applying ad-hoc signature for Safari acceptance..."
  codesign --sign - --force --deep "$APP_PATH" 2>/dev/null || true
fi

# Copy to dist
mkdir -p "$DIST_DIR"
ARCHIVE="$DIST_DIR/keyguard-safari-extension-${SIGN_MODE}.app"
rm -rf "$ARCHIVE"
cp -R "$APP_PATH" "$ARCHIVE"
echo "    -> $ARCHIVE"

if [ -n "$APPEX_PATH" ]; then
  echo "    Extension: $APPEX_PATH"
fi

echo "==> Done."
echo ""
echo "To test: open '$ARCHIVE' (Safari will prompt to enable the extension)"
