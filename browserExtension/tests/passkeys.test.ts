// The background's side of passkeys: the origin from the sender, when the
// browser handles a request instead, how app errors reach the page, aborts,
// and conditional requests answered from the inline menu.
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import type { ExtensionStatus } from "../src/lib/background/api";
import { BackgroundController } from "../src/lib/background/controller";
import type { FrameRef, MessageSenderLike } from "../src/lib/background/frames";
import type { AppAccess, PagePort } from "../src/lib/background/pages";
import { PasskeyController, rpIdOf } from "../src/lib/background/passkeys";
import type { NativeCryptoNoise } from "../src/lib/crypto/noise";
import { loadNoise } from "../src/lib/crypto/wasm";
import { OptionsStore, type ExtensionOptions, type OptionsArea } from "../src/lib/options/store";
import type { JsonObject } from "../src/lib/protocol/framing";
import { ProtocolError, SessionClosed } from "../src/lib/protocol/session";
import { WEBAUTHN_PORT, type WebAuthnResult } from "../src/lib/webauthn/bridge";
import { abortable, FakeHost, FakeNativePort, type Handler } from "./support/fakeApp";
import { authenticationJSON, registrationJSON } from "./support/webauthn";

const ID = "abcdefghijklmnopabcdefghijklmnop";
const PAGE = "https://login.example.com/sign-in";
const ORIGIN = "https://login.example.com";
const CHALLENGE = "Y2hhbGxlbmdl";

beforeEach(() => {
  fakeBrowser.reset();
});

async function settle() {
  for (let i = 0; i < 10; i++) await new Promise((resolve) => setTimeout(resolve, 0));
}

/** `storage.local` with a slow read: a held read returns what was stored when it started. */
class SlowStorage implements OptionsArea {
  private next: Promise<void> | null = null;

  async get(keys: string | string[]): Promise<Record<string, unknown>> {
    const held = this.next;
    this.next = null;
    const stored = await fakeBrowser.storage.local.get(keys);
    await held;
    return stored;
  }

  set(items: Record<string, unknown>): Promise<void> {
    return fakeBrowser.storage.local.set(items);
  }

  /** Holds the next read until the returned function runs. */
  holdNextRead(): () => void {
    let release!: () => void;
    this.next = new Promise((resolve) => (release = () => resolve()));
    return release;
  }
}

function contentSender(fields: Partial<MessageSenderLike> = {}): MessageSenderLike {
  return { id: ID, tab: { id: 7, url: PAGE }, frameId: 0, documentId: "doc-0", url: PAGE, origin: ORIGIN, ...fields };
}

class FakePort implements PagePort {
  readonly name = WEBAUTHN_PORT;
  readonly posted: WebAuthnResult[] = [];
  disconnected = false;
  private readonly messageListeners: ((message: unknown) => void)[] = [];
  private readonly disconnectListeners: (() => void)[] = [];
  readonly onMessage = { addListener: (listener: (message: unknown) => void) => void this.messageListeners.push(listener) };
  readonly onDisconnect = { addListener: (listener: () => void) => void this.disconnectListeners.push(listener) };

  constructor(readonly sender: MessageSenderLike) {}

  postMessage(message: unknown): void {
    if (this.disconnected) throw new Error("disconnected");
    this.posted.push(message as WebAuthnResult);
  }

  disconnect(): void {
    this.disconnected = true;
  }

  emit(message: unknown): void {
    for (const listener of this.messageListeners) listener(message);
  }

  /** The content script's side went away: the page aborted or navigated. */
  close(): void {
    this.disconnected = true;
    for (const listener of this.disconnectListeners) listener();
  }
}

const getRequest = (fields: Record<string, unknown> = {}) => ({
  kind: "get",
  options: { challenge: CHALLENGE, rpId: "example.com" },
  mediation: "optional",
  same_origin_with_ancestors: true,
  ...fields,
});
const createRequest = (fields: Record<string, unknown> = {}) => ({
  kind: "create",
  options: { challenge: CHALLENGE, rp: { name: "Example" }, user: { id: "AA", name: "ann", displayName: "Ann" }, pubKeyCredParams: [] },
  same_origin_with_ancestors: true,
  ...fields,
});

