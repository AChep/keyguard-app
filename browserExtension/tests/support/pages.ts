// Shared helpers of the tests of the background's side of content scripts and
// embedded pages (../pages.test.ts, ../menuDetails.test.ts): a PageController
// with a fake app and browser around it, and the senders and ports of its
// content scripts and pages.
//
// The tests fake `setTimeout`: see [realSetTimeout].
import { vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import type { ExtensionStatus } from "../../src/lib/background/api";
import { TabWatch, type FrameRef, type MessageSenderLike } from "../../src/lib/background/frames";
import { PageController, type AppAccess, type PageDeps, type PagePort } from "../../src/lib/background/pages";
import { OptionsStore } from "../../src/lib/options/store";
import { MENU_DETAILS_PORT, MENU_PORT, type ContentCommand, type MenuView } from "../../src/lib/page/messages";
import type { JsonObject } from "../../src/lib/protocol/framing";
import type { Capability, SuggestItem } from "../../src/lib/protocol/messages";
import { ProtocolError } from "../../src/lib/protocol/session";
import { abortable } from "./fakeApp";

export const ID = "abcdefghijklmnopabcdefghijklmnop";
export const LOGIN_URL = "https://login.example.com/sign-in";
export const LOGIN_ORIGIN = "https://login.example.com";

/** The site icon the fake browser has for page [url] of tab [tabId], see PageDeps.siteIcon. */
export function iconOf(url: string, tabId = 7): string {
  return `https://icons.example/${tabId}?page=${encodeURIComponent(url)}`;
}

/** How long a probe waits for the frames' answers, on the background's fake clock: see [timeUp]. */
export const PROBE_TIMEOUT_MS = 20;

/**
 * The real `setTimeout`, for the fake browser's messages and [settle]. The
 * background's timers are fake: its probes and waits run out only where a
 * test lets their time pass, however slow the machine.
 */
export const realSetTimeout = globalThis.setTimeout;

/** Lets the messages and promises in flight go through, while the background's clock stands still. */
export async function settle() {
  for (let i = 0; i < 10; i++) await new Promise((resolve) => realSetTimeout(resolve, 0));
}

/** Lets [ms] pass on the background's clock once the frames had their chance to answer: a probe nobody answered runs out. */
export async function timeUp(ms = PROBE_TIMEOUT_MS) {
  await settle();
  await vi.advanceTimersByTimeAsync(ms);
  await settle();
}

/** Awaits [action], letting the time of each probe it still waits on run out once the frames answered, see [timeUp]. */
export async function finish<T>(action: Promise<T>): Promise<T> {
  let pending = true;
  void action.then(
    () => (pending = false),
    () => (pending = false),
  );
  await settle();
  for (let probes = 0; pending && probes < 3; probes++) await timeUp();
  if (pending) throw new Error("Still waiting after the time of 3 probes");
  return action;
}

export function item(handle: string, fields: Partial<SuggestItem> = {}): SuggestItem {
  return {
    handle,
    name: `Item ${handle}`,
    username: "ann",
    account: "Personal",
    has_password: true,
    has_totp: false,
    reprompt: false,
    matched: true,
    ...fields,
  };
}

/** A content script's sender: a frame of a tab, as the browser reports it. */
export function contentSender(fields: Partial<MessageSenderLike> = {}): MessageSenderLike {
  return {
    id: ID,
    tab: { id: 7, url: LOGIN_URL },
    frameId: 3,
    documentId: "doc-3",
    url: LOGIN_URL,
    origin: "https://login.example.com",
    ...fields,
  };
}

/** The token of the content script in the document of [sender]: 32 hex characters, by its `documentId`. */
export function docOf(sender: MessageSenderLike): string {
  const id = sender.documentId ?? `frame-${sender.frameId}`;
  return Array.from(id, (char) => char.charCodeAt(0).toString(16).padStart(2, "0"))
    .join("")
    .padEnd(32, "0")
    .slice(0, 32);
}

/** Sends [request] from the content script in the document of [sender], with its token. */
export function fromContent(
  pages: PageController,
  request: object,
  sender: MessageSenderLike = contentSender(),
): Promise<unknown> {
  return pages.onContentMessage({ ...request, doc: docOf(sender) }, sender);
}

/**
 * A frame with a login form, as its content script answers a `form_probe`.
 * [writable]: false for a form of read-only fields only.
 */
export function form(fields: Partial<MessageSenderLike>, focusedAt: number | null = null, writable = true) {
  return { sender: contentSender(fields), focusedAt, writable };
}

/** The top frame, on LOGIN_URL, with a login form. */
export function topForm(writable = true) {
  return form({ frameId: 0, documentId: "doc-0" }, null, writable);
}

/** An embedded page's sender: our page, in a tab. */
export function pageSender(page: string, tabId = 7): MessageSenderLike {
  return { id: ID, tab: { id: tabId }, frameId: 9, url: `chrome-extension://dynamic-id${page}` };
}

export class FakePort implements PagePort {
  readonly posted: JsonObject[] = [];
  disconnected = false;
  private readonly messageListeners: ((message: unknown) => void)[] = [];
  private readonly disconnectListeners: (() => void)[] = [];
  readonly onMessage = {
    addListener: (listener: (message: unknown) => void) => void this.messageListeners.push(listener),
  };
  readonly onDisconnect = { addListener: (listener: () => void) => void this.disconnectListeners.push(listener) };

  constructor(
    readonly name: string,
    readonly sender: MessageSenderLike,
  ) {}

  postMessage(message: unknown): void {
    if (this.disconnected) throw new Error("disconnected");
    this.posted.push(message as JsonObject);
  }

  disconnect(): void {
    this.disconnected = true;
  }

  /** A message from the page. */
  emit(message: unknown): void {
    for (const listener of this.messageListeners) listener(message);
  }

  close(): void {
    this.disconnected = true;
    for (const listener of this.disconnectListeners) listener();
  }

  views(): MenuView[] {
    return this.posted.filter((it) => it.kind === "view").map((it) => it.view as unknown as MenuView);
  }

  /** The offer of the save bar's last view, which its actions name. */
  offer(): number {
    return this.posted.findLast((it) => it.kind === "view")!.offer as number;
  }

  lastView(): MenuView | undefined {
    return this.views().at(-1);
  }
}

export interface Sent {
  tabId: number;
  message: ContentCommand;
  target?: { frameId: number; documentId?: string };
}

/** The app and the browser around a PageController. */
export function setup(options: { documentIds?: boolean; status?: ExtensionStatus; capabilities?: Capability[] } = {}) {
  let clock = 1_000_000;
  let tokens = 0;
  const sent: Sent[] = [];
  const requests: JsonObject[] = [];
  const state = {
    status: options.status ?? ({ kind: "unlocked" } as ExtensionStatus),
    sessionId: 1 as number | null,
    capabilities: new Set<string>(options.capabilities ?? ["save"]),
    suggest: [item("h1", { has_totp: true }), item("h2", { username: undefined })] as SuggestItem[],
    unlocks: 0,
    popups: 0,
    /** Whether the tab is in a private window, as `tabs.get` tells the popup's calls and the shortcut. */
    incognito: false,
    /** Whether the tab is the selected one of its window, as `tabs.get` tells. */
    active: true,
    injected: [] as number[],
    /** Answers of the content scripts, by command kind. */
    contentScript: (message: ContentCommand, _target?: { frameId: number }): unknown => {
      switch (message.kind) {
        case "guard":
        case "fill":
        case "fill_totp":
        case "fill_password":
          return { ok: true };
        default:
          return undefined;
      }
    },
    /** Answers of the app; `undefined` falls through to the defaults. */
    app: (_request: JsonObject): unknown => undefined,
    /** The frame's pending conditional passkey request, see ConditionalPasskeys. */
    conditional: null as { rpId: string } | null,
    picks: [] as { frame: FrameRef; handle: string }[],
    pickError: null as Error | null,
    /**
     * The frames with a login form, which answer a `form_probe`; `null` when
     * the tab has no content script. By default, the top frame.
     */
    forms: [topForm()] as { sender: MessageSenderLike; focusedAt: number | null; writable: boolean }[] | null,
    /** The content script of the tab's top frame, which answers a `top_probe`; `null` when none answers. */
    top: null as MessageSenderLike | null,
    /** What `unlock` waits for before it unlocks. */
    unlocking: null as Promise<void> | null,
    /** The signals of `unlock` and of requests, by type: an abort sends `cancel`. */
    signals: [] as { type: string; signal: AbortSignal | undefined }[],
    /** The site icon of a page of a tab; throws as `tabs.get` does for a closed tab. */
    siteIcon: (tabId: number, url: string): string | null => iconOf(url, tabId),
    /** What happens while `tabs.get` looks the tab up, the next time only. */
    tabLookup: null as (() => Promise<void>) | null,
    /** What the background wrote to the clipboard, see PageDeps.copyText. */
    copies: [] as string[],
  };

  const app: AppAccess = {
    get snapshot() {
      return state.status;
    },
    get sessionId() {
      return state.sessionId;
    },
    supports: (capability) => state.capabilities.has(capability),
    unlock: async (signal) => {
      state.unlocks++;
      state.signals.push({ type: "unlock", signal });
      if (state.unlocking !== null) await abortable(state.unlocking, signal);
      state.status = { kind: "unlocked" };
      return null;
    },
    request: (async (type: string, fields: JsonObject, signal?: AbortSignal) => {
      const request = { type, ...fields };
      requests.push(request);
      state.signals.push({ type, signal });
      const custom = await abortable(state.app(request), signal);
      if (custom instanceof Error) throw custom;
      if (custom !== undefined) return custom;
      switch (type) {
        case "suggest":
          return { blocked: false, save_blocked: false, truncated: false, items: state.suggest };
        case "fill":
          return { username: "ann", password: `secret-${fields.handle as string}` };
        case "totp":
          return { code: "123456" };
        case "generate":
          return { password: "Gen3rated!" };
        case "save":
        case "never_save":
          return {};
        default:
          throw new ProtocolError("not_supported");
      }
    }) as AppAccess["request"],
  };

  const deps: PageDeps = {
    extensionId: ID,
    app,
    options: new OptionsStore(fakeBrowser.storage.local),
    passkeys: {
      conditionalFor: () => state.conditional,
      pick: async (frame, handle) => {
        state.picks.push({ frame, handle });
        if (state.pickError !== null) throw state.pickError;
      },
    },
    sendToFrame: async (tabId, message, target) => {
      sent.push({ tabId, message, target });
      const top = state.top;
      if (message.kind === "top_probe" && top !== null) {
        realSetTimeout(() => void fromContent(pages, { kind: "top_report", probe: message.probe }, top), 0);
      }
      const answer = state.contentScript(message, target);
      if (answer instanceof Error) throw answer;
      return answer;
    },
    sendToTab: async (tabId, message) => {
      sent.push({ tabId, message });
      if (message.kind === "frame_guard") {
        // The parent that holds the frame reports, as a message of its own.
        const answer = state.contentScript(message);
        if (answer instanceof Error) throw answer;
        if (answer instanceof Object && !(answer instanceof Promise)) {
          const report = { kind: "frame_report", probe: message.probe, ...answer };
          realSetTimeout(() => void fromContent(pages, report, contentSender({ frameId: 1, documentId: "doc-1" })), 0);
        }
        return undefined;
      }
      if (message.kind !== "form_probe") return undefined;
      if (state.forms === null) throw new Error("Could not establish connection. Receiving end does not exist.");
      for (const { sender, focusedAt, writable } of state.forms) {
        const report = { kind: "form_report", probe: message.probe, focused_at: focusedAt, writable };
        realSetTimeout(() => void fromContent(pages, report, sender), 0);
      }
      return null;
    },
    injectContentScript: async (tabId) => {
      state.injected.push(tabId);
      // Into the top frame, which has the page's form.
      state.forms ??= [topForm()];
    },
    tab: async () => {
      const meanwhile = state.tabLookup;
      state.tabLookup = null;
      await meanwhile?.();
      return { url: LOGIN_URL, incognito: state.incognito, active: state.active };
    },
    siteIcon: async (tabId, url) => state.siteIcon(tabId, url),
    tabs: new TabWatch(),
    openPopup: async () => void state.popups++,
    copyText: async (text) => void state.copies.push(text),
    documentIds: options.documentIds ?? true,
    now: () => clock,
    randomToken: () => (++tokens).toString(16).padStart(32, "0"),
    probeTimeoutMs: PROBE_TIMEOUT_MS,
  };
  const pages = new PageController(deps);
  /** The signal of the last request or unlock of [type]. */
  const signalOf = (type: string) => state.signals.findLast((it) => it.type === type)?.signal;

  /**
   * Opens a menu for a field, as the content script of [sender] would, and
   * binds its page. [details]: the token of the card beside it, unbound.
   */
  async function openMenu(role: string, sender = contentSender(), webauthn = false) {
    const result = (await fromContent(pages, { kind: "menu_open", role, webauthn }, sender)) as {
      token: string;
      details: string;
    };
    const port = new FakePort(MENU_PORT, pageSender("/inline-menu.html", sender.tab?.id));
    pages.connect(port);
    port.emit({ kind: "bind", token: result.token });
    await settle();
    return { port, token: result.token, details: result.details };
  }

  /** The page of the card beside a menu binds [token] in tab [tabId]. */
  async function bindDetails(token: string, tabId = 7) {
    const port = new FakePort(MENU_DETAILS_PORT, pageSender("/menu-details.html", tabId));
    pages.connect(port);
    port.emit({ kind: "bind", token });
    await settle();
    return port;
  }

  /** The popup's fill of [handle] on the tab's page, past the time of its probes. */
  const fillFromPopup = (handle: string) => finish(pages.fillTab(7, LOGIN_URL, handle));

  return {
    pages,
    state,
    sent,
    requests,
    openMenu,
    bindDetails,
    fillFromPopup,
    signalOf,
    tabs: deps.tabs,
    options: deps.options,
    advance: (ms: number) => (clock += ms),
    fills: () => sent.filter((it) => it.message.kind.startsWith("fill")),
  };
}
