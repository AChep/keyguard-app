// The requests, responses and events of protocol/README.md §4–§6 and §9.
import { isObject } from "../util/json";
import type { JsonObject } from "./framing";

/** The protocol versions this extension speaks; `hello` offers [max]. */
export const PROTOCOL_VERSIONS = { min: 1, max: 1 } as const;

/**
 * The app's answer to `hello`: `ok` within [versions]; `app_outdated` below
 * them; `invalid` above them, which the app never answers, as it picks the
 * lower of the two highest versions.
 */
export type VersionCheck = { kind: "ok"; version: number } | { kind: "app_outdated" } | { kind: "invalid" };

export function checkVersion(answered: number, versions: { min: number; max: number } = PROTOCOL_VERSIONS): VersionCheck {
  if (answered > versions.max) return { kind: "invalid" };
  if (answered < versions.min) return { kind: "app_outdated" };
  return { kind: "ok", version: answered };
}

export type VaultState = "locked" | "unlocked";
export type Capability = "passkeys" | "save";

/** The optional features this extension implements, offered in every `hello`. */
export const CAPABILITIES: readonly Capability[] = ["passkeys", "save"];

/** The error codes of §9. Unknown codes are failures without a retry. */
export type ProtocolErrorCode =
  | "vault_locked"
  | "unpaired"
  | "update_required"
  | "user_denied"
  | "approval_timeout"
  | "not_allowed"
  | "security"
  | "not_supported"
  /** A passkey of `excludeCredentials` is already in the vault. */
  | "invalid_state"
  | "invalid_request"
  | "too_large"
  | "busy"
  /** The extension cancelled the request. */
  | "cancelled";

export interface HelloRequest {
  protocol: number;
  capabilities: readonly Capability[];
}

export interface HelloResponse {
  protocol: number;
  capabilities: string[];
  paired: boolean;
  vault: VaultState;
}

export interface PairCommitRequest {
  /** Base64 of 32 bytes. */
  commit: string;
  /** 1 to 64 characters. */
  name: string;
}

export interface PairCommitResponse {
  /** Base64 of the app's 32-byte nonce. */
  nonce: string;
}

export interface PairRevealRequest {
  /** Base64 of the extension's 32-byte nonce. */
  nonce: string;
}

/** `cancel`: stops the request in flight with the `id` of [target]. Answered with no fields. */
export interface CancelRequest {
  target: number;
}

/** The answer to `pair_reveal` and `unlock`. */
export interface VaultResponse {
  vault: VaultState;
}

/**
 * The field of the requests that name a page: `private_window` is `true` for
 * a page in a private window, such as an incognito one, and absent otherwise.
 * The app then stores no browsing data of the request (protocol/README.md,
 * "Private windows").
 */
export interface PagePrivacy {
  private_window?: boolean;
}

/** The [PagePrivacy] of a page: in a private window or not. */
export function privateWindow(isPrivate: boolean | undefined): PagePrivacy {
  return isPrivate === true ? { private_window: true } : {};
}

export type SuggestKind = "login" | "passkey";

export interface SuggestRequest extends PagePrivacy {
  url: string;
  kinds?: SuggestKind[];
  rp_id?: string;
}

export interface SuggestPasskey {
  handle: string;
  user_name: string;
  user_display_name?: string;
}

export interface SuggestItem {
  handle: string;
  name: string;
  username?: string;
  account: string;
  has_password: boolean;
  has_totp: boolean;
  reprompt: boolean;
  passkeys?: SuggestPasskey[];
  /** On the first item only, when it's the login used last on this device. */
  last_used?: boolean;
  /** The item's collections hide its password: the popup doesn't copy it. A fill still releases it. */
  password_hidden?: boolean;
}

/** Whether the popup may copy the password of [item]: it has one, and its collections don't hide it. */
export function copiesPassword(item: Pick<SuggestItem, "has_password" | "password_hidden">): boolean {
  return item.has_password && item.password_hidden !== true;
}

export interface SuggestResponse {
  blocked: boolean;
  /** Saving is blocked on the page; true whenever [blocked] is. */
  save_blocked: boolean;
  truncated: boolean;
  items: SuggestItem[];
}

/** A `search` query takes 2 to 100 code points, once trimmed. */
export const SEARCH_QUERY_MIN = 2;
export const SEARCH_QUERY_MAX = 100;

/** [text] trimmed as a `search` query, or `null` when it's too short to send. */
export function searchQuery(text: string): string | null {
  const query = text.trim();
  return Array.from(query).length >= SEARCH_QUERY_MIN ? query : null;
}

export interface SearchRequest extends PagePrivacy {
  query: string;
  /** The page, as for `suggest`. */
  url: string;
}

/** A login of `search`: `matched` is false when it isn't saved for the page's site. */
export interface SearchItem extends Omit<SuggestItem, "passkeys" | "last_used"> {
  matched: boolean;
}

export interface SearchResponse {
  blocked: boolean;
  truncated: boolean;
  items: SearchItem[];
}

/** The page of a `fill` or `totp`. */
export interface FramePage extends PagePrivacy {
  url: string;
  /** The origin of the tab's top frame, when it isn't the origin of `url`. */
  top_origin?: string;
}

export interface HandleRequest extends FramePage {
  handle: string;
}

export interface FillResponse {
  username?: string;
  password?: string;
}

/** The values of a `fill` response, without the response's `id` and `ok`. */
export function fillValues(response: FillResponse): FillResponse {
  return {
    ...(response.username !== undefined ? { username: response.username } : {}),
    ...(response.password !== undefined ? { password: response.password } : {}),
  };
}

