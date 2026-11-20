// WebAuthn's JSON forms (Level 3, §5.1): the options a page passes to
// `navigator.credentials`, as `PublicKeyCredentialCreationOptionsJSON` and
// `PublicKeyCredentialRequestOptionsJSON` for the app, and the app's
// `RegistrationResponseJSON` / `AuthenticationResponseJSON` back as objects
// shaped like the browser's `PublicKeyCredential`.
//
// It runs in the page's main world, where page scripts may replace globals
// later: the built-ins it calls are captured when the script loads, at
// document_start, before any page script runs. Options of an unexpected shape
// throw a TypeError; the caller then lets the browser handle the call, which
// reports the error as it would without Keyguard.
type Json = Record<string, unknown>;

const { apply } = Reflect;
const { create, defineProperty, keys } = Object;
const { isArray } = Array;
const { isView } = ArrayBuffer;
const NativeUint8Array = Uint8Array;
const NativeTypeError = TypeError;
const objectToString = Object.prototype.toString;
const charCodeAt = String.prototype.charCodeAt;
const sliceBuffer = ArrayBuffer.prototype.slice;
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
/** Extension inputs nest a few levels: `prf.evalByCredential.<id>.first`. */
const MAX_DEPTH = 8;
/** The `authenticatorAttachment` of the credentials Keyguard returns. */
const PLATFORM = "platform";

// region Base64url

/** Base64url without padding, as WebAuthn's JSON uses. */
export function toBase64Url(bytes: Uint8Array): string {
  let out = "";
  let i = 0;
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i]! << 16) | (bytes[i + 1]! << 8) | bytes[i + 2]!;
    out += ALPHABET[n >> 18]! + ALPHABET[(n >> 12) & 63]! + ALPHABET[(n >> 6) & 63]! + ALPHABET[n & 63]!;
  }
  const rest = bytes.length - i;
  if (rest > 0) {
    const n = (bytes[i]! << 16) | (rest === 2 ? bytes[i + 1]! << 8 : 0);
    out += ALPHABET[n >> 18]! + ALPHABET[(n >> 12) & 63]!;
    if (rest === 2) out += ALPHABET[(n >> 6) & 63]!;
  }
  return out;
}

function base64UrlValue(code: number): number {
  if (code >= 65 && code <= 90) return code - 65;
  if (code >= 97 && code <= 122) return code - 71;
  if (code >= 48 && code <= 57) return code + 4;
  if (code === 45) return 62;
  if (code === 95) return 63;
  return -1;
}

/** Decodes base64url, with or without padding; `null` for anything else. */
export function fromBase64Url(text: string): Uint8Array | null {
  let length = text.length;
  while (length > 0 && apply(charCodeAt, text, [length - 1]) === 61) length--;
  if (length % 4 === 1 || text.length - length > 2) return null;
  const out = new NativeUint8Array((length * 3) >> 2);
  let buffer = 0;
  let bits = 0;
  let j = 0;
  for (let i = 0; i < length; i++) {
    const value = base64UrlValue(apply(charCodeAt, text, [i]) as number);
    if (value < 0) return null;
    buffer = ((buffer << 6) | value) & 0xffffff;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[j++] = (buffer >> bits) & 255;
    }
  }
  return out;
}

// endregion

// region Options

function fail(what: string): never {
  throw new NativeTypeError(`Keyguard can't read ${what}`);
}

function record(value: unknown, what: string): Json {
  if (typeof value !== "object" || value === null || isArray(value)) fail(what);
  return value as Json;
}

function string(value: unknown, what: string): string {
  if (typeof value !== "string") fail(what);
  return value;
}

function number(value: unknown, what: string): number {
  if (typeof value !== "number" || value !== value) fail(what);
  return value;
}

/**
 * A WebIDL `long`, converted as the browser does: KeycloakJS passes `alg` as
 * `"-7"`. Unary plus is ToNumber; `| 0` is ToInt32: NaN and infinities are 0,
 * the rest truncates and wraps to 32 bits.
 */
function long(value: unknown, what: string): number {
  if (value === undefined) fail(what);
  return +(value as number) | 0;
}

function boolean(value: unknown, what: string): boolean {
  if (typeof value !== "boolean") fail(what);
  return value;
}

/** The bytes of a BufferSource: an ArrayBuffer or a view of one, of any realm. */
function bytesOf(value: unknown, what: string): Uint8Array {
  if (isView(value)) return new NativeUint8Array(value.buffer, value.byteOffset, value.byteLength);
  if (apply(objectToString, value, []) === "[object ArrayBuffer]") return new NativeUint8Array(value as ArrayBuffer);
  return fail(what);
}

function base64Url(value: unknown, what: string): string {
  return toBase64Url(bytesOf(value, what));
}

function list<T>(value: unknown, what: string, item: (value: unknown) => T): T[] {
  if (!isArray(value)) fail(what);
  const out: T[] = [];
  for (let i = 0; i < value.length; i++) out[out.length] = item(value[i]);
  return out;
}

