// The extension's access to websites: whether it has it, asking for it, and
// watching the user change it.
import type { WxtBrowser } from "wxt/browser";
import { describe, expect, expectTypeOf, it } from "vitest";
import {
  hasSiteAccess,
  observeSiteAccess,
  requestAllSites,
  requestSite,
  sitePattern,
  WEB_PAGES,
  type PermissionSet,
  type SiteAccessApi,
} from "../src/lib/options/siteAccess";
import { settle } from "./support/pages";

type Listener = (permissions: PermissionSet) => void;

function fakeEvent(name: string, calls: string[]) {
  const listeners = new Set<Listener>();
  return {
    listeners,
    addListener: (it: Listener) => {
      calls.push(`${name}.add`);
      listeners.add(it);
    },
    removeListener: (it: Listener) => {
      calls.push(`${name}.remove`);
      listeners.delete(it);
    },
    fire: (permissions: PermissionSet) => listeners.forEach((it) => it(permissions)),
  };
}

/** `browser.permissions`, holding the given origins; a request grants or not. */
function fakePermissions({ origins = [] as string[], grant = true } = {}) {
  const held = new Set(origins);
  const calls: string[] = [];
  const onAdded = fakeEvent("onAdded", calls);
  const onRemoved = fakeEvent("onRemoved", calls);
  const api: SiteAccessApi = {
    permissions: {
      contains: async (p) => {
        calls.push(`contains ${p.origins?.join()}`);
        // Access to every website of a scheme covers each of its sites.
        return (p.origins ?? []).every((it) => held.has(it) || held.has(`${new URL(it).protocol}//*/*`));
      },
      request: async (p) => {
        calls.push(`request ${p.origins?.join()}`);
        if (grant) p.origins?.forEach((it) => held.add(it));
        return grant;
      },
      onAdded,
      onRemoved,
    },
  };
  return { api, calls, onAdded, onRemoved };
}

