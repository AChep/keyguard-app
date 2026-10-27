#!/bin/sh
# Bundle the native app's CLI agent and the externally launched keyguard CLI.
set -eu
. "$SRCROOT/../xcode/scripts/rust-env.sh"
cd "$SRCROOT/.."
./gradlew :desktopCliAgent:compileCliAgentUniversal :desktopCli:compileCliUniversal
case "$(uname -m)" in
    arm64) CLI_PLATFORM="macos-arm64" ;;
    x86_64) CLI_PLATFORM="macos-x64" ;;
    *) echo "error: unsupported macOS host architecture"; exit 1 ;;
esac
CLI_DESTINATION="$BUILT_PRODUCTS_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
mkdir -p "$CLI_DESTINATION"
CLI_IDENTITY="${EXPANDED_CODE_SIGN_IDENTITY:-${CODE_SIGN_IDENTITY:-}}"
if [ "${CODE_SIGNING_ALLOWED:-YES}" = "NO" ] || [ -z "$CLI_IDENTITY" ]; then
    CLI_IDENTITY="-"
fi
case "$CONFIGURATION" in
    *Release) CLI_TIMESTAMP="--timestamp" ;;
    *) CLI_TIMESTAMP="--timestamp=none" ;;
esac
if [ "$CLI_IDENTITY" = "-" ]; then
    CLI_TIMESTAMP="--timestamp=none"
fi
# The agent accepts only the keyguard CLI that sits next to it.
for CLI_BINARY in keyguard-cli-agent keyguard; do
    case "$CLI_BINARY" in
        keyguard-cli-agent) CLI_MODULE="desktopCliAgent" ;;
        keyguard) CLI_MODULE="desktopCli" ;;
    esac
    CLI_SOURCE="$SRCROOT/../$CLI_MODULE/build/bundled-app-resources/$CLI_PLATFORM/$CLI_BINARY"
    if [ ! -f "$CLI_SOURCE" ]; then
        echo "error: $CLI_BINARY binary not found after build"
        exit 1
    fi
    CLI_TARGET="$CLI_DESTINATION/$CLI_BINARY"
    cp -f "$CLI_SOURCE" "$CLI_TARGET"
    chmod +x "$CLI_TARGET"
    if [ "$CLI_BINARY" = "keyguard-cli-agent" ]; then
        codesign --force --options runtime "$CLI_TIMESTAMP" --sign "$CLI_IDENTITY" \
            --entitlements "$SRCROOT/macosApp/KeyguardCliAgent.entitlements" "$CLI_TARGET"
    else
        # The CLI is launched outside the app and must not inherit an app sandbox.
        codesign --force --options runtime "$CLI_TIMESTAMP" --sign "$CLI_IDENTITY" "$CLI_TARGET"
    fi
done
# The keyguard CLI finds the agent's socket in the container of this App Group.
printf '%s\n' "$KEYGUARD_APP_GROUP_ID" > "$CLI_DESTINATION/keyguard-app-group"