/** Sets `json[key]` to `convert(value)` unless the member is absent. */
function optional(json: Json, key: string, value: unknown, convert: (value: unknown) => unknown) {
  if (value !== undefined) json[key] = convert(value);
}

function strings(what: string) {
  return (value: unknown) => list(value, what, (it) => string(it, what));
}

function descriptor(value: unknown): Json {
  const source = record(value, "a credential descriptor");
  const json: Json = { type: string(source.type, "type"), id: base64Url(source.id, "id") };
  optional(json, "transports", source.transports, strings("transports"));
  return json;
}

/**
 * Extension inputs and their results: BufferSources become base64url, as in
 * `AuthenticationExtensionsClientInputsJSON` (`prf`, `largeBlob`); plain
 * values and records pass through, so extensions Keyguard doesn't know reach
 * the app as they are.
 */
function extensionValue(value: unknown, depth: number): unknown {
  if (depth > MAX_DEPTH) fail("extensions");
  switch (typeof value) {
    case "string":
    case "boolean":
      return value;
    case "number":
      return number(value, "extensions");
    case "object": {
      if (value === null) return null;
      if (isView(value) || apply(objectToString, value, []) === "[object ArrayBuffer]") return base64Url(value, "extensions");
      if (isArray(value)) return list(value, "extensions", (it) => extensionValue(it, depth + 1));
      const out: Json = {};
      const names = keys(value);
      for (let i = 0; i < names.length; i++) {
        const name = names[i]!;
        const member = (value as Json)[name];
        if (member !== undefined) out[name] = extensionValue(member, depth + 1);
      }
      return out;
    }
    default:
      return fail("extensions");
  }
}

function extensions(value: unknown): unknown {
  return extensionValue(record(value, "extensions"), 0);
}

/** `PublicKeyCredentialCreationOptions` as `PublicKeyCredentialCreationOptionsJSON`. */
export function creationOptionsToJSON(value: unknown): Json {
  const options = record(value, "publicKey");
  const rp = record(options.rp, "rp");
  const user = record(options.user, "user");
  const rpJson: Json = { name: string(rp.name, "rp.name") };
  optional(rpJson, "id", rp.id, (it) => string(it, "rp.id"));
  const json: Json = {
    rp: rpJson,
    user: {
      id: base64Url(user.id, "user.id"),
      name: string(user.name, "user.name"),
      displayName: string(user.displayName, "user.displayName"),
    },
    challenge: base64Url(options.challenge, "challenge"),
    pubKeyCredParams: list(options.pubKeyCredParams, "pubKeyCredParams", (it) => {
      const param = record(it, "pubKeyCredParams");
      return { type: string(param.type, "type"), alg: long(param.alg, "alg") };
    }),
  };
  optional(json, "timeout", options.timeout, (it) => number(it, "timeout"));
  optional(json, "excludeCredentials", options.excludeCredentials, (it) => list(it, "excludeCredentials", descriptor));
  optional(json, "authenticatorSelection", options.authenticatorSelection, (it) => {
    const selection = record(it, "authenticatorSelection");
    const out: Json = {};
    optional(out, "authenticatorAttachment", selection.authenticatorAttachment, (v) => string(v, "authenticatorAttachment"));
    optional(out, "residentKey", selection.residentKey, (v) => string(v, "residentKey"));
    optional(out, "requireResidentKey", selection.requireResidentKey, (v) => boolean(v, "requireResidentKey"));
    optional(out, "userVerification", selection.userVerification, (v) => string(v, "userVerification"));
    return out;
  });
  optional(json, "hints", options.hints, strings("hints"));
  optional(json, "attestation", options.attestation, (it) => string(it, "attestation"));
  optional(json, "attestationFormats", options.attestationFormats, strings("attestationFormats"));
  optional(json, "extensions", options.extensions, extensions);
  return json;
}

/** `PublicKeyCredentialRequestOptions` as `PublicKeyCredentialRequestOptionsJSON`. */
export function requestOptionsToJSON(value: unknown): Json {
  const options = record(value, "publicKey");
  const json: Json = { challenge: base64Url(options.challenge, "challenge") };
  optional(json, "timeout", options.timeout, (it) => number(it, "timeout"));
  optional(json, "rpId", options.rpId, (it) => string(it, "rpId"));
  optional(json, "allowCredentials", options.allowCredentials, (it) => list(it, "allowCredentials", descriptor));
  optional(json, "userVerification", options.userVerification, (it) => string(it, "userVerification"));
  optional(json, "hints", options.hints, strings("hints"));
  optional(json, "attestation", options.attestation, (it) => string(it, "attestation"));
  optional(json, "attestationFormats", options.attestationFormats, strings("attestationFormats"));
  optional(json, "extensions", options.extensions, extensions);
  return json;
}

// endregion

// region Credentials

/** The browser's classes, whose prototypes the results take, so `instanceof` holds. */
export interface CredentialClasses {
  PublicKeyCredential?: { prototype: object } | undefined;
  AuthenticatorAttestationResponse?: { prototype: object } | undefined;
  AuthenticatorAssertionResponse?: { prototype: object } | undefined;
}

