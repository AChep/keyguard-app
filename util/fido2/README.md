# FIDO2 vault unlock client

This Compose-free module registers a security-key credential and evaluates the
WebAuthn PRF extension. Linux/macOS use Mozilla's `authenticator` CTAP2 client;
Windows uses its WebAuthn broker (API version 6 or newer), including the system
PIN prompt. Android uses YubiKit's FIDO client in `common`, with generic FIDO USB
device discovery. iOS and Flatpak are not supported.

The RP ID is `keyguard.dev`. Every operation requires user verification
and presence. CTAP1 fallback is disabled. PRF inputs use WebAuthn domain separation
on every backend, including CTAP 2.0 `hmac-secret` keys. Windows requires a
discoverable credential to enable PRF; other backends discourage resident keys.

The bounded JNI/C ABI accepts one registration or derivation request per call.
Cancellation handles are opaque IDs, not pointers. PIN retries require a new
user submission; native errors expose status codes without device responses.
Kotlin callers own returned secrets and erase them after use. The module knows
nothing about vault storage: `Fido2UnlockService` derives an HKDF-SHA256 wrapping
key and uses the existing authenticated AES cipher to protect the local master
key. Only public credential metadata, salts, and ciphertext are persisted.

Linux builds need `libudev-dev` (or the distribution's equivalent); runtime users
need permission to access the key's hidraw device. CI installs the build package.

Run automated checks:

```sh
cargo fmt --manifest-path util/fido2/rust/Cargo.toml --all -- --check
cargo clippy --manifest-path util/fido2/rust/Cargo.toml --workspace --all-targets --locked -- -D warnings
cargo test --manifest-path util/fido2/rust/Cargo.toml --workspace --locked
./gradlew :util:fido2:checkComposeFree :util:fido2:desktopTest :util:fido2:macosArm64Test :util:fido2:testAndroidHostTest
./gradlew :common:desktopTest --tests '*Fido2*'
```
