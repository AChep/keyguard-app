# Native protocol

This tool generates Rust Prost types from a Protobuf schema. It accepts schema,
generated package file, and output paths without knowing any bridge consumers.
Paths are relative to the caller's working directory. Install protoc first.

    cargo run --manifest-path util/ffi/tools/native-protocol/Cargo.toml --locked -- generate path/schema.proto example.v1.rs path/generated.rs

Use check instead of generate to fail on missing or stale output without changing it.
The generated Rust file is checked in so normal builds do not require protoc.

Repository-specific paths live in scripts/native_protocol.py. From the repository root:

    python3 scripts/native_protocol.py generate windows-passkeys
    python3 scripts/native_protocol.py check windows-passkeys

Omitting the protocol selects every registered protocol. The wrapper also works
from other directories when invoked by its path.
