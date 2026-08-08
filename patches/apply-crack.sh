#!/usr/bin/env bash
set -euo pipefail

PATCH_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$PATCH_DIR/.." && pwd)"
cd "$REPO_ROOT"

echo "[patch] Applying Elnix90 crack mods..."

# Ensure androidNdk version catalog entry exists (needed by cracked build.gradle.kts)
if ! grep -q '^androidNdk' gradle/libs.versions.toml; then
  sed -i '/^\[versions\]/a androidNdk = "27.0.12077973"' gradle/libs.versions.toml
fi

# Ensure google-services.json exists (required by google-services plugin)
if [ ! -f "androidApp/google-services.json" ]; then
  cat > androidApp/google-services.json << 'GSJSON'
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "keyguard-placeholder",
    "storage_bucket": "keyguard-placeholder.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": {
          "package_name": "com.artemchep.keyguard"
        }
      },
      "oauth_client": [],
      "api_key": [
        { "current_key": "AIzaSyPlaceholderKeyForBuild00" }
      ],
      "services": {}
    }
  ],
  "configuration_version": "1"
}
GSJSON
fi
if [ ! -d "util/crypto" ]; then
  mkdir -p util/crypto
  echo "" > util/crypto/build.gradle
fi

# Overwrite upstream files with cracked versions
cp "$PATCH_DIR/androidApp/.gitignore" androidApp/.gitignore
cp "$PATCH_DIR/androidApp/build.gradle.kts" androidApp/build.gradle.kts
cp "$PATCH_DIR/androidApp/keyguard-release.keystore" androidApp/keyguard-release.keystore

# Strip :util:crypto ref from cracked build.gradle.kts if settings.gradle doesn't include it
if ! grep -q "':util:crypto'" settings.gradle; then
  sed -i '/:util:crypto/d' androidApp/build.gradle.kts
fi

cp "$PATCH_DIR/common/src/commonMain/kotlin/com/artemchep/keyguard/common/usecase/GetPurchased.kt" \
   common/src/commonMain/kotlin/com/artemchep/keyguard/common/usecase/GetPurchased.kt

echo "[patch] Done."
