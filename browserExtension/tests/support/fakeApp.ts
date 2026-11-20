// A stand-in for the Keyguard app's side of the protocol: a Noise XX responder
// through the same Wasm, with the pairing code derived independently with
// node:crypto, that answers with the shapes of protocol/fixtures/. Plus a fake
// native messaging port and host.
import nodeCrypto from "node:crypto";
import type { NativeCryptoNoise, NoiseHandshake, NoiseKeyPair, NoiseTransport } from "../../src/lib/crypto/noise";
import type { NativePort } from "../../src/lib/native/connection";
import { fragmentMessage, Reassembler, type JsonObject } from "../../src/lib/protocol/framing";
import type { VaultState } from "../../src/lib/protocol/messages";
import { PROLOGUE_PREFIX, ProtocolError, SUITE_ID } from "../../src/lib/protocol/session";
import { concatBytes, fromBase64, toBase64, toHex, utf8 } from "../../src/lib/util/bytes";
import { exchanges, responseFields } from "./fixtures";

type Reply = JsonObject | { error: string };
export type Handler = (request: JsonObject, app: FakeApp) => Reply | Promise<Reply> | undefined;

export interface FakeAppOptions {
  noise: NativeCryptoNoise;
  origin: string;
  keys?: NoiseKeyPair;
  /** Hex of the extension keys the app paired with. */
  pairedKeys?: Set<string>;
  vault?: VaultState;
  /** The optional features the app supports; it agrees to those the extension offers. Default: all. */
  capabilities?: string[];
  /** Answers a request first; `undefined` falls through to the defaults. */
  handler?: Handler;
  /** Fragment size of the app's messages. */
  maxFragmentData?: number;
  /** A payload for message 2, which the protocol forbids. */
  message2Payload?: Uint8Array;
  /** Never answers message 1. */
  silent?: boolean;
}

/** Independent of the extension's WebCrypto code: node:crypto's HKDF. */
export function nodePairingCode(input: {
  handshakeHash: Uint8Array;
  keys: [Uint8Array, Uint8Array];
  appNonce: Uint8Array;
  extensionNonce: Uint8Array;
}): string {
  const [first, second] = [...input.keys].sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)));
  const info = Buffer.concat([Buffer.from("keyguard-browser-sas/v1"), first!, second!, input.appNonce, input.extensionNonce]);
  const okm = Buffer.from(nodeCrypto.hkdfSync("sha256", Buffer.alloc(0), input.handshakeHash, info, 4));
  return String(okm.readUInt32BE(0) % 1_000_000).padStart(6, "0");
}

export class FakeApp {
  readonly keys: NoiseKeyPair;
  readonly requests: JsonObject[] = [];
  readonly pairedKeys: Set<string>;
  vault: VaultState;
  extensionKey: Uint8Array | null = null;
  handshakeHash: Uint8Array | null = null;
  paired = false;
  pairingCode: string | null = null;
  closed = false;
  /** The targets of `cancel` requests, in order; `cancel` itself isn't in [requests]. */
  readonly cancels: number[] = [];
  /** The IDs of requests the app still serves. */
  private readonly inFlight = new Set<number>();

  private stage: "suite" | "message1" | "message3" | "transport" = "suite";
  private handshake: NoiseHandshake | null = null;
  private transport: NoiseTransport | null = null;
  private readonly reassembler = new Reassembler();
  private pairing: { commit: Uint8Array; appNonce: Uint8Array } | null = null;

  constructor(
    private readonly options: FakeAppOptions,
    private readonly send: (frame: Uint8Array) => void,
  ) {
    this.keys = options.keys ?? options.noise.generateKeyPair();
    this.pairedKeys = options.pairedKeys ?? new Set();
    this.vault = options.vault ?? "locked";
  }

