#!/bin/sh
# Switch manifest for target browser.
# Usage: ./switch-browser.sh {chrome|firefox}

DIR="$(cd "$(dirname "$0")" && pwd)"

case "${1:-}" in
  chrome)
    cp "$DIR/manifest.json" "$DIR/manifest.json.bak" 2>/dev/null
    cp "$DIR/manifest.chrome.json" "$DIR/manifest.json"
    echo "Switched to chrome"
    ;;
  firefox)
    cp "$DIR/manifest.json" "$DIR/manifest.json.bak" 2>/dev/null
    cp "$DIR/manifest.firefox.json" "$DIR/manifest.json"
    echo "Switched to firefox"
    ;;
  *)
    echo "Usage: $0 {chrome|firefox}"
    exit 1
    ;;
esac
