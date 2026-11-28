// The extension on the Chrome Web Store, through its API v2. The release
// workflow submits a version only if the store doesn't have it
// (scripts/cws-version.ts), then uploads and publishes it (scripts/cws-submit.ts).
// https://developer.chrome.com/docs/webstore/api
import { createPrivateKey, sign, type KeyObject } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";
import { compareVersions } from "./version.ts";

const CWS_URL = "https://chromewebstore.googleapis.com";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/chromewebstore";

/** A service account of a Google Cloud project with the Chrome Web Store API. */
export interface CwsServiceAccount {
  email: string;
  /** The PEM private key, as in the `private_key` field of the account's JSON key. */
  privateKey: string;
}

/** The extension [extensionId] of the publisher [publisherId]. */
export interface CwsItem {
  publisherId: string;
  extensionId: string;
}

/** A state of a revision of the item. */
export type CwsItemState =
  | "PENDING_REVIEW"
  | "STAGED"
  | "PUBLISHED"
  | "PUBLISHED_TO_TESTERS"
  | "REJECTED"
  | "CANCELLED"
  | "ITEM_STATE_UNSPECIFIED";

export interface CwsRevisionStatus {
  state?: CwsItemState;
  distributionChannels?: { deployPercentage?: number; crxVersion?: string }[];
}

/**
 * The state of an upload. The docs disagree on the name of an upload in
 * progress: `UPLOAD_IN_PROGRESS` in the `media.upload` reference,
 * `IN_PROGRESS` in the UploadState enum and the API guide. Both count.
 */
export type CwsUploadState = "SUCCEEDED" | "IN_PROGRESS" | "UPLOAD_IN_PROGRESS" | "FAILED" | "NOT_FOUND";

/** The response of `fetchStatus`, with the fields this file reads. */
export interface CwsStatus {
  publishedItemRevisionStatus?: CwsRevisionStatus;
  submittedItemRevisionStatus?: CwsRevisionStatus;
  lastAsyncUploadState?: CwsUploadState;
}

/** A failed request to Google: the token exchange or the Chrome Web Store API. */
export class CwsError extends Error {
  readonly status: number;
  /** Whether Google refused the service account's token request. */
  readonly auth: boolean;

  constructor(message: string, status: number, auth = false) {
    super(message);
    this.status = status;
    this.auth = auth;
  }
}

/** What to do about a [CwsError] that Google's token endpoint answered. */
export const CWS_AUTH_HINT = "Check CWS_SERVICE_ACCOUNT_EMAIL, CWS_SERVICE_ACCOUNT_PRIVATE_KEY and the clock.";

/** What to do about a [CwsError], by its HTTP status. */
export const CWS_HINTS: Record<number, string> = {
  401: CWS_AUTH_HINT,
  403: "The service account needs access to the publisher in the Chrome Web Store Developer Dashboard.",
  404: "Check the publisher ID and the extension ID. Upload the extension's first version by hand.",
};

/**
 * A warning of the store as a line of the log. On GitHub Actions
 * ([githubActions]), a workflow command that shows it on the run's page,
 * where a green job's log goes unread; its message escaped as the runner
 * asks.
 */
export function cwsWarningLine(warning: string, githubActions: boolean): string {
  if (!githubActions) return `Warning: ${warning}`;
  const message = warning.replaceAll("%", "%25").replaceAll("\r", "%0D").replaceAll("\n", "%0A");
  return `::warning title=Chrome Web Store::${message}`;
}

/**
 * The private key of a PEM secret, named [name] in errors. A key pasted from
 * the `private_key` field of a JSON key has `\n` escapes for its line breaks.
 * Base64 has no `\`, so the replacement leaves a plain PEM as it is.
 */
export function privateKeyFromPem(pem: string, name: string): KeyObject {
  try {
    return createPrivateKey(pem.replaceAll("\\n", "\n"));
  } catch (error) {
    // OpenSSL's message, "DECODER routines::unsupported" for one, names no key.
    throw new Error(`${name} is not a PEM private key`, { cause: error });
  }
}

/**
 * The assertion that the service account trades for an access token, signed
 * with RS256.
 * https://developers.google.com/identity/protocols/oauth2/service-account#authorizingrequests
 */
