// The Noise calls of NativeCryptoNoise.kt that the extension needs: static key
// pairs, the XX handshake and its transport. The Wasm owns the Noise state and
// its keys; the caller owns framing, timeouts and retries.
import { create } from "@bufbuild/protobuf";
import {
  NoiseHandshakeReadRequestSchema,
  NoiseHandshakeStatusRequestSchema,
  NoiseHandshakeStatusResultSchema,
  NoiseHandshakeStreamOpenRequestSchema,
  NoiseHandshakeWriteRequestSchema,
  NoiseIntoTransportRequestSchema,
  NoiseKeyPairRequestSchema,
  NoiseKeyPairResultSchema,
  NoisePattern,
  NoiseTransportReadRequestSchema,
  NoiseTransportWriteRequestSchema,
  type NoiseHandshakeStatusResult,
} from "../../generated/proto/native_crypto_pb";
import { decodePayload, NativeCryptoClient, NativeCryptoError } from "./nativeCrypto";

/** Length of a private or public key. */
export const KEY_SIZE = 32;
/** Length of a handshake hash. */
export const HASH_SIZE = 32;
/** Largest Noise message. */
export const MAX_MESSAGE_SIZE = 65_535;
/** Largest payload of one transport message. */
export const MAX_TRANSPORT_PAYLOAD_SIZE = MAX_MESSAGE_SIZE - 16;

const EMPTY = new Uint8Array(0);

const KEY_PAIR_OPERATION = "noise_key_pair";
const HANDSHAKE_OPEN_OPERATION = "noise_handshake.stream_open";
const HANDSHAKE_WRITE_OPERATION = "noise_handshake_write";
const HANDSHAKE_READ_OPERATION = "noise_handshake_read";
const HANDSHAKE_STATUS_OPERATION = "noise_handshake_status";
const INTO_TRANSPORT_OPERATION = "noise_into_transport";
const TRANSPORT_WRITE_OPERATION = "noise_transport_write";
const TRANSPORT_READ_OPERATION = "noise_transport_read";

/** A Curve25519 static key pair. */
export interface NoiseKeyPair {
  privateKey: Uint8Array;
  publicKey: Uint8Array;
}

export class NativeCryptoNoise {
  constructor(private readonly client: NativeCryptoClient) {}

  /** Generates a static key pair from the system RNG. */
  generateKeyPair(): NoiseKeyPair {
    return this.keyPair(EMPTY);
  }

  /** Returns the key pair of the static `privateKey`, deriving its public key. */
  keyPairOf(privateKey: Uint8Array): NoiseKeyPair {
    // The native side takes an empty key as a request for a new one.
    if (privateKey.length !== KEY_SIZE) throw new NativeCryptoError(KEY_PAIR_OPERATION, "INVALID_ARGUMENT");
    return this.keyPair(privateKey);
  }

  /**
   * Starts a `Noise_XX_25519_ChaChaPoly_SHA256` handshake. Both sides pass the
   * same prologue. The handshake proves only that the peer holds its key:
   * check [NoiseHandshake.remoteStaticKey] against a pinned key, or confirm it
   * out of band, before trusting the peer.
   */
  xx(options: { initiator: boolean; localPrivateKey: Uint8Array; prologue: Uint8Array }): NoiseHandshake {
    const handle = this.client.openHandle(HANDSHAKE_OPEN_OPERATION, {
      case: "noiseHandshake",
      value: create(NoiseHandshakeStreamOpenRequestSchema, {
        pattern: NoisePattern.XX,
        initiator: options.initiator,
        localPrivateKey: options.localPrivateKey,
        prologue: options.prologue,
      }),
    });
    return new NoiseHandshake(this.client, handle);
  }

  private keyPair(privateKey: Uint8Array): NoiseKeyPair {
    const payload = this.client.callBytes(KEY_PAIR_OPERATION, {
      case: "noiseKeyPair",
      value: create(NoiseKeyPairRequestSchema, { privateKey }),
    });
    const result = decodePayload(KEY_PAIR_OPERATION, NoiseKeyPairResultSchema, payload, (it) => ({
      ...it,
      privateKey: it.privateKey.slice(),
      publicKey: it.publicKey.slice(),
    }));
    if (result.privateKey.length !== KEY_SIZE || result.publicKey.length !== KEY_SIZE) {
      result.privateKey.fill(0);
      throw new NativeCryptoError(KEY_PAIR_OPERATION, "MALFORMED_RESPONSE");
    }
    return { privateKey: result.privateKey, publicKey: result.publicKey };
  }
}

