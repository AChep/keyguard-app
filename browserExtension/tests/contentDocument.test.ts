// @vitest-environment happy-dom
// The content script's document token, against a background that addresses
// frames only, as on Firefox, which has no `documentId`: a document refuses
// the commands of the document before it in the same frame and origin. Also
// the top frame's answer to a `top_probe`, what it re-checks as the page
// changes, the password step after a username step, the sign-in after a
// fill, and a script that an update of the extension orphaned or replaced.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import { ContentScriptContext } from "wxt/utils/content-script-context";
import contentScript from "../entrypoints/content/index";
import { TabWatch, type MessageSenderLike } from "../src/lib/background/frames";
import { PageController, type AppAccess } from "../src/lib/background/pages";
import { orphanSafe } from "../src/lib/content/orphan";
import { OptionsStore } from "../src/lib/options/store";
import { documentCommandOf, type ContentCommand } from "../src/lib/page/messages";
import { ProtocolError } from "../src/lib/protocol/session";
import { byId, render } from "./support/dom";

const PAGE = "https://login.example.com/sign-in";
const ORIGIN = "https://login.example.com";
const FORM = `<form><input id="username" name="username"><input id="password" name="password" type="password"></form>`;
const STALE = "0".repeat(32);

/** The top frame of tab 7, as Firefox reports it: no `documentId`. */
const SENDER: MessageSenderLike = {
  id: fakeBrowser.runtime.id,
  tab: { id: 7, url: PAGE },
  frameId: 0,
  url: PAGE,
  origin: ORIGIN,
};

const stops: (() => void)[] = [];

/** Starts the content script in a new document of the frame, showing [html]; returns its invalidation. */
function startDocument(html = FORM): () => void {
  render(html);
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
/** Whether the background leaves each `password_step` unanswered, as while it fills the step: the report's token stays usable. */
let holdSteps: boolean;

beforeEach(() => {
  fakeBrowser.reset();
  (window as unknown as { happyDOM: { setURL(url: string): void } }).happyDOM.setURL(PAGE);
  (fakeBrowser.i18n as { getMessage(name: string): string }).getMessage = () => "";
  tokens = [];
  messages = [];
  sent = [];
  fillPrompt = null;
  holdSteps = false;
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
    tab: async () => ({ url: PAGE, active: true }),
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
    if (holdSteps && (message as { kind: string }).kind === "password_step") return true;
    void pages.onContentMessage(message, SENDER).then(sendResponse);
    return true;
  });
});

afterEach(() => {
  for (const stop of stops.splice(0)) stop();
  document.body.innerHTML = "";
});

/**
 * Lets mutation records, the watcher's debounce and its scan run, and what
 * follows them, as a report's answer or a sign-in's wait: on real timers.
 */
const scanned = () => new Promise((resolve) => setTimeout(resolve, 400));

