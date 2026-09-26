# Instance coordination

`util/instance` provides process ownership and acknowledged activation for desktop
JVM and Kotlin/Native macOS applications. The Rust core is shared by JNI and C
bindings. It uses Unix-domain sockets on macOS/Linux and named pipes on Windows.

## Application contract

Call `InstanceCoordinator.acquireOrActivate()` before starting services that use
shared application data. Only `InstanceResult.Primary` authorizes starting those
services. `Activated` means another process queued activation, not that its window
received OS focus. Failures never authorize starting a second instance.

The application supplies an absolute private local coordination directory, a short
runtime directory, and a stable ASCII identity. Use the same coordination directory
and identity for all versions, architectures, and JVM/native builds that share
application data. Separate release/development channels and independent data
domains. Do not include a version, random value, or executable path in the identity.

The coordination directory contains a permanent lock file and separate endpoint
metadata. Never delete or replace the lock file while any participating process
can run. Runtime socket paths may be separate from the data directory to respect
Unix socket path limits. The runtime root may be shared temporary storage; native
code creates a private per-run directory. Windows uses a private named pipe.

`PrimaryInstance.awaitActivation()` blocks and must run off the UI thread. It
restarts a failed listener up to three times with interruptible backoff, retaining
ownership throughout. Each replacement uses fresh credentials and atomically
published metadata. Successful activation resets the recovery budget. Exhausted
recovery reports an error; the app must provide a visible quit/restart path. One
receiver consumes coalesced activation requests, including requests received before
the receiver starts. Coroutine cancellation alone does not interrupt this native
call. `stop()` wakes it and stops accepting requests while retaining ownership.
`close()` stops transport work and releases ownership last; both operations are
idempotent. Keep ownership until all shared-state services have stopped.

The protocol supports activation only. Requests are bounded, authenticated with a
per-run token, and acknowledged after queueing. Idle listeners block on OS events;
there is no directory watcher or periodic timer. Slow active clients have deadlines.
User-only access does not isolate the app from malicious code running as that user.

## Keyguard integration

Normal desktop startup resolves its existing data directory and arbitrates before
initializing crypto, DI, persistence, or background workers. Activation is retained
until the UI is ready and routed to the existing show/unminimize behavior. Finder
and Dock reopening continue through the AWT reopen listener.

The app retains ownership through JVM termination because its background services
outlive windows. Its shutdown hook stops IPC without releasing ownership early.
Startup errors show category-specific guidance and selectable diagnostics before DI
is available. If listener recovery fails, the app shows its main window and offers
Quit so ownership can be released through normal process termination.
Coordination files live in a sibling directory named `<data-directory-name>.instance`
beside the app's data directory. Those files survive erasing data, settings, and caches;
the OS releases ownership at process termination. The packaged native smoke entry
point also exercises isolated instance IPC and never opens the user's instance lock.

## Validation

```sh
cargo fmt --manifest-path util/instance/rust/Cargo.toml --all -- --check
cargo test --manifest-path util/instance/rust/Cargo.toml --workspace --locked
cargo clippy --manifest-path util/instance/rust/Cargo.toml --workspace --all-targets --locked --no-deps -- -D warnings
./gradlew :util:instance:desktopTest
./gradlew :util:instance:macosArm64Test
./gradlew :desktopApp:jvmTest
python3 -m unittest discover -s scripts -p 'test_*native*py'
```

Native macOS tests need macOS and the installed Apple Rust target. CI uses
representative PR coverage and checks produced release packages; the complete
platform matrix is available manually. Rust and JVM tests support Windows,
Linux, and macOS. Kotlin/Native tests run on macOS arm64.
Release package smoke requires
`instance=PASS` alongside the other native library, helper, and TLS checks. Manual UI checks should cover a
backgrounded, minimized, hidden-to-tray, locked, or still-loading main window; OS
focus permission is distinct from successful IPC delivery.

The standard Rust build convention publishes the JNI library as bundled desktop
resources and links the C archive through Kotlin/Native cinterop. This module does
not produce a Swift framework or XCFramework.

The desktop test fixture shares the JNI build's Cargo target directory. Gradle orders
the two Cargo tasks when both are requested so compatible dependency artifacts can be reused.
