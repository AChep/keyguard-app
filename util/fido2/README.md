# FIDO2 security-key client

This Compose-free Kotlin Multiplatform module registers security-key credentials,
evaluates WebAuthn PRF for vault unlock, and produces assertions for account
authentication.

Callers validate assertion RP IDs and AppIDs against their trusted server origin,
manage vault storage, and clear returned secrets after use.

Linux/macOS negotiate CTAP2 or legacy U2F according to the key's capabilities.
The reviewed `authenticator` fork in `thirdParty/rust/` recovers credential IDs
omitted by CTAP2.0 keys after allow-list filtering. Vault unlock requires user
verification and PRF; legacy U2F is only usable for compatible account assertions.

Linux builds need `libudev-dev` (or the distribution's equivalent). Runtime users
need permission to access the key's hidraw device.

## Tests

Run from the repository root:

```sh
cargo test -p keyguard-fido2-c -p keyguard-fido2-core -p keyguard-fido2-jni --all-features --locked
cargo test --manifest-path thirdParty/rust/Cargo.toml -p authenticator --no-default-features --features crypto_rust --lib --locked
./gradlew :util:fido2:desktopTest :util:fido2:testAndroidHostTest
./gradlew :common:desktopTest --tests '*Fido2*'
```

On macOS, also run the native tests:

```sh
./gradlew :util:fido2:macosArm64Test
```