/** The clicks on the sign-in button `#go` and the submits of its form `#f`, in order. */
function recordSignIn(): string[] {
  const events: string[] = [];
  byId<HTMLElement>("go").addEventListener("click", () => events.push("click"));
  byId<HTMLFormElement>("f").addEventListener("submit", (event) => {
    // happy-dom would navigate.
    event.preventDefault();
    events.push("submit");
  });
  return events;
}

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

  it("looks for a password step among the password fields a change added only, one detection per form", async () => {
    const passwords = Array.from({ length: 5 }, () => `<input type="password" autocomplete="new-password">`).join("");
    startDocument(`<form id="settings"><input id="name" name="username" value="ann">${passwords}</form>`);
    await scanned();
    // Detection asks every field of the form for its boxes.
    const detections = vi.spyOn(byId("name"), "getClientRects");

    const search = document.createElement("p");
    search.innerHTML = `<input name="q">`;
    document.body.append(search);
    await scanned();
    expect(detections).not.toHaveBeenCalled();

    const another = document.createElement("input");
    another.type = "password";
    byId("settings").append(another, document.createElement("input"));
    await scanned();
    expect(detections).toHaveBeenCalledTimes(1);
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

describe("the password step", () => {
  const USERNAME_STEP = `<form><input id="username" name="username" autocomplete="username"></form>`;
  const PASSWORD_STEP = `<form><input id="account" name="username" readonly value="ann"><input id="password" type="password"></form>`;

  // happy-dom has no layout: each input gets a box of its own, one under
  // the other, and is what's on top there.
  beforeEach(() => {
    const inputs = () => Array.from(document.querySelectorAll("input"));
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
      return new DOMRect(10, 10 + 40 * Math.max(inputs().indexOf(this as HTMLInputElement), 0), 200, 32);
    });
    vi.spyOn(document, "elementFromPoint").mockImplementation((_x, y) => inputs()[Math.floor((y - 10) / 40)] ?? null);
  });
  afterEach(() => vi.restoreAllMocks());

  /** Waits until [done] holds, on real timers: the content script reads the options and the background answers meanwhile. */
  async function until(done: () => boolean) {
    for (let i = 0; i < 100 && !done(); i++) await new Promise((resolve) => setTimeout(resolve, 10));
  }

  /** The tab goes to the background, or comes back to the front, as the browser tells the page. */
  function setVisible(visible: boolean) {
    const state = vi.spyOn(document, "visibilityState", "get");
    if (visible) state.mockRestore();
    else state.mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
  }

  /** The content scripts' `password_step` reports, in order. */
  const reports = () => messages.filter((it) => it.kind === "password_step") as unknown as { step: string; account?: string }[];

  /** A fill of target `step` with the token of [report], for the document that sent it last. */
  const stepFill = (report: { step: string }, password = "pw") =>
    ({ kind: "fill", target: "step", step: report.step, username: "bob", password, doc: tokens.at(-1)!, origin: ORIGIN }) as const;

  /**
   * The popup fills the username step of a new document, which then
   * navigates to the password step, [next]; the invalidation of its document.
   */
  async function signIn(next = PASSWORD_STEP): Promise<() => void> {
    const stopFirst = startDocument(USERNAME_STEP);
    fillPrompt = async () => ({ username: "ann", password: "secret" });
    await pages.fillTab(7, PAGE, "h1");
    expect(byId("username").value).toBe("ann");
    stopFirst();
    return startDocument(next);
  }

  it("fills the password of the next page after a fill of the username alone, asking once, without its username", async () => {
    await signIn();
    await until(() => byId("password").value !== "");
    expect(byId("password").value).toBe("secret");
    expect(byId("account").value).toBe("ann");
    const fills = sent.filter((it) => it.message.kind === "fill").map((it) => it.message);
    expect(reports()).toEqual([
      { kind: "password_step", step: expect.stringMatching(/^[0-9a-f]{32}$/), url: PAGE, account: "ann", doc: expect.stringMatching(/^[0-9a-f]{32}$/) },
    ]);
    expect(fills).toEqual([
      expect.objectContaining({ target: "page", username: "ann", password: "secret" }),
      expect.objectContaining({ target: "step", step: reports()[0]!.step, password: "secret" }),
    ]);
    expect(fills[1]).not.toHaveProperty("username");
  });

  it("asks when a password field that a style showed gets focus: the watcher sees no change", async () => {
    await signIn(PASSWORD_STEP.replace(`type="password"`, `type="password" style="visibility: hidden"`));
    await scanned();
    expect(reports()).toEqual([]);
    byId("password").style.visibility = "";
    byId("password").focus();
    await until(() => byId("password").value !== "");
    expect(byId("password").value).toBe("secret");
  });

  it("fills the password that a single-page app shows after the username, and never the username", async () => {
    startDocument(`<form><input id="username" autocomplete="username"><input id="password" type="password" style="visibility: hidden"></form>`);
    fillPrompt = async () => ({ username: "ann", password: "secret" });
    await pages.fillTab(7, PAGE, "h1");
    expect([byId("username").value, byId("password").value]).toEqual(["ann", ""]);
    byId("password").style.visibility = "";
    byId("password").focus();
    await until(() => byId("password").value !== "");
    expect([byId("username").value, byId("password").value]).toEqual(["ann", "secret"]);
  });

  it("asks again once a username fill was answered, for a step shown during the fill: one shown before waits for focus", async () => {
    fillPrompt = async () => ({ username: "ann", password: "secret" });
    // A single-page app shows the step by a style, which the watcher doesn't see, as the username is filled.
    const stop = startDocument(`<form><input id="username" autocomplete="username"></form><form><input id="password" type="password" style="visibility: hidden"></form>`);
    byId("username").addEventListener("input", () => {
      byId("password").style.visibility = "";
      byId("password").focus();
    });
    await pages.fillTab(7, PAGE, "h1");
    // The fill wrote the username alone; the look after it finds the step.
    expect([byId("username").value, byId("password").value]).toEqual(["ann", ""]);
    await until(() => byId("password").value !== "");
    expect(byId("password").value).toBe("secret");
    stop();

    // A step the background refused, before the username was filled: the
    // user didn't go on to it, after one fill or two in a row.
    startDocument(`
      <form><input id="username" autocomplete="username"><input type="password" style="visibility: hidden"></form>
      <form><input id="password" type="password"></form>`);
    await until(() => reports().length === 2);
    await pages.fillTab(7, PAGE, "h1");
    await pages.fillTab(7, PAGE, "h1");
    expect(byId("username").value).toBe("ann");
    await scanned();
    expect(byId("password").value).toBe("");
    expect(reports()).toHaveLength(2);
    // Focus tells when they do.
    byId("password").focus();
    await until(() => byId("password").value !== "");
    expect(byId("password").value).toBe("secret");
    expect(reports()).toHaveLength(3);
  });

  it("fills the field its token names, once, with the password alone: a later report doesn't take it over", async () => {
    holdSteps = true;
    startDocument(`
      <form><input name="username" readonly value="ann"><input id="p1" type="password"></form>
      <form><input name="username" readonly value="ann"><input id="p2" type="password"></form>`);
    // Two steps at once: a scan can't tell which one the user is on, and reports neither.
    await scanned();
    expect(reports()).toEqual([]);
    // Focus tells.
    byId("p1").focus();
    byId("p2").focus();
    await until(() => reports().length === 2);
    const [first, second] = reports();
    expect(await deliver(stepFill(first!))).toEqual({ ok: true });
    expect([byId("p1").value, byId("p2").value]).toEqual(["pw", ""]);
    expect(Array.from(document.querySelectorAll<HTMLInputElement>("[name=username]"), (it) => it.value)).toEqual(["ann", "ann"]);
    for (const step of [first!, { step: "0".repeat(32) }]) expect(await deliver(stepFill(step))).toEqual({ ok: false, reason: "no_form" });
    // Nor once its form shows another account than the background was told.
    document.querySelectorAll<HTMLInputElement>("[name=username]")[1]!.value = "bob";
    expect(await deliver(stepFill(second!))).toEqual({ ok: false, reason: "no_form" });
    expect(byId("p2").value).toBe("");
  });

  it("asks nothing for a field the page covers, or in a tab in the background, and fills nothing there", async () => {
    holdSteps = true;
    vi.spyOn(document, "elementFromPoint").mockReturnValue(document.body);
    startDocument(PASSWORD_STEP);
    byId("password").focus();
    await scanned();
    expect(reports()).toEqual([]);

    vi.spyOn(document, "elementFromPoint").mockReturnValue(byId("password"));
    const hidden = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    byId("password").blur();
    byId("password").focus();
    await scanned();
    expect(reports()).toEqual([]);

    hidden.mockRestore();
    byId("password").blur();
    byId("password").focus();
    await until(() => reports().length === 1);
    // The tab went to the background, or the page covered the field, before the fill came.
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    expect(await deliver(stepFill(reports()[0]!))).toEqual({ ok: false, reason: "no_form" });
    expect(byId("password").value).toBe("");
  });

  it("asks for the step of a page that loaded in a background tab once the tab is in front, detecting nothing before", async () => {
    const stopFirst = startDocument(USERNAME_STEP);
    fillPrompt = async () => ({ username: "ann", password: "secret" });
    await pages.fillTab(7, PAGE, "h1");
    stopFirst();
    // The user went to another tab as the next page loaded. Detection asks
    // every field of the form for its boxes.
    setVisible(false);
    const detections = vi.spyOn(Element.prototype, "getClientRects");
    startDocument(PASSWORD_STEP);
    await scanned();
    expect(reports()).toEqual([]);
    expect(detections).not.toHaveBeenCalled();

    setVisible(true);
    await until(() => byId("password").value !== "");
    expect(byId("password").value).toBe("secret");
    expect(reports()).toHaveLength(1);
  });

  it("asks on a return to the tab only when it missed a step in the background, once", async () => {
    holdSteps = true;
    startDocument(PASSWORD_STEP.replace(`type="password"`, `type="password" style="visibility: hidden"`));
    await scanned();
    // Shown by a style, which no scan sees: a return to the tab that missed nothing asks nothing, focus tells.
    byId("password").style.visibility = "";
    setVisible(false);
    setVisible(true);
    await scanned();
    expect(reports()).toEqual([]);

    // A single-page app shows the next step while the tab is in the background.
    setVisible(false);
    document.body.innerHTML = PASSWORD_STEP;
    await scanned();
    expect(reports()).toEqual([]);
    setVisible(true);
    await until(() => reports().length === 1);
    setVisible(false);
    setVisible(true);
    await scanned();
    expect(reports()).toHaveLength(1);
  });

  it("asks on a return to the tab for a step shown as the username was filled, the tab put in the background before the look after the fill", async () => {
    holdSteps = true;
    fillPrompt = async () => ({ username: "ann", password: "secret" });
    startDocument(`<form><input id="username" autocomplete="username"></form><form><input id="password" type="password" style="visibility: hidden"></form>`);
    byId("username").addEventListener("input", () => (byId("password").style.visibility = ""));
    await pages.fillTab(7, PAGE, "h1");
    expect([byId("username").value, byId("password").value]).toEqual(["ann", ""]);
    // Its report on focus right after the fill goes unanswered, as one the
    // background refused before it had the fill's answer.
    byId("password").focus();
    await until(() => reports().length === 1);
    setVisible(false);
    await scanned();
    expect(reports()).toHaveLength(1);
    // The look after the fill, not a plain scan: a step found since the fill began is asked for again, once.
    setVisible(true);
    await until(() => reports().length === 2);
    setVisible(false);
    setVisible(true);
    await scanned();
    expect(reports()).toHaveLength(2);
  });

  it("writes nothing into a field that the page's focus or key handlers changed first", async () => {
    holdSteps = true;
    const stop = startDocument(PASSWORD_STEP);
    await until(() => reports().length === 1);
    const field = byId("password");
    field.addEventListener("keydown", () => (field.value = "typed"), { once: true });
    expect(await deliver(stepFill(reports()[0]!))).toEqual({ ok: false, reason: "obscured" });
    expect(field.value).toBe("typed");

    // Shown as plain text, though it still says it's the current password.
    stop();
    startDocument(PASSWORD_STEP.replace(`type="password"`, `type="password" autocomplete="current-password"`));
    await until(() => reports().length === 2);
    byId("password").addEventListener("focus", () => (byId("password").type = "text"), { once: true });
    expect(await deliver(stepFill(reports()[1]!))).toEqual({ ok: false, reason: "obscured" });
    expect(byId("password").value).toBe("");
  });

  it("fills nothing once the reported field is in another form, or the page at another URL", async () => {
    holdSteps = true;
    const stop = startDocument(`
      <form><input name="username" readonly value="ann"><input id="password" type="password"></form>
      <form id="other"><input name="username" readonly value="ann"></form>`);
    await until(() => reports().length === 1);
    // A single-page app reuses the input in another form, of the same account.
    byId("other").append(byId("password"));
    expect(await deliver(stepFill(reports()[0]!))).toEqual({ ok: false, reason: "no_form" });
    expect(byId("password").value).toBe("");

    stop();
    startDocument(PASSWORD_STEP);
    await until(() => reports().length === 2);
    history.pushState(null, "", "/elsewhere");
    expect(await deliver(stepFill(reports()[1]!))).toEqual({ ok: false, reason: "no_form" });
    expect(byId("password").value).toBe("");
  });

  it("forgets each report once the background answered it", async () => {
    startDocument(PASSWORD_STEP);
    await until(() => reports().length === 1);
    // Nothing is armed: the background answers without a fill.
    await scanned();
    expect(await deliver(stepFill(reports()[0]!))).toEqual({ ok: false, reason: "no_form" });
    // Nor a report of focus.
    byId("password").focus();
    await until(() => reports().length === 2);
    await scanned();
    expect(await deliver(stepFill(reports()[1]!))).toEqual({ ok: false, reason: "no_form" });
    expect(byId("password").value).toBe("");
  });

  it("signs in on a step whose username the site filled with the account, with the option on", async () => {
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ submitAfterFill: true });
    await signIn(`<form id="f"><input id="account" name="username" value="ann"><input id="password" type="password"><button id="go">Sign in</button></form>`);
    const events = recordSignIn();
    await until(() => events.length === 2);
    expect(events).toEqual(["click", "submit"]);
    expect([byId("account").value, byId("password").value]).toEqual(["ann", "secret"]);
  });

  it("tells which fills wrote the username of a sign-in alone", async () => {
    const fill = { kind: "fill", target: "focused", username: "ann", password: "pw", origin: ORIGIN } as const;
    for (const [html, step] of [
      [USERNAME_STEP, true],
      // The password shows later.
      [`<form><input name="username"><input type="password" style="visibility: hidden"></form>`, true],
      [`<form><input name="username"><input type="password" autocomplete="new-password"></form>`, false],
      [`<form><input name="username"><input type="password"></form>`, false],
    ] as const) {
      const stop = startDocument(html);
      document.querySelector("input")!.focus();
      await deliver({ kind: "save_bar_show" });
      expect(await deliver({ ...fill, doc: tokens.at(-1)! }), html).toEqual(step ? { ok: true, passwordStep: true } : { ok: true });
      stop();
    }
  });

  it("asks nothing with the option off, or on a form with a username to type", async () => {
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ fillPasswordStep: false });
    const stop = await signIn();
    byId("password").focus();
    await scanned();
    expect(reports()).toEqual([]);
    expect(byId("password").value).toBe("");

    stop();
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ fillPasswordStep: true });
    startDocument();
    byId("password").focus();
    await scanned();
    expect(reports()).toEqual([]);
  });
});

