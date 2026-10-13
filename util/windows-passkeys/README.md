# Windows passkey provider

This desktop-only bridge hosts Windows' `IPluginAuthenticator` COM interface.
It loads the third-party provider exports from System32's `webauthn.dll` at
runtime. Older Windows versions and unpackaged processes report unavailable
capabilities without affecting the rest of Keyguard.

The primary Keyguard process owns the COM server and the vault. A dedicated MTA
thread lives as long as the process: the first native call sets the process's
COM security there, once, so Keyguard makes that call before anything else uses
COM. Only the `start` command publishes the class factory, before vault
initialization. A failed start can be retried, and a stopped server can start
again; only a missing provider API or package is final. RPC threads
verify Windows' signed request, decode its CTAP payload, and wait for Kotlin.
Kotlin waits natively for a new request, the end of the live one, or a provider
status change; nothing polls, and cancelling the wait ends it at once. There is
one live request with a 120-second deadline. Cancellation, vault lock,
and shutdown invalidate it; delayed Windows Hello results cannot revive it.

Kotlin owns credential selection, authenticated unlock, vault access, and the
shared WebAuthn authenticator. Private keys never cross this bridge. JNI carries
bounded requests, public metadata, authenticator data, and assertion signatures.
Only Windows-signed requests can initiate a ceremony. The RP ID and client-data
hash are preserved exactly as supplied by Windows, which validates the caller.

Every response tells the site that the user was verified, so the bridge sends
one only for a verified request: Rust checks the Windows Hello signature, or
Kotlin reports that the user unlocked the vault during the request. Otherwise
the request ends. Kotlin also asks for the password of items that require it.

## Wire protocol

[`schema/windows_passkeys.proto`](schema/windows_passkeys.proto) defines the
JNI messages. Each call sends one `Command` and receives a zero status byte and
one `Reply`, framed by `util/ffi`; a single non-zero byte means the bridge itself
failed or the call was cancelled.

Like the FIDO2 and YubiKey bridges, each call runs as a `util/ffi` operation:
Kotlin's `runNativeOperation` creates a handle, and cancelling the coroutine
cancels the native call. Unlike theirs, these operations claim no device, so the
event wait runs alongside the other calls. Windows Hello has no dismiss API: a
cancelled verification returns once its prompt closes.

The Rust types are generated and checked in; Kotlin mirrors the schema in
`WindowsPasskeyProtocol.kt`. Both test suites check the encodings in
[`schema/fixtures/`](schema/fixtures), so a change on one side fails the other's
tests.

Add fields and variants under new numbers, and reserve removed ones. After
changing the schema, regenerate the Rust types and update the fixtures:

```sh
python3 scripts/native_protocol.py generate windows-passkeys
```

A fixture mismatch reports the expected lines.

Windows retains public credential metadata after explicit registration consent,
including across vault locks. The app reconciles that index when unlocked and
clears it before unregistering or erasing data. It does not cache private keys.

See [MSIX packaging and manual checks](../../desktopApp/msix/README.md).

API references:

- [Pinned Microsoft ABI headers and verification](abi/README.md)
- [Microsoft PasskeyManager sample](https://github.com/microsoft/Windows-classic-samples/tree/main/Samples/PasskeyManager)

From the repository root:

```sh
python3 scripts/native_protocol.py check windows-passkeys
cargo test -p keyguard-windows-passkeys-core -p keyguard-windows-passkeys-jni --locked
cargo fmt -p keyguard-windows-passkeys-core -p keyguard-windows-passkeys-jni -- --check
cargo clippy -p keyguard-windows-passkeys-core -p keyguard-windows-passkeys-jni --all-targets --locked -- -D warnings
./gradlew :util:windows-passkeys:desktopTest :util:webauthn:desktopTest
./gradlew :common:desktopTest --tests '*WindowsPasskey*'
```

The tests do not enroll a provider or use the user's vault. Provider logic and
metadata tests use typed fake plugin functions and run without plugin exports.
Cryptographic proof tests use ephemeral software keys. Kotlin drives the request
flow and index through a fake `WindowsPasskeyBridge`.

Real Windows encoder, decoder and provider-state tests are explicitly ignored in
ordinary runs. On a Windows client with the provider API, run them from the root:

```powershell
./util/windows-passkeys/abi/test-integration.ps1 -Target x86_64-pc-windows-msvc
```

Build that target first with the `cargo test` command above, adding `--target`
with the same target triple. Use `aarch64-pc-windows-msvc` for native ARM64.
The runner fails if the suite is empty or a required export is missing; it logs
the OS and DLL version. Missing optional status callbacks remain supported.
CI requires these checks on Windows 11 ARM64, both natively and as an x64 process
under emulation. Windows Server runs the logic and ABI checks without depending
on plugin availability. Registration and Hello still need the installed-package
checks described in the MSIX documentation.
