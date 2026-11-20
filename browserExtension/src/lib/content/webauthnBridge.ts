// The isolated world's side of passkey requests
// (entrypoints/webauthn-bridge.content.ts): relays the main world's requests
// (../webauthn/page.ts) to the background over one port each, and the answers
// back. See ../webauthn/bridge.ts for the messages.
//
// - Only messages whose source is this window count: other frames can post to
//   it too.
// - Answers go to this window only, at its origin. It posts nothing unasked:
//   a page that never calls WebAuthn sees no message. Each request gets an
//   `ack` at once, then its result.
// - `same_origin_with_ancestors` is computed here, never taken from the main
//   world; the background takes the origin from the port's sender.
// - A modal request of a document without focus goes to the browser, which
//   refuses it, here too: the page can post requests itself, past the main
//   world's check (../webauthn/page.ts). Conditional ones wait for a pick in
//   the inline menu, which needs focus anyway.
import {
  BRIDGE_TAG,
  openEnvelope,
  parseMainMessage,
  parseWebAuthnResult,
  type IsolatedMessage,
  type PageRequest,
  type WebAuthnRequest,
  type WebAuthnResult,
} from "../webauthn/bridge";
import { isOpaqueOrigin, ownOrigin } from "./guards";

/** The most requests of one frame in flight. */
export const MAX_REQUESTS = 8;

/** The parts of `runtime.Port` the bridge uses. */
export interface BridgePort {
  postMessage(message: unknown): void;
  disconnect(): void;
  onMessage: { addListener(callback: (message: unknown) => void): void };
  onDisconnect: { addListener(callback: () => void): void };
}

export interface BridgeDeps {
  win: Window;
  /** `runtime.connect` with the passkeys port's name. */
  connect(): BridgePort;
  signal: AbortSignal;
}

/**
 * Whether this frame's origin is the origin of each of its ancestors, as
 * WebAuthn's `sameOriginWithAncestors`: Chromium lists them in
 * `location.ancestorOrigins`; elsewhere a parent of another origin can't be
 * read.
 */
export function sameOriginWithAncestors(win: Window): boolean {
  if (win.top === win) return true;
  const origin = ownOrigin(win);
  const ancestors = (win.location as Location & { ancestorOrigins?: DOMStringList }).ancestorOrigins;
  if (ancestors !== undefined && ancestors !== null) {
    for (let i = 0; i < ancestors.length; i++) if (ancestors[i] !== origin) return false;
    return true;
  }
  for (let current: Window = win; current.top !== current; ) {
    const parent: Window = current.parent;
    try {
      if (ownOrigin(parent) !== origin) return false;
    } catch {
      return false;
    }
    current = parent;
  }
  return true;
}

function noAnswer(request: PageRequest): WebAuthnResult {
  return request.kind === "available" ? { kind: "available", available: false } : { kind: "fallback" };
}

/** Whether [request] shows a prompt at once: a `create`, or a `get` that isn't conditional. */
function isModal(request: PageRequest): boolean {
  return request.kind === "create" || (request.kind === "get" && request.mediation !== "conditional");
}

export function startWebAuthnBridge(deps: BridgeDeps): void {
  const { win, signal } = deps;
  // A sandboxed frame isn't the origin its URL says (CVE-2025-65203): the
  // browser handles its requests.
  const opaque = isOpaqueOrigin(win);
  const targetOrigin = opaque ? "*" : ownOrigin(win);
  /** The requests in flight, by the main world's ID: each ends with one answer. */
  const requests = new Map<number, { port: BridgePort; finish(result: WebAuthnResult): void }>();

  const post = (message: IsolatedMessage) => {
    try {
      win.postMessage({ tag: BRIDGE_TAG, to: "main", json: JSON.stringify(message) }, targetOrigin);
    } catch {
      // The frame navigated to another origin.
    }
  };
  const answer = (id: number, result: WebAuthnResult) => post({ kind: "result", id, result });

  function forward(id: number, request: PageRequest) {
    // `hasFocus` of the isolated world, which the page can't replace.
    if (opaque || requests.has(id) || requests.size >= MAX_REQUESTS || (isModal(request) && !win.document.hasFocus())) {
      answer(id, noAnswer(request));
      return;
    }
    let port: BridgePort;
    try {
      port = deps.connect();
    } catch {
      // The extension was updated or removed since this script started.
      answer(id, noAnswer(request));
      return;
    }
    const finish = (result: WebAuthnResult) => {
      if (requests.get(id)?.port !== port) return;
      requests.delete(id);
      answer(id, result);
      port.disconnect();
    };
    requests.set(id, { port, finish });
    port.onMessage.addListener((message) => {
      const result = parseWebAuthnResult(message);
      if (result !== null) finish(result);
    });
    port.onDisconnect.addListener(() => finish(noAnswer(request)));
    const message: WebAuthnRequest =
      request.kind === "available" ? request : { ...request, same_origin_with_ancestors: sameOriginWithAncestors(win) };
    port.postMessage(message);
  }

  win.addEventListener(
    "message",
    (event) => {
      if (event.source !== win) return;
      const message = parseMainMessage(openEnvelope(event.data, "isolated"));
      if (message === null) return;
      switch (message.kind) {
        case "ping":
          post({ kind: "ready" });
          return;
        case "abort": {
          // The main world settled already; the disconnect cancels in the background.
          const request = requests.get(message.id);
          requests.delete(message.id);
          request?.port.disconnect();
          return;
        }
        default: {
          const { id, ...request } = message;
          // Before anything else: the main world waits only so long for it.
          post({ kind: "ack", id });
          forward(id, request);
        }
      }
    },
    { capture: true, signal },
  );
  // The extension was updated or removed: the browser takes what's in flight.
  signal.addEventListener("abort", () => {
    for (const request of [...requests.values()]) request.finish({ kind: "fallback" });
  });
}
