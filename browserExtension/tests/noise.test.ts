// The TS client against the real util/crypto Wasm, both sides of XX.
import { beforeAll, describe, expect, it } from "vitest";
import { NativeCryptoError } from "../src/lib/crypto/nativeCrypto";
import type { NativeCryptoNoise } from "../src/lib/crypto/noise";
import { loadNoise } from "../src/lib/crypto/wasm";
import { fromHex, toHex, utf8 } from "../src/lib/util/bytes";
import { vectors } from "./support/vectors";

let noise: NativeCryptoNoise;

beforeAll(async () => {
  noise = await loadNoise();
});

function code(block: () => unknown): string {
  try {
    block();
  } catch (error) {
    if (error instanceof NativeCryptoError) return error.code;
    throw error;
  }
  throw new Error("Expected a failure");
}

function handshake(prologues: [Uint8Array, Uint8Array] = [utf8("p"), utf8("p")]) {
  const extension = noise.generateKeyPair();
  const app = noise.generateKeyPair();
  const initiator = noise.xx({ initiator: true, localPrivateKey: extension.privateKey, prologue: prologues[0] });
  const responder = noise.xx({ initiator: false, localPrivateKey: app.privateKey, prologue: prologues[1] });
  return { extension, app, initiator, responder };
}

describe("Noise through the Wasm", () => {
  it("derives the public keys of the vectors", () => {
    const vector = vectors.keyguard;
    expect(toHex(noise.keyPairOf(fromHex(vector.init_static)).publicKey)).toBe(vector.init_static_public);
    expect(toHex(noise.keyPairOf(fromHex(vector.resp_static)).publicKey)).toBe(vector.resp_static_public);
    expect(code(() => noise.keyPairOf(new Uint8Array(31)))).toBe("INVALID_ARGUMENT");
  });

  it("generates distinct key pairs", () => {
    const first = noise.generateKeyPair();
    const second = noise.generateKeyPair();
    expect(first.privateKey.length).toBe(32);
    expect(first.publicKey).not.toEqual(second.publicKey);
    expect(noise.keyPairOf(first.privateKey).publicKey).toEqual(first.publicKey);
  });

  it("runs an XX handshake and transport between initiator and responder", () => {
    const { extension, app, initiator, responder } = handshake();
    const message1 = initiator.writeMessage();
    expect(message1.length).toBe(32);
    expect(responder.readMessage(message1).length).toBe(0);
    const message2 = responder.writeMessage();
    expect(message2.length).toBe(96);
    expect(initiator.readMessage(message2).length).toBe(0);
    // The initiator knows the app's key before it sends its own.
    expect(initiator.remoteStaticKey()).toEqual(app.publicKey);
    expect(initiator.isFinished()).toBe(false);
    expect(responder.remoteStaticKey()).toBeNull();
    const message3 = initiator.writeMessage();
    expect(message3.length).toBe(64);
    responder.readMessage(message3);
    expect(responder.remoteStaticKey()).toEqual(extension.publicKey);
    expect(initiator.isFinished() && responder.isFinished()).toBe(true);
    expect(initiator.handshakeHash()).toEqual(responder.handshakeHash());

    const sending = initiator.intoTransport();
    const receiving = responder.intoTransport();
    const first = sending.writeMessage(utf8("hello"));
    expect(first.length).toBe(5 + 16);
    expect(receiving.readMessage(first)).toEqual(utf8("hello"));
    expect(sending.readMessage(receiving.writeMessage(utf8("back")))).toEqual(utf8("back"));

    const forged = sending.writeMessage(utf8("x"));
    forged[0]! ^= 1;
    expect(code(() => receiving.readMessage(forged))).toBe("AUTHENTICATION_FAILED");
    // A failed read ends the transport.
    expect(code(() => receiving.readMessage(sending.writeMessage(utf8("y"))))).toBe("INVALID_SESSION");
    sending.close();
    receiving.close();
    // Closing twice is a no-op.
    sending.close();
  });

  it("fails message 2 when the prologues differ", () => {
    const { initiator, responder } = handshake([utf8("keyguard-browser/v1\na"), utf8("keyguard-browser/v1\nb")]);
    responder.readMessage(initiator.writeMessage());
    expect(code(() => initiator.readMessage(responder.writeMessage()))).toBe("AUTHENTICATION_FAILED");
    initiator.close();
    responder.close();
  });

  it("has no hash before the handshake finishes", () => {
    const { initiator, responder } = handshake();
    expect(code(() => initiator.handshakeHash())).toBe("CRYPTO_FAILURE");
    initiator.close();
    responder.close();
    expect(code(() => initiator.writeMessage())).toBe("INVALID_SESSION");
  });
});
