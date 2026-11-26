// The background's side of content scripts and embedded pages: frames from
// the sender, tokens, secrets to one frame and document, checks before
// acting, "Fill TOTP", the generator, the save bar, the shortcut and the
// popup's fill.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import type { ExtensionStatus } from "../src/lib/background/api";
import { BackgroundController } from "../src/lib/background/controller";
import { TabWatch, type FrameRef, type MessageSenderLike } from "../src/lib/background/frames";
import {
  CONTINUE_WINDOW_MS,
  PageController,
  PASSKEY_WAIT_MS,
  PASSWORD_STEP_WINDOW_MS,
  SAVE_TTL_MS,
  TOKEN_TTL_MS,
  TOTP_WINDOW_MS,
} from "../src/lib/background/pages";
import type { NativeCryptoNoise } from "../src/lib/crypto/noise";
import { loadNoise } from "../src/lib/crypto/wasm";
import { OptionsStore } from "../src/lib/options/store";
import {
  MENU_PORT,
  parseContentRequest,
  parseMenuRequest,
  SAVE_BAR_PORT,
  type MenuView,
} from "../src/lib/page/messages";
import type { JsonObject } from "../src/lib/protocol/framing";
import type { Capability, SuggestItem } from "../src/lib/protocol/messages";
import { ProtocolError } from "../src/lib/protocol/session";
import { FakeHost, FakeNativePort } from "./support/fakeApp";
import {
  contentSender,
  docOf,
  FakePort,
  finish,
  form,
  fromContent,
  ID,
  iconOf,
  item,
  LOGIN_ORIGIN,
  LOGIN_URL,
  pageSender,
  settle,
  setup,
  timeUp,
  topForm,
  type Sent,
} from "./support/pages";

beforeEach(() => {
  fakeBrowser.reset();
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("content script requests", () => {
  it("ignores malformed requests and senders that aren't our content scripts", async () => {
    const { pages } = setup();
    const doc = docOf(contentSender());
    expect(parseContentRequest({ kind: "menu_open", role: "admin", doc })).toBeNull();
    expect(parseContentRequest({ kind: "save_candidate", candidate: { username: "ann" }, doc })).toBeNull();
    expect(parseContentRequest({ kind: "save_candidate", candidate: { password: "x".repeat(5000) }, doc })).toBeNull();
    expect(parseContentRequest({ kind: "focus_report", probe: "short", role: null, doc })).toBeNull();
    expect(parseContentRequest({ kind: "top_report", probe: "short", doc })).toBeNull();
    for (const step of [
      { url: LOGIN_URL },
      { step: "short" },
      { step: "f".repeat(32), url: 7 },
      { step: "f".repeat(32), account: "" },
      { step: "f".repeat(32), account: 7 },
      { step: "f".repeat(32), account: "x".repeat(5000) },
    ]) {
      expect(parseContentRequest({ kind: "password_step", ...step, doc }), JSON.stringify(step)).toBeNull();
    }
    for (const parent of [
      { frame: 0, x: 1, y: 1 },
      { frame: 2, x: Number.NaN, y: 1 },
      { frame: 2.5, x: 1, y: 1 },
      "2",
    ]) {
      expect(
        parseContentRequest({ kind: "frame_report", probe: "f".repeat(32), ok: true, parent, doc }),
        JSON.stringify(parent),
      ).toBeNull();
    }
    expect(parseContentRequest({ kind: "form_report", probe: "f".repeat(32), focused_at: -1, doc })).toBeNull();
    expect(parseContentRequest({ kind: "form_report", probe: "f".repeat(32), focused_at: "now", doc })).toBeNull();
    // The frame of the menu or the card beside it: no other popup.
    const card = { kind: "popup_shown", popup: "menu_details" };
    for (const popup of [undefined, "save_bar", "menu_detail", 7]) {
      expect(parseContentRequest({ kind: "popup_shown", popup, shown: true, doc }), String(popup)).toBeNull();
    }
    // The menu's only shows, on no side: it has no room to lack.
    expect(parseContentRequest({ kind: "popup_shown", popup: "menu", doc })).toBeNull();
    for (const shown of [false, "yes", 1]) {
      expect(parseContentRequest({ kind: "popup_shown", popup: "menu", shown, doc }), String(shown)).toBeNull();
    }
    expect(parseContentRequest({ kind: "popup_shown", popup: "menu", shown: true, side: "left", doc })).toBeNull();
    expect(parseContentRequest({ kind: "popup_shown", popup: "menu", shown: true, room: false, doc })).toBeNull();
    expect(parseContentRequest({ ...card, doc })).toBeNull();
    expect(parseContentRequest({ ...card, shown: "yes", doc })).toBeNull();
    expect(parseContentRequest({ ...card, shown: true, side: "up", doc })).toBeNull();
    expect(parseContentRequest({ ...card, shown: true, side: "left", doc })).toEqual({
      ...card,
      shown: true,
      side: "left",
      doc,
    });
    // A hidden card is on no side.
    expect(parseContentRequest({ ...card, shown: false, side: "left", doc })).toEqual({ ...card, shown: false, doc });
    // Only a hidden card lacks room.
    expect(parseContentRequest({ ...card, shown: false, room: false, doc })).toEqual({
      ...card,
      shown: false,
      room: false,
      doc,
    });
    expect(parseContentRequest({ ...card, shown: true, side: "left", room: false, doc })).toEqual({
      ...card,
      shown: true,
      side: "left",
      doc,
    });
    for (const room of [true, "no", 0, null]) {
      expect(parseContentRequest({ ...card, shown: false, room, doc }), String(room)).toBeNull();
    }
    expect(parseContentRequest({ kind: "form_report", probe: "f".repeat(32), focused_at: null, doc })).toBeNull();
    expect(
      parseContentRequest({ kind: "form_report", probe: "f".repeat(32), focused_at: null, writable: "yes", doc }),
    ).toBeNull();
    expect(await fromContent(pages, { kind: "menu_open", role: "username" }, contentSender({ id: "other" }))).toEqual({
      error: "unsupported_page",
    });
  });

  it("takes each request only with the token of its document", async () => {
    const { pages } = setup();
    const valid = { kind: "menu_open", role: "username", webauthn: false };
    for (const doc of [undefined, null, 7, "", "f".repeat(31), "f".repeat(33), "F".repeat(32), "g".repeat(32)]) {
      expect(parseContentRequest({ ...valid, doc }), String(doc)).toBeNull();
      expect(await pages.onContentMessage({ ...valid, doc }, contentSender()), String(doc)).toBeNull();
    }
    const doc = docOf(contentSender());
    expect(parseContentRequest({ ...valid, doc })).toEqual({ ...valid, doc });
    for (const request of [
      { kind: "save_candidate", candidate: { password: "pw" } },
      { kind: "username_step", username: "ann" },
      { kind: "password_step", step: "f".repeat(32) },
      { kind: "password_step", step: "f".repeat(32), account: "ann", url: LOGIN_URL },
      { kind: "save_bar_open" },
      { kind: "popup_shown", popup: "menu", shown: true },
      { kind: "popup_shown", popup: "menu_details", shown: false, room: false },
      { kind: "focus_report", probe: "f".repeat(32), role: null },
      { kind: "form_report", probe: "f".repeat(32), focused_at: null, writable: false },
      { kind: "top_report", probe: "f".repeat(32) },
      { kind: "frame_report", probe: "f".repeat(32), ok: true, parent: { frame: 2, x: 1.5, y: 0 } },
    ]) {
      expect(parseContentRequest(request), request.kind).toBeNull();
      expect(parseContentRequest({ ...request, doc }), request.kind).toEqual({ ...request, doc });
    }
  });

  it("opens no menu in opaque or sandboxed frames", async () => {
    const { pages } = setup();
    for (const sender of [
      contentSender({ origin: "null" }),
      contentSender({ url: "about:srcdoc", origin: "null" }),
      contentSender({ url: "data:text/html,<form>" }),
    ]) {
      expect(await fromContent(pages, { kind: "menu_open", role: "username" }, sender)).toEqual({
        error: "unsupported_page",
      });
    }
  });

  it("opens no menu when the user turned the inline menu off", async () => {
    const { pages } = setup();
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ inlineMenu: false });
    expect(await fromContent(pages, { kind: "menu_open", role: "username" }, contentSender())).toEqual({
      error: "not_allowed",
    });
  });
});

