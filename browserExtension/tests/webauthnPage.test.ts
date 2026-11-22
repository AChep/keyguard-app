// The page's side of passkeys: the main-world wrappers (src/lib/webauthn/page.ts)
// and the isolated bridge (src/lib/content/webauthnBridge.ts) in a happy-dom
// window, with a fake background behind the bridge's ports and fake browser
// WebAuthn classes.
import { Window as HappyWindow } from "happy-dom";
import { afterEach, describe, expect, it } from "vitest";
import {
  MAX_REQUESTS,
  sameOriginWithAncestors,
  startWebAuthnBridge,
  type BridgePort,
} from "../src/lib/content/webauthnBridge";
import { BRIDGE_TAG, type WebAuthnRequest, type WebAuthnResult } from "../src/lib/webauthn/bridge";
import { fromBase64Url } from "../src/lib/webauthn/json";
import { installWebAuthn } from "../src/lib/webauthn/page";
import { authenticationJSON, CREDENTIAL_ID, registrationJSON } from "./support/webauthn";

const URL = "https://login.example.com/sign-in";
const ORIGIN = "https://login.example.com";
type PageWindow = Window & typeof globalThis;
type Background = (request: WebAuthnRequest, port: FakeBridgePort) => void;

const windows: HappyWindow[] = [];
afterEach(async () => {
  for (const win of windows.splice(0)) await win.happyDOM.close();
});

async function settle(rounds = 10) {
  for (let i = 0; i < rounds; i++) await new Promise((resolve) => setTimeout(resolve, 0));
}

/** The kinds of the bridge's messages on [win] from now on, as the page's own listeners see them. */
function bridgeMessages(win: PageWindow): string[] {
  const kinds: string[] = [];
  win.addEventListener("message", (event) => {
    const data = event.data as { tag?: unknown; json?: unknown } | null;
    if (data?.tag === BRIDGE_TAG && typeof data.json === "string")
      kinds.push((JSON.parse(data.json) as { kind: string }).kind);
  });
  return kinds;
}

/** The background's end of a bridge port. */
class FakeBridgePort implements BridgePort {
  readonly posted: unknown[] = [];
  disconnected = false;
  private readonly messageListeners: ((message: unknown) => void)[] = [];
  private readonly disconnectListeners: (() => void)[] = [];
  readonly onMessage = {
    addListener: (listener: (message: unknown) => void) => void this.messageListeners.push(listener),
  };
  readonly onDisconnect = { addListener: (listener: () => void) => void this.disconnectListeners.push(listener) };

  constructor(private readonly background: Background) {}

  postMessage(message: unknown): void {
    this.posted.push(message);
    queueMicrotask(() => this.background(message as WebAuthnRequest, this));
  }

  /** The content script disconnects: its own listeners don't fire. */
  disconnect(): void {
    this.disconnected = true;
  }

  reply(result: WebAuthnResult): void {
    if (!this.disconnected) for (const listener of this.messageListeners) listener(result);
  }

  /** The background's side went away. */
  close(): void {
    this.disconnected = true;
    for (const listener of this.disconnectListeners) listener();
  }
}

function define(target: object, name: string, value: unknown) {
  Object.defineProperty(target, name, { value, configurable: true, writable: true });
}

/** A challenge's base64url, as the app reads it from the request. */
function challengeOf(request: WebAuthnRequest): string {
  return (request as unknown as { options: { challenge: string } }).options.challenge;
}

/** Answers with a credential for the request, as the app would. */
const answerWithCredential: Background = (request, port) => {
  if (request.kind === "available") port.reply({ kind: "available", available: true });
  else if (request.kind === "get")
    port.reply({ kind: "credential", credential: authenticationJSON(ORIGIN, challengeOf(request)) });
  else port.reply({ kind: "credential", credential: registrationJSON(ORIGIN, challengeOf(request)) });
};