  receive(frame: Uint8Array): void {
    if (this.closed) return;
    switch (this.stage) {
      case "suite":
        if (frame.length !== 1 || frame[0] !== SUITE_ID) throw new Error("Expected the suite ID");
        this.handshake = this.options.noise.xx({
          initiator: false,
          localPrivateKey: this.keys.privateKey,
          prologue: concatBytes(utf8(PROLOGUE_PREFIX), utf8(this.options.origin)),
        });
        this.stage = "message1";
        return;
      case "message1": {
        const handshake = this.handshake!;
        handshake.readMessage(frame);
        if (this.options.silent) return;
        this.send(handshake.writeMessage(this.options.message2Payload));
        this.stage = "message3";
        return;
      }
      case "message3": {
        const handshake = this.handshake!;
        handshake.readMessage(frame);
        this.extensionKey = handshake.remoteStaticKey();
        this.handshakeHash = handshake.handshakeHash();
        this.paired = this.pairedKeys.has(toHex(this.extensionKey!));
        this.transport = handshake.intoTransport();
        this.stage = "transport";
        return;
      }
      case "transport": {
        const message = this.reassembler.push(this.transport!.readMessage(frame));
        if (message !== null) void this.answer(message);
      }
    }
  }

  /** Sends an event to the extension. */
  event(event: JsonObject): void {
    this.write(event);
  }

  /** Answers request [id] now, ahead of its handler, which then answers nothing. */
  respond(id: number, reply: JsonObject): void {
    if (this.inFlight.delete(id)) this.write({ id, ok: true, ...reply });
  }

  write(message: JsonObject): void {
    for (const fragment of fragmentMessage(message, this.options.maxFragmentData)) {
      this.send(this.transport!.writeMessage(fragment));
    }
  }

  close(): void {
    this.closed = true;
    this.transport?.close();
    this.handshake?.close();
  }

  private async answer(request: JsonObject) {
    if (request.type === "cancel") {
      // Answered at once (protocol §5); a target in flight answers `cancelled`.
      const target = request.target as number;
      this.cancels.push(target);
      this.write({ id: request.id, ok: true });
      if (this.inFlight.delete(target)) this.write({ id: target, ok: false, error: "cancelled" });
      return;
    }
    const id = request.id as number;
    this.requests.push(request);
    this.inFlight.add(id);
    const reply = (await this.options.handler?.(request, this)) ?? this.defaultReply(request);
    // Cancelled meanwhile: already answered.
    if (this.closed || !this.inFlight.delete(id)) return;
    if ("error" in reply && typeof reply.error === "string") {
      this.write({ id: request.id, ok: false, error: reply.error });
    } else {
      this.write({ id: request.id, ok: true, ...reply });
    }
  }

  private defaultReply(request: JsonObject): Reply {
    const type = request.type as string;
    if (type === "hello") {
      return {
        protocol: responseFields("hello").protocol,
        capabilities: ((request.capabilities as string[] | undefined) ?? []).filter((it) =>
          (this.options.capabilities ?? ["passkeys", "save"]).includes(it),
        ),
        paired: this.paired,
        vault: this.vault,
      };
    }
    if (type === "pair_commit" || type === "pair_reveal") {
      if (this.paired) return { error: "invalid_request" };
      // Pairing needs an unlocked vault (protocol §8).
      if (this.vault === "locked") return { error: "vault_locked" };
      return type === "pair_commit" ? this.pairCommit(request) : this.pairReveal(request);
    }
    if (!this.paired) return { error: "unpaired" };
    if (type === "unlock") {
      this.vault = "unlocked";
      return { vault: this.vault };
    }
    // The fixture's response, whatever the request names.
    return Object.hasOwn(exchanges, type) ? responseFields(type) : { error: "not_supported" };
  }

  private pairCommit(request: JsonObject): Reply {
    const commit = fromBase64(request.commit as string);
    if (commit?.length !== 32) return { error: "invalid_request" };
    const appNonce = new Uint8Array(nodeCrypto.randomBytes(32));
    this.pairing = { commit, appNonce };
    return { nonce: toBase64(appNonce) };
  }

