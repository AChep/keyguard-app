// Canned WebAuthn JSON, as the app answers `passkey_get` and `passkey_create`
// (protocol §5): `clientDataJSON` for the request's origin and challenge, the
// rest fixed bytes. Nothing here is a real credential.
import { toBase64Url } from "../../src/lib/webauthn/json";

export const CREDENTIAL_ID = toBase64Url(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]));
export const USER_HANDLE = toBase64Url(new TextEncoder().encode("user-1"));

function bytes(length: number, seed: number): string {
  return toBase64Url(Uint8Array.from({ length }, (_, i) => (seed + i) & 255));
}

export function clientDataJSON(type: "webauthn.create" | "webauthn.get", origin: string, challenge: string): string {
  return toBase64Url(new TextEncoder().encode(JSON.stringify({ type, challenge, origin, crossOrigin: false })));
}

/** A `RegistrationResponseJSON` for a request of [origin] with [challenge]. */
export function registrationJSON(origin: string, challenge: string): Record<string, unknown> {
  return {
    id: CREDENTIAL_ID,
    rawId: CREDENTIAL_ID,
    type: "public-key",
    authenticatorAttachment: "platform",
    response: {
      clientDataJSON: clientDataJSON("webauthn.create", origin, challenge),
      attestationObject: bytes(64, 10),
      authenticatorData: bytes(37, 20),
      transports: ["internal", "hybrid"],
      publicKey: bytes(91, 30),
      publicKeyAlgorithm: -7,
    },
    clientExtensionResults: { credProps: { rk: true }, prf: { enabled: true } },
  };
}

/** An `AuthenticationResponseJSON` for a request of [origin] with [challenge]. */
export function authenticationJSON(origin: string, challenge: string): Record<string, unknown> {
  return {
    id: CREDENTIAL_ID,
    rawId: CREDENTIAL_ID,
    type: "public-key",
    authenticatorAttachment: "platform",
    response: {
      clientDataJSON: clientDataJSON("webauthn.get", origin, challenge),
      authenticatorData: bytes(37, 40),
      signature: bytes(70, 50),
      userHandle: USER_HANDLE,
    },
    clientExtensionResults: {
      prf: { results: { first: bytes(32, 60), second: bytes(32, 70) } },
      largeBlob: { blob: bytes(16, 80) },
    },
  };
}