function setup(
  options: { bridge?: boolean; background?: Background; timeoutMs?: number; focused?: () => boolean } = {},
) {
  const happy = new HappyWindow({ url: URL });
  windows.push(happy);
  const win = happy as unknown as PageWindow;
  // Before the install, which takes `hasFocus` from the prototype; the isolated bridge calls the document's.
  if (options.focused) {
    define(win.Document.prototype, "hasFocus", options.focused);
    define(win.document, "hasFocus", options.focused);
  }
  const native = {
    gets: [] as Record<string, unknown>[],
    creates: [] as Record<string, unknown>[],
    get: (_options: Record<string, unknown>): Promise<unknown> => Promise.resolve("native credential"),
    create: (_options: Record<string, unknown>): Promise<unknown> => Promise.resolve("native registration"),
    conditional: false,
    uvpaa: false,
  };
  class FakeContainer {
    get(options: Record<string, unknown>) {
      native.gets.push(options);
      return native.get(options);
    }
    create(options: Record<string, unknown>) {
      native.creates.push(options);
      return native.create(options);
    }
  }
  class FakePublicKeyCredential {
    static isConditionalMediationAvailable() {
      return Promise.resolve(native.conditional);
    }
    static isUserVerifyingPlatformAuthenticatorAvailable() {
      return Promise.resolve(native.uvpaa);
    }
    static getClientCapabilities() {
      return Promise.resolve({
        conditionalGet: native.conditional,
        hybridTransport: true,
        userVerifyingPlatformAuthenticator: false,
      });
    }
  }
  class FakeAttestationResponse {}
  class FakeAssertionResponse {}
  const credentials = new FakeContainer();
  const originals = {
    get: Object.getOwnPropertyDescriptor(FakeContainer.prototype, "get")!,
    isConditionalMediationAvailable: Object.getOwnPropertyDescriptor(
      FakePublicKeyCredential,
      "isConditionalMediationAvailable",
    )!,
  };
  define(win, "isSecureContext", true);
  define(win, "CredentialsContainer", FakeContainer);
  define(win, "PublicKeyCredential", FakePublicKeyCredential);
  define(win, "AuthenticatorAttestationResponse", FakeAttestationResponse);
  define(win, "AuthenticatorAssertionResponse", FakeAssertionResponse);
  define(win.navigator, "credentials", credentials);

  const ports: FakeBridgePort[] = [];
  const bridgeAbort = new AbortController();
  const background = options.background ?? answerWithCredential;
  // As at document_start: the main world first, then the isolated script.
  const installed = installWebAuthn(win, options.timeoutMs ?? 50);
  if (options.bridge !== false) {
    startWebAuthnBridge({
      win,
      connect: () => {
        const port = new FakeBridgePort(background);
        ports.push(port);
        return port;
      },
      signal: bridgeAbort.signal,
    });
  }
  return {
    win,
    native,
    ports,
    credentials,
    originals,
    installed,
    bridgeAbort,
    classes: { FakeContainer, FakePublicKeyCredential, FakeAttestationResponse, FakeAssertionResponse },
    requests: () => ports.map((port) => port.posted[0] as WebAuthnRequest),
  };
}

const challenge = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]);
const getOptions = (extra: Record<string, unknown> = {}) => ({
  publicKey: { challenge, rpId: "example.com" },
  ...extra,
});
const createOptions = (): CredentialCreationOptions => ({
  publicKey: {
    rp: { id: "example.com", name: "Example" },
    user: { id: new TextEncoder().encode("user-1"), name: "ann", displayName: "Ann" },
    challenge,
    pubKeyCredParams: [{ type: "public-key" as const, alg: -7 }],
    extensions: { credProps: true },
  },
});