describe("inline menu", () => {
  it("binds a token once, for its tab only, before it expires", async () => {
    const { pages, advance } = setup();
    const result = (await fromContent(pages, { kind: "menu_open", role: "username" }, contentSender())) as {
      token: string;
    };

    // A content script can't pose as the menu page.
    const fromPage = new FakePort(MENU_PORT, contentSender());
    pages.connect(fromPage);
    expect(fromPage.disconnected).toBe(true);

    // The menu page in another tab can't use it, and gets dropped after a few tries.
    const otherTab = new FakePort(MENU_PORT, pageSender("/inline-menu.html", 8));
    pages.connect(otherTab);
    for (let i = 0; i < 4; i++) otherTab.emit({ kind: "bind", token: result.token });
    expect(otherTab.posted).toEqual([]);
    expect(otherTab.disconnected).toBe(true);

    const port = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(port);
    port.emit({ kind: "bind", token: result.token });
    expect(port.posted[0]).toEqual({ kind: "bound" });

    // Used up.
    const again = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(again);
    again.emit({ kind: "bind", token: result.token });
    expect(again.posted).toEqual([]);

    // Expired.
    const late = (await fromContent(pages, { kind: "menu_open", role: "username" }, contentSender())) as {
      token: string;
    };
    advance(TOKEN_TTL_MS + 1);
    const latePort = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(latePort);
    latePort.emit({ kind: "bind", token: late.token });
    expect(latePort.posted).toEqual([]);
  });

  /** A `menu_open` of the content script of [sender]; its token. */
  async function menuToken(pages: PageController, sender = contentSender(), webauthn = false): Promise<string> {
    return ((await fromContent(pages, { kind: "menu_open", role: "username", webauthn }, sender)) as { token: string })
      .token;
  }

  /** The menu's page binds [token]. */
  async function bindMenu(pages: PageController, token: string): Promise<FakePort> {
    const port = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(port);
    port.emit({ kind: "bind", token });
    await settle();
    return port;
  }

  it("asks the app as the token is issued, and shows that answer once the page binds", async () => {
    const context = setup();
    let answer: () => void = () => undefined;
    context.state.app = (request) =>
      request.type === "suggest" ? new Promise((resolve) => (answer = () => resolve(undefined))) : undefined;
    const token = await menuToken(context.pages);
    // The menu's page still loads: the app has the request already.
    expect(context.requests).toEqual([{ type: "suggest", url: LOGIN_URL }]);
    answer();
    await settle();
    const port = await bindMenu(context.pages, token);
    expect(port.lastView()).toMatchObject({
      kind: "items",
      items: [{ handle: "h1" }, { handle: "h2" }],
      icon: iconOf(LOGIN_URL),
    });
    expect(context.requests).toHaveLength(1);

    // The page binds before the app answers: the answer shows when it comes.
    const next = await bindMenu(context.pages, await menuToken(context.pages));
    expect(next.lastView()).toEqual({ kind: "loading" });
    answer();
    await settle();
    expect(next.lastView()).toMatchObject({ kind: "items", items: [{ handle: "h1" }, { handle: "h2" }] });
    expect(context.requests).toHaveLength(2);
  });

  it("shows the early answer only in the menu its token binds", async () => {
    const context = setup();
    const FRAME_URL = "https://login.example.com/frame";
    context.state.app = (request) =>
      request.type === "suggest"
        ? {
            blocked: false,
            save_blocked: false,
            truncated: false,
            items: [item(request.url === FRAME_URL ? "framed" : "top")],
          }
        : undefined;
    const top = await menuToken(context.pages);
    const framed = await menuToken(context.pages, contentSender({ frameId: 5, documentId: "doc-5", url: FRAME_URL }));
    await settle();
    // The page of the second token binds first.
    expect((await bindMenu(context.pages, framed)).lastView()).toMatchObject({
      kind: "items",
      items: [{ handle: "framed" }],
    });
    expect((await bindMenu(context.pages, top)).lastView()).toMatchObject({
      kind: "items",
      items: [{ handle: "top" }],
    });
    expect(context.requests).toHaveLength(2);
  });

  it("cancels the early request when the menu is replaced, its tab closes or its token expires", async () => {
    const context = setup();
    context.state.app = (request) => (request.type === "suggest" ? new Promise(() => undefined) : undefined);
    const cancelled = () => context.state.signals.filter((it) => it.type === "suggest").map((it) => it.signal?.aborted);
    // The frame opens another menu.
    await menuToken(context.pages);
    await menuToken(context.pages);
    expect(cancelled()).toEqual([true, false]);
    context.pages.tabRemoved(7);
    expect(cancelled()).toEqual([true, true]);

    // The page binds too late.
    const late = await menuToken(context.pages);
    context.advance(TOKEN_TTL_MS + 1);
    const port = await bindMenu(context.pages, late);
    expect(port.posted).toEqual([]);
    expect(cancelled()).toEqual([true, true, true]);

    // A bound menu's close cancels it, as for its later requests.
    const bound = await bindMenu(context.pages, await menuToken(context.pages));
    expect(cancelled().at(-1)).toBe(false);
    bound.close();
    expect(cancelled().at(-1)).toBe(true);
  });

  it("asks again once bound when the vault or the session changed while the page loaded", async () => {
    const context = setup();
    const cancelled = () => context.signalOf("suggest")?.aborted;
    // The same state again: the early request stays.
    let token = await menuToken(context.pages);
    context.pages.statusChanged();
    expect(cancelled()).toBe(false);
    context.state.sessionId = 2;
    context.pages.statusChanged();
    expect(cancelled()).toBe(true);
    let port = await bindMenu(context.pages, token);
    expect(context.requests).toHaveLength(2);
    expect(port.lastView()).toMatchObject({ kind: "items", items: [{ handle: "h1" }, { handle: "h2" }] });

    token = await menuToken(context.pages);
    context.state.status = { kind: "locked", unlocking: false };
    context.pages.statusChanged();
    expect(cancelled()).toBe(true);
    port = await bindMenu(context.pages, token);
    expect(port.lastView()).toEqual({ kind: "locked", unlocking: false });
    expect(context.requests).toHaveLength(3);

    // Locked when the menu opens: nothing to ask early.
    await bindMenu(context.pages, await menuToken(context.pages));
    expect(context.requests).toHaveLength(3);
  });

  it("tells the menu of the sender's document that its frame shows, also before its page bound", async () => {
    const { pages, openMenu } = setup();
    const shown = (port: FakePort) => port.posted.filter((it) => it.kind === "shown").length;
    const { port } = await openMenu("username");
    expect(shown(port)).toBe(0);
    await fromContent(pages, { kind: "popup_shown", popup: "menu", shown: true });
    // Another document's word counts for nothing.
    await fromContent(
      pages,
      { kind: "popup_shown", popup: "menu", shown: true },
      contentSender({ documentId: "doc-next" }),
    );
    await fromContent(
      pages,
      { kind: "popup_shown", popup: "menu", shown: true },
      contentSender({ frameId: 5, documentId: "doc-5" }),
    );
    expect(shown(port)).toBe(1);

    // The frame shows after a while without the page's height, which may be before it binds.
    const token = await menuToken(pages);
    await fromContent(pages, { kind: "popup_shown", popup: "menu", shown: true });
    const next = await bindMenu(pages, token);
    expect(next.posted.slice(0, 2)).toEqual([{ kind: "bound" }, { kind: "shown" }]);
  });

  it("suggests for the frame's own URL: a cross-origin iframe is matched by its URL", async () => {
    const { openMenu, requests } = setup();
    const { port } = await openMenu(
      "username",
      contentSender({
        frameId: 5,
        documentId: "doc-5",
        url: "https://auth.idp.example/login?x=1",
        origin: "https://auth.idp.example",
      }),
    );
    expect(requests).toEqual([{ type: "suggest", url: "https://auth.idp.example/login?x=1" }]);
    // The icon is the frame's too: siteIconUrl gives none for a frame of another site than its tab.
    expect(port.lastView()).toMatchObject({
      kind: "items",
      role: "username",
      host: "auth.idp.example",
      icon: iconOf("https://auth.idp.example/login?x=1"),
    });
  });

  it("shows names, usernames and details only: the pages of the menu and its card never get secrets", async () => {
    const { openMenu, bindDetails } = setup();
    const { port, details } = await openMenu("username");
    const card = await bindDetails(details);
    port.emit({ kind: "select", handle: "h1", top: 8, height: 48 });
    await settle();
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(JSON.stringify(port.posted)).not.toContain("secret");
    // The fill hid the card, which shows again for the row still selected once it ended.
    expect(card.posted.map((it) => it.kind)).toEqual(["bound", "view", "hidden", "view"]);
    expect(JSON.stringify(card.posted)).not.toContain("secret");
    // What the login has, not its values.
    expect(card.posted[1]).toEqual({
      kind: "view",
      seq: 1,
      handle: "h1",
      item: {
        name: "Item h1",
        username: "ann",
        account: "Personal",
        copies: ["copy_username", "copy_password", "copy_totp"],
        reprompt: false,
        matched: true,
        last: false,
        icon: iconOf(LOGIN_URL),
        passkeys: [],
      },
    });
    expect(port.lastView()).toMatchObject({
      kind: "items",
      items: [
        { handle: "h1", name: "Item h1", username: "ann", account: "Personal", has_totp: true, reprompt: false },
        { handle: "h2", name: "Item h2" },
      ],
    });
  });

  it("checks the page first, then sends the values to the one frame and document", async () => {
    const { openMenu, sent, requests } = setup();
    const { port } = await openMenu("current-password");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(sent.map((it) => it.message.kind)).toEqual(["guard", "fill"]);
    expect(sent.every((it) => it.tabId === 7 && it.target?.frameId === 3 && it.target.documentId === "doc-3")).toBe(
      true,
    );
    expect(sent.map((it) => (it.message as { doc?: string }).doc)).toEqual([
      docOf(contentSender()),
      docOf(contentSender()),
    ]);
    expect(sent[1]!.message).toEqual({
      kind: "fill",
      doc: docOf(contentSender()),
      origin: "https://login.example.com",
      target: "menu",
      username: "ann",
      password: "secret-h1",
    });
    expect(requests.at(-1)).toEqual({ type: "fill", handle: "h1", url: LOGIN_URL });
  });

  it("asks the app for nothing when the page covers or fades the field or the menu", async () => {
    const { openMenu, state, sent, requests } = setup();
    state.contentScript = (message) => (message.kind === "guard" ? { ok: false, reason: "obscured" } : { ok: true });
    const { port } = await openMenu("username");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(requests.map((it) => it.type)).toEqual(["suggest"]);
    expect(sent.map((it) => it.message.kind)).toEqual(["guard"]);
    expect(port.posted).toContainEqual({ kind: "error", error: "obscured" });
  });

  it("suggests for the URL the content script reads as the menu opens, of the sender's origin only", async () => {
    const { pages, requests } = setup();
    const ROUTE = "https://login.example.com/sign-in/password";
    await fromContent(pages, { kind: "menu_open", role: "username", url: ROUTE });
    expect(requests.at(-1)).toEqual({ type: "suggest", url: ROUTE });
    // Another origin's, or an about:blank frame's own, is the sender's URL.
    for (const url of ["https://evil.example/", "about:blank"]) {
      await fromContent(pages, { kind: "menu_open", role: "username", url });
      expect(requests.at(-1), url).toEqual({ type: "suggest", url: LOGIN_URL });
    }
  });

  it("shows the matches for the frame's URL now instead of filling, when a single-page app changed it since the menu opened", async () => {
    const { openMenu, state, sent, requests } = setup();
    const ROUTE = "https://login.example.com/admin";
    state.contentScript = (message) => (message.kind === "guard" ? { ok: true, url: ROUTE } : { ok: true });
    const { port } = await openMenu("username");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(sent.map((it) => it.message.kind)).toEqual(["guard"]);
    expect(requests).toEqual([
      { type: "suggest", url: LOGIN_URL },
      { type: "suggest", url: ROUTE },
    ]);
    expect(port.lastView()).toMatchObject({ kind: "items" });
    expect(port.posted.filter((it) => it.kind === "error")).toEqual([]);
    // The next pick is for the new URL.
    port.emit({ kind: "totp", handle: "h1" });
    await settle();
    expect(sent.map((it) => it.message.kind)).toEqual(["guard", "guard", "fill_totp"]);
    expect(requests.at(-1)).toEqual({ type: "totp", handle: "h1", url: ROUTE });

    // A blocked URI covers the new URL: the menu says so, and nothing fills.
    state.contentScript = (message) => (message.kind === "guard" ? { ok: true, url: `${ROUTE}/users` } : { ok: true });
    state.app = (request) =>
      request.type === "suggest" && request.url === `${ROUTE}/users`
        ? { blocked: true, save_blocked: true, truncated: false, items: [] }
        : undefined;
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(port.lastView()).toEqual({ kind: "blocked", host: "login.example.com" });
    expect(sent.filter((it) => it.message.kind === "fill")).toEqual([]);
  });

  it("refuses a fill when the frame answers the checks with another origin's URL", async () => {
    const { openMenu, state, sent, requests } = setup();
    state.contentScript = (message) =>
      message.kind === "guard" ? { ok: true, url: "https://evil.example/" } : { ok: true };
    const { port } = await openMenu("username");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(port.posted).toContainEqual({ kind: "error", error: "origin" });
    expect(sent.map((it) => it.message.kind)).toEqual(["guard"]);
    expect(requests.map((it) => it.type)).toEqual(["suggest"]);
  });

  it("asks each parent of another origin that the frame names to check it, on browsers without IntersectionObserver v2", async () => {
    const { openMenu, state, sent, requests } = setup();
    // The menu's frame 3 sits in frame 2 of its origin, in frame 1 of another, in the top frame of a third.
    const answers: Record<string, unknown> = {
      guard: { ok: true, url: LOGIN_URL, parent: { frame: 2, x: 140, y: 90 } },
      "frame_guard 2": { ok: true, parent: { frame: 1, x: 200, y: 150 } },
      "frame_guard 1": { ok: true },
    };
    state.contentScript = (message) => {
      const key = message.kind === "frame_guard" ? `frame_guard ${message.frame}` : message.kind;
      return answers[key] ?? { ok: true };
    };
    const { port } = await openMenu("username");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(sent.map((it) => [it.message.kind, it.target?.frameId])).toEqual([
      ["guard", 3],
      ["frame_guard", undefined],
      ["frame_guard", undefined],
      ["fill", 3],
    ]);
    expect(sent[1]!.message).toMatchObject({ kind: "frame_guard", frame: 2, x: 140, y: 90 });
    expect(sent[2]!.message).toMatchObject({ kind: "frame_guard", frame: 1, x: 200, y: 150 });
    expect(requests.at(-1)).toMatchObject({ type: "fill", handle: "h1" });

    // A parent that covers the frame refuses; nothing is released.
    answers["frame_guard 1"] = { ok: false, reason: "obscured" };
    sent.length = 0;
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(sent.map((it) => it.message.kind)).toEqual(["guard", "frame_guard", "frame_guard"]);
    expect(port.posted.at(-2)).toEqual({ kind: "error", error: "obscured" });
  });

  it("refuses when a parent of another origin doesn't answer: no content script, an opaque origin, or too slow", async () => {
    const answers: unknown[] = [
      undefined,
      new Error("Could not establish connection. Receiving end does not exist."),
      { ok: true, parent: { frame: "top", x: 1, y: 1 } },
    ];
    for (const answer of answers) {
      const { openMenu, state, sent, requests } = setup();
      state.contentScript = (message) =>
        message.kind === "guard"
          ? { ok: true, parent: { frame: 2, x: 10, y: 10 } }
          : message.kind === "frame_guard"
            ? answer
            : { ok: true };
      const { port } = await openMenu("username");
      port.emit({ kind: "fill", handle: "h1" });
      await settle();
      expect(port.posted.filter((it) => it.kind === "error")).toEqual([]);
      await timeUp();
      expect(port.posted).toContainEqual({ kind: "error", error: "obscured" });
      expect(sent.map((it) => it.message.kind)).not.toContain("fill");
      expect(requests.map((it) => it.type)).toEqual(["suggest"]);
    }
  });

  it("targets frames only on Firefox, which has no documentId", async () => {
    const { openMenu, sent } = setup({ documentIds: false });
    const { port } = await openMenu("username");
    port.emit({ kind: "fill", handle: "h2" });
    await settle();
    expect(sent.map((it) => it.target)).toEqual([{ frameId: 3 }, { frameId: 3 }]);
  });

  it("sends each command for a document with its token, on every browser", async () => {
    for (const documentIds of [true, false]) {
      const context = setup({ documentIds, capabilities: ["passkeys", "save"] });
      context.state.conditional = { rpId: "example.com" };
      context.state.app = (request) =>
        request.type === "suggest" && (request.kinds as string[] | undefined)?.includes("passkey")
          ? {
              blocked: false,
              save_blocked: false,
              truncated: false,
              items: [item("p1", { passkeys: [{ handle: "pk1", user_name: "ann" }] })],
            }
          : undefined;
      // The inline menu: its card, fill, TOTP, generate, resize, a passkey and close.
      const menuSender = contentSender({ frameId: 5, documentId: "doc-5" });
      const login = await context.openMenu("new-password", menuSender, true);
      const card = await context.bindDetails(login.details);
      login.port.emit({ kind: "select", handle: "h1", top: 0, height: 48 });
      await settle();
      card.emit({ kind: "resize", height: 120, seq: 1 });
      await settle();
      login.port.emit({ kind: "deselect" });
      await settle();
      for (const request of [
        { kind: "fill", handle: "h1" },
        { kind: "totp", handle: "h1" },
        { kind: "generate" },
        { kind: "resize", height: 100 },
        { kind: "passkey", handle: "pk1" },
      ]) {
        login.port.emit(request);
        await settle();
      }
      const second = await context.openMenu("username", menuSender);
      second.port.emit({ kind: "close" });
      await settle();
      // The save bar.
      await fromContent(context.pages, { kind: "save_candidate", candidate: { password: "pw" } }, menuSender);
      const barSender = contentSender({ frameId: 0, documentId: "doc-0" });
      const { token } = (await fromContent(context.pages, { kind: "save_bar_open" }, barSender)) as { token: string };
      const bar = new FakePort(SAVE_BAR_PORT, pageSender("/save-bar.html"));
      context.pages.connect(bar);
      bar.emit({ kind: "bind", token });
      await settle();
      bar.emit({ kind: "resize", height: 200 });
      await settle();
      bar.emit({ kind: "save", offer: bar.offer() });
      await settle();
      // The shortcut, and the popup.
      const shortcut = context.pages.fillCommand(7);
      await settle();
      const probe = (context.sent.findLast((it) => it.message.kind === "focus_probe")!.message as { probe: string })
        .probe;
      await fromContent(context.pages, { kind: "focus_report", probe, role: "username" }, menuSender);
      await shortcut;
      await context.fillFromPopup("h1");

      const commands = context.sent.filter((it) => it.target !== undefined);
      const kinds = new Set(commands.map((it) => it.message.kind));
      expect(kinds, String(documentIds)).toEqual(
        new Set([
          "guard",
          "fill",
          "fill_totp",
          "fill_password",
          "menu_resize",
          "menu_close",
          "menu_details",
          "menu_details_hide",
          "save_bar_show",
          "save_bar_resize",
          "save_bar_close",
        ]),
      );
      for (const { message, target } of commands) {
        // The top frame shows the bar on its next page too.
        if (message.kind === "save_bar_show") {
          expect(message, String(documentIds)).toEqual({ kind: "save_bar_show" });
          continue;
        }
        const sender = target!.frameId === 0 ? barSender : menuSender;
        expect((message as { doc?: string }).doc, `${message.kind} ${String(documentIds)}`).toBe(docOf(sender));
      }
    }
  });

  it("refuses handles the menu didn't show", async () => {
    const { openMenu, sent } = setup();
    const { port } = await openMenu("username");
    port.emit({ kind: "fill", handle: "forged" });
    await settle();
    expect(sent).toEqual([]);
    expect(port.posted).toContainEqual({ kind: "error", error: "invalid_request" });
  });

  it("reports a frame that navigated away before the fill", async () => {
    const { openMenu, state } = setup();
    state.contentScript = (message) =>
      message.kind === "guard"
        ? { ok: true }
        : new Error("Could not establish connection. Receiving end does not exist.");
    const { port } = await openMenu("username");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(port.posted).toContainEqual({ kind: "error", error: "origin" });
  });

  it("offers Fill TOTP on code fields for 2 minutes after a login fill on the tab", async () => {
    const context = setup();
    const login = await context.openMenu("current-password");
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();

    // The code step: another page of the same origin, in the same tab.
    const otpSender = contentSender({
      frameId: 0,
      documentId: "doc-otp",
      url: "https://login.example.com/verify",
      origin: "https://login.example.com",
    });
    const otp = await context.openMenu("otp", otpSender);
    expect(otp.port.lastView()).toMatchObject({
      kind: "items",
      role: "otp",
      totp: { handle: "h1" },
      items: [{ handle: "h1" }],
    });
    otp.port.emit({ kind: "totp", handle: "h1" });
    await settle();
    expect(context.requests.at(-1)).toEqual({ type: "totp", handle: "h1", url: "https://login.example.com/verify" });
    expect(context.fills().at(-1)).toMatchObject({
      message: { kind: "fill_totp", origin: "https://login.example.com", code: "123456", target: "menu" },
      target: { frameId: 0, documentId: "doc-otp" },
    });

    context.advance(TOTP_WINDOW_MS + 1);
    const later = await context.openMenu("otp", otpSender);
    expect(later.port.lastView()).toMatchObject({ kind: "items", totp: null });
  });

  it("offers Fill TOTP on the origin of the fill only, not on another of its site or scheme", async () => {
    for (const [filled, code] of [
      ["https://login.example.com/sign-in", "https://mfa.example.com/verify"],
      ["https://a.co.uk/sign-in", "https://b.co.uk/verify"],
      ["https://login.example.com/sign-in", "http://login.example.com/verify"],
      ["https://login.example.com/sign-in", "https://login.example.com:8443/verify"],
    ]) {
      const context = setup();
      const sender = (url: string, documentId: string) =>
        contentSender({ tab: { id: 7, url }, frameId: 0, documentId, url, origin: new URL(url).origin });
      const login = await context.openMenu("current-password", sender(filled!, "doc-login"));
      login.port.emit({ kind: "fill", handle: "h1" });
      await settle();
      const otp = await context.openMenu("otp", sender(code!, "doc-otp"));
      expect(otp.port.lastView(), code).toMatchObject({ kind: "items", totp: null });
    }
  });

  it("keeps the item of a login fill for Fill TOTP, whatever the app lists after", async () => {
    const context = setup();
    const login = await context.openMenu("current-password");
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    // The matches of the code step lack it.
    context.state.suggest = [item("h3")];
    const otp = await context.openMenu("otp");
    expect(otp.port.lastView()).toMatchObject({
      kind: "items",
      items: [],
      totp: { handle: "h1", name: "Item h1", has_totp: true },
    });
    otp.port.emit({ kind: "totp", handle: "h1" });
    await settle();
    expect(context.fills().at(-1)).toMatchObject({ message: { kind: "fill_totp", code: "123456" } });
  });

  it("offers Fill TOTP for the popup's pick, from its last suggest or search on the tab", async () => {
    const context = setup();
    context.state.app = (request) =>
      request.type === "search"
        ? { blocked: false, truncated: false, items: [{ ...item("h3", { has_totp: true }), matched: false }] }
        : undefined;
    await context.pages.calls.suggest({ tabId: 7 });
    await context.pages.calls.search({ tabId: 7, query: "item" });
    for (const handle of ["h1", "h3"]) {
      await expect(context.pages.calls.fill({ tabId: 7, handle, origin: LOGIN_ORIGIN })).resolves.toBeNull();
      expect((await context.openMenu("otp")).port.lastView(), handle).toMatchObject({ totp: { handle } });
    }
  });

  it("forgets Fill TOTP with the session, whose handles died", async () => {
    const context = setup();
    const login = await context.openMenu("current-password");
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    context.state.sessionId = 2;
    const otp = await context.openMenu("otp");
    expect(otp.port.lastView()).toMatchObject({ kind: "items", totp: null });
  });

  it("forgets Fill TOTP when a login without one is filled next on the tab", async () => {
    const context = setup();
    const login = await context.openMenu("current-password");
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    // Another account, which has no TOTP.
    login.port.emit({ kind: "fill", handle: "h2" });
    await settle();
    expect(context.fills().map((it) => (it.message as { password?: string }).password)).toEqual([
      "secret-h1",
      "secret-h2",
    ]);
    const otp = await context.openMenu("otp");
    expect(otp.port.lastView()).toMatchObject({ kind: "items", totp: null });
  });

  it("generates into the new-password fields", async () => {
    const { openMenu, requests, fills } = setup();
    const { port } = await openMenu("new-password");
    expect(port.lastView()).toMatchObject({ kind: "items", role: "new-password" });
    port.emit({ kind: "generate" });
    await settle();
    expect(requests.at(-1)).toEqual({ type: "generate", url: LOGIN_URL });
    expect(fills().at(-1)!.message).toEqual({
      kind: "fill_password",
      doc: docOf(contentSender()),
      origin: "https://login.example.com",
      target: "menu",
      password: "Gen3rated!",
    });
  });

  it("checks the page, then has the app open a new login of the page's URL alone, and closes", async () => {
    expect(parseMenuRequest({ kind: "create", url: "https://evil.example/" })).toEqual({ kind: "create" });
    const context = setup();
    const { port } = await context.openMenu("username");
    expect(port.lastView()).toMatchObject({ kind: "items", create: true });
    port.emit({ kind: "create" });
    await settle();
    expect(context.requests.at(-1)).toEqual({ type: "save", url: LOGIN_URL });
    expect(context.sent.map((it) => it.message.kind)).toEqual(["guard", "menu_close"]);
    expect(port.disconnected).toBe(true);
  });

  it("asks for no new login under an overlay, and shows the app's refusal", async () => {
    const context = setup();
    const { port } = await context.openMenu("username");
    context.state.contentScript = (message) =>
      message.kind === "guard" ? { ok: false, reason: "obscured" } : undefined;
    port.emit({ kind: "create" });
    await settle();
    expect(context.requests.filter((it) => it.type === "save")).toEqual([]);
    expect(port.posted).toContainEqual({ kind: "error", error: "obscured" });

    context.state.contentScript = () => ({ ok: true });
    context.state.app = (request) => (request.type === "save" ? new ProtocolError("busy") : undefined);
    port.emit({ kind: "create" });
    await settle();
    expect(port.posted.at(-2)).toEqual({ kind: "error", error: "busy" });
    expect(port.disconnected).toBe(false);
  });

  it("unlocks first when the vault locked since the menu showed", async () => {
    const context = setup();
    const { port } = await context.openMenu("username");
    let locked = true;
    context.state.app = (request) =>
      request.type === "save" && locked ? ((locked = false), new ProtocolError("vault_locked")) : undefined;
    port.emit({ kind: "create" });
    await settle();
    expect(context.state.unlocks).toBe(1);
    expect(context.requests.filter((it) => it.type === "save")).toHaveLength(2);
    expect(port.disconnected).toBe(true);
  });

  it("offers no new login where saving is blocked or the app can't save, and refuses one", async () => {
    const blocked = setup();
    blocked.state.app = (request) =>
      request.type === "suggest"
        ? { blocked: false, save_blocked: true, truncated: false, items: [item("h1")] }
        : undefined;
    for (const [name, context] of [
      ["saving blocked", blocked],
      ["no save capability", setup({ capabilities: [] })],
    ] as const) {
      const { port } = await context.openMenu("username");
      expect(port.lastView(), name).toMatchObject({ kind: "items", create: false });
      port.emit({ kind: "create" });
      await settle();
      expect(port.posted.at(-1), name).toEqual({ kind: "error", error: "invalid_request" });
      expect(
        context.requests.filter((it) => it.type === "save"),
        name,
      ).toEqual([]);
      expect(
        context.sent.filter((it) => it.message.kind === "guard"),
        name,
      ).toEqual([]);
    }
  });

  it("offers to unlock a locked vault, then shows the matches", async () => {
    const context = setup({ status: { kind: "locked", unlocking: false } });
    const { port } = await context.openMenu("username");
    expect(port.lastView()).toEqual({ kind: "locked", unlocking: false });
    port.emit({ kind: "unlock" });
    await settle();
    expect(context.state.unlocks).toBe(1);
    context.pages.statusChanged();
    await settle();
    expect(port.lastView()).toMatchObject({ kind: "items" });
  });

  it("points to the popup when the extension isn't paired or the app isn't running", async () => {
    const context = setup({ status: { kind: "not_paired", pairing: { phase: "idle" } } });
    const { port } = await context.openMenu("username");
    expect(port.lastView()).toEqual({ kind: "status", status: { kind: "not_paired", pairing: { phase: "idle" } } });
    // The menu's page opens the popup itself, in the user's click: a message
    // here would come too late for Firefox before 149.
    port.emit({ kind: "open_popup" });
    await settle();
    expect(context.state.popups).toBe(0);
  });

  it("shows a blocked site", async () => {
    const context = setup();
    context.state.app = (request) =>
      request.type === "suggest" ? { blocked: true, save_blocked: true, truncated: false, items: [] } : undefined;
    const { port } = await context.openMenu("username");
    expect(port.lastView()).toEqual({ kind: "blocked", host: "login.example.com" });
  });

  it("shows the icon of the frame's site, from its tab, and none when the browser can't tell", async () => {
    const context = setup();
    const { port } = await context.openMenu("username", contentSender({ tab: { id: 9, url: LOGIN_URL } }));
    expect(port.lastView()).toMatchObject({ kind: "items", icon: iconOf(LOGIN_URL, 9) });
    // As `tabs.get` for a tab that closed: the menu still lists the logins.
    context.state.siteIcon = () => {
      throw new Error("No tab with id: 9.");
    };
    context.pages.statusChanged();
    await settle();
    expect(port.lastView()).toMatchObject({ kind: "items", items: [{ handle: "h1" }, { handle: "h2" }], icon: null });
  });

  it("closes and sizes the menu in its own frame", async () => {
    const { openMenu, sent } = setup();
    const { port } = await openMenu("username");
    // The content script keeps the frame within its bounds.
    port.emit({ kind: "resize", height: 5000 });
    port.emit({ kind: "close" });
    await settle();
    const doc = docOf(contentSender());
    const origin = "https://login.example.com";
    expect(sent.map((it) => it.message)).toEqual([
      { kind: "menu_resize", doc, origin, height: 5000 },
      { kind: "menu_close", doc, origin },
    ]);
    expect(port.disconnected).toBe(true);
  });

  it("has the menu of the sender's document focus its first row, also when asked before its page bound", async () => {
    const { pages, openMenu } = setup();
    const focuses = (port: FakePort) => port.posted.filter((it) => it.kind === "focus").length;
    const { port } = await openMenu("username");
    expect(focuses(port)).toBe(0);
    await fromContent(pages, { kind: "menu_focus" });
    expect(focuses(port)).toBe(1);

    // The content script asks once the page loaded, which may be before it binds.
    const { token } = (await fromContent(pages, { kind: "menu_open", role: "username", webauthn: false })) as {
      token: string;
    };
    await fromContent(pages, { kind: "menu_focus" });
    const next = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(next);
    next.emit({ kind: "bind", token });
    await settle();
    expect(next.posted.slice(0, 2)).toEqual([{ kind: "bound" }, { kind: "focus" }]);
  });

  it("refuses a focus request from another document, frame or tab", async () => {
    const { pages, openMenu } = setup();
    const { port } = await openMenu("username");
    const firefox = contentSender({ frameId: 4, documentId: undefined });
    const { port: unbound } = await openMenu("username", firefox);
    for (const sender of [
      contentSender({ documentId: "doc-next" }),
      contentSender({ frameId: 5, documentId: "doc-5" }),
      contentSender({ tab: { id: 8 } }),
    ]) {
      await fromContent(pages, { kind: "menu_focus" }, sender);
    }
    // Without a `documentId`, as on Firefox: the token tells the documents apart.
    await pages.onContentMessage({ kind: "menu_focus", doc: "a".repeat(32) }, firefox);
    expect([...port.posted, ...unbound.posted].filter((it) => it.kind === "focus")).toEqual([]);
  });

  it("shows only the latest refresh when an older one answers late", async () => {
    const context = setup();
    let answer: () => void = () => undefined;
    context.state.app = (request) =>
      request.type === "suggest"
        ? new Promise(
            (resolve) =>
              (answer = () => resolve({ blocked: false, save_blocked: false, truncated: false, items: [item("old")] })),
          )
        : undefined;
    const { port } = await context.openMenu("username");
    // The vault locks while the app suggests.
    context.state.status = { kind: "locked", unlocking: false };
    context.pages.statusChanged();
    answer();
    await settle();
    expect(port.lastView()).toEqual({ kind: "locked", unlocking: false });

    // An older refresh that fails doesn't report over the newer items.
    const failing = setup();
    let fail: (error: Error) => void = () => undefined;
    let suggests = 0;
    failing.state.app = (request) =>
      request.type === "suggest" && ++suggests === 1 ? new Promise((_, reject) => (fail = reject)) : undefined;
    const menu = await failing.openMenu("username");
    failing.pages.statusChanged();
    await settle();
    expect(menu.port.lastView()).toMatchObject({ kind: "items", items: [{ handle: "h1" }, { handle: "h2" }] });
    fail(new ProtocolError("busy"));
    await settle();
    expect(menu.port.posted.filter((it) => it.kind === "error")).toEqual([]);
  });

  it("cancels its request when the menu closes, which is no failure", async () => {
    const context = setup();
    context.state.app = (request) => (request.type === "fill" ? new Promise(() => undefined) : undefined);
    const { port } = await context.openMenu("username");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(context.signalOf("fill")?.aborted).toBe(false);
    // The user dismissed the menu, or its frame navigated.
    port.close();
    expect(context.signalOf("fill")?.aborted).toBe(true);
    await settle();
    expect(port.posted.filter((it) => it.kind === "error")).toEqual([]);
    expect(context.fills()).toEqual([]);

    // Its unlock too, through the menu's own close.
    const locked = setup({ status: { kind: "locked", unlocking: false } });
    locked.state.unlocking = new Promise(() => undefined);
    const menu = await locked.openMenu("username");
    menu.port.emit({ kind: "unlock" });
    await settle();
    expect(locked.signalOf("unlock")?.aborted).toBe(false);
    menu.port.emit({ kind: "close" });
    await settle();
    expect(locked.signalOf("unlock")?.aborted).toBe(true);
  });
});

