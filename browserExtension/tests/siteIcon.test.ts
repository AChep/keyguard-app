// The site icons of the logins that match a page: Chromium's favicon cache,
// Firefox's favicon of the tab, for frames of the tab's site only.
import { describe, expect, it } from "vitest";
import { siteIconSource, siteIconUrl, type SiteIconEnv } from "../src/lib/background/siteIcon";

const CHROMIUM: SiteIconEnv = {
  source: "chromium",
  getURL: (path) => `chrome-extension://abcdefghijklmnopabcdefghijklmnop${path}`,
};
const FIREFOX: SiteIconEnv = {
  source: "firefox",
  getURL: (path) => `moz-extension://6a0b7c1e-0d4f-4d3e-9a51-2f7b8c9d0e1f${path}`,
};
const NONE: SiteIconEnv = { ...CHROMIUM, source: "none" };

const PAGE = "https://login.example.com/sign-in";
const FAVICON = "https://login.example.com/favicon.ico";

describe("siteIconSource", () => {
  it("is the tab's favicon on Firefox, and Chromium's cache with the favicon permission only", () => {
    expect(siteIconSource({ firefox: true, permissions: [] })).toBe("firefox");
    expect(siteIconSource({ firefox: false, permissions: ["storage", "favicon"] })).toBe("chromium");
    expect(siteIconSource({ firefox: false, permissions: ["storage"] })).toBe("none");
  });
});

describe("siteIconUrl on Chromium", () => {
  it("asks the extension's favicon endpoint for the tab's URL, encoded, at 32 pixels", () => {
    const tabUrl = "https://login.example.com/sign-in?next=/a b&x=1#top";
    const icon = siteIconUrl({ url: PAGE, tabUrl }, CHROMIUM)!;
    expect(icon).toBe(
      "chrome-extension://abcdefghijklmnopabcdefghijklmnop/_favicon/" +
        "?pageUrl=https%3A%2F%2Flogin.example.com%2Fsign-in%3Fnext%3D%2Fa%2520b%26x%3D1%23top&size=32",
    );
    const url = new URL(icon);
    expect([...url.searchParams.keys()]).toEqual(["pageUrl", "size"]);
    expect(url.searchParams.get("pageUrl")).toBe(new URL(tabUrl).href);
  });

  it("uses the tab's URL for a frame of its site", () => {
    const icon = siteIconUrl(
      { url: "https://accounts.example.com/embedded", tabUrl: "https://www.example.com/" },
      CHROMIUM,
    );
    expect(new URL(icon!).searchParams.get("pageUrl")).toBe("https://www.example.com/");
  });

  it("serves http and https pages alike", () => {
    const icon = siteIconUrl({ url: "http://intranet.example/", tabUrl: "http://intranet.example/" }, CHROMIUM);
    expect(new URL(icon!).searchParams.get("pageUrl")).toBe("http://intranet.example/");
  });

  it("gives none for pages that aren't http(s)", () => {
    for (const tabUrl of [
      "chrome://settings/",
      "file:///home/ann/a.html",
      "about:blank",
      "data:text/html,x",
      "chrome-extension://abc/page.html",
    ]) {
      expect(siteIconUrl({ url: tabUrl, tabUrl }, CHROMIUM), tabUrl).toBeNull();
    }
    expect(siteIconUrl({ url: "about:blank", tabUrl: PAGE }, CHROMIUM)).toBeNull();
    expect(siteIconUrl({ url: PAGE }, CHROMIUM)).toBeNull();
    expect(siteIconUrl({ url: PAGE, tabUrl: "not a url" }, CHROMIUM)).toBeNull();
  });

  it("gives none for a frame of another site than its tab, nor of another scheme", () => {
    expect(
      siteIconUrl({ url: "https://auth.idp.example/login", tabUrl: "https://shop.example.com/cart" }, CHROMIUM),
    ).toBeNull();
    expect(siteIconUrl({ url: "http://login.example.com/", tabUrl: PAGE }, CHROMIUM)).toBeNull();
  });
});

describe("siteIconUrl on Firefox", () => {
  it("takes the tab's https favicon", () => {
    expect(siteIconUrl({ url: PAGE, tabUrl: PAGE, favIconUrl: FAVICON }, FIREFOX)).toBe(FAVICON);
    // A CDN's icon is the site's all the same.
    expect(siteIconUrl({ url: PAGE, tabUrl: PAGE, favIconUrl: "https://cdn.example.net/icon.png" }, FIREFOX)).toBe(
      "https://cdn.example.net/icon.png",
    );
  });

  it("takes data:image favicons, but no other data", () => {
    const png = "data:image/png;base64,iVBORw0KGgo=";
    expect(siteIconUrl({ url: PAGE, tabUrl: PAGE, favIconUrl: png }, FIREFOX)).toBe(png);
    expect(siteIconUrl({ url: PAGE, tabUrl: PAGE, favIconUrl: "data:image/svg+xml,%3Csvg/%3E" }, FIREFOX)).toBe(
      "data:image/svg+xml,%3Csvg/%3E",
    );
    for (const favIconUrl of [
      "data:text/html,<script>alert(1)</script>",
      "data:,image/png",
      "data:application/octet-stream;base64,AA==",
    ]) {
      expect(siteIconUrl({ url: PAGE, tabUrl: PAGE, favIconUrl }, FIREFOX), favIconUrl).toBeNull();
    }
  });

  it("refuses favicons of other schemes", () => {
    for (const favIconUrl of [
      "http://login.example.com/favicon.ico",
      "chrome://global/skin/icons/defaultFavicon.svg",
      "moz-extension://6a0b7c1e-0d4f-4d3e-9a51-2f7b8c9d0e1f/icon.png",
      "blob:https://login.example.com/0b7c1e6a-0d4f-4d3e-9a51-2f7b8c9d0e1f",
      "javascript:alert(1)",
      "file:///home/ann/icon.png",
      "",
      "favicon.ico",
    ]) {
      expect(siteIconUrl({ url: PAGE, tabUrl: PAGE, favIconUrl }, FIREFOX), favIconUrl).toBeNull();
    }
    expect(siteIconUrl({ url: PAGE, tabUrl: PAGE }, FIREFOX)).toBeNull();
  });

  it("gives none for a frame of another site than its tab, or a page that isn't http(s)", () => {
    expect(
      siteIconUrl({ url: "https://auth.idp.example/login", tabUrl: PAGE, favIconUrl: FAVICON }, FIREFOX),
    ).toBeNull();
    expect(siteIconUrl({ url: "about:blank", tabUrl: "about:blank", favIconUrl: FAVICON }, FIREFOX)).toBeNull();
  });
});

describe("siteIconUrl elsewhere", () => {
  it("gives none", () => {
    expect(siteIconUrl({ url: PAGE, tabUrl: PAGE, favIconUrl: FAVICON }, NONE)).toBeNull();
  });
});
