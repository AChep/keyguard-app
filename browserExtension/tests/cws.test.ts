// The Chrome Web Store API client (scripts/cws.ts).
import { generateKeyPairSync, verify } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import {
  CwsError,
  cwsAccessToken,
  cwsFetchStatus,
  cwsJwt,
  cwsSubmit,
  cwsVersionStatus,
  cwsWarningLine,
  privateKeyFromPem,
  type CwsItem,
  type CwsServiceAccount,
  type CwsStatus,
} from "../scripts/cws";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const PEM = privateKey.export({ type: "pkcs8", format: "pem" }) as string;
const ACCOUNT: CwsServiceAccount = { email: "release@keyguard.iam.gserviceaccount.com", privateKey: PEM };
const ITEM: CwsItem = { publisherId: "publisher-1", extensionId: "pbgoonibllaljhcpadklliddccmhdehh" };
const NAME = "publishers/publisher-1/items/pbgoonibllaljhcpadklliddccmhdehh";
const TOKEN = "ya29.test-access-token";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function googleError(code: number, status: string, message: string): Response {
  return json(code, { error: { code, message, status } });
}

/** A fetch that answers with [responses] in order, and the requests it got. */
function fakeFetch(...responses: Response[]) {
  const requests: { url: URL; method: string; headers: Headers; body: unknown }[] = [];
  const fetchFn = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    requests.push({
      url: new URL(String(input)),
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      body: init?.body,
    });
    const response = responses.shift();
    if (response === undefined) throw new Error("unexpected request");
    return response;
  });
  return { fetchFn: fetchFn as unknown as typeof fetch, requests };
}

function decode(part: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
}

function revision(state: string, ...versions: string[]) {
  return { state, distributionChannels: versions.map((crxVersion) => ({ deployPercentage: 100, crxVersion })) };
}

describe("privateKeyFromPem", () => {
  it("reads a PKCS#8 key with escaped line breaks, as in a JSON key", () => {
    const escaped = PEM.replaceAll("\n", "\\n");
    expect(escaped).not.toContain("\n");
    const key = privateKeyFromPem(escaped, "The key");
    expect(key.export({ type: "pkcs8", format: "pem" })).toBe(PEM);
  });

  it("reads a PKCS#1 key", () => {
    const pkcs1 = privateKey.export({ type: "pkcs1", format: "pem" }) as string;
    expect(pkcs1).toContain("BEGIN RSA PRIVATE KEY");
    expect(privateKeyFromPem(pkcs1, "The key").export({ type: "pkcs8", format: "pem" })).toBe(PEM);
  });

  it("names the key that isn't PEM", () => {
    expect(() => privateKeyFromPem("not a key", "CWS_CRX_PRIVATE_KEY")).toThrow(
      "CWS_CRX_PRIVATE_KEY is not a PEM private key",
    );
  });
});

describe("cwsJwt", () => {
  it("signs the claims Google asks for with RS256", () => {
    const now = Date.UTC(2026, 10, 28, 12, 0, 0, 500);
    const [header, payload, signature] = cwsJwt(ACCOUNT, now).split(".");
    expect(decode(header!)).toEqual({ alg: "RS256", typ: "JWT" });
    const iat = Math.floor(now / 1000);
    expect(decode(payload!)).toEqual({
      iss: ACCOUNT.email,
      scope: "https://www.googleapis.com/auth/chromewebstore",
      aud: "https://oauth2.googleapis.com/token",
      iat,
      exp: iat + 3600,
    });
    const input = Buffer.from(`${header}.${payload}`);
    expect(verify("sha256", input, publicKey, Buffer.from(signature!, "base64url"))).toBe(true);
  });
});

