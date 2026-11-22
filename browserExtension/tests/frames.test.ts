// Frames come from the browser's sender, never from messages.
import { describe, expect, it } from "vitest";
import { POPUP_PAGE } from "../src/lib/background/api";
import {
  frameOf,
  frameTarget,
  isOwnPage,
  sameSite,
  TabWatch,
  type MessageSenderLike,
} from "../src/lib/background/frames";
import { MENU_PAGE, SAVE_BAR_PAGE } from "../src/lib/page/messages";

const ID = "abcdefghijklmnopabcdefghijklmnop";

function sender(fields: Partial<MessageSenderLike>): MessageSenderLike {
  const url = "https://login.example.com/a?b=1";
  return { id: ID, tab: { id: 7, url }, frameId: 0, documentId: "doc-0", url, ...fields };
}

describe("frameOf", () => {
  it("takes the URL, origin, frame and document from the sender", () => {
    expect(frameOf(sender({ origin: "https://login.example.com" }), ID)).toEqual({
      tabId: 7,
      frameId: 0,
      documentId: "doc-0",
      url: "https://login.example.com/a?b=1",
      origin: "https://login.example.com",
    });
  });

  it("matches a cross-origin iframe by its own URL", () => {
    const frame = frameOf(sender({ frameId: 4, documentId: "doc-4", url: "https://auth.idp.example/login" }), ID);
    expect(frame).toMatchObject({
      frameId: 4,
      documentId: "doc-4",
      url: "https://auth.idp.example/login",
      origin: "https://auth.idp.example",
    });
  });

  it("keeps the document's token of the message", () => {
    const doc = "0123456789abcdef0123456789abcdef";
    expect(frameOf(sender({ documentId: undefined }), ID, doc)).toEqual({
      tabId: 7,
      frameId: 0,
      doc,
      url: "https://login.example.com/a?b=1",
      origin: "https://login.example.com",
    });
  });

  it("takes the origin of the tab's top frame from the tab, for a frame of another origin only", () => {
    const top = "https://shop.example.net/cart";
    expect(
      frameOf(sender({ frameId: 4, url: "https://auth.idp.example/login", tab: { id: 7, url: top } }), ID),
    ).toMatchObject({
      origin: "https://auth.idp.example",
      topOrigin: "https://shop.example.net",
    });
    expect(
      frameOf(
        sender({ frameId: 2, url: "about:blank", origin: "https://auth.idp.example", tab: { id: 7, url: top } }),
        ID,
      ),
    ).toMatchObject({
      origin: "https://auth.idp.example",
      topOrigin: "https://shop.example.net",
    });
    expect(
      frameOf(sender({ frameId: 4, tab: { id: 7, url: "https://login.example.com/other" } }), ID),
    ).not.toHaveProperty("topOrigin");
    // The top frame is the tab's page, whatever the tab's URL says.
    expect(frameOf(sender({ tab: { id: 7, url: top } }), ID)).not.toHaveProperty("topOrigin");
  });

  it("leaves the top frame's origin to ask when the tab's URL doesn't tell", () => {
    // An about:blank popup has its opener's origin, which its URL doesn't say.
    for (const tab of [{ id: 7, url: "about:blank" }, { id: 7, url: "chrome://newtab/" }, { id: 7 }]) {
      expect(frameOf(sender({ frameId: 4, tab }), ID), JSON.stringify(tab)).toHaveProperty("topOrigin", null);
    }
    for (const tab of [{ id: 7, url: "about:blank" }, { id: 7 }]) {
      expect(frameOf(sender({ tab }), ID), JSON.stringify(tab)).not.toHaveProperty("topOrigin");
    }
  });

  it("refuses opaque origins: sandboxed frames and CSP sandbox", () => {
    expect(frameOf(sender({ origin: "null" }), ID)).toBeNull();
    expect(frameOf(sender({ url: "about:srcdoc", origin: "null" }), ID)).toBeNull();
    // An origin that disagrees with the URL.
    expect(frameOf(sender({ origin: "https://evil.example" }), ID)).toBeNull();
  });

  it("serves about:blank as the http(s) origin it inherits, if the browser says which", () => {
    expect(frameOf(sender({ frameId: 2, url: "about:blank", origin: "https://login.example.com" }), ID)).toMatchObject({
      url: "https://login.example.com/",
      origin: "https://login.example.com",
    });
    // Firefox reports no origin: nothing to match.
    expect(frameOf(sender({ url: "about:blank" }), ID)).toBeNull();
    expect(frameOf(sender({ url: "about:blank", origin: "chrome-extension://other" }), ID)).toBeNull();
  });

  it("serves about:blank and srcdoc frames as their parent's origin, never sandboxed or data: frames", () => {
    const parent = "https://login.example.com";
    for (const url of ["about:blank", "about:srcdoc"]) {
      expect(frameOf(sender({ frameId: 2, documentId: "doc-2", url, origin: parent }), ID), url).toEqual({
        tabId: 7,
        frameId: 2,
        documentId: "doc-2",
        url: `${parent}/`,
        origin: parent,
      });
    }
    // A sandboxed srcdoc frame and a data: frame run as an opaque origin.
    expect(frameOf(sender({ url: "about:srcdoc", origin: "null" }), ID), "sandboxed srcdoc").toBeNull();
    expect(frameOf(sender({ url: "data:text/html,<form>", origin: "null" }), ID), "data:").toBeNull();
    // Only about: URLs inherit: a data: URL can't claim a web origin.
    expect(frameOf(sender({ url: "data:text/html,<form>", origin: parent }), ID), "data: with an origin").toBeNull();
  });

  it("refuses everything but http(s) pages of our content scripts in tabs", () => {
    for (const url of [
      "data:text/html,x",
      "blob:https://a.example/1",
      "file:///etc/passwd",
      "chrome://settings/",
      `chrome-extension://${ID}/popup.html`,
    ]) {
      expect(frameOf(sender({ url }), ID), url).toBeNull();
    }
    expect(frameOf(sender({ id: "another-extension" }), ID)).toBeNull();
    expect(frameOf(sender({ tab: undefined }), ID)).toBeNull();
    expect(frameOf(sender({ frameId: undefined }), ID)).toBeNull();
    expect(frameOf(sender({ documentLifecycle: "prerender" }), ID)).toBeNull();
    expect(frameOf(sender({ documentLifecycle: "active" }), ID)).not.toBeNull();
    expect(frameOf(undefined, ID)).toBeNull();
  });

  it("marks a frame of a tab in a private window", () => {
    const url = "https://login.example.com/a?b=1";
    expect(frameOf(sender({ tab: { id: 7, url, incognito: true } }), ID)).toMatchObject({ privateWindow: true });
    expect(frameOf(sender({ tab: { id: 7, url, incognito: false } }), ID)).not.toHaveProperty("privateWindow");
    expect(frameOf(sender({}), ID)).not.toHaveProperty("privateWindow");
  });
});