function setup(options: { status?: ExtensionStatus; capabilities?: string[] } = {}) {
  const requests: JsonObject[] = [];
  const changes: FrameRef[] = [];
  const state = {
    status: options.status ?? ({ kind: "unlocked" } as ExtensionStatus),
    capabilities: new Set(options.capabilities ?? ["passkeys", "save"]),
    unlocks: 0,
    /** What `unlock` does: unlocks, or throws. */
    unlock: (): unknown => ({ kind: "unlocked" }),
    /** Answers of the app; `undefined` answers with a credential for the request. */
    app: (_request: JsonObject): unknown => undefined,
    /** The signal of each request: an abort sends `cancel`. */
    signals: [] as (AbortSignal | undefined)[],
  };
  const app: AppAccess = {
    get snapshot() {
      return state.status;
    },
    sessionId: 1,
    supports: (capability) => state.capabilities.has(capability),
    unlock: async () => {
      state.unlocks++;
      const result = state.unlock();
      if (result instanceof Error) throw result;
      state.status = result as ExtensionStatus;
      return null;
    },
    request: (async (type: string, fields: JsonObject, signal?: AbortSignal) => {
      const request = { type, ...fields };
      requests.push(request);
      state.signals.push(signal);
      const custom = await abortable(state.app(request), signal);
      if (custom instanceof Error) throw custom;
      if (custom !== undefined) return custom;
      const challenge = (fields.options as { challenge: string }).challenge;
      const origin = fields.origin as string;
      return { credential: type === "passkey_get" ? authenticationJSON(origin, challenge) : registrationJSON(origin, challenge) };
    }) as AppAccess["request"],
  };
  const storage = new SlowStorage();
  const options_ = new OptionsStore(storage);
  const passkeys = new PasskeyController({
    extensionId: ID,
    app,
    options: options_,
    conditionalChanged: (frame) => changes.push(frame),
  });

  /** The user changes the options on the options page; the background follows as on `storage.onChanged`. */
  async function change(patch: Partial<ExtensionOptions>) {
    const next = await new OptionsStore(fakeBrowser.storage.local).setOptions(patch);
    options_.invalidate();
    passkeys.optionsChanged(next);
  }

  /** A port of the bridge with one request; resolves once the background answered or settled. */
  async function send(message: unknown, sender = contentSender()) {
    const port = new FakePort(sender);
    passkeys.connect(port);
    port.emit(message);
    await settle();
    return port;
  }

  return { passkeys, state, requests, changes, options: options_, storage, change, send };
}

