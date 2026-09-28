#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-run}"
APP_NAME="Keyguard"
BUNDLE_ID="com.artemchep.keyguard.mac"
SCHEME="Keyguard"
CONFIGURATION="Debug"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_FILE="$ROOT_DIR/macosApp/Keyguard.xcodeproj"
DERIVED_DATA_DIR="$ROOT_DIR/build/xcode/macosApp"
PRODUCTS_DIR="$DERIVED_DATA_DIR/Build/Products"
APP_BUNDLE="$PRODUCTS_DIR/$CONFIGURATION/$APP_NAME.app"

if [ -z "${DEVELOPER_DIR:-}" ]; then
  if [ -d "/Applications/Xcode.app/Contents/Developer" ]; then
    export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
  elif [ -d "/Applications/Xcode-beta.app/Contents/Developer" ]; then
    export DEVELOPER_DIR="/Applications/Xcode-beta.app/Contents/Developer"
  else
    echo "error: full Xcode not found. Install Xcode or set DEVELOPER_DIR." >&2
    exit 1
  fi
fi

usage() {
  echo "usage: $0 [run|--debug|--logs|--telemetry|--verify]" >&2
}

kill_existing() {
  pkill -x "$APP_NAME" >/dev/null 2>&1 || true
}

build_app() {
  local signing_args
  if [ "${KEYGUARD_MACOS_UNSIGNED:-0}" = "1" ]; then
    signing_args=(
      CODE_SIGNING_ALLOWED=NO
      CODE_SIGNING_REQUIRED=NO
      CODE_SIGN_IDENTITY=
    )
  else
    signing_args=(
      -allowProvisioningUpdates
    )
  fi

  /usr/bin/xcodebuild \
    -project "$PROJECT_FILE" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination "platform=macOS,arch=arm64" \
    -derivedDataPath "$DERIVED_DATA_DIR" \
    "${signing_args[@]}" \
    build

  if [ ! -d "$APP_BUNDLE" ]; then
    local discovered
    discovered="$(find "$PRODUCTS_DIR" -path "*/$APP_NAME.app" -type d | head -n 1 || true)"
    if [ -z "$discovered" ]; then
      echo "error: built app bundle not found under $PRODUCTS_DIR" >&2
      exit 1
    fi
    APP_BUNDLE="$discovered"
  fi
}

open_app() {
  /usr/bin/open -n "$APP_BUNDLE"
}

kill_existing
build_app

case "$MODE" in
  run)
    open_app
    ;;
  --debug|debug)
    lldb -- "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
    ;;
  --logs|logs)
    open_app
    /usr/bin/log stream --info --style compact --predicate "process == \"$APP_NAME\""
    ;;
  --telemetry|telemetry)
    open_app
    /usr/bin/log stream --info --style compact --predicate "subsystem == \"$BUNDLE_ID\""
    ;;
  --verify|verify)
    open_app
    sleep 3
    pgrep -x "$APP_NAME" >/dev/null
    ;;
  *)
    usage
    exit 2
    ;;
esac
