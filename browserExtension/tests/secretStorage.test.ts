// The static key and the pairing in the IndexedDB of the extension's origin,
// where content scripts can't read them.
import { IDBFactory } from "fake-indexeddb";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { NativeCryptoNoise } from "../src/lib/crypto/noise";
import { loadNoise } from "../src/lib/crypto/wasm";
import { PairingStore, UnsupportedStoreVersion } from "../src/lib/pairing/store";
import { IndexedDbArea } from "../src/lib/storage/indexedDb";
import { toBase64 } from "../src/lib/util/bytes";

let noise: NativeCryptoNoise;
let factory: IDBFactory;

beforeAll(async () => {
  noise = await loadNoise();
});

beforeEach(() => {
  factory = new IDBFactory();
});

describe("IndexedDbArea", () => {
  it("gets, sets and removes values, across connections", async () => {
    const area = new IndexedDbArea(factory);
    expect(await area.get("a")).toEqual({});
    await area.set({ a: { nested: [1, 2] }, b: "text" });
    expect(await area.get("a")).toEqual({ a: { nested: [1, 2] } });
    expect(await new IndexedDbArea(factory).get("b")).toEqual({ b: "text" });
    await area.remove("a");
    expect(await area.get("a")).toEqual({});
    expect(await area.get("b")).toEqual({ b: "text" });
  });

  it("keeps databases of other names apart", async () => {
    await new IndexedDbArea(factory, "one").set({ a: 1 });
    expect(await new IndexedDbArea(factory, "two").get("a")).toEqual({});
  });
});

describe("PairingStore", () => {
  it("keeps the key pair and the pairing in IndexedDB", async () => {
    const secrets = new IndexedDbArea(factory);
    const store = new PairingStore(secrets);
    const keys = await store.staticKey(noise);
    expect(await new PairingStore(secrets).staticKey(noise)).toEqual(keys);
    const appKey = noise.generateKeyPair().publicKey;
    await store.setPairing({ appKey });
    expect(await new PairingStore(secrets).pairing()).toEqual({ appKey });
    await store.forgetPairing();
    expect(await new PairingStore(secrets).pairing()).toBeNull();
  });

  it("reads a pairing that an earlier build stored with a pairing ID, and stores none", async () => {
    const secrets = new IndexedDbArea(factory);
    const store = new PairingStore(secrets);
    const keys = await store.staticKey(noise);
    const appKey = noise.generateKeyPair().publicKey;
    const record = { version: 1, privateKey: toBase64(keys.privateKey), publicKey: toBase64(keys.publicKey) };
    await secrets.set({ keyguard: { ...record, pairing: { appKey: toBase64(appKey), pairingId: "pairing-9" } } });
    expect(await store.load(noise)).toEqual({ keys, pairing: { appKey } });
    await store.setPairing({ appKey });
    expect((await secrets.get("keyguard")).keyguard).toEqual({ ...record, pairing: { appKey: toBase64(appKey) } });
  });

  it("neither uses nor replaces the record of a newer version", async () => {
    const secrets = new IndexedDbArea(factory);
    const newer = { version: 2, identity: { key: "opaque" } };
    await secrets.set({ keyguard: newer });
    const store = new PairingStore(secrets);
    const appKey = noise.generateKeyPair().publicKey;
    for (const call of [
      () => store.load(noise),
      () => store.staticKey(noise),
      () => store.pairing(),
      () => store.setPairing({ appKey }),
      () => store.forgetPairing(),
      () => store.forgetPairing({ rotateKey: noise }),
    ]) {
      await expect(call()).rejects.toBeInstanceOf(UnsupportedStoreVersion);
    }
    expect(await secrets.get("keyguard")).toEqual({ keyguard: newer });
  });

  it("replaces an unreadable record with a new key", async () => {
    const secrets = new IndexedDbArea(factory);
    for (const record of ["text", { privateKey: "AA==" }, { version: 1, privateKey: "AA==", publicKey: "AA==" }, { version: 0 }]) {
      await secrets.set({ keyguard: record });
      const keys = await new PairingStore(secrets).staticKey(noise);
      expect((await secrets.get("keyguard")).keyguard, JSON.stringify(record)).toMatchObject({ version: 1, publicKey: toBase64(keys.publicKey) });
    }
  });
});
