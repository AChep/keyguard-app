#!/bin/sh
# Bundles the Compose Multiplatform resources (localized strings, etc.) that the shared
# module reads at runtime.
#
# The CMP resource aggregation (`<target>AggregateResources`) runs as part of the
# framework build, but the Kotlin plugin does not put its output anywhere the .app can
# see. Without this copy a clean build ships no resources and the first localized-string
# read aborts with `org.jetbrains.compose.resources.MissingResourceException`.
#
# Each AutoFill extension is its own process with its own bundle, so the extension
# targets need this phase too — not just the apps.
#
# Shared by every target on both platforms (see xcode/keyguard-common.yml); the slice is
# resolved from $SDK_NAME so one script covers macOS, device and simulator.
set -eu

BASE="$SRCROOT/../common/build/kotlin-multiplatform-resources/aggregated-resources"
case "$SDK_NAME" in
  iphonesimulator*) SLICE="iosSimulatorArm64" ;;
  iphoneos*)        SLICE="iosArm64" ;;
  macosx*)          SLICE="macosArm64" ;;
  *)
    echo "warning: unrecognized SDK_NAME '$SDK_NAME'; skipping Compose resource copy"
    exit 0
    ;;
esac

SRC="$BASE/$SLICE/composeResources"
DEST="$BUILT_PRODUCTS_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/compose-resources"
if [ -d "$SRC" ]; then
  rm -rf "$DEST"
  mkdir -p "$DEST"
  cp -R "$SRC" "$DEST/"
  # The source catalog is a placeholder; package this platform's generated report.
  LICENSES="$SRCROOT/../appleApp/build/reports/licensee/$SLICE/artifacts.json"
  if [ ! -f "$LICENSES" ]; then
    echo "error: Apple license report not found at $LICENSES"
    exit 1
  fi
  cp "$LICENSES" "$DEST/composeResources/com.artemchep.keyguard.res/files/licenses.json"
else
  echo "warning: Compose resources not found at $SRC"
fi