describe("frameTarget", () => {
  it("targets the document where the browser can, else the frame", () => {
    expect(frameTarget({ frameId: 3, documentId: "doc-3" }, true)).toEqual({ frameId: 3, documentId: "doc-3" });
    // Firefox rejects `documentId`.
    expect(frameTarget({ frameId: 3, documentId: "doc-3" }, false)).toEqual({ frameId: 3 });
    expect(frameTarget({ frameId: 3 }, true)).toEqual({ frameId: 3 });
  });
});

describe("isOwnPage", () => {
  it("accepts our page, also under a dynamic ID", () => {
    expect(isOwnPage({ id: ID, url: `chrome-extension://${ID}/inline-menu.html` }, ID, "/inline-menu.html")).toBe(true);
    expect(
      isOwnPage({ id: ID, url: "chrome-extension://3f1c9e0a-dynamic/inline-menu.html" }, ID, "/inline-menu.html"),
    ).toBe(true);
    expect(isOwnPage({ id: ID, url: "moz-extension://5a5c4a3e-uuid/save-bar.html" }, ID, "/save-bar.html")).toBe(true);
  });

  it("refuses content scripts, other pages and other extensions", () => {
    expect(isOwnPage({ id: ID, url: "https://evil.example/inline-menu.html" }, ID, "/inline-menu.html")).toBe(false);
    expect(isOwnPage({ id: ID, url: `chrome-extension://${ID}/popup.html` }, ID, "/inline-menu.html")).toBe(false);
    expect(isOwnPage({ id: "other", url: `chrome-extension://other/inline-menu.html` }, ID, "/inline-menu.html")).toBe(
      false,
    );
    expect(isOwnPage(undefined, ID, "/inline-menu.html")).toBe(false);
  });

  it("takes the popup's port from the popup page only: Firefox's web-accessible frames are extension pages too", () => {
    expect(isOwnPage({ id: ID, url: `moz-extension://5a5c4a3e-uuid${POPUP_PAGE}` }, ID, POPUP_PAGE)).toBe(true);
    expect(isOwnPage({ id: ID, url: `chrome-extension://${ID}${POPUP_PAGE}?tab=1` }, ID, POPUP_PAGE)).toBe(true);
    for (const page of [MENU_PAGE, SAVE_BAR_PAGE, "/options.html", "/offscreen.html"]) {
      expect(isOwnPage({ id: ID, url: `moz-extension://5a5c4a3e-uuid${page}` }, ID, POPUP_PAGE), page).toBe(false);
    }
  });
});

describe("sameSite", () => {
  it("compares the scheme and, loosely, the last two labels: a pre-filter the app checks again", () => {
    expect(sameSite("https://login.example.com/", "https://mfa.example.com/a")).toBe(true);
    expect(sameSite("https://example.com/", "https://example.org/")).toBe(false);
    expect(sameSite("http://example.com/", "https://example.com/")).toBe(false);
    expect(sameSite("https://127.0.0.1/", "https://127.0.0.2/")).toBe(false);
    // Too loose to remember anything by: see the exact origins of ../src/lib/background/pages.ts.
    expect(sameSite("https://a.co.uk/", "https://b.co.uk/")).toBe(true);
  });
});

describe("TabWatch", () => {
  it("tells when the first watch starts and the last one ends: navigations matter only in between", async () => {
    const heard: boolean[] = [];
    const tabs = new TabWatch((watching) => heard.push(watching));
    let finish!: () => void;
    const first = tabs.run(1, "https://a.example/", () => new Promise<void>((resolve) => (finish = resolve)));
    await tabs.run(2, "https://b.example/", async () => undefined);
    expect(heard).toEqual([true]);
    finish();
    await first;
    expect(heard).toEqual([true, false]);

    // A failed action ends its watch too.
    await expect(tabs.run(1, undefined, () => Promise.reject(new Error("failed")))).rejects.toThrow("failed");
    expect(heard).toEqual([true, false, true, false]);
  });
});
