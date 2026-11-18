# Web crypto

`:util:crypto` supports Kotlin/JS and Kotlin/WasmGC using the Rust crypto backend
compiled with Emscripten. Enable either or both targets:

| Target | Gradle property |
| --- | --- |
| Kotlin/JS | `-Pkeyguard.crypto.wasm=true` |
| Kotlin/WasmGC | `-Pkeyguard.crypto.wasmGc=true` |

## Build and test

Requires the repository's Rust/JDK toolchains, Python 3.10+, Node, and Yarn Classic.
Gradle installs and activates Emscripten 4.0.23 in its user-home cache and writes
the runtime package to `util/crypto/build/wasm/npm/`.

```sh
(cd util/crypto && rustup target add wasm32-unknown-emscripten)

./gradlew :util:crypto:jsNodeTest :util:crypto:jsBrowserTest \
  -Pkeyguard.crypto.wasm=true

./gradlew :util:crypto:wasmJsNodeTest :util:crypto:wasmJsBrowserTest \
  -Pkeyguard.crypto.wasmGc=true

# Build and lint the Rust/Wasm backend independently.
./gradlew :util:crypto:buildNativeCryptoWasm :util:crypto:clippyNativeCryptoWasm

# The browser extension's Noise-only package, in build/wasm/npm-noise/.
./gradlew :util:crypto:buildNativeCryptoWasmNoise :util:crypto:clippyNativeCryptoWasmNoise
```

Browser tests use installed headless Chrome. Select an existing activated SDK with
`-Pkeyguard.wasm.emsdkDir=/path/to/emsdk`, or a Python interpreter with
`-Pkeyguard.wasm.python=/path/to/python3`. Gradle's `--offline` requires a cached SDK;
`-Pkeyguard.nativeCargo.cargoOffline=true` uses cached Cargo dependencies.

## Runtime

Call the suspending `initializeNativeCryptoWasm()` once per JS realm before using
`NativeCrypto`. Crypto calls are synchronous after initialization; run expensive
operations in a worker.

For browser worker checks:

```sh
./gradlew :util:crypto:stageWasmGcBrowserProbe -Pkeyguard.crypto.wasmGc=true
python3 util/crypto/wasm/serve.py
```

Open [the JS adapter check](http://127.0.0.1:8766/wasm/smoke.html) or
[the Kotlin/WasmGC check](http://127.0.0.1:8766/wasm/wasmgc-smoke.html).
The local server supplies CSP headers allowing Wasm execution.

## Limitations

- OpenPGP detached-verification streaming, message encryption/decryption streaming,
  and output draining are unavailable. Check `NativeCrypto.capabilities`;
  unsupported calls return `UNSUPPORTED_OPERATION`.
- Web OpenPGP encryption accepts up to 8 MiB of plaintext. Decryption returns up to
  15 MiB; request/response envelopes are limited to 16 MiB, including keys and
  metadata. Larger inputs or outputs return `RESOURCE_LIMIT`.
- Wasm memory can grow to 2 GiB and stays allocated until worker teardown.
  Zeroization cannot cover copies made by JavaScript or garbage collection.
- Consumers must handle extension resource URLs, CSP, and worker lifecycle.
