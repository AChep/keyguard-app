#!/usr/bin/env bash
set -euo pipefail

tarball=$(realpath "${1:?Expected a Linux release tarball}")
arch=${2:?Expected x86_64 or aarch64}
version=${3:?Expected a semantic version}
case "$arch" in
  x86_64|aarch64) ;;
  *) echo "Unsupported AppImage architecture: $arch" >&2; exit 1 ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
test -s "$tarball"
command -v zsyncmake > /dev/null || {
  echo "zsyncmake is required to generate AppImage delta updates" >&2
  exit 1
}

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
output_dir="$repo_dir/desktopApp/build/appimage"
mkdir -p "$output_dir"
work_dir=$(mktemp -d "$output_dir/work-$arch.XXXXXX")
trap 'rm -rf -- "$work_dir"' EXIT
app_dir="$work_dir/Keyguard.AppDir"
mkdir -p "$app_dir/usr"
tar -xzf "$tarball" -C "$app_dir/usr" --strip-components=1

app_id=com.artemchep.keyguard
test -s "$app_dir/usr/share/applications/$app_id.desktop"
test -s "$app_dir/usr/share/metainfo/$app_id.metainfo.xml"
test -s "$app_dir/usr/share/icons/hicolor/scalable/apps/$app_id.svg"
ln -s "usr/share/applications/$app_id.desktop" "$app_dir/$app_id.desktop"
ln -s "usr/share/icons/hicolor/scalable/apps/$app_id.svg" "$app_dir/$app_id.svg"
cp "$repo_dir/desktopApp/icon.png" "$app_dir/.DirIcon"
cp "$repo_dir/desktopApp/appimage/AppRun" "$app_dir/AppRun"
chmod 755 "$app_dir/AppRun"

# Pin both downloads: appimagetool otherwise fetches a moving runtime release.
tool_version=1.9.1
runtime_version=20251108
tool="$work_dir/appimagetool-$arch.AppImage"
runtime="$work_dir/runtime-$arch"
curl --fail --location --retry 3 --output "$tool" \
  "https://github.com/AppImage/appimagetool/releases/download/$tool_version/appimagetool-$arch.AppImage"
curl --fail --location --retry 3 --output "$runtime" \
  "https://github.com/AppImage/type2-runtime/releases/download/$runtime_version/runtime-$arch"
(
  cd "$work_dir"
  sha256sum --check "$repo_dir/.github/actions/build_appimage/$arch.sha256"
)
chmod 755 "$tool"

filename="Keyguard-$version-linux-$arch.AppImage"
appimage="$work_dir/$filename"
zsync="$appimage.zsync"
repository=${GITHUB_REPOSITORY:-AChep/keyguard-app}
update_information="gh-releases-zsync|${repository%/*}|${repository#*/}|latest|Keyguard-*-linux-$arch.AppImage.zsync"
(
  # zsyncmake writes its output in the current working directory.
  cd "$work_dir"
  ARCH="$arch" VERSION="$version" "$tool" --appimage-extract-and-run \
    --runtime-file "$runtime" --updateinformation "$update_information" \
    --file-url "$filename" "$app_dir" "$filename"
)
test -s "$appimage"
test -x "$appimage"
test -s "$zsync"
embedded_update_information=$("$appimage" --appimage-updateinformation)
if [[ "$embedded_update_information" != "$update_information" ]]; then
  echo "AppImage update information does not match $update_information" >&2
  exit 1
fi

# Read only the text header; zsync's block checksums are binary.
python3 - "$appimage" "$zsync" <<'PY'
import hashlib
from pathlib import Path
import sys

appimage, zsync = map(Path, sys.argv[1:])
header = {}
with zsync.open("rb") as stream:
    for line in stream:
        if line == b"\n":
            break
        key, value = line.decode("ascii").rstrip("\n").split(": ", 1)
        header[key] = value
    else:
        sys.exit(f"{zsync}: missing zsync header terminator")

digest = hashlib.sha1()
with appimage.open("rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
expected = {
    "Filename": appimage.name,
    "URL": appimage.name,
    "Length": str(appimage.stat().st_size),
    "SHA-1": digest.hexdigest(),
}
for key, value in expected.items():
    if header.get(key) != value:
        sys.exit(f"{zsync}: {key} does not match the AppImage (expected {value!r})")
PY

# Inspect the final SquashFS payload, then exercise the AppImage launcher without FUSE.
(
  cd "$work_dir"
  "$appimage" --appimage-extract > /dev/null
)
APPIMAGE_EXTRACT_AND_RUN=1 python3 "$repo_dir/scripts/verify_native_bundle.py" \
  desktop "$work_dir/squashfs-root" --platform linux --arch "$arch" --launcher "$appimage"

# Publish the outputs only after all validation succeeds.
mv -f -- "$appimage" "$zsync" "$output_dir/"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "path=$output_dir/$filename" >> "$GITHUB_OUTPUT"
  echo "zsync-path=$output_dir/$filename.zsync" >> "$GITHUB_OUTPUT"
fi
echo "Built $output_dir/$filename and $output_dir/$filename.zsync"
