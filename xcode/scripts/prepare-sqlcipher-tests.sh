#!/bin/bash
set -euo pipefail

# Match the committed Xcode package locks. Keep the vendor artifact checksum
# pinned so CI never substitutes system SQLite for the encryption tests.
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
version=4.16.0
checksum=510fd00fa51fb017909a159bb1cc233b012e8ce18dc9c2f09014fe47f557c1a6
destination="${1:-$repo_root/build/sqlcipher-tests}"
python3 - "$repo_root" "$version" <<'PY'
import json
from pathlib import Path
import sys

root, expected = Path(sys.argv[1]), sys.argv[2]
for project in ("iosApp/iosApp.xcodeproj", "macosApp/Keyguard.xcodeproj"):
    lock = root / project / "project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
    package = next(pin for pin in json.loads(lock.read_text())["pins"] if pin["identity"] == "sqlcipher.swift")
    if package["state"]["version"] != expected:
        raise SystemExit("Update the SQLCipher test artifact and checksum to match " + str(lock))
PY
mkdir -p "$destination"
archive="$destination/SQLCipher-$version.zip"
if ! test -f "$archive" || ! echo "$checksum  $archive" | shasum -a 256 --check --status; then
    curl --fail --location --retry 3 \
        "https://github.com/sqlcipher/SQLCipher.swift/releases/download/$version/SQLCipher.xcframework.zip" \
        --output "$archive"
fi
echo "$checksum  $archive" | shasum -a 256 --check --status
unzip -q -o "$archive" -d "$destination"
test -d "$destination/SQLCipher.xcframework/macos-arm64_x86_64/SQLCipher.framework"
test -d "$destination/SQLCipher.xcframework/ios-arm64_x86_64-simulator/SQLCipher.framework"
echo "SQLCipher test framework: $destination/SQLCipher.xcframework"
