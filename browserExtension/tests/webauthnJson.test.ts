// WebAuthn's JSON forms: options from the page to the app, the app's
// credentials back to PublicKeyCredential-shaped objects, and the background's
// checks of those credentials.
import { describe, expect, it } from "vitest";
import { credentialProblem } from "../src/lib/webauthn/credential";
import {
  creationOptionsToJSON,
  credentialFromJSON,
  fromBase64Url,
  requestOptionsToJSON,
  toBase64Url,
} from "../src/lib/webauthn/json";
import { authenticationJSON, CREDENTIAL_ID, registrationJSON, USER_HANDLE } from "./support/webauthn";

const ORIGIN = "https://login.example.com";
const encode = (text: string) => new TextEncoder().encode(text);
const b64 = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");
const bytesOf = (buffer: unknown) => new Uint8Array(buffer as ArrayBuffer);

class FakePublicKeyCredential {}
class FakeAttestationResponse {}
class FakeAssertionResponse {}
const CLASSES = {
  PublicKeyCredential: FakePublicKeyCredential,
  AuthenticatorAttestationResponse: FakeAttestationResponse,
  AuthenticatorAssertionResponse: FakeAssertionResponse,
};

describe("base64url", () => {
  it("matches Node's encoder without padding, and decodes back", () => {
    for (let length = 0; length < 40; length++) {
      const bytes = Uint8Array.from({ length }, (_, i) => (i * 37 + length) & 255);
      const text = toBase64Url(bytes);
      expect(text).toBe(b64(bytes));
      expect(fromBase64Url(text)).toEqual(bytes);
    }
  });

  it("takes padding, and refuses other alphabets and lengths", () => {
    expect(fromBase64Url("AQI=")).toEqual(new Uint8Array([1, 2]));
    expect(fromBase64Url("AQ==")).toEqual(new Uint8Array([1]));
    expect(fromBase64Url("+/8")).toBeNull();
    expect(fromBase64Url("A")).toBeNull();
    expect(fromBase64Url("AQ===")).toBeNull();
    expect(fromBase64Url("A Q")).toBeNull();
  });
});

