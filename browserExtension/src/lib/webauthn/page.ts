// Passkeys in the page's main world (entrypoints/webauthn.content.ts). Wraps
// `navigator.credentials.create/get` and the feature checks of
// `PublicKeyCredential`, and asks the isolated content script (./bridge.ts)
// whether Keyguard answers. What Keyguard doesn't take goes to the browser's
// own functions, as without the extension.
//
// - The wrappers are Proxies of the browser's functions, set with the original
//   property attributes: `name` and `length` stay, and `toString()` gives
//   "function () { [native code] }".
// - The built-ins it calls are captured when the script runs, at
//   document_start, before any page script; replacing globals later doesn't
//   reach them.
// - Only `publicKey` requests on `navigator.credentials` are Keyguard's;
//   everything else goes straight to the browser.
// - It asks whether the isolated script is there on the first request, so a
//   page that never calls WebAuthn sees no message. Each request then waits
//   for the isolated script's `ack` a while: an update of the extension
//   orphans the script, which answers nothing, and the browser takes the
//   request.
// - A modal request of a document without focus goes to the browser, which
//   refuses it: Keyguard's window doesn't pop up for a page in the background.
// - A conditional `get` also runs the browser's own conditional request, so the
//   browser's passkeys stay offered; the first credential wins and aborts the
//   other.
import type { IsolatedMessage, MainMessage, PageRequest, WebAuthnErrorName, WebAuthnResult } from "./bridge";
import { BRIDGE_TAG, MAX_REQUEST_LENGTH } from "./bridge";
import { creationOptionsToJSON, credentialFromJSON, requestOptionsToJSON } from "./json";

/** How long a request waits for the isolated script to say it's there, or that it has the request, before going to the browser. */
export const BRIDGE_TIMEOUT_MS = 1_000;

// Developer-facing, as the browser's own messages are.
const ERROR_MESSAGES: Record<WebAuthnErrorName, string> = {
  NotAllowedError: "The operation either timed out or was not allowed.",
  InvalidStateError: "The authenticator already contains one of the excluded credentials.",
  SecurityError: "The relying party ID is not valid for this origin.",
  AbortError: "The operation was aborted.",
  NotSupportedError: "The operation is not supported.",
};

const FALLBACK: WebAuthnResult = { kind: "fallback" };

type Fn = (...args: unknown[]) => unknown;
type Handler = (original: Fn, thisArg: unknown, args: unknown[]) => unknown;
type Json = Record<string, unknown>;

interface Call {
  message: PageRequest & { id: number };
  sent: boolean;
  /** The isolated script said it has the request. */
  acked: boolean;
  done(result: WebAuthnResult | undefined): void;
}

const { apply, defineProperty, getOwnPropertyDescriptor } = Reflect;
const { create } = Object;
const { stringify, parse } = JSON;
const NativePromise = Promise;
const NativeProxy = Proxy;
const promiseThen = Promise.prototype.then;
const promiseResolve = Promise.resolve;

function isRecord(value: unknown): value is Json {
  return typeof value === "object" && value !== null;
}

function getter(prototype: object | undefined, name: string): Fn | undefined {
  return prototype === undefined ? undefined : (getOwnPropertyDescriptor(prototype, name)?.get as Fn | undefined);
}

