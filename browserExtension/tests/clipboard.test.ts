// Clipboard writes from the background: an offscreen document on Chromium,
// the event page's DOM on Firefox.
import { describe, expect, it } from "vitest";
import {
  ClipboardUnavailable,
  ClipboardWriter,
  clipboardRoute,
  isOffscreenCopy,
  type OffscreenApi,
  type OffscreenCopy,
} from "../src/lib/background/clipboard";

describe("clipboardRoute", () => {
  it("routes Chromium through the offscreen document and Firefox through its DOM", () => {
    expect(clipboardRoute({ firefox: false, offscreen: true, document: false })).toBe("offscreen");
    expect(clipboardRoute({ firefox: true, offscreen: false, document: true })).toBe("document");
    // A Firefox that grew `offscreen` still has its DOM.
    expect(clipboardRoute({ firefox: true, offscreen: true, document: true })).toBe("document");
    expect(clipboardRoute({ firefox: false, offscreen: false, document: true })).toBe("document");
    expect(clipboardRoute({ firefox: false, offscreen: false, document: false })).toBeNull();
    expect(clipboardRoute({ firefox: true, offscreen: false, document: false })).toBeNull();
  });
});

function fakeOffscreen() {
  const calls: string[] = [];
  let open = false;
  const api: OffscreenApi = {
    hasDocument: async () => open,
    createDocument: async ({ url, reasons }) => {
      calls.push(`create ${url} ${reasons.join()}`);
      if (open) throw new Error("Only a single offscreen document may be created.");
      open = true;
    },
    closeDocument: async () => {
      calls.push("close");
      open = false;
    },
  };
  return { api, calls };
}

describe("ClipboardWriter", () => {
  it("writes through a short-lived offscreen document", async () => {
    const { api, calls } = fakeOffscreen();
    const sent: OffscreenCopy[] = [];
    const writer = new ClipboardWriter({
      route: "offscreen",
      offscreen: api,
      offscreenUrl: "chrome-extension://id/offscreen.html",
      sendMessage: async (message) => {
        sent.push(message);
        return { ok: true };
      },
    });
    await Promise.all([writer.write("one"), writer.write("two")]);
    expect(sent).toEqual([
      { target: "offscreen", kind: "copy", text: "one" },
      { target: "offscreen", kind: "copy", text: "two" },
    ]);
    expect(calls).toEqual([
      "create chrome-extension://id/offscreen.html CLIPBOARD",
      "close",
      "create chrome-extension://id/offscreen.html CLIPBOARD",
      "close",
    ]);
    expect(sent.every(isOffscreenCopy)).toBe(true);
  });

  it("fails when the offscreen document couldn't copy, and still closes it", async () => {
    const { api, calls } = fakeOffscreen();
    const writer = new ClipboardWriter({
      route: "offscreen",
      offscreen: api,
      offscreenUrl: "chrome-extension://id/offscreen.html",
      sendMessage: async () => ({ ok: false }),
    });
    await expect(writer.write("x")).rejects.toBeInstanceOf(ClipboardUnavailable);
    expect(calls.at(-1)).toBe("close");
  });

  it("writes with the DOM on Firefox", async () => {
    const written: string[] = [];
    const writer = new ClipboardWriter({ route: "document", writeText: async (text) => void written.push(text) });
    await writer.write("secret");
    expect(written).toEqual(["secret"]);
    await expect(new ClipboardWriter({ route: null }).write("x")).rejects.toBeInstanceOf(ClipboardUnavailable);
  });

  it("answers only its own messages in the offscreen document", () => {
    expect(isOffscreenCopy({ target: "offscreen", kind: "copy", text: "x" })).toBe(true);
    expect(isOffscreenCopy({ kind: "save_candidate", candidate: { password: "x" } })).toBe(false);
    expect(isOffscreenCopy(null)).toBe(false);
  });
});
