#!/usr/bin/env bash
set -euo pipefail

tarball=$(realpath "${1:?Expected a Linux release tarball}")
arch=${2:?Expected x86_64 or aarch64}
version=${3:?Expected a semantic version}
case "$arch" in
  x86_64|aarch64) ;;
  *) echo "Unsupported Flatpak architecture: $arch" >&2; exit 1 ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
test -s "$tarball"

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
output_dir="$repo_dir/desktopApp/build/flatpak"
mkdir -p "$output_dir"
work_dir=$(mktemp -d "$output_dir/work-$arch.XXXXXX")
app_id=com.artemchep.keyguard
installed=false
cleanup() {
  if $installed; then
    flatpak uninstall --user --assumeyes --noninteractive "$app_id" || true
  fi
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

# These CI runners must not replace an existing installation.
if flatpak info --user "$app_id" >/dev/null 2>&1; then
  echo "Refusing to replace an existing user installation of $app_id" >&2
  exit 1
fi

manifest="$work_dir/$app_id.yml"
cp "$repo_dir/desktopApp/flatpak/$app_id.yml" "$manifest"
mkdir -p "$work_dir/app-$arch"
tar -xzf "$tarball" -C "$work_dir/app-$arch" --strip-components=1
runtime_version=$(python3 "$repo_dir/.github/get_flatpak_runtime_version.py" "$manifest")
flatpak remote-add --if-not-exists --user flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user --assumeyes --noninteractive flathub \
  "org.freedesktop.Platform/$arch/$runtime_version" "org.freedesktop.Sdk/$arch/$runtime_version"
flatpak-builder --user --force-clean --arch="$arch" \
  --state-dir="$work_dir/state" --repo="$work_dir/repo" "$work_dir/target" "$manifest"

filename="Keyguard-$version-$arch.flatpak"
bundle="$work_dir/$filename"
flatpak build-bundle --arch="$arch" "$work_dir/repo" "$bundle" "$app_id"
flatpak install --user --assumeyes --noninteractive "$bundle"
installed=true
installation=$(flatpak info --user --show-location "$app_id")
dbus-run-session -- python3 "$repo_dir/scripts/verify_native_bundle.py" desktop \
  "$installation/files" --platform linux --arch "$arch" --flatpak-app-id "$app_id"

# Only verified final bundles become workflow outputs.
mv -- "$bundle" "$output_dir/$filename"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "path=$output_dir/$filename" >> "$GITHUB_OUTPUT"
fi
echo "Built $output_dir/$filename"