describe("main-world wrappers", () => {
  it("installs only in a secure context with WebAuthn", () => {
    const happy = new HappyWindow({ url: "http://insecure.example/" });
    windows.push(happy);
    expect(installWebAuthn(happy as unknown as PageWindow)).toBe(false);
    expect(setup().installed).toBe(true);
  });

  it("keeps the functions native-looking, with their property attributes", () => {
    const { win, originals, classes } = setup();
    const get = win.navigator.credentials.get;
    expect(get).not.toBe(originals.get.value);
    expect(get.name).toBe("get");
    expect(get.length).toBe(originals.get.value.length);
    expect(Function.prototype.toString.call(get)).toContain("[native code]");
    const { value: _, ...attributes } = Object.getOwnPropertyDescriptor(classes.FakeContainer.prototype, "get")!;
    const { value: __, ...original } = originals.get;
    expect(attributes).toEqual(original);
    expect(Object.keys(win.navigator.credentials)).toEqual([]);
    const check = (classes.FakePublicKeyCredential as unknown as Record<string, () => unknown>)
      .isConditionalMediationAvailable!;
    expect(check.name).toBe("isConditionalMediationAvailable");
  });

  it("passes everything but publicKey requests on navigator.credentials to the browser", async () => {
    const { win, native, ports } = setup();
    const password = { password: true };
    await expect(win.navigator.credentials.get(password as CredentialRequestOptions)).resolves.toBe(
      "native credential",
    );
    expect(native.gets[0]).toBe(password);
    const other = new (win.CredentialsContainer as unknown as new () => CredentialsContainer)();
    await other.get(getOptions());
    // A mediation Keyguard doesn't know, options it can't read, or a signal that already fired.
    await win.navigator.credentials.get(getOptions({ mediation: "immediate" }) as CredentialRequestOptions);
    await win.navigator.credentials.get({ publicKey: { rpId: "example.com" } } as unknown as CredentialRequestOptions);
    const aborted = new (win.AbortController as typeof AbortController)();
    aborted.abort();
    await win.navigator.credentials.get(getOptions({ signal: aborted.signal }));
    await win.navigator.credentials.create(
      getOptions({ mediation: "conditional" }) as unknown as CredentialCreationOptions,
    );
    await settle();
    expect(native.gets).toHaveLength(5);
    expect(native.creates).toHaveLength(1);
    expect(ports).toHaveLength(0);
  });

  it("answers a modal get with Keyguard's credential, on the browser's prototypes", async () => {
    const { win, native, ports, classes, requests } = setup();
    const credential = (await win.navigator.credentials.get(getOptions())) as PublicKeyCredential;
    expect(native.gets).toEqual([]);
    expect(credential).toBeInstanceOf(classes.FakePublicKeyCredential);
    expect(credential.response).toBeInstanceOf(classes.FakeAssertionResponse);
    expect(credential.id).toBe(CREDENTIAL_ID);
    expect(requests()).toEqual([
      {
        kind: "get",
        mediation: "optional",
        options: { challenge: "AQIDBAUGBwg", rpId: "example.com" },
        same_origin_with_ancestors: true,
      },
    ]);
    // The bridge closed the port after the answer.
    expect(ports[0]!.disconnected).toBe(true);
    expect(credential.toJSON()).toEqual(authenticationJSON(ORIGIN, "AQIDBAUGBwg"));
  });

  it("creates a passkey, with the registration's getters", async () => {
    const { win, native, classes, requests } = setup();
    const credential = (await win.navigator.credentials.create(createOptions())) as PublicKeyCredential;
    expect(native.creates).toEqual([]);
    expect(credential).toBeInstanceOf(classes.FakePublicKeyCredential);
    const response = credential.response as AuthenticatorAttestationResponse;
    expect(response).toBeInstanceOf(classes.FakeAttestationResponse);
    expect(response.getTransports()).toEqual(["internal", "hybrid"]);
    expect(response.getPublicKeyAlgorithm()).toBe(-7);
    expect(credential.getClientExtensionResults()).toEqual({ credProps: { rk: true }, prf: { enabled: true } });
    expect(requests()[0]).toMatchObject({
      kind: "create",
      options: { rp: { id: "example.com", name: "Example" }, user: { name: "ann" }, extensions: { credProps: true } },
    });
  });

  it("lets the browser handle what Keyguard passes on, with the page's own options", async () => {
    const { win, native } = setup({ background: (_request, port) => port.reply({ kind: "fallback" }) });
    const options = getOptions();
    await expect(win.navigator.credentials.get(options)).resolves.toBe("native credential");
    expect(native.gets).toEqual([options]);
    expect(native.gets[0]).toBe(options);
    const creation = createOptions();
    await expect(win.navigator.credentials.create(creation)).resolves.toBe("native registration");
    expect(native.creates[0]).toBe(creation);
  });

  it("hands a fallback to the browser once the page has focus again, unless the page aborts first", async () => {
    let focused = true;
    // Keyguard's prompt took focus, then passed: the browser would refuse the call now.
    const background: Background = (_request, port) => {
      focused = false;
      port.reply({ kind: "fallback" });
    };
    const { win, native } = setup({ background, focused: () => focused });
    const pending = win.navigator.credentials.get(getOptions());
    await settle();
    expect(native.gets).toEqual([]);
    // A focus event while the document has none changes nothing.
    win.dispatchEvent(new win.Event("focus"));
    await settle();
    expect(native.gets).toEqual([]);
    focused = true;
    win.dispatchEvent(new win.Event("focus"));
    await expect(pending).resolves.toBe("native credential");
    expect(native.gets).toHaveLength(1);

    const controller = new (win.AbortController as typeof AbortController)();
    const create = win.navigator.credentials.create({ ...createOptions(), signal: controller.signal });
    await settle();
    const reason = new Error("page gave up");
    controller.abort(reason);
    await expect(create).rejects.toBe(reason);
    focused = true;
    win.dispatchEvent(new win.Event("focus"));
    await settle();
    expect(native.creates).toEqual([]);

    // A conditional request doesn't wait.
    focused = false;
    void win.navigator.credentials.get(getOptions({ mediation: "conditional" }));
    expect(native.gets).toHaveLength(2);
  });

  it("hands a modal request of a document without focus to the browser at once, which refuses it", async () => {
    let focused = false;
    const { win, native, ports } = setup({ focused: () => focused });
    const messages = bridgeMessages(win);
    await expect(win.navigator.credentials.get(getOptions())).resolves.toBe("native credential");
    const creation = createOptions();
    await expect(win.navigator.credentials.create(creation)).resolves.toBe("native registration");
    expect(native.creates[0]).toBe(creation);
    expect(ports).toEqual([]);
    expect(messages).toEqual([]);
    // A conditional request waits for the user's pick, which needs focus anyway.
    const get = native.get;
    native.get = () => new Promise(() => undefined);
    void win.navigator.credentials.get(getOptions({ mediation: "conditional" }));
    await settle();
    expect(ports).toHaveLength(1);
    native.get = get;

    focused = true;
    await expect(win.navigator.credentials.get(getOptions())).resolves.toMatchObject({ id: CREDENTIAL_ID });
    expect(ports).toHaveLength(2);
    expect(native.gets).toHaveLength(2);
  });

  it("falls back when the background goes away or Keyguard's answer is malformed", async () => {
    const closing = setup({ background: (_request, port) => port.close() });
    await expect(closing.win.navigator.credentials.get(getOptions())).resolves.toBe("native credential");
    const malformed = setup({
      background: (_request, port) => port.reply({ kind: "credential", credential: { id: 1 } }),
    });
    await expect(malformed.win.navigator.credentials.get(getOptions())).rejects.toMatchObject({
      name: "NotAllowedError",
    });
    expect(malformed.native.gets).toEqual([]);
  });

  it("rejects with the DOMException the background names", async () => {
    for (const name of ["NotAllowedError", "InvalidStateError", "SecurityError", "NotSupportedError"] as const) {
      const { win, native } = setup({ background: (_request, port) => port.reply({ kind: "error", name }) });
      const error = await win.navigator.credentials.create(createOptions()).catch((error: unknown) => error);
      expect(error).toBeInstanceOf(win.DOMException);
      expect(error).toMatchObject({ name });
      expect(native.creates).toEqual([]);
    }
  });

  it("rejects with the abort reason and cancels in the background", async () => {
    const { win, native, ports } = setup({ background: () => undefined });
    const controller = new (win.AbortController as typeof AbortController)();
    const pending = win.navigator.credentials.get(getOptions({ signal: controller.signal }));
    await settle();
    expect(ports).toHaveLength(1);
    const reason = new Error("page gave up");
    controller.abort(reason);
    await expect(pending).rejects.toBe(reason);
    await settle();
    expect(ports[0]!.disconnected).toBe(true);
    expect(native.gets).toEqual([]);

    const second = new (win.AbortController as typeof AbortController)();
    const create = win.navigator.credentials.create({ ...createOptions(), signal: second.signal });
    await settle();
    second.abort();
    await expect(create).rejects.toMatchObject({ name: "AbortError" });
  });

  it("aborts a request that waits for the isolated script, which never gets it", async () => {
    const { win, ports } = setup({ bridge: false, timeoutMs: 1_000 });
    const controller = new (win.AbortController as typeof AbortController)();
    const pending = win.navigator.credentials.get(getOptions({ signal: controller.signal }));
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(ports).toEqual([]);
  });

  it("hands a request longer than the bridge takes to the browser, queued or not", async () => {
    // A long timer: only the length check answers the queued call.
    const { win, native, ports } = setup({ timeoutMs: 60_000 });
    const descriptors = Array.from({ length: 2_000 }, (_, i) => ({
      type: "public-key" as const,
      id: new Uint8Array(32).fill(i),
    }));
    const get = { publicKey: { ...getOptions().publicKey, allowCredentials: descriptors } };
    const create = createOptions();
    create.publicKey!.excludeCredentials = descriptors;
    // Before the isolated script said it's there.
    const queued = win.navigator.credentials.get(get);
    await expect(queued).resolves.toBe("native credential");
    await expect(win.navigator.credentials.get(get)).resolves.toBe("native credential");
    await expect(win.navigator.credentials.create(create)).resolves.toBe("native registration");
    expect(native.gets).toEqual([get, get]);
    expect(native.creates[0]).toBe(create);
    await settle();
    expect(ports).toEqual([]);
  });

  it("goes to the browser without the isolated script", async () => {
    const { win, native } = setup({ bridge: false });
    await expect(win.navigator.credentials.get(getOptions())).resolves.toBe("native credential");
    expect(native.gets).toHaveLength(1);
    await expect(win.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()).resolves.toBe(false);
  });

  it("ignores answers from other windows", async () => {
    const { win } = setup({ background: () => undefined });
    let settled = false;
    void win.navigator.credentials.get(getOptions()).then(() => (settled = true));
    await settle();
    const forged = {
      tag: BRIDGE_TAG,
      to: "main",
      json: JSON.stringify({ kind: "result", id: 1, result: { kind: "fallback" } }),
    };
    const other = new HappyWindow({ url: "https://evil.example/" });
    windows.push(other);
    win.dispatchEvent(new win.MessageEvent("message", { data: forged, source: other as unknown as Window }));
    await settle();
    expect(settled).toBe(false);
  });
});

