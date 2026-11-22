// One protocol session, protocol/README.md §2–§4: the suite ID, the XX
// handshake as the initiator with the pinned-key check, then requests,
// responses and events over Noise transport messages.
import type { NativeCryptoNoise, NoiseHandshake, NoiseTransport } from "../crypto/noise";
import { bytesEqual, concatBytes, utf8 } from "../util/bytes";
import { fragmentMessage, MAX_DEPTH, MessageTooDeep, nestsAtMost, ProtocolViolation, Reassembler, type JsonObject } from "./framing";
import {
  parseEvent,
  parseResponse,
  type CancelRequest,
  type ProtocolEvent,
  type RequestOf,
  type RequestType,
  type ResponseOf,
} from "./messages";

/** The only suite: Noise_XX_25519_ChaChaPoly_SHA256 with this prologue. */
export const SUITE_ID = 0x03;
export const PROLOGUE_PREFIX = "keyguard-browser/v1\n";
/** The app ends a handshake that takes longer; so does the extension. */
export const HANDSHAKE_TIMEOUT_MS = 20_000;
/** The most requests in flight per session; `cancel` doesn't count. */
export const MAX_IN_FLIGHT = 8;
const MAX_ID = Number.MAX_SAFE_INTEGER;

export type SessionEnd =
  /** This side closed the session, such as when the port disconnected. */
  | { reason: "closed" }
  /** The app's static key isn't the pinned one; message 3 wasn't sent. */
  | { reason: "pinned_key_mismatch" }
  /** The handshake didn't finish in time. */
  | { reason: "handshake_timeout" }
  /** The app broke the protocol, or a message failed to decrypt. */
  | { reason: "violation"; detail: string };

/** The session ended before the request got its response. */
export class SessionClosed extends Error {
  constructor(readonly end: SessionEnd) {
    super(`The session ended: ${end.reason}`);
    this.name = "SessionClosed";
  }
}

/**
 * The app answered a request with `{ok: false, error}`. A request whose
 * signal aborts fails with `cancelled` at once, as the app answers it.
 */
export class ProtocolError extends Error {
  constructor(readonly code: string) {
    super(`The app answered: ${code}`);
    this.name = "ProtocolError";
  }
}

/** Whether a request failed because its caller stopped waiting: an abort, not a failure to show. */
export function isCancelled(error: unknown): boolean {
  return error instanceof ProtocolError && error.code === "cancelled";
}

export interface Handshake {
  /** The app's static public key. */
  remoteStaticKey: Uint8Array;
  /** The handshake hash `h`, for the pairing code. */
  handshakeHash: Uint8Array;
}

export interface SessionOptions {
  noise: NativeCryptoNoise;
  /** The extension's static private key. */
  staticPrivateKey: Uint8Array;
  /** `chrome-extension://<id>/`, or the Firefox add-on ID. */
  origin: string;
  /** The app key of the pairing, or `null` when the extension isn't paired. */
  pinnedAppKey: Uint8Array | null;
  /** Sends one Noise frame to the app. */
  send(frame: Uint8Array): void;
  onEvent?(event: ProtocolEvent): void;
  /** Called once, when the session ends for any reason. */
  onEnd?(end: SessionEnd): void;
  handshakeTimeoutMs?: number;
}

interface Pending {
  /** Whether it holds one of the [MAX_IN_FLIGHT] slots: `cancel` doesn't. */
  slot: boolean;
  resolve(message: JsonObject): void;
  reject(error: unknown): void;
}

type State =
  | { kind: "idle" }
  | { kind: "handshake"; handshake: NoiseHandshake }
  | { kind: "transport"; transport: NoiseTransport }
  | { kind: "ended"; end: SessionEnd };

export class ProtocolSession {
  /** Resolves once the handshake finished; rejects with [SessionClosed]. */
  readonly established: Promise<Handshake>;

  private state: State = { kind: "idle" };
  private resolveEstablished!: (handshake: Handshake) => void;
  private rejectEstablished!: (error: unknown) => void;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private readonly reassembler = new Reassembler();
  private readonly pending = new Map<number, Pending>();
  private readonly waiting: (() => void)[] = [];
  private nextId = 1;
  /** Requests from taking a slot to their response. */
  private inFlight = 0;

  constructor(private readonly options: SessionOptions) {
    this.established = new Promise((resolve, reject) => {
      this.resolveEstablished = resolve;
      this.rejectEstablished = reject;
    });
    // Callers that never await it shouldn't see an unhandled rejection.
    this.established.catch(() => undefined);
  }

