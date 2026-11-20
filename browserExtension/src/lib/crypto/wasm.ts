// Loads util/crypto's Wasm once per background wake. The module is imported
// statically: Chromium's service worker forbids dynamic `import()`.
import * as wasm from "keyguard-crypto-wasm";
import type { InitializeOptions } from "keyguard-crypto-wasm";
import { NativeCryptoClient } from "./nativeCrypto";
import { NativeCryptoNoise } from "./noise";

let loading: Promise<NativeCryptoNoise> | undefined;

/**
 * Initializes the Wasm and returns its Noise client. In the extension, pass
 * [extensionWasmOptions]; in Node, the loader reads the file next to the module.
 */
export function loadNoise(options?: InitializeOptions): Promise<NativeCryptoNoise> {
  loading ??= wasm
    .initialize(options)
    .then(() => {
      const client = new NativeCryptoClient(wasm);
      client.ensureReady();
      return new NativeCryptoNoise(client);
    })
    .catch((error: unknown) => {
      loading = undefined;
      throw error;
    });
  return loading;
}

/** Loads the Wasm from the extension's own files, through `getURL(path)`. */
export function extensionWasmOptions(getURL: (path: string) => string): InitializeOptions {
  return {
    locateFile: (path) => getURL(`/${path}`),
  };
}
