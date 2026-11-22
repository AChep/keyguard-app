// Checks of the app's passkey responses before the background hands them to a
// page: the shape of `RegistrationResponseJSON` / `AuthenticationResponseJSON`
// (WebAuthn Level 3), and a `clientDataJSON` for this request's origin and
// challenge. The app builds `clientDataJSON` itself (protocol §5); a mismatch
// is a bug that must not reach another origin.
import type { JsonObject } from "../protocol/framing";
import { bytesEqual } from "../util/bytes";
import { isObject } from "../util/json";
import { fromBase64Url } from "./json";

export interface ExpectedCredential {
  kind: "create" | "get";
  /** The origin of the frame that asked. */
  origin: string;
  /** The `challenge` of the request's options, base64url. */
  challenge: unknown;
}

function bytes(value: unknown): Uint8Array | null {
  return typeof value === "string" ? fromBase64Url(value) : null;
}

const isBytes = (value: unknown) => bytes(value) !== null;
const isOptionalBytes = (value: unknown) => value === undefined || value === null || isBytes(value);

function clientDataProblem(value: unknown, expected: ExpectedCredential): string | null {
  const data = bytes(value);
  if (data === null) return "clientDataJSON";
  let clientData: unknown;
  try {
    clientData = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(data));
  } catch {
    return "clientDataJSON";
  }
  if (!isObject(clientData)) return "clientDataJSON";
  if (clientData.type !== (expected.kind === "create" ? "webauthn.create" : "webauthn.get"))
    return "clientDataJSON.type";
  if (clientData.origin !== expected.origin) return "clientDataJSON.origin";
  const challenge = bytes(clientData.challenge);
  const asked = bytes(expected.challenge);
  if (challenge === null || asked === null || !bytesEqual(challenge, asked)) return "clientDataJSON.challenge";
  return null;
}

function extensionsProblem(value: unknown): string | null {
  if (value === undefined) return null;
  if (!isObject(value)) return "clientExtensionResults";
  const prf = value.prf;
  if (prf !== undefined) {
    if (!isObject(prf)) return "prf";
    const results = prf.results;
    if (
      results !== undefined &&
      (!isObject(results) || !isBytes(results.first) || !(results.second === undefined || isBytes(results.second)))
    ) {
      return "prf.results";
    }
  }
  const largeBlob = value.largeBlob;
  if (largeBlob !== undefined && (!isObject(largeBlob) || !(largeBlob.blob === undefined || isBytes(largeBlob.blob)))) {
    return "largeBlob";
  }
  return null;
}

/** What's wrong with a credential of the app, or `null` when it fits the request. */
export function credentialProblem(credential: JsonObject, expected: ExpectedCredential): string | null {
  const id = bytes(credential.id);
  const rawId = bytes(credential.rawId);
  if (id === null || rawId === null || id.length === 0 || !bytesEqual(id, rawId)) return "id";
  if (credential.type !== "public-key") return "type";
  if (credential.authenticatorAttachment !== undefined && typeof credential.authenticatorAttachment !== "string") {
    return "authenticatorAttachment";
  }
  const extensions = extensionsProblem(credential.clientExtensionResults);
  if (extensions !== null) return extensions;
  const response = credential.response;
  if (!isObject(response)) return "response";
  if (!isBytes(response.authenticatorData)) return "authenticatorData";
  if (expected.kind === "create") {
    if (!isBytes(response.attestationObject)) return "attestationObject";
    if (!Number.isSafeInteger(response.publicKeyAlgorithm)) return "publicKeyAlgorithm";
    if (!isOptionalBytes(response.publicKey)) return "publicKey";
    const transports = response.transports;
    if (transports !== undefined && (!Array.isArray(transports) || !transports.every((it) => typeof it === "string"))) {
      return "transports";
    }
  } else {
    if (!isBytes(response.signature)) return "signature";
    if (!isOptionalBytes(response.userHandle)) return "userHandle";
    if (response.attestationObject !== undefined && !isBytes(response.attestationObject)) return "attestationObject";
  }
  return clientDataProblem(response.clientDataJSON, expected);
}
