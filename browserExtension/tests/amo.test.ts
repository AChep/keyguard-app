// The lookup of the add-on's versions on addons.mozilla.org (scripts/amo.ts).
import { createHmac } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { AmoError, amoHasVersion, amoJwt, type AmoApiKey } from "../scripts/amo";

const KEY: AmoApiKey = { issuer: "user:12345:67", secret: "test-secret-value" };
const GUID = "browser@keyguard.dev";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function page(versions: string[], next: string | null): Response {
  return json(200, { count: versions.length, next, previous: null, results: versions.map((version) => ({ version })) });
}

/** A fetch that answers with [responses] in order, and the URLs and headers it got. */
function fakeFetch(...responses: Response[]) {
  const requests: { url: URL; headers: Headers }[] = [];
  const fetchFn = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    requests.push({ url: new URL(String(input)), headers: new Headers(init?.headers) });
    const response = responses.shift();
    if (response === undefined) throw new Error("unexpected request");
    return response;
  });
  return { fetchFn: fetchFn as unknown as typeof fetch, requests };
}

function decode(part: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
}

describe("amoJwt", () => {
  it("signs the claims AMO asks for with HS256", () => {
    const now = Date.UTC(2026, 10, 21, 12, 0, 0, 500);
    const [header, payload, signature] = amoJwt(KEY, now).split(".");
    expect(decode(header!)).toEqual({ alg: "HS256", typ: "JWT" });
    const claims = decode(payload!);
    expect(claims).toEqual({
      iss: KEY.issuer,
      jti: expect.any(String),
      iat: Math.floor(now / 1000),
      exp: expect.any(Number),
    });
    const lifetime = (claims.exp as number) - (claims.iat as number);
    expect(lifetime).toBeGreaterThan(0);
    expect(lifetime).toBeLessThanOrEqual(5 * 60);
    expect(signature).toBe(createHmac("sha256", KEY.secret).update(`${header}.${payload}`).digest("base64url"));
  });

  it("uses a new jti for every token", () => {
    const jti = (token: string) => decode(token.split(".")[1]!).jti;
    expect(jti(amoJwt(KEY, 0))).not.toBe(jti(amoJwt(KEY, 0)));
  });
});

describe("amoHasVersion", () => {
  it("finds a version, unlisted ones included, with the API key", async () => {
    const { fetchFn, requests } = fakeFetch(page(["3.8.1", "3.8.2"], null));
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).resolves.toBe(true);
    expect(requests).toHaveLength(1);
    const { url, headers } = requests[0]!;
    expect(url.origin).toBe("https://addons.mozilla.org");
    expect(url.pathname).toBe("/api/v5/addons/addon/browser%40keyguard.dev/versions/");
    expect(url.searchParams.get("filter")).toBe("all_with_unlisted");
    expect(url.searchParams.get("page")).toBe("1");
    const token = /^JWT (.+)$/.exec(headers.get("Authorization") ?? "")?.[1];
    expect(decode(token!.split(".")[1]!).iss).toBe(KEY.issuer);
  });

  it("reports an absent version", async () => {
    const { fetchFn } = fakeFetch(page(["3.8.1", "3.8.20"], null));
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).resolves.toBe(false);
  });

  it("reports an absent version of an add-on without versions", async () => {
    const { fetchFn } = fakeFetch(page([], null));
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).resolves.toBe(false);
  });

  it("reads every page, and sends the token to AMO only", async () => {
    const { fetchFn, requests } = fakeFetch(
      page(["3.8.4", "3.8.3"], "https://example.com/next?page=2"),
      page(["3.8.2"], "https://example.com/next?page=3"),
    );
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).resolves.toBe(true);
    expect(requests.map(({ url }) => [url.origin, url.searchParams.get("page")])).toEqual([
      ["https://addons.mozilla.org", "1"],
      ["https://addons.mozilla.org", "2"],
    ]);
    // A token of its own for each page, which AMO doesn't take twice.
    const jti = ({ headers }: { headers: Headers }) =>
      decode(/^JWT (.+)$/.exec(headers.get("Authorization") ?? "")![1]!.split(".")[1]!).jti;
    expect(jti(requests[1]!)).toEqual(expect.any(String));
    expect(jti(requests[1]!)).not.toBe(jti(requests[0]!));
  });

  it("stops at an empty page, even with a next page", async () => {
    const { fetchFn, requests } = fakeFetch(page([], "https://addons.mozilla.org/next"));
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).resolves.toBe(false);
    expect(requests).toHaveLength(1);
  });

  it("stops after the last page", async () => {
    const { fetchFn, requests } = fakeFetch(page(["3.8.4"], "https://addons.mozilla.org/next"), page(["3.8.3"], null));
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).resolves.toBe(false);
    expect(requests).toHaveLength(2);
  });

  it("fails on an HTTP error with AMO's message, without the key", async () => {
    const { fetchFn } = fakeFetch(json(401, { detail: "Incorrect authentication credentials." }));
    const error = await amoHasVersion(GUID, "3.8.2", KEY, fetchFn).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(AmoError);
    expect((error as AmoError).status).toBe(401);
    expect((error as AmoError).message).toBe(
      "AMO's versions of browser@keyguard.dev: HTTP 401, Incorrect authentication credentials.",
    );
    expect((error as AmoError).message).not.toContain(KEY.secret);
    expect((error as AmoError).message).not.toContain("JWT");
  });

  it("fails on an error page that isn't JSON", async () => {
    const { fetchFn } = fakeFetch(new Response("<html>Bad gateway</html>", { status: 502, statusText: "Bad Gateway" }));
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).rejects.toThrow(
      "AMO's versions of browser@keyguard.dev: HTTP 502, Bad Gateway",
    );
  });

  it("fails on a response without results", async () => {
    const { fetchFn } = fakeFetch(json(200, { detail: "?" }));
    await expect(amoHasVersion(GUID, "3.8.2", KEY, fetchFn)).rejects.toThrow("the response has no results");
  });
});
