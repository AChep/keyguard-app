# Android QR scanner

This module owns live camera scanning, its CameraX and ML Kit dependencies, and the camera
permission and hardware declarations. `androidApp` includes it and binds `ScanQrRouteFactoryAndroid`.

The dependency direction is `androidApp -> feature:qr-scanner-android -> common`. The factory
contract and shared UI helpers remain in `common` during migration. Keep `common` independent of
this module so apps can omit the scanner. The scan action and camera permission setting follow
the availability of the factory in DI.

Image-file QR decoding and barcode generation remain shared. Wear's
`checkOptionalFeatureDependencies` task verifies that the live scanner and its libraries stay out
of every production compile/runtime classpath.