describe("options to JSON", () => {
  it("converts creation options, with BufferSources of any kind as base64url", () => {
    const challenge = crypto.getRandomValues(new Uint8Array(32));
    const userId = encode("user-1");
    const excluded = new Uint8Array([9, 8, 7]);
    const salt = new Uint8Array(32).fill(1);
    const blob = encode("large blob");
    const json = creationOptionsToJSON({
      rp: { id: "example.com", name: "Example" },
      user: { id: new DataView(userId.buffer), name: "ann@example.com", displayName: "Ann" },
      challenge: challenge.buffer,
      pubKeyCredParams: [
        { type: "public-key", alg: -7 },
        { type: "public-key", alg: -257 },
      ],
      timeout: 60_000,
      excludeCredentials: [{ type: "public-key", id: excluded, transports: ["internal"] }],
      authenticatorSelection: { residentKey: "required", requireResidentKey: true, userVerification: "preferred" },
      hints: ["client-device"],
      attestation: "none",
      attestationFormats: ["packed"],
      extensions: {
        credProps: true,
        prf: { eval: { first: salt } },
        largeBlob: { support: "preferred", write: blob.buffer },
      },
      // Not WebAuthn: left out, as WebIDL does.
      unknown: { nested: true },
    });
    expect(json).toEqual({
      rp: { id: "example.com", name: "Example" },
      user: { id: b64(userId), name: "ann@example.com", displayName: "Ann" },
      challenge: b64(challenge),
      pubKeyCredParams: [
        { type: "public-key", alg: -7 },
        { type: "public-key", alg: -257 },
      ],
      timeout: 60_000,
      excludeCredentials: [{ type: "public-key", id: b64(excluded), transports: ["internal"] }],
      authenticatorSelection: { residentKey: "required", requireResidentKey: true, userVerification: "preferred" },
      hints: ["client-device"],
      attestation: "none",
      attestationFormats: ["packed"],
      extensions: {
        credProps: true,
        prf: { eval: { first: b64(salt) } },
        largeBlob: { support: "preferred", write: b64(blob) },
      },
    });
    // The JSON is plain data: it survives a JSON round trip unchanged.
    expect(JSON.parse(JSON.stringify(json))).toEqual(json);
  });

  it("converts alg as a WebIDL long, as the browser does for KeycloakJS's strings", () => {
    const algOf = (alg: unknown) => {
      const json = creationOptionsToJSON({
        rp: { name: "Example" },
        user: { id: encode("user-1"), name: "ann", displayName: "Ann" },
        challenge: new Uint8Array(1),
        pubKeyCredParams: [{ type: "public-key", alg }],
      });
      return (json.pubKeyCredParams as { alg: unknown }[])[0]!.alg;
    };
    expect(algOf(-7)).toBe(-7);
    expect(algOf("-7")).toBe(-7);
    expect(algOf("-257")).toBe(-257);
    // Not a number: 0, as in the browser, where no authenticator takes it.
    expect(algOf("ES256")).toBe(0);
    expect(algOf(-7.9)).toBe(-7);
    expect(algOf(2 ** 32 - 7)).toBe(-7);
    // A required member.
    expect(() => algOf(undefined)).toThrow(TypeError);
  });

  it("converts request options, with prf per credential and largeBlob read", () => {
    const challenge = new Uint8Array([1, 2, 3, 4]);
    const json = requestOptionsToJSON({
      challenge,
      rpId: "example.com",
      allowCredentials: [{ type: "public-key", id: new Uint8Array([5, 6]) }],
      userVerification: "required",
      extensions: {
        prf: { evalByCredential: { [CREDENTIAL_ID]: { first: new Uint8Array([1]), second: new Uint8Array([2]) } } },
        largeBlob: { read: true },
        appid: "https://example.com/appid.json",
      },
    });
    expect(json).toEqual({
      challenge: b64(challenge),
      rpId: "example.com",
      allowCredentials: [{ type: "public-key", id: b64(new Uint8Array([5, 6])) }],
      userVerification: "required",
      extensions: {
        prf: { evalByCredential: { [CREDENTIAL_ID]: { first: "AQ", second: "Ag" } } },
        largeBlob: { read: true },
        appid: "https://example.com/appid.json",
      },
    });
    expect(requestOptionsToJSON({ challenge })).toEqual({ challenge: b64(challenge) });
  });

  it("throws on what WebAuthn wouldn't take, so the browser reports it", () => {
    expect(() => requestOptionsToJSON({})).toThrow(TypeError);
    expect(() => requestOptionsToJSON({ challenge: "not bytes" })).toThrow(TypeError);
    expect(() => requestOptionsToJSON({ challenge: new Uint8Array(1), allowCredentials: {} })).toThrow(TypeError);
    expect(() => requestOptionsToJSON({ challenge: new Uint8Array(1), extensions: { f: () => 1 } })).toThrow(TypeError);
    let deep: Record<string, unknown> = {};
    const root = deep;
    for (let i = 0; i < 20; i++) deep = deep.next = {};
    expect(() => requestOptionsToJSON({ challenge: new Uint8Array(1), extensions: root })).toThrow(TypeError);
    expect(() => creationOptionsToJSON({ rp: { name: "x" }, user: {}, challenge: new Uint8Array(1), pubKeyCredParams: [] })).toThrow(
      TypeError,
    );
    expect(() => creationOptionsToJSON(null)).toThrow(TypeError);
  });
});