describe("site access", () => {
  it("fits the browser's permissions API on every build", () => {
    expectTypeOf<Pick<WxtBrowser, "permissions">>().toExtend<SiteAccessApi>();
  });

  it.each([
    ["https://example.com/login?next=/", "https://example.com/*"],
    ["http://example.com/", "http://example.com/*"],
    ["https://sub.example.com:8443/a", "https://sub.example.com/*"],
    ["http://localhost:8080/", "http://localhost/*"],
    ["http://[::1]:3000/", "http://[::1]/*"],
  ])("the pattern of %s is %s", (url, pattern) => {
    expect(sitePattern(url)).toBe(pattern);
  });

  it.each([
    undefined,
    "",
    "not a url",
    "about:blank",
    "chrome://extensions/",
    "file:///etc/hosts",
    "ftp://example.com/",
  ])("%s has no pattern", (url) => {
    expect(sitePattern(url)).toBeNull();
  });

  it("checks every website without a URL", async () => {
    const { api, calls } = fakePermissions({ origins: [...WEB_PAGES] });
    expect(await hasSiteAccess(api)).toBe(true);
    expect(calls).toEqual(["contains http://*/*,https://*/*"]);
    expect(await hasSiteAccess(fakePermissions({ origins: ["https://*/*"] }).api)).toBe(false);
  });

  it("checks only the URL's site with one", async () => {
    const { api, calls } = fakePermissions({ origins: ["https://example.com/*"] });
    expect(await hasSiteAccess(api, "https://example.com/login")).toBe(true);
    expect(calls).toEqual(["contains https://example.com/*"]);
    expect(await hasSiteAccess(api, "http://example.com/login")).toBe(false);
    expect(await hasSiteAccess(api, "https://other.example/")).toBe(false);
  });

  it("counts a site granted with the URL's port, as Chromium's extension settings grant it", async () => {
    const { api, calls } = fakePermissions({ origins: ["http://localhost:3000/*"] });
    expect(await hasSiteAccess(api, "http://localhost:3000/login")).toBe(true);
    expect(calls).toEqual(["contains http://localhost/*", "contains http://localhost:3000/*"]);
    expect(await hasSiteAccess(api, "http://localhost:8080/")).toBe(false);
    expect(await hasSiteAccess(api, "http://localhost/")).toBe(false);
  });

  it("counts a site granted without a port for each of its ports", async () => {
    const { api, calls } = fakePermissions({ origins: ["http://localhost/*"] });
    expect(await hasSiteAccess(api, "http://localhost:3000/")).toBe(true);
    expect(calls).toEqual(["contains http://localhost/*"]);
  });

  it("takes a rejected pattern with a port as no access, as Firefox may", async () => {
    const { api } = fakePermissions();
    const contains = api.permissions.contains;
    api.permissions.contains = (p) =>
      p.origins?.some((it) => new URL(it.replace("*", "x")).port !== "")
        ? Promise.reject(new Error("Invalid host permission"))
        : contains(p);
    expect(await hasSiteAccess(api, "https://example.com:8443/")).toBe(false);
  });

  it("counts access to every website for the URL's site", async () => {
    const { api, calls } = fakePermissions({ origins: [...WEB_PAGES] });
    expect(await hasSiteAccess(api, "https://example.com/")).toBe(true);
    expect(calls).toEqual(["contains https://example.com/*"]);
  });

  it.each(["about:blank", "chrome://extensions/", "file:///etc/hosts", "not a url"])(
    "has no access to ask about for %s",
    async (url) => {
      const { api, calls } = fakePermissions({ origins: [...WEB_PAGES] });
      expect(await hasSiteAccess(api, url)).toBe(false);
      expect(calls).toEqual([]);
    },
  );

  it("asks for every website before anything is awaited", async () => {
    const { api, calls } = fakePermissions();
    const result = requestAllSites(api);
    // While the click's user gesture lasts.
    expect(calls).toEqual(["request http://*/*,https://*/*"]);
    expect(await result).toBe(true);
    expect(await hasSiteAccess(api)).toBe(true);
  });

  it("asks for the URL's site before anything is awaited", async () => {
    const { api, calls } = fakePermissions();
    const result = requestSite(api, "https://example.com:8443/login");
    expect(calls).toEqual(["request https://example.com/*"]);
    expect(await result).toBe(true);
    expect(await hasSiteAccess(api, "https://example.com/")).toBe(true);
    expect(await hasSiteAccess(api)).toBe(false);
  });

  it("reports a denied request", async () => {
    const { api } = fakePermissions({ grant: false });
    expect(await requestAllSites(api)).toBe(false);
    expect(await requestSite(api, "https://example.com/")).toBe(false);
    expect(await hasSiteAccess(api, "https://example.com/")).toBe(false);
  });

  it.each([undefined, "about:blank", "chrome://extensions/", "file:///etc/hosts"])(
    "doesn't ask for %s",
    async (url) => {
      const { api, calls } = fakePermissions();
      expect(await requestSite(api, url)).toBe(false);
      expect(calls).toEqual([]);
    },
  );

  it("observes the access now and after each grant or withdrawal, until unsubscribed", async () => {
    const { api, calls, onAdded, onRemoved } = fakePermissions();
    const answers: boolean[] = [];
    const unsubscribe = observeSiteAccess(api, "https://example.com/login", (granted) => answers.push(granted));
    await settle();
    expect(calls).toEqual(["onAdded.add", "onRemoved.add", "contains https://example.com/*"]);
    expect(answers).toEqual([false]);

    await requestSite(api, "https://example.com/");
    onAdded.fire({ origins: ["https://example.com/*"] });
    await settle();
    onRemoved.fire({ origins: ["https://other.example/*"] });
    await settle();
    expect(answers).toEqual([false, true, true]);

    unsubscribe();
    expect(calls.slice(-2)).toEqual(["onAdded.remove", "onRemoved.remove"]);
    expect(onAdded.listeners.size).toBe(0);
    expect(onRemoved.listeners.size).toBe(0);
  });

  it("drops the answers of older checks and those after the unsubscribe", async () => {
    const pending: ((granted: boolean) => void)[] = [];
    const { api, onAdded } = fakePermissions();
    api.permissions.contains = () => new Promise((resolve) => pending.push(resolve));
    const answers: boolean[] = [];
    const unsubscribe = observeSiteAccess(api, undefined, (granted) => answers.push(granted));
    onAdded.fire({ origins: [...WEB_PAGES] });
    pending[1]!(true);
    pending[0]!(false);
    await settle();
    expect(answers).toEqual([true]);

    onAdded.fire({ origins: [...WEB_PAGES] });
    unsubscribe();
    pending[2]!(false);
    await settle();
    expect(answers).toEqual([true]);
  });

  it("ignores a failed check", async () => {
    const { api, onAdded } = fakePermissions({ origins: [...WEB_PAGES] });
    const contains = api.permissions.contains;
    api.permissions.contains = () => Promise.reject(new Error("permissions"));
    const answers: boolean[] = [];
    const unsubscribe = observeSiteAccess(api, undefined, (granted) => answers.push(granted));
    await settle();
    expect(answers).toEqual([]);

    api.permissions.contains = contains;
    onAdded.fire({ origins: [...WEB_PAGES] });
    await settle();
    expect(answers).toEqual([true]);
    unsubscribe();
  });
});
