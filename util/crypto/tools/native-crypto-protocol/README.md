# Native crypto protocol

`native-crypto-protocol` generates the Rust Prost types used by Keyguard's
native-crypto wire protocol. The source of truth is
`util/crypto/schema/native_crypto.proto`; the generated Rust file is checked in
at `util/crypto/rust/crates/keyguard-crypto-core/src/protocol/generated.rs` so
normal builds do not require `protoc`.

After changing the schema, install `protoc` and regenerate from the repository
root:

```sh
cargo run --manifest-path util/crypto/tools/native-crypto-protocol/Cargo.toml --locked -- generate
```

To fail when the checked-in output has drifted from the schema, run:

```sh
cargo run --manifest-path util/crypto/tools/native-crypto-protocol/Cargo.toml --locked -- check
```
