// Owns the native connection and the protocol session of the background, and
// answers the popup's calls about the session; ./pages.ts answers those about a
// tab's page. Browser APIs come in through [ControllerDeps], so tests can run it
// against a fake host.
import type { NativeCryptoNoise, NoiseKeyPair } from "../crypto/noise";
import { MessageTooDeep, MessageTooLarge } from "../protocol/framing";
import {
  CAPABILITIES,
  checkVersion,
  InvalidResponse,
  privateWindow,
  PROTOCOL_VERSIONS,
  type Capability,
  type HelloResponse,
  type PagePrivacy,
  type ProtocolEvent,
  type RequestOf,
  type RequestType,
  type ResponseOf,
  type VaultState,
} from "../protocol/messages";
import { isCancelled, ProtocolError, ProtocolSession, SessionClosed, type Handshake, type SessionEnd } from "../protocol/session";
import { NativeConnection, type ConnectionListener, type Disconnect, type HostStatus, type NativePort } from "../native/connection";
import { NONCE_SIZE, pairingCode, pairingCommit } from "../pairing/code";
import { PairingStore, type Pairing, type StorageArea } from "../pairing/store";
import { fromBase64, randomBytes, toBase64 } from "../util/bytes";
import type { CallError, CallHandlers, ExtensionStatus, PageCallMethod } from "./api";
import { webUrl, type TabInfo } from "./frames";

/** `pair_commit` takes a name of 1 to 64 characters. */
const MAX_NAME_LENGTH = 64;

export interface ControllerDeps {
  connectNative(application: string): NativePort;
  lastError(): string | undefined;
  loadNoise(): Promise<NativeCryptoNoise>;
  /** Where the static key and the pairing live: content scripts mustn't reach it. */
  storage: StorageArea;
  /** The extension origin of the prologue. */
  origin: string;
  /** The browser's name: the `name` of `pair_commit`. */
  browserName: string;
  /** A tab, as `tabs.get` tells; its URL is `undefined` when the extension can't see it. */
  tab(tabId: number): Promise<TabInfo>;
  /** Writes the clipboard from the background, see ./clipboard.ts. */
  copyText(text: string): Promise<void>;
  /** Whether the popup may copy the password of [handle] on tab [tabId], see PageController.mayCopyPassword. */
  mayCopyPassword(tabId: number, handle: string): boolean;
  onStatus(status: ExtensionStatus): void;
  initialBackoffMs?: number;
}

/** A call failed; `code` is a [CallError]. */
export class CallFailure extends Error {
  constructor(readonly code: CallError) {
    super(code);
    this.name = "CallFailure";
  }
}

interface ActiveSession {
  /** Counts sessions: handles die with theirs. */
  id: number;
  protocol: ProtocolSession;
  noise: NativeCryptoNoise;
  keys: NoiseKeyPair;
  pairing: Pairing | null;
  handshake: Handshake | null;
  /** Whether the app knows this extension's key, after `hello` or pairing. */
  paired: boolean;
  /** The protocol version the app answered `hello` with; `null` before. */
  version: number | null;
  /** The offered capabilities the app agreed to in `hello`. */
  capabilities: ReadonlySet<string>;
}

export function callErrorOf(error: unknown): CallError {
  if (error instanceof CallFailure) return error.code;
  if (error instanceof ProtocolError) return error.code || "unknown";
  if (error instanceof SessionClosed) return "disconnected";
  if (error instanceof InvalidResponse) return "invalid_response";
  if (error instanceof MessageTooLarge) return "too_large";
  if (error instanceof MessageTooDeep) return "invalid_request";
  return "unknown";
}

function vaultStatus(vault: VaultState): ExtensionStatus {
  return vault === "unlocked" ? { kind: "unlocked" } : { kind: "locked", unlocking: false };
}

/** The `unlock` in flight, which callers share; see [BackgroundController.unlock]. */
interface SharedUnlock {
  promise: Promise<null>;
  abort: AbortController;
  /** Callers with a signal that still wait. */
  waiters: number;
  /** A caller without a signal waits until the app answers. */
  held: boolean;
}

