// Microsoft Edge Add-ons. The release workflow uploads the Chromium zip and
// submits it for certification (scripts/edge-submit.ts), unless the store's
// website already shows that version (scripts/edge-version.ts).
// https://learn.microsoft.com/microsoft-edge/extensions/update/api/addons-api-reference
import { randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";

const API_URL = "https://api.addons.microsoftedge.microsoft.com";
const WEBSITE_URL = "https://microsoftedge.microsoft.com";

/**
 * An API key of Partner Center: Microsoft Edge > Publish API. It expires
 * after 72 days.
 */
export interface EdgeApiKey {
  clientId: string;
  apiKey: string;
}

/**
 * The state of an upload or a submission, as the API returns it. A failure
 * names its reason in `message`, `errorCode` and `errors`; the documented
 * `errors` are strings or `{ message }` objects.
 */
export interface EdgeOperation {
  id?: string;
  /** `InProgress`, `Succeeded` or `Failed`. */
  status?: string;
  message?: string | null;
  errorCode?: string | null;
  errors?: unknown;
}

/** A failed request to Edge Add-ons, or a failed operation. */
export class EdgeError extends Error {
  /** The HTTP status; 200 for a failed operation. */
  readonly status: number;
  /** The failed operation, as the API returned it. */
  readonly operation: EdgeOperation | undefined;

  constructor(message: string, status: number, operation?: EdgeOperation) {
    super(message);
    this.status = status;
    this.operation = operation;
  }
}

/** What to do about an [EdgeError] of a request, by its HTTP status. */
export const EDGE_HINTS: Record<number, string> = {
  401: "The API key expired (after 72 days) or is wrong. Create a new one in Partner Center > Microsoft Edge > Publish API, then update EDGE_API_KEY in the edge-web-store environment.",
  403: "Check that EDGE_CLIENT_ID and EDGE_API_KEY are of the same Partner Center account as the product.",
  404: "Check the product ID: Partner Center > Microsoft Edge > the extension > Extension overview.",
  429: "Too many requests: try again later.",
};

/**
 * The version that the Edge Add-ons website shows for the extension
 * [crxId], or null if it shows none: the extension isn't public yet. The
 * API has no way to read it, so this reads an undocumented endpoint of the
 * website, without a key.
 */
export async function edgePublicVersion(crxId: string, fetchFn: typeof fetch = fetch): Promise<string | null> {
  const url = new URL(`/addons/getproductdetailsbycrxid/${encodeURIComponent(crxId)}`, WEBSITE_URL);
  const response = await fetchFn(url, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(30_000),
  });
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new EdgeError(`Edge Add-ons details of ${crxId}: HTTP ${response.status}`, response.status);
  }
  const version = ((await response.json()) as { version?: unknown }).version;
  if (typeof version !== "string" || version === "") {
    throw new Error(`Edge Add-ons details of ${crxId}: the response has no version`);
  }
  return version;
}

export interface EdgeSubmitOptions {
  fetchFn?: typeof fetch;
  /** How often to ask for the state of an operation. */
  intervalMs?: number;
  /** How long to wait for an operation to finish. */
  timeoutMs?: number;
}

/**
 * Uploads [zip] as the draft of the product [productId], then submits the
 * draft for certification with [notes] for the testers. `in-review`: a
 * submission, maybe of the same version, is still in review, so the upload
 * stays a draft and isn't submitted.
 */
export async function edgeSubmit(
  productId: string,
  zip: Uint8Array<ArrayBuffer>,
  notes: string,
  key: EdgeApiKey,
  { fetchFn = fetch, intervalMs = 5_000, timeoutMs = 10 * 60_000 }: EdgeSubmitOptions = {},
): Promise<"submitted" | "in-review"> {
  const product = `/v1/products/${encodeURIComponent(productId)}`;
  const options = { key, fetchFn, intervalMs, timeoutMs };

  const upload = await edgeOperation(
    "upload of the package",
    `${product}/submissions/draft/package`,
    { body: zip, headers: { "Content-Type": "application/zip" }, timeoutMs: 5 * 60_000 },
    options,
  );
  if (upload.status !== "Succeeded") throw operationError("upload of the package", upload);

  // The notes go as text/plain, which fetch() sends a string body as.
  const submission = await edgeOperation("submission", `${product}/submissions`, { body: notes }, options);
  if (submission.status === "Succeeded") return "submitted";
  if (submission.errorCode === "InProgressSubmission") return "in-review";
  throw operationError("submission", submission);
}

/**
 * Checks the API key without changing anything: asks for the state of a
 * submission that doesn't exist, which an accepted key gets a 404 for. It
 * checks the key, not [productId]: the API answers a product ID that isn't
 * valid, or isn't the developer's, with the same 404. Fails on 401 and 403,
 * and on 429 and a server error, which tell nothing.
 */
