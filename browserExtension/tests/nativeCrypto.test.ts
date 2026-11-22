// The protobuf envelope of the TS client, against bytes derived by hand from
// util/crypto/schema/native_crypto.proto, which is what NativeCrypto.kt's
// kotlinx.serialization encoder writes for the same requests.
import { create, fromBinary, toBinary, type MessageInitShape } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import {
  NativeErrorCode,
  NativeRequestSchema,
  NativeResponseSchema,
  NativeStatusSchema,
  NativeStreamOpenRequestSchema,
  NoiseHandshakeStreamOpenRequestSchema,
  NoiseHandshakeWriteRequestSchema,
  NoiseKeyPairRequestSchema,
  NoisePattern,
} from "../src/generated/proto/native_crypto_pb";
import {
  encodeRequest,
  encodeStreamOpenRequest,
  NativeCryptoClient,
  NativeCryptoError,
  type NativeCryptoBridge,
} from "../src/lib/crypto/nativeCrypto";
import { concatBytes, fromHex, toHex } from "../src/lib/util/bytes";
import { vectors } from "./support/vectors";

describe("request encoding", () => {
  it("encodes a key pair request like NativeCrypto.kt", () => {
    // protocol_version = 3; noise_key_pair (71, LEN) = {} with the empty
    // private key omitted, as kotlinx omits a default value.
    const bytes = encodeRequest({ case: "noiseKeyPair", value: create(NoiseKeyPairRequestSchema, {}) });
    expect(toHex(bytes)).toBe("0803" + "ba04" + "00");
  });

  it("encodes an XX initiator open request like NativeCrypto.kt", () => {
    const key = fromHex(vectors.keyguard.init_static);
    const prologue = fromHex(vectors.keyguard.init_prologue);
    expect(prologue.length).toBe(72);
    const bytes = encodeStreamOpenRequest({
      case: "noiseHandshake",
      value: create(NoiseHandshakeStreamOpenRequestSchema, {
        pattern: NoisePattern.XX,
        initiator: true,
        localPrivateKey: key,
        prologue,
      }),
    });
    // pattern = 3, initiator = true, local_private_key (3), no remote key or
    // psk, prologue (6): 2 + 2 + 34 + 74 = 112 bytes inside noise_handshake (23).
    const inner = concatBytes(fromHex("0803" + "1001" + "1a20"), key, fromHex("3248"), prologue);
    expect(inner.length).toBe(0x70);
    expect(bytes).toEqual(concatBytes(fromHex("0803" + "ba01" + "70"), inner));
  });

  it("encodes 64-bit handles as unsigned varints", () => {
    const write = (handle: bigint) =>
      encodeRequest({
        case: "noiseHandshakeWrite",
        value: create(NoiseHandshakeWriteRequestSchema, { handle }),
      });
    // noise_handshake_write (72) = {handle = 1}; the empty payload is omitted.
    expect(toHex(write(1n))).toBe("0803" + "c204" + "02" + "0801");
    // A handle above 2^63 is what Kotlin sees as a negative Long: ten bytes.
    const large = (1n << 63n) + 5n;
    const decoded = fromBinary(NativeRequestSchema, write(large));
    expect(decoded.operation.case).toBe("noiseHandshakeWrite");
    expect(decoded.operation.value).toMatchObject({ handle: large });
    expect(write(large).length).toBe(2 + 2 + 1 + 11);
  });

  it("decodes what it encodes", () => {
    const bytes = encodeStreamOpenRequest({
      case: "noiseHandshake",
      value: create(NoiseHandshakeStreamOpenRequestSchema, {
        pattern: NoisePattern.XX,
        initiator: false,
        localPrivateKey: new Uint8Array(32).fill(7),
        prologue: new Uint8Array([1, 2, 3]),
      }),
    });
    const decoded = fromBinary(NativeStreamOpenRequestSchema, bytes);
    expect(decoded.protocolVersion).toBe(3);
    expect(decoded.operation.case).toBe("noiseHandshake");
    expect(decoded.operation.value).toMatchObject({
      pattern: NoisePattern.XX,
      initiator: false,
      localPrivateKey: new Uint8Array(32).fill(7),
      prologue: new Uint8Array([1, 2, 3]),
    });
  });
});

function response(init: MessageInitShape<typeof NativeResponseSchema>): Uint8Array {
  return toBinary(NativeResponseSchema, create(NativeResponseSchema, init));
}

const OK = create(NativeStatusSchema, { code: NativeErrorCode.OK });

function bridge(overrides: Partial<NativeCryptoBridge>): NativeCryptoBridge {
  return {
    abiVersion: () => 1,
    capabilities: () => Int32Array.of(0, 1 << 8),
    call: () => response({ protocolVersion: 3, status: OK, result: { case: "bytesValue", value: Uint8Array.of(1) } }),
    streamOpen: () => response({ protocolVersion: 3, status: OK, result: { case: "uint64Value", value: 9n } }),
    streamClose: () => response({ protocolVersion: 3, status: OK }),
    ...overrides,
  };
}