describe("cwsAccessToken", () => {
  it("trades the signed assertion for an access token", async () => {
    const { fetchFn, requests } = fakeFetch(json(200, { access_token: TOKEN, expires_in: 3599, token_type: "Bearer" }));
    await expect(cwsAccessToken(ACCOUNT, fetchFn)).resolves.toBe(TOKEN);
    const { url, method, body } = requests[0]!;
    expect(url.href).toBe("https://oauth2.googleapis.com/token");
    expect(method).toBe("POST");
    const form = body as URLSearchParams;
    expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
    expect(decode(form.get("assertion")!.split(".")[1]!).iss).toBe(ACCOUNT.email);
  });

  it("fails on a refused assertion with Google's message, without the key", async () => {
    const { fetchFn } = fakeFetch(json(400, { error: "invalid_grant", error_description: "Invalid JWT Signature." }));
    const error = await cwsAccessToken(ACCOUNT, fetchFn).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CwsError);
    expect((error as CwsError).status).toBe(400);
    expect((error as CwsError).auth).toBe(true);
    expect((error as CwsError).message).toBe(
      `Google's token for ${ACCOUNT.email}: HTTP 400, invalid_grant: Invalid JWT Signature.`,
    );
    expect((error as CwsError).message).not.toContain("PRIVATE KEY");
  });

  it("fails on a response without a token", async () => {
    const { fetchFn } = fakeFetch(json(200, { expires_in: 3599 }));
    await expect(cwsAccessToken(ACCOUNT, fetchFn)).rejects.toThrow("the response has no access_token");
  });
});

describe("cwsFetchStatus", () => {
  it("reads the item's status with the access token", async () => {
    const status = { name: NAME, publishedItemRevisionStatus: revision("PUBLISHED", "3.9.0") };
    const { fetchFn, requests } = fakeFetch(json(200, status));
    await expect(cwsFetchStatus(ITEM, TOKEN, fetchFn)).resolves.toEqual(status);
    const { url, method, headers } = requests[0]!;
    expect(url.href).toBe(`https://chromewebstore.googleapis.com/v2/${NAME}:fetchStatus`);
    expect(method).toBe("GET");
    expect(headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
  });

  it("fails on an HTTP error with Google's message, without the token", async () => {
    const { fetchFn } = fakeFetch(googleError(404, "NOT_FOUND", "Requested entity was not found."));
    const error = await cwsFetchStatus(ITEM, TOKEN, fetchFn).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CwsError);
    expect((error as CwsError).status).toBe(404);
    expect((error as CwsError).auth).toBe(false);
    expect((error as CwsError).message).toBe(
      "The Chrome Web Store's status of pbgoonibllaljhcpadklliddccmhdehh: HTTP 404, Requested entity was not found.",
    );
    expect((error as CwsError).message).not.toContain(TOKEN);
  });

  it("fails on an error page that isn't JSON", async () => {
    const { fetchFn } = fakeFetch(new Response("<html>Bad gateway</html>", { status: 502, statusText: "Bad Gateway" }));
    await expect(cwsFetchStatus(ITEM, TOKEN, fetchFn)).rejects.toThrow("HTTP 502, Bad Gateway");
  });
});

describe("cwsVersionStatus", () => {
  const status = (published?: object, submitted?: object): CwsStatus =>
    ({ publishedItemRevisionStatus: published, submittedItemRevisionStatus: submitted }) as CwsStatus;

  it("finds the published version, or a newer one", () => {
    expect(cwsVersionStatus(status(revision("PUBLISHED", "3.10.0")), "3.10.0")).toBe("exists");
    expect(cwsVersionStatus(status(revision("PUBLISHED", "3.11.0")), "3.10.0")).toBe("exists");
  });

  it("finds a submitted version the store holds", () => {
    for (const state of ["PENDING_REVIEW", "STAGED", "PUBLISHED", "PUBLISHED_TO_TESTERS"]) {
      const submitted = revision(state, "3.10.0");
      expect(cwsVersionStatus(status(revision("PUBLISHED", "3.9.0"), submitted), "3.10.0")).toBe("exists");
    }
  });

  it("reports an older version awaiting review or publishing", () => {
    for (const state of ["PENDING_REVIEW", "STAGED"]) {
      const submitted = revision(state, "3.9.1");
      expect(cwsVersionStatus(status(revision("PUBLISHED", "3.9.0"), submitted), "3.10.0")).toBe("pending");
    }
  });

  it("reports an absent version", () => {
    expect(cwsVersionStatus(status(revision("PUBLISHED", "3.9.0")), "3.10.0")).toBe("absent");
    expect(cwsVersionStatus(status(), "3.10.0")).toBe("absent");
    // A rejected or cancelled version can be submitted again.
    for (const state of ["REJECTED", "CANCELLED"]) {
      const submitted = revision(state, "3.10.0");
      expect(cwsVersionStatus(status(revision("PUBLISHED", "3.9.0"), submitted), "3.10.0")).toBe("absent");
    }
  });
});