export async function edgeCheckKey(productId: string, key: EdgeApiKey, fetchFn: typeof fetch = fetch): Promise<void> {
  const path = `/v1/products/${encodeURIComponent(productId)}/submissions/operations/${randomUUID()}`;
  const response = await edgeFetch(path, {}, key, fetchFn);
  if ([401, 403, 429].includes(response.status) || response.status >= 500) {
    throw new EdgeError(
      `Edge Add-ons key check: HTTP ${response.status}${await errorDetail(response)}`,
      response.status,
    );
  }
}

interface EdgeRequest {
  /** The body of a POST; a GET without it. */
  body?: BodyInit;
  headers?: Record<string, string>;
  timeoutMs?: number;
}

function edgeFetch(path: string, request: EdgeRequest, key: EdgeApiKey, fetchFn: typeof fetch): Promise<Response> {
  return fetchFn(new URL(path, API_URL), {
    method: request.body === undefined ? "GET" : "POST",
    headers: { ...request.headers, Authorization: `ApiKey ${key.apiKey}`, "X-ClientID": key.clientId },
    body: request.body ?? null,
    signal: AbortSignal.timeout(request.timeoutMs ?? 30_000),
  });
}

/**
 * POSTs [request] to [path], which answers with the ID of an operation in
 * Location, then asks for the operation's state at `[path]/operations/<ID>`
 * until it finishes.
 */
async function edgeOperation(
  what: string,
  path: string,
  request: EdgeRequest,
  { key, fetchFn, intervalMs, timeoutMs }: Required<EdgeSubmitOptions> & { key: EdgeApiKey },
): Promise<EdgeOperation> {
  const started = await edgeFetch(path, request, key, fetchFn);
  if (!started.ok) {
    throw new EdgeError(`Edge Add-ons ${what}: HTTP ${started.status}${await errorDetail(started)}`, started.status);
  }
  // The docs show a bare ID; a URL or a path would end with it.
  const location = started.headers.get("Location") ?? "";
  const id = location
    .split(/[?#]/)[0]!
    .split("/")
    .findLast((segment) => segment.trim() !== "")
    ?.trim();
  if (id === undefined) {
    const message = `Edge Add-ons ${what}: HTTP ${started.status} without the operation's ID in Location`;
    throw new EdgeError(message, started.status);
  }

  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const response = await edgeFetch(`${path}/operations/${id}`, {}, key, fetchFn);
    if (!response.ok) {
      const message = `Edge Add-ons ${what}, operation ${id}: HTTP ${response.status}${await errorDetail(response)}`;
      throw new EdgeError(message, response.status);
    }
    const operation = (await response.json()) as EdgeOperation | null;
    if (typeof operation !== "object" || operation === null) {
      throw new Error(`Edge Add-ons ${what}, operation ${id}: the response isn't an operation`);
    }
    // Anything else is the end, a response without a status included: the
    // docs show one for an unexpected failure.
    if (operation.status !== "InProgress") return operation;
    if (Date.now() >= deadline) {
      throw new Error(`Edge Add-ons ${what}, operation ${id}: still in progress after ${timeoutMs / 1000} s`);
    }
    await sleep(intervalMs);
  }
}

/** An error with all that [operation] tells of its failure, its JSON included. */
function operationError(what: string, operation: EdgeOperation): EdgeError {
  const lines = [`Edge Add-ons ${what}: ${operation.status ?? "no status"}, ${operation.message || "no message"}`];
  if (operation.errorCode) lines.push(`Error code: ${operation.errorCode}`);
  if (Array.isArray(operation.errors) && operation.errors.length > 0) {
    lines.push("Errors:");
    for (const error of operation.errors as unknown[]) {
      const message = typeof error === "string" ? error : (error as { message?: unknown } | null)?.message;
      lines.push(`- ${typeof message === "string" ? message : JSON.stringify(error)}`);
    }
  }
  lines.push(`Operation: ${JSON.stringify(operation)}`);
  return new EdgeError(lines.join("\n"), 200, operation);
}

/**
 * The API's message for a failed request: the `message` of its JSON error,
 * or the whole JSON of an unknown shape, which the docs don't describe.
 */
async function errorDetail(response: Response): Promise<string> {
  const text = (await response.text().catch(() => "")).trim();
  try {
    const message = (JSON.parse(text) as { message?: unknown } | null)?.message;
    if (typeof message === "string" && message !== "") return `, ${message}`;
    if (text !== "") return `, ${text.slice(0, 2000)}`;
  } catch {
    // Not JSON: an error page of a proxy, for one.
  }
  return response.statusText === "" ? "" : `, ${response.statusText}`;
}
