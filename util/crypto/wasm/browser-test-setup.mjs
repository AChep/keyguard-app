import { initialize } from 'keyguard-crypto-wasm';

// Register a Mocha root hook in the same webpack bundle as the Kotlin tests,
// so they all use the initialized instance of the npm backend.
before(async function initializeCrypto() {
    this.timeout(180_000);
    await initialize();
});
