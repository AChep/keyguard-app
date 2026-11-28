// The Microsoft Edge Add-ons client of the release workflow (scripts/edge.ts).
import { describe, expect, it, vi } from "vitest";
import {
  edgeCheckKey,
  EdgeError,
  edgePublicVersion,
  edgeSubmit,
  type EdgeApiKey,
  type EdgeOperation,
} from "../scripts/edge";

const KEY: EdgeApiKey = { clientId: "test-client-id", apiKey: "test-api-key-value" };
const PRODUCT = "d34f98f5-f9b7-42b1-bebb-98707202b21d";
const CRX_ID = "jmiinenokkmkljkgaahelgacgkjemebb";
const ZIP = new Uint8Array([0x50, 0x4b, 0x03, 0x04]);
const NOTES = "Pair the extension with the desktop app.";
const OPTIONS = { intervalMs: 0, timeoutMs: 60_000 };

const API = `https://api.addons.microsoftedge.microsoft.com/v1/products/${PRODUCT}`;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function accepted(location: string | null): Response {
  return new Response(null, { status: 202, headers: location === null ? {} : { Location: location } });
}

function operation(status: string, fields: Partial<EdgeOperation> = {}): Response {
  return json(200, {
    id: "op",
    createdTime: "2026-11-28T10:00:00Z",
    lastUpdatedTime: "2026-11-28T10:00:05Z",
    status,
    message: null,
    errorCode: null,
    errors: null,
    ...fields,
  });
}

/** A fetch that answers with [responses] in order, and the requests it got. */
function fakeFetch(...responses: Response[]) {
  const requests: { method: string; url: string; headers: Headers; body: unknown }[] = [];
  const fetchFn = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    requests.push({
      method: init?.method ?? "GET",
      url: String(input),
      headers: new Headers(init?.headers),
      body: init?.body ?? null,
    });
    const response = responses.shift();
    if (response === undefined) throw new Error("unexpected request");
    return response;
  });
  return { fetchFn: fetchFn as unknown as typeof fetch, requests };
}

async function failure(promise: Promise<unknown>): Promise<EdgeError> {
  const error = await promise.catch((e: unknown) => e);
  expect(error).toBeInstanceOf(EdgeError);
  return error as EdgeError;
}

describe("edgePublicVersion", () => {
  it("reads the version that the website shows, without a key", async () => {
    const { fetchFn, requests } = fakeFetch(json(200, { crxId: CRX_ID, name: "Keyguard", version: "3.9.0" }));
    await expect(edgePublicVersion(CRX_ID, fetchFn)).resolves.toBe("3.9.0");
    expect(requests).toHaveLength(1);
    expect(requests[0]!.url).toBe(`https://microsoftedge.microsoft.com/addons/getproductdetailsbycrxid/${CRX_ID}`);
    expect(requests[0]!.headers.has("Authorization")).toBe(false);
    expect(requests[0]!.headers.has("X-ClientID")).toBe(false);
  });

  it("reports no version for an extension that isn't public", async () => {
    const { fetchFn } = fakeFetch(new Response("Status Code: 404; Not Found", { status: 404 }));
    await expect(edgePublicVersion(CRX_ID, fetchFn)).resolves.toBeNull();
  });

  it("fails on another HTTP error", async () => {
    const { fetchFn } = fakeFetch(new Response("<html>Bad gateway</html>", { status: 502 }));
    const error = await failure(edgePublicVersion(CRX_ID, fetchFn));
    expect(error.status).toBe(502);
    expect(error.message).toBe(`Edge Add-ons details of ${CRX_ID}: HTTP 502`);
  });

  it("fails on a response without a version", async () => {
    const { fetchFn } = fakeFetch(json(200, { crxId: CRX_ID }));
    await expect(edgePublicVersion(CRX_ID, fetchFn)).rejects.toThrow("the response has no version");
  });
});