describe("handshake", () => {
  it("posts nothing until the page's first request, then asks once", async () => {
    const { win } = setup();
    const messages = bridgeMessages(win);
    await settle();
    expect(messages).toEqual([]);

    await expect(win.navigator.credentials.get(getOptions())).resolves.toMatchObject({ id: CREDENTIAL_ID });
    await expect(win.navigator.credentials.create(createOptions())).resolves.toMatchObject({ id: CREDENTIAL_ID });
    expect(messages).toEqual(["ping", "ready", "get", "ack", "result", "create", "ack", "result"]);
  });

  it("hands a request to the browser when the isolated script stops answering, as after an update, and asks again", async () => {
    const { win, native, ports, bridgeAbort } = setup({ timeoutMs: 50 });
    const messages = bridgeMessages(win);
    await expect(win.navigator.credentials.get(getOptions())).resolves.toMatchObject({ id: CREDENTIAL_ID });
    // The extension updated: its isolated script is orphaned, and answers nothing.
    bridgeAbort.abort();
    messages.length = 0;
    await expect(win.navigator.credentials.get(getOptions())).resolves.toBe("native credential");
    await settle();
    expect(messages).toEqual(["get", "abort"]);
    expect(native.gets).toHaveLength(1);
    expect(ports).toHaveLength(1);

    // A new isolated script, as Firefox injects: the next request asks for it, and it answers.
    messages.length = 0;
    const later: FakeBridgePort[] = [];
    startWebAuthnBridge({
      win,
      connect: () => {
        const port = new FakeBridgePort(answerWithCredential);
        later.push(port);
        return port;
      },
      signal: new AbortController().signal,
    });
    await expect(win.navigator.credentials.get(getOptions())).resolves.toMatchObject({ id: CREDENTIAL_ID });
    expect(messages).toEqual(["ping", "ready", "get", "ack", "result"]);
    expect(later).toHaveLength(1);
  });

  it("waits for a slow answer once the isolated script has the request", async () => {
    let answer!: () => void;
    const { win, native } = setup({
      timeoutMs: 20,
      background: (request, port) => (answer = () => answerWithCredential(request, port)),
    });
    const pending = win.navigator.credentials.get(getOptions());
    await new Promise((resolve) => setTimeout(resolve, 60));
    answer();
    await expect(pending).resolves.toMatchObject({ id: CREDENTIAL_ID });
    expect(native.gets).toEqual([]);
  });

  it("hands the first request to the browser without the isolated script, and asks again on a later one", async () => {
    const { win, native } = setup({ bridge: false });
    const messages = bridgeMessages(win);
    await expect(win.navigator.credentials.get(getOptions())).resolves.toBe("native credential");
    expect(messages).toEqual(["ping"]);

    // The isolated script starts late.
    const ports: FakeBridgePort[] = [];
    const connect = () => {
      const port = new FakeBridgePort(answerWithCredential);
      ports.push(port);
      return port;
    };
    startWebAuthnBridge({ win, connect, signal: new AbortController().signal });
    await expect(win.navigator.credentials.get(getOptions())).resolves.toMatchObject({ id: CREDENTIAL_ID });
    expect(native.gets).toHaveLength(1);
    expect(ports).toHaveLength(1);
    expect(messages).toEqual(["ping", "ping", "ready", "get", "ack", "result"]);
  });
});