const keyPair = { case: "noiseKeyPair", value: create(NoiseKeyPairRequestSchema, {}) } as const;

function failure(block: () => unknown): NativeCryptoError {
  try {
    block();
  } catch (error) {
    if (error instanceof NativeCryptoError) return error;
    throw error;
  }
  throw new Error("Expected a failure");
}

describe("NativeCryptoClient", () => {
  it("returns bytes, handles and wipes the envelopes", () => {
    let request: Uint8Array | null = null;
    let returned: Uint8Array | null = null;
    const client = new NativeCryptoClient(
      bridge({
        call: (bytes) => {
          request = bytes;
          returned = response({
            protocolVersion: 3,
            status: OK,
            result: { case: "bytesValue", value: Uint8Array.of(4, 5) },
          });
          return returned;
        },
      }),
    );
    expect(client.callBytes("noise_key_pair", keyPair)).toEqual(Uint8Array.of(4, 5));
    expect(request!.every((byte) => byte === 0)).toBe(true);
    expect(returned!.every((byte) => byte === 0)).toBe(true);
    expect(
      client.openHandle("noise_handshake.stream_open", {
        case: "noiseHandshake",
        value: create(NoiseHandshakeStreamOpenRequestSchema, {}),
      }),
    ).toBe(9n);
  });

  it("checks the ABI version and the Noise capability first", () => {
    expect(failure(() => new NativeCryptoClient(bridge({ abiVersion: () => 2 })).callBytes("x", keyPair)).code).toBe(
      "ABI_MISMATCH",
    );
    const noNoise = new NativeCryptoClient(bridge({ capabilities: () => Int32Array.of(-1, ~(1 << 8)) }));
    expect(failure(() => noNoise.callBytes("x", keyPair)).code).toBe("MISSING_CAPABILITY");
  });

  it.each([
    [
      "an error status",
      response({
        protocolVersion: 3,
        status: create(NativeStatusSchema, { code: NativeErrorCode.AUTHENTICATION_FAILED }),
      }),
      "AUTHENTICATION_FAILED",
    ],
    [
      "an unknown error status",
      response({ protocolVersion: 3, status: create(NativeStatusSchema, { code: 99 as NativeErrorCode }) }),
      "INTERNAL",
    ],
    ["another protocol version", response({ protocolVersion: 2, status: OK }), "UNSUPPORTED_PROTOCOL"],
    [
      "a missing status",
      response({ protocolVersion: 3, result: { case: "bytesValue", value: Uint8Array.of(1) } }),
      "MALFORMED_RESPONSE",
    ],
    ["a missing result", response({ protocolVersion: 3, status: OK }), "MALFORMED_RESPONSE"],
    [
      "a result of another type",
      response({ protocolVersion: 3, status: OK, result: { case: "int32Value", value: 1 } }),
      "MALFORMED_RESPONSE",
    ],
    ["bytes that aren't protobuf", Uint8Array.of(0xff, 0xff, 0xff), "MALFORMED_RESPONSE"],
  ])("maps %s to its error code", (_, bytes, code) => {
    const client = new NativeCryptoClient(bridge({ call: () => bytes }));
    const error = failure(() => client.callBytes("noise_key_pair", keyPair));
    expect(error.code).toBe(code);
    expect(error.operation).toBe("noise_key_pair");
  });

  it("refuses a zero handle and a non-empty close", () => {
    const zero = new NativeCryptoClient(
      bridge({
        streamOpen: () => response({ protocolVersion: 3, status: OK, result: { case: "uint64Value", value: 0n } }),
      }),
    );
    const open = { case: "noiseHandshake", value: create(NoiseHandshakeStreamOpenRequestSchema, {}) } as const;
    expect(failure(() => zero.openHandle("open", open)).code).toBe("MALFORMED_RESPONSE");
    const close = new NativeCryptoClient(
      bridge({
        streamClose: () =>
          response({ protocolVersion: 3, status: OK, result: { case: "bytesValue", value: Uint8Array.of(1) } }),
      }),
    );
    expect(failure(() => close.closeHandle(1n)).code).toBe("MALFORMED_RESPONSE");
  });

  it("splits a handle into its 32-bit words to close it", () => {
    const words: number[][] = [];
    const client = new NativeCryptoClient(
      bridge({
        streamClose: (low, high) => {
          words.push([low, high]);
          return response({ protocolVersion: 3, status: OK });
        },
      }),
    );
    client.closeHandle(0xfedcba98_76543210n);
    expect(words).toEqual([[0x76543210, 0xfedcba98 | 0]]);
  });
});