describe("cwsSubmit", () => {
  const ZIP = { fileName: "keyguard-3.10.0-chrome.zip", bytes: new Uint8Array([0x50, 0x4b, 0x03, 0x04]) };
  const CRX = { fileName: "keyguard-3.10.0-chrome.crx", bytes: new Uint8Array([0x43, 0x72, 0x32, 0x34]) };
  const options = { pollIntervalMs: 0 };

  function uploaded(uploadState: string): Response {
    return json(200, { name: NAME, itemId: ITEM.extensionId, crxVersion: "3.10.0", uploadState });
  }

  function published(body: object = {}): Response {
    return json(200, { name: NAME, itemId: ITEM.extensionId, state: "PENDING_REVIEW", ...body });
  }

  it("uploads a zip and submits it for review", async () => {
    const { fetchFn, requests } = fakeFetch(uploaded("SUCCEEDED"), published());
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn })).resolves.toEqual({
      state: "PENDING_REVIEW",
      warnings: [],
    });
    expect(requests).toHaveLength(2);
    const [upload, publish] = requests;
    expect(upload!.url.href).toBe(`https://chromewebstore.googleapis.com/upload/v2/${NAME}:upload`);
    expect(upload!.method).toBe("POST");
    expect(upload!.headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
    expect(upload!.headers.get("Content-Type")).toBe("application/zip");
    expect(upload!.headers.has("X-Goog-Upload-Protocol")).toBe(false);
    expect(upload!.body).toBe(ZIP.bytes);
    expect(publish!.url.href).toBe(`https://chromewebstore.googleapis.com/v2/${NAME}:publish`);
    expect(publish!.method).toBe("POST");
    expect(publish!.headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
    expect(publish!.headers.get("Content-Type")).toBe("application/json");
    expect(JSON.parse(publish!.body as string)).toEqual({ publishType: "DEFAULT_PUBLISH" });
  });

  it("uploads a CRX raw, with its file name", async () => {
    const { fetchFn, requests } = fakeFetch(uploaded("SUCCEEDED"), published());
    await cwsSubmit(ITEM, TOKEN, CRX, { ...options, fetchFn });
    const { headers, body } = requests[0]!;
    expect(headers.get("X-Goog-Upload-Protocol")).toBe("raw");
    expect(headers.get("X-Goog-Upload-File-Name")).toBe("keyguard-3.10.0-chrome.crx");
    expect(headers.has("Content-Type")).toBe(false);
    expect(body).toBe(CRX.bytes);
  });

  it("waits for an upload that the store still processes", async () => {
    const { fetchFn, requests } = fakeFetch(
      uploaded("IN_PROGRESS"),
      json(200, { name: NAME, lastAsyncUploadState: "IN_PROGRESS" }),
      json(200, { name: NAME, lastAsyncUploadState: "SUCCEEDED" }),
      published(),
    );
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn })).resolves.toMatchObject({
      state: "PENDING_REVIEW",
    });
    expect(requests.map(({ url }) => url.pathname.split(":").at(-1))).toEqual([
      "upload",
      "fetchStatus",
      "fetchStatus",
      "publish",
    ]);
  });

  it("waits for an upload in progress by the name of the media.upload reference", async () => {
    const { fetchFn, requests } = fakeFetch(
      uploaded("UPLOAD_IN_PROGRESS"),
      json(200, { name: NAME, lastAsyncUploadState: "UPLOAD_IN_PROGRESS" }),
      json(200, { name: NAME, lastAsyncUploadState: "SUCCEEDED" }),
      published(),
    );
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn })).resolves.toMatchObject({
      state: "PENDING_REVIEW",
    });
    expect(requests.map(({ url }) => url.pathname.split(":").at(-1))).toEqual([
      "upload",
      "fetchStatus",
      "fetchStatus",
      "publish",
    ]);
  });

  it("gives up on an upload that takes too long", async () => {
    const { fetchFn, requests } = fakeFetch(uploaded("IN_PROGRESS"));
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { fetchFn, pollIntervalMs: 0, uploadTimeoutMs: 0 })).rejects.toThrow(
      "The Chrome Web Store still processes keyguard-3.10.0-chrome.zip after 0 s",
    );
    expect(requests).toHaveLength(1);
  });

  it("fails on a failed upload, without publishing", async () => {
    const { fetchFn, requests } = fakeFetch(
      uploaded("IN_PROGRESS"),
      json(200, { name: NAME, lastAsyncUploadState: "FAILED" }),
    );
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn })).rejects.toThrow(
      "The Chrome Web Store didn't take keyguard-3.10.0-chrome.zip: the upload state is FAILED. Upload the package in the Developer Dashboard to see why the store refused it.",
    );
    expect(requests).toHaveLength(2);
  });

  it("fails on a refused upload with Google's message", async () => {
    const { fetchFn } = fakeFetch(googleError(400, "INVALID_ARGUMENT", "The version must be greater than 3.10.0."));
    const error = await cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CwsError);
    expect((error as CwsError).status).toBe(400);
    expect((error as CwsError).message).toBe(
      "The Chrome Web Store's upload of keyguard-3.10.0-chrome.zip: HTTP 400, The version must be greater than 3.10.0.",
    );
  });

  it("fails on a refused submission", async () => {
    const { fetchFn } = fakeFetch(uploaded("SUCCEEDED"), googleError(403, "PERMISSION_DENIED", "Permission denied."));
    const error = await cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn }).catch((e: unknown) => e);
    expect((error as CwsError).status).toBe(403);
    expect((error as CwsError).message).toBe(
      "The Chrome Web Store's submission of pbgoonibllaljhcpadklliddccmhdehh: HTTP 403, Permission denied.",
    );
  });

  it("returns the store's warnings", async () => {
    const warnings = [
      { reason: "PERMISSION_JUSTIFICATION", description: "Justify the nativeMessaging permission." },
      { description: "Only a description." },
      { code: 7 },
    ];
    const { fetchFn } = fakeFetch(uploaded("SUCCEEDED"), published({ warningInfo: { warnings } }));
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn })).resolves.toEqual({
      state: "PENDING_REVIEW",
      warnings: [
        "PERMISSION_JUSTIFICATION: Justify the nativeMessaging permission.",
        "Only a description.",
        '{"code":7}',
      ],
    });
  });

  it("fails on a submission without a state", async () => {
    const { fetchFn } = fakeFetch(uploaded("SUCCEEDED"), json(200, { name: NAME }));
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn })).rejects.toThrow("the response has no state");
  });

  it.each(["REJECTED", "CANCELLED"])("fails on a submission that the store answers with %s", async (state) => {
    const { fetchFn } = fakeFetch(uploaded("SUCCEEDED"), published({ state }));
    await expect(cwsSubmit(ITEM, TOKEN, ZIP, { ...options, fetchFn })).rejects.toThrow(
      `The Chrome Web Store's submission of pbgoonibllaljhcpadklliddccmhdehh is ${state}. Check the item in the Developer Dashboard.`,
    );
  });
});

describe("cwsWarningLine", () => {
  it("prints a plain warning outside GitHub Actions", () => {
    expect(cwsWarningLine("50% of\nthe text", false)).toBe("Warning: 50% of\nthe text");
  });

  it("prints a workflow command on GitHub Actions, with the message escaped", () => {
    expect(cwsWarningLine("PERMISSION_JUSTIFICATION: 50% of\r\nthe text", true)).toBe(
      "::warning title=Chrome Web Store::PERMISSION_JUSTIFICATION: 50%25 of%0D%0Athe text",
    );
  });
});