describe("passkey requests", () => {
  it("takes the origin from the sender, never from the request", async () => {
    const { send, requests } = setup();
    const port = await send(getRequest({ origin: "https://bank.example", options: { challenge: CHALLENGE, origin: "x" } }));
    expect(requests).toEqual([
      {
        type: "passkey_get",
        origin: ORIGIN,
        options: { challenge: CHALLENGE, origin: "x" },
        mediation: "optional",
        same_origin_with_ancestors: true,
      },
    ]);
    expect(port.posted).toEqual([{ kind: "credential", credential: authenticationJSON(ORIGIN, CHALLENGE) }]);

    // Firefox reports no `origin`: the frame's URL gives it.
    const { send: sendFirefox, requests: firefox } = setup();
    await sendFirefox(createRequest(), contentSender({ origin: undefined, documentId: undefined, url: "https://www.example.com/a?b" }));
    expect(firefox[0]).toMatchObject({ type: "passkey_create", origin: "https://www.example.com", same_origin_with_ancestors: true });
  });

  it("says when the page is in a private window, also for a pick in the menu", async () => {
    const context = setup();
    const sender = contentSender({ tab: { id: 7, url: PAGE, incognito: true } });
    await context.send(getRequest(), sender);
    await context.send(createRequest(), sender);
    await context.send(getRequest({ mediation: "conditional" }), sender);
    await context.passkeys.pick({ tabId: 7, frameId: 0, documentId: "doc-0", url: PAGE, origin: ORIGIN }, "pk-1");
    expect(context.requests.map((it) => [it.type, it.mediation, it.private_window])).toEqual([
      ["passkey_get", "optional", true],
      ["passkey_create", undefined, true],
      ["passkey_get", "conditional", true],
    ]);
    await context.send(getRequest());
    expect(context.requests.at(-1)).not.toHaveProperty("private_window");
  });

  it("refuses malformed requests and senders that aren't content scripts", async () => {
    const { send, requests } = setup();
    for (const message of [
      { kind: "get", options: { challenge: CHALLENGE }, mediation: "sometimes", same_origin_with_ancestors: true },
      { kind: "create", options: "x", same_origin_with_ancestors: true },
      getRequest({ same_origin_with_ancestors: "yes" }),
      getRequest({ options: { challenge: "x".repeat(70_000) } }),
    ]) {
      expect((await send(message)).posted).toEqual([{ kind: "fallback" }]);
    }
    for (const sender of [contentSender({ id: "other" }), contentSender({ origin: "null" }), contentSender({ tab: undefined })]) {
      expect((await send(getRequest(), sender)).posted).toEqual([{ kind: "fallback" }]);
      expect((await send({ kind: "available" }, sender)).posted).toEqual([{ kind: "available", available: false }]);
    }
    expect(requests).toEqual([]);
  });

  it("lets the browser handle requests Keyguard doesn't serve", async () => {
    const cases: [string, (context: ReturnType<typeof setup>) => Promise<void> | void, Record<string, unknown>?][] = [
      ["the passkeys option is off", async (context) => void (await context.options.setOptions({ passkeys: false }))],
      ["not paired", (context) => void (context.state.status = { kind: "not_paired", pairing: { phase: "idle" } })],
      ["the app isn't running", (context) => void (context.state.status = { kind: "app_unavailable" })],
      ["the app lacks passkeys", (context) => void context.state.capabilities.delete("passkeys")],
      ["a cross-origin ancestor", () => undefined, { same_origin_with_ancestors: false }],
      // Silent mediation shows nothing, not even the unlock prompt.
      ["silent mediation", (context) => void (context.state.status = { kind: "locked", unlocking: false }), { mediation: "silent" }],
    ];
    for (const [name, prepare, fields] of cases) {
      fakeBrowser.reset();
      const context = setup();
      await prepare(context);
      const subframe = contentSender({ frameId: 4, documentId: "doc-4" });
      const port = await context.send(getRequest(fields), subframe);
      expect(port.posted, name).toEqual([{ kind: "fallback" }]);
      expect(context.requests, name).toEqual([]);
      expect(context.state.unlocks, name).toBe(0);
    }
    // A subframe that claims same-origin ancestors must share the tab's origin.
    fakeBrowser.reset();
    const context = setup();
    const subframeOf = (url?: string) => contentSender({ frameId: 4, tab: { id: 7, ...(url !== undefined ? { url } : {}) } });
    expect((await context.send(getRequest(), subframeOf("https://portal.example/"))).posted).toEqual([{ kind: "fallback" }]);
    // A tab whose URL the browser didn't report.
    expect((await context.send(getRequest(), subframeOf())).posted).toEqual([{ kind: "fallback" }]);
    expect((await context.send(getRequest(), subframeOf("https://login.example.com/home"))).posted[0]).toMatchObject({ kind: "credential" });
  });

  it("lets the browser create on a cross-platform authenticator: Keyguard reports platform", async () => {
    const context = setup();
    const selection = (authenticatorAttachment: string) =>
      createRequest({ options: { ...createRequest().options, authenticatorSelection: { authenticatorAttachment, residentKey: "required" } } });
    expect((await context.send(selection("cross-platform"))).posted).toEqual([{ kind: "fallback" }]);
    expect(context.requests).toEqual([]);
    expect((await context.send(selection("platform"))).posted[0]).toMatchObject({ kind: "credential" });
    expect((await context.send(createRequest())).posted[0]).toMatchObject({ kind: "credential" });
    expect(context.requests.map((it) => it.type)).toEqual(["passkey_create", "passkey_create"]);
  });

  it("lets the browser take modal requests of a tab that isn't the active one, as the browser tells", async () => {
    const context = setup();
    const background = contentSender({ tab: { id: 7, url: PAGE, active: false } });
    expect((await context.send(getRequest(), background)).posted).toEqual([{ kind: "fallback" }]);
    expect((await context.send(createRequest(), background)).posted).toEqual([{ kind: "fallback" }]);
    expect(context.requests).toEqual([]);
    // A conditional one waits for a pick in the inline menu.
    const conditional = await context.send(getRequest({ mediation: "conditional" }), background);
    expect(conditional.posted).toEqual([]);
    expect(context.passkeys.conditionalFor({ tabId: 7, frameId: 0, documentId: "doc-0", url: PAGE, origin: ORIGIN })).not.toBeNull();
    const active = contentSender({ tab: { id: 7, url: PAGE, active: true } });
    expect((await context.send(getRequest(), active)).posted[0]).toMatchObject({ kind: "credential" });
  });

  it("sends requests to the app while locked: the app's unlock prompt comes first", async () => {
    const context = setup({ status: { kind: "locked", unlocking: false } });
    // The user closes the unlock prompt, or lets it time out: no browser fallback.
    context.state.app = () => new ProtocolError("user_denied");
    expect((await context.send(getRequest())).posted).toEqual([{ kind: "error", name: "NotAllowedError" }]);
    context.state.app = () => new ProtocolError("approval_timeout");
    expect((await context.send(createRequest())).posted).toEqual([{ kind: "error", name: "NotAllowedError" }]);
    context.state.app = () => undefined;
    expect((await context.send(createRequest())).posted[0]).toMatchObject({ kind: "credential" });
    expect(context.requests.map((it) => it.type)).toEqual(["passkey_get", "passkey_create", "passkey_create"]);
    expect(context.state.unlocks).toBe(0);
  });

  it("maps the app's errors as protocol §5 lists", async () => {
    const cases: [unknown, WebAuthnResult][] = [
      [new ProtocolError("not_supported"), { kind: "fallback" }],
      [new ProtocolError("invalid_state"), { kind: "error", name: "InvalidStateError" }],
      [new ProtocolError("security"), { kind: "error", name: "SecurityError" }],
      [new ProtocolError("user_denied"), { kind: "error", name: "NotAllowedError" }],
      [new ProtocolError("approval_timeout"), { kind: "error", name: "NotAllowedError" }],
      [new ProtocolError("not_allowed"), { kind: "error", name: "NotAllowedError" }],
      [new ProtocolError("vault_locked"), { kind: "error", name: "NotAllowedError" }],
      // Only after the page aborted, whose port is gone; an abort all the same.
      [new ProtocolError("cancelled"), { kind: "error", name: "AbortError" }],
      [new ProtocolError("something_new"), { kind: "error", name: "NotAllowedError" }],
      // The session ended: Keyguard is gone, the browser takes over.
      [new SessionClosed({ reason: "closed" }), { kind: "fallback" }],
      // A credential for another origin is a bug that mustn't reach the page.
      [{ credential: authenticationJSON("https://evil.example", CHALLENGE) }, { kind: "error", name: "NotAllowedError" }],
    ];
    for (const [answer, expected] of cases) {
      const context = setup();
      context.state.app = () => answer;
      expect((await context.send(getRequest())).posted, JSON.stringify(expected)).toEqual([expected]);
    }
  });

  it("cancels the request when the page aborts, and drops a late answer", async () => {
    const context = setup();
    let answer: () => void = () => undefined;
    context.state.app = (request) =>
      new Promise((resolve) => (answer = () => resolve({ credential: registrationJSON(ORIGIN, (request.options as { challenge: string }).challenge) })));
    const port = new FakePort(contentSender());
    context.passkeys.connect(port);
    port.emit(createRequest());
    await settle();
    expect(context.state.signals[0]?.aborted).toBe(false);
    port.close();
    expect(context.state.signals[0]?.aborted).toBe(true);
    answer();
    await settle();
    expect(port.posted).toEqual([]);
  });

  it("says whether Keyguard answers passkey requests", async () => {
    const context = setup({ status: { kind: "locked", unlocking: false } });
    expect((await context.send({ kind: "available" })).posted).toEqual([{ kind: "available", available: true }]);
    await context.options.setOptions({ passkeys: false });
    expect((await context.send({ kind: "available" })).posted).toEqual([{ kind: "available", available: false }]);
    expect(context.state.unlocks).toBe(0);
  });
});

