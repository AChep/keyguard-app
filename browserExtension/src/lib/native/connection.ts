// The native messaging port to keyguard-browser-host, protocol/README.md §1.
// One port at a time for the background's lifetime, reconnected with backoff
// when it drops.
import { MAX_MESSAGE_SIZE } from "../crypto/noise";
import { fromBase64, toBase64 } from "../util/bytes";
import { isObject } from "../util/json";

export const NATIVE_HOST_NAME = "com.artemchep.keyguard";

export type HostStatus =
  | "ready"
  | "app_unavailable"
  | "update_required"
  | "integration_disabled"
  | "unverified_host"
  | "too_many_sessions";

const HOST_STATUSES: ReadonlySet<string> = new Set<HostStatus>([
  "ready",
  "app_unavailable",
  "update_required",
  "integration_disabled",
  "unverified_host",
  "too_many_sessions",
]);

/** The part of `runtime.Port` the connection uses. */
export interface NativePort {
  postMessage(message: unknown): void;
  disconnect(): void;
  onMessage: { addListener(callback: (message: unknown) => void): void };
  onDisconnect: { addListener(callback: (port: NativePort) => void): void };
  /** Firefox reports why a port closed here. */
  error?: { message?: string } | null;
}

export interface Disconnect {
  /** The browser has no connector manifest for the host, or it isn't allowed. */
  connectorMissing: boolean;
}

export interface ConnectionListener {
  /** A host status; `null` for an unknown one, which means not connected. */
  status(status: HostStatus | null): void;
  /** A Noise frame of the current session. */
  frame(frame: Uint8Array): void;
  /** The port closed. The connection reconnects by itself unless stopped. */
  disconnected(reason: Disconnect): void;
}

export interface NativeConnectionOptions {
  connectNative(application: string): NativePort;
  /** Chromium's `runtime.lastError` message after a port closed. */
  lastError(): string | undefined;
  listener: ConnectionListener;
  initialBackoffMs?: number;
  maxBackoffMs?: number;
}

// Chromium: "Specified native messaging host not found." or "Access to the
// specified native messaging host is forbidden."; Firefox: "No such native
// application com.artemchep.keyguard".
const CONNECTOR_MISSING = /not found|forbidden|no such native application/i;

export class NativeConnection {
  private port: NativePort | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private attempts = 0;
  private stopped = true;

  constructor(private readonly options: NativeConnectionOptions) {}

  /** Opens the port now, unless it's open. Later drops reconnect with backoff. */
  connect(): void {
    this.stopped = false;
    clearTimeout(this.timer);
    this.timer = undefined;
    if (this.port !== null) return;

    let port: NativePort;
    try {
      port = this.options.connectNative(NATIVE_HOST_NAME);
    } catch {
      this.closed({ connectorMissing: true });
      return;
    }
    this.port = port;
    port.onMessage.addListener((message) => {
      if (this.port === port) this.read(message);
    });
    port.onDisconnect.addListener((closed) => {
      // Reading lastError also keeps Chromium from logging it as unchecked.
      const message = closed.error?.message ?? this.options.lastError();
      if (this.port !== port) return;
      this.port = null;
      this.closed({ connectorMissing: message !== undefined && CONNECTOR_MISSING.test(message) });
    });
  }

  /**
   * Opens the port now if it dropped and waits for its backoff. Does nothing
   * while it's open, or once stopped.
   */
  reconnectNow(): void {
    if (this.stopped || this.port !== null) return;
    this.connect();
  }

  /** Sends one Noise frame; dropped while the port is closed, as the host would. */
  send(frame: Uint8Array): void {
    this.port?.postMessage({ type: "noise", data: toBase64(frame) });
  }

  /**
   * Ends the session by closing the port. With `reconnect`, a new port opens
   * after the backoff; without it, only [connect] opens one.
   */
  restart(reconnect: boolean): void {
    const port = this.port;
    if (port === null) {
      if (!reconnect) this.stop();
      return;
    }
    this.port = null;
    port.disconnect();
    if (!reconnect) this.stopped = true;
    this.closed({ connectorMissing: false });
  }

  /** Closes the port and stops reconnecting. */
  stop(): void {
    this.stopped = true;
    clearTimeout(this.timer);
    this.timer = undefined;
    const port = this.port;
    this.port = null;
    port?.disconnect();
  }

  private read(message: unknown) {
    if (!isObject(message)) return;
    const { type } = message;
    if (type === "status") {
      const { status } = message;
      const hostStatus = typeof status === "string" && HOST_STATUSES.has(status) ? (status as HostStatus) : null;
      if (hostStatus !== "update_required" && hostStatus !== "unverified_host") {
        this.attempts = 0;
      }
      this.options.listener.status(hostStatus);
    } else if (type === "noise") {
      const { data } = message;
      const frame = typeof data === "string" ? fromBase64(data) : null;
      if (frame === null || frame.length < 1 || frame.length > MAX_MESSAGE_SIZE) {
        this.restart(true);
        return;
      }
      this.options.listener.frame(frame);
    }
    // Other types are ignored.
  }

  private closed(reason: Disconnect) {
    this.options.listener.disconnected(reason);
    if (this.stopped) return;
    const initial = this.options.initialBackoffMs ?? 1_000;
    const max = this.options.maxBackoffMs ?? 60_000;
    const delay = Math.min(max, initial * 2 ** this.attempts);
    this.attempts = Math.min(this.attempts + 1, 16);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      if (!this.stopped) this.connect();
    }, delay);
  }
}
