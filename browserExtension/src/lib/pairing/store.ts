// The extension's static key and its pairing (protocol/README.md §2 Keys and
// §8), kept where content scripts can't read them: the background passes the
// IndexedDB of the extension's origin (../storage/indexedDb.ts).
import type { NativeCryptoNoise, NoiseKeyPair } from "../crypto/noise";
import { KEY_SIZE } from "../crypto/noise";
import { fromBase64, toBase64 } from "../util/bytes";
import { isObject } from "../util/json";
import { SerialQueue } from "../util/serial";

const STORAGE_KEY = "keyguard";

/** The part of a `storage` area the store uses. */
export interface StorageArea {
  get(key: string): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
}

export interface Pairing {
  /** The app's static public key, pinned at pairing. */
  appKey: Uint8Array;
}

/**
 * The stored record. A later format has a greater `version`. Earlier builds
 * stored a `pairingId` in `pairing` too: reads ignore it.
 */
interface Stored {
  version: 1;
  privateKey: string;
  publicKey: string;
  pairing?: { appKey: string };
}

/**
 * A newer version of the extension stored the key and the pairing, in a
 * format this one can't read. The store neither uses nor replaces it, so a
 * downgrade keeps the newer version's key and pairing.
 */
export class UnsupportedStoreVersion extends Error {
  constructor(readonly version: number) {
    super(`The key and the pairing are stored in format ${version} of a newer extension version; update the extension`);
    this.name = "UnsupportedStoreVersion";
  }
}

function decodeKey(value: unknown): Uint8Array | null {
  const bytes = typeof value === "string" ? fromBase64(value) : null;
  return bytes?.length === KEY_SIZE ? bytes : null;
}

/**
 * The record, or `null` when there is none or it's unreadable: no version
 * writes such a record, and a new key replaces it. Throws
 * [UnsupportedStoreVersion] for a record of a later format.
 */
function parseStored(value: unknown): Stored | null {
  if (!isObject(value)) return null;
  const { version } = value;
  if (typeof version === "number" && version > 1) throw new UnsupportedStoreVersion(version);
  if (version !== 1 || decodeKey(value.privateKey) === null || decodeKey(value.publicKey) === null) return null;
  return value as unknown as Stored;
}

function pairingOf(stored: Stored | null): Pairing | null {
  const appKey = decodeKey(stored?.pairing?.appKey);
  return appKey === null ? null : { appKey };
}

/**
 * Every call reads the record first: one of a later format fails the call
 * with [UnsupportedStoreVersion], and nothing is written.
 */
export class PairingStore {
  // Serializes writes, so a key is never created twice.
  private readonly queue = new SerialQueue();

  constructor(private readonly area: StorageArea) {}

  /** The static key pair, created on first use. */
  async staticKey(noise: NativeCryptoNoise): Promise<NoiseKeyPair> {
    return (await this.load(noise)).keys;
  }

  /** The static key pair, created on first use, and the pairing, from one read. */
  load(noise: NativeCryptoNoise): Promise<{ keys: NoiseKeyPair; pairing: Pairing | null }> {
    return this.queue.run(async () => {
      const stored = await this.read();
      if (stored === null) return { keys: await this.writeKey(noise), pairing: null };
      return {
        keys: { privateKey: fromBase64(stored.privateKey)!, publicKey: fromBase64(stored.publicKey)! },
        pairing: pairingOf(stored),
      };
    });
  }

  /** The pairing, or `null` when the extension isn't paired. */
  pairing(): Promise<Pairing | null> {
    return this.queue.run(async () => pairingOf(await this.read()));
  }

  /** Pins the app's key after a confirmed pairing. */
  setPairing(pairing: Pairing): Promise<void> {
    return this.queue.run(async () => {
      const stored = await this.read();
      if (stored === null) throw new Error("No static key to pair");
      await this.write({ ...stored, pairing: { appKey: toBase64(pairing.appKey) } });
    });
  }

  /** Forgets the pairing. With `rotateKey`, also replaces the static key. */
  forgetPairing(options: { rotateKey?: NativeCryptoNoise } = {}): Promise<void> {
    return this.queue.run(async () => {
      const stored = await this.read();
      if (options.rotateKey !== undefined) {
        await this.writeKey(options.rotateKey);
        return;
      }
      if (stored?.pairing === undefined) return;
      const { pairing: _, ...rest } = stored;
      await this.write(rest);
    });
  }

  private async writeKey(noise: NativeCryptoNoise): Promise<NoiseKeyPair> {
    const keys = noise.generateKeyPair();
    await this.write({ version: 1, privateKey: toBase64(keys.privateKey), publicKey: toBase64(keys.publicKey) });
    return keys;
  }

  private async read(): Promise<Stored | null> {
    return parseStored((await this.area.get(STORAGE_KEY))[STORAGE_KEY]);
  }

  private write(stored: Stored): Promise<void> {
    return this.area.set({ [STORAGE_KEY]: stored });
  }
}