describe("credentials from JSON", () => {
  it("rebuilds a registration on the browser's prototypes", () => {
    const json = registrationJSON(ORIGIN, "Y2hhbGxlbmdl");
    const credential = credentialFromJSON(json, "create", CLASSES) as unknown as Record<string, unknown> & PublicKeyCredential;
    expect(credential).toBeInstanceOf(FakePublicKeyCredential);
    expect(credential.response).toBeInstanceOf(FakeAttestationResponse);
    expect(credential.id).toBe(CREDENTIAL_ID);
    expect(credential.type).toBe("public-key");
    expect(credential.authenticatorAttachment).toBe("platform");
    expect(credential.rawId).toBeInstanceOf(ArrayBuffer);
    expect(b64(bytesOf(credential.rawId))).toBe(CREDENTIAL_ID);

    const response = credential.response as AuthenticatorAttestationResponse;
    const source = json.response as Record<string, string>;
    expect(b64(bytesOf(response.clientDataJSON))).toBe(source.clientDataJSON);
    expect(b64(bytesOf(response.attestationObject))).toBe(source.attestationObject);
    expect(b64(bytesOf(response.getAuthenticatorData()))).toBe(source.authenticatorData);
    expect(b64(bytesOf(response.getPublicKey()))).toBe(source.publicKey);
    expect(response.getPublicKeyAlgorithm()).toBe(-7);
    const transports = response.getTransports();
    expect(transports).toEqual(["internal", "hybrid"]);
    transports.push("usb");
    expect(response.getTransports()).toEqual(["internal", "hybrid"]);
    expect(credential.getClientExtensionResults()).toEqual({ credProps: { rk: true }, prf: { enabled: true } });

    // toJSON() and JSON.stringify give the app's JSON, and copies of it.
    expect(credential.toJSON()).toEqual(json);
    (credential.toJSON() as unknown as Record<string, unknown>).id = "changed";
    expect(credential.toJSON()).toEqual(json);
    expect(JSON.parse(JSON.stringify(credential))).toEqual(json);
    // for...in sees the members, as on the browser's objects.
    const names: string[] = [];
    for (const name in credential) names.push(name);
    expect(names).toEqual(expect.arrayContaining(["id", "rawId", "type", "response", "toJSON", "getClientExtensionResults"]));
  });

  it("rebuilds an authentication, with prf and largeBlob outputs as ArrayBuffers", () => {
    const json = authenticationJSON(ORIGIN, "Y2hhbGxlbmdl");
    const credential = credentialFromJSON(json, "get", CLASSES);
    expect(credential.response).toBeInstanceOf(FakeAssertionResponse);
    const response = credential.response as AuthenticatorAssertionResponse;
    const source = json.response as Record<string, string>;
    expect(b64(bytesOf(response.signature))).toBe(source.signature);
    expect(b64(bytesOf(response.authenticatorData))).toBe(source.authenticatorData);
    expect(b64(bytesOf(response.userHandle))).toBe(USER_HANDLE);
    const results = credential.getClientExtensionResults() as {
      prf: { results: { first: ArrayBuffer; second: ArrayBuffer } };
      largeBlob: { blob: ArrayBuffer };
    };
    const extensions = json.clientExtensionResults as typeof results & Record<string, Record<string, Record<string, string>>>;
    expect(results.prf.results.first).toBeInstanceOf(ArrayBuffer);
    expect(b64(bytesOf(results.prf.results.first))).toBe(extensions.prf.results.first);
    expect(b64(bytesOf(results.prf.results.second))).toBe(extensions.prf.results.second);
    expect(b64(bytesOf(results.largeBlob.blob))).toBe(extensions.largeBlob.blob);
    // Each call gets its own copy; toJSON keeps base64url.
    expect(credential.getClientExtensionResults()).not.toBe(results);
    expect(credential.toJSON()).toEqual(json);
  });

  it("reports a platform authenticator, whatever the app says, for a get and a create", () => {
    for (const [json, kind] of [
      [authenticationJSON(ORIGIN, "Y2hhbGxlbmdl"), "get"],
      [registrationJSON(ORIGIN, "Y2hhbGxlbmdl"), "create"],
    ] as const) {
      for (const attachment of ["cross-platform", null, 7]) {
        const credential = credentialFromJSON({ ...json, authenticatorAttachment: attachment }, kind, CLASSES);
        expect(credential.authenticatorAttachment, `${kind} ${String(attachment)}`).toBe("platform");
        expect(credential.toJSON(), `${kind} ${String(attachment)}`).toMatchObject({ authenticatorAttachment: "platform" });
      }
    }
  });

  it("defaults what the app may leave out, and fails on malformed fields", () => {
    const json = authenticationJSON(ORIGIN, "Y2hhbGxlbmdl");
    const { clientExtensionResults: _, authenticatorAttachment: __, ...rest } = json;
    const response = { ...(json.response as Record<string, unknown>), userHandle: undefined };
    const credential = credentialFromJSON({ ...rest, response }, "get", {});
    expect(Object.getPrototypeOf(credential)).toBe(Object.prototype);
    expect(credential.authenticatorAttachment).toBe("platform");
    expect((credential.response as AuthenticatorAssertionResponse).userHandle).toBeNull();
    expect(credential.getClientExtensionResults()).toEqual({});
    expect(credential.toJSON()).toMatchObject({ authenticatorAttachment: "platform", clientExtensionResults: {}, type: "public-key" });

    expect(() => credentialFromJSON({ ...json, rawId: "not base64url!" }, "get", {})).toThrow(TypeError);
    expect(() => credentialFromJSON({ ...json, response: { ...response, signature: 1 } }, "get", {})).toThrow(TypeError);
    expect(() => credentialFromJSON(registrationJSON(ORIGIN, "AA"), "create", {})).not.toThrow();
  });
});

