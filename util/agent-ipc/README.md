# Agent IPC

`util/agent-ipc` is the app-side server of the private channel between Keyguard and
its own agent helpers (SSH, GPG and CLI). The helpers' client is the
`keyguard-agent-ipc` crate in `commonAgent/ipc`. Despite the similar name, this
module uses that crate only in its tests, as a real helper client.

A Rust core serves the desktop JVM through JNI and native macOS through cinterop.
It uses Unix-domain sockets on Linux and macOS and overlapped named pipes on
Windows. iOS never links it.

## Contract

The module is a byte transport. Rust owns the OS side:

- creating and securing the endpoint;
- accept, with a kernel snapshot of the peer;
- full-duplex byte I/O;
- a wait for input or disconnect that consumes nothing;
- close, with a prompt wake-up of every blocked call.

Kotlin owns framing, sessions, authentication, limits, timeouts, peer policy and
logging. Rust starts no threads, makes no callbacks, writes no logs and installs
no process-wide signal handlers. Calls block the calling thread.

## Validation

Run from the repository root:

```sh
cargo fmt -p keyguard-agent-ipc-core -p keyguard-agent-ipc-jni -p keyguard-agent-ipc-c -- --check
cargo clippy -p keyguard-agent-ipc-core -p keyguard-agent-ipc-jni -p keyguard-agent-ipc-c --all-targets --all-features --locked --no-deps -- -D warnings
cargo test -p keyguard-agent-ipc-core -p keyguard-agent-ipc-jni -p keyguard-agent-ipc-c --all-features --locked
./gradlew :util:agent-ipc:desktopTest :util:agent-ipc:detekt
./gradlew :util:agent-ipc:macosArm64Test :appleApp:macosArm64Test
```

`desktopTest` runs under `-Xcheck:jni`, and `AgentIpcLoadTest` runs every JNI export
in a separate JVM that must print no JNI warning. Kotlin/Native tests need macOS
arm64 and the installed Apple Rust target.