  get ended(): SessionEnd | null {
    return this.state.kind === "ended" ? this.state.end : null;
  }

  /** Whether a request is in flight, or waits for a slot. */
  get busy(): boolean {
    return this.inFlight > 0 || this.waiting.length > 0;
  }

  /** Sends the suite ID and the first handshake message. A failure ends the session. */
  start(): void {
    if (this.state.kind !== "idle") throw new Error("The session already started");
    try {
      const handshake = this.options.noise.xx({
        initiator: true,
        localPrivateKey: this.options.staticPrivateKey,
        prologue: concatBytes(utf8(PROLOGUE_PREFIX), utf8(this.options.origin)),
      });
      this.state = { kind: "handshake", handshake };
      this.timer = setTimeout(
        () => this.end({ reason: "handshake_timeout" }),
        this.options.handshakeTimeoutMs ?? HANDSHAKE_TIMEOUT_MS,
      );
      const message1 = handshake.writeMessage();
      this.options.send(Uint8Array.of(SUITE_ID));
      this.options.send(message1);
    } catch (error) {
      this.end({ reason: "violation", detail: describe(error) });
    }
  }

  /** Takes the next Noise frame from the app. */
  receive(frame: Uint8Array): void {
    switch (this.state.kind) {
      case "idle":
        this.end({ reason: "violation", detail: "A frame arrived before the handshake" });
        return;
      case "handshake":
        this.readHandshake(this.state.handshake, frame);
        return;
      case "transport":
        this.readTransport(this.state.transport, frame);
        return;
      case "ended":
        return;
    }
  }

  /**
   * Sends a request and resolves with its checked response. Rejects with
   * [ProtocolError] for an error response, or [SessionClosed]. At most
   * [MAX_IN_FLIGHT] requests are in flight; later ones wait for a slot.
   *
   * Fields that nest deeper than the app takes, as a page's passkey options
   * can, fail this request alone with [MessageTooDeep]: it takes no slot and
   * writes nothing, where the app would end the session.
   *
   * When [signal] aborts, the request fails with `cancelled` at once. One in
   * flight gets a `cancel` (protocol §5) and keeps its slot until the app
   * answers it.
   */
  async request<T extends RequestType>(type: T, fields: RequestOf<T>, signal?: AbortSignal): Promise<ResponseOf<T>> {
    if (signal?.aborted) throw cancelled();
    // The fields become the message's own object.
    if (!nestsAtMost(fields, MAX_DEPTH)) throw new MessageTooDeep();
    await this.established;
    await this.acquireSlot(signal);
    let sent: { id: number; response: Promise<JsonObject> };
    try {
      // It aborted while the slot was on its way.
      if (signal?.aborted) throw cancelled();
      sent = this.send({ type, ...fields }, true);
    } catch (error) {
      this.releaseSlot();
      throw error;
    }
    const stop = () => this.cancel(sent.id);
    signal?.addEventListener("abort", stop, { once: true });
    try {
      return parseResponse(type, await sent.response);
    } finally {
      signal?.removeEventListener("abort", stop);
    }
  }

  /** Ends the session; pending and later requests fail with [SessionClosed]. */
  close(): void {
    this.end({ reason: "closed" });
  }

  /** Writes a request with the next ID; resolves with its response, ok or not. */
  private send(message: JsonObject, slot: boolean): { id: number; response: Promise<JsonObject> } {
    if (this.state.kind !== "transport") throw new SessionClosed(this.ended ?? { reason: "closed" });
    const transport = this.state.transport;
    const id = this.nextId;
    if (id > MAX_ID) {
      this.end({ reason: "violation", detail: "Request IDs ran out" });
      throw new SessionClosed(this.ended ?? { reason: "closed" });
    }
    const fragments = fragmentMessage({ id, ...message });
    this.nextId++;
    const response = new Promise<JsonObject>((resolve, reject) => {
      this.pending.set(id, { slot, resolve, reject });
    });
    try {
      for (const fragment of fragments) this.options.send(transport.writeMessage(fragment));
    } catch (error) {
      this.end({ reason: "violation", detail: describe(error) });
    }
    return { id, response };
  }