export function cwsJwt(account: CwsServiceAccount, now: number = Date.now()): string {
  const iat = Math.floor(now / 1000);
  // Google refuses an assertion that expires more than an hour after `iat`.
  const claims = { iss: account.email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 };
  const input = `${base64url({ alg: "RS256", typ: "JWT" })}.${base64url(claims)}`;
  const signature = sign(
    "sha256",
    Buffer.from(input),
    privateKeyFromPem(account.privateKey, "The service account's private key"),
  );
  return `${input}.${signature.toString("base64url")}`;
}

function base64url(value: object): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

/** An access token of the service account, valid for an hour. */
export async function cwsAccessToken(account: CwsServiceAccount, fetchFn: typeof fetch = fetch): Promise<string> {
  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion: cwsJwt(account),
  });
  const token = await request<{ access_token?: unknown }>(
    fetchFn,
    new URL(TOKEN_URL),
    { method: "POST", body },
    `Google's token for ${account.email}`,
    { auth: true },
  );
  if (typeof token.access_token !== "string" || token.access_token === "") {
    throw new Error(`Google's token for ${account.email}: the response has no access_token`);
  }
  return token.access_token;
}

/** The published and submitted revisions of [item], and its last upload. */
export async function cwsFetchStatus(item: CwsItem, token: string, fetchFn: typeof fetch = fetch): Promise<CwsStatus> {
  return request<CwsStatus>(
    fetchFn,
    itemUrl("/v2", item, "fetchStatus"),
    { headers: { Authorization: `Bearer ${token}` } },
    `The Chrome Web Store's status of ${item.extensionId}`,
  );
}

/** What the store has of a version, see [cwsVersionStatus]. */
export type CwsVersionStatus = "exists" | "pending" | "absent";

// A submitted revision in these states holds its version: the store refuses
// an upload that isn't newer.
const SUBMITTED_STATES: (CwsItemState | undefined)[] = [
  "PENDING_REVIEW",
  "STAGED",
  "PUBLISHED",
  "PUBLISHED_TO_TESTERS",
];
// The store takes no new submission while one awaits review or publishing.
const PENDING_STATES: (CwsItemState | undefined)[] = ["PENDING_REVIEW", "STAGED"];

/**
 * `exists` if the published revision, or a submitted one that the store holds,
 * has [version] or a newer one; else `pending` if an older version awaits
 * review or publishing; else `absent`. A rejected or cancelled version counts
 * as absent: it can be submitted again.
 */
export function cwsVersionStatus(status: CwsStatus, version: string): CwsVersionStatus {
  const submitted = status.submittedItemRevisionStatus;
  const versions = [
    ...versionsOf(status.publishedItemRevisionStatus),
    ...(SUBMITTED_STATES.includes(submitted?.state) ? versionsOf(submitted) : []),
  ];
  if (versions.some((other) => compareVersions(other, version) >= 0)) return "exists";
  return PENDING_STATES.includes(submitted?.state) ? "pending" : "absent";
}

function versionsOf(revision: CwsRevisionStatus | undefined): string[] {
  return (revision?.distributionChannels ?? []).flatMap(({ crxVersion }) =>
    typeof crxVersion === "string" ? [crxVersion] : [],
  );
}

/** A zip, or a CRX signed with the key of the verified CRX uploads (scripts/crx.ts). */
export interface CwsPackage {
  /** The file's name; a `.crx` one is uploaded as a CRX. */
  fileName: string;
  bytes: Uint8Array<ArrayBuffer>;
}

export interface CwsSubmitOptions {
  fetchFn?: typeof fetch;
  /** How often to check an upload that the store still processes. */
  pollIntervalMs?: number;
  /** How long to wait for the store to process the upload. */
  uploadTimeoutMs?: number;
}

/** The submitted revision: its state, `PENDING_REVIEW` for one, and the store's warnings. */
export interface CwsSubmission {
  state: string;
  warnings: string[];
}

interface UploadResponse {
  crxVersion?: string;
  uploadState?: CwsUploadState;
}

interface PublishResponse {
  state?: unknown;
  warningInfo?: { warnings?: { reason?: unknown; description?: unknown }[] };
}

/**
 * Uploads [pkg] as the new version of [item] and submits it for review. The
 * version is published once approved. Fails if the store answers the
 * submission with `REJECTED` or `CANCELLED`.
 */
