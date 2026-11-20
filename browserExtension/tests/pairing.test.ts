import { describe, expect, it } from "vitest";
import { pairingCode, pairingCodeInfo, pairingCommit } from "../src/lib/pairing/code";
import { fromBase64, fromHex, toHex } from "../src/lib/util/bytes";
import { nodePairingCode } from "./support/fakeApp";
import { vectors } from "./support/vectors";

const vector = vectors.keyguard;
const pairing = vector.pairing;

const input = {
  handshakeHash: fromHex(vector.handshake_hash),
  extensionPublicKey: fromHex(vector.init_static_public),
  appPublicKey: fromHex(vector.resp_static_public),
  appNonce: fromHex(pairing.app_nonce),
  extensionNonce: fromHex(pairing.extension_nonce),
};

describe("pairing code", () => {
  it("commits to the extension nonce as in the vectors", async () => {
    const commit = await pairingCommit(fromHex(pairing.extension_nonce));
    expect(toHex(commit)).toBe(pairing.commit);
    // The commitment the vector's pair_commit carries, as Base64.
    const sent = JSON.parse(vector.messages[5]!.text!) as { commit: string };
    expect(fromBase64(sent.commit)).toEqual(commit);
  });

  it("derives the info, HKDF output and code of the vectors", async () => {
    expect(toHex(pairingCodeInfo(input))).toBe(pairing.sas_info);
    const { code, okm } = await pairingCode(input);
    expect(toHex(okm)).toBe(pairing.sas_okm);
    expect(code).toBe(pairing.code);
  });

  it("sorts the static keys, so the roles don't change the code", async () => {
    const swapped = { ...input, extensionPublicKey: input.appPublicKey, appPublicKey: input.extensionPublicKey };
    expect(pairingCodeInfo(swapped)).toEqual(pairingCodeInfo(input));
  });

  it("matches an independent derivation for random inputs", async () => {
    for (let i = 0; i < 20; i++) {
      const random = (size: number) => crypto.getRandomValues(new Uint8Array(size));
      const sample = {
        handshakeHash: random(32),
        extensionPublicKey: random(32),
        appPublicKey: random(32),
        appNonce: random(32),
        extensionNonce: random(32),
      };
      const { code } = await pairingCode(sample);
      expect(code).toMatch(/^\d{6}$/);
      expect(code).toBe(
        nodePairingCode({
          handshakeHash: sample.handshakeHash,
          keys: [sample.extensionPublicKey, sample.appPublicKey],
          appNonce: sample.appNonce,
          extensionNonce: sample.extensionNonce,
        }),
      );
    }
  });
});