describe("the sign-in after a fill", () => {
  const SIGN_IN = `<form id="f"><input id="username" name="username"><input id="password" type="password"><button id="go">Sign in</button></form>`;

  it("follows the answer to the fill, once", async () => {
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ submitAfterFill: true });
    startDocument(SIGN_IN);
    const events = recordSignIn();
    fillPrompt = async () => ({ username: "ann", password: "secret" });
    await pages.fillTab(7, PAGE, "h1");
    expect(sent.at(-1)!.message).toMatchObject({ kind: "fill", target: "page", submit: true });
    // The background has the answer: nothing clicked yet.
    expect(events).toEqual([]);
    await scanned();
    expect(events).toEqual(["click", "submit"]);
    await scanned();
    expect(events).toHaveLength(2);
  });

  it("clicks nothing once the page signed in on the fill's own events", async () => {
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ submitAfterFill: true });
    startDocument(SIGN_IN);
    const events = recordSignIn();
    // An AJAX sign-in as the password changes, while the fill runs.
    byId("password").addEventListener("change", () => byId<HTMLFormElement>("f").requestSubmit());
    fillPrompt = async () => ({ username: "ann", password: "secret" });
    await pages.fillTab(7, PAGE, "h1");
    expect(events).toEqual(["submit"]);
    await scanned();
    expect(events).toEqual(["submit"]);
  });

  it("is cancelled by the next fill, and not asked for by a command without `submit: true`", async () => {
    startDocument(SIGN_IN);
    const events = recordSignIn();
    await deliver({ kind: "save_bar_show" });
    const own = tokens.at(-1)!;
    const fill = { kind: "fill", target: "page", username: "ann", password: "pw", doc: own, origin: ORIGIN } as const;
    expect(await deliver({ ...fill, submit: true })).toEqual({ ok: true });
    // Any fill, even one that finds nothing to fill.
    expect(await deliver({ kind: "fill_totp", target: "page", code: "123456", doc: own, origin: ORIGIN })).toEqual({ ok: false, reason: "obscured" });
    await deliver({ ...fill, submit: "yes" } as unknown as ContentCommand);
    await scanned();
    expect(events).toEqual([]);
  });
});