  /**
   * The caller of request [id] stopped waiting: the request fails now, and
   * the app stops serving it. Its late answer, `cancelled` or not, frees the
   * slot.
   */
  private cancel(id: number) {
    const pending = this.pending.get(id);
    if (pending === undefined) return;
    pending.reject(cancelled());
    const cancel: CancelRequest = { target: id };
    try {
      // The app answers at once, with no fields.
      this.send({ type: "cancel", ...cancel }, false).response.catch(() => undefined);
    } catch {
      // The session ended, which stops every request.
    }
  }

  private readHandshake(handshake: NoiseHandshake, frame: Uint8Array) {
    let established: Handshake;
    let transport: NoiseTransport;
    let message3: Uint8Array;
    try {
      const payload = handshake.readMessage(frame);
      if (payload.length > 0) throw new ProtocolViolation("A handshake message has a payload");
      const remoteStaticKey = handshake.remoteStaticKey();
      if (remoteStaticKey === null) throw new ProtocolViolation("Message 2 has no static key");
      // Check the pinned key before message 3, so another app never learns ours.
      const pinned = this.options.pinnedAppKey;
      if (pinned !== null && !bytesEqual(pinned, remoteStaticKey)) {
        this.end({ reason: "pinned_key_mismatch" });
        return;
      }
      message3 = handshake.writeMessage();
      if (!handshake.isFinished()) throw new ProtocolViolation("The handshake didn't finish");
      established = { remoteStaticKey, handshakeHash: handshake.handshakeHash() };
      transport = handshake.intoTransport();
    } catch (error) {
      this.end({ reason: "violation", detail: describe(error) });
      return;
    }
    clearTimeout(this.timer);
    // The transport state first: a failed send then ends the session and wipes it.
    this.state = { kind: "transport", transport };
    try {
      this.options.send(message3);
    } catch (error) {
      this.end({ reason: "violation", detail: describe(error) });
      return;
    }
    this.resolveEstablished(established);
  }

  private readTransport(transport: NoiseTransport, frame: Uint8Array) {
    let message: JsonObject | null;
    try {
      message = this.reassembler.push(transport.readMessage(frame));
    } catch (error) {
      this.end({ reason: "violation", detail: describe(error) });
      return;
    }
    if (message === null) return;
    if ("id" in message) {
      this.readResponse(message);
    } else if (typeof message.event === "string") {
      const event = parseEvent(message);
      if (event !== null) this.options.onEvent?.(event);
    } else {
      this.end({ reason: "violation", detail: "A message is neither a response nor an event" });
    }
  }

  private readResponse(message: JsonObject) {
    const id = message.id;
    const pending = typeof id === "number" ? this.pending.get(id) : undefined;
    if (pending === undefined || typeof message.ok !== "boolean") {
      this.end({ reason: "violation", detail: "A response has no valid ID" });
      return;
    }
    this.pending.delete(id as number);
    if (pending.slot) this.releaseSlot();
    if (message.ok) {
      pending.resolve(message);
    } else {
      pending.reject(new ProtocolError(typeof message.error === "string" ? message.error : ""));
    }
  }

  private acquireSlot(signal: AbortSignal | undefined): Promise<void> {
    if (signal?.aborted) return Promise.reject(cancelled());
    // `end()` freed no slots and woke the queue already: this one would wait forever.
    if (this.state.kind === "ended") return Promise.reject(new SessionClosed(this.state.end));
    if (this.inFlight < MAX_IN_FLIGHT) {
      this.inFlight++;
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      const wake = () => {
        signal?.removeEventListener("abort", stop);
        this.inFlight++;
        resolve();
      };
      // Not sent yet: it just leaves the queue.
      const stop = () => {
        const index = this.waiting.indexOf(wake);
        if (index >= 0) this.waiting.splice(index, 1);
        reject(cancelled());
      };
      signal?.addEventListener("abort", stop, { once: true });
      this.waiting.push(wake);
    });
  }

  private releaseSlot() {
    this.inFlight--;
    this.waiting.shift()?.();
  }

  private end(end: SessionEnd) {
    if (this.state.kind === "ended") return;
    const previous = this.state;
    this.state = { kind: "ended", end };
    clearTimeout(this.timer);
    try {
      if (previous.kind === "handshake") previous.handshake.close();
      if (previous.kind === "transport") previous.transport.close();
    } catch {
      // The Wasm wipes a failed session itself.
    }
    const error = new SessionClosed(end);
    this.rejectEstablished(error);
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear();
    // Waiting requests wake up, see the ended state and fail.
    for (const wake of this.waiting.splice(0)) wake();
    this.options.onEnd?.(end);
  }
}

function cancelled(): ProtocolError {
  return new ProtocolError("cancelled");
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