describe("conditional mediation", () => {
  function conditionalSetup(background: Background) {
    const context = setup({ background });
    let browserSignal: AbortSignal | null = null;
    let resolveBrowser: (value: unknown) => void = () => undefined;
    let rejectBrowser: (error: unknown) => void = () => undefined;
    context.native.get = (options) => {
      browserSignal = options.signal as AbortSignal;
      return new Promise((resolve, reject) => {
        resolveBrowser = resolve;
        rejectBrowser = reject;
        browserSignal!.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      });
    };
    return {
      ...context,
      browserSignal: () => browserSignal,
      resolveBrowser: (value: unknown) => resolveBrowser(value),
      rejectBrowser: (error: unknown) => rejectBrowser(error),
    };
  }

  it("runs the browser's own conditional UI next to Keyguard's; Keyguard's pick wins", async () => {
    let answer: (() => void) | null = null;
    const context = conditionalSetup((request, port) => {
      answer = () => port.reply({ kind: "credential", credential: authenticationJSON(ORIGIN, challengeOf(request)) });
    });
    const controller = new (context.win.AbortController as typeof AbortController)();
    const options = getOptions({ mediation: "conditional", signal: controller.signal });
    const pending = context.win.navigator.credentials.get(options);
    await settle();
    expect(context.requests()[0]).toMatchObject({ kind: "get", mediation: "conditional" });
    // The browser's request: the page's options with a signal of the wrapper's.
    expect(context.native.gets[0]).toMatchObject({ mediation: "conditional", publicKey: options.publicKey });
    expect(context.browserSignal()).not.toBe(controller.signal);
    answer!();
    const credential = (await pending) as PublicKeyCredential;
    expect(credential.id).toBe(CREDENTIAL_ID);
    expect(context.browserSignal()!.aborted).toBe(true);
  });

  it("takes the browser's credential and cancels Keyguard's request", async () => {
    const context = conditionalSetup(() => undefined);
    const pending = context.win.navigator.credentials.get(getOptions({ mediation: "conditional" }));
    await settle();
    context.resolveBrowser("browser passkey");
    await expect(pending).resolves.toBe("browser passkey");
    await settle();
    expect(context.ports[0]!.disconnected).toBe(true);
  });

  it("keeps waiting for Keyguard when the browser fails, and fails when both do", async () => {
    let fallback: (() => void) | null = null;
    const context = conditionalSetup((_request, port) => (fallback = () => port.reply({ kind: "fallback" })));
    let outcome: unknown = null;
    const pending = context.win.navigator.credentials
      .get(getOptions({ mediation: "conditional" }))
      .catch((error: unknown) => {
        outcome = error;
      });
    await settle();
    const error = new DOMException("no conditional UI", "NotSupportedError");
    context.rejectBrowser(error);
    await settle();
    expect(outcome).toBeNull();
    fallback!();
    await pending;
    expect(outcome).toBe(error);
  });

  it("rejects on abort, stopping both", async () => {
    const context = conditionalSetup(() => undefined);
    const controller = new (context.win.AbortController as typeof AbortController)();
    const pending = context.win.navigator.credentials.get(
      getOptions({ mediation: "conditional", signal: controller.signal }),
    );
    await settle();
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    await settle();
    expect(context.browserSignal()!.aborted).toBe(true);
    expect(context.ports[0]!.disconnected).toBe(true);
  });
});