describe("an update of the extension", () => {
  /** Starts a copy of the content script with WXT's own context, as the browser or the background's re-injection does. */
  function startCopy(): ContentScriptContext {
    const ctx = new ContentScriptContext("content", { noScriptStartedPostMessage: true });
    contentScript.main(ctx);
    stops.push(() => ctx.abort());
    return ctx;
  }

  // The save bar's iframe; at about:blank here.
  const settings = (window as unknown as { happyDOM: { settings: { enableIframePageLoading: boolean } } }).happyDOM.settings;
  const id = fakeBrowser.runtime.id;
  beforeEach(() => {
    settings.enableIframePageLoading = true;
    vi.spyOn(fakeBrowser.runtime, "getURL").mockReturnValue("about:blank");
  });
  afterEach(() => {
    settings.enableIframePageLoading = false;
    fakeBrowser.runtime.id = id;
    vi.restoreAllMocks();
  });

  /** The hosts of the extension's UI in the page. */
  const hosts = () => Array.from(document.documentElement.children).filter((it) => it.localName === "div");

  /** A login submitted on the page: the background asks the content script to show the save bar, in the UI's host. */
  async function offerSave() {
    const candidate = { username: "ann", password: "new-pass" };
    await pages.onContentMessage({ kind: "save_candidate", candidate, doc: STALE }, SENDER);
    await scanned();
  }

  it("tears down a script it orphaned as a field gets focus: its UI goes, and nothing is sent", async () => {
    render(FORM);
    const ctx = startCopy();
    await offerSave();
    expect(hosts()).toHaveLength(1);

    // Chromium: the extension's APIs are gone, and a call throws at once.
    fakeBrowser.runtime.id = undefined as never;
    const sendMessage = vi.spyOn(fakeBrowser.runtime, "sendMessage").mockImplementation(() => {
      throw new Error("Extension context invalidated.");
    });
    byId("username").focus();
    await scanned();
    // Not `isValid`, whose read would tear the script down itself.
    expect(ctx.signal.aborted).toBe(true);
    expect(hosts()).toEqual([]);
    expect(sendMessage).not.toHaveBeenCalled();
  });

  it("sends nothing from an orphaned script, and never throws where the call throws at once", async () => {
    let reads = 0;
    let gone = false;
    const ctx = {
      get isInvalid() {
        reads++;
        return gone;
      },
    };
    const send = orphanSafe(ctx, (_message: string): Promise<string> => {
      throw new Error("Extension context invalidated.");
    });
    await expect(send("menu_open")).resolves.toBeUndefined();
    // Asked again after the failure: the extension may have gone meanwhile.
    expect(reads).toBe(2);
    gone = true;
    const answer = vi.fn(async () => "token");
    await expect(orphanSafe(ctx, answer)("menu_open")).resolves.toBeUndefined();
    expect(answer).not.toHaveBeenCalled();
  });

  it("starts a copy that replaces the live one in its frame: one UI, one answer", async () => {
    render(FORM);
    const first = startCopy();
    await offerSave();
    expect(hosts()).toHaveLength(1);

    const second = startCopy();
    expect(first.signal.aborted).toBe(true);
    expect(second.signal.aborted).toBe(false);
    expect(hosts()).toEqual([]);
    // The new copy shows the offer again, as on the page after a navigation.
    await scanned();
    expect(hosts()).toHaveLength(1);
    messages.length = 0;
    await deliver({ kind: "save_bar_show" });
    await scanned();
    expect(messages.map((it) => it.kind)).toEqual(["save_bar_open"]);
    expect(hosts()).toHaveLength(1);
  });
});