function buffer(value: unknown, what: string): ArrayBuffer {
  const bytes = typeof value === "string" ? fromBase64Url(value) : null;
  if (bytes === null) fail(what);
  return bytes.buffer as ArrayBuffer;
}

function copyBuffer(value: ArrayBuffer): ArrayBuffer {
  return apply(sliceBuffer, value, [0]) as ArrayBuffer;
}

/** A deep copy of JSON values. */
function copyJson(value: unknown): unknown {
  if (typeof value !== "object" || value === null) return value;
  if (isArray(value)) return list(value, "JSON", copyJson);
  const out: Json = {};
  const names = keys(value);
  for (let i = 0; i < names.length; i++) out[names[i]!] = copyJson((value as Json)[names[i]!]);
  return out;
}

/**
 * An object of the browser class [type], or a plain object without it, whose
 * members are own properties: the class's getters would reject an object the
 * browser didn't make.
 */
function make(type: { prototype: object } | undefined, members: Json): Json {
  const object = create(type?.prototype ?? Object.prototype) as Json;
  const names = keys(members);
  for (let i = 0; i < names.length; i++) {
    defineProperty(object, names[i]!, { value: members[names[i]!], enumerable: true, configurable: true });
  }
  return object;
}

/**
 * `getClientExtensionResults()` from `clientExtensionResults`: the base64url
 * outputs of `prf` and `largeBlob` become ArrayBuffers; the rest passes
 * through.
 */
function extensionResults(json: Json): Json {
  const results = copyJson(json) as Json;
  const prf = results.prf as Json | undefined;
  const prfResults = typeof prf === "object" && prf !== null ? (prf.results as Json | undefined) : undefined;
  if (typeof prfResults === "object" && prfResults !== null) {
    prfResults.first = buffer(prfResults.first, "prf.results.first");
    if (prfResults.second !== undefined) prfResults.second = buffer(prfResults.second, "prf.results.second");
  }
  const largeBlob = results.largeBlob as Json | undefined;
  if (typeof largeBlob === "object" && largeBlob !== null && largeBlob.blob !== undefined) {
    largeBlob.blob = buffer(largeBlob.blob, "largeBlob.blob");
  }
  return results;
}

/**
 * A `PublicKeyCredential` of a registration (`create`) or an authentication
 * (`get`) from the app's JSON. Its prototype is the browser's, so
 * `instanceof PublicKeyCredential` holds; the members are own properties, and
 * `toJSON()` returns the app's JSON.
 *
 * Its `authenticatorAttachment`, in `toJSON()` too, is `"platform"`, whatever
 * the app says, as other password managers report their passkeys: some sites
 * take another attachment for a security key.
 */
export function credentialFromJSON(value: unknown, kind: "create" | "get", classes: CredentialClasses): PublicKeyCredential {
  const json = copyJson(record(value, "the credential")) as Json;
  const response = record(json.response, "response");
  const clientExtensionResults = json.clientExtensionResults === undefined ? {} : record(json.clientExtensionResults, "clientExtensionResults");
  // Decoded once now, so a malformed result fails here and not in a getter.
  extensionResults(clientExtensionResults);
  const clientDataJSON = buffer(response.clientDataJSON, "clientDataJSON");
  let responseObject: Json;
  if (kind === "create") {
    const authenticatorData = buffer(response.authenticatorData, "authenticatorData");
    const publicKey = response.publicKey === undefined || response.publicKey === null ? null : buffer(response.publicKey, "publicKey");
    const transports = response.transports === undefined ? [] : list(response.transports, "transports", (it) => string(it, "transports"));
    const algorithm = number(response.publicKeyAlgorithm, "publicKeyAlgorithm");
    responseObject = make(classes.AuthenticatorAttestationResponse, {
      clientDataJSON,
      attestationObject: buffer(response.attestationObject, "attestationObject"),
      getTransports: () => list(transports, "transports", (it) => it),
      getAuthenticatorData: () => copyBuffer(authenticatorData),
      getPublicKey: () => (publicKey === null ? null : copyBuffer(publicKey)),
      getPublicKeyAlgorithm: () => algorithm,
    });
  } else {
    const members: Json = {
      clientDataJSON,
      authenticatorData: buffer(response.authenticatorData, "authenticatorData"),
      signature: buffer(response.signature, "signature"),
      userHandle: response.userHandle === undefined || response.userHandle === null ? null : buffer(response.userHandle, "userHandle"),
    };
    if (response.attestationObject !== undefined) members.attestationObject = buffer(response.attestationObject, "attestationObject");
    responseObject = make(classes.AuthenticatorAssertionResponse, members);
  }
  const id = string(json.id, "id");
  const normalized: Json = { ...json, authenticatorAttachment: PLATFORM, clientExtensionResults, type: "public-key" };
  return make(classes.PublicKeyCredential, {
    id,
    rawId: buffer(json.rawId, "rawId"),
    type: "public-key",
    authenticatorAttachment: PLATFORM,
    response: responseObject,
    getClientExtensionResults: () => extensionResults(clientExtensionResults),
    toJSON: () => copyJson(normalized),
  }) as unknown as PublicKeyCredential;
}

// endregion
