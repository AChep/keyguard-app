// Kotlin's shared tests are synchronous. Load Wasm before Mocha imports them.
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const require = createRequire(resolve(process.cwd(), 'package.json'));
const crypto = await import(pathToFileURL(require.resolve('keyguard-crypto-wasm')).href);
await crypto.initialize();