describe("feature checks", () => {
  it("says yes when the browser or Keyguard does", async () => {
    let available = true;
    const context = setup({ background: (_request, port) => port.reply({ kind: "available", available }) });
    const PublicKeyCredential = context.win.PublicKeyCredential;
    await expect(PublicKeyCredential.isConditionalMediationAvailable()).resolves.toBe(true);
    await expect(PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()).resolves.toBe(true);
    expect(context.requests()).toEqual([{ kind: "available" }, { kind: "available" }]);
    available = false;
    await expect(PublicKeyCredential.isConditionalMediationAvailable()).resolves.toBe(false);
    // The browser's yes needs no question.
    context.native.uvpaa = true;
    await expect(PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()).resolves.toBe(true);
    expect(context.requests()).toHaveLength(3);
  });

  it("adds Keyguard to the browser's client capabilities", async () => {
    let available = true;
    const context = setup({ background: (_request, port) => port.reply({ kind: "available", available }) });
    const getClientCapabilities = (
      context.win.PublicKeyCredential as unknown as { getClientCapabilities(): Promise<unknown> }
    ).getClientCapabilities;
    await expect(getClientCapabilities()).resolves.toEqual({
      conditionalGet: true,
      hybridTransport: true,
      passkeyPlatformAuthenticator: true,
      userVerifyingPlatformAuthenticator: true,
    });
    available = false;
    await expect(getClientCapabilities()).resolves.toEqual({
      conditionalGet: false,
      hybridTransport: true,
      userVerifyingPlatformAuthenticator: false,
    });
  });
});

