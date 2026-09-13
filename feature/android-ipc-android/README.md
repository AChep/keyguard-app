# Android IPC providers

This module owns the Android OpenPGP and SSH authentication IPC providers, approval UI,
registration storage, provider-specific vault loading and key selection, and OpenKeychain APIs.
It also owns the provider manifest entries and cross-process consumer keep rules.

The dependency direction is
`androidApp -> feature:android-ipc-android -> common + feature:android-ipc-presentation`.
`common` provides an empty registration service by default. The phone app imports
`androidIpcModule()` with `allowOverride = true`, then calls `installAndroidIpcProviders()`
to follow the user's provider switches. Only the registration service binding explicitly
overrides the default; vault session ownership and authorization checks remain in the providers.

The approval state producer lives in the Compose-free presentation module. This module adapts
coordinator snapshots, localized resource labels, and Compose lifecycle to that producer.
Shared crypto implementations and the connected-apps settings contract remain in `common`.
Moving the connected-apps screen requires a later optional settings contribution boundary.

Wear includes neither IPC module. `:wearApp:checkOptionalFeatureDependencies` verifies every
production compile/runtime classpath, and `:wearApp:checkOptionalFeatureManifests` verifies
that components and activity-alias targets in `com.artemchep.keyguard.android.ipc` and its
subpackages are absent from every production merged manifest. It fails if no variants are checked.

Run provider and presentation tests with:

```shell
./gradlew :feature:android-ipc-android:testDebugUnitTest \
  :feature:android-ipc-presentation:desktopTest \
  :feature:android-ipc-presentation:checkComposeFree
```

The Android tests include the provider contract, authorization, persistence, key selection,
usage attribution, and DI wiring tests. The native crypto consumer plugin supplies the host
JNI library for SSH contract tests.
