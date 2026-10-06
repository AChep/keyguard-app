# KeyguardUI

`KeyguardUI` is the shared SwiftUI package for the iOS and macOS apps. It contains
screens, reusable components, and the bridge to shared app logic.
The Kotlin bridge, `KeyguardShared.xcframework`, is built by [appleApp](../appleApp/).

## Build

Run all commands below from the repository root.

Before opening an Xcode project for the first time, build the shared framework so
Swift Package Manager can find it:

```sh
./gradlew :appleApp:assembleKeyguardSharedDebugXCFramework
```

Open `iosApp/iosApp.xcodeproj` or `macosApp/Keyguard.xcodeproj` in Xcode. Select the
`Keyguard` scheme and your device or simulator, then build and run. The scheme's
build steps rebuild the shared framework and generate the strings.

The projects are generated with XcodeGen. After changing either app's `project.yml`
or the shared specifications in `xcode/`, regenerate both:

```sh
(cd macosApp && xcodegen generate)
(cd iosApp && xcodegen generate)
```

## Local signing

To sign with your own Apple account, copy the
[signing example](../xcode/Signing.local.xcconfig.example) to
`xcode/Signing.local.xcconfig`. Replace all three values: the development team,
bundle ID, and App Group ID. Use identifiers registered to your account.
The local file is ignored by Git; the example has setup details.
AutoFill needs a working App Group to share the app's vault.

## Formatting

Check Swift formatting before submitting changes, or use `--fix` to apply it:

```sh
xcode/scripts/lint-swift.sh
xcode/scripts/lint-swift.sh --fix
```
