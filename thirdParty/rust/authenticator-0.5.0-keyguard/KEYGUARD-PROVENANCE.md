# Keyguard fork provenance

- Upstream package: `authenticator` 0.5.0 from crates.io
- Crates.io package SHA-256: `bbd6f57365675990f2db272a6560b28945df74cf3749c70aafd9b1c7829edebc`
- Upstream repository: `https://github.com/mozilla/authenticator-rs`
- Upstream commit: `30ce304960261bdd0a7d88d86c54e5e94214dc50`
- Imported upstream `Cargo.toml.orig` SHA-256: `b143b28d45851d732c7f802396384fb6cc2679e7e52d3d2681280f97afc3d4ae`
- Upstream `LICENSE` SHA-256: `e866c8f5864d4cacfe403820e722e9dc03fe3c7565efa5e4dad9051d827bb92a`

The source was imported from the checksum-verified Cargo registry package.
The upstream MPL-2.0 license, `.cargo_vcs_info.json`, and byte-exact
`Cargo.toml.orig` are retained.

## Local changes

`GetAssertion::finalize_result` restores an omitted credential descriptor only
when the actual request allow-list has exactly one entry. This handles CTAP2.0
responses after the library filters a caller's longer list during preflight.
Explicit descriptors remain unchanged; missing IDs on empty or ambiguous lists
remain missing so the caller can reject them. Authentication data, signatures,
PIN/UV processing, and PRF outputs are not modified.

The regression tests exercise preflight selection followed by CBOR response
decoding, explicit IDs, and ambiguous/discoverable requests. The effective
manifest adds the reviewed-vendor marker and declares upstream's `cfg(fuzzing)`.
`examples/ctap2_discoverable_creds.rs` has formatting-only changes for rustfmt.

## Consumers and checks

`keyguard-fido2-core` in the root workspace consumes this fork on Linux/macOS
with `default-features = false` and `crypto_rust`. The root manifest pins its
version and applies the path patch. The neutral `thirdParty/rust` workspace
runs the fork's unit tests; do not enable all features because upstream's
crypto backends are mutually exclusive.

Run from the repository root:

```sh
cargo fmt --manifest-path thirdParty/rust/Cargo.toml -p authenticator -- --check
cargo clippy --manifest-path thirdParty/rust/Cargo.toml -p authenticator --no-default-features --features crypto_rust --lib --tests --locked -- -D clippy::correctness -D clippy::suspicious -A clippy::manual_unwrap_or_default
cargo test --manifest-path thirdParty/rust/Cargo.toml -p authenticator --no-default-features --features crypto_rust --lib --locked
cargo fmt --all -- --check
cargo clippy -p keyguard-fido2-c -p keyguard-fido2-core -p keyguard-fido2-jni --all-targets --all-features --locked --no-deps -- -D warnings
cargo test -p keyguard-fido2-c -p keyguard-fido2-core -p keyguard-fido2-jni --all-features --locked
./gradlew :util:fido2:desktopTest :util:fido2:macosArm64Test
./gradlew :common:desktopTest --tests '*Fido2*'
```

Clippy retains upstream style warnings while denying correctness and suspicious
lints, except the two existing `manual_unwrap_or_default` style findings. CI
runs the fork tests on Linux/macOS and builds the native consumers on
Linux/macOS/Windows. Windows uses its separate OS WebAuthn backend. Fork-only
edits select the FIDO2 consumer checks through `classify_native_changes.py`.

Remove this patch when an upstream release includes equivalent recovery and
passes these regressions. The native-crypto workspace does not consume this
fork; its dependency policy is unchanged.