describe("save", () => {
  async function submit(
    context: ReturnType<typeof setup>,
    candidate: object = { username: "ann", password: "new-pass" },
    sender = contentSender(),
  ) {
    await fromContent(context.pages, { kind: "save_candidate", candidate }, sender);
  }

  async function openBar(
    context: ReturnType<typeof setup>,
    sender = contentSender({ frameId: 0, documentId: "doc-next" }),
  ) {
    const result = (await fromContent(context.pages, { kind: "save_bar_open" }, sender)) as { token: string } | null;
    if (result === null) return null;
    const port = new FakePort(SAVE_BAR_PORT, pageSender("/save-bar.html"));
    context.pages.connect(port);
    port.emit({ kind: "bind", token: result.token });
    await settle();
    return port;
  }

  it("asks the top frame to show the bar, which sends save with the sender's URL", async () => {
    const context = setup();
    await submit(context, { username: "ann", password: "new-pass", previous_password: "old" });
    expect(context.sent).toEqual([{ tabId: 7, message: { kind: "save_bar_show" }, target: { frameId: 0 } }]);

    // A subframe can't show it; the top frame of the next page can.
    expect(await fromContent(context.pages, { kind: "save_bar_open" }, contentSender())).toBeNull();
    const bar = (await openBar(context))!;
    expect(bar.posted).toEqual([
      { kind: "bound" },
      { kind: "view", offer: 1, host: "login.example.com", username: "ann", icon: iconOf(LOGIN_URL) },
    ]);
    expect(JSON.stringify(bar.posted)).not.toContain("new-pass");

    bar.emit({ kind: "save", offer: 1 });
    await settle();
    expect(context.requests.at(-1)).toEqual({
      type: "save",
      url: LOGIN_URL,
      username: "ann",
      password: "new-pass",
      previous_password: "old",
    });
    expect(context.sent.at(-1)).toEqual({
      tabId: 7,
      message: {
        kind: "save_bar_close",
        doc: docOf(contentSender({ documentId: "doc-next" })),
        origin: "https://login.example.com",
      },
      target: { frameId: 0, documentId: "doc-next" },
    });
    expect(bar.disconnected).toBe(true);
    expect(await openBar(context)).toBeNull();
  });

  it("checks the blocked URIs of the submitted frame with a suggest of no kinds: the app matches no logins", async () => {
    const context = setup();
    await submit(
      context,
      undefined,
      contentSender({ frameId: 5, documentId: "doc-5", url: "https://login.example.com/frame?step=2" }),
    );
    expect(context.requests).toEqual([{ type: "suggest", url: "https://login.example.com/frame?step=2", kinds: [] }]);
  });

  it("shows the icon of the submitted page's site, taken from its tab at the submit", async () => {
    const context = setup();
    await submit(context);
    // The bar shows on the page that follows, whatever its tab shows now.
    context.state.siteIcon = () => "https://icons.example/next";
    const bar = (await openBar(context))!;
    expect(bar.posted.at(-1)).toMatchObject({ kind: "view", icon: iconOf(LOGIN_URL) });

    // A tab the browser can't tell about: the bar still shows.
    context.state.siteIcon = () => {
      throw new Error("No tab with id: 7.");
    };
    await submit(context);
    expect(bar.posted.at(-1)).toEqual({
      kind: "view",
      offer: 2,
      host: "login.example.com",
      username: "ann",
      icon: null,
    });
  });

  it("takes the username of the username-first step", async () => {
    const context = setup();
    await fromContent(context.pages, { kind: "username_step", username: "ann@example.com" }, contentSender());
    await submit(context, { password: "pw" });
    const bar = (await openBar(context))!;
    expect(bar.posted.at(-1)).toEqual({
      kind: "view",
      offer: 1,
      host: "login.example.com",
      username: "ann@example.com",
      icon: iconOf(LOGIN_URL),
    });
  });

  it("asks the app to block saving on the site with Never, and never offers there again", async () => {
    const context = setup();
    // The app's rule of saving only: `suggest` reports it from then on.
    let rule = false;
    context.state.app = (request) => {
      if (request.type === "never_save") rule = true;
      return request.type === "suggest" && rule
        ? { blocked: false, save_blocked: true, truncated: false, items: [] }
        : undefined;
    };
    // Submitted in a subframe: its URL is the one the app blocks.
    const frame = contentSender({ frameId: 5, documentId: "doc-5", url: "https://login.example.com/frame?step=2" });
    await submit(context, undefined, frame);
    const bar = (await openBar(context))!;
    bar.emit({ kind: "never", offer: bar.offer() });
    await settle();
    expect(context.requests.at(-1)).toEqual({ type: "never_save", url: "https://login.example.com/frame?step=2" });
    expect(context.sent.at(-1)).toMatchObject({
      message: { kind: "save_bar_close" },
      target: { frameId: 0, documentId: "doc-next" },
    });
    expect(bar.disconnected).toBe(true);
    // The offer is gone.
    expect(await openBar(context)).toBeNull();

    context.sent.length = 0;
    await submit(context, undefined, frame);
    expect(context.sent).toEqual([]);
    expect(await openBar(context)).toBeNull();
  });

  it("shows a failure of Never, and keeps the bar", async () => {
    const context = setup();
    await submit(context);
    const bar = (await openBar(context))!;
    let refuse = true;
    context.state.app = (request) =>
      request.type === "never_save" && refuse ? new ProtocolError("not_allowed") : undefined;
    bar.emit({ kind: "never", offer: bar.offer() });
    await settle();
    expect(bar.posted.slice(-3)).toEqual([
      { kind: "busy", busy: true },
      { kind: "error", error: "not_allowed" },
      { kind: "busy", busy: false },
    ]);
    expect(bar.disconnected).toBe(false);
    // The user tries again.
    refuse = false;
    bar.emit({ kind: "never", offer: bar.offer() });
    await settle();
    expect(context.requests.filter((it) => it.type === "never_save")).toHaveLength(2);
    expect(bar.disconnected).toBe(true);
  });

  it("sizes the bar in its own frame, and keeps it on a resize after the offer expired", async () => {
    const context = setup();
    await submit(context);
    const bar = (await openBar(context))!;
    context.sent.length = 0;
    // The content script keeps the frame within its bounds.
    bar.emit({ kind: "resize", height: 5000 });
    await settle();
    // The clock alone: the offer's timer, which closes the bar, didn't fire (see below).
    context.advance(SAVE_TTL_MS + 1);
    bar.emit({ kind: "resize", height: 180 });
    await settle();
    const doc = docOf(contentSender({ documentId: "doc-next" }));
    const origin = "https://login.example.com";
    expect(context.sent.map((it) => it.message)).toEqual([
      { kind: "save_bar_resize", doc, origin, height: 5000 },
      { kind: "save_bar_resize", doc, origin, height: 180 },
    ]);
    expect(bar.disconnected).toBe(false);
    // Malformed: dropped.
    bar.emit({ kind: "resize", height: "tall" });
    bar.emit({ kind: "resize", height: Number.NaN });
    await settle();
    expect(context.sent).toHaveLength(2);
  });

  it("dismisses with Not now", async () => {
    const context = setup();
    await submit(context);
    const bar = (await openBar(context))!;
    bar.emit({ kind: "dismiss", offer: bar.offer() });
    await settle();
    // Only the offer's blocked-URI check, which matches no logins: no save.
    expect(context.requests).toEqual([{ type: "suggest", url: LOGIN_URL, kinds: [] }]);
    expect(await openBar(context)).toBeNull();
  });

  it("offers nothing with save prompts off, or an app without save", async () => {
    const off = setup();
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ savePrompts: false });
    await submit(off);
    expect(off.sent).toEqual([]);

    fakeBrowser.reset();
    const older = setup({ capabilities: [] });
    await submit(older);
    expect(older.sent).toEqual([]);
  });

  it("offers nothing where a blocked URI blocks saving, and offers when the app can't tell", async () => {
    // A rule of autofill and saving, then one of saving only, which still fills.
    for (const blocks of [
      { blocked: true, save_blocked: true },
      { blocked: false, save_blocked: true },
    ]) {
      fakeBrowser.reset();
      const blocked = setup();
      blocked.state.app = (request) =>
        request.type === "suggest" ? { ...blocks, truncated: false, items: [] } : undefined;
      await submit(blocked);
      expect(blocked.requests, JSON.stringify(blocks)).toEqual([{ type: "suggest", url: LOGIN_URL, kinds: [] }]);
      expect(blocked.sent, JSON.stringify(blocks)).toEqual([]);
      expect(await openBar(blocked), JSON.stringify(blocks)).toBeNull();
    }

    // The vault is locked: the app checks the save itself.
    fakeBrowser.reset();
    const locked = setup();
    locked.state.app = (request) => (request.type === "suggest" ? new ProtocolError("vault_locked") : undefined);
    await submit(locked);
    expect(locked.sent).toEqual([{ tabId: 7, message: { kind: "save_bar_show" }, target: { frameId: 0 } }]);
  });

  it("forgets an unanswered offer after a while", async () => {
    const context = setup();
    await submit(context);
    context.advance(SAVE_TTL_MS + 1);
    expect(await openBar(context)).toBeNull();
  });

  it("drops an offer nobody takes from memory once it expires, though nothing asks for it", async () => {
    const context = setup();
    await submit(context);
    vi.advanceTimersByTime(SAVE_TTL_MS);
    // The clock the offer checks stood still: only the timer dropped it.
    expect(await openBar(context)).toBeNull();
  });

  it("closes the bar once its offer expires: a later Save would only close it", async () => {
    const context = setup();
    await submit(context);
    const bar = (await openBar(context))!;
    vi.advanceTimersByTime(SAVE_TTL_MS);
    await settle();
    expect(context.sent.at(-1)).toMatchObject({
      message: { kind: "save_bar_close" },
      target: { frameId: 0, documentId: "doc-next" },
    });
    expect(bar.disconnected).toBe(true);
  });

  it("keeps the bar of a newer offer when the older one expires", async () => {
    const context = setup();
    await submit(context, { username: "ann", password: "first" });
    const bar = (await openBar(context))!;
    vi.advanceTimersByTime(SAVE_TTL_MS / 2);
    await submit(context, { username: "bob", password: "second" });
    vi.advanceTimersByTime(SAVE_TTL_MS / 2);
    await settle();
    expect(bar.disconnected).toBe(false);
    bar.emit({ kind: "save", offer: bar.offer() });
    await settle();
    expect(context.requests.at(-1)).toEqual({ type: "save", url: LOGIN_URL, username: "bob", password: "second" });
    expect(bar.disconnected).toBe(true);
  });

  it("lets a save in flight finish when its offer expires", async () => {
    const context = setup();
    await submit(context);
    const bar = (await openBar(context))!;
    let answer!: () => void;
    context.state.app = (request) =>
      request.type === "save" ? new Promise((resolve) => (answer = () => resolve({}))) : undefined;
    bar.emit({ kind: "save", offer: bar.offer() });
    await settle();
    vi.advanceTimersByTime(SAVE_TTL_MS);
    await settle();
    expect(bar.disconnected).toBe(false);
    expect(context.signalOf("save")?.aborted).toBe(false);
    answer();
    await settle();
    expect(bar.disconnected).toBe(true);
  });

  it("closes the bar when Save or Never fails after its offer expired: the bar has nothing left to act on", async () => {
    for (const [action, type] of [
      ["save", "save"],
      ["never", "never_save"],
    ] as const) {
      const context = setup();
      await submit(context);
      const bar = (await openBar(context))!;
      let refuse!: () => void;
      context.state.app = (request) =>
        request.type === type
          ? new Promise((_, reject) => (refuse = () => reject(new ProtocolError("not_allowed"))))
          : undefined;
      bar.emit({ kind: action, offer: bar.offer() });
      await settle();
      vi.advanceTimersByTime(SAVE_TTL_MS);
      await settle();
      expect(bar.disconnected, action).toBe(false);
      context.sent.length = 0;
      refuse();
      await settle();
      expect(bar.posted, action).toContainEqual({ kind: "error", error: "not_allowed" });
      expect(
        context.sent.map((it) => it.message.kind),
        action,
      ).toEqual(["save_bar_close"]);
      expect(bar.disconnected, action).toBe(true);
    }
  });

  it("keeps the bar for a newer offer when Save fails, or succeeds, for the one it replaced", async () => {
    for (const succeeds of [false, true]) {
      const context = setup();
      await submit(context, { username: "ann", password: "first" });
      const bar = (await openBar(context))!;
      let answer!: () => void;
      context.state.app = (request) =>
        request.type === "save" && request.password === "first"
          ? new Promise(
              (resolve, reject) => (answer = () => (succeeds ? resolve({}) : reject(new ProtocolError("not_allowed")))),
            )
          : undefined;
      bar.emit({ kind: "save", offer: bar.offer() });
      await settle();
      vi.advanceTimersByTime(SAVE_TTL_MS / 2);
      await submit(context, { username: "bob", password: "second" });
      // The first offer would have expired by now.
      vi.advanceTimersByTime(SAVE_TTL_MS / 2);
      answer();
      await settle();
      expect(bar.disconnected, String(succeeds)).toBe(false);
      expect(bar.posted.at(-1), String(succeeds)).toEqual({ kind: "busy", busy: false });
      expect(
        bar.posted.findLast((it) => it.kind === "view"),
        String(succeeds),
      ).toMatchObject({ username: "bob" });
      bar.emit({ kind: "save", offer: bar.offer() });
      await settle();
      expect(context.requests.at(-1), String(succeeds)).toEqual({
        type: "save",
        url: LOGIN_URL,
        username: "bob",
        password: "second",
      });
      expect(bar.disconnected, String(succeeds)).toBe(true);
    }
  });

  it("shows a newer offer on the bar that shows, which keeps its Save going: a new bar would replace it", async () => {
    const context = setup();
    await submit(context, { username: "ann", password: "first" });
    const bar = (await openBar(context))!;
    let answer!: () => void;
    context.state.app = (request) =>
      request.type === "save" ? new Promise((resolve) => (answer = () => resolve({}))) : undefined;
    bar.emit({ kind: "save", offer: bar.offer() });
    await settle();
    context.sent.length = 0;
    await submit(context, { username: "bob", password: "second" });
    await settle();
    expect(context.sent.filter((it) => it.message.kind === "save_bar_show")).toEqual([]);
    expect(bar.posted.at(-1)).toMatchObject({ kind: "view", offer: 2, username: "bob" });
    expect(context.signalOf("save")?.aborted).toBe(false);
    expect(bar.disconnected).toBe(false);
    answer();
    await settle();
    expect(context.requests.find((it) => it.type === "save")).toMatchObject({ username: "ann", password: "first" });

    // Once the bar's page is gone, as on a navigation, a newer offer asks the top frame again.
    bar.close();
    await submit(context, { username: "cid", password: "third" });
    expect(context.sent.filter((it) => it.message.kind === "save_bar_show")).toHaveLength(1);
  });

  it("unlocks first when the vault locked meanwhile, for Save and Never", async () => {
    for (const [action, type] of [
      ["save", "save"],
      ["never", "never_save"],
    ] as const) {
      const context = setup();
      await submit(context);
      const bar = (await openBar(context))!;
      let locked = true;
      context.state.app = (request) => {
        if (request.type !== type || !locked) return undefined;
        locked = false;
        return new ProtocolError("vault_locked");
      };
      bar.emit({ kind: action, offer: bar.offer() });
      await settle();
      expect(context.state.unlocks, action).toBe(1);
      expect(
        context.requests.filter((it) => it.type === type),
        action,
      ).toHaveLength(2);
      expect(bar.disconnected, action).toBe(true);
      expect(await openBar(context), action).toBeNull();
    }
  });

  it("cancels the save when the bar closes first, and offers it again on the next page", async () => {
    const context = setup();
    await submit(context);
    const bar = (await openBar(context))!;
    context.state.app = (request) => (request.type === "save" ? new Promise(() => undefined) : undefined);
    bar.emit({ kind: "save", offer: bar.offer() });
    await settle();
    // The page navigated, which removed the bar's frame.
    bar.close();
    expect(context.signalOf("save")?.aborted).toBe(true);
    await settle();
    expect(await openBar(context)).not.toBeNull();
  });
});