export interface TotpResponse {
  code: string;
}

export interface GenerateRequest extends PagePrivacy {
  url?: string;
}

export interface GenerateResponse {
  password: string;
}

export interface SaveRequest extends PagePrivacy {
  url: string;
  username?: string;
  password?: string;
  previous_password?: string;
}

/** `never_save`: the app blocks saving on the page's site. Answered with no fields. */
export interface NeverSaveRequest extends PagePrivacy {
  url: string;
}

export type PasskeyMediation = "silent" | "optional" | "conditional" | "required";

export interface PasskeyGetRequest extends PagePrivacy {
  origin: string;
  options: JsonObject;
  mediation: PasskeyMediation;
  same_origin_with_ancestors: boolean;
  handle?: string;
}

export interface PasskeyCreateRequest extends PagePrivacy {
  origin: string;
  options: JsonObject;
  same_origin_with_ancestors: boolean;
}

export interface PasskeyResponse {
  /** `AuthenticationResponseJSON` or `RegistrationResponseJSON`. */
  credential: JsonObject;
}

/** Each request type with its fields and its response. */
export interface Requests {
  hello: [HelloRequest, HelloResponse];
  pair_commit: [PairCommitRequest, PairCommitResponse];
  pair_reveal: [PairRevealRequest, VaultResponse];
  unlock: [Record<string, never>, VaultResponse];
  suggest: [SuggestRequest, SuggestResponse];
  search: [SearchRequest, SearchResponse];
  fill: [HandleRequest, FillResponse];
  totp: [HandleRequest, TotpResponse];
  generate: [GenerateRequest, GenerateResponse];
  save: [SaveRequest, Record<string, never>];
  never_save: [NeverSaveRequest, Record<string, never>];
  passkey_get: [PasskeyGetRequest, PasskeyResponse];
  passkey_create: [PasskeyCreateRequest, PasskeyResponse];
}

export type RequestType = keyof Requests;
export type RequestOf<T extends RequestType> = Requests[T][0];
export type ResponseOf<T extends RequestType> = Requests[T][1];

export type ProtocolEvent = { event: "vault"; state: VaultState } | { event: "revoked" } | { event: "disabled" };

// region Validation
//
// The app is authenticated, but a response is still checked before use, so a
// broken app fails with a protocol error instead of a TypeError later.

/** A response that doesn't have the shape of §5. */
export class InvalidResponse extends Error {
  constructor(type: string) {
    super(`The response to ${type} is malformed`);
    this.name = "InvalidResponse";
  }
}

type Check = (value: unknown) => boolean;

const isString: Check = (value) => typeof value === "string";
const isBoolean: Check = (value) => typeof value === "boolean";
const isInteger: Check = (value) => Number.isSafeInteger(value);
const optional =
  (check: Check): Check =>
  (value) =>
    value === undefined || check(value);
const arrayOf =
  (check: Check): Check =>
  (value) =>
    Array.isArray(value) && value.every(check);
const shape =
  (fields: Record<string, Check>): Check =>
  (value) =>
    isObject(value) && Object.entries(fields).every(([name, check]) => check(value[name]));

const isPasskey = shape({ handle: isString, user_name: isString, user_display_name: optional(isString) });
const ITEM_FIELDS = {
  handle: isString,
  name: isString,
  username: optional(isString),
  account: isString,
  has_password: isBoolean,
  has_totp: isBoolean,
  reprompt: isBoolean,
  password_hidden: optional(isBoolean),
};
const isItem = shape({ ...ITEM_FIELDS, passkeys: optional(arrayOf(isPasskey)), last_used: optional(isBoolean) });
const isSearchItem = shape({ ...ITEM_FIELDS, matched: isBoolean });

const RESPONSE_SHAPES: { [T in RequestType]: Check } = {
  hello: shape({ protocol: isInteger, capabilities: arrayOf(isString), paired: isBoolean, vault: isString }),
  pair_commit: shape({ nonce: isString }),
  pair_reveal: shape({ vault: isString }),
  unlock: shape({ vault: isString }),
  suggest: shape({ blocked: isBoolean, save_blocked: isBoolean, truncated: isBoolean, items: arrayOf(isItem) }),
  search: shape({ blocked: isBoolean, truncated: isBoolean, items: arrayOf(isSearchItem) }),
  fill: shape({ username: optional(isString), password: optional(isString) }),
  totp: shape({ code: isString }),
  generate: shape({ password: isString }),
  save: shape({}),
  never_save: shape({}),
  passkey_get: shape({ credential: isObject }),
  passkey_create: shape({ credential: isObject }),
};

/** A vault state; an unknown one is `locked`, so the app may add states. */
function vaultState(value: string): VaultState {
  return value === "unlocked" ? "unlocked" : "locked";
}

/** Checks the fields of a successful response and returns it typed. */
export function parseResponse<T extends RequestType>(type: T, message: JsonObject): ResponseOf<T> {
  if (!RESPONSE_SHAPES[type](message)) throw new InvalidResponse(type);
  const response = typeof message.vault === "string" ? { ...message, vault: vaultState(message.vault) } : message;
  return response as unknown as ResponseOf<T>;
}

/** Returns a known event, or `null` for one to ignore. */
export function parseEvent(message: JsonObject): ProtocolEvent | null {
  switch (message.event) {
    case "vault":
      return typeof message.state === "string" ? { event: "vault", state: vaultState(message.state) } : null;
    case "revoked":
      return { event: "revoked" };
    case "disabled":
      return { event: "disabled" };
    default:
      return null;
  }
}

// endregion
