#!/bin/sh
# Build browser extension packages for Chrome and Firefox.
#
# Usage:
#   ./build-extension.sh chrome    # Chrome/Chromium/Edge (.zip)
#   ./build-extension.sh firefox   # Firefox (.zip + .xpi)
#   ./build-extension.sh all       # Both
#
# Environment:
#   EXTENSION_VERSION  — version string (e.g. "20260816" from a git tag).
#                        Defaults to "0.0.0" if not set.

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${DIR}/../dist"
VERSION="${EXTENSION_VERSION:-0.0.0}"

# Files to exclude from the zip (build artifacts, scripts, browser-specific manifests)
EXCLUDE_LIST=(
  "manifest.json.bak"
  "manifest.chrome.json"
  "manifest.firefox.json"
  "switch-browser.sh"
  "build-extension.sh"
  ".gitignore"
  "*.bak"
)

build_chrome() {
  echo "==> Building Chrome/Chromium extension v${VERSION}"

  # Prepare manifest
  cp "$DIR/manifest.json" "$DIR/manifest.json.bak" 2>/dev/null || true
  cp "$DIR/manifest.chrome.json" "$DIR/manifest.json"
  inject_version

  # Package
  mkdir -p "$DIST_DIR"
  local archive="$DIST_DIR/keyguard-extension-chrome.zip"
  rm -f "$archive"

  # Build exclude args for zip
  local exclude_args=()
  for pattern in "${EXCLUDE_LIST[@]}"; do
    exclude_args+=(-x "*${pattern}*")
  done

  (cd "$DIR" && zip -r "$archive" . "${exclude_args[@]}")
  echo "    -> $archive"

  # Cleanup
  restore_manifest
}

build_firefox() {
  echo "==> Building Firefox extension v${VERSION}"

  # Prepare manifest
  cp "$DIR/manifest.json" "$DIR/manifest.json.bak" 2>/dev/null || true
  cp "$DIR/manifest.firefox.json" "$DIR/manifest.json"
  inject_version

  # Package zip
  mkdir -p "$DIST_DIR"
  local zip_archive="$DIST_DIR/keyguard-extension-firefox.zip"
  local xpi_archive="$DIST_DIR/keyguard-browser-extension.xpi"
  rm -f "$zip_archive" "$xpi_archive"

  local exclude_args=()
  for pattern in "${EXCLUDE_LIST[@]}"; do
    exclude_args+=(-x "*${pattern}*")
  done

  (cd "$DIR" && zip -r "$zip_archive" . "${exclude_args[@]}")
  echo "    -> $zip_archive"

  # Firefox accepts .zip for upload, but .xpi is the traditional format.
  # Copy zip as .xpi (both work for AMO upload).
  cp "$zip_archive" "$xpi_archive"
  echo "    -> $xpi_archive"

  # If AMO credentials are available, produce a signed .xpi
  if [ -n "${AMO_JWT_ISSUER:-}" ] && [ -n "${AMO_JWT_SECRET:-}" ]; then
    echo "    AMO credentials found, attempting web-ext sign..."
    if command -v npx >/dev/null 2>&1; then
      local signed_xpi="$DIST_DIR/keyguard-browser-extension-signed.xpi"
      npx web-ext sign \
        --source-dir="$DIR" \
        --artifacts-dir="$DIST_DIR" \
        --filename="keyguard-browser-extension-signed.xpi" \
        --channel=listed \
        --no-config-discovery \
        2>&1 || echo "    WARNING: web-ext sign failed (expected without AMO approval)"
    else
      echo "    WARNING: npx not found, skipping web-ext sign"
    fi
  else
    echo "    AMO credentials not set, skipping web-ext sign"
    echo "    Set AMO_JWT_ISSUER and AMO_JWT_SECRET to enable signing"
  fi

  # Cleanup
  restore_manifest
}

inject_version() {
  # Replace "version": "..." in manifest.json with the build version
  if [ "$VERSION" != "0.0.0" ]; then
    local tmp="$DIR/manifest.json.tmp"
    sed "s/\"version\": *\"[^\"]*\"/\"version\": \"${VERSION}\"/" \
      "$DIR/manifest.json" > "$tmp" && mv "$tmp" "$DIR/manifest.json"
  fi
}

restore_manifest() {
  # Restore original manifest.json from backup
  if [ -f "$DIR/manifest.json.bak" ]; then
    mv "$DIR/manifest.json.bak" "$DIR/manifest.json"
  else
    rm -f "$DIR/manifest.json"
  fi
}

# --- Main ---

case "${1:-}" in
  chrome)
    build_chrome
    ;;
  firefox)
    build_firefox
    ;;
  all)
    build_chrome
    build_firefox
    ;;
  *)
    echo "Usage: $0 {chrome|firefox|all}"
    echo ""
    echo "Environment variables:"
    echo "  EXTENSION_VERSION  Version string (default: 0.0.0)"
    echo "  AMO_JWT_ISSUER     AMO API issuer for Firefox signing (optional)"
    echo "  AMO_JWT_SECRET     AMO API secret for Firefox signing (optional)"
    exit 1
    ;;
esac

echo "==> Done."