describe("save bar actions", () => {
  async function offered(
    context: ReturnType<typeof setup>,
    candidate: object = { username: "ann", password: "new-pass" },
  ) {
    await fromContent(context.pages, { kind: "save_candidate", candidate }, contentSender());
  }

  async function openBar(context: ReturnType<typeof setup>) {
    const sender = contentSender({ frameId: 0, documentId: "doc-next" });
    const { token } = (await fromContent(context.pages, { kind: "save_bar_open" }, sender)) as { token: string };
    const port = new FakePort(SAVE_BAR_PORT, pageSender("/save-bar.html"));
    context.pages.connect(port);
    port.emit({ kind: "bind", token });
    await settle();
    return port;
  }

  it("checks that the page shows the bar before Save and Never, in the bar's own document", async () => {
    for (const [action, type] of [
      ["save", "save"],
      ["never", "never_save"],
    ] as const) {
      const context = setup();
      await offered(context);
      const bar = await openBar(context);
      context.sent.length = 0;
      bar.emit({ kind: action, offer: bar.offer() });
      await settle();
      expect(context.sent[0], action).toEqual({
        tabId: 7,
        message: {
          kind: "guard",
          popup: "save_bar",
          doc: docOf(contentSender({ documentId: "doc-next" })),
          origin: "https://login.example.com",
        },
        target: { frameId: 0, documentId: "doc-next" },
      });
      expect(context.requests.at(-1), action).toMatchObject({ type });
    }
  });

  it("asks the app for nothing when the page covers the bar, and keeps the offer", async () => {
    for (const action of ["save", "never"] as const) {
      const context = setup();
      await offered(context);
      const bar = await openBar(context);
      context.state.contentScript = (message) =>
        message.kind === "guard" ? { ok: false, reason: "obscured" } : undefined;
      bar.emit({ kind: action, offer: bar.offer() });
      await settle();
      expect(
        context.requests.map((it) => it.type),
        action,
      ).toEqual(["suggest"]);
      expect(bar.posted, action).toContainEqual({ kind: "error", error: "obscured" });
      expect(bar.disconnected, action).toBe(false);
    }
  });

  it("acts on the offer the bar showed only: a newer one takes none of its actions", async () => {
    const context = setup();
    await offered(context, { username: "ann", password: "first" });
    const bar = await openBar(context);
    const first = bar.offer();
    // The page submits another login while the user reads the bar: the bar shows it.
    await offered(context, { username: "bob", password: "second" });
    const second = bar.offer();
    expect(second).not.toBe(first);
    expect(bar.posted.at(-1)).toMatchObject({ kind: "view", offer: second, username: "bob" });

    for (const kind of ["save", "never", "dismiss"]) {
      bar.emit({ kind, offer: first });
      await settle();
    }
    expect(context.requests.map((it) => it.type)).toEqual(["suggest", "suggest"]);
    expect(bar.disconnected).toBe(false);
    // Each refused action shows the offer again.
    expect(bar.posted.at(-1)).toMatchObject({ kind: "view", offer: second, username: "bob" });

    bar.emit({ kind: "save", offer: second });
    await settle();
    expect(context.requests.at(-1)).toEqual({ type: "save", url: LOGIN_URL, username: "bob", password: "second" });
    expect(bar.disconnected).toBe(true);
  });

  it("ignores a dismiss, as on Escape, while Save or Never runs: the action goes on", async () => {
    for (const [action, type] of [
      ["save", "save"],
      ["never", "never_save"],
    ] as const) {
      const context = setup();
      await offered(context);
      const bar = await openBar(context);
      let answer!: () => void;
      context.state.app = (request) =>
        request.type === type ? new Promise((resolve) => (answer = () => resolve({}))) : undefined;
      // At once, and while the app answers.
      bar.emit({ kind: action, offer: bar.offer() });
      bar.emit({ kind: "dismiss", offer: bar.offer() });
      await settle();
      bar.emit({ kind: "dismiss", offer: bar.offer() });
      await settle();
      expect(context.signalOf(type)?.aborted, action).toBe(false);
      expect(bar.disconnected, action).toBe(false);
      answer();
      await settle();
      expect(
        context.requests.filter((it) => it.type === type),
        action,
      ).toHaveLength(1);
      expect(bar.disconnected, action).toBe(true);
    }
  });

  it("takes no action without the offer, or with a malformed one", async () => {
    const context = setup();
    await offered(context);
    const bar = await openBar(context);
    for (const offer of [undefined, null, 0, -1, 1.5, "1"]) bar.emit({ kind: "save", offer });
    await settle();
    expect(context.requests.map((it) => it.type)).toEqual(["suggest"]);
    expect(bar.disconnected).toBe(false);
  });

  it("takes the username of a username-first step of the same origin only", async () => {
    for (const [step, submitted, taken] of [
      ["https://login.example.com/", "https://login.example.com/password", true],
      ["https://login.example.com/", "https://auth.example.com/password", false],
      ["https://a.co.uk/", "https://b.co.uk/password", false],
      ["http://login.example.com/", "https://login.example.com/password", false],
    ] as const) {
      const context = setup();
      const sender = (url: string) =>
        contentSender({ tab: { id: 7, url }, frameId: 0, documentId: "doc-0", url, origin: new URL(url).origin });
      await fromContent(context.pages, { kind: "username_step", username: "ann@example.com" }, sender(step));
      await fromContent(context.pages, { kind: "save_candidate", candidate: { password: "pw" } }, sender(submitted));
      const bar = await openBar(context);
      expect(bar.posted.at(-1), `${step} ${submitted}`).toMatchObject({
        kind: "view",
        username: taken ? "ann@example.com" : null,
      });
    }
  });
});

describe("private windows", () => {
  /** A content script of a tab in a private window. */
  const privateSender = (fields: Partial<MessageSenderLike> = {}) =>
    contentSender({ tab: { id: 7, url: LOGIN_URL, incognito: true }, ...fields });

  /** The requests that name a page, by type, with whether they say it's private. */
  const privacy = (requests: JsonObject[]) => requests.map((it) => [it.type, it.private_window === true]);

  it("says so in every request for a page of a content script there", async () => {
    const context = setup({ capabilities: ["passkeys", "save"] });
    context.state.conditional = { rpId: "example.com" };
    const sender = privateSender({ frameId: 0, documentId: "doc-0" });
    const menu = await context.openMenu("new-password", sender, true);
    for (const request of [
      { kind: "fill", handle: "h1" },
      { kind: "totp", handle: "h1" },
      { kind: "generate" },
      { kind: "create" },
    ]) {
      menu.port.emit(request);
      await settle();
    }
    await fromContent(context.pages, { kind: "save_candidate", candidate: { password: "pw" } }, sender);
    const { token } = (await fromContent(context.pages, { kind: "save_bar_open" }, sender)) as { token: string };
    const bar = new FakePort(SAVE_BAR_PORT, pageSender("/save-bar.html"));
    context.pages.connect(bar);
    bar.emit({ kind: "bind", token });
    await settle();
    bar.emit({ kind: "never", offer: bar.offer() });
    await settle();
    await fromContent(context.pages, { kind: "save_candidate", candidate: { password: "pw2" } }, sender);
    const second = (await fromContent(context.pages, { kind: "save_bar_open" }, sender)) as { token: string };
    const next = new FakePort(SAVE_BAR_PORT, pageSender("/save-bar.html"));
    context.pages.connect(next);
    next.emit({ kind: "bind", token: second.token });
    await settle();
    next.emit({ kind: "save", offer: next.offer() });
    await settle();
    // The suggests of the menu, for logins and passkeys, and of the offers' checks of blocked URIs.
    expect(new Set(context.requests.map((it) => it.type))).toEqual(
      new Set(["suggest", "fill", "totp", "generate", "never_save", "save"]),
    );
    expect(
      context.requests.filter((it) => it.type === "suggest" && (it.kinds as string[] | undefined)?.includes("passkey")),
    ).toHaveLength(1);
    expect(privacy(context.requests).filter(([, isPrivate]) => !isPrivate)).toEqual([]);

    // The same requests of a tab in another window say nothing.
    const regular = setup();
    const login = await regular.openMenu("current-password");
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(regular.requests.length).toBeGreaterThan(0);
    for (const request of regular.requests) expect(request).not.toHaveProperty("private_window");
  });

  it("says so for the popup's calls and the shortcut, by the tab's window", async () => {
    const context = setup();
    context.state.incognito = true;
    context.state.app = (request) =>
      request.type === "search"
        ? { blocked: false, truncated: false, items: [{ ...item("h1"), matched: true }] }
        : undefined;
    await context.pages.calls.suggest({ tabId: 7 });
    await context.pages.calls.search({ tabId: 7, query: "item" });
    // The popup's fill: the frame with the form is in that tab too.
    context.state.forms = [
      { sender: privateSender({ frameId: 0, documentId: "doc-0" }), focusedAt: null, writable: true },
    ];
    await context.pages.calls.fill({ tabId: 7, handle: "h1", origin: LOGIN_ORIGIN });
    // The shortcut, where no content script has focus: the tab's best login form.
    await finish(context.pages.fillCommand(7));
    expect(privacy(context.requests)).toEqual([
      ["suggest", true],
      ["search", true],
      ["fill", true],
      ["suggest", true],
      ["fill", true],
    ]);
  });

  it("keeps the pages of private windows in memory only", async () => {
    const context = setup();
    const sender = privateSender({ frameId: 0, documentId: "doc-0" });
    await fromContent(context.pages, { kind: "username_step", username: "ann" }, sender);
    await fromContent(context.pages, { kind: "save_candidate", candidate: { password: "pw" } }, sender);
    const login = await context.openMenu("current-password", sender);
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(await fakeBrowser.storage.local.get(null)).toEqual({});
  });
});

