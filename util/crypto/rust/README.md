# Keyguard Native Crypto

This Rust workspace is the dependency leaf behind Keyguard's Kotlin
Multiplatform `:util:crypto` module.

- `keyguard-crypto-core` is safe Rust and owns the protobuf protocol,
  primitives, policy limits, and generation-tagged session registry.
- `keyguard-crypto-sensitive` owns heap-stable AWS-LC digest/HMAC contexts and
  the narrowly audited, volatile-wipe adapter for pinned RustCrypto BLAKE2
  state. It is the only non-FFI crate that contains reviewed unsafe code.
- `keyguard-crypto-jni` exports the nine methods on
  `com.artemchep.keyguard.nativecrypto.NativeCryptoJni`.
- `keyguard-crypto-c` exports the Apple C ABI declared in
  `crates/keyguard-crypto-c/include/keyguard_crypto.h`.
The wire source of truth is `../schema/native_crypto.proto`. ABI version 1 and
protocol version 1 are independent. Calls fail closed on an ABI/capability
mismatch, malformed request, resource limit, stale session, or contained panic.
Rust-owned request/response byte buffers and Argon2 memory blocks are
zeroized, and the FFI panic hook never prints panic payloads. Digest/HMAC
contexts are allocated on the AWS-LC heap before secret input and cleansed on
drop. Argon2's BLAKE2 state is likewise boxed before input and volatile-wiped
on every exit. The dependency/layout assumptions for that unsafe operation are
exact-pinned and mechanically checked; see the
[sensitive-context security record](../../../docs/security/native-crypto-sensitive-contexts.md)
and architecture acceptance ledger.

Streaming digest, HMAC, AES-CBC-PKCS#7, and fused AES-CBC/HMAC sessions accept
raw chunks of at most 64 KiB. Registry lookup is short-lived and each handle
has its own lock, so unrelated sessions can progress concurrently. AES
decryption retains only the final block/tail for padding validation;
application one-shot adapters stage update output until `finish` succeeds.

Hostile-wire work factors are bounded before backend work: PBKDF2 preserves
the public Kotlin contract of every positive `Int` iteration count
(1..2,147,483,647) and rejects larger protobuf-only values. The application's
2,000,000-iteration login policy remains a caller-level policy, not a primitive
ABI restriction. Argon2 follows the repository's 10,000-iteration, 1 GiB, and
parallelism-64 limits, random-integer batches contain at most 1,024 values, and
the repeated KDBX AES transform accepts at most 100,000,000 rounds and
200,000,000 total block transforms.

## Local checks

Install `protoc`, then verify that the checked-in Rust protocol still matches
the wire schema before running the workspace checks:

```sh
cargo run --manifest-path ../tools/native-crypto-protocol/Cargo.toml --locked -- check
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --all-targets --locked
cargo check --manifest-path fuzz/Cargo.toml --all-targets
cargo deny --config ../../../.github/native-crypto-deny.toml check
cargo run --manifest-path ../tools/native-crypto-policy/Cargo.toml --locked -- \
  --repository ../../..
```

After changing `../schema/native_crypto.proto`, regenerate the Rust protocol
with:

```sh
cargo run --manifest-path ../tools/native-crypto-protocol/Cargo.toml --locked -- generate
```

Run the bounded fuzz targets with `cargo fuzz run dispatch`,
`cargo fuzz run session_lifecycle`, `cargo fuzz run ssh_private_key_import`,
and `cargo fuzz run openpgp_read`. Release performance baselines are
`cargo bench -p keyguard-crypto-core --bench argon2` and
`cargo bench -p keyguard-crypto-core --bench bulk_primitives`. The diagnostic
`small_primitives` bench separates fused protobuf encode/dispatch cost from the
caller-owned-output AES-CBC/HMAC data plane; pair it with
`:util:crypto:nativeCryptoLayerBenchmark` for Kotlin serialization and JNI/C
boundary attribution.
