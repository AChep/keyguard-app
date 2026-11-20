// A thin client of util/crypto's protobuf envelope, mirroring the envelope and
// error handling of NativeCrypto.kt (NativeCryptoClient). Only what the
// extension needs: calls, handle-returning calls, and stream open/close.
import { create, fromBinary, toBinary, type DescMessage, type MessageShape } from "@bufbuild/protobuf";
import {
  NativeErrorCode,
  NativeRequestSchema,
  NativeResponseSchema,
  NativeStreamOpenRequestSchema,
  type NativeRequest,
  type NativeResponse,
  type NativeStreamOpenRequest,
} from "../../generated/proto/native_crypto_pb";

export const EXPECTED_ABI_VERSION = 1;
export const PROTOCOL_VERSION = 3;
export const MAX_CONTROL_ENVELOPE_BYTES = 16 * 1024 * 1024;

/** `NativeCryptoCapability.NOISE` of NativeCrypto.kt. */
export const NOISE_CAPABILITY_BIT = 1n << 40n;

/** The runtime of util/crypto/wasm/index.mjs, or a fake of it in tests. */
export interface NativeCryptoBridge {
  abiVersion(): number;
  capabilities(): Int32Array;
  call(request: Uint8Array): Int8Array | Uint8Array;
  streamOpen(request: Uint8Array): Int8Array | Uint8Array;
  streamClose(low: number, high: number): Int8Array | Uint8Array;
}

/** The codes of `NativeCryptoErrorCode` in NativeCryptoError.kt. */
export type NativeCryptoErrorCode =
  | "ABI_MISMATCH"
  | "MISSING_CAPABILITY"
  | "MALFORMED_RESPONSE"
  | "INVALID_REQUEST"
  | "UNSUPPORTED_PROTOCOL"
  | "INVALID_ARGUMENT"
  | "RESOURCE_LIMIT"
  | "CRYPTO_FAILURE"
  | "AUTHENTICATION_FAILED"
  | "INVALID_SESSION"
  | "PANIC"
  | "INTERNAL"
  | "UNSUPPORTED_KEY_VERSION"
  | "NO_USABLE_KEY"
  | "UNSUPPORTED_OPERATION";

const WIRE_ERROR_CODES: Partial<Record<NativeErrorCode, NativeCryptoErrorCode>> = {
  [NativeErrorCode.INVALID_REQUEST]: "INVALID_REQUEST",
  [NativeErrorCode.UNSUPPORTED_PROTOCOL]: "UNSUPPORTED_PROTOCOL",
  [NativeErrorCode.INVALID_ARGUMENT]: "INVALID_ARGUMENT",
  [NativeErrorCode.RESOURCE_LIMIT]: "RESOURCE_LIMIT",
  [NativeErrorCode.CRYPTO_FAILURE]: "CRYPTO_FAILURE",
  [NativeErrorCode.AUTHENTICATION_FAILED]: "AUTHENTICATION_FAILED",
  [NativeErrorCode.INVALID_SESSION]: "INVALID_SESSION",
  [NativeErrorCode.PANIC]: "PANIC",
  [NativeErrorCode.INTERNAL]: "INTERNAL",
  [NativeErrorCode.UNSUPPORTED_KEY_VERSION]: "UNSUPPORTED_KEY_VERSION",
  [NativeErrorCode.NO_USABLE_KEY]: "NO_USABLE_KEY",
  [NativeErrorCode.UNSUPPORTED_OPERATION]: "UNSUPPORTED_OPERATION",
};

/** A stable, secret-free failure from the crypto boundary. */
export class NativeCryptoError extends Error {
  constructor(
    readonly operation: string,
    readonly code: NativeCryptoErrorCode,
  ) {
    super(`Native crypto failed: operation=${operation}, code=${code}`);
    this.name = "NativeCryptoError";
  }
}

type Defined<T> = Exclude<T, { case: undefined }>;
export type RequestOperation = Defined<NativeRequest["operation"]>;
export type StreamOpenOperation = Defined<NativeStreamOpenRequest["operation"]>;
export type ResponseResult = Defined<NativeResponse["result"]>;

function malformed(operation: string): NativeCryptoError {
  return new NativeCryptoError(operation, "MALFORMED_RESPONSE");
}

function bytesOf(array: Int8Array | Uint8Array): Uint8Array {
  return array instanceof Uint8Array ? array : new Uint8Array(array.buffer, array.byteOffset, array.byteLength);
}

/** Encodes a request envelope the way NativeCrypto.kt does. */
export function encodeRequest(operation: RequestOperation): Uint8Array {
  return toBinary(NativeRequestSchema, create(NativeRequestSchema, { protocolVersion: PROTOCOL_VERSION, operation }));
}

/** Encodes a stream-open envelope the way NativeCrypto.kt does. */
export function encodeStreamOpenRequest(operation: StreamOpenOperation): Uint8Array {
  return toBinary(
    NativeStreamOpenRequestSchema,
    create(NativeStreamOpenRequestSchema, { protocolVersion: PROTOCOL_VERSION, operation }),
  );
}

/**
 * Decodes a domain payload returned inside `bytes_value`, then wipes it. A
 * payload that doesn't decode is a malformed response. Byte fields are copied,
 * as the decoder returns views of the payload.
 */
