# YubiKey device operations

This Compose-free Kotlin Multiplatform library owns OTP discovery,
OTP capture on Android, slot inspection, HMAC-SHA1 challenge-response, and slot
provisioning. Vault encryption, persisted protectors, enrollment confirmation,
localization, and screen lifecycle remain in the application.

- Android uses YubiKit through `AndroidYubiKeyOtpReader` and
  `YubiKeyActivityProtocol`. The caller supplies its Activity Result adapter.
- JVM desktop and native macOS use `NativeYubiKeyClient`, backed by the same Rust
  OTP/HID implementation. JNI and C are thin, versioned adapters.
- iOS and the shipped Flatpak sandbox report unsupported. Desktop OTP text entry
  remains an application concern. NFC challenge-response is Android-only.

The desktop backend requires an enabled OTP interface and access to its HID
feature reports. Linux requires suitable host udev permissions. The macOS app
sandbox requires the USB device entitlement. No device access occurs at startup.

## Contract

Slots are 1 or 2. Challenges contain 1–63 bytes; the configured HMAC_LT64 mode
reserves the final payload byte for padding. Provisioning secrets contain
20 bytes. Provisioning checks the current slot before writing; a configured slot
requires explicit `overwrite = true`. Inspection only reports whether the slot is
configured, not its algorithm. Access-code-protected slots cannot be overwritten.
Provisioning and its verification challenge use the same open device connection.

The native backend refuses ambiguous device selection when multiple OTP-capable
YubiKeys are connected, and serializes operations within the process. Cancellation
signals a token checked between HID calls; polling has a 30-second deadline.
An OS HID call itself is synchronous. Handles close after success, error, or
cancellation. Owned secret buffers are cleared; callers own and must clear their
input secrets and returned HMAC responses after use.

The HID protocol follows the [Yubico OTP protocol](https://developers.yubico.com/yubikey-personalization/Manuals_&_Tutorials/).

## Validation

Run from the repository root:

```sh
cargo fmt -p keyguard-yubikey-c -p keyguard-yubikey-core -p keyguard-yubikey-jni -- --check
cargo clippy -p keyguard-yubikey-c -p keyguard-yubikey-core -p keyguard-yubikey-jni --all-targets --all-features --locked --no-deps -- -D warnings
cargo test -p keyguard-yubikey-c -p keyguard-yubikey-core -p keyguard-yubikey-jni --all-features --locked
./gradlew :util:yubikey:checkComposeFree :util:yubikey:desktopTest :util:yubikey:macosArm64Test :util:yubikey:testAndroidHostTest
```

Automated tests use simulated HID reports and exercise the real JNI/C boundaries
without opening a key. Before release, verify both slots on physical hardware:
Android USB/NFC, desktop USB on each supported OS, and the signed macOS sandbox.
Check enrollment, overwrite refusal/confirmation, protected slots, touch timeout,
cancel, disconnect, and restart followed by vault unlock. Use a spare slot:
provisioning replaces its existing configuration and cannot be rolled back.