/** Installs the wrappers in [win]; `false` where the page has no WebAuthn. */
export function installWebAuthn(win: Window & typeof globalThis, timeoutMs = BRIDGE_TIMEOUT_MS): boolean {
  const credentials = win.navigator?.credentials;
  const Container = win.CredentialsContainer;
  const PublicKeyCredential = win.PublicKeyCredential;
  if (
    win.isSecureContext !== true ||
    !credentials ||
    typeof Container !== "function" ||
    typeof PublicKeyCredential !== "function"
  ) {
    return false;
  }
  const NativeDOMException = win.DOMException;
  const NativeAbortController = win.AbortController;
  const abortController = NativeAbortController.prototype.abort as Fn;
  const controllerSignal = getter(NativeAbortController.prototype, "signal");
  const addListener = win.EventTarget.prototype.addEventListener as Fn;
  const removeListener = win.EventTarget.prototype.removeEventListener as Fn;
  const eventSource = getter(win.MessageEvent?.prototype, "source");
  const eventData = getter(win.MessageEvent?.prototype, "data");
  const postMessage = win.postMessage as Fn;
  const setTimer = win.setTimeout as Fn;
  const doc = win.document;
  const hasFocus = win.Document?.prototype.hasFocus as Fn | undefined;
  const classes = {
    PublicKeyCredential,
    AuthenticatorAttestationResponse: win.AuthenticatorAttestationResponse,
    AuthenticatorAssertionResponse: win.AuthenticatorAssertionResponse,
  };

  // region Bridge

  const calls = create(null) as Record<number, Call>;
  let nextId = 0;
  let ready = false;
  /** Whether a ping is out. The first call sends it, not the start, which every page would see. */
  let pinged = false;

  const postJson = (json: string) => apply(postMessage, win, [{ tag: BRIDGE_TAG, to: "isolated", json }, "*"]);
  const post = (message: MainMessage) => postJson(stringify(message));

  function send(entry: Call) {
    const id = entry.message.id;
    const json = stringify(entry.message);
    // The isolated script drops it without an answer: the browser takes it.
    if (json.length > MAX_REQUEST_LENGTH) {
      delete calls[id];
      entry.done(FALLBACK);
      return;
    }
    entry.sent = true;
    postJson(json);
    apply(setTimer, win, [
      () => {
        if (calls[id] !== entry || entry.acked) return;
        // An isolated script orphaned by an update of the extension: it may
        // never answer. A later call asks for one again; a slow one drops this.
        delete calls[id];
        ready = false;
        pinged = false;
        post({ kind: "abort", id });
        entry.done(FALLBACK);
      },
      timeoutMs,
    ]);
  }

  /** Sends a request to the isolated script; [done] gets its result, or a fallback without one. */
  function call(request: PageRequest, done: Call["done"]): number {
    const id = ++nextId;
    const entry: Call = { message: { ...request, id }, sent: false, acked: false, done };
    calls[id] = entry;
    if (ready) {
      send(entry);
    } else {
      if (!pinged) {
        pinged = true;
        post({ kind: "ping" });
      }
      apply(setTimer, win, [
        () => {
          if (calls[id] !== entry || entry.sent) return;
          delete calls[id];
          // No answer: a later call asks again.
          pinged = false;
          done(FALLBACK);
        },
        timeoutMs,
      ]);
    }
    return id;
  }

  /** Drops a request: the isolated script disconnects its port, which cancels it in the background. */
  function cancel(id: number) {
    const entry = calls[id];
    if (entry === undefined) return;
    delete calls[id];
    if (entry.sent) post({ kind: "abort", id });
  }

  apply(addListener, win, [
    "message",
    (event: MessageEvent) => {
      if ((eventSource ? apply(eventSource, event, []) : event.source) !== win) return;
      const data = eventData ? apply(eventData, event, []) : event.data;
      if (!isRecord(data) || data.tag !== BRIDGE_TAG || data.to !== "main" || typeof data.json !== "string") return;
      let message: IsolatedMessage;
      try {
        message = parse(data.json) as IsolatedMessage;
      } catch {
        return;
      }
      if (message?.kind === "ready") {
        if (ready) return;
        ready = true;
        for (const id in calls) if (!calls[id]!.sent) send(calls[id]!);
      } else if (message?.kind === "ack") {
        const entry = calls[message.id];
        if (entry !== undefined && entry.sent) entry.acked = true;
      } else if (message?.kind === "result") {
        const entry = calls[message.id];
        if (entry === undefined || !entry.sent) return;
        delete calls[message.id];
        entry.done(message.result);
      }
    },
    true,
  ]);

  // endregion

  // region Requests

  function domError(name: WebAuthnErrorName): DOMException {
    return new NativeDOMException(
      ERROR_MESSAGES[name] ?? ERROR_MESSAGES.NotAllowedError,
      ERROR_MESSAGES[name] ? name : "NotAllowedError",
    );
  }

  function abortReason(signal: AbortSignal): unknown {
    return signal.reason === undefined ? domError("AbortError") : signal.reason;
  }

  /** Listens for [signal]'s abort; returns a function that stops listening. */
  function onAbort(signal: AbortSignal | undefined, listener: () => void): () => void {
    if (!isRecord(signal)) return () => undefined;
    apply(addListener, signal, ["abort", listener]);
    return () => apply(removeListener, signal, ["abort", listener]);
  }

  /** Whether the document has focus, which the browser's own modal requests need. */
  function focused(): boolean {
    return typeof hasFocus !== "function" || apply(hasFocus, doc, []) === true;
  }

  /**
   * Calls [callback] once the document has focus, which the browser's own
   * request needs; returns a function that stops waiting.
   */
  function whenFocused(callback: () => void): () => void {
    if (focused()) {
      callback();
      return () => undefined;
    }
    const listener = () => {
      if (!focused()) return;
      apply(removeListener, win, ["focus", listener]);
      callback();
    };
    apply(addListener, win, ["focus", listener]);
    return () => apply(removeListener, win, ["focus", listener]);
  }

  function thenOf(value: unknown, fulfilled: (value: unknown) => void, rejected: (error: unknown) => void) {
    void apply(promiseThen, apply(promiseResolve, NativePromise, [value]), [fulfilled, rejected]);
  }

  function credentialOf(result: WebAuthnResult | undefined, kind: "create" | "get"): PublicKeyCredential | null {
    if (result?.kind !== "credential") return null;
    try {
      return credentialFromJSON(result.credential, kind, classes);
    } catch {
      return null;
    }
  }

  /**
   * A modal `get` or a `create`: Keyguard's answer, or the browser's when
   * Keyguard passes. A document without focus gets the browser's at once,
   * which refuses it: Keyguard prompts for the page the user is on only.
   */
  function modal(
    original: Fn,
    thisArg: unknown,
    args: unknown[],
    request: Extract<PageRequest, { kind: "get" | "create" }>,
    signal: AbortSignal | undefined,
  ) {
    if (!focused()) return apply(original, thisArg, args);
    return new NativePromise((resolve, reject) => {
      let settled = false;
      let id = 0;
      let stopWaiting: () => void = () => undefined;
      const stop = onAbort(signal, () => {
        if (settled) return;
        settled = true;
        stop();
        stopWaiting();
        cancel(id);
        reject(abortReason(signal!));
      });
      id = call(request, (result) => {
        if (settled) return;
        if (result?.kind !== "credential" && result?.kind !== "error") {
          // The browser takes over, with the page's own signal, once the page
          // has focus again: Keyguard's prompt may have taken it, and the
          // browser refuses a document without focus.
          stopWaiting = whenFocused(() => {
            if (settled) return;
            settled = true;
            stop();
            try {
              resolve(apply(original, thisArg, args));
            } catch (error) {
              reject(error);
            }
          });
          return;
        }
        settled = true;
        stop();
        if (result.kind === "credential") {
          const credential = credentialOf(result, request.kind);
          if (credential === null) reject(domError("NotAllowedError"));
          else resolve(credential);
        } else {
          reject(domError(result.name));
        }
      });
    });
  }

  /** A conditional `get`: Keyguard's inline menu and the browser's own UI, whichever the user picks from. */
  function conditional(original: Fn, thisArg: unknown, options: Json, json: Json) {
    const signal = options.signal as AbortSignal | undefined;
    return new NativePromise((resolve, reject) => {
      let settled = false;
      let id = 0;
      let keyguardDone = false;
      let browserError: { error: unknown } | null = null;
      const internal = new NativeAbortController();
      const abortBrowser = () => apply(abortController, internal, []);
      const settle = () => {
        if (settled) return false;
        settled = true;
        stop();
        return true;
      };
      const stop = onAbort(signal, () => {
        if (!settle()) return;
        cancel(id);
        abortBrowser();
        reject(abortReason(signal!));
      });
      let browser: unknown;
      try {
        const internalSignal = controllerSignal ? apply(controllerSignal, internal, []) : internal.signal;
        browser = apply(original, thisArg, [{ ...options, signal: internalSignal }]);
      } catch (error) {
        browserError = { error };
      }
      if (browserError === null) {
        thenOf(
          browser,
          (credential) => {
            if (!settle()) return;
            cancel(id);
            resolve(credential);
          },
          (error) => {
            browserError = { error };
            if (keyguardDone && settle()) reject(error);
          },
        );
      }
      id = call({ kind: "get", options: json, mediation: "conditional" }, (result) => {
        if (settled) return;
        const credential = credentialOf(result, "get");
        if (credential !== null) {
          settle();
          abortBrowser();
          resolve(credential);
          return;
        }
        // Keyguard has nothing for this request: the browser's answer counts.
        keyguardDone = true;
        if (browserError !== null && settle()) reject(browserError.error);
      });
    });
  }

  /** Feature checks: the browser's `true`, or else whether Keyguard answers. */
  function available(done: (available: boolean) => void) {
    call({ kind: "available" }, (result) => done(result?.kind === "available" && result.available === true));
  }

  function orKeyguard(original: Fn, thisArg: unknown, args: unknown[]) {
    return new NativePromise((resolve) => {
      let browser: unknown;
      try {
        browser = apply(original, thisArg, args);
      } catch {
        browser = false;
      }
      thenOf(
        browser,
        (value) => (value === true ? resolve(true) : available(resolve)),
        () => available(resolve),
      );
    });
  }

  // endregion

  // region Wrappers

  function wrap(target: object, name: string, handler: Handler) {
    const descriptor = getOwnPropertyDescriptor(target, name);
    if (descriptor === undefined || typeof descriptor.value !== "function") return;
    // A handler without a prototype: traps the page adds to Object.prototype don't apply.
    const traps = create(null) as ProxyHandler<Fn>;
    traps.apply = (original, thisArg, args) => handler(original, thisArg, args);
    defineProperty(target, name, { ...descriptor, value: new NativeProxy(descriptor.value as Fn, traps) });
  }

  const otherTypes = (options: Json) =>
    options.password !== undefined ||
    options.federated !== undefined ||
    options.identity !== undefined ||
    options.otp !== undefined ||
    options.digital !== undefined;

  wrap(Container.prototype, "get", (original, thisArg, args) => {
    const options = args[0];
    if (thisArg !== credentials || !isRecord(options) || options.publicKey === undefined || otherTypes(options)) {
      return apply(original, thisArg, args);
    }
    const mediation = options.mediation === undefined ? "optional" : options.mediation;
    const signal = options.signal as AbortSignal | undefined;
    const known =
      mediation === "silent" || mediation === "optional" || mediation === "conditional" || mediation === "required";
    let json: Json;
    try {
      if (!known || signal?.aborted === true) return apply(original, thisArg, args);
      json = requestOptionsToJSON(options.publicKey);
    } catch {
      // Options Keyguard can't read: the browser reports them as it would.
      return apply(original, thisArg, args);
    }
    return mediation === "conditional"
      ? conditional(original, thisArg, options, json)
      : modal(original, thisArg, args, { kind: "get", options: json, mediation }, signal);
  });

  wrap(Container.prototype, "create", (original, thisArg, args) => {
    const options = args[0];
    if (thisArg !== credentials || !isRecord(options) || options.publicKey === undefined || otherTypes(options)) {
      return apply(original, thisArg, args);
    }
    // Conditional creation (passkey upgrades) stays the browser's.
    const mediation = options.mediation;
    const signal = options.signal as AbortSignal | undefined;
    let json: Json;
    try {
      if (
        (mediation !== undefined && mediation !== "optional" && mediation !== "required") ||
        signal?.aborted === true
      ) {
        return apply(original, thisArg, args);
      }
      json = creationOptionsToJSON(options.publicKey);
    } catch {
      return apply(original, thisArg, args);
    }
    return modal(original, thisArg, args, { kind: "create", options: json }, signal);
  });

  wrap(PublicKeyCredential, "isConditionalMediationAvailable", orKeyguard);
  wrap(PublicKeyCredential, "isUserVerifyingPlatformAuthenticatorAvailable", orKeyguard);
  wrap(
    PublicKeyCredential,
    "getClientCapabilities",
    (original, thisArg, args) =>
      new NativePromise((resolve, reject) => {
        let browser: unknown;
        try {
          browser = apply(original, thisArg, args);
        } catch (error) {
          reject(error);
          return;
        }
        thenOf(
          browser,
          (capabilities) =>
            available((yes) =>
              resolve(
                yes && isRecord(capabilities)
                  ? {
                      ...capabilities,
                      conditionalGet: true,
                      passkeyPlatformAuthenticator: true,
                      userVerifyingPlatformAuthenticator: true,
                    }
                  : capabilities,
              ),
            ),
          reject,
        );
      }),
  );

  // endregion
  return true;
}
