// The background's side of the card beside the inline menu
// (../src/lib/background/menuDetails.ts): its token, its views and size, the
// selection and focus it follows, and its copies, which go to the clipboard
// from the background after the checks of a menu fill.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import {
  MAX_DETAIL_URIS,
  MENU_DETAILS_PORT,
  MENU_PORT,
  parseMenuDetailsRequest,
  parseMenuRequest,
  type ContentCommand,
} from "../src/lib/page/messages";
import { TOKEN_TTL_MS } from "../src/lib/background/pages";
import { ProtocolError } from "../src/lib/protocol/session";
import {
  contentSender,
  docOf,
  FakePort,
  fromContent,
  iconOf,
  item,
  LOGIN_ORIGIN,
  LOGIN_URL,
  pageSender,
  settle,
  setup,
  type Sent,
} from "./support/pages";

beforeEach(() => {
  fakeBrowser.reset();
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("the card beside the inline menu", () => {
  const URIS = Array.from({ length: 7 }, (_, index) => `https://login.example.com/${index}`);
  const REVISION = "2026-11-20T10:00:00Z";

  /** A menu whose first login has 7 URIs of 9, a folder and a date, with its card bound. */
  async function openWithCard(context = setup(), sender = contentSender()) {
    context.state.suggest = [
      item("h1", { uris: URIS, uri_count: 9, folder: "Work", revision_date: REVISION }),
      item("h2", { username: undefined }),
    ];
    const menu = await context.openMenu("username", sender);
    const card = await context.bindDetails(menu.details);
    return { ...context, menu: menu.port, details: menu.details, card };
  }

  /** The commands about the card that the content script got. */
  const cardCommands = (sent: Sent[]) =>
    sent
      .map((it) => it.message)
      .filter((it) => it.kind === "menu_details" || it.kind === "menu_details_hide")
      .map(({ doc: _doc, origin: _origin, ...command }: ContentCommand & { doc?: string; origin?: string }) => command);

  /** The views the card's page got. */
  const cardViews = (card: FakePort) => card.posted.filter((it) => it.kind === "view");

  async function select(menu: FakePort, handle: string, top = 40, height = 48) {
    menu.emit({ kind: "select", handle, top, height });
    await settle();
  }

  async function resize(card: FakePort, seq: number, height = 150) {
    card.emit({ kind: "resize", height, seq });
    await settle();
  }

  it("issues a token for the card with the menu's, which binds only the card's page, in its tab, once", async () => {
    const { pages } = setup();
    const result = (await fromContent(pages, { kind: "menu_open", role: "username" })) as {
      token: string;
      details: string;
    };
    expect(result.details).toMatch(/^[0-9a-f]{32}$/);
    expect(result.details).not.toBe(result.token);

    // Not on the menu's port, nor the menu's token on the card's.
    const menuPage = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(menuPage);
    menuPage.emit({ kind: "bind", token: result.details });
    expect(menuPage.posted).toEqual([]);
    const wrongToken = new FakePort(MENU_DETAILS_PORT, pageSender("/menu-details.html"));
    pages.connect(wrongToken);
    wrongToken.emit({ kind: "bind", token: result.token });
    expect(wrongToken.posted).toEqual([]);
    // Neither another page nor a content script on the card's port.
    for (const sender of [pageSender("/inline-menu.html"), contentSender()]) {
      const port = new FakePort(MENU_DETAILS_PORT, sender);
      pages.connect(port);
      expect(port.disconnected).toBe(true);
    }
    // Not in another tab.
    const otherTab = new FakePort(MENU_DETAILS_PORT, pageSender("/menu-details.html", 8));
    pages.connect(otherTab);
    otherTab.emit({ kind: "bind", token: result.details });
    expect(otherTab.posted).toEqual([]);

    const card = new FakePort(MENU_DETAILS_PORT, pageSender("/menu-details.html"));
    pages.connect(card);
    card.emit({ kind: "bind", token: result.details });
    expect(card.posted).toEqual([{ kind: "bound" }]);
    // Used up.
    const again = new FakePort(MENU_DETAILS_PORT, pageSender("/menu-details.html"));
    pages.connect(again);
    again.emit({ kind: "bind", token: result.details });
    expect(again.posted).toEqual([]);
  });

  it("shows the selected login's details, with 5 URIs, once the card told the height of that view", async () => {
    const { menu, card, sent } = await openWithCard();
    await select(menu, "h1", 40, 48);
    expect(cardViews(card)).toEqual([
      {
        kind: "view",
        seq: 1,
        handle: "h1",
        item: {
          name: "Item h1",
          username: "ann",
          account: "Personal",
          copies: ["copy_username", "copy_password"],
          reprompt: false,
          matched: true,
          last: false,
          icon: iconOf(LOGIN_URL),
          passkeys: [],
          details: { uris: URIS.slice(0, 5), more: 4, folder: "Work", revision_date: REVISION },
        },
      },
    ]);
    // Not before the card has the height of what it shows.
    expect(cardCommands(sent)).toEqual([]);
    await resize(card, 1, 150);
    expect(cardCommands(sent)).toEqual([{ kind: "menu_details", top: 40, row: 48, height: 150 }]);

    // Another login: the height of the first view is stale.
    await select(menu, "h2", 92, 48);
    expect(cardViews(card).at(-1)).toEqual({
      kind: "view",
      seq: 2,
      handle: "h2",
      item: {
        name: "Item h2",
        account: "Personal",
        copies: ["copy_password"],
        reprompt: false,
        matched: true,
        last: false,
        icon: iconOf(LOGIN_URL),
        passkeys: [],
      },
    });
    await resize(card, 1, 150);
    expect(cardCommands(sent)).toHaveLength(1);
    await resize(card, 2, 90);
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 92, row: 48, height: 90 });

    // The same login's row moved, as on a scroll: the card moves, with the view it has.
    await select(menu, "h2", 60, 48);
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 60, row: 48, height: 90 });
    expect(cardViews(card)).toHaveLength(2);
    // Each command for the menu's document.
    for (const { message } of sent) expect((message as { doc?: string }).doc).toBe(docOf(contentSender()));
  });

  it("shows the login selected while its page loaded once it binds", async () => {
    const context = setup();
    const { port, details } = await context.openMenu("username");
    await select(port, "h1");
    const card = await context.bindDetails(details);
    expect(card.posted).toEqual([
      { kind: "bound" },
      { kind: "view", seq: 1, handle: "h1", item: expect.objectContaining({ name: "Item h1" }) },
    ]);
    await resize(card, 1);
    expect(cardCommands(context.sent)).toEqual([{ kind: "menu_details", top: 40, row: 48, height: 150 }]);
  });

  it("hides the card when no row is selected, while an action runs, and for a handle the menu doesn't list", async () => {
    const context = await openWithCard();
    const { menu, card, sent } = context;
    await select(menu, "h1");
    await resize(card, 1);
    // At once.
    menu.emit({ kind: "deselect" });
    await settle();
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details_hide" });

    await select(menu, "h1");
    await resize(card, 2);
    let fail!: () => void;
    context.state.app = (request) =>
      request.type === "fill"
        ? new Promise((_resolve, reject) => (fail = () => reject(new ProtocolError("busy"))))
        : undefined;
    menu.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details_hide" });
    // Not while it runs.
    await select(menu, "h1", 60);
    expect(cardViews(card)).toHaveLength(2);
    // Once it ends, the row the menu still selects shows its card again.
    fail();
    await settle();
    expect(cardViews(card)).toHaveLength(3);
    await resize(card, 3);
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 60, row: 48, height: 150 });

    const other = await openWithCard();
    await select(other.menu, "h1");
    await resize(other.card, 1);
    await select(other.menu, "forged");
    expect(cardCommands(other.sent).at(-1)).toEqual({ kind: "menu_details_hide" });
    expect(cardViews(other.card)).toHaveLength(1);
  });

  it("hides the card when the menu lists no logins, or a refresh drops the login; shows it anew when the refresh keeps it", async () => {
    const context = await openWithCard();
    const { menu, card, sent } = context;
    await select(menu, "h1");
    await resize(card, 1);
    // A refresh, as the app's state changed: the details may have too.
    context.state.suggest = [item("h1", { folder: "Home" }), item("h2")];
    context.pages.statusChanged();
    await settle();
    expect(cardViews(card).at(-1)).toMatchObject({ seq: 2, item: { details: { folder: "Home" } } });
    // Its flags too, and the menu's: a suggested login gets no site icon, a hidden password shows none.
    context.state.suggest = [
      item("h2"),
      item("h1", {
        folder: "Home",
        matched: false,
        password_hidden: true,
        has_totp: true,
        reprompt: true,
        last_used: true,
      }),
    ];
    context.pages.statusChanged();
    await settle();
    expect(cardViews(card).at(-1)).toMatchObject({
      seq: 3,
      item: {
        name: "Item h1",
        copies: ["copy_username", "copy_totp"],
        reprompt: true,
        matched: false,
        last: true,
        icon: null,
      },
    });
    context.state.suggest = [item("h1", { folder: "Home" }), item("h2")];
    context.pages.statusChanged();
    await settle();
    expect(cardViews(card).at(-1)).toMatchObject({
      seq: 4,
      item: { copies: ["copy_username", "copy_password"], matched: true, last: false, icon: iconOf(LOGIN_URL) },
    });
    await resize(card, 4, 100);
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 40, row: 48, height: 100 });
    // A refresh that changes nothing the card shows: it keeps its view, and only moves.
    const commands = cardCommands(sent).length;
    context.pages.statusChanged();
    await settle();
    expect(cardViews(card).at(-1)).toMatchObject({ seq: 4 });
    expect(cardCommands(sent)).toHaveLength(commands + 1);
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 40, row: 48, height: 100 });

    context.state.suggest = [item("h2")];
    context.pages.statusChanged();
    await settle();
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details_hide" });

    await select(menu, "h2");
    await resize(card, 5);
    context.state.status = { kind: "locked", unlocking: false };
    context.pages.statusChanged();
    await settle();
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details_hide" });
    // Nothing to show while it's locked.
    const views = cardViews(card).length;
    await select(menu, "h2");
    expect(cardViews(card)).toHaveLength(views);
  });

  it("closes the card's page with its menu, and with a menu that replaces it before either bound", async () => {
    const { menu, card } = await openWithCard();
    menu.emit({ kind: "close" });
    await settle();
    expect(card.disconnected).toBe(true);

    const context = setup();
    const first = (await fromContent(context.pages, { kind: "menu_open", role: "username" })) as {
      token: string;
      details: string;
    };
    const early = await context.bindDetails(first.details);
    expect(early.posted).toEqual([{ kind: "bound" }]);
    const second = (await fromContent(context.pages, { kind: "menu_open", role: "username" })) as {
      details: string;
    };
    expect(early.disconnected).toBe(true);
    const late = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    context.pages.connect(late);
    late.emit({ kind: "bind", token: first.token });
    expect(late.posted).toEqual([]);
    // The newer menu's card binds.
    expect((await context.bindDetails(second.details)).posted).toEqual([{ kind: "bound" }]);

    // A card whose menu closed before it bound gets nothing.
    const closed = await context.openMenu("username");
    closed.port.close();
    expect((await context.bindDetails(closed.details)).posted).toEqual([]);
  });

  /** A menu with its card showing login [handle]: selected, and its height told. */
  async function showing(handle = "h1", context = setup(), sender = contentSender()) {
    const opened = await openWithCard(context, sender);
    await select(opened.menu, handle);
    await resize(opened.card, cardViews(opened.card).at(-1)!.seq as number);
    return opened;
  }

  /** The view the card's page shows last: what its copies are for. */
  const lastSeq = (card: FakePort) => (cardViews(card).at(-1)?.seq as number | undefined) ?? 0;

  /** The card copies [action] of [handle] in view [seq], its last by default, as on a click past its gate. */
  async function copy(card: FakePort, action: string, handle = "h1", seq = lastSeq(card)) {
    card.emit({ kind: "copy", action, handle, seq });
    await settle();
  }

  /** What the card's page got after its views. */
  const cardEvents = (card: FakePort) => card.posted.filter((it) => it.kind !== "view").map((it) => it.kind);

  /** The popups the background asked the content script to check before acting. */
  const guards = (sent: Sent[]) =>
    sent.filter((it) => it.message.kind === "guard").map((it) => (it.message as { popup: string }).popup);

  it("drops the card's token with its menu's: a menu that replaces it, its tab closing, its time running out", async () => {
    const context = setup();
    const { pages } = context;
    const open = async () =>
      (await fromContent(pages, { kind: "menu_open", role: "username" })) as { token: string; details: string };
    // Replaced before either bound.
    const replaced = await open();
    await open();
    expect((await context.bindDetails(replaced.details)).posted).toEqual([]);
    // Its tab closed.
    const removed = await open();
    pages.tabRemoved(7);
    expect((await context.bindDetails(removed.details)).posted).toEqual([]);
    // The menu's page binds too late: the card that bound in time goes with it.
    const late = await open();
    const card = await context.bindDetails(late.details);
    expect(card.posted).toEqual([{ kind: "bound" }]);
    context.advance(TOKEN_TTL_MS + 1);
    const menu = new FakePort(MENU_PORT, pageSender("/inline-menu.html"));
    pages.connect(menu);
    menu.emit({ kind: "bind", token: late.token });
    await settle();
    expect(menu.posted).toEqual([]);
    expect(card.disconnected).toBe(true);
  });

  it("closes the card with its menu however the menu ends, cancelling its copy; the card closing leaves the menu", async () => {
    // The menu's page went, as on a navigation: its content script closes nothing.
    const context = setup();
    const { menu, card, sent } = await showing("h1", context);
    context.state.app = (request) => (request.type === "fill" ? new Promise(() => undefined) : undefined);
    await copy(card, "copy_password");
    const signal = context.signalOf("fill");
    expect(signal?.aborted).toBe(false);
    menu.close();
    expect(card.disconnected).toBe(true);
    expect(signal?.aborted).toBe(true);
    await settle();
    expect(context.state.copies).toEqual([]);
    expect(sent.filter((it) => it.message.kind === "menu_close")).toEqual([]);

    // Its tab closed.
    const removed = await openWithCard();
    removed.pages.tabRemoved(7);
    expect(removed.menu.disconnected).toBe(true);
    expect(removed.card.disconnected).toBe(true);

    // The card's page went: the menu stays, and selects and fills as before.
    const other = await showing();
    other.card.close();
    await select(other.menu, "h2");
    other.menu.emit({ kind: "fill", handle: "h2" });
    await settle();
    expect(other.menu.disconnected).toBe(false);
    expect(other.fills()).toHaveLength(1);
    expect(other.menu.posted.filter((it) => it.kind === "error")).toEqual([]);
  });

  it("hides no card once its menu closed during a copy's checks, whatever they answer", async () => {
    const context = setup();
    const { menu, card, sent } = await showing("h1", context);
    const hides = () => cardCommands(sent).filter((it) => it.kind === "menu_details_hide").length;
    let answer: (value: unknown) => void = () => undefined;
    const contentScript = context.state.contentScript;
    context.state.contentScript = (message, target) =>
      message.kind === "guard" ? new Promise((resolve) => (answer = resolve)) : contentScript(message, target);
    await copy(card, "copy_username");
    menu.close();
    expect(hides()).toBe(0);
    // A single-page app moved on meanwhile.
    answer({ ok: true, url: `${LOGIN_ORIGIN}/next` });
    await settle();
    expect(hides()).toBe(0);
    expect(context.state.copies).toEqual([]);
  });

  it("binds no card once its menu ended: closed by the user, or with its tab", async () => {
    const context = setup();
    const closed = await context.openMenu("username");
    closed.port.emit({ kind: "close" });
    await settle();
    expect((await context.bindDetails(closed.details)).posted).toEqual([]);

    const removed = await context.openMenu("username");
    context.pages.tabRemoved(7);
    expect((await context.bindDetails(removed.details)).posted).toEqual([]);
  });

  it("copies the username from the menu's suggest after the checks for the card, and keeps the menu and the card", async () => {
    const { card, menu, sent, requests, state } = await showing();
    const before = sent.length;
    await copy(card, "copy_username");
    // The card and the menu's field, in the menu's document.
    expect(guards(sent)).toEqual(["menu_details"]);
    expect(sent.slice(before).map((it) => it.message.kind)).toEqual(["guard"]);
    expect((sent.at(-1)!.message as { doc: string }).doc).toBe(docOf(contentSender()));
    // No app request.
    expect(requests.map((it) => it.type)).toEqual(["suggest"]);
    expect(state.copies).toEqual(["ann"]);
    expect(cardEvents(card)).toEqual(["bound", "busy", "copied", "busy"]);
    expect(card.posted.filter((it) => it.kind === "busy").map((it) => it.busy)).toEqual([true, false]);
    // Nothing closes or hides.
    expect(menu.disconnected).toBe(false);
    expect(card.disconnected).toBe(false);
    expect(sent.map((it) => it.message.kind)).not.toContain("menu_close");
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 40, row: 48, height: 150 });
  });

  it("copies the password and the one-time password from the app for the page, as a menu fill asks, and never posts them", async () => {
    const context = setup();
    const embedded = contentSender({ url: "https://idp.example.net/frame", origin: "https://idp.example.net" });
    const opened = await showing("h1", context, embedded);
    // A suggested login: the app prompts for it.
    context.state.suggest = [item("h1", { has_totp: true, matched: false })];
    context.pages.statusChanged();
    await settle();
    await resize(opened.card, cardViews(opened.card).at(-1)!.seq as number);
    const { card, menu, sent, requests, state } = opened;
    await copy(card, "copy_password");
    await copy(card, "copy_totp");
    expect(guards(sent)).toEqual(["menu_details", "menu_details"]);
    expect(requests.filter((it) => it.type !== "suggest")).toEqual([
      { type: "fill", handle: "h1", url: "https://idp.example.net/frame", top_origin: LOGIN_ORIGIN },
      { type: "totp", handle: "h1", url: "https://idp.example.net/frame", top_origin: LOGIN_ORIGIN },
    ]);
    expect(state.copies).toEqual(["secret-h1", "123456"]);
    expect(card.posted.filter((it) => it.kind === "copied")).toHaveLength(2);
    // Only the clipboard gets them: not the card, the menu or the content script.
    for (const posted of [card.posted, menu.posted, sent.map((it) => it.message)]) {
      expect(JSON.stringify(posted)).not.toContain("secret");
      expect(JSON.stringify(posted)).not.toContain("123456");
    }
  });

  it("copies in a private window as the menu fills there", async () => {
    const context = setup();
    const sender = contentSender({ tab: { id: 7, url: LOGIN_URL, incognito: true } });
    const { card, requests } = await showing("h1", context, sender);
    await copy(card, "copy_password");
    expect(requests.at(-1)).toEqual({ type: "fill", handle: "h1", url: LOGIN_URL, private_window: true });
  });

  it("refuses a handle the card doesn't show or the menu doesn't list, a hidden password, and a field the login lacks", async () => {
    const context = setup();
    const { card, menu, sent, requests, state } = await showing("h1", context);
    // Shown in the menu, not in the card.
    await copy(card, "copy_username", "h2");
    await copy(card, "copy_username", "forged");
    // A login without a one-time password.
    await copy(card, "copy_totp");
    // Its collections hide the password: as the popup, the card copies none.
    context.state.suggest = [item("h1", { password_hidden: true }), item("h2", { username: undefined })];
    context.pages.statusChanged();
    await settle();
    await copy(card, "copy_password");
    // A login without a username.
    await select(menu, "h2");
    await copy(card, "copy_username", "h2");
    // The card hid: nothing to copy.
    menu.emit({ kind: "deselect" });
    await settle();
    await copy(card, "copy_password", "h2");
    // The menu's latest suggest dropped the login.
    await select(menu, "h1");
    context.state.suggest = [item("h2")];
    context.pages.statusChanged();
    await settle();
    await copy(card, "copy_username");
    expect(card.posted.filter((it) => it.kind === "error").map((it) => it.error)).toEqual([
      "invalid_request",
      "invalid_request",
      "invalid_request",
      "not_allowed",
      "invalid_request",
      "invalid_request",
      "invalid_request",
    ]);
    expect(guards(sent)).toEqual([]);
    expect(requests.map((it) => it.type).filter((type) => type !== "suggest")).toEqual([]);
    expect(state.copies).toEqual([]);
  });

  it("copies nothing when the page covers the card or its field, or moved to another URL", async () => {
    const context = setup();
    const { card, menu, requests, state } = await showing("h1", context);
    context.state.contentScript = (message) =>
      message.kind === "guard" ? { ok: false, reason: "popup_covered" } : { ok: true };
    await copy(card, "copy_password");
    await copy(card, "copy_username");
    expect(card.posted.filter((it) => it.kind === "error").map((it) => it.error)).toEqual([
      "popup_covered",
      "popup_covered",
    ]);
    expect(requests.map((it) => it.type)).toEqual(["suggest"]);
    // A single-page app moved on since the menu opened: the menu shows its matches instead.
    context.state.contentScript = (message) =>
      message.kind === "guard" ? { ok: true, url: "https://login.example.com/other" } : { ok: true };
    await copy(card, "copy_password");
    expect(requests.map((it) => [it.type, it.url])).toEqual([
      ["suggest", LOGIN_URL],
      ["suggest", "https://login.example.com/other"],
    ]);
    expect(state.copies).toEqual([]);
    expect(menu.views().filter((view) => view.kind === "items")).toHaveLength(2);
    // A copy the menu's closing cancels copies nothing.
    context.state.contentScript = () => ({ ok: true });
    await select(menu, "h1");
    await resize(card, cardViews(card).at(-1)!.seq as number);
    let release!: () => void;
    context.state.app = (request) =>
      request.type === "fill"
        ? new Promise((resolve) => (release = () => resolve({ password: "late-secret" })))
        : undefined;
    await copy(card, "copy_password");
    menu.emit({ kind: "close" });
    await settle();
    release();
    await settle();
    expect(context.signalOf("fill")?.aborted).toBe(true);
    expect(state.copies).toEqual([]);
  });

  it("hides the card at once when the menu selects no login, even while it copies, which still ends", async () => {
    const context = setup();
    const { card, menu, sent } = await showing("h1", context);
    const hidden = () => cardCommands(sent).filter((it) => it.kind === "menu_details_hide").length;
    // A copy waiting for the app's prompt.
    let release!: () => void;
    context.state.app = (request) =>
      request.type === "fill" ? new Promise((resolve) => (release = () => resolve({ password: "pw" }))) : undefined;
    await copy(card, "copy_password");
    menu.emit({ kind: "deselect" });
    await settle();
    expect(hidden()).toBe(1);
    expect(card.posted.at(-1)).toEqual({ kind: "hidden" });
    release();
    await settle();
    expect(context.state.copies).toEqual(["pw"]);
    expect(cardEvents(card).slice(-2)).toEqual(["copied", "busy"]);
  });

  it("hides the card and drops the menu's selection when focus left both for the page: only in the sender's document", async () => {
    const context = setup();
    const { card, menu, sent, pages } = await showing("h1", context);
    const hidden = () => cardCommands(sent).filter((it) => it.kind === "menu_details_hide").length;
    await fromContent(pages, { kind: "menu_blur" }, contentSender({ frameId: 4, documentId: "doc-4" }));
    expect(hidden()).toBe(0);
    expect(menu.posted.filter((it) => it.kind === "deselect")).toEqual([]);
    await fromContent(pages, { kind: "menu_blur" });
    expect(hidden()).toBe(1);
    expect(card.posted.at(-1)).toEqual({ kind: "hidden" });
    expect(menu.posted.filter((it) => it.kind === "deselect")).toEqual([{ kind: "deselect" }]);
    // Nothing to copy, nor to move focus into, till Space selects the login again.
    await copy(card, "copy_username");
    expect(card.posted.at(-1)).toEqual({ kind: "error", error: "invalid_request" });
    await select(menu, "h1");
    await resize(card, lastSeq(card));
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 40, row: 48, height: 150 });
  });

  /** The content script answers `has_focus` with [focused]'s value, and the rest with `ok`. */
  function focusAnswers(context: ReturnType<typeof setup>) {
    const focus = { focused: false as boolean | Error };
    context.state.contentScript = (message) =>
      message.kind !== "has_focus"
        ? { ok: true }
        : focus.focused instanceof Error
          ? focus.focused
          : focus.focused
            ? { ok: true }
            : { ok: false, reason: "popup_covered" };
    return focus;
  }

  /** The `has_focus` commands that the content scripts got. */
  const focusProbes = (sent: Sent[]) => sent.filter((it) => it.message.kind === "has_focus");

  it("hides the card and drops the menu's selection once the window lost focus, from the menu's frame or the card's: the menu stays", async () => {
    const context = setup();
    const focus = focusAnswers(context);
    const { card, menu, sent } = await showing("h1", context);
    const hidden = () => cardCommands(sent).filter((it) => it.kind === "menu_details_hide").length;
    const deselects = () => menu.posted.filter((it) => it.kind === "deselect").length;

    // Focus moved from the menu into the card, or back: the menu's document still has it.
    focus.focused = true;
    menu.emit({ kind: "blurred" });
    card.emit({ kind: "blurred" });
    await settle();
    expect(focusProbes(sent)).toHaveLength(2);
    for (const { message, target } of focusProbes(sent)) {
      expect(message).toEqual({ kind: "has_focus", doc: docOf(contentSender()), origin: LOGIN_ORIGIN });
      expect(target).toEqual({ frameId: 3, documentId: "doc-3" });
    }
    expect(hidden()).toBe(0);
    expect(deselects()).toBe(0);

    // The window lost focus, as to the app's prompt, while focus was in the menu.
    focus.focused = false;
    menu.emit({ kind: "blurred" });
    await settle();
    expect(hidden()).toBe(1);
    expect(card.posted.at(-1)).toEqual({ kind: "hidden" });
    expect(deselects()).toBe(1);
    // No login selected: nothing to ask, nor to hide.
    card.emit({ kind: "blurred" });
    await settle();
    expect(focusProbes(sent)).toHaveLength(3);

    // While focus was in the card; and a document that can't answer counts as one without focus.
    for (const answer of [false, new Error("Receiving end does not exist.")]) {
      await select(menu, "h1");
      await resize(card, lastSeq(card));
      focus.focused = answer;
      card.emit({ kind: "blurred" });
      await settle();
    }
    expect(hidden()).toBe(3);
    expect(deselects()).toBe(3);

    // The menu stays open.
    expect(menu.disconnected).toBe(false);
    expect(card.disconnected).toBe(false);
    expect(sent.filter((it) => it.message.kind === "menu_close")).toEqual([]);
  });

  it("hides the card at once when the window lost focus during a copy, as to the app's prompt, which still ends", async () => {
    const context = setup();
    focusAnswers(context);
    const { card, menu, sent } = await showing("h1", context);
    let release!: () => void;
    context.state.app = (request) =>
      request.type === "fill" ? new Promise((resolve) => (release = () => resolve({ password: "pw" }))) : undefined;
    await copy(card, "copy_password");
    card.emit({ kind: "blurred" });
    await settle();
    expect(cardCommands(sent).filter((it) => it.kind === "menu_details_hide")).toHaveLength(1);
    expect(card.posted.at(-1)).toEqual({ kind: "hidden" });
    expect(menu.posted.filter((it) => it.kind === "deselect")).toEqual([{ kind: "deselect" }]);
    release();
    await settle();
    expect(context.state.copies).toEqual(["pw"]);
    expect(cardEvents(card).slice(-2)).toEqual(["copied", "busy"]);
  });

  it("drops a blur of a page that didn't bind, and malformed or unknown ones", async () => {
    expect(parseMenuRequest({ kind: "blurred", handle: "h1" })).toEqual({ kind: "blurred" });
    expect(parseMenuDetailsRequest({ kind: "blurred", seq: 1 })).toEqual({ kind: "blurred" });
    for (const request of [
      { kind: "blur" },
      { kind: "menu_blur" },
      { kind: "Blurred" },
      { blurred: true },
      "blurred",
    ]) {
      expect(parseMenuRequest(request), JSON.stringify(request)).toBeNull();
      expect(parseMenuDetailsRequest(request), JSON.stringify(request)).toBeNull();
    }
    // The card selects nothing itself.
    expect(parseMenuDetailsRequest({ kind: "deselect" })).toBeNull();

    const context = setup();
    focusAnswers(context);
    const { card, menu, sent, pages } = await showing("h1", context);
    // A page that didn't bind, or bound with another token.
    const stranger = new FakePort(MENU_DETAILS_PORT, pageSender("/menu-details.html"));
    pages.connect(stranger);
    stranger.emit({ kind: "blurred" });
    stranger.emit({ kind: "bind", token: "f".repeat(32) });
    stranger.emit({ kind: "blurred" });
    // A content script: the menu's document tells `menu_blur` only, once focus is on the page.
    await fromContent(pages, { kind: "blurred" });
    menu.emit({ kind: "blur" });
    card.emit({ kind: "blurred_window" });
    await settle();
    expect(focusProbes(sent)).toEqual([]);
    expect(cardCommands(sent).filter((it) => it.kind === "menu_details_hide")).toEqual([]);
    expect(card.posted.at(-1)).not.toEqual({ kind: "hidden" });
    expect(menu.posted.filter((it) => it.kind === "deselect")).toEqual([]);
  });

  it("tells the card when its frame shows and hides, for its click gate: only the card of the sender's document", async () => {
    const context = setup();
    const { card, menu, pages } = await showing("h1", context);
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: true });
    await fromContent(
      pages,
      { kind: "popup_shown", popup: "menu_details", shown: true },
      contentSender({ frameId: 4, documentId: "doc-4" }),
    );
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: false });
    expect(cardEvents(card)).toEqual(["bound", "shown", "hidden"]);
    // Hidden by the background: once it does.
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: true });
    menu.emit({ kind: "deselect" });
    await settle();
    expect(cardEvents(card)).toEqual(["bound", "shown", "hidden", "shown", "hidden"]);
  });

  it("tells the card and the menu which side of the menu the card shows on, for their arrow keys", async () => {
    const context = setup();
    const { card, menu, pages } = await showing("h1", context);
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: true, side: "left" });
    // It moved to the other side.
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: true, side: "right" });
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: false });
    expect(card.posted.filter((it) => it.kind === "shown" || it.kind === "hidden")).toEqual([
      { kind: "shown", side: "left" },
      { kind: "shown", side: "right" },
      { kind: "hidden" },
    ]);
    expect(menu.posted.filter((it) => it.kind === "details_side")).toEqual([
      { kind: "details_side", side: "left" },
      { kind: "details_side", side: "right" },
    ]);
  });

  it("drops the selection on both sides when the card has no room beside the menu: not while it's hidden for a while", async () => {
    const context = setup();
    const { card, menu, sent, pages } = await showing("h1", context);
    const hidden = () => cardCommands(sent).filter((it) => it.kind === "menu_details_hide").length;
    const deselects = () => menu.posted.filter((it) => it.kind === "deselect").length;
    // Hidden for a while, as with its row scrolled out of the menu: the selection stays.
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: false });
    expect(hidden()).toBe(0);
    expect(deselects()).toBe(0);
    // Another document's card lacks room: not this one's.
    await fromContent(
      pages,
      { kind: "popup_shown", popup: "menu_details", shown: false, room: false },
      contentSender({ frameId: 4, documentId: "doc-4" }),
    );
    expect(deselects()).toBe(0);
    // It fits on neither side: the menu's page holds no selection that shows nothing, so Escape closes it.
    await fromContent(pages, { kind: "popup_shown", popup: "menu_details", shown: false, room: false });
    expect(hidden()).toBe(1);
    expect(deselects()).toBe(1);
    expect(card.posted.at(-1)).toEqual({ kind: "hidden" });
    // Nothing to copy, nor to move focus into; an action doesn't bring the card back after.
    await copy(card, "copy_username");
    expect(card.posted.at(-1)).toEqual({ kind: "error", error: "invalid_request" });
    menu.emit({ kind: "details_focus", handle: "h1" });
    menu.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(sent.filter((it) => it.message.kind === "focus_popup")).toEqual([]);
    expect(cardViews(card)).toHaveLength(1);
    expect(menu.disconnected).toBe(false);
  });

  it("moves focus into the card on the menu's ArrowRight, and back to the row on the card's, through the content script", async () => {
    const context = setup();
    const focusAnswers: Record<string, unknown> = {};
    context.state.contentScript = (message) =>
      message.kind === "focus_popup" ? focusAnswers[message.popup] : { ok: true };
    const { card, menu, sent } = await showing("h1", context);
    /** The popups the content script was asked to move focus into. */
    const focusCommands = () => sent.flatMap(({ message }) => (message.kind === "focus_popup" ? [message.popup] : []));

    // The content script refuses: focus isn't in the menu, or the card doesn't show.
    menu.emit({ kind: "details_focus", handle: "h1" });
    await settle();
    expect(focusCommands()).toEqual(["menu_details"]);
    expect(cardEvents(card)).toEqual(["bound"]);
    // Not for a login the card doesn't show.
    menu.emit({ kind: "details_focus", handle: "h2" });
    await settle();
    expect(focusCommands()).toEqual(["menu_details"]);

    focusAnswers.menu_details = { ok: true };
    menu.emit({ kind: "details_focus", handle: "h1" });
    await settle();
    expect(cardEvents(card)).toEqual(["bound", "focus"]);
    expect((sent.at(-1)!.message as { doc: string }).doc).toBe(docOf(contentSender()));

    // Back to the row of the card's login.
    card.emit({ kind: "back" });
    await settle();
    expect(focusCommands().at(-1)).toBe("menu");
    expect(menu.posted.filter((it) => String(it.kind).startsWith("focus"))).toEqual([]);
    focusAnswers.menu = { ok: true };
    card.emit({ kind: "back" });
    await settle();
    expect(menu.posted.filter((it) => String(it.kind).startsWith("focus"))).toEqual([
      { kind: "focus_row", handle: "h1" },
    ]);

    // A view the card hasn't told the height of: not yet.
    await select(menu, "h2");
    menu.emit({ kind: "details_focus", handle: "h2" });
    await settle();
    expect(cardEvents(card)).toEqual(["bound", "focus"]);

    // Tab in the card closes the menu, as in the menu.
    card.emit({ kind: "close" });
    await settle();
    expect(sent.at(-1)!.message.kind).toBe("menu_close");
    expect(menu.disconnected).toBe(true);
    expect(card.disconnected).toBe(true);
  });

  it("moves focus into the card only while it shows the key's login at its height: never once it does", async () => {
    const context = setup();
    context.state.contentScript = () => ({ ok: true });
    const { card, menu, sent, state, pages } = await openWithCard(context);
    const focusCommands = () => sent.flatMap(({ message }) => (message.kind === "focus_popup" ? [message.popup] : []));

    // No card yet: the key is dropped, not kept for when it shows.
    menu.emit({ kind: "details_focus", handle: "h1" });
    await settle();
    await select(menu, "h1");
    menu.emit({ kind: "details_focus", handle: "h1" });
    await settle();
    await resize(card, lastSeq(card));
    expect(focusCommands()).toEqual([]);
    // Now it shows, placed.
    menu.emit({ kind: "details_focus", handle: "h1" });
    await settle();
    expect(focusCommands()).toEqual(["menu_details"]);
    expect(cardEvents(card)).toEqual(["bound", "focus"]);

    // Not for another login, nor once it hid, as when the vault locks.
    menu.emit({ kind: "details_focus", handle: "h2" });
    await settle();
    state.status = { kind: "locked", unlocking: false };
    pages.statusChanged();
    await settle();
    menu.emit({ kind: "details_focus", handle: "h1" });
    await settle();
    // Not for a login the menu doesn't list.
    menu.emit({ kind: "details_focus", handle: "forged" });
    await settle();
    expect(focusCommands()).toEqual(["menu_details"]);
    expect(cardEvents(card).filter((it) => it === "focus")).toHaveLength(1);
  });

  it("forgets the matches of the old URL at once when a single-page app moved on: nothing copies or fills till the new ones come", async () => {
    const context = setup();
    const { card, menu, sent, state, requests } = await showing("h1", context);
    const other = "https://login.example.com/other";
    state.contentScript = (message) => (message.kind === "guard" ? { ok: true, url: other } : { ok: true });
    // The app answers for the new URL later, or fails.
    let fail!: () => void;
    state.app = (request) =>
      request.type === "suggest" && request.url === other
        ? new Promise((_resolve, reject) => (fail = () => reject(new ProtocolError("vault_locked"))))
        : undefined;
    await copy(card, "copy_username");
    expect(guards(sent)).toEqual(["menu_details"]);
    // The card hides, the menu's page drops its selection, and the menu loads, at once.
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details_hide" });
    expect(cardEvents(card)).toEqual(["bound", "busy", "hidden", "busy"]);
    expect(menu.posted.filter((it) => it.kind === "deselect")).toEqual([{ kind: "deselect" }]);
    expect(menu.views().at(-1)).toEqual({ kind: "loading" });
    // A second click before the matches of the new URL come.
    await copy(card, "copy_username");
    await copyUri(card, 0);
    menu.emit({ kind: "fill", handle: "h1" });
    await settle();
    // After a refresh that fails too.
    fail();
    await settle();
    await select(menu, "h1");
    await copy(card, "copy_username");
    menu.emit({ kind: "fill", handle: "h1" });
    await settle();
    expect(card.posted.filter((it) => it.kind === "error").map((it) => it.error)).toEqual([
      "invalid_request",
      "invalid_request",
      "invalid_request",
    ]);
    expect(menu.posted.filter((it) => it.kind === "error").map((it) => it.error)).toEqual([
      "invalid_request",
      "vault_locked",
      "invalid_request",
    ]);
    expect(guards(sent)).toEqual(["menu_details"]);
    expect(requests.map((it) => it.type)).toEqual(["suggest", "suggest"]);
    expect(state.copies).toEqual([]);
  });

  it("drops the selection on both sides when a single-page app moved on: Space shows the card again, for the new matches", async () => {
    const context = setup();
    const { card, menu, sent, state } = await showing("h1", context);
    const other = "https://login.example.com/other";
    let moved!: () => void;
    state.contentScript = (message) =>
      message.kind === "guard"
        ? new Promise((resolve) => (moved = () => resolve({ ok: true, url: other })))
        : { ok: true };
    // The new page lists the login too.
    state.suggest = [item("h3"), item("h1")];
    menu.emit({ kind: "fill", handle: "h1" });
    await settle();
    // The row moved while the action ran.
    await select(menu, "h1", 60);
    moved();
    await settle();
    // The card hides, and the menu's page drops its selection: the loading view
    // and the new matches may come within one of its frames, which keeps the row.
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details_hide" });
    expect(menu.posted.filter((it) => it.kind === "deselect")).toEqual([{ kind: "deselect" }]);
    expect(menu.views().slice(-2)).toMatchObject([{ kind: "loading" }, { kind: "items" }]);
    // Neither the action's end nor the new matches bring the card back.
    expect(cardViews(card)).toHaveLength(1);
    expect(cardCommands(sent).filter((it) => it.kind === "menu_details")).toHaveLength(1);
    expect(context.fills()).toEqual([]);
    // Space on the login's row of the new page.
    await select(menu, "h1");
    await resize(card, lastSeq(card));
    expect(cardViews(card)).toHaveLength(2);
    expect(cardCommands(sent).at(-1)).toEqual({ kind: "menu_details", top: 40, row: 48, height: 150 });
  });

  it("copies only for the view the card shows: a refresh that reordered the login's URIs refuses a copy of the view before", async () => {
    const context = setup();
    const { card, state } = await showing("h1", context);
    const seq = lastSeq(card);
    state.suggest = [item("h1", { uris: [URIS[1]!, URIS[0]!] }), item("h2")];
    context.pages.statusChanged();
    await settle();
    expect(lastSeq(card)).toBe(seq + 1);
    await copyUri(card, 0, "h1", seq);
    await copy(card, "copy_username", "h1", seq);
    expect(card.posted.filter((it) => it.kind === "error").map((it) => it.error)).toEqual([
      "invalid_request",
      "invalid_request",
    ]);
    expect(state.copies).toEqual([]);
    // The view it shows now copies.
    await copyUri(card, 0);
    expect(state.copies).toEqual([URIS[1]]);
  });

  /** The card copies linked URI [index] of [handle] in view [seq], as [copy]. */
  async function copyUri(card: FakePort, index: unknown, handle = "h1", seq = lastSeq(card)) {
    card.emit({ kind: "copy_uri", handle, index, seq });
    await settle();
  }

  it("copies a linked URI the card lists from the menu's suggest, after the checks for the card, without the app", async () => {
    const { card, menu, sent, requests, state } = await showing();
    const before = sent.length;
    await copyUri(card, 2);
    await copyUri(card, 4);
    // The card and the menu's field, in the menu's document, for each.
    expect(guards(sent)).toEqual(["menu_details", "menu_details"]);
    expect(sent.slice(before).map((it) => it.message.kind)).toEqual(["guard", "guard"]);
    // No app request: a URI is no secret.
    expect(requests.map((it) => it.type)).toEqual(["suggest"]);
    expect(state.copies).toEqual([URIS[2], URIS[4]]);
    expect(cardEvents(card)).toEqual(["bound", "busy", "copied", "busy", "busy", "copied", "busy"]);
    // Nothing closes or hides.
    expect(menu.disconnected).toBe(false);
    expect(card.disconnected).toBe(false);
    expect(sent.map((it) => it.message.kind)).not.toContain("menu_close");
  });

  it("refuses a URI the card doesn't list, and one of a handle it doesn't show", async () => {
    const context = setup();
    const { card, menu, sent, requests, state } = await showing("h1", context);
    // Past the 5 the card lists, of 7 the background has: the page drops them.
    for (const index of [5, 6, -1, 1.5, "1", null, Number.MAX_SAFE_INTEGER + 1]) await copyUri(card, index);
    expect(card.posted.filter((it) => it.kind === "error")).toEqual([]);
    // Shown in the menu, not in the card; or no login at all.
    await copyUri(card, 0, "h2");
    await copyUri(card, 0, "forged");
    // A login without URIs.
    await select(menu, "h2");
    await copyUri(card, 0, "h2");
    // The card hid: nothing to copy.
    menu.emit({ kind: "deselect" });
    await settle();
    await copyUri(card, 0, "h2");
    // The menu's latest suggest has fewer URIs for the login than the card showed.
    await select(menu, "h1");
    context.state.suggest = [item("h1", { uris: URIS.slice(0, 2) }), item("h2")];
    context.pages.statusChanged();
    await settle();
    await copyUri(card, 3);
    expect(card.posted.filter((it) => it.kind === "error").map((it) => it.error)).toEqual([
      "invalid_request",
      "invalid_request",
      "invalid_request",
      "invalid_request",
      "invalid_request",
    ]);
    expect(guards(sent)).toEqual([]);
    expect(requests.map((it) => it.type).filter((type) => type !== "suggest")).toEqual([]);
    expect(state.copies).toEqual([]);
    expect(card.posted.filter((it) => it.kind === "copied")).toEqual([]);
  });

  it("copies no URI when the page covers the card or its field", async () => {
    const context = setup();
    const { card, state } = await showing("h1", context);
    context.state.contentScript = (message) =>
      message.kind === "guard" ? { ok: false, reason: "popup_covered" } : { ok: true };
    await copyUri(card, 0);
    expect(card.posted.filter((it) => it.kind === "error").map((it) => it.error)).toEqual(["popup_covered"]);
    expect(card.posted.filter((it) => it.kind === "copied")).toEqual([]);
    expect(state.copies).toEqual([]);
  });

  it("drops malformed requests of the card", () => {
    for (const request of [
      { kind: "copy", action: "fill", handle: "h1", seq: 1 },
      { kind: "copy", action: "copy_password", seq: 1 },
      { kind: "copy", action: "copy_password", handle: "", seq: 1 },
      // Without the view it's for, or with a malformed one.
      { kind: "copy", action: "copy_password", handle: "h1" },
      { kind: "copy", action: "copy_password", handle: "h1", seq: 1.5 },
      { kind: "copy", action: "copy_password", handle: "h1", seq: "1" },
      { kind: "copy_uri", index: 0, seq: 1 },
      { kind: "copy_uri", handle: "h1", seq: 1 },
      { kind: "copy_uri", handle: "", index: 0, seq: 1 },
      { kind: "copy_uri", handle: "h1", index: -1, seq: 1 },
      { kind: "copy_uri", handle: "h1", index: 0.5, seq: 1 },
      { kind: "copy_uri", handle: "h1", index: "0", seq: 1 },
      { kind: "copy_uri", handle: "h1", index: MAX_DETAIL_URIS, seq: 1 },
      { kind: "copy_uri", handle: "h1", index: Number.NaN, seq: 1 },
      { kind: "copy_uri", handle: "h1", index: 0 },
      { kind: "copy_uri", handle: "h1", index: 0, seq: Number.NaN },
    ]) {
      expect(parseMenuDetailsRequest(request), JSON.stringify(request)).toBeNull();
    }
    expect(parseMenuDetailsRequest({ kind: "copy", action: "copy_totp", handle: "h1", seq: 2, value: "x" })).toEqual({
      kind: "copy",
      action: "copy_totp",
      handle: "h1",
      seq: 2,
    });
    expect(
      parseMenuDetailsRequest({ kind: "copy_uri", handle: "h1", index: MAX_DETAIL_URIS - 1, seq: 2, uri: "x" }),
    ).toEqual({
      kind: "copy_uri",
      handle: "h1",
      index: MAX_DETAIL_URIS - 1,
      seq: 2,
    });
    expect(parseMenuDetailsRequest({ kind: "back", handle: "h1" })).toEqual({ kind: "back" });
    expect(parseMenuRequest({ kind: "details_focus" })).toBeNull();
    expect(parseMenuRequest({ kind: "details_focus", handle: "h1" })).toEqual({ kind: "details_focus", handle: "h1" });
  });

  it("drops malformed selections and sizes", async () => {
    for (const select of [
      { kind: "select", top: 0, height: 48 },
      { kind: "select", handle: "", top: 0, height: 48 },
      { kind: "select", handle: "h".repeat(65), top: 0, height: 48 },
      { kind: "select", handle: "h1", top: Number.NaN, height: 48 },
      { kind: "select", handle: "h1", top: 0, height: "48" },
    ]) {
      expect(parseMenuRequest(select), JSON.stringify(select)).toBeNull();
    }
    expect(parseMenuRequest({ kind: "deselect", handle: "h1" })).toEqual({ kind: "deselect" });
    for (const resize of [
      { kind: "resize", height: 100 },
      { kind: "resize", height: 100, seq: 1.5 },
      { kind: "resize", height: Number.POSITIVE_INFINITY, seq: 1 },
      { kind: "select", handle: "h1", top: 0, height: 48 },
    ]) {
      expect(parseMenuDetailsRequest(resize), JSON.stringify(resize)).toBeNull();
    }

    const { menu, card, sent } = await openWithCard();
    menu.emit({ kind: "select", handle: "h1", top: "40", height: 48 });
    await settle();
    expect(cardViews(card)).toEqual([]);
    await select(menu, "h1");
    card.emit({ kind: "resize", height: "150", seq: 1 });
    card.emit({ kind: "resize", height: 150 });
    await settle();
    expect(cardCommands(sent)).toEqual([]);
  });
});
