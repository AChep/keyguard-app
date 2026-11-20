// The messages of passkey requests between the three parts of the extension:
//
// - main world (./page.ts) ⇄ isolated content script (../content/webauthnBridge.ts):
//   `window.postMessage` of an [Envelope] with JSON text. The isolated script
//   takes only messages whose source is its own window, and answers only that
//   window. The page sees these messages: they carry only what the page passed
//   in and what it gets back.
// - isolated content script ⇄ background (../background/passkeys.ts): a
//   `runtime.Port` named [WEBAUTHN_PORT] per request. The first message is a
//   [WebAuthnRequest], the answer one [WebAuthnResult]. Disconnecting cancels:
//   the page aborted, the frame navigated, or the tab closed.
//
// The background takes the origin from the port's sender, never from these
// messages.
import type { JsonObject } from "../protocol/framing";
import type { PasskeyMediation } from "../protocol/messages";

/** The `tag` of envelopes; other messages on the window are someone else's. */
export const BRIDGE_TAG = "keyguard-webauthn";
export const WEBAUTHN_PORT = "webauthn";
/** The longest JSON text of an envelope or request, in characters. */
export const MAX_REQUEST_LENGTH = 64 * 1024;

export interface Envelope {
  tag: typeof BRIDGE_TAG;
  /** The world it's for: the other side sees its own messages too. */
  to: "main" | "isolated";
  json: string;
}

export const MEDIATIONS: readonly PasskeyMediation[] = ["silent", "optional", "conditional", "required"];

/** A page's request, by the main world. `options` is WebAuthn's JSON form. */
export type PageRequest =
  | { kind: "available" }
  | { kind: "get"; options: JsonObject; mediation: PasskeyMediation }
  | { kind: "create"; options: JsonObject };

/** The main world's messages to the isolated script. */
export type MainMessage =
  /** Asks the isolated script to say it's there, on the first request. */
  | { kind: "ping" }
  | (PageRequest & { id: number })
  /** The page aborted, or the browser answered first. */
  | { kind: "abort"; id: number };

export type WebAuthnErrorName = "NotAllowedError" | "InvalidStateError" | "SecurityError" | "AbortError" | "NotSupportedError";

export const ERROR_NAMES: readonly WebAuthnErrorName[] = [
  "NotAllowedError",
  "InvalidStateError",
  "SecurityError",
  "AbortError",
  "NotSupportedError",
];

export type WebAuthnResult =
  /** Whether Keyguard answers passkey requests of this frame now. */
  | { kind: "available"; available: boolean }
  /** `RegistrationResponseJSON` or `AuthenticationResponseJSON`. */
  | { kind: "credential"; credential: JsonObject }
  /** The browser handles the request, as without Keyguard. */
  | { kind: "fallback" }
  | { kind: "error"; name: WebAuthnErrorName };

/** The isolated script's messages to the main world: answers only. */
export type IsolatedMessage =
  /** The answer to a ping. */
  | { kind: "ready" }
  /**
   * The isolated script has request [id], at once: one orphaned by an update
   * of the extension never says so, and the main world hands the request to
   * the browser.
   */
  | { kind: "ack"; id: number }
  | { kind: "result"; id: number; result: WebAuthnResult };

/** The request on a [WEBAUTHN_PORT]; `same_origin_with_ancestors` comes from the isolated script. */
export type WebAuthnRequest =
  | { kind: "available" }
  | { kind: "get"; options: JsonObject; mediation: PasskeyMediation; same_origin_with_ancestors: boolean }
  | { kind: "create"; options: JsonObject; same_origin_with_ancestors: boolean };

// region Validation

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isMediation(value: unknown): value is PasskeyMediation {
  return typeof value === "string" && (MEDIATIONS as readonly string[]).includes(value);
}

/** The JSON of an envelope for [to], or `null` for anything else. */
export function openEnvelope(data: unknown, to: Envelope["to"]): unknown {
  if (!isObject(data) || data.tag !== BRIDGE_TAG || data.to !== to) return null;
  const json = data.json;
  if (typeof json !== "string" || json.length > MAX_REQUEST_LENGTH) return null;
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
}

/** A page request with its fields checked, or `null`. */
export function parsePageRequest(message: unknown): PageRequest | null {
  if (!isObject(message)) return null;
  switch (message.kind) {
    case "available":
      return { kind: "available" };
    case "get":
      return isObject(message.options) && isMediation(message.mediation)
        ? { kind: "get", options: message.options as JsonObject, mediation: message.mediation }
        : null;
    case "create":
      return isObject(message.options) ? { kind: "create", options: message.options as JsonObject } : null;
    default:
      return null;
  }
}

/** A main-world message for the isolated script, or `null`. */
export function parseMainMessage(message: unknown): MainMessage | null {
  if (!isObject(message)) return null;
  if (message.kind === "ping") return { kind: "ping" };
  if (!isId(message.id)) return null;
  if (message.kind === "abort") return { kind: "abort", id: message.id };
  const request = parsePageRequest(message);
  return request === null ? null : { ...request, id: message.id };
}

/** The request of a [WEBAUTHN_PORT], checked: it comes from a content script. */
export function parseWebAuthnRequest(message: unknown): WebAuthnRequest | null {
  if (!isObject(message)) return null;
  const request = parsePageRequest(message);
  if (request === null) return null;
  if (request.kind === "available") return request;
  if (typeof message.same_origin_with_ancestors !== "boolean") return null;
  if (JSON.stringify(request.options).length > MAX_REQUEST_LENGTH) return null;
  return { ...request, same_origin_with_ancestors: message.same_origin_with_ancestors };
}

export function parseWebAuthnResult(message: unknown): WebAuthnResult | null {
  if (!isObject(message)) return null;
  switch (message.kind) {
    case "available":
      return typeof message.available === "boolean" ? { kind: "available", available: message.available } : null;
    case "credential":
      return isObject(message.credential) ? { kind: "credential", credential: message.credential as JsonObject } : null;
    case "fallback":
      return { kind: "fallback" };
    case "error":
      return typeof message.name === "string" && (ERROR_NAMES as readonly string[]).includes(message.name)
        ? { kind: "error", name: message.name as WebAuthnErrorName }
        : null;
    default:
      return null;
  }
}

// endregion