describe("shortcut and popup", () => {
  it("fills the best match into the frame with focus, after a fresh suggest", async () => {
    const context = setup();
    context.state.suggest = [item("h2", { has_password: false }), item("h3")];
    const command = context.pages.fillCommand(7);
    await settle();
    const probe = context.sent[0]!;
    expect(probe).toMatchObject({ tabId: 7, message: { kind: "focus_probe" } });
    expect(probe.target).toBeUndefined();
    // The frame with focus answers.
    const focused = contentSender({
      frameId: 5,
      documentId: "doc-5",
      url: "https://login.example.com/frame",
      origin: "https://login.example.com",
    });
    await fromContent(
      context.pages,
      { kind: "focus_report", probe: (probe.message as { probe: string }).probe, role: "username" },
      focused,
    );
    await command;
    expect(context.requests).toEqual([
      { type: "suggest", url: "https://login.example.com/frame" },
      { type: "fill", handle: "h3", url: "https://login.example.com/frame" },
    ]);
    expect(context.sent.at(-1)).toEqual({
      tabId: 7,
      message: {
        kind: "fill",
        doc: docOf(focused),
        origin: "https://login.example.com",
        target: "focused",
        username: "ann",
        password: "secret-h3",
      },
      target: { frameId: 5, documentId: "doc-5" },
    });
  });

  it("ignores focus reports of other tabs and unknown probes", async () => {
    const context = setup();
    const command = context.pages.fillCommand(7);
    await settle();
    const probe = (context.sent[0]!.message as { probe: string }).probe;
    await fromContent(context.pages, { kind: "focus_report", probe, role: null }, contentSender({ tab: { id: 8 } }));
    await fromContent(context.pages, { kind: "focus_report", probe: "f".repeat(32), role: null }, contentSender());
    await finish(command);
    // No frame answered: the top frame, by the tab's URL.
    expect(context.fills().at(-1)).toMatchObject({ message: { target: "page" }, target: { frameId: 0 } });
  });

  it("unlocks first, and opens the popup when Keyguard isn't ready", async () => {
    const locked = setup({ status: { kind: "locked", unlocking: false } });
    await finish(locked.pages.fillCommand(7));
    expect(locked.state.unlocks).toBe(1);

    expect(locked.state.popups).toBe(0);

    const unpaired = setup({ status: { kind: "app_unavailable" } });
    const command = unpaired.pages.fillCommand(7);
    // At once, in the handler of the user's key: Firefox before 149 opens it only then.
    expect(unpaired.state.popups).toBe(1);
    await command;
    expect(unpaired.state.popups).toBe(1);
    expect(unpaired.requests).toEqual([]);
  });

  it("cancels the shortcut's requests when the tab leaves the page's origin or closes", async () => {
    const locked = setup({ status: { kind: "locked", unlocking: false } });
    locked.state.unlocking = new Promise(() => undefined);
    const unlocking = locked.pages.fillCommand(7);
    await settle();
    locked.tabs.navigated(7, "https://login.example.com/next");
    locked.tabs.navigated(8, "https://elsewhere.example/");
    expect(locked.signalOf("unlock")?.aborted).toBe(false);
    locked.tabs.navigated(7, "https://elsewhere.example/");
    await expect(unlocking).rejects.toMatchObject({ code: "cancelled" });

    const context = setup();
    context.state.app = (request) => (request.type === "fill" ? new Promise(() => undefined) : undefined);
    const filling = context.pages.fillCommand(7);
    // No frame reports focus: the top frame's best match.
    await timeUp();
    expect(context.signalOf("fill")?.aborted).toBe(false);
    context.tabs.removed(7);
    await expect(filling).rejects.toMatchObject({ code: "cancelled" });
    expect(context.fills()).toEqual([]);
  });

  it("cancels the shortcut's suggest when the tab closes", async () => {
    // For the frame that reports focus, and for the top frame when none does.
    for (const focused of [true, false]) {
      const context = setup();
      context.state.app = (request) => (request.type === "suggest" ? new Promise(() => undefined) : undefined);
      const command = context.pages.fillCommand(7);
      await settle();
      if (focused) {
        const probe = (context.sent[0]!.message as { probe: string }).probe;
        await fromContent(context.pages, { kind: "focus_report", probe, role: "current-password" }, contentSender());
        await settle();
      } else {
        await timeUp();
      }
      expect(context.signalOf("suggest")?.aborted, `focused: ${focused}`).toBe(false);
      context.tabs.removed(7);
      expect(context.signalOf("suggest")?.aborted, `focused: ${focused}`).toBe(true);
      await expect(command, `focused: ${focused}`).rejects.toMatchObject({ code: "cancelled" });
    }
  });

  it("fills the popup's pick into the top frame, injecting the content script once if missing", async () => {
    const context = setup();
    context.state.forms = null;
    await context.fillFromPopup("h1");
    expect(context.state.injected).toEqual([7]);
    expect(context.sent.map((it) => [it.message.kind, it.target])).toEqual([
      ["form_probe", undefined],
      ["form_probe", undefined],
      ["fill", { frameId: 0, documentId: "doc-0" }],
    ]);
    expect(context.sent[2]!.message).toEqual({
      kind: "fill",
      doc: docOf(topForm().sender),
      origin: "https://login.example.com",
      target: "page",
      username: "ann",
      password: "secret-h1",
    });

    // The form went away since the probe.
    context.state.contentScript = () => ({ ok: false, reason: "no_form" });
    await expect(context.fillFromPopup("h1")).rejects.toMatchObject({ code: "no_form" });
    expect(context.state.injected).toEqual([7]);
  });
});

describe("the popup's fill", () => {
  /** A sign-in page embedded by the page, on the page's site. */
  const SIGN_IN = {
    frameId: 4,
    documentId: "doc-4",
    url: "https://idmsa.example.com/signin?widget=1",
    origin: "https://idmsa.example.com",
  };

  it("fills a login in a frame of the page's site, with that frame's URL and ID", async () => {
    const context = setup();
    context.state.forms = [
      form(
        {
          frameId: 5,
          documentId: "doc-5",
          url: "https://accounts.example.com/other",
          origin: "https://accounts.example.com",
        },
        1_000,
      ),
      // Focused last.
      form(SIGN_IN, 2_000),
    ];
    await context.fillFromPopup("h1");
    expect(context.requests).toEqual([
      { type: "fill", handle: "h1", url: SIGN_IN.url, top_origin: "https://login.example.com" },
    ]);
    expect(context.fills()).toEqual([
      {
        tabId: 7,
        message: {
          kind: "fill",
          doc: docOf(contentSender(SIGN_IN)),
          origin: "https://idmsa.example.com",
          target: "page",
          username: "ann",
          password: "secret-h1",
        },
        target: { frameId: 4, documentId: "doc-4" },
      },
    ]);

    // Firefox has no documentId.
    const firefox = setup({ documentIds: false });
    firefox.state.forms = [form(SIGN_IN)];
    await firefox.fillFromPopup("h1");
    expect(firefox.fills().map((it) => it.target)).toEqual([{ frameId: 4 }]);
  });

  it("never fills a frame of another site", async () => {
    const context = setup();
    context.state.forms = [
      form(
        { frameId: 5, documentId: "doc-5", url: "https://evil.example/login", origin: "https://evil.example" },
        9_000,
      ),
      // Another scheme is another site.
      form(
        {
          frameId: 6,
          documentId: "doc-6",
          url: "http://login.example.com/sign-in",
          origin: "http://login.example.com",
        },
        9_000,
      ),
    ];
    await expect(context.fillFromPopup("h1")).rejects.toMatchObject({ code: "no_form" });
    expect(context.requests).toEqual([]);
    expect(context.fills()).toEqual([]);

    // A frame of the site gets it, though another site's was focused later.
    context.state.forms.push(form(SIGN_IN, 1_000));
    await context.fillFromPopup("h1");
    expect(context.requests).toEqual([
      { type: "fill", handle: "h1", url: SIGN_IN.url, top_origin: "https://login.example.com" },
    ]);
    expect(context.fills().map((it) => it.target)).toEqual([{ frameId: 4, documentId: "doc-4" }]);
  });

  it("refuses before anything is released when the tab went on to another origin than the one the popup listed for", async () => {
    const context = setup();
    for (const origin of ["https://evil.example", "http://login.example.com", undefined]) {
      await expect(
        context.pages.calls.fill({ tabId: 7, handle: "h1", origin } as never),
        String(origin),
      ).rejects.toMatchObject({ code: "origin" });
    }
    expect(context.sent).toEqual([]);
    expect(context.requests).toEqual([]);
    // Another page of the same origin is the same.
    await expect(context.pages.calls.fill({ tabId: 7, handle: "h1", origin: LOGIN_ORIGIN })).resolves.toBeNull();
    expect(context.requests).toEqual([{ type: "fill", handle: "h1", url: LOGIN_URL }]);
  });

  it("asks the app to release nothing when no frame has a form", async () => {
    const context = setup();
    context.state.forms = [];
    await expect(context.fillFromPopup("h1")).rejects.toMatchObject({ code: "no_form" });
    expect(context.requests).toEqual([]);
    expect(context.fills()).toEqual([]);
    expect(context.state.injected).toEqual([]);
  });

  it("fills the top frame when it has a form, over a frame focused later", async () => {
    const context = setup();
    context.state.forms = [form(SIGN_IN, 5_000), topForm()];
    await context.fillFromPopup("h1");
    expect(context.requests).toEqual([{ type: "fill", handle: "h1", url: LOGIN_URL }]);
    expect(context.fills().map((it) => it.target)).toEqual([{ frameId: 0, documentId: "doc-0" }]);
  });

  it("fills a frame with a real login over a top frame that only shows the account read-only", async () => {
    const context = setup();
    // The top frame answers first.
    context.state.forms = [topForm(false), form(SIGN_IN, null)];
    await context.fillFromPopup("h1");
    expect(context.requests).toEqual([
      { type: "fill", handle: "h1", url: SIGN_IN.url, top_origin: "https://login.example.com" },
    ]);
    expect(context.fills().map((it) => it.target)).toEqual([{ frameId: 4, documentId: "doc-4" }]);
  });

  it("still fills a form of read-only fields when no frame has a writable one: they may turn writable on focus", async () => {
    const context = setup();
    context.state.forms = [form(SIGN_IN, 5_000, false), topForm(false)];
    await context.fillFromPopup("h1");
    expect(context.requests).toEqual([{ type: "fill", handle: "h1", url: LOGIN_URL }]);
    expect(context.fills().map((it) => it.target)).toEqual([{ frameId: 0, documentId: "doc-0" }]);
  });
});

