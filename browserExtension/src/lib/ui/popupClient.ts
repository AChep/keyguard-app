// The popup's side of the background API, see ../background/api.ts.
import { browser } from "wxt/browser";
import {
  POPUP_PORT,
  type BackgroundMessage,
  type CallArgs,
  type CallError,
  type CallMethod,
  type CallResult,
  type ExtensionStatus,
  type PopupMessage,
} from "../background/api";

/** A call the background answered with an error. */
export class PopupCallError extends Error {
  constructor(readonly code: CallError) {
    super(code);
    this.name = "PopupCallError";
  }
}

/** The parts of `runtime.Port` the popup uses. */
export interface PopupPort {
  postMessage(message: unknown): void;
  onMessage: { addListener(callback: (message: unknown) => void): void };
  onDisconnect: { addListener(callback: () => void): void };
}

/** Ports in a row that close before the background says anything, after which the popup stops reconnecting. */
export const MAX_RECONNECTS = 3;

/**
 * Chromium stops the background's service worker when it's idle, which
 * closes the port: the client connects again at once, which starts the
 * worker, and the worker sends the status again. Calls in flight fail with
 * `disconnected`, as does a call on a port that can't post.
 */
export class PopupClient {
  private port: PopupPort | null = null;
  private readonly pending = new Map<number, { resolve(value: unknown): void; reject(error: unknown): void }>();
  private nextId = 1;
  /** Ports that closed since the background last said something. */
  private silentCloses = 0;

  constructor(
    private readonly onStatus: (status: ExtensionStatus) => void,
    private readonly connect: () => PopupPort = () => browser.runtime.connect({ name: POPUP_PORT }) as unknown as PopupPort,
  ) {
    this.open();
  }

  call<M extends CallMethod>(method: M, args: CallArgs<M>): Promise<CallResult<M>> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const port = this.port;
      if (port === null) {
        reject(new PopupCallError("disconnected"));
        return;
      }
      this.pending.set(id, { resolve: (value) => resolve(value as CallResult<M>), reject });
      const message: PopupMessage = { kind: "call", id, method, args };
      try {
        port.postMessage(message);
      } catch {
        // The port closed without telling yet.
        this.closed(port);
      }
    });
  }

  private open() {
    let port: PopupPort;
    try {
      port = this.connect();
    } catch {
      // The extension was updated or removed.
      this.port = null;
      return;
    }
    this.port = port;
    port.onMessage.addListener((raw: unknown) => {
      if (this.port !== port) return;
      this.silentCloses = 0;
      const message = raw as BackgroundMessage;
      if (message.kind === "status") {
        this.onStatus(message.status);
        return;
      }
      const pending = this.pending.get(message.id);
      if (pending === undefined) return;
      this.pending.delete(message.id);
      if (message.ok) pending.resolve(message.value);
      else pending.reject(new PopupCallError(message.error));
    });
    port.onDisconnect.addListener(() => this.closed(port));
  }

  /** [port] closed: its calls fail, and a new port takes over, unless the background keeps closing them. */
  private closed(port: PopupPort) {
    if (this.port !== port) return;
    this.port = null;
    for (const pending of this.pending.values()) pending.reject(new PopupCallError("disconnected"));
    this.pending.clear();
    if (++this.silentCloses <= MAX_RECONNECTS) this.open();
  }
}

export function callErrorCode(error: unknown): CallError {
  return error instanceof PopupCallError ? error.code : "unknown";
}
