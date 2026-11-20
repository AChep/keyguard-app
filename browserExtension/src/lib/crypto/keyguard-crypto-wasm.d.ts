// Types of util/crypto/wasm/index.mjs, the runtime package that
// `./gradlew :util:crypto:buildNativeCryptoWasmNoise` writes to
// util/crypto/build/wasm/npm-noise/. Only the functions the extension calls:
// that build exports nothing else.
declare module "keyguard-crypto-wasm" {
  export interface InitializeOptions {
    /** Maps a file the loader needs, `keyguard_crypto.wasm`, to its URL. */
    locateFile?: (path: string, prefix: string) => string;
    /** The Wasm binary itself, instead of fetching it. */
    wasmBinary?: ArrayBuffer | Uint8Array;
  }

  /** Loads the Wasm once per JS realm; later calls wait for the first one. */
  export function initialize(options?: InitializeOptions): Promise<void>;

  export function abiVersion(): number;

  /** The capability mask as two 32-bit words, low then high. */
  export function capabilities(): Int32Array;

  /** Runs one `NativeRequest` envelope and returns the `NativeResponse` bytes. */
  export function call(request: Uint8Array): Int8Array;

  /** Opens a stream session from a `NativeStreamOpenRequest` envelope. */
  export function streamOpen(request: Uint8Array): Int8Array;

  /** Closes the stream session whose 64-bit handle is `high:low`. */
  export function streamClose(low: number, high: number): Int8Array;
}