describe("the popup's matches", () => {
  it("are kept for a later fill of the tab, but not once the tab closed while the app answered", async () => {
    const SEARCHED = { blocked: false, truncated: false, items: [{ ...item("s1"), matched: true }] };
    for (const call of ["suggest", "search"] as const) {
      const context = setup();
      // What the popup listed, for "Fill TOTP" after its fill.
      const listed = (context.pages as unknown as { listed: Map<number, unknown> }).listed;
      const ask = () =>
        call === "suggest"
          ? context.pages.calls.suggest({ tabId: 7 })
          : context.pages.calls.search({ tabId: 7, query: "item" });
      let answer: () => void = () => undefined;
      context.state.app = (request) =>
        request.type === call
          ? new Promise((resolve) => (answer = () => resolve(call === "search" ? SEARCHED : undefined)))
          : undefined;

      let pending = ask();
      await settle();
      answer();
      await expect(pending, call).resolves.toMatchObject({ kind: "items" });
      expect(listed.has(7), call).toBe(true);

      context.pages.tabRemoved(7);
      pending = ask();
      await settle();
      // The tab closes, as the background hears it.
      context.tabs.removed(7);
      context.pages.tabRemoved(7);
      answer();
      await expect(pending, call).resolves.toMatchObject({ kind: "items" });
      expect(listed.has(7), call).toBe(false);
    }
  });

  it("offer a new login only when the app can save and saving isn't blocked on the page", async () => {
    const context = setup();
    await expect(context.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject({ kind: "items", create: true });
    context.state.app = (request) =>
      request.type === "suggest" ? { blocked: false, save_blocked: true, truncated: false, items: [] } : undefined;
    await expect(context.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject({ kind: "items", create: false });
    const older = setup({ capabilities: [] });
    await expect(older.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject({ kind: "items", create: false });
  });

  it("have the app open a new login of the tab's URL alone, for the origin the popup listed for", async () => {
    const context = setup();
    for (const origin of ["https://evil.example", "http://login.example.com", undefined]) {
      await expect(context.pages.calls.create({ tabId: 7, origin } as never), String(origin)).rejects.toMatchObject({
        code: "origin",
      });
    }
    expect(context.requests).toEqual([]);
    await expect(context.pages.calls.create({ tabId: 7, origin: LOGIN_ORIGIN })).resolves.toBeNull();
    expect(context.requests).toEqual([{ type: "save", url: LOGIN_URL }]);
    // A tab in a private window says so.
    context.state.incognito = true;
    await expect(context.pages.calls.create({ tabId: 7, origin: LOGIN_ORIGIN })).resolves.toBeNull();
    expect(context.requests.slice(1)).toEqual([{ type: "save", url: LOGIN_URL, private_window: true }]);
  });

  it("come with the icon of the tab's site, or none when the browser can't tell", async () => {
    const context = setup();
    await expect(context.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject({
      kind: "items",
      icon: iconOf(LOGIN_URL),
    });
    context.state.siteIcon = () => {
      throw new Error("No tab with id: 7.");
    };
    await expect(context.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject({
      kind: "items",
      items: [{ handle: "h1" }, { handle: "h2" }],
      icon: null,
    });
  });
});

describe("a reload of the extension", () => {
  it("would cut a menu about to bind or open, a save offered or shown, and a fill or list of the popup", async () => {
    const context = setup();
    const { pages } = context;
    expect(pages.busy).toBe(false);

    await fromContent(pages, { kind: "menu_open", role: "username", webauthn: false });
    expect(pages.busy, "a menu's token").toBe(true);
    context.advance(TOKEN_TTL_MS + 1);
    expect(pages.busy, "an expired token").toBe(false);
    const { port: menu } = await context.openMenu("username");
    expect(pages.busy, "an open menu").toBe(true);
    menu.close();
    expect(pages.busy).toBe(false);

    await fromContent(pages, { kind: "save_candidate", candidate: { username: "ann", password: "new-pass" } });
    expect(pages.busy, "a save offer").toBe(true);
    const result = (await fromContent(
      pages,
      { kind: "save_bar_open" },
      contentSender({ frameId: 0, documentId: "doc-0" }),
    )) as { token: string };
    const bar = new FakePort(SAVE_BAR_PORT, pageSender("/save-bar.html"));
    pages.connect(bar);
    bar.emit({ kind: "bind", token: result.token });
    await settle();
    bar.close();
    expect(pages.busy, "a save offer after its bar closed").toBe(true);
    await vi.advanceTimersByTimeAsync(SAVE_TTL_MS);
    expect(pages.busy, "an expired save offer").toBe(false);

    let answer: () => void = () => undefined;
    context.state.app = (request) =>
      request.type === "suggest" ? new Promise((resolve) => (answer = () => resolve(undefined))) : undefined;
    const suggest = pages.calls.suggest({ tabId: 7 });
    await settle();
    expect(pages.busy, "the popup's list").toBe(true);
    answer();
    await suggest;
    expect(pages.busy).toBe(false);
  });
});

/** The password step after the username step on LOGIN_URL: another page of its origin, in the top frame of the same tab. */
const NEXT_URL = "https://login.example.com/password";
const NEXT = contentSender({
  tab: { id: 7, url: NEXT_URL },
  frameId: 0,
  documentId: "doc-next",
  url: NEXT_URL,
  origin: "https://login.example.com",
});

/** A tab, [context], where the menu of the username step on LOGIN_URL, in the frame of [sender], filled [handle]. */
async function afterUsernameStep(handle = "h2", context = setup(), sender = contentSender()) {
  const { port } = await context.openMenu("username", sender);
  port.emit({ kind: "fill", handle });
  await settle();
  return context;
}

/** Presses the shortcut with focus in a field of [role] in the frame of [sender]; the handle it filled. */
async function shortcut(context: ReturnType<typeof setup>, role: string, sender: MessageSenderLike) {
  const command = context.pages.fillCommand(7);
  await settle();
  const probe = context.sent.findLast((it) => it.message.kind === "focus_probe")!.message as { probe: string };
  await fromContent(context.pages, { kind: "focus_report", probe: probe.probe, role }, sender);
  await command;
  return context.requests.findLast((it) => it.type === "fill")?.handle;
}

describe("the next step of a sign-in", () => {
  /** The app's order of the default matches. */
  const APP_ORDER = { kind: "items", items: [{ handle: "h1" }, { handle: "h2" }], last: null };

  it("lists the login filled on the username step first, marked, in the menu of the next page of the site", async () => {
    const context = await afterUsernameStep("h2");
    const password = await context.openMenu("current-password", NEXT);
    expect(password.port.lastView()).toMatchObject({
      kind: "items",
      role: "current-password",
      items: [{ handle: "h2" }, { handle: "h1" }],
      last: "h2",
    });
    const username = await context.openMenu("username", NEXT);
    expect(username.port.lastView()).toMatchObject({ items: [{ handle: "h2" }, { handle: "h1" }], last: "h2" });
    // Nothing fills before the user picks.
    expect(context.requests.at(-1)).toEqual({ type: "suggest", url: NEXT_URL });
    password.port.emit({ kind: "fill", handle: "h2" });
    await settle();
    expect(context.requests.at(-1)).toEqual({ type: "fill", handle: "h2", url: NEXT_URL });
  });

  it("fills the login filled last with the shortcut on the next page of the site", async () => {
    const context = await afterUsernameStep("h2");
    expect(await shortcut(context, "current-password", NEXT)).toBe("h2");
    expect(context.requests.slice(-2)).toEqual([
      { type: "suggest", url: NEXT_URL },
      { type: "fill", handle: "h2", url: NEXT_URL },
    ]);
    expect(context.fills().at(-1)).toMatchObject({
      message: { kind: "fill", target: "focused", password: "secret-h2" },
      target: { frameId: 0 },
    });
  });

  it("keeps the app's order on another origin, after 5 minutes, in another session and on other fields", async () => {
    // Another site, and another origin of the site.
    for (const url of ["https://other.example/login", "https://auth.example.com/password"]) {
      const elsewhere = contentSender({
        tab: { id: 7, url },
        frameId: 0,
        documentId: "doc-other",
        url,
        origin: new URL(url).origin,
      });
      const otherSite = await afterUsernameStep();
      expect((await otherSite.openMenu("current-password", elsewhere)).port.lastView(), url).toMatchObject(APP_ORDER);
      expect(await shortcut(otherSite, "current-password", elsewhere), url).toBe("h1");
    }

    const later = await afterUsernameStep();
    later.advance(CONTINUE_WINDOW_MS - 1);
    expect((await later.openMenu("current-password", NEXT)).port.lastView()).toMatchObject({ last: "h2" });
    later.advance(2);
    expect((await later.openMenu("current-password", NEXT)).port.lastView()).toMatchObject(APP_ORDER);
    expect(await shortcut(later, "current-password", NEXT)).toBe("h1");

    const newSession = await afterUsernameStep();
    newSession.state.sessionId = 2;
    expect((await newSession.openMenu("current-password", NEXT)).port.lastView()).toMatchObject(APP_ORDER);
    expect(await shortcut(newSession, "current-password", NEXT)).toBe("h1");

    // A sign-up form.
    const signUp = await afterUsernameStep();
    expect((await signUp.openMenu("new-password", NEXT)).port.lastView()).toMatchObject(APP_ORDER);
    expect(await shortcut(signUp, "new-password", NEXT)).toBe("h1");
  });

  it("never adds a login that the next page's suggest didn't return", async () => {
    const context = await afterUsernameStep("h2");
    context.state.suggest = [item("h1"), item("h3")];
    const { port } = await context.openMenu("current-password", NEXT);
    expect(port.lastView()).toMatchObject({ items: [{ handle: "h1" }, { handle: "h3" }], last: null });
    port.emit({ kind: "fill", handle: "h2" });
    await settle();
    expect(port.posted).toContainEqual({ kind: "error", error: "invalid_request" });
    expect(context.pages.lastFirst(7, NEXT_URL, context.state.suggest)).toEqual({
      items: context.state.suggest,
      last: null,
    });
    expect(await shortcut(context, "current-password", NEXT)).toBe("h1");
  });

  it("keeps Fill TOTP to 2 minutes and to a login with one, while the next step continues for 5", async () => {
    const context = setup();
    context.state.suggest = [item("h2"), item("h1", { has_totp: true })];
    const login = await context.openMenu("username");
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect((await context.openMenu("otp", NEXT)).port.lastView()).toMatchObject({ totp: { handle: "h1" } });
    context.advance(TOTP_WINDOW_MS + 1);
    expect((await context.openMenu("otp", NEXT)).port.lastView()).toMatchObject({ totp: null });
    expect((await context.openMenu("current-password", NEXT)).port.lastView()).toMatchObject({
      items: [{ handle: "h1" }, { handle: "h2" }],
      last: "h1",
    });

    // A login without TOTP filled next: no code of the one before.
    const next = setup();
    const step = await next.openMenu("username");
    step.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    step.port.emit({ kind: "fill", handle: "h2" });
    await settle();
    expect((await next.openMenu("otp", NEXT)).port.lastView()).toMatchObject({ totp: null });
    expect((await next.openMenu("current-password", NEXT)).port.lastView()).toMatchObject({
      items: [{ handle: "h2" }, { handle: "h1" }],
      last: "h2",
    });
  });

  it("orders the popup's matches the same way, after a fill from the popup or the shortcut", async () => {
    const context = setup();
    const [h1, h2] = context.state.suggest;
    await context.fillFromPopup("h2");
    expect(context.pages.lastFirst(7, NEXT_URL, [h1!, h2!])).toEqual({ items: [h2, h1], last: "h2" });
    // Another tab has its own.
    expect(context.pages.lastFirst(8, NEXT_URL, [h1!, h2!])).toEqual({ items: [h1, h2], last: null });
    // The shortcut's fill, on a sign-up form here, replaces it.
    expect(await shortcut(context, "new-password", NEXT)).toBe("h1");
    expect(context.pages.lastFirst(7, NEXT_URL, [h2!, h1!])).toEqual({ items: [h1, h2], last: "h1" });
  });

  it("marks the app's last used login when the tab filled none within 5 minutes", async () => {
    const context = setup();
    const APP_LAST_USED = { items: [{ handle: "h2" }, { handle: "h1" }], last: "h2" };
    context.state.suggest = [item("h2", { last_used: true }), item("h1")];
    expect((await context.openMenu("current-password", NEXT)).port.lastView()).toMatchObject(APP_LAST_USED);
    await expect(context.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject(APP_LAST_USED);
    // A sign-up form.
    expect((await context.openMenu("new-password", NEXT)).port.lastView()).toMatchObject({ last: null });

    const later = await afterUsernameStep("h1");
    later.state.suggest = [item("h2", { last_used: true }), item("h1")];
    later.advance(CONTINUE_WINDOW_MS + 1);
    expect((await later.openMenu("current-password", NEXT)).port.lastView()).toMatchObject(APP_LAST_USED);
  });

  it("lists the login filled on the tab over the app's last used: two tabs may sign in to two accounts", async () => {
    const context = await afterUsernameStep("h1");
    context.state.suggest = [item("h2", { last_used: true }), item("h1")];
    const [h2, h1] = context.state.suggest;
    expect((await context.openMenu("current-password", NEXT)).port.lastView()).toMatchObject({
      items: [{ handle: "h1" }, { handle: "h2" }],
      last: "h1",
    });
    expect(context.pages.lastFirst(7, NEXT_URL, [h2!, h1!])).toEqual({ items: [h1, h2], last: "h1" });
    // Another tab has filled none.
    expect(context.pages.lastFirst(8, NEXT_URL, [h2!, h1!])).toEqual({ items: [h2, h1], last: "h2" });
    expect(await shortcut(context, "current-password", NEXT)).toBe("h1");
  });

  it("fills the app's last used login, its first item, with the shortcut on a sign-in step", async () => {
    const context = setup();
    context.state.suggest = [item("h2", { last_used: true, has_password: false }), item("h1")];
    expect(await shortcut(context, "current-password", NEXT)).toBe("h2");
    // A sign-up form takes the first login with a password.
    expect(await shortcut(context, "new-password", NEXT)).toBe("h1");
  });
});

describe("matched and suggested logins", () => {
  /** Suggested by their names, around matched ones; m2 comes from an older app, without `matched`. */
  const MIXED = () => [
    item("s1", { matched: false }),
    item("m1", { matched: true }),
    item("s2", { matched: false }),
    item("m2"),
  ];

  it("lists the matched logins first, then the suggested ones, each in the app's order", async () => {
    const context = setup();
    context.state.suggest = MIXED();
    const { port } = await context.openMenu("username");
    expect(port.lastView()).toMatchObject({
      kind: "items",
      items: [
        { handle: "m1", matched: true },
        { handle: "m2", matched: true },
        { handle: "s1", matched: false },
        { handle: "s2", matched: false },
      ],
      last: null,
    });
    const popup = await context.pages.calls.suggest({ tabId: 7 });
    expect(popup).toMatchObject({ kind: "items", last: null });
    expect(popup.kind === "items" && popup.items.map((it) => it.handle)).toEqual(["m1", "m2", "s1", "s2"]);
    // So does a one-time-code field, with the logins that have a TOTP.
    context.state.suggest = MIXED().map((it) => ({ ...it, has_totp: it.handle !== "m1" }));
    expect((await context.openMenu("otp")).port.lastView()).toMatchObject({
      items: [{ handle: "m2" }, { handle: "s1" }, { handle: "s2" }],
    });
  });

  it("lists the login used last first of its own section", async () => {
    const suggested = setup();
    suggested.state.suggest = MIXED();
    await afterUsernameStep("s2", suggested);
    const order = { items: [{ handle: "m1" }, { handle: "m2" }, { handle: "s2" }, { handle: "s1" }], last: "s2" };
    expect((await suggested.openMenu("current-password", NEXT)).port.lastView()).toMatchObject(order);
    await expect(suggested.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject(order);

    const matched = setup();
    matched.state.suggest = [...MIXED().slice(0, 3), item("m2", { last_used: true })];
    const lifted = { items: [{ handle: "m2" }, { handle: "m1" }, { handle: "s1" }, { handle: "s2" }], last: "m2" };
    expect((await matched.openMenu("current-password", NEXT)).port.lastView()).toMatchObject(lifted);
    await expect(matched.pages.calls.suggest({ tabId: 7 })).resolves.toMatchObject(lifted);
  });

  it("fills a suggested login from the menu, which the app prompts for", async () => {
    const context = setup();
    context.state.suggest = MIXED();
    const { port } = await context.openMenu("username");
    port.emit({ kind: "fill", handle: "s1" });
    await settle();
    expect(context.requests.at(-1)).toEqual({ type: "fill", handle: "s1", url: LOGIN_URL });
  });

  /** The login the shortcut fills on a sign-in step, in a tab that filled none, from [suggest]. */
  async function best(suggest: SuggestItem[]) {
    const context = setup();
    context.state.suggest = suggest;
    return shortcut(context, "current-password", NEXT);
  }

  it("fills a matched login with the shortcut: the one used last, then one with a password, then the first", async () => {
    // The suggested login used last, and the first, without a password.
    expect(
      await best([item("s1", { matched: false, last_used: true }), item("m1", { has_password: false }), item("m2")]),
    ).toBe("m2");
    expect(
      await best([item("m1", { last_used: true, has_password: false }), item("s1", { matched: false }), item("m2")]),
    ).toBe("m1");
    expect(await best([item("s1", { matched: false }), item("m1", { has_password: false })])).toBe("m1");
    // The suggested login filled on the username step doesn't win over a matched one either.
    const context = setup();
    context.state.suggest = MIXED();
    await afterUsernameStep("s1", context);
    expect(await shortcut(context, "current-password", NEXT)).toBe("m1");
  });

  it("fills a suggested login with the shortcut only when none matches, as before", async () => {
    const suggested = (handle: string, fields: Partial<SuggestItem> = {}) =>
      item(handle, { matched: false, ...fields });
    expect(await best([suggested("s1", { has_password: false }), suggested("s2")])).toBe("s2");
    expect(await best([suggested("s1", { has_password: false, last_used: true }), suggested("s2")])).toBe("s1");
    expect(await best([suggested("s1", { has_password: false }), suggested("s2", { has_password: false })])).toBe("s1");
  });
});

describe("the password step", () => {
  /** The username step: a field of the tab's top frame. */
  const USERNAME_STEP = contentSender({ frameId: 0, documentId: "doc-0" });
  /** The password step: the next page of the top frame. */
  const PASSWORD_STEP = NEXT;
  /** The token of a `password_step` report. */
  const STEP = "5".repeat(32);

  /** The answer of a fill that wrote the username alone, as on a username-first step. */
  const USERNAME_ONLY = { ok: true, passwordStep: true };

  /**
   * The menu of [step] fills [handle], and its content script answers with
   * [answer]. A password step's fill writes the password.
   */
  async function usernameStep(
    context: ReturnType<typeof setup>,
    handle = "h1",
    answer: unknown = USERNAME_ONLY,
    step = USERNAME_STEP,
  ) {
    context.state.contentScript = (message) => {
      if (message.kind === "guard") return { ok: true };
      if (message.kind !== "fill") return undefined;
      return message.target === "step" ? { ok: true } : answer;
    };
    return afterUsernameStep(handle, context, step);
  }

  /** The content script of [sender] reports a password step, showing [account] if any; the fills that followed. */
  async function passwordStep(
    context: ReturnType<typeof setup>,
    sender = PASSWORD_STEP,
    account?: string,
    step = STEP,
  ) {
    const before = context.fills().length;
    const request = { kind: "password_step", step, url: sender.url, ...(account !== undefined ? { account } : {}) };
    expect(await fromContent(context.pages, request, sender)).toBeNull();
    await settle();
    return context.fills().slice(before);
  }

  /** The app's releases of logins. */
  const releases = (context: ReturnType<typeof setup>) => context.requests.filter((it) => it.type === "fill");

  /**
   * Reports the password step while the app holds the release of its
   * password; resolves once the app asked, with a release of it that
   * resolves once the report is answered.
   */
  async function heldStep(context: ReturnType<typeof setup>): Promise<() => Promise<void>> {
    let approve!: () => void;
    const approved = new Promise<void>((resolve) => (approve = resolve));
    // The app prompts for the step's release only.
    context.state.app = (request) =>
      request.type === "fill" && request.handle === "h1" && request.url === NEXT_URL
        ? approved.then(() => undefined)
        : undefined;
    const reported = fromContent(context.pages, { kind: "password_step", step: STEP, url: NEXT_URL }, PASSWORD_STEP);
    await settle();
    expect(releases(context)).toHaveLength(2);
    return async () => {
      approve();
      await reported;
      await settle();
    };
  }

  it("fills the password of a login whose username alone was filled, into the field its report names", async () => {
    const context = await usernameStep(setup());
    expect(await passwordStep(context)).toEqual([
      {
        tabId: 7,
        // The password only: the page has the username.
        message: {
          kind: "fill",
          target: "step",
          step: STEP,
          password: "secret-h1",
          doc: docOf(PASSWORD_STEP),
          origin: LOGIN_ORIGIN,
        },
        target: { frameId: 0, documentId: "doc-next" },
      },
    ]);
    expect(releases(context)).toEqual([
      { type: "fill", handle: "h1", url: LOGIN_URL },
      { type: "fill", handle: "h1", url: NEXT_URL },
    ]);
    // Fill TOTP follows, for the login filled.
    expect((await context.openMenu("otp", PASSWORD_STEP)).port.lastView()).toMatchObject({ totp: { handle: "h1" } });
  });

  it("fills once: a page that asks again, as after a wrong password, gets nothing", async () => {
    const context = await usernameStep(setup());
    expect(await passwordStep(context)).toHaveLength(1);
    expect(await passwordStep(context)).toEqual([]);
    expect(await passwordStep(context, { ...PASSWORD_STEP, documentId: "doc-again" })).toEqual([]);
    expect(releases(context)).toHaveLength(2);

    // Two forms of a page that ask at once: the first gets it, by its token.
    const both = await usernameStep(setup());
    await Promise.all([
      passwordStep(both),
      passwordStep(both, { ...PASSWORD_STEP, documentId: "doc-again" }, undefined, "6".repeat(32)),
    ]);
    expect(releases(both)).toHaveLength(2);
    expect(both.fills().map((it) => it.message)).toEqual([
      expect.objectContaining({ target: "menu" }),
      expect.objectContaining({ target: "step", step: STEP, doc: docOf(PASSWORD_STEP) }),
    ]);
  });

  it("tries once: a failed release doesn't arm it again, and shows nothing", async () => {
    const context = await usernameStep(setup());
    context.state.app = (request) => (request.type === "fill" ? new ProtocolError("user_denied") : undefined);
    expect(await passwordStep(context)).toEqual([]);
    context.state.app = () => undefined;
    expect(await passwordStep(context, { ...PASSWORD_STEP, documentId: "doc-again" })).toEqual([]);
    expect(releases(context)).toHaveLength(2);
  });

  it("fills nothing after a fill that wrote the password too, that wasn't a sign-in's username, or that the document didn't tell", async () => {
    // The document tells nothing after a fill that wrote the password too, or a sign-up form's username; anything but `true` is as nothing.
    for (const flag of [undefined, false, null, "yes", 1, { username: true }]) {
      const context = await usernameStep(setup(), "h1", { ok: true, passwordStep: flag });
      expect(await passwordStep(context), JSON.stringify(flag)).toEqual([]);
      expect(releases(context)).toHaveLength(1);
    }
    const noPassword = setup();
    noPassword.state.app = (request) => (request.type === "fill" ? { username: "ann" } : undefined);
    await usernameStep(noPassword);
    expect(await passwordStep(noPassword)).toEqual([]);
    expect(releases(noPassword)).toHaveLength(1);
  });

  it("fills only a step that shows no account, or the one filled", async () => {
    const context = await usernameStep(setup());
    // The user typed another account on the username step.
    expect(await passwordStep(context, PASSWORD_STEP, "bob")).toEqual([]);
    expect(releases(context)).toHaveLength(1);
    // That doesn't use it up.
    expect(await passwordStep(context, PASSWORD_STEP, "  ANN ")).toHaveLength(1);

    const none = await usernameStep(setup());
    expect(await passwordStep(none)).toHaveLength(1);
  });

  it("fills nothing into another origin, frame or tab, which leave it to the top frame of the username", async () => {
    const context = await usernameStep(setup());
    const authUrl = "https://auth.example.com/password";
    for (const sender of [
      contentSender({
        tab: { id: 7, url: authUrl },
        frameId: 0,
        documentId: "doc-auth",
        url: authUrl,
        origin: "https://auth.example.com",
      }),
      contentSender({ frameId: 5, documentId: "doc-5", url: NEXT_URL }),
      contentSender({ tab: { id: 8, url: NEXT_URL }, frameId: 0, documentId: "doc-8", url: NEXT_URL }),
    ]) {
      expect(await passwordStep(context, sender), `${sender.tab?.id} ${sender.frameId} ${sender.url}`).toEqual([]);
    }
    expect(releases(context)).toHaveLength(1);
    expect(await passwordStep(context)).toHaveLength(1);
  });

  it("fills nothing in a frame, even one of the top frame's site, and a fill there arms nothing", async () => {
    for (const origin of ["https://idp.example.net", "https://auth.example.com", LOGIN_ORIGIN]) {
      const frame = { tab: { id: 7, url: LOGIN_URL }, frameId: 4, origin };
      const context = await usernameStep(
        setup(),
        "h1",
        USERNAME_ONLY,
        contentSender({ ...frame, documentId: "doc-4", url: `${origin}/signin` }),
      );
      expect(
        await passwordStep(context, contentSender({ ...frame, documentId: "doc-4b", url: `${origin}/password` })),
        origin,
      ).toEqual([]);
      expect(
        await passwordStep(
          context,
          contentSender({ frameId: 0, documentId: "doc-0b", url: `${origin}/password`, origin }),
        ),
        origin,
      ).toEqual([]);
      expect(releases(context), origin).toHaveLength(1);
    }
  });

  it("fills nothing past 2 minutes, or in another app session", async () => {
    const within = await usernameStep(setup());
    within.advance(PASSWORD_STEP_WINDOW_MS);
    expect(await passwordStep(within)).toHaveLength(1);

    const late = await usernameStep(setup());
    late.advance(PASSWORD_STEP_WINDOW_MS + 1);
    expect(await passwordStep(late)).toEqual([]);
    expect(releases(late)).toHaveLength(1);
    // The menu still lists the login first.
    expect((await late.openMenu("current-password", PASSWORD_STEP)).port.lastView()).toMatchObject({ last: "h1" });

    const newSession = await usernameStep(setup());
    newSession.state.sessionId = 2;
    expect(await passwordStep(newSession)).toEqual([]);
    expect(releases(newSession)).toHaveLength(1);
  });

  it("fills nothing with the option off, the vault locked or the tab in the background", async () => {
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ fillPasswordStep: false });
    const off = await usernameStep(setup());
    expect(await passwordStep(off)).toEqual([]);
    expect(releases(off)).toHaveLength(1);
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ fillPasswordStep: true });

    const locked = await usernameStep(setup());
    locked.state.status = { kind: "locked", unlocking: false };
    expect(await passwordStep(locked)).toEqual([]);
    expect(locked.state.unlocks).toBe(0);
    expect(releases(locked)).toHaveLength(1);

    const background = await usernameStep(setup());
    background.state.active = false;
    expect(await passwordStep(background)).toEqual([]);
    expect(releases(background)).toHaveLength(1);
  });

  it("delivers nothing when, while the app asked, the user left the tab, locked the vault or turned the option off, or another fill came", async () => {
    for (const meanwhile of [
      (context: ReturnType<typeof setup>) => void (context.state.active = false),
      async (context: ReturnType<typeof setup>) => {
        const { port } = await context.openMenu(
          "username",
          contentSender({ frameId: 0, documentId: "doc-next", url: NEXT_URL }),
        );
        port.emit({ kind: "fill", handle: "h2" });
      },
      // The app session stays.
      (context: ReturnType<typeof setup>) => void (context.state.status = { kind: "locked", unlocking: false }),
      (context: ReturnType<typeof setup>) => context.options.setOptions({ fillPasswordStep: false }),
      () => void vi.spyOn(OptionsStore.prototype, "options").mockRejectedValue(new Error("storage")),
    ]) {
      await new OptionsStore(fakeBrowser.storage.local).setOptions({ fillPasswordStep: true });
      const context = await usernameStep(setup());
      const before = context.fills().length;
      const release = await heldStep(context);
      await meanwhile(context);
      await settle();
      await release();
      expect(
        context
          .fills()
          .slice(before)
          .filter((it) => (it.message as { target: string }).target === "step"),
      ).toEqual([]);
      vi.restoreAllMocks();
    }
  });

  it("delivers nothing when another fill replaced the username's during the last look at the tab", async () => {
    const context = await usernameStep(setup());
    const before = context.fills().length;
    const release = await heldStep(context);
    // The look at the tab once the app released the password.
    context.state.tabLookup = async () => {
      const { port } = await context.openMenu(
        "username",
        contentSender({ frameId: 0, documentId: "doc-next", url: NEXT_URL }),
      );
      port.emit({ kind: "fill", handle: "h2" });
      await settle();
    };
    await release();
    expect(
      context
        .fills()
        .slice(before)
        .map((it) => (it.message as { target: string }).target),
    ).toEqual(["menu"]);
  });

  it("asks to sign in after the password, with the option on", async () => {
    await new OptionsStore(fakeBrowser.storage.local).setOptions({ submitAfterFill: true });
    const context = await usernameStep(setup());
    expect(context.fills().map((it) => it.message)).toEqual([
      expect.objectContaining({ target: "menu", submit: true }),
    ]);
    expect(await passwordStep(context)).toEqual([
      expect.objectContaining({
        message: {
          kind: "fill",
          target: "step",
          step: STEP,
          password: "secret-h1",
          submit: true,
          doc: docOf(PASSWORD_STEP),
          origin: LOGIN_ORIGIN,
        },
      }),
    ]);
  });
});

describe("sign in after filling", () => {
  const submitAfterFill = (on: boolean) =>
    new OptionsStore(fakeBrowser.storage.local).setOptions({ submitAfterFill: on });

  /** A login fill from the menu, the popup and the shortcut, in turn; the commands that each sent. */
  async function loginFills(context: ReturnType<typeof setup>) {
    const { port } = await context.openMenu("current-password");
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    await context.fillFromPopup("h1");
    await shortcut(context, "current-password", contentSender());
    return context.fills().map((it) => it.message);
  }

  it("asks the document to sign in after each login fill, with the option on, in the tab the user is on", async () => {
    await submitAfterFill(true);
    expect(await loginFills(setup())).toEqual([
      expect.objectContaining({ kind: "fill", target: "menu", password: "secret-h1", submit: true }),
      expect.objectContaining({ kind: "fill", target: "page", password: "secret-h1", submit: true }),
      expect.objectContaining({ kind: "fill", target: "focused", password: "secret-h1", submit: true }),
    ]);
  });

  it("doesn't when the options can't be read, and the fill still works", async () => {
    await submitAfterFill(true);
    const context = setup();
    const read = vi.spyOn(OptionsStore.prototype, "options");
    try {
      const { port } = await context.openMenu("current-password");
      read.mockRejectedValue(new Error("storage"));
      port.emit({ kind: "fill", handle: "h1" });
      await settle();
      await context.fillFromPopup("h1");
    } finally {
      read.mockRestore();
    }
    const fills = context.fills().map((it) => it.message);
    expect(fills).toEqual([
      expect.objectContaining({ kind: "fill", target: "menu", password: "secret-h1" }),
      expect.objectContaining({ kind: "fill", target: "page", password: "secret-h1" }),
    ]);
    for (const message of fills) expect(message).not.toHaveProperty("submit");
  });

  it("doesn't with the option off, or in a tab in the background", async () => {
    const off = await loginFills(setup());
    expect(off).toHaveLength(3);
    for (const message of off) expect(message, (message as { target: string }).target).not.toHaveProperty("submit");

    await submitAfterFill(true);
    const context = setup();
    context.state.active = false;
    const background = await loginFills(context);
    expect(background).toHaveLength(3);
    for (const message of background)
      expect(message, (message as { target: string }).target).not.toHaveProperty("submit");
  });

  it("never asks after a one-time code or a generated password", async () => {
    await submitAfterFill(true);
    const context = setup();
    const login = await context.openMenu("current-password");
    login.port.emit({ kind: "fill", handle: "h1" });
    await settle();
    const otp = await context.openMenu("otp");
    otp.port.emit({ kind: "totp", handle: "h1" });
    await settle();
    // The shortcut on a code field, after the login fill.
    await shortcut(context, "otp", contentSender());
    const generator = await context.openMenu("new-password");
    generator.port.emit({ kind: "generate" });
    await settle();

    const fills = context.fills().map((it) => it.message);
    expect(fills.map((it) => it.kind)).toEqual(["fill", "fill_totp", "fill_totp", "fill_password"]);
    expect(fills[0]).toHaveProperty("submit", true);
    for (const message of fills.slice(1)) expect(message, message.kind).not.toHaveProperty("submit");
  });
});

describe("the top-level site", () => {
  /** A sign-in frame of another site, in the tab's page. */
  const EMBEDDED = {
    frameId: 4,
    documentId: "doc-4",
    url: "https://idp.example.net/signin",
    origin: "https://idp.example.net",
    tab: { id: 7, url: LOGIN_URL },
  };

  const topProbes = (context: ReturnType<typeof setup>) => context.sent.filter((it) => it.message.kind === "top_probe");

  /** The shortcut, into the frame of [sender], which reports focus; past the time of the top frame's probe. */
  async function shortcut(context: ReturnType<typeof setup>, sender: MessageSenderLike) {
    const command = context.pages.fillCommand(7);
    await settle();
    const probe = (context.sent.findLast((it) => it.message.kind === "focus_probe")!.message as { probe: string })
      .probe;
    await fromContent(context.pages, { kind: "focus_report", probe, role: "username" }, sender);
    return finish(command);
  }

  it("names the tab's origin in fill and totp for a frame of another origin, from the sender", async () => {
    const context = setup();
    context.state.suggest = [item("h1", { has_totp: true })];
    const { port } = await context.openMenu("current-password", contentSender(EMBEDDED));
    port.emit({ kind: "fill", handle: "h1" });
    await settle();
    port.emit({ kind: "totp", handle: "h1" });
    await settle();
    expect(context.requests.filter((it) => it.type !== "suggest")).toEqual([
      { type: "fill", handle: "h1", url: EMBEDDED.url, top_origin: "https://login.example.com" },
      { type: "totp", handle: "h1", url: EMBEDDED.url, top_origin: "https://login.example.com" },
    ]);

    // The popup's fill, into a frame of the tab's site, and the shortcut, into that frame.
    context.requests.length = 0;
    const sameSite = { ...EMBEDDED, url: "https://idmsa.example.com/signin", origin: "https://idmsa.example.com" };
    context.state.forms = [form(sameSite)];
    await context.fillFromPopup("h1");
    const shortcut = context.pages.fillCommand(7);
    await settle();
    const probe = (context.sent.findLast((it) => it.message.kind === "focus_probe")!.message as { probe: string })
      .probe;
    await fromContent(context.pages, { kind: "focus_report", probe, role: "username" }, contentSender(EMBEDDED));
    await shortcut;
    expect(context.requests.filter((it) => it.type === "fill")).toEqual([
      { type: "fill", handle: "h1", url: sameSite.url, top_origin: "https://login.example.com" },
      { type: "fill", handle: "h1", url: EMBEDDED.url, top_origin: "https://login.example.com" },
    ]);
  });

  it("names none for the top frame or a frame of the same origin", async () => {
    for (const sender of [
      contentSender({ frameId: 0, documentId: "doc-0", tab: { id: 7, url: LOGIN_URL } }),
      // The tab's URL may lag behind its top frame's: the top frame is never embedded.
      contentSender({ frameId: 0, documentId: "doc-0", tab: { id: 7, url: "https://elsewhere.example/" } }),
      contentSender({ tab: { id: 7, url: "https://login.example.com/other" } }),
    ]) {
      const context = setup();
      const { port } = await context.openMenu("current-password", sender);
      port.emit({ kind: "fill", handle: "h1" });
      await settle();
      expect(context.requests.at(-1), JSON.stringify(sender.tab)).toEqual({
        type: "fill",
        handle: "h1",
        url: LOGIN_URL,
      });
    }
  });

  it("asks the top frame nothing when the tab's URL is http(s)", async () => {
    for (const sender of [contentSender(EMBEDDED), contentSender()]) {
      const context = setup();
      const { port } = await context.openMenu("current-password", sender);
      port.emit({ kind: "fill", handle: "h1" });
      await settle();
      port.emit({ kind: "totp", handle: "h1" });
      await settle();
      await shortcut(context, sender);
      context.state.forms = [
        form({ ...EMBEDDED, url: "https://idmsa.example.com/signin", origin: "https://idmsa.example.com" }),
      ];
      await context.fillFromPopup("h1");
      const top = sender.url === EMBEDDED.url ? { top_origin: "https://login.example.com" } : {};
      expect(context.requests.filter((it) => it.type !== "suggest")).toEqual([
        { type: "fill", handle: "h1", url: sender.url, ...top },
        { type: "totp", handle: "h1", url: sender.url, ...top },
        { type: "fill", handle: "h1", url: sender.url, ...top },
        {
          type: "fill",
          handle: "h1",
          url: "https://idmsa.example.com/signin",
          top_origin: "https://login.example.com",
        },
      ]);
      expect(topProbes(context)).toEqual([]);
    }
  });

  describe("when the tab's URL doesn't tell", () => {
    /** The top frame of a popup the attacker opened with `window.open()`: about:blank, with the opener's origin. */
    const POPUP = {
      frameId: 0,
      documentId: "doc-0",
      url: "about:blank",
      origin: "https://attacker.example",
      tab: { id: 7, url: "about:blank" },
    };
    /** A bank's sign-in frame, which the attacker wrote into the popup. */
    const BANK = {
      ...POPUP,
      frameId: 4,
      documentId: "doc-4",
      url: "https://bank.example/signin",
      origin: "https://bank.example",
    };
    /** A sign-in frame on the popup's site, for the popup's fill, in a tab whose URL the browser didn't report. */
    const SIGN_IN = {
      ...BANK,
      url: "https://idmsa.example.com/signin",
      origin: "https://idmsa.example.com",
      tab: { id: 7 },
    };

    /** The token of the first `top_probe`, once it's sent: its time doesn't pass meanwhile. */
    async function topProbe(context: ReturnType<typeof setup>): Promise<string> {
      await settle();
      return (topProbes(context)[0]!.message as { probe: string }).probe;
    }

    it("names the origin of an about:blank top frame, from the sender of its answer", async () => {
      const context = setup();
      context.state.top = contentSender(POPUP);
      const { port } = await context.openMenu("current-password", contentSender(BANK));
      port.emit({ kind: "fill", handle: "h1" });
      await settle();
      port.emit({ kind: "totp", handle: "h1" });
      await settle();
      await shortcut(context, contentSender(BANK));
      context.state.forms = [form(SIGN_IN)];
      await context.fillFromPopup("h1");
      const top = "https://attacker.example";
      expect(context.requests.filter((it) => it.type !== "suggest")).toEqual([
        { type: "fill", handle: "h1", url: BANK.url, top_origin: top },
        { type: "totp", handle: "h1", url: BANK.url, top_origin: top },
        { type: "fill", handle: "h1", url: BANK.url, top_origin: top },
        { type: "fill", handle: "h1", url: SIGN_IN.url, top_origin: top },
      ]);
      // Once per release, to the tab's top frame, whatever its document.
      expect(topProbes(context).map((it) => [it.tabId, it.target])).toEqual(Array(4).fill([7, { frameId: 0 }]));
    });

    it("refuses before the app releases anything when the top frame's origin is opaque or unreported", async () => {
      for (const top of [
        // A sandboxed popup, or one with CSP `sandbox`.
        { ...POPUP, origin: "null" },
        { ...POPUP, url: "https://attacker.example/", origin: "null" },
        { ...POPUP, url: "data:text/html,<iframe>", origin: "null" },
        // Firefox may not report the origin of an about: page.
        { ...POPUP, origin: undefined },
      ]) {
        const context = setup();
        context.state.top = contentSender(top);
        const { port } = await context.openMenu("current-password", contentSender(BANK));
        port.emit({ kind: "fill", handle: "h1" });
        await timeUp();
        expect(
          context.requests.map((it) => it.type),
          JSON.stringify(top),
        ).toEqual(["suggest"]);
        expect(context.fills()).toEqual([]);
        expect(port.posted).toContainEqual({ kind: "error", error: "unsupported_page" });
      }
    });

    it("refuses before the app releases anything when the top frame doesn't answer", async () => {
      const context = setup();
      const { port } = await context.openMenu("current-password", contentSender(BANK));
      port.emit({ kind: "totp", handle: "h1" });
      await timeUp();
      expect(port.posted).toContainEqual({ kind: "error", error: "unsupported_page" });
      await expect(shortcut(context, contentSender(BANK))).rejects.toMatchObject({ code: "unsupported_page" });
      context.state.forms = [form(SIGN_IN)];
      await expect(context.fillFromPopup("h1")).rejects.toMatchObject({ code: "unsupported_page" });
      expect(context.requests.map((it) => it.type)).toEqual(["suggest", "suggest"]);
      expect(context.fills()).toEqual([]);
      expect(topProbes(context)).toHaveLength(3);
    });

    it("ignores answers of another frame or tab, or to another probe", async () => {
      const context = setup();
      const { port } = await context.openMenu("current-password", contentSender(BANK));
      port.emit({ kind: "fill", handle: "h1" });
      const probe = await topProbe(context);
      const answer = (sender: MessageSenderLike, token = probe) =>
        fromContent(context.pages, { kind: "top_report", probe: token }, sender);
      await answer(contentSender(BANK));
      await answer(
        contentSender({ ...POPUP, origin: "https://other-tab.example", tab: { id: 8, url: "about:blank" } }),
      );
      await answer(contentSender({ ...POPUP, origin: "https://other-probe.example" }), "f".repeat(32));
      // Only the top frame of the tab, with the probe's token.
      await answer(contentSender(POPUP));
      await settle();
      expect(context.requests.at(-1)).toEqual({
        type: "fill",
        handle: "h1",
        url: BANK.url,
        top_origin: "https://attacker.example",
      });
    });

    it("asks the top frame nothing for its own requests", async () => {
      for (const tab of [{ id: 7, url: "about:blank" }, { id: 7 }]) {
        const context = setup();
        const sender = contentSender({ ...POPUP, tab });
        const { port } = await context.openMenu("current-password", sender);
        port.emit({ kind: "fill", handle: "h1" });
        await settle();
        await shortcut(context, sender);
        expect(
          context.requests.filter((it) => it.type === "fill"),
          JSON.stringify(tab),
        ).toEqual([
          { type: "fill", handle: "h1", url: "https://attacker.example/" },
          { type: "fill", handle: "h1", url: "https://attacker.example/" },
        ]);
        expect(topProbes(context)).toEqual([]);
      }
    });
  });
});

describe("passkeys in the inline menu", () => {
  const FRAME: FrameRef = {
    tabId: 7,
    frameId: 3,
    documentId: "doc-3",
    doc: docOf(contentSender()),
    url: LOGIN_URL,
    origin: "https://login.example.com",
  };

  /** A menu on a `webauthn` field while the page waits on a conditional request. */
  function passkeySetup(capabilities: Capability[] = ["passkeys", "save"]) {
    const context = setup({ capabilities });
    context.state.conditional = { rpId: "example.com" };
    context.state.app = (request) =>
      request.type === "suggest" && (request.kinds as string[] | undefined)?.includes("passkey")
        ? {
            blocked: false,
            save_blocked: false,
            truncated: false,
            items: [
              item("p1", {
                name: "Elsewhere",
                passkeys: [
                  { handle: "pk1", user_name: "ann", user_display_name: "Ann" },
                  { handle: "pk2", user_name: "bob" },
                ],
              }),
            ],
          }
        : undefined;
    return context;
  }

  it("lists the passkeys of the page's conditional request on webauthn fields, apart from the logins", async () => {
    const context = passkeySetup();
    const { port } = await context.openMenu("username", contentSender(), true);
    expect(context.requests).toEqual([
      { type: "suggest", url: LOGIN_URL, kinds: ["passkey"], rp_id: "example.com" },
      { type: "suggest", url: LOGIN_URL },
    ]);
    const view = port.lastView() as Extract<MenuView, { kind: "items" }>;
    expect(view.passkeys).toEqual([
      { handle: "pk1", user_name: "ann", user_display_name: "Ann", item: "Elsewhere", account: "Personal" },
      { handle: "pk2", user_name: "bob", item: "Elsewhere", account: "Personal" },
    ]);
    // The logins with passkeys for the RP ID may not match the page: they aren't offered for filling.
    expect(view.items.map((it) => it.handle)).toEqual(["h1", "h2"]);
  });

  it("asks for passkeys only on webauthn fields, with a conditional request and the capability", async () => {
    for (const [name, prepare, webauthn] of [
      ["no webauthn token", () => undefined, false],
      ["no conditional request", (context: ReturnType<typeof setup>) => void (context.state.conditional = null), true],
      [
        "no capability",
        (context: ReturnType<typeof setup>) => void context.state.capabilities.delete("passkeys"),
        true,
      ],
    ] as const) {
      const context = passkeySetup();
      prepare(context);
      const { port } = await context.openMenu("username", contentSender(), webauthn);
      expect(context.requests, name).toEqual([{ type: "suggest", url: LOGIN_URL }]);
      expect(port.lastView(), name).toMatchObject({ kind: "items", passkeys: [] });
    }
  });

  it("shows the logins when the app refuses the RP ID", async () => {
    const context = passkeySetup();
    context.state.app = (request) =>
      (request.kinds as string[] | undefined) ? new ProtocolError("security") : undefined;
    const { port } = await context.openMenu("username", contentSender(), true);
    expect(port.lastView()).toMatchObject({ kind: "items", passkeys: [], items: [{ handle: "h1" }, { handle: "h2" }] });
  });

  /** A passkey setup whose `suggest` of [kind] waits for the returned release; [logins], the app's answer for the logins. */
  function holding(kind: "login" | "passkey", logins?: unknown) {
    const context = passkeySetup();
    const answers = context.state.app;
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => (release = resolve));
    context.state.app = (request) => {
      const passkeys = request.kinds !== undefined;
      const answer = () =>
        request.type === "suggest" && !passkeys && logins !== undefined ? logins : answers(request);
      return request.type === "suggest" && passkeys === (kind === "passkey") ? held.then(answer) : answer();
    };
    return { context, release: () => release() };
  }

  const itemViews = (port: FakePort) => port.views().filter((it) => it.kind === "items");

  it("waits for the passkeys and shows them with the logins in one view, so no row moves", async () => {
    const { context, release } = holding("passkey");
    const { port } = await context.openMenu("username", contentSender(), true);
    // The logins are in, the passkeys aren't: no rows yet.
    expect(itemViews(port)).toEqual([]);
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(port.posted.at(-1)).toEqual({ kind: "error", error: "invalid_request" });

    release();
    await settle();
    expect(itemViews(port)).toHaveLength(1);
    expect(port.lastView()).toMatchObject({
      kind: "items",
      items: [{ handle: "h1" }, { handle: "h2" }],
      passkeys: [{ handle: "pk1" }, { handle: "pk2" }],
    });
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(context.state.picks).toEqual([{ frame: FRAME, handle: "pk1" }]);
  });

  it("shows the logins alone once the passkeys take longer than the wait, and cancels their request", async () => {
    const context = passkeySetup();
    const answers = context.state.app;
    // A related origin the app never finishes fetching.
    context.state.app = (request) => (request.kinds !== undefined ? new Promise(() => undefined) : answers(request));
    const { port } = await context.openMenu("username", contentSender(), true);
    expect(itemViews(port)).toEqual([]);

    await timeUp(PASSKEY_WAIT_MS - 1);
    expect(itemViews(port)).toEqual([]);
    await timeUp(1);
    expect(itemViews(port)).toHaveLength(1);
    expect(port.lastView()).toMatchObject({ kind: "items", items: [{ handle: "h1" }, { handle: "h2" }], passkeys: [] });
    const suggests = context.requests.filter((request) => request.type === "suggest");
    const signals = context.state.signals.filter((it) => it.type === "suggest");
    const passkeyRequest = suggests.findIndex((request) => request.kinds !== undefined);
    expect(passkeyRequest).toBeGreaterThanOrEqual(0);
    expect(signals[passkeyRequest]?.signal?.aborted).toBe(true);
  });

  it("shows passkeys that came first with the logins, in one view", async () => {
    const { context, release } = holding("login");
    const { port } = await context.openMenu("username", contentSender(), true);
    expect(itemViews(port)).toEqual([]);
    release();
    await settle();
    expect(itemViews(port)).toHaveLength(1);
    expect(port.lastView()).toMatchObject({
      items: [{ handle: "h1" }, { handle: "h2" }],
      passkeys: [{ handle: "pk1" }, { handle: "pk2" }],
    });
  });

  it("shows a failure or a blocked page without waiting for the passkeys, and nothing once the menu closed", async () => {
    // The logins fail: the menu shows the error, and nothing after it.
    const failing = holding("passkey", new ProtocolError("busy"));
    const menu = await failing.context.openMenu("username", contentSender(), true);
    expect(menu.port.posted.at(-1)).toEqual({ kind: "error", error: "busy" });
    failing.release();
    await settle();
    expect(itemViews(menu.port)).toEqual([]);

    const blocked = holding("passkey", { blocked: true, save_blocked: true, truncated: false, items: [] });
    const blockedMenu = await blocked.context.openMenu("username", contentSender(), true);
    expect(blockedMenu.port.lastView()).toEqual({ kind: "blocked", host: "login.example.com" });
    blocked.release();
    await settle();
    expect(blockedMenu.port.lastView()).toEqual({ kind: "blocked", host: "login.example.com" });

    const closing = holding("passkey");
    const closed = await closing.context.openMenu("username", contentSender(), true);
    closed.port.close();
    const posted = closed.port.posted.length;
    closing.release();
    await settle();
    expect(closed.port.posted).toHaveLength(posted);
  });

  it("checks the page, then answers the request with the picked passkey and closes", async () => {
    const context = passkeySetup();
    const { port } = await context.openMenu("username", contentSender(), true);
    port.emit({ kind: "passkey", handle: "unknown" });
    await settle();
    expect(port.posted.at(-1)).toEqual({ kind: "error", error: "invalid_request" });
    expect(context.state.picks).toEqual([]);

    port.emit({ kind: "passkey", handle: "pk2" });
    await settle();
    expect(context.sent.map((it) => it.message.kind)).toEqual(["guard", "menu_close"]);
    expect(context.sent[0]!.target).toEqual({ frameId: 3, documentId: "doc-3" });
    expect(context.state.picks).toEqual([{ frame: FRAME, handle: "pk2" }]);
    expect(port.disconnected).toBe(true);
  });

  it("picks nothing under an overlay, and shows the app's refusal", async () => {
    const context = passkeySetup();
    const { port } = await context.openMenu("username", contentSender(), true);
    context.state.contentScript = (message) =>
      message.kind === "guard" ? { ok: false, reason: "obscured" } : undefined;
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(context.state.picks).toEqual([]);
    expect(port.posted).toContainEqual({ kind: "error", error: "obscured" });

    context.state.contentScript = () => ({ ok: true });
    context.state.pickError = new ProtocolError("user_denied");
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(context.state.picks).toHaveLength(1);
    expect(port.posted.at(-2)).toEqual({ kind: "error", error: "user_denied" });
    // The page still waits: the menu stays.
    expect(port.disconnected).toBe(false);
  });

  it("picks nothing when a single-page app changed the URL since the menu opened: it shows the matches for the new one", async () => {
    const context = passkeySetup();
    const ROUTE = "https://login.example.com/admin";
    const { port } = await context.openMenu("username", contentSender(), true);
    context.state.contentScript = (message) => (message.kind === "guard" ? { ok: true, url: ROUTE } : { ok: true });
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(context.state.picks).toEqual([]);
    expect(context.sent.map((it) => it.message.kind)).toEqual(["guard"]);
    expect(context.requests.slice(2)).toEqual([
      { type: "suggest", url: ROUTE, kinds: ["passkey"], rp_id: "example.com" },
      { type: "suggest", url: ROUTE },
    ]);
    expect(port.lastView()).toMatchObject({ kind: "items", passkeys: [{ handle: "pk1" }, { handle: "pk2" }] });
    expect(port.posted.filter((it) => it.kind === "error")).toEqual([]);
    expect(port.disconnected).toBe(false);

    // The next pick is for the new URL, in the same document.
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(context.state.picks).toEqual([{ frame: { ...FRAME, url: ROUTE }, handle: "pk1" }]);
    expect(port.disconnected).toBe(true);
  });

  it("keeps the newer view when an older refresh's passkeys come late", async () => {
    const context = passkeySetup();
    const answers = context.state.app;
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => (release = resolve));
    context.state.app = (request) =>
      request.kinds !== undefined ? held.then(() => answers(request)) : answers(request);
    const { port } = await context.openMenu("username", contentSender(), true);
    // The page's request ends while the first refresh waits for its passkeys.
    context.state.conditional = null;
    context.pages.conditionalChanged(FRAME);
    await settle();
    expect(port.lastView()).toMatchObject({ kind: "items", passkeys: [] });
    release();
    await settle();
    expect(port.lastView()).toMatchObject({ kind: "items", passkeys: [] });
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(port.posted.at(-1)).toEqual({ kind: "error", error: "invalid_request" });
  });

  it("cancels both suggests when the menu closes", async () => {
    const context = passkeySetup();
    context.state.app = (request) => (request.type === "suggest" ? new Promise(() => undefined) : undefined);
    const { port } = await context.openMenu("username", contentSender(), true);
    const signals = context.state.signals.filter((it) => it.type === "suggest").map((it) => it.signal);
    expect(signals.map((it) => it?.aborted)).toEqual([false, false]);
    port.close();
    expect(signals.map((it) => it?.aborted)).toEqual([true, true]);
  });

  it("drops the passkeys when the page's request ends", async () => {
    const context = passkeySetup();
    const { port } = await context.openMenu("username", contentSender(), true);
    const other = await context.openMenu("username", contentSender({ frameId: 5, documentId: "doc-5" }), true);
    context.requests.length = 0;
    context.state.conditional = null;
    context.pages.conditionalChanged(FRAME);
    await settle();
    // Only the menu of that frame asks again.
    expect(context.requests).toEqual([{ type: "suggest", url: LOGIN_URL }]);
    expect(port.lastView()).toMatchObject({ kind: "items", passkeys: [] });
    expect(other.port.lastView()).toMatchObject({ kind: "items", passkeys: [{ handle: "pk1" }, { handle: "pk2" }] });
    port.emit({ kind: "passkey", handle: "pk1" });
    await settle();
    expect(port.posted.at(-1)).toEqual({ kind: "error", error: "invalid_request" });
  });
});

describe("with the app session", () => {
  let noise: NativeCryptoNoise;
  beforeAll(async () => {
    noise = await loadNoise();
  });

  // Real timers for the session, which waits on the Wasm and WebCrypto: nothing here waits on a probe.
  beforeEach(() => {
    vi.useRealTimers();
  });

  it("suggests and fills through the protocol session, for the frame's URL", async () => {
    const origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";
    const appKeys = noise.generateKeyPair();
    const pairedKeys = new Set<string>();
    let host: FakeHost | null = null;
    const statuses: ExtensionStatus[] = [];
    const controller = new BackgroundController({
      connectNative: () => {
        const port = new FakeNativePort();
        host = new FakeHost(port, () => ({
          noise,
          origin,
          keys: appKeys,
          pairedKeys,
          vault: "unlocked",
          handler: (request) => {
            if (request.type === "suggest") {
              return { blocked: false, save_blocked: false, truncated: false, items: [item("s1", { has_totp: true })] };
            }
            if (request.type === "fill") return { username: "ann", password: "pw" };
            // The approval prompt stays until the extension cancels.
            if (request.type === "totp") return new Promise(() => undefined);
            return undefined;
          },
        }));
        return port;
      },
      lastError: () => undefined,
      loadNoise: () => Promise.resolve(noise),
      storage: fakeBrowser.storage.local,
      origin,
      browserName: "Chrome",
      tab: async () => ({ url: LOGIN_URL }),
      copyText: async () => undefined,
      mayCopyPassword: () => false,
      onStatus: (status) => statuses.push(status),
      initialBackoffMs: 1,
    });
    const sent: Sent[] = [];
    const pages = new PageController({
      extensionId: ID,
      app: controller,
      options: new OptionsStore(fakeBrowser.storage.local),
      passkeys: { conditionalFor: () => null, pick: async () => undefined },
      sendToFrame: async (tabId, message, target) => {
        sent.push({ tabId, message, target });
        return { ok: true };
      },
      sendToTab: async () => undefined,
      injectContentScript: async () => undefined,
      tab: async () => ({ url: LOGIN_URL }),
      siteIcon: async () => null,
      tabs: new TabWatch(),
      openPopup: async () => undefined,
      copyText: async () => undefined,
      documentIds: true,
    });
    controller.start();
    host!.ready();
    for (let i = 0; i < 200 && controller.snapshot.kind !== "not_paired"; i++) await settle();
    await controller.calls.pair({});
    expect(controller.snapshot.kind).toBe("unlocked");
    expect(controller.supports("save")).toBe(true);

    const frameUrl = "https://auth.idp.example/embedded-login";
    const sender = contentSender({
      frameId: 2,
      documentId: "doc-2",
      url: frameUrl,
      origin: "https://auth.idp.example",
    });
    const { token } = (await fromContent(pages, { kind: "menu_open", role: "username" }, sender)) as { token: string };
    const port = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(port);
    port.emit({ kind: "bind", token });
    for (let i = 0; i < 200 && port.lastView()?.kind !== "items"; i++) await settle();
    port.emit({ kind: "fill", handle: "s1" });
    for (let i = 0; i < 200 && !sent.some((it) => it.message.kind === "fill"); i++) await settle();

    const requests = host!.app!.requests;
    expect(requests.find((it) => it.type === "suggest")).toMatchObject({ url: frameUrl });
    expect(requests.find((it) => it.type === "fill")).toMatchObject({ handle: "s1", url: frameUrl });
    expect(sent.at(-1)).toEqual({
      tabId: 7,
      message: {
        kind: "fill",
        doc: docOf(sender),
        origin: "https://auth.idp.example",
        target: "menu",
        username: "ann",
        password: "pw",
      },
      target: { frameId: 2, documentId: "doc-2" },
    });

    // The user closes the menu while the app asks: the app gets a `cancel`.
    port.emit({ kind: "totp", handle: "s1" });
    for (let i = 0; i < 200 && !requests.some((it) => it.type === "totp"); i++) await settle();
    port.close();
    for (let i = 0; i < 200 && host!.app!.cancels.length === 0; i++) await settle();
    expect(host!.app!.cancels).toEqual([requests.find((it) => it.type === "totp")!.id]);
  });
});