describe("edgeSubmit", () => {
  it("uploads the zip, waits, submits it with the notes and waits", async () => {
    const { fetchFn, requests } = fakeFetch(
      accepted("upload-op"),
      operation("InProgress"),
      operation("Succeeded", { message: "Successfully updated package to keyguard.zip", errorCode: "" }),
      accepted("publish-op"),
      operation("InProgress"),
      operation("Succeeded", { message: "Successfully created submission with ID 1", errorCode: "" }),
    );
    await expect(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS })).resolves.toBe("submitted");
    expect(requests.map(({ method, url }) => `${method} ${url}`)).toEqual([
      `POST ${API}/submissions/draft/package`,
      `GET ${API}/submissions/draft/package/operations/upload-op`,
      `GET ${API}/submissions/draft/package/operations/upload-op`,
      `POST ${API}/submissions`,
      `GET ${API}/submissions/operations/publish-op`,
      `GET ${API}/submissions/operations/publish-op`,
    ]);
    for (const { headers } of requests) {
      expect(headers.get("Authorization")).toBe(`ApiKey ${KEY.apiKey}`);
      expect(headers.get("X-ClientID")).toBe(KEY.clientId);
    }
    expect(requests[0]!.headers.get("Content-Type")).toBe("application/zip");
    expect(requests[0]!.body).toBe(ZIP);
    expect(requests[3]!.body).toBe(NOTES);
  });

  it.each([
    ["a URL", "https://api.addons.microsoftedge.microsoft.com/v1/products/p/submissions/draft/package/operations/abc"],
    ["a path", "/v1/products/p/submissions/draft/package/operations/abc/"],
    ["an ID with spaces", " abc "],
  ])("takes the operation ID from the end of %s in Location", async (_, location) => {
    const { fetchFn, requests } = fakeFetch(
      accepted(location),
      operation("Succeeded"),
      accepted("publish-op"),
      operation("Succeeded"),
    );
    await expect(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS })).resolves.toBe("submitted");
    expect(requests[1]!.url).toBe(`${API}/submissions/draft/package/operations/abc`);
  });

  it("fails without an operation ID in Location", async () => {
    const { fetchFn, requests } = fakeFetch(accepted(null));
    const error = await failure(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS }));
    expect(error.status).toBe(202);
    expect(error.message).toBe("Edge Add-ons upload of the package: HTTP 202 without the operation's ID in Location");
    expect(requests).toHaveLength(1);
  });

  it("reports a submission that waits for one in review", async () => {
    const { fetchFn } = fakeFetch(
      accepted("upload-op"),
      operation("Succeeded"),
      accepted("publish-op"),
      operation("Failed", {
        message: "Can't publish extension as your extension submission is in progress. Please try again later.",
        errorCode: "InProgressSubmission",
      }),
    );
    await expect(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS })).resolves.toBe("in-review");
  });

  it("fails on a failed upload with all that the operation tells", async () => {
    const failed = {
      id: "upload-op",
      status: "Failed",
      message: "Error in package upload.",
      errorCode: "PackageValidationError",
      errors: ["The manifest is invalid.", { message: "The version must be higher." }],
    };
    const { fetchFn, requests } = fakeFetch(accepted("upload-op"), json(200, failed));
    const error = await failure(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS }));
    expect(error.operation).toEqual(failed);
    expect(error.message).toBe(
      [
        "Edge Add-ons upload of the package: Failed, Error in package upload.",
        "Error code: PackageValidationError",
        "Errors:",
        "- The manifest is invalid.",
        "- The version must be higher.",
        `Operation: ${JSON.stringify(failed)}`,
      ].join("\n"),
    );
    // Not submitted.
    expect(requests).toHaveLength(2);
  });

  it("fails on a failed submission", async () => {
    const { fetchFn } = fakeFetch(
      accepted("upload-op"),
      operation("Succeeded"),
      accepted("publish-op"),
      operation("Failed", {
        message: "Can't publish extension as your extension has modules that are not valid.",
        errorCode: "ModuleStateUnPublishable",
        errors: [{ message: "Invalid module : Properties" }],
      }),
    );
    const error = await failure(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS }));
    expect(error.operation?.errorCode).toBe("ModuleStateUnPublishable");
    expect(error.message).toContain("Edge Add-ons submission: Failed, Can't publish extension");
    expect(error.message).toContain("- Invalid module : Properties");
    expect(error.message).toContain('Operation: {"id":"op"');
  });

  it("fails on an operation without a status", async () => {
    // The docs' "unexpected failure" response.
    const unexpected = { id: "publish-op", message: "An error occurred while processing the request." };
    const { fetchFn } = fakeFetch(
      accepted("upload-op"),
      operation("Succeeded"),
      accepted("publish-op"),
      json(200, unexpected),
    );
    const error = await failure(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS }));
    expect(error.message).toBe(
      [
        "Edge Add-ons submission: no status, An error occurred while processing the request.",
        `Operation: ${JSON.stringify(unexpected)}`,
      ].join("\n"),
    );
  });

  it("gives up on an operation that doesn't finish", async () => {
    const { fetchFn } = fakeFetch(accepted("upload-op"), operation("InProgress"));
    await expect(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, intervalMs: 0, timeoutMs: 0 })).rejects.toThrow(
      "Edge Add-ons upload of the package, operation upload-op: still in progress after 0 s",
    );
  });

  it("fails on an HTTP error with the API's message, without the key", async () => {
    const { fetchFn } = fakeFetch(json(401, { message: "Unauthorized" }));
    const error = await failure(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS }));
    expect(error.status).toBe(401);
    expect(error.operation).toBeUndefined();
    expect(error.message).toBe("Edge Add-ons upload of the package: HTTP 401, Unauthorized");
    expect(error.message).not.toContain(KEY.apiKey);
  });

  it("fails on an HTTP error of an unknown shape with the whole of it", async () => {
    const { fetchFn } = fakeFetch(accepted("upload-op"), json(404, { code: "NotFound" }));
    const error = await failure(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS }));
    expect(error.status).toBe(404);
    expect(error.message).toBe(
      'Edge Add-ons upload of the package, operation upload-op: HTTP 404, {"code":"NotFound"}',
    );
  });

  it("fails on an error page that isn't JSON", async () => {
    const { fetchFn } = fakeFetch(new Response("<html>Bad gateway</html>", { status: 502, statusText: "Bad Gateway" }));
    await expect(edgeSubmit(PRODUCT, ZIP, NOTES, KEY, { fetchFn, ...OPTIONS })).rejects.toThrow(
      "Edge Add-ons upload of the package: HTTP 502, Bad Gateway",
    );
  });
});

describe("edgeCheckKey", () => {
  it("accepts a key that gets a 404 for a made-up operation", async () => {
    const { fetchFn, requests } = fakeFetch(new Response(null, { status: 404 }));
    await expect(edgeCheckKey(PRODUCT, KEY, fetchFn)).resolves.toBeUndefined();
    expect(requests).toHaveLength(1);
    const { method, url, headers } = requests[0]!;
    expect(method).toBe("GET");
    expect(url).toMatch(new RegExp(`^${API}/submissions/operations/[0-9a-f-]{36}$`));
    expect(headers.get("Authorization")).toBe(`ApiKey ${KEY.apiKey}`);
    expect(headers.get("X-ClientID")).toBe(KEY.clientId);
  });

  it.each([401, 403, 429, 500])("fails on HTTP %i", async (status) => {
    const { fetchFn } = fakeFetch(new Response(null, { status }));
    const error = await failure(edgeCheckKey(PRODUCT, KEY, fetchFn));
    expect(error.status).toBe(status);
    expect(error.message).toBe(`Edge Add-ons key check: HTTP ${status}`);
  });
});