describe("credential checks", () => {
  const challenge = "Y2hhbGxlbmdl";
  const expected = { origin: ORIGIN, challenge } as const;

  it("accepts the app's JSON for the request", () => {
    expect(credentialProblem(registrationJSON(ORIGIN, challenge), { kind: "create", ...expected })).toBeNull();
    expect(credentialProblem(authenticationJSON(ORIGIN, challenge), { kind: "get", ...expected })).toBeNull();
    // The challenge compares as bytes: padding doesn't matter.
    expect(credentialProblem(authenticationJSON(ORIGIN, "AQI"), { kind: "get", origin: ORIGIN, challenge: "AQI=" })).toBeNull();
  });

  it("refuses a credential for another origin, challenge or ceremony", () => {
    expect(credentialProblem(authenticationJSON("https://evil.example", challenge), { kind: "get", ...expected })).toBe(
      "clientDataJSON.origin",
    );
    expect(credentialProblem(authenticationJSON(ORIGIN, "b3RoZXI"), { kind: "get", ...expected })).toBe("clientDataJSON.challenge");
    expect(credentialProblem(registrationJSON(ORIGIN, challenge), { kind: "get", ...expected })).toBe("signature");
    const registration = registrationJSON(ORIGIN, challenge);
    const asGet = { ...registration, response: { ...(registration.response as object), signature: "AA" } };
    expect(credentialProblem(asGet, { kind: "get", ...expected })).toBe("clientDataJSON.type");
  });

  it("refuses malformed fields", () => {
    const json = authenticationJSON(ORIGIN, challenge);
    const response = json.response as Record<string, unknown>;
    const problem = (credential: Record<string, unknown>) => credentialProblem(credential, { kind: "get", ...expected });
    expect(problem({ ...json, rawId: toBase64Url(new Uint8Array([9])) })).toBe("id");
    expect(problem({ ...json, type: "password" })).toBe("type");
    expect(problem({ ...json, response: { ...response, userHandle: 5 } })).toBe("userHandle");
    expect(problem({ ...json, clientExtensionResults: { prf: { results: { first: "!" } } } })).toBe("prf.results");
    expect(problem({ ...json, clientExtensionResults: { largeBlob: { blob: 1 } } })).toBe("largeBlob");
    expect(problem({ ...json, response: { ...response, clientDataJSON: toBase64Url(encode("not json")) } })).toBe("clientDataJSON");
    const registration = registrationJSON(ORIGIN, challenge);
    const create = (credential: Record<string, unknown>) => credentialProblem(credential, { kind: "create", ...expected });
    const registrationResponse = registration.response as Record<string, unknown>;
    expect(create({ ...registration, response: { ...registrationResponse, publicKeyAlgorithm: "ES256" } })).toBe("publicKeyAlgorithm");
    expect(create({ ...registration, response: { ...registrationResponse, transports: [1] } })).toBe("transports");
  });
});