export function decodePayload<Desc extends DescMessage>(
  operation: string,
  schema: Desc,
  payload: Uint8Array,
  copy: (message: MessageShape<Desc>) => MessageShape<Desc>,
): MessageShape<Desc> {
  try {
    return copy(fromBinary(schema, payload));
  } catch {
    throw malformed(operation);
  } finally {
    payload.fill(0);
  }
}

export class NativeCryptoClient {
  private ready = false;

  constructor(private readonly bridge: NativeCryptoBridge) {}

  /** Checks the ABI version and that the backend can run Noise. */
  ensureReady(): void {
    if (this.ready) return;
    if (this.bridge.abiVersion() !== EXPECTED_ABI_VERSION) {
      throw new NativeCryptoError("bootstrap.abi", "ABI_MISMATCH");
    }
    const words = this.bridge.capabilities();
    const mask = (BigInt((words[1] ?? 0) >>> 0) << 32n) | BigInt((words[0] ?? 0) >>> 0);
    if ((mask & NOISE_CAPABILITY_BIT) === 0n) {
      throw new NativeCryptoError("bootstrap.capabilities", "MISSING_CAPABILITY");
    }
    this.ready = true;
  }

  call(operationName: string, operation: RequestOperation): ResponseResult {
    this.ensureReady();
    const request = this.envelope(operationName, () => encodeRequest(operation));
    let response: Uint8Array;
    try {
      response = bytesOf(this.bridge.call(request));
    } finally {
      request.fill(0);
    }
    const result = this.decodeResponse(operationName, response, true);
    if (result === undefined) throw malformed(operationName);
    return result;
  }

  callBytes(operationName: string, operation: RequestOperation): Uint8Array {
    const result = this.call(operationName, operation);
    if (result.case !== "bytesValue") throw malformed(operationName);
    return result.value;
  }

  /** Calls an operation that answers with a new session handle. */
  callHandle(operationName: string, operation: RequestOperation): bigint {
    return requireHandle(operationName, this.call(operationName, operation));
  }

  /** Opens a session whose requests go through [call] with its handle. */
  openHandle(operationName: string, operation: StreamOpenOperation): bigint {
    this.ensureReady();
    const request = this.envelope(operationName, () => encodeStreamOpenRequest(operation));
    let response: Uint8Array;
    try {
      response = bytesOf(this.bridge.streamOpen(request));
    } finally {
      request.fill(0);
    }
    return requireHandle(operationName, this.decodeResponse(operationName, response, true));
  }

  /** Releases a session [openHandle] returned. This operation is idempotent. */
  closeHandle(handle: bigint): void {
    const operation = "stream.close";
    const low = Number(handle & 0xffffffffn) | 0;
    const high = Number(handle >> 32n) | 0;
    const result = this.decodeResponse(operation, bytesOf(this.bridge.streamClose(low, high)), false);
    if (result === undefined) return;
    if (result.case !== "bytesValue") throw malformed(operation);
    if (result.value.length > 0) {
      result.value.fill(0);
      throw malformed(operation);
    }
  }

  private envelope(operation: string, encode: () => Uint8Array): Uint8Array {
    let encoded: Uint8Array;
    try {
      encoded = encode();
    } catch {
      throw new NativeCryptoError(operation, "INTERNAL");
    }
    if (encoded.length > MAX_CONTROL_ENVELOPE_BYTES) {
      encoded.fill(0);
      throw new NativeCryptoError(operation, "RESOURCE_LIMIT");
    }
    return encoded;
  }

  private decodeResponse(
    expectedOperation: string,
    response: Uint8Array,
    requireResult: boolean,
  ): ResponseResult | undefined {
    try {
      if (response.length > MAX_CONTROL_ENVELOPE_BYTES) {
        throw new NativeCryptoError(expectedOperation, "RESOURCE_LIMIT");
      }
      let decoded: NativeResponse;
      try {
        decoded = fromBinary(NativeResponseSchema, response);
      } catch {
        throw malformed(expectedOperation);
      }
      if (decoded.protocolVersion !== PROTOCOL_VERSION) {
        throw new NativeCryptoError(expectedOperation, "UNSUPPORTED_PROTOCOL");
      }
      const status = decoded.status;
      if (status === undefined) throw malformed(expectedOperation);
      if (status.code !== NativeErrorCode.OK) {
        throw new NativeCryptoError(expectedOperation, WIRE_ERROR_CODES[status.code] ?? "INTERNAL");
      }
      const result = decoded.result;
      if (result.case === undefined) {
        if (requireResult) throw malformed(expectedOperation);
        return undefined;
      }
      // The decoder returns views of the response, which is wiped below.
      if (result.case === "bytesValue") return { case: "bytesValue", value: result.value.slice() };
      return result;
    } finally {
      response.fill(0);
    }
  }
}

function requireHandle(operation: string, result: ResponseResult | undefined): bigint {
  if (result?.case !== "uint64Value") {
    if (result?.case === "bytesValue") result.value.fill(0);
    throw malformed(operation);
  }
  if (result.value === 0n) throw malformed(operation);
  return result.value;
}
