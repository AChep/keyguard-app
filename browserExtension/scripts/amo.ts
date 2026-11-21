// The versions of the add-on on addons.mozilla.org (AMO). The release workflow
// submits a version only if AMO doesn't have it, as AMO refuses a version
// number it already has (scripts/amo-version.ts).
import { createHmac, randomUUID } from "node:crypto";

const AMO_URL = "https://addons.mozilla.org";

/** An API key of AMO's Developer Hub: Tools > Manage API Keys. */
export interface AmoApiKey {
  /** The JWT issuer, `user:<id>:<n>`. */
  issuer: string;
  secret: string;
}

/**
 * A token for the `Authorization: JWT <token>` header, signed with HS256.
 * https://mozilla.github.io/addons-server/topics/api/auth.html
 */
export function amoJwt(key: AmoApiKey, now: number = Date.now()): string {
  const iat = Math.floor(now / 1000);
  // AMO refuses a token that expires more than 5 minutes after `iat`. `jti`
  // must be unique among the issuer's recent requests.
  const claims = { iss: key.issuer, jti: randomUUID(), iat, exp: iat + 60 };
  const input = `${base64url({ alg: "HS256", typ: "JWT" })}.${base64url(claims)}`;
  return `${input}.${createHmac("sha256", key.secret).update(input).digest("base64url")}`;
}

function base64url(value: object): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

/** A failed request to AMO's API. */
export class AmoError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

interface VersionsPage {
  next?: string | null;
  results?: { version?: unknown }[];
}

/**
 * Whether AMO has [version] of the add-on [guid] in any channel and state:
 * listed or unlisted, awaiting review, approved, rejected or disabled. Only
 * authors of the add-on can list its unlisted versions.
 * https://mozilla.github.io/addons-server/topics/api/addons.html#versions-list
 */
export async function amoHasVersion(
  guid: string,
  version: string,
  key: AmoApiKey,
  fetchFn: typeof fetch = fetch,
): Promise<boolean> {
  for (let page = 1; ; page++) {
    // Pages by number rather than by `next`, so the token goes to AMO alone.
    const url = new URL(`/api/v5/addons/addon/${encodeURIComponent(guid)}/versions/`, AMO_URL);
    url.search = new URLSearchParams({ filter: "all_with_unlisted", page: String(page), page_size: "50" }).toString();
    const response = await fetchFn(url, {
      headers: { Accept: "application/json", Authorization: `JWT ${amoJwt(key)}` },
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) {
      const message = `AMO's versions of ${guid}: HTTP ${response.status}${await errorDetail(response)}`;
      throw new AmoError(message, response.status);
    }
    const body = (await response.json()) as VersionsPage;
    if (!Array.isArray(body.results)) {
      throw new Error(`AMO's versions of ${guid}: the response has no results`);
    }
    if (body.results.some((result) => result.version === version)) return true;
    if (!body.next || body.results.length === 0) return false;
  }
}

/** AMO's message for a failed request: the `detail` of its JSON error. */
async function errorDetail(response: Response): Promise<string> {
  try {
    const detail = ((await response.json()) as { detail?: unknown }).detail;
    if (typeof detail === "string" && detail !== "") return `, ${detail}`;
  } catch {
    // Not JSON: an error page of a proxy, for one.
  }
  return response.statusText === "" ? "" : `, ${response.statusText}`;
}
