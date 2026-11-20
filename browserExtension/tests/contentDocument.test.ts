// @vitest-environment happy-dom
// The content script's document token, against a background that addresses
// frames only, as on Firefox, which has no `documentId`: a document refuses
// the commands of the document before it in the same frame and origin. Also
// the top frame's answer to a `top_probe`, and what it re-checks as the page
// changes.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import type { ContentScriptContext } from "wxt/utils/content-script-context";
import contentScript from "../entrypoints/content/index";
import { TabWatch, type MessageSenderLike } from "../src/lib/background/frames";
import { PageController, type AppAccess } from "../src/lib/background/pages";
import { OptionsStore } from "../src/lib/options/store";
import { documentCommandOf, type ContentCommand } from "../src/lib/page/messages";
import { ProtocolError } from "../src/lib/protocol/session";
import { byId, render } from "./support/dom";

const PAGE = "https://login.example.com/sign-in";
const FORM = `<form><input id="username" name="username"><input id="password" name="password" type="password"></form>`;
const STALE = "0".repeat(32);

/** The top frame of tab 7, as Firefox reports it: no `documentId`. */
const SENDER: MessageSenderLike = {
  id: fakeBrowser.runtime.id,
  tab: { id: 7, url: PAGE },
  frameId: 0,
  url: PAGE,
  origin: "https://login.example.com",
};

const stops: (() => void)[] = [];

/** Starts the content script in a new document of the frame; returns its invalidation. */
function startDocument(): () => void {
  render(FORM);
  const invalidated: (() => void)[] = [];
  contentScript.main({ onInvalidated: (callback: () => void) => void invalidated.push(callback) } as unknown as ContentScriptContext);
  const stop = () => {
    for (const callback of invalidated.splice(0)) callback();
  };
  stops.push(stop);
  return stop;
}

/** `tabs.sendMessage`: the command reaches the frame's content script, which answers. */
function deliver(command: ContentCommand): Promise<unknown> {
  return new Promise((resolve) => {
    void fakeBrowser.runtime.onMessage.trigger(command, { id: fakeBrowser.runtime.id }, resolve).then((results: unknown[]) => {
      if (!results.includes(true)) resolve(undefined);
    });
  });
}

/** The tokens of the content scripts' messages to the background, in order. */
let tokens: string[];
/** The content scripts' messages to the background, in order. */
let messages: { kind: string }[];
let pages: PageController;
let sent: { message: ContentCommand; target: { frameId: number; documentId?: string } }[];
/** The app's prompt for a `fill`, which releases the values once the user approves. */
let fillPrompt: (() => Promise<unknown>) | null;

beforeEach(() => {
  fakeBrowser.reset();
  (window as unknown as { happyDOM: { setURL(url: string): void } }).happyDOM.setURL(PAGE);
  (fakeBrowser.i18n as { getMessage(name: string): string }).getMessage = () => "";
  tokens = [];
  messages = [];
  sent = [];
  fillPrompt = null;
  const app: AppAccess = {
    snapshot: { kind: "unlocked" },
    sessionId: 1,
    supports: () => true,
    unlock: async () => null,
    request: (async (type: string) => {
      if (type === "fill" && fillPrompt !== null) return fillPrompt();
      throw new ProtocolError("not_supported");
    }) as AppAccess["request"],
  };
  pages = new PageController({
    extensionId: fakeBrowser.runtime.id,
    app,
    options: new OptionsStore(fakeBrowser.storage.local),
    passkeys: { conditionalFor: () => null, pick: async () => undefined },
    sendToFrame: (_tabId, message, target) => {
      sent.push({ message, target });
      return deliver(message);
    },
    sendToTab: (_tabId, message) => deliver(message),
    injectContentScript: async () => undefined,
    tab: async () => ({ url: PAGE }),
    siteIcon: async () => null,
    tabs: new TabWatch(),
    openPopup: async () => undefined,
    documentIds: false,
    probeTimeoutMs: 200,
  });
  // The background: content scripts' messages come with an empty sender here.
  fakeBrowser.runtime.onMessage.addListener((message: unknown, sender: { id?: string }, sendResponse: (response: unknown) => void) => {
    if (sender.id !== undefined) return false;
    tokens.push((message as { doc: string }).doc);
    messages.push(message as { kind: string });
    void pages.onContentMessage(message, SENDER).then(sendResponse);
    return true;
  });
});