export async function cwsSubmit(
  item: CwsItem,
  token: string,
  pkg: CwsPackage,
  { fetchFn = fetch, pollIntervalMs = 5_000, uploadTimeoutMs = 10 * 60_000 }: CwsSubmitOptions = {},
): Promise<CwsSubmission> {
  const authorization = `Bearer ${token}`;
  const headers: Record<string, string> = pkg.fileName.toLowerCase().endsWith(".crx")
    ? { Authorization: authorization, "X-Goog-Upload-Protocol": "raw", "X-Goog-Upload-File-Name": pkg.fileName }
    : { Authorization: authorization, "Content-Type": "application/zip" };
  const upload = await request<UploadResponse>(
    fetchFn,
    itemUrl("/upload/v2", item, "upload"),
    { method: "POST", headers, body: pkg.bytes },
    `The Chrome Web Store's upload of ${pkg.fileName}`,
    { timeoutMs: 5 * 60_000 },
  );
  // A large package is processed after the response. Either name of the
  // state in progress, see [CwsUploadState].
  let state = upload.uploadState;
  const deadline = Date.now() + uploadTimeoutMs;
  while (state === "IN_PROGRESS" || state === "UPLOAD_IN_PROGRESS") {
    if (Date.now() >= deadline) {
      throw new Error(`The Chrome Web Store still processes ${pkg.fileName} after ${uploadTimeoutMs / 1000} s`);
    }
    await sleep(pollIntervalMs);
    state = (await cwsFetchStatus(item, token, fetchFn)).lastAsyncUploadState;
  }
  if (state !== "SUCCEEDED") {
    // The API doesn't say why; the dashboard does.
    throw new Error(
      `The Chrome Web Store didn't take ${pkg.fileName}: the upload state is ${state}. Upload the package in the Developer Dashboard to see why the store refused it.`,
    );
  }

  const publish = await request<PublishResponse>(
    fetchFn,
    itemUrl("/v2", item, "publish"),
    {
      method: "POST",
      headers: { Authorization: authorization, "Content-Type": "application/json" },
      // Publishes once approved, rather than staging the version.
      body: JSON.stringify({ publishType: "DEFAULT_PUBLISH" }),
    },
    `The Chrome Web Store's submission of ${item.extensionId}`,
  );
  if (typeof publish.state !== "string") {
    throw new Error(`The Chrome Web Store's submission of ${item.extensionId}: the response has no state`);
  }
  // Not submitted: a green job would hide it.
  if (publish.state === "REJECTED" || publish.state === "CANCELLED") {
    throw new Error(
      `The Chrome Web Store's submission of ${item.extensionId} is ${publish.state}. Check the item in the Developer Dashboard.`,
    );
  }
  // Warnings don't block the review.
  const warnings = (publish.warningInfo?.warnings ?? []).map(
    (warning) =>
      [warning.reason, warning.description].filter((s) => typeof s === "string" && s !== "").join(": ") ||
      JSON.stringify(warning),
  );
  return { state: publish.state, warnings };
}

function itemUrl(prefix: string, { publisherId, extensionId }: CwsItem, method: string): URL {
  const name = `publishers/${encodeURIComponent(publisherId)}/items/${encodeURIComponent(extensionId)}`;
  return new URL(`${prefix}/${name}:${method}`, CWS_URL);
}

/** The JSON response of a request, or a [CwsError] named [what]. */
async function request<T>(
  fetchFn: typeof fetch,
  url: URL,
  init: RequestInit,
  what: string,
  { auth = false, timeoutMs = 30_000 } = {},
): Promise<T> {
  const response = await fetchFn(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
  if (!response.ok) {
    throw new CwsError(`${what}: HTTP ${response.status}${await errorDetail(response)}`, response.status, auth);
  }
  return (await response.json()) as T;
}

/** Google's message for a failed request. */
async function errorDetail(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: unknown; error_description?: unknown };
    // The API's errors: { error: { code, message, status } }.
    if (typeof body.error === "object" && body.error !== null) {
      const message = (body.error as { message?: unknown }).message;
      if (typeof message === "string" && message !== "") return `, ${message}`;
    }
    // The token endpoint's OAuth errors: { error, error_description }.
    if (typeof body.error === "string" && body.error !== "") {
      const description = typeof body.error_description === "string" ? body.error_description : "";
      return `, ${[body.error, description].filter(Boolean).join(": ")}`;
    }
  } catch {
    // Not JSON: an error page of a proxy, for one.
  }
  return response.statusText === "" ? "" : `, ${response.statusText}`;
}