/** [promise], or `cancelled` once [signal] aborts, after calling [stop]. */
function unlessAborted<T>(promise: Promise<T>, signal: AbortSignal, stop: () => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const abort = () => {
      stop();
      reject(new ProtocolError("cancelled"));
    };
    signal.addEventListener("abort", abort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}

export class BackgroundController implements ConnectionListener {
  private current: ExtensionStatus = { kind: "starting" };
  private readonly connection: NativeConnection;
  private readonly store: PairingStore;
  private session: ActiveSession | null = null;
  /** Bumped as each session starts or ends; a session's own is its `id`. */
  private generation = 0;
  /** The `unlock` in flight: the app answers a second one with `busy`. */
  private unlocking: SharedUnlock | null = null;
  /** A pinned-key mismatch stops reconnecting until the user acts. */
  private halted = false;
  private rotatedStaleKey = false;

  /** The popup's calls about the app session, see ./api.ts; PageController answers those about a tab's page. */
  readonly calls: Omit<CallHandlers, PageCallMethod> = {
    retry: () => this.retry(),
    pair: () => this.pair(),
    repair: () => this.repair(),
    unlock: () => this.unlock(),
    copy_password: async ({ tabId, handle, origin }) => {
      // Unlisted, or its collections hide the password.
      if (!this.deps.mayCopyPassword(tabId, handle)) throw new CallFailure("not_allowed");
      const { password } = await this.request("fill", { handle, ...(await this.page(tabId, origin)) });
      if (password === undefined) throw new CallFailure("invalid_response");
      await this.deps.copyText(password);
      return null;
    },
    copy_totp: async ({ tabId, handle, origin }) => {
      const { code } = await this.request("totp", { handle, ...(await this.page(tabId, origin)) });
      await this.deps.copyText(code);
      return null;
    },
    generate: async ({ tabId }) => {
      const tab = tabId === undefined ? null : await this.deps.tab(tabId);
      const url = webUrl(tab?.url);
      return this.request("generate", url === null ? {} : { url: url.href, ...privateWindow(tab?.incognito) });
    },
  };

  constructor(private readonly deps: ControllerDeps) {
    this.store = new PairingStore(deps.storage);
    this.connection = new NativeConnection({
      connectNative: deps.connectNative,
      lastError: deps.lastError,
      listener: this,
      ...(deps.initialBackoffMs !== undefined ? { initialBackoffMs: deps.initialBackoffMs } : {}),
    });
  }

  /** The status the popup shows. */
  get snapshot(): ExtensionStatus {
    return this.current;
  }

  start(): void {
    this.connection.connect();
  }

  /**
   * A browser window got focus, as when the user comes back from installing
   * the connector in the app: a dropped port reconnects now. Its backoff timer
   * may not outlive the service worker, which stops when idle without a port.
   */
  focused(): void {
    this.connection.reconnectNow();
  }

  /** The current session's number, or `null` without one. */
  get sessionId(): number | null {
    return this.session?.id ?? null;
  }

  /** Whether the app is answering a request of this session, as while it prompts the user. */
  get busy(): boolean {
    return this.session?.protocol.busy === true;
  }

  /** Whether the app agreed to a capability in this session's `hello`. */
  supports(capability: Capability): boolean {
    return this.session?.capabilities.has(capability) === true;
  }

  /** Creates the static key now, as on install, instead of at the first session. */
  async ensureStaticKey(): Promise<void> {
    await this.store.staticKey(await this.deps.loadNoise());
  }

  // region ConnectionListener

  status(host: HostStatus | null): void {
    this.endSession();
    if (host === "ready") {
      void this.openSession();
      return;
    }
    // An unknown status means not connected.
    this.setStatus({ kind: host ?? "app_unavailable" });
  }

  frame(frame: Uint8Array): void {
    this.session?.protocol.receive(frame);
  }

  disconnected(reason: Disconnect): void {
    this.endSession();
    if (this.halted) return;
    if (reason.connectorMissing) {
      this.setStatus({ kind: "connector_missing" });
    } else if (
      this.current.kind !== "protocol_error" &&
      this.current.kind !== "update_required" &&
      this.current.kind !== "unverified_host"
    ) {
      this.setStatus({ kind: "app_unavailable" });
    }
  }

  // endregion

  // region Popup calls

  private async retry(): Promise<null> {
    this.halted = false;
    this.endSession();
    this.connection.stop();
    // The user asked: if this session fails too, its reconnect doesn't wait long.
    this.connection.resetBackoff();
    this.setStatus({ kind: "starting" });
    this.connection.connect();
    return null;
  }

  private async pair(): Promise<null> {
    const active = this.session;
    const handshake = active?.handshake;
    if (active === null || handshake == null || active.paired || this.current.kind !== "not_paired") {
      throw new CallFailure("disconnected");
    }
    if (this.current.pairing.phase === "starting" || this.current.pairing.phase === "confirm") {
      throw new CallFailure("busy");
    }
    const appKey = handshake.remoteStaticKey;
    this.setStatus({ kind: "not_paired", pairing: { phase: "starting" } });
    try {
      // Commit to our nonce before learning the app's, see protocol §8.
      const extensionNonce = randomBytes(NONCE_SIZE);
      const commit = await pairingCommit(extensionNonce);
      const name = this.deps.browserName.slice(0, MAX_NAME_LENGTH) || "Browser";
      const { nonce } = await this.sessionRequest(active, "pair_commit", { commit: toBase64(commit), name });
      const appNonce = fromBase64(nonce);
      if (appNonce?.length !== NONCE_SIZE) throw new InvalidResponse("pair_commit");
      const { code } = await pairingCode({
        handshakeHash: handshake.handshakeHash,
        extensionPublicKey: active.keys.publicKey,
        appPublicKey: appKey,
        appNonce,
        extensionNonce,
      });
      const reveal = this.sessionRequest(active, "pair_reveal", { nonce: toBase64(extensionNonce) });
      if (this.session === active) this.setStatus({ kind: "not_paired", pairing: { phase: "confirm", code } });
      const { vault } = await reveal;
      const pairing: Pairing = { appKey };
      // As in [afterHello]: the session and its status change before the pin
      // is written, so events that come meanwhile apply on top.
      active.pairing = pairing;
      active.paired = true;
      if (this.session === active) this.setStatus(vaultStatus(vault));
      try {
        await this.store.setPairing(pairing);
      } catch (error) {
        // The app paired with our key, but the pin is lost, and the app
        // refuses another `pair_commit`. A new session starts over with a new
        // key, as for any key the app knows without a pin; see [afterHello].
        console.error("Keyguard: failed to store the pairing", error);
        this.rotatedStaleKey = false;
        if (this.session === active) this.failSession();
        throw error;
      }
    } catch (error) {
      if (this.session === active && this.current.kind === "not_paired") {
        this.setStatus({ kind: "not_paired", pairing: { phase: "failed", error: callErrorOf(error) } });
      }
      throw error;
    }
    return null;
  }

  private async repair(): Promise<null> {
    if (this.current.kind !== "pinned_key_mismatch") throw new CallFailure("invalid_request");
    // A new static key makes the next session unpaired for any app.
    await this.store.forgetPairing({ rotateKey: await this.deps.loadNoise() });
    return this.retry();
  }

  /**
   * Asks the app to unlock. Calls while one is in flight share it.
   *
   * A call with a [signal] stops waiting when it aborts, failing with
   * `cancelled`; once no caller waits, the request is cancelled and the app's
   * prompt closes. A call without one, such as the popup's, which the prompt
   * closes as it takes focus, waits until the app answers.
   */
  unlock(signal?: AbortSignal): Promise<null> {
    if (signal?.aborted) return Promise.reject(new ProtocolError("cancelled"));
    let shared = this.unlocking;
    if (shared === null) {
      const abort = new AbortController();
      const started: SharedUnlock = {
        promise: this.unlockOnce(abort.signal).finally(() => {
          if (this.unlocking === started) this.unlocking = null;
        }),
        abort,
        waiters: 0,
        held: false,
      };
      // Callers that all stopped waiting don't see its failure.
      started.promise.catch(() => undefined);
      this.unlocking = shared = started;
    }
    if (signal === undefined) {
      shared.held = true;
      return shared.promise;
    }
    const waiting = shared;
    waiting.waiters++;
    return unlessAborted(waiting.promise, signal, () => {
      if (--waiting.waiters > 0 || waiting.held) return;
      // A later call asks again instead of sharing the cancelled one.
      if (this.unlocking === waiting) this.unlocking = null;
      waiting.abort.abort();
    });
  }

  private async unlockOnce(signal: AbortSignal): Promise<null> {
    if (this.current.kind === "unlocked") return null;
    if (this.current.kind !== "locked") throw new CallFailure("disconnected");
    const active = this.session;
    this.setStatus({ kind: "locked", unlocking: true });
    try {
      const { vault } = await this.request("unlock", {}, signal);
      if (this.session === active) this.setStatus(vaultStatus(vault));
    } catch (error) {
      if (this.session === active && this.current.kind === "locked") {
        this.setStatus(
          isCancelled(error) ? { kind: "locked", unlocking: false } : { kind: "locked", unlocking: false, error: callErrorOf(error) },
        );
      }
      throw error;
    }
    return null;
  }

  /**
   * The page of a tab, as a request names it: its URL, and whether it's in a
   * private window. Fails with `origin` when it isn't of [origin], the page
   * the popup listed the item for: the tab went on since.
   */
  private async page(tabId: number, origin: unknown): Promise<{ url: string } & PagePrivacy> {
    const tab = await this.deps.tab(tabId);
    const url = webUrl(tab.url);
    if (url === null) throw new CallFailure("unsupported_page");
    if (url.origin !== origin) throw new CallFailure("origin");
    return { url: url.href, ...privateWindow(tab.incognito) };
  }

  /**
   * A request of a paired session. When [signal] aborts, the app gets a
   * `cancel` and the request fails with `cancelled`.
   */
  async request<T extends RequestType>(type: T, fields: RequestOf<T>, signal?: AbortSignal): Promise<ResponseOf<T>> {
    const active = this.session;
    if (active === null || !active.paired) throw new CallFailure("disconnected");
    return this.sessionRequest(active, type, fields, signal);
  }

  /** Applies pairing errors only to the session that received them. */
  private async sessionRequest<T extends RequestType>(
    active: ActiveSession,
    type: T,
    fields: RequestOf<T>,
    signal?: AbortSignal,
  ): Promise<ResponseOf<T>> {
    const pairing = active.pairing;
    try {
      return await active.protocol.request(type, fields, signal);
    } catch (error) {
      if (this.session === active && error instanceof ProtocolError) {
        if (error.code === "update_required") this.halt({ kind: "app_update_required" });
        if (error.code === "unpaired" && active.paired && active.pairing === pairing) await this.unpaired(active);
      }
      throw error;
    }
  }

  // endregion

  // region Session

  private async openSession() {
    const generation = ++this.generation;
    this.setStatus({ kind: "connecting" });
    let noise: NativeCryptoNoise;
    let keys: NoiseKeyPair;
    let pairing: Pairing | null;
    try {
      // Only the static key needs the crypto, to create it on first use.
      noise = await this.deps.loadNoise();
      ({ keys, pairing } = await this.store.load(noise));
    } catch (error) {
      console.error("Keyguard: failed to load the crypto or the static key", error);
      if (generation === this.generation) this.setStatus({ kind: "protocol_error" });
      return;
    }
    if (generation !== this.generation) return;

    const protocol: ProtocolSession = new ProtocolSession({
      noise,
      staticPrivateKey: keys.privateKey,
      origin: this.deps.origin,
      pinnedAppKey: pairing?.appKey ?? null,
      send: (frame) => this.connection.send(frame),
      onEvent: (event) => {
        this.onEvent(active, event).catch((error: unknown) => console.error("Keyguard: failed to apply an event", error));
      },
      onEnd: (end) => this.onSessionEnd(active, end),
    });
    const active: ActiveSession = {
      id: generation,
      protocol,
      noise,
      keys,
      pairing,
      handshake: null,
      paired: false,
      version: null,
      capabilities: new Set(),
    };
    this.session = active;
    protocol.start();
    try {
      active.handshake = await protocol.established;
      const hello = await this.sessionRequest(active, "hello", { protocol: PROTOCOL_VERSIONS.max, capabilities: CAPABILITIES });
      if (this.session === active) await this.afterHello(active, hello);
    } catch (error) {
      if (this.session !== active || error instanceof SessionClosed) return;
      // `hello` failed: an app whose lowest version is above our highest
      // answers `not_supported`, so the extension needs an update.
      if (error instanceof ProtocolError && error.code === "not_supported") {
        this.halt({ kind: "extension_update_required" });
      } else {
        console.warn("Keyguard: the session failed to start", error);
        this.failSession();
      }
    }
  }

  /**
   * Applies `hello` to [active], the current session. The session and its
   * status change before the store is written: events that come meanwhile
   * apply on top, and a session that ends meanwhile keeps its new status.
   */
  private async afterHello(active: ActiveSession, hello: HelloResponse) {
    const version = checkVersion(hello.protocol);
    switch (version.kind) {
      case "app_outdated":
        // As the host's `update_required`: reconnects with backoff, and at once on focus.
        this.endSession();
        this.setStatus({ kind: "update_required" });
        this.connection.restart(true);
        return;
      case "invalid":
        // Above the version we offered: the app broke the protocol.
        this.halt({ kind: "protocol_error" });
        return;
      case "ok":
        active.version = version.version;
    }
    // Not at the host's `ready`, which each new port gets: sessions that
    // fail right after it must back off.
    this.connection.resetBackoff();
    active.capabilities = new Set(hello.capabilities.filter((it) => (CAPABILITIES as readonly string[]).includes(it)));
    if (!hello.paired) {
      // The app removed the pairing: forget it and offer to pair again.
      const forgetting = active.pairing === null ? null : this.store.forgetPairing();
      active.pairing = null;
      this.setStatus({ kind: "not_paired", pairing: { phase: "idle" } });
      await forgetting;
      return;
    }
    if (active.pairing === null) {
      // The app knows our key, but we pinned no app key: start over with a
      // new key, so the user can pair and confirm the app's key.
      if (!this.rotatedStaleKey) {
        this.rotatedStaleKey = true;
        await this.store.forgetPairing({ rotateKey: active.noise });
        // A newer session loads the new key.
        if (this.session !== active) return;
        this.endSession();
        this.connection.restart(true);
        return;
      }
      this.setStatus({ kind: "not_paired", pairing: { phase: "idle" } });
      return;
    }
    active.paired = true;
    this.setStatus(vaultStatus(hello.vault));
  }

  private async onEvent(active: ActiveSession, event: ProtocolEvent) {
    if (this.session !== active) return;
    switch (event.event) {
      case "vault":
        if (active.paired && (this.current.kind === "locked" || this.current.kind === "unlocked")) {
          this.setStatus(vaultStatus(event.state));
        }
        return;
      case "revoked":
        await this.unpaired(active);
        return;
      case "disabled":
        this.setStatus({ kind: "integration_disabled" });
        return;
    }
  }

  /**
   * The app no longer knows the pairing: forget it, keeping the key, and offer
   * to pair again. The status changes first, as in [afterHello].
   */
  private async unpaired(active: ActiveSession) {
    active.paired = false;
    active.pairing = null;
    const forgetting = this.store.forgetPairing();
    if (this.session === active) this.setStatus({ kind: "not_paired", pairing: { phase: "idle" } });
    await forgetting;
  }

  private onSessionEnd(active: ActiveSession, end: SessionEnd) {
    if (this.session !== active) return;
    this.session = null;
    switch (end.reason) {
      case "pinned_key_mismatch":
        this.halt({ kind: "pinned_key_mismatch" });
        return;
      case "violation":
      case "handshake_timeout":
        console.warn("Keyguard: the session ended", end);
        this.failSession();
        return;
      case "closed":
        return;
    }
  }

  /** Ends the session and reconnects with backoff. */
  private failSession() {
    this.endSession();
    this.setStatus({ kind: "protocol_error" });
    this.connection.restart(true);
  }

  /** Ends the session and waits for the user. */
  private halt(status: ExtensionStatus) {
    this.halted = true;
    this.endSession();
    this.setStatus(status);
    this.connection.restart(false);
  }

  private endSession() {
    this.generation++;
    const active = this.session;
    this.session = null;
    active?.protocol.close();
  }

  // endregion

  private setStatus(status: ExtensionStatus) {
    this.current = status;
    this.deps.onStatus(status);
  }
}
