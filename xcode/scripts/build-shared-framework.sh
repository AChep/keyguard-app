#!/bin/sh
# Assembles the KeyguardShared.xcframework from the :appleApp Kotlin module and
# publishes it to the stable SwiftPM path consumed by appleUi/Package.swift's
# binaryTarget. Xcode builds one SDK at a time, so the framework only carries the
# slice for $SDK_NAME; switching the destination publishes that SDK's slice instead.
#
# :appleApp is the ONLY producer of this framework — the app directories carry no
# Kotlin of their own. The assemble task is finalized by a sync task that ditto's the
# artifact to build/XCFrameworks/swiftpm/ (see appleApp/build.gradle.kts).
#
# Runs once in the build pre-action of the shared "Keyguard" scheme (both platforms,
# xcode/keyguard-common.yml) and of the macOS "Keyguard App Store" scheme
# (macosApp/project.yml), before Xcode copies the SwiftPM binary target and builds
# the app + AutoFill extension.
set -eu

# Xcode ignores a failing pre-action and would go on to build the Swift sources
# against the previously published framework. Remove it instead, so the build
# fails at the SwiftPM binary target rather than running stale Kotlin.
PUBLISHED_FRAMEWORK="$SRCROOT/../appleApp/build/XCFrameworks/swiftpm/KeyguardShared.xcframework"
remove_stale_framework() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "error: building KeyguardShared failed (exit $status); removed $PUBLISHED_FRAMEWORK" >&2
        rm -rf "$PUBLISHED_FRAMEWORK"
    fi
}
trap remove_stale_framework EXIT

# shellcheck source=xcode/scripts/rust-env.sh
. "$SRCROOT/../xcode/scripts/rust-env.sh"

# Xcode exports its selected target's SDK into script environments. Kotlin/Native
# and cargo pass an explicit `--target` + sysroot for each slice they cross-compile, but
# cargo's `cc` crate honours $SDKROOT even when building a HOST build script — so an
# iOS-simulator SDKROOT makes aws-lc-sys's build script compile against the simulator
# sysroot, where <sys/random.h> does not exist:
#
#   aws-lc/crypto/rand_extra/getentropy.c:22:10: fatal error: 'sys/random.h' file not found
#
# Pin it to the macOS SDK, which is what a terminal build resolves via xcrun. (Clearing
# it instead is NOT enough: the `cc` on the phase's PATH then emits no -isysroot at all
# and cannot find even <stdio.h>.) Keeping the value identical to the terminal one also
# keeps cargo's fingerprint stable, so a framework already built from a shell stays
# up-to-date here instead of being rebuilt.
SDKROOT="$(xcrun --sdk macosx --show-sdk-path)"
export SDKROOT
unset CPATH LIBRARY_PATH CFLAGS CPPFLAGS LDFLAGS

cd "$SRCROOT/.."
# A fallback only: explicit Gradle properties (including release metadata) have
# precedence over ORG_GRADLE_PROJECT_* environment values. Source archives without
# Git metadata keep an empty ref, so they do not create broken revision links.
if [ -z "${ORG_GRADLE_PROJECT_versionRef+x}" ]; then
    ORG_GRADLE_PROJECT_versionRef="$(git rev-parse --short HEAD 2>/dev/null || true)"
    export ORG_GRADLE_PROJECT_versionRef
fi
# Build the framework slice and the license report for the app being built,
# including native dependencies.
case "$SDK_NAME" in
  iphonesimulator*) SLICE="IosSimulatorArm64" ;;
  iphoneos*)        SLICE="IosArm64" ;;
  macosx*)          SLICE="MacosArm64" ;;
  *)
    echo "error: unsupported SDK_NAME '$SDK_NAME' for KeyguardShared"
    exit 1
    ;;
esac
case "$CONFIGURATION" in
  *Release) FRAMEWORK_BUILD=Release; KONFIG_FLAVOR=release ;;
  *) FRAMEWORK_BUILD=Debug; KONFIG_FLAVOR=dev ;;
esac
if [ "$FRAMEWORK_BUILD" = Release ]; then
  # Kotlin/Native release linking exceeds the default Gradle heap. Serializing the
  # work also keeps the compilers and the linker from competing for that memory.
  ./gradlew --no-daemon \
    "-Dorg.gradle.jvmargs=-Xmx16384m -Dfile.encoding=UTF-8" \
    --max-workers=1 \
    -Pbuildkonfig.flavor="$KONFIG_FLAVOR" \
    ":appleApp:licensee$SLICE" \
    ":appleApp:assembleKeyguardShared${SLICE}${FRAMEWORK_BUILD}XCFramework"
else
  ./gradlew \
    -Pbuildkonfig.flavor="$KONFIG_FLAVOR" \
    ":appleApp:licensee$SLICE" \
    ":appleApp:assembleKeyguardShared${SLICE}${FRAMEWORK_BUILD}XCFramework"
fi