describe("conditional requests", () => {
  const frame = (fields: Partial<FrameRef> = {}): FrameRef => ({
    tabId: 7,
    frameId: 0,
    documentId: "doc-0",
    url: PAGE,
    origin: ORIGIN,
    ...fields,
  });

  it("waits for the user's pick in the menu, then answers with the passkey", async () => {
    const context = setup({ status: { kind: "locked", unlocking: false } });
    const port = await context.send(getRequest({ mediation: "conditional" }));
    // No prompt and no request until the user picks.
    expect(port.posted).toEqual([]);
    expect(context.requests).toEqual([]);
    expect(context.state.unlocks).toBe(0);
    expect(context.changes).toEqual([frame()]);
    expect(context.passkeys.conditionalFor(frame())).toMatchObject({ rpId: "example.com" });
    expect(context.passkeys.conditionalFor(frame({ frameId: 3 }))).toBeNull();
    expect(context.passkeys.conditionalFor(frame({ documentId: "doc-1" }))).toBeNull();

    context.state.status = { kind: "unlocked" };
    await context.passkeys.pick(frame(), "pk-1");
    expect(context.requests).toEqual([
      {
        type: "passkey_get",
        origin: ORIGIN,
        options: { challenge: CHALLENGE, rpId: "example.com" },
        mediation: "conditional",
        same_origin_with_ancestors: true,
        handle: "pk-1",
      },
    ]);
    expect(port.posted).toEqual([{ kind: "credential", credential: authenticationJSON(ORIGIN, CHALLENGE) }]);
    // Answered: the menu stops listing passkeys.
    expect(context.passkeys.conditionalFor(frame())).toBeNull();
    expect(context.changes).toHaveLength(2);
  });

  it("keeps the request when the app refuses the pick, for the menu to show", async () => {
    const context = setup();
    const port = await context.send(getRequest({ mediation: "conditional" }));
    context.state.app = () => new ProtocolError("user_denied");
    await expect(context.passkeys.pick(frame(), "pk-1")).rejects.toMatchObject({ code: "user_denied" });
    context.state.app = () => ({ credential: authenticationJSON(ORIGIN, "b3RoZXI") });
    await expect(context.passkeys.pick(frame(), "pk-1")).rejects.toMatchObject({ code: "invalid_response" });
    expect(port.posted).toEqual([]);
    expect(context.passkeys.conditionalFor(frame())).not.toBeNull();
  });

  it("cancels a pick when its menu closes, and keeps the request for another pick", async () => {
    const context = setup();
    const port = await context.send(getRequest({ mediation: "conditional" }));
    context.state.app = () => new Promise(() => undefined);
    const menu = new AbortController();
    const pick = context.passkeys.pick(frame(), "pk-1", menu.signal);
    await settle();
    const signal = context.state.signals.at(-1);
    expect(signal?.aborted).toBe(false);
    menu.abort();
    expect(signal?.aborted).toBe(true);
    await expect(pick).rejects.toMatchObject({ code: "cancelled" });
    expect(port.posted).toEqual([]);
    expect(context.passkeys.conditionalFor(frame())).not.toBeNull();
    // Another pick answers the page.
    context.state.app = () => undefined;
    await context.passkeys.pick(frame(), "pk-1");
    expect(port.posted).toEqual([{ kind: "credential", credential: authenticationJSON(ORIGIN, CHALLENGE) }]);
  });

  it("hands the request to the browser when the pick falls back, and still reports the error", async () => {
    // "Use another device", and a session that ended.
    for (const error of [new ProtocolError("not_supported"), new SessionClosed({ reason: "closed" })]) {
      const context = setup();
      const port = await context.send(getRequest({ mediation: "conditional" }));
      context.state.app = () => error;
      await expect(context.passkeys.pick(frame(), "pk-1"), error.message).rejects.toBe(error);
      expect(port.posted, error.message).toEqual([{ kind: "fallback" }]);
      expect(context.passkeys.conditionalFor(frame()), error.message).toBeNull();
      expect(context.changes, error.message).toHaveLength(2);
    }
  });

  it("hands waiting requests to the browser when the session ends or loses passkeys", async () => {
    const context = setup();
    const port = await context.send(getRequest({ mediation: "conditional" }));
    const subframe = await context.send(getRequest({ mediation: "conditional" }), contentSender({ frameId: 4, documentId: "doc-4" }));
    // The vault locks: the app's unlock prompt comes at the pick.
    context.state.status = { kind: "locked", unlocking: false };
    context.passkeys.statusChanged();
    expect(port.posted).toEqual([]);
    context.state.status = { kind: "app_unavailable" };
    context.passkeys.statusChanged();
    expect(port.posted).toEqual([{ kind: "fallback" }]);
    expect(subframe.posted).toEqual([{ kind: "fallback" }]);
    expect(context.passkeys.conditionalFor(frame())).toBeNull();
    expect(context.changes).toHaveLength(4);

    const without = setup();
    const waiting = await without.send(getRequest({ mediation: "conditional" }));
    without.state.capabilities.delete("passkeys");
    without.passkeys.statusChanged();
    expect(waiting.posted).toEqual([{ kind: "fallback" }]);
  });

  it("hands waiting requests to the browser when the user turns passkeys off", async () => {
    const context = setup();
    const port = await context.send(getRequest({ mediation: "conditional" }));
    // Another option changed: still waiting.
    await context.change({ inlineMenu: false });
    expect(port.posted).toEqual([]);
    await context.change({ passkeys: false });
    expect(port.posted).toEqual([{ kind: "fallback" }]);
    expect(context.passkeys.conditionalFor(frame())).toBeNull();
    expect(context.changes).toHaveLength(2);
    expect(context.requests).toEqual([]);
  });

  it("hands a request to the browser when the user turns passkeys off while it's checked", async () => {
    // Its read of the options starts before the change, gets the old value,
    // and finishes after the change or before it.
    for (const order of ["after", "before"]) {
      fakeBrowser.reset();
      const context = setup();
      const release = context.storage.holdNextRead();
      const port = new FakePort(contentSender());
      context.passkeys.connect(port);
      port.emit(getRequest({ mediation: "conditional" }));
      if (order === "before") release();
      await settle();
      await context.change({ passkeys: false });
      await settle();
      release();
      await settle();
      expect(port.posted, order).toEqual([{ kind: "fallback" }]);
      expect(context.passkeys.conditionalFor(frame()), order).toBeNull();
      expect(context.requests, order).toEqual([]);
    }
  });

  it("sends no pick to the app once the user turned passkeys off", async () => {
    const context = setup();
    const port = await context.send(getRequest({ mediation: "conditional" }));
    // A read of the options after the change would be slow.
    const release = context.storage.holdNextRead();
    await context.change({ passkeys: false });
    await expect(context.passkeys.pick(frame(), "pk-1")).rejects.toMatchObject({ code: "invalid_request" });
    release();
    await settle();
    expect(context.requests).toEqual([]);
    expect(port.posted).toEqual([{ kind: "fallback" }]);
    expect(context.passkeys.conditionalFor(frame())).toBeNull();
  });

  it("hands a request to the browser when the session ends while it's checked", async () => {
    const context = setup();
    const port = new FakePort(contentSender());
    context.passkeys.connect(port);
    port.emit(getRequest({ mediation: "conditional" }));
    // While the options load.
    context.state.status = { kind: "app_unavailable" };
    context.passkeys.statusChanged();
    await settle();
    expect(port.posted).toEqual([{ kind: "fallback" }]);
    expect(context.passkeys.conditionalFor(frame())).toBeNull();
    expect(context.changes).toEqual([]);
  });

  it("forgets the request when the page aborts or navigates", async () => {
    const context = setup();
    const port = await context.send(getRequest({ mediation: "conditional" }));
    let answer: () => void = () => undefined;
    context.state.app = () => new Promise((resolve) => (answer = () => resolve({ credential: authenticationJSON(ORIGIN, CHALLENGE) })));
    const pick = context.passkeys.pick(frame(), "pk-1");
    await settle();
    port.close();
    expect(context.passkeys.conditionalFor(frame())).toBeNull();
    expect(context.changes).toHaveLength(2);
    // The pick's request in flight gets a `cancel`.
    expect(context.state.signals.at(-1)?.aborted).toBe(true);
    answer();
    await expect(pick).rejects.toMatchObject({ code: "origin" });
    expect(port.posted).toEqual([]);
    await expect(context.passkeys.pick(frame(), "pk-1")).rejects.toMatchObject({ code: "invalid_request" });
  });

  it("keeps the extension busy while one waits: a reload would hand it to the browser", async () => {
    const context = setup();
    await context.send(getRequest());
    expect(context.passkeys.busy).toBe(false);
    const port = await context.send(getRequest({ mediation: "conditional" }));
    expect(context.passkeys.busy).toBe(true);
    port.close();
    expect(context.passkeys.busy).toBe(false);
  });

  it("lists passkeys of the request's RP ID, or of the page's host", () => {
    expect(rpIdOf({ rpId: "example.com" }, ORIGIN)).toBe("example.com");
    expect(rpIdOf({}, "https://login.example.com:8443")).toBe("login.example.com");
  });
});