afterEach(() => {
  for (const stop of stops.splice(0)) stop();
  document.body.innerHTML = "";
});

/** The popup's fill of the tab. The app's prompt holds the values until [approve]. */
function fillFromPopup() {
  let approve!: () => void;
  let prompt!: () => void;
  const approved = new Promise<void>((resolve) => (approve = resolve));
  const prompted = new Promise<void>((resolve) => (prompt = resolve));
  fillPrompt = async () => {
    prompt();
    await approved;
    return { username: "ann", password: "secret" };
  };
  return { filling: pages.fillTab(7, PAGE, "h1"), prompted, approve };
}

describe("the document token", () => {
  it("is 128 random bits per document, sent with each request", async () => {
    const stopFirst = startDocument();
    await deliver({ kind: "save_bar_show" });
    const first = new Set(tokens);
    tokens.length = 0;
    stopFirst();
    startDocument();
    await deliver({ kind: "save_bar_show" });
    const second = new Set(tokens);
    expect(first.size).toBe(1);
    expect(second.size).toBe(1);
    expect([...first, ...second]).toEqual([expect.stringMatching(/^[0-9a-f]{32}$/), expect.stringMatching(/^[0-9a-f]{32}$/)]);
    expect([...first][0]).not.toBe([...second][0]);
  });

  it("refuses a fill for the document before a navigation within the origin, on a frame-only target", async () => {
    const stopFirst = startDocument();
    const { filling, prompted, approve } = fillFromPopup();
    // The app prompts; meanwhile the frame navigates to another page of the same origin.
    await prompted;
    const first = tokens.at(-1);
    stopFirst();
    startDocument();
    approve();

    await expect(filling).rejects.toMatchObject({ code: "origin" });
    expect(sent).toHaveLength(1);
    expect(sent[0]!.target).toEqual({ frameId: 0 });
    expect(sent[0]!.message).toMatchObject({ kind: "fill", doc: first, origin: "https://login.example.com" });
    expect(byId("username").value).toBe("");
    expect(byId("password").value).toBe("");
  });

  it("lets the document that asked take its command", async () => {
    startDocument();
    const { filling, prompted, approve } = fillFromPopup();
    await prompted;
    approve();
    await filling;
    expect(sent.map((it) => it.message.kind)).toEqual(["fill"]);
    expect(byId("password").value).toBe("secret");
  });

  it("refuses each command for one document without its token or its origin", async () => {
    startDocument();
    await deliver({ kind: "save_bar_show" });
    const [own] = tokens;
    const origin = "https://login.example.com";
    const answered = [
      { kind: "fill", target: "page", username: "ann", password: "pw" },
      { kind: "fill_totp", target: "focused", code: "123456" },
      { kind: "fill_password", target: "menu", password: "pw" },
      { kind: "guard", popup: "menu" },
      { kind: "guard", popup: "save_bar" },
    ];
    for (const stamp of [{ doc: STALE, origin }, { doc: own!, origin: "https://evil.example" }, { origin }, { doc: own! }]) {
      for (const command of answered) {
        expect(await deliver({ ...command, ...stamp } as ContentCommand), `${command.kind} ${JSON.stringify(stamp)}`).toEqual({
          ok: false,
          reason: "origin",
        });
      }
    }
    // Its own token and origin get past it: no menu or save bar is open to check.
    expect(await deliver({ kind: "guard", popup: "menu", doc: own!, origin })).toEqual({ ok: false, reason: "obscured" });
    expect(await deliver({ kind: "guard", popup: "save_bar", doc: own!, origin })).toEqual({ ok: false, reason: "obscured" });
    expect(byId("password").value).toBe("");
    expect(await deliver({ ...answered[0], doc: own!, origin } as ContentCommand)).toEqual({ ok: true });
    expect(byId("password").value).toBe("pw");
  });

  it("checks every command for one document, and answers those that expect it", () => {
    for (const kind of ["fill", "fill_totp", "fill_password", "guard"]) expect(documentCommandOf(kind), kind).toBe("answer");
    for (const kind of ["menu_resize", "menu_close", "save_bar_resize", "save_bar_close"]) {
      expect(documentCommandOf(kind), kind).toBe("notify");
    }
    for (const kind of ["save_bar_show", "focus_probe", "form_probe", "top_probe", "toString", undefined]) {
      expect(documentCommandOf(kind), String(kind)).toBeNull();
    }
  });
});