/**
 * One side of a Noise handshake. Exchange messages until [isFinished], read
 * [remoteStaticKey] and [handshakeHash], then call [intoTransport]. [close] it
 * in every case; closing twice, or after [intoTransport], is a no-op. A failed
 * read or write ends the handshake, unless it failed with `INVALID_ARGUMENT`.
 */
export class NoiseHandshake {
  private closed = false;

  constructor(
    private readonly client: NativeCryptoClient,
    private readonly handle: bigint,
  ) {}

  /** Writes the next handshake message carrying `payload`. */
  writeMessage(payload: Uint8Array = EMPTY): Uint8Array {
    return this.client.callBytes(HANDSHAKE_WRITE_OPERATION, {
      case: "noiseHandshakeWrite",
      value: create(NoiseHandshakeWriteRequestSchema, { handle: this.handle, payload }),
    });
  }

  /** Reads the next handshake message and returns its payload. */
  readMessage(message: Uint8Array): Uint8Array {
    return this.client.callBytes(HANDSHAKE_READ_OPERATION, {
      case: "noiseHandshakeRead",
      value: create(NoiseHandshakeReadRequestSchema, { handle: this.handle, message }),
    });
  }

  /** Whether every message of the pattern was written or read. */
  isFinished(): boolean {
    return this.status().finished;
  }

  /** The peer's static public key, or `null` while it isn't known yet. */
  remoteStaticKey(): Uint8Array | null {
    const key = this.status().remoteStaticKey;
    if (key.length === 0) return null;
    if (key.length !== KEY_SIZE) throw new NativeCryptoError(HANDSHAKE_STATUS_OPERATION, "MALFORMED_RESPONSE");
    return key;
  }

  /** The handshake hash, equal on both sides. Throws before [isFinished]. */
  handshakeHash(): Uint8Array {
    const status = this.status();
    if (!status.finished) throw new NativeCryptoError(HANDSHAKE_STATUS_OPERATION, "CRYPTO_FAILURE");
    if (status.handshakeHash.length !== HASH_SIZE) {
      throw new NativeCryptoError(HANDSHAKE_STATUS_OPERATION, "MALFORMED_RESPONSE");
    }
    return status.handshakeHash;
  }

  /** Spends this finished handshake and returns its transport. */
  intoTransport(): NoiseTransport {
    // The handshake handle is spent whatever the outcome.
    this.closed = true;
    const handle = this.client.callHandle(INTO_TRANSPORT_OPERATION, {
      case: "noiseIntoTransport",
      value: create(NoiseIntoTransportRequestSchema, { handle: this.handle }),
    });
    return new NoiseTransport(this.client, handle);
  }

  /** Releases the handshake and wipes its keys. */
  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.client.closeHandle(this.handle);
  }

  private status(): NoiseHandshakeStatusResult {
    const payload = this.client.callBytes(HANDSHAKE_STATUS_OPERATION, {
      case: "noiseHandshakeStatus",
      value: create(NoiseHandshakeStatusRequestSchema, { handle: this.handle }),
    });
    return decodePayload(HANDSHAKE_STATUS_OPERATION, NoiseHandshakeStatusResultSchema, payload, (it) => ({
      ...it,
      remoteStaticKey: it.remoteStaticKey.slice(),
      handshakeHash: it.handshakeHash.slice(),
    }));
  }
}

/**
 * The encrypted channel of a finished handshake. Messages must be read in the
 * order the other side wrote them. A failed read or write ends the transport,
 * unless it failed with `INVALID_ARGUMENT`. [close] it in every case.
 */
export class NoiseTransport {
  private closed = false;

  constructor(
    private readonly client: NativeCryptoClient,
    private readonly handle: bigint,
  ) {}

  /** Encrypts `payload`, at most [MAX_TRANSPORT_PAYLOAD_SIZE] bytes, into the next message. */
  writeMessage(payload: Uint8Array): Uint8Array {
    return this.client.callBytes(TRANSPORT_WRITE_OPERATION, {
      case: "noiseTransportWrite",
      value: create(NoiseTransportWriteRequestSchema, { handle: this.handle, payload }),
    });
  }

  /** Decrypts the next message and returns its payload. */
  readMessage(message: Uint8Array): Uint8Array {
    return this.client.callBytes(TRANSPORT_READ_OPERATION, {
      case: "noiseTransportRead",
      value: create(NoiseTransportReadRequestSchema, { handle: this.handle, message }),
    });
  }

  /** Releases the transport and wipes its keys. */
  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.client.closeHandle(this.handle);
  }
}