describe("with the app session", () => {
  let noise: NativeCryptoNoise;
  beforeAll(async () => {
    noise = await loadNoise();
  });

  /** A controller paired with an app and a PasskeyController on it, as entrypoints/background.ts wires them. */
  async function paired(handler: Handler) {
    const origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";
    const appKeys = noise.generateKeyPair();
    const pairedKeys = new Set<string>();
    const hosts: FakeHost[] = [];
    const options = new OptionsStore(fakeBrowser.storage.local);
    const controller = new BackgroundController({
      connectNative: () => {
        const port = new FakeNativePort();
        hosts.push(new FakeHost(port, () => ({ noise, origin, keys: appKeys, pairedKeys, vault: "unlocked", handler })));
        return port;
      },
      lastError: () => undefined,
      loadNoise: () => Promise.resolve(noise),
      storage: fakeBrowser.storage.local,
      origin,
      browserName: "Chrome",
      tab: async () => ({ url: PAGE }),
      copyText: async () => undefined,
      mayCopyPassword: () => false,
      onStatus: () => passkeys.statusChanged(),
      initialBackoffMs: 1,
    });
    const passkeys = new PasskeyController({ extensionId: ID, app: controller, options, conditionalChanged: () => undefined });
    controller.start();
    hosts[0]!.ready();
    for (let i = 0; i < 200 && controller.snapshot.kind !== "not_paired"; i++) await settle();
    await controller.calls.pair({});
    return { controller, passkeys, options, hosts, app: hosts[0]!.app! };
  }

  const FRAME: FrameRef = { tabId: 7, frameId: 0, documentId: "doc-0", url: PAGE, origin: ORIGIN };

  it("sends passkey_get through the protocol, with the passkeys capability", async () => {
    const { controller, passkeys, hosts, app } = await paired((request) => {
      // The app prompts until the extension cancels.
      if (request.type === "passkey_create") return new Promise(() => undefined);
      if (request.type !== "passkey_get") return undefined;
      const options = request.options as { challenge: string };
      return { credential: authenticationJSON(request.origin as string, options.challenge) };
    });
    expect(controller.supports("passkeys")).toBe(true);

    const port = new FakePort(contentSender());
    passkeys.connect(port);
    port.emit(getRequest());
    for (let i = 0; i < 200 && port.posted.length === 0; i++) await settle();
    expect(app.requests.find((it) => it.type === "passkey_get")).toMatchObject({
      origin: ORIGIN,
      mediation: "optional",
      same_origin_with_ancestors: true,
      options: { challenge: CHALLENGE, rpId: "example.com" },
    });
    expect(port.posted).toEqual([{ kind: "credential", credential: authenticationJSON(ORIGIN, CHALLENGE) }]);

    // Locked: the request goes to the app as is. The page aborts while the app
    // prompts, and the app gets a `cancel` for it.
    app.vault = "locked";
    app.event({ event: "vault", state: "locked" });
    for (let i = 0; i < 200 && controller.snapshot.kind !== "locked"; i++) await settle();
    const aborted = new FakePort(contentSender());
    passkeys.connect(aborted);
    aborted.emit(createRequest());
    for (let i = 0; i < 200 && !app.requests.some((it) => it.type === "passkey_create"); i++) await settle();
    expect(app.requests.some((it) => it.type === "unlock")).toBe(false);
    aborted.close();
    for (let i = 0; i < 200 && app.cancels.length === 0; i++) await settle();
    expect(app.cancels).toEqual([app.requests.find((it) => it.type === "passkey_create")!.id]);
    await settle();
    expect(aborted.posted).toEqual([]);
    expect(controller.snapshot.kind).toBe("locked");

    // The app goes away while the page waits on a conditional request: the
    // browser's own conditional request answers it.
    const conditional = new FakePort(contentSender());
    passkeys.connect(conditional);
    conditional.emit(getRequest({ mediation: "conditional" }));
    await settle();
    expect(passkeys.conditionalFor(FRAME)).not.toBeNull();
    hosts[0]!.status("app_unavailable");
    expect(conditional.posted).toEqual([{ kind: "fallback" }]);
  });

  it("hands a request whose options nest deeper than the app takes to the browser, and keeps the session", async () => {
    const { controller, passkeys, app } = await paired((request) => {
      if (request.type !== "passkey_get") return undefined;
      const options = request.options as { challenge: string };
      return { credential: authenticationJSON(request.origin as string, options.challenge) };
    });
    const session = controller.sessionId;
    // A page can post any JSON to the bridge, past the main world's checks.
    let deep: unknown = 1;
    for (let i = 0; i < 100; i++) deep = [deep];
    const options = { challenge: CHALLENGE, rpId: "example.com", extensions: { x: deep } };
    for (const mediation of ["optional", "conditional"]) {
      const port = new FakePort(contentSender());
      passkeys.connect(port);
      port.emit(getRequest({ options, mediation }));
      await settle();
      if (mediation === "conditional") {
        // The user picks a passkey in the menu: the request goes to the browser, and the menu tells.
        await expect(passkeys.pick(FRAME, "pk-1")).rejects.toMatchObject({ name: "MessageTooDeep" });
      }
      for (let i = 0; i < 200 && port.posted.length === 0; i++) await settle();
      expect(port.posted, mediation).toEqual([{ kind: "fallback" }]);
    }
    expect(app.requests.some((it) => it.type === "passkey_get")).toBe(false);
    // Other tabs' requests go on in the same session.
    const port = new FakePort(contentSender());
    passkeys.connect(port);
    port.emit(getRequest());
    for (let i = 0; i < 200 && port.posted.length === 0; i++) await settle();
    expect(port.posted).toEqual([{ kind: "credential", credential: authenticationJSON(ORIGIN, CHALLENGE) }]);
    expect(controller.sessionId).toBe(session);
  });

  it("keeps the session as the user turns passkeys off and on", async () => {
    // A request in flight that the toggles mustn't cancel.
    const { controller, passkeys, options, hosts, app } = await paired((request) =>
      request.type === "generate" ? new Promise(() => undefined) : undefined,
    );
    const session = controller.sessionId;
    const generating = controller.request("generate", {});
    generating.catch(() => undefined);
    const conditional = new FakePort(contentSender());
    passkeys.connect(conditional);
    conditional.emit(getRequest({ mediation: "conditional" }));
    for (let i = 0; i < 200 && !app.requests.some((it) => it.type === "generate"); i++) await settle();
    expect(passkeys.conditionalFor(FRAME)).not.toBeNull();

    // As entrypoints/background.ts on `storage.onChanged`.
    passkeys.optionsChanged(await options.setOptions({ passkeys: false }));
    expect(conditional.posted).toEqual([{ kind: "fallback" }]);
    const off = new FakePort(contentSender());
    passkeys.connect(off);
    off.emit(getRequest());
    await settle();
    expect(off.posted).toEqual([{ kind: "fallback" }]);

    passkeys.optionsChanged(await options.setOptions({ passkeys: true }));
    const on = new FakePort(contentSender());
    passkeys.connect(on);
    on.emit(getRequest({ mediation: "conditional" }));
    await settle();
    expect(passkeys.conditionalFor(FRAME)).not.toBeNull();

    // One session throughout: no new port, no second `hello`, no `cancel`.
    expect(hosts).toHaveLength(1);
    expect(controller.sessionId).toBe(session);
    expect(controller.supports("passkeys")).toBe(true);
    expect(app.requests.filter((it) => it.type === "hello")).toHaveLength(1);
    expect(app.requests.some((it) => it.type === "passkey_get")).toBe(false);
    expect(app.cancels).toEqual([]);
    expect(controller.snapshot.kind).toBe("unlocked");
  });
});