  private pairReveal(request: JsonObject): Reply {
    const pairing = this.pairing;
    const extensionNonce = fromBase64(request.nonce as string);
    if (pairing === null || extensionNonce === null) return { error: "invalid_request" };
    this.pairing = null;
    const expected = nodeCrypto
      .createHash("sha256")
      .update(Buffer.concat([Buffer.from("keyguard-browser-commit/v1\n"), extensionNonce]))
      .digest();
    if (!nodeCrypto.timingSafeEqual(expected, commitBuffer(pairing.commit))) return { error: "security" };
    this.pairingCode = nodePairingCode({
      handshakeHash: this.handshakeHash!,
      keys: [this.keys.publicKey, this.extensionKey!],
      appNonce: pairing.appNonce,
      extensionNonce,
    });
    this.paired = true;
    this.pairedKeys.add(toHex(this.extensionKey!));
    return { vault: this.vault };
  }
}

function commitBuffer(commit: Uint8Array): Buffer {
  return Buffer.from(commit);
}

/**
 * For fakes of the app session: [value], or `cancelled` once [signal] aborts,
 * as ProtocolSession.request fails.
 */
export function abortable<T>(value: T | Promise<T>, signal: AbortSignal | undefined): Promise<T> {
  if (signal === undefined) return Promise.resolve(value);
  return new Promise((resolve, reject) => {
    const abort = () => reject(new ProtocolError("cancelled"));
    if (signal.aborted) abort();
    signal.addEventListener("abort", abort, { once: true });
    Promise.resolve(value).then(resolve, reject);
  });
}

type Listener<T> = (value: T) => void;

/** A native messaging port; the test plays the host. */
export class FakeNativePort implements NativePort {
  readonly posted: unknown[] = [];
  disconnected = false;
  error: { message?: string } | null = null;
  onPost: ((message: unknown) => void) | null = null;

  private readonly messageListeners: Listener<unknown>[] = [];
  private readonly disconnectListeners: Listener<NativePort>[] = [];

  readonly onMessage = { addListener: (listener: Listener<unknown>) => void this.messageListeners.push(listener) };
  readonly onDisconnect = {
    addListener: (listener: Listener<NativePort>) => void this.disconnectListeners.push(listener),
  };

  postMessage(message: unknown): void {
    if (this.disconnected) throw new Error("Attempting to use a disconnected port object");
    this.posted.push(message);
    this.onPost?.(message);
  }

  disconnect(): void {
    this.disconnected = true;
  }

  /** A message from the host. */
  emit(message: unknown): void {
    for (const listener of this.messageListeners) listener(message);
  }

  /** The browser closed the port, as when the host exits. */
  close(error?: string): void {
    this.disconnected = true;
    this.error = error === undefined ? null : { message: error };
    for (const listener of this.disconnectListeners) listener(this);
  }
}

/**
 * The host and the app behind a port: noise frames go to the current app,
 * whose frames come back asynchronously, like real messaging.
 */
export class FakeHost {
  app: FakeApp | null = null;
  readonly sent: Uint8Array[] = [];

  constructor(
    readonly port: FakeNativePort,
    private readonly appOptions: () => FakeAppOptions,
  ) {
    port.onPost = (message) => {
      const { type, data } = message as { type: string; data: string };
      if (type !== "noise") return;
      const frame = fromBase64(data)!;
      this.sent.push(frame);
      this.app?.receive(frame);
    };
  }

  /** Connects to a new app session and reports `ready`. */
  ready(): FakeApp {
    this.app?.close();
    this.sent.length = 0;
    const app = new FakeApp(this.appOptions(), (frame) => {
      queueMicrotask(() => {
        if (!this.port.disconnected && this.app === app) this.port.emit({ type: "noise", data: toBase64(frame) });
      });
    });
    this.app = app;
    this.port.emit({ type: "status", status: "ready" });
    return app;
  }

  status(status: string): void {
    this.app?.close();
    this.app = null;
    this.port.emit({ type: "status", status });
  }
}