describe("isolated bridge", () => {
  function post(win: PageWindow, json: unknown, source: unknown = win) {
    const data = { tag: BRIDGE_TAG, to: "isolated", json: typeof json === "string" ? json : JSON.stringify(json) };
    win.dispatchEvent(new win.MessageEvent("message", { data, source: source as Window }));
  }

  it("posts nothing unasked, and answers a ping at any time", async () => {
    const happy = new HappyWindow({ url: URL });
    windows.push(happy);
    const win = happy as unknown as PageWindow;
    const messages = bridgeMessages(win);
    startWebAuthnBridge({
      win,
      connect: () => new FakeBridgePort(() => undefined),
      signal: new AbortController().signal,
    });
    await settle();
    expect(messages).toEqual([]);
    post(win, { kind: "ping" });
    await settle();
    post(win, { kind: "ping" });
    await settle();
    expect(messages).toEqual(["ping", "ready", "ping", "ready"]);
  });

  it("takes requests from its own window only, well-formed", async () => {
    const { win, ports } = setup();
    const other = new HappyWindow({ url: "https://evil.example/" });
    windows.push(other);
    const request = { kind: "get", id: 99, options: { challenge: "AA" }, mediation: "optional" };
    post(win, request, other);
    post(win, "{not json");
    post(win, { ...request, id: -1 });
    post(win, { ...request, mediation: "sometimes" });
    post(win, { kind: "create", id: 98 });
    win.dispatchEvent(
      new win.MessageEvent("message", {
        data: { tag: BRIDGE_TAG, to: "main", json: JSON.stringify(request) },
        source: win,
      }),
    );
    await settle();
    expect(ports).toEqual([]);
    post(win, request);
    await settle();
    expect(ports).toHaveLength(1);
  });

  it("computes same_origin_with_ancestors itself, never from the page", async () => {
    const { win, requests } = setup({ background: () => undefined });
    post(win, {
      kind: "get",
      id: 7,
      options: { challenge: "AA" },
      mediation: "optional",
      same_origin_with_ancestors: false,
      origin: "https://bank.example",
    });
    await settle();
    expect(requests()).toEqual([
      { kind: "get", options: { challenge: "AA" }, mediation: "optional", same_origin_with_ancestors: true },
    ]);
  });

  it("compares the frame's origin with each ancestor's", () => {
    const location = (origin: string, ancestorOrigins?: string[]) => ({
      origin,
      ...(ancestorOrigins ? { ancestorOrigins } : {}),
    });
    const top = { location: location(ORIGIN) } as unknown as Window & { top: unknown; parent: unknown };
    top.top = top;
    top.parent = top;
    expect(sameOriginWithAncestors(top)).toBe(true);
    const frame = (origin: string, parent: Window, ancestorOrigins?: string[]) =>
      ({ location: location(origin, ancestorOrigins), top, parent }) as unknown as Window;
    // Chromium: location.ancestorOrigins.
    expect(sameOriginWithAncestors(frame(ORIGIN, top, [ORIGIN]))).toBe(true);
    expect(sameOriginWithAncestors(frame(ORIGIN, top, ["https://other.example", ORIGIN]))).toBe(false);
    // Elsewhere: a parent of another origin can't be read.
    expect(sameOriginWithAncestors(frame(ORIGIN, top))).toBe(true);
    const crossOrigin = {
      get location(): never {
        throw new DOMException("Blocked a frame", "SecurityError");
      },
      top,
      parent: top,
    } as unknown as Window;
    expect(sameOriginWithAncestors(frame(ORIGIN, crossOrigin))).toBe(false);
    // An about:blank or srcdoc frame runs as its parent's origin; its URL's is "null".
    const inherited = (parent: Window, ancestorOrigins?: string[]) =>
      ({ ...frame("null", parent, ancestorOrigins), origin: ORIGIN }) as unknown as Window;
    expect(sameOriginWithAncestors(inherited(top, [ORIGIN]))).toBe(true);
    expect(sameOriginWithAncestors(inherited(top))).toBe(true);
    expect(sameOriginWithAncestors(frame(ORIGIN, inherited(top)))).toBe(true);
  });

  it("hands a modal request of a document without focus to the browser itself, though the page posts it past the main world", async () => {
    let focused = false;
    const { win, ports } = setup({ focused: () => focused, background: () => undefined });
    const results: unknown[] = [];
    win.addEventListener("message", (event) => {
      const data = event.data as { to?: string; json?: string };
      const message =
        data.to === "main" && typeof data.json === "string" ? (JSON.parse(data.json) as { kind: string }) : null;
      if (message?.kind === "result") results.push(message);
    });
    post(win, { kind: "get", id: 1, options: { challenge: "AA" }, mediation: "optional" });
    post(win, { kind: "get", id: 2, options: { challenge: "AA" }, mediation: "required" });
    post(win, { kind: "create", id: 3, options: { challenge: "AA" } });
    await settle();
    expect(ports).toEqual([]);
    expect(results).toEqual([1, 2, 3].map((id) => ({ kind: "result", id, result: { kind: "fallback" } })));
    // A conditional one waits for a pick in the inline menu, which needs focus anyway.
    post(win, { kind: "get", id: 4, options: { challenge: "AA" }, mediation: "conditional" });
    await settle();
    expect(ports).toHaveLength(1);
    focused = true;
    post(win, { kind: "get", id: 5, options: { challenge: "AA" }, mediation: "optional" });
    await settle();
    expect(ports).toHaveLength(2);
  });

  it("answers for a sandboxed frame without asking the background", async () => {
    const happy = new HappyWindow({ url: URL });
    windows.push(happy);
    const win = happy as unknown as PageWindow;
    define(win, "origin", "null");
    const answers: unknown[] = [];
    win.addEventListener("message", (event) => answers.push(event.data));
    let connects = 0;
    startWebAuthnBridge({
      win,
      connect: () => (connects++, new FakeBridgePort(() => undefined)),
      signal: new AbortController().signal,
    });
    post(win, { kind: "get", id: 1, options: { challenge: "AA" }, mediation: "optional" });
    post(win, { kind: "available", id: 2 });
    await settle();
    expect(connects).toBe(0);
    const results = answers
      .map((it) => JSON.parse((it as { json: string }).json))
      .filter((it: { kind: string }) => it.kind === "result");
    expect(results).toEqual([
      { kind: "result", id: 1, result: { kind: "fallback" } },
      { kind: "result", id: 2, result: { kind: "available", available: false } },
    ]);
  });

  it("limits the requests in flight, and hands them to the browser when the extension goes away", async () => {
    const { win, ports, native, bridgeAbort } = setup({ background: () => undefined });
    const pending = Array.from({ length: MAX_REQUESTS + 1 }, () => win.navigator.credentials.get(getOptions()));
    await settle();
    expect(ports).toHaveLength(MAX_REQUESTS);
    // The one over the limit went to the browser at once.
    expect(native.gets).toHaveLength(1);
    bridgeAbort.abort();
    await Promise.all(pending);
    expect(native.gets).toHaveLength(MAX_REQUESTS + 1);
    expect(ports.every((port) => port.disconnected)).toBe(true);
  });

  it("keeps the challenge bytes through the bridge", async () => {
    const { win, requests } = setup({ background: () => undefined });
    void win.navigator.credentials.get(getOptions());
    await settle();
    expect(fromBase64Url(challengeOf(requests()[0]!))).toEqual(challenge);
  });
});