describe("the top frame's origin", () => {
  it("answers a probe with a message of its own, whose sender tells the background the origin", async () => {
    startDocument();
    const probe = "c".repeat(32);
    // Nothing on the probe's own channel, which has no sender to read.
    expect(await deliver({ kind: "top_probe", probe })).toBeUndefined();
    await new Promise((resolve) => setTimeout(resolve, 0));
    const reports = messages.filter((it) => it.kind === "top_report");
    expect(reports).toEqual([{ kind: "top_report", probe, doc: expect.stringMatching(/^[0-9a-f]{32}$/) }]);
  });
});

describe("a child frame of another origin", () => {
  it("is checked by the document that holds it only, which answers for its own ancestors too", async () => {
    // Firefox's `runtime.getFrameId`, as the content script reads it at start.
    (fakeBrowser.runtime as unknown as { getFrameId(target: unknown): number }).getFrameId = (target) =>
      target instanceof Element && target.id === "child" ? 4 : -1;
    try {
      startDocument();
      const child = document.createElement("iframe");
      child.id = "child";
      document.body.append(child);
      const probe = "c".repeat(32);
      const reports = async (command: object) => {
        messages.length = 0;
        // No answer on the command's own channel: a report of its own, see PageController.parentShows.
        expect(await deliver({ kind: "frame_guard", probe, ...command } as never)).toBeUndefined();
        await new Promise((resolve) => setTimeout(resolve, 0));
        return messages.filter((it) => it.kind === "frame_report");
      };
      // happy-dom has no hit testing: the parent shows the child where asked.
      vi.spyOn(document, "elementFromPoint").mockReturnValue(child);
      const doc = expect.stringMatching(/^[0-9a-f]{32}$/);
      expect(await reports({ frame: 4, x: 10, y: 10 })).toEqual([{ kind: "frame_report", probe, ok: true, doc }]);
      // Another frame's check, or a malformed one, gets no answer here.
      expect(await reports({ frame: 5, x: 10, y: 10 })).toEqual([]);
      expect(await reports({ frame: 4, x: "left", y: 10 })).toEqual([]);
      // Covered where the popup is.
      vi.spyOn(document, "elementFromPoint").mockReturnValue(document.body);
      expect(await reports({ frame: 4, x: 10, y: 10 })).toEqual([{ kind: "frame_report", probe, ok: false, doc }]);
    } finally {
      vi.restoreAllMocks();
      delete (fakeBrowser.runtime as unknown as { getFrameId?: unknown }).getFrameId;
    }
  });
});

describe("a changing page", () => {
  /** Lets mutation records, the watcher's debounce and its scan run. */
  const scanned = () => new Promise((resolve) => setTimeout(resolve, 400));

  it("re-checks the focused field after inputs change, not after other elements are added", async () => {
    startDocument();
    const field = byId("password");
    field.focus();
    await scanned();
    // Detection asks every field of the form for its boxes.
    const detections = vi.spyOn(field, "getClientRects");

    const banner = document.createElement("div");
    banner.innerHTML = "<p>Ad</p><x-card></x-card>";
    document.body.append(banner);
    await scanned();
    expect(detections).not.toHaveBeenCalled();

    document.querySelector("form")!.append(document.createElement("input"));
    await scanned();
    expect(detections).toHaveBeenCalled();
  });

  it("forgets the last field with a role once it leaves the page", async () => {
    startDocument();
    const field = byId("password");
    field.focus();
    field.blur();
    await scanned();
    const isConnected = Object.getOwnPropertyDescriptor(Node.prototype, "isConnected")!.get!;
    let reads = 0;
    Object.defineProperty(field, "isConnected", {
      configurable: true,
      get() {
        reads++;
        return isConnected.call(this);
      },
    });
    const own = tokens.at(-1)!;
    const fill = { kind: "fill", doc: own, origin: "https://login.example.com", target: "focused", username: "ann", password: "pw" } as const;
    // Without focus in a field, a fill for the focused one takes the last.
    await deliver(fill);
    expect(reads).toBeGreaterThan(0);

    document.body.innerHTML = "<p>Signed in</p>";
    await scanned();
    reads = 0;
    await deliver(fill);
    expect(reads).toBe(0);
  });
});
