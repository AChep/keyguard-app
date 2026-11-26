// The background's side of the content scripts and of the pages they embed:
// the inline menu, the save bar, the fill shortcut and the popup's calls about
// a tab's page.
//
// Rules:
// - A frame and its URL come from the browser's sender (./frames.ts).
// - Secrets go to one document with `tabs.sendMessage`, never to the embedded
//   pages: to its frame, with its `documentId` on Chromium and its token
//   (../page/messages.ts) on every browser.
// - An embedded page is bound to the frame that asked for it by a one-time
//   token, which expires in seconds and works for its tab only. The menu's
//   first matches, asked for as the token is issued, wait with the token: only
//   the page it binds gets them.
// - Fills and passkey picks follow a user gesture: a trusted click in the menu (checked by the
//   menu page, then the content script's clickjacking checks, and without
//   IntersectionObserver v2 those of the frame's parents of another origin), a click in the
//   popup, or the keyboard shortcut. Nothing fills on page load, but the
//   password step of a sign-in one of those started, see
//   [PageController.passwordStep]. Save and Never of the save bar, and the
//   copies of the card beside the menu, take the same checks.
// - The card's copies go to the clipboard from here ([PageDeps.copyText]):
//   neither the card's page nor the content script gets the value. A linked
//   URI, which the card shows, goes the same way, after the same checks.
// - What the background remembers of a page, as the login filled last on a
//   tab, is kept by the page's exact origin.
// - A request for a page in a private window says so (`private_window`): the
//   app then stores no browsing data. Like every page, such a page is kept in
//   memory only here.
// - A request whose result can't be used any more gets a `cancel`, and the
//   app's prompt closes: when the menu or the save bar that asked closes (the
//   user dismissed it, or its frame navigated), or for the shortcut and the
//   popup's fill, when the tab closes or leaves the page.
import {
  copiesPassword,
  fillValues,
  matchedFirst,
  privateWindow,
  searchQuery,
  type Capability,
  type FramePage,
  type RequestOf,
  type RequestType,
  type ResponseOf,
  type SuggestItem,
} from "../protocol/messages";
import { isCancelled, ProtocolError } from "../protocol/session";
import type { OptionsStore } from "../options/store";
import {
  MENU_DETAILS_PAGE,
  MENU_DETAILS_PORT,
  MENU_PAGE,
  MENU_PORT,
  SAVE_BAR_PAGE,
  SAVE_BAR_PORT,
  parseContentRequest,
  parseDocumentResult,
  parseMenuDetailsRequest,
  parseMenuRequest,
  parseSaveBarRequest,
  randomToken,
  type ContentCommand,
  type ContentRequest,
  type DocumentCommand,
  type FieldRole,
  type FillTarget,
  type FrameCheck,
  type GuardedPopup,
  type MenuDetailsEvent,
  type MenuDetailsRequest,
  type MenuEvent,
  type MenuItem,
  type MenuPasskey,
  type MenuRequest,
  type MenuView,
  type SaveBarEvent,
  type SaveBarRequest,
  type SaveCandidate,
} from "../page/messages";
import type { CallHandlers, ExtensionStatus, PageCallMethod, PageSearch, PageSuggestions } from "./api";
import { CallFailure, callErrorOf } from "./controller";
import {
  frameOf,
  frameTarget,
  isOwnPage,
  originOf,
  sameDocument,
  sameFrame,
  sameSite,
  webUrl,
  type FrameRef,
  type MessageSenderLike,
  type TabInfo,
  type TabWatch,
} from "./frames";
import { MenuDetailsController, type MenuDetailsLink, type MenuDetailsSession } from "./menuDetails";

/** A token binds an embedded page for this long after the content script asked. */
export const TOKEN_TTL_MS = 10_000;
/** "Fill TOTP" follows a login fill on the same tab for this long. */
export const TOTP_WINDOW_MS = 2 * 60_000;
/** The next step of a sign-in lists the login filled last on the tab first for this long. */
export const CONTINUE_WINDOW_MS = 5 * 60_000;
/**
 * The password step of a sign-in gets the password of a login whose username
 * alone was filled in the tab's top frame for this long, see
 * [PageController.passwordStep].
 */
export const PASSWORD_STEP_WINDOW_MS = 2 * 60_000;
/** A save bar stays offered, across navigations, for this long. */
export const SAVE_TTL_MS = 2 * 60_000;
/** The username of a username-first step counts for its password step for this long. */
export const USERNAME_TTL_MS = 5 * 60_000;
/** Wrong tokens a port may send before it's dropped. */
const MAX_BIND_ATTEMPTS = 4;
/** How many parents of another origin the checks before an action ask, see [PageController.guard]. */
const MAX_PARENT_CHECKS = 8;

/** What the pages need of the app session, see ./controller.ts. */
export interface AppAccess {
  readonly snapshot: ExtensionStatus;
  /** Changes with every session; handles die with theirs. */
  readonly sessionId: number | null;
  supports(capability: Capability): boolean;
  /** When [signal] aborts, the app gets a `cancel` and the request fails with `cancelled`. */
  request<T extends RequestType>(type: T, fields: RequestOf<T>, signal?: AbortSignal): Promise<ResponseOf<T>>;
  /** Asks the app to unlock; resolves once it answered. [signal] stops waiting, as for [request]. */
  unlock(signal?: AbortSignal): Promise<unknown>;
}

/** The pages' view of the conditional passkey requests, see ./passkeys.ts. */
export interface ConditionalPasskeys {
  /** The conditional `get` that a frame's document waits on, with the RP ID to list. */
  conditionalFor(frame: FrameRef): { rpId: string } | null;
  /** Answers it with the passkey the user picked; [signal] cancels the pick. */
  pick(frame: FrameRef, handle: string, signal?: AbortSignal): Promise<void>;
}

/** The parts of `runtime.Port` the controller uses. */
export interface PagePort {
  readonly name: string;
  readonly sender?: MessageSenderLike;
  postMessage(message: unknown): void;
  disconnect(): void;
  onMessage: { addListener(callback: (message: unknown) => void): void };
  onDisconnect: { addListener(callback: () => void): void };
}

export interface PageDeps {
  extensionId: string;
  app: AppAccess;
  options: OptionsStore;
  passkeys: ConditionalPasskeys;
  /** `tabs.sendMessage` to one frame, resolving with its answer. */
  sendToFrame(
    tabId: number,
    message: ContentCommand,
    target: { frameId: number; documentId?: string },
  ): Promise<unknown>;
  /** `tabs.sendMessage` to every frame of a tab; rejects when none has a content script. */
  sendToTab(tabId: number, message: ContentCommand): Promise<unknown>;
  /** Injects the content script into a tab's top frame, as for tabs open before the install. */
  injectContentScript(tabId: number): Promise<void>;
  /** A tab, as `tabs.get` tells; its URL is `undefined` when the extension can't see it. */
  tab(tabId: number): Promise<TabInfo>;
  /**
   * The URL of the icon of the site of [url], a page in tab [tabId], from what
   * the browser reports of the tab, see ./siteIcon.ts; `null` for none.
   */
  siteIcon(tabId: number, url: string): Promise<string | null>;
  /**
   * Cancels the requests of the shortcut and of the popup's fill when their
   * tab closes or leaves the page; tells the popup's lists their tab closed.
   */
  tabs: TabWatch;
  /**
   * `action.openPopup()`. Firefox before 149 opens it only while it handles
   * the user's input: call it before anything awaits.
   */
  openPopup(): Promise<void>;
  /** Writes [text] to the clipboard, for a copy of the card beside the menu, see ./clipboard.ts. */
  copyText(text: string): Promise<void>;
  /** Whether `tabs.sendMessage` takes a `documentId`: Chromium does, Firefox doesn't. */
  documentIds: boolean;
  now?(): number;
  randomToken?(): string;
  /** How long the shortcut waits for the frame with focus to answer, and a fill for the frames with a form. */
  probeTimeoutMs?: number;
  /** How long the inline menu waits for the passkeys of a page's conditional request, see [PASSKEY_WAIT_MS]. */
  passkeyWaitMs?: number;
}

/** A frame's answer to a `form_probe`: it has a login form to fill. */
interface FormReport {
  frame: FrameRef;
  /** When a field there last got focus, by `Date.now()`. */
  focusedAt: number | null;
  /** Whether its form has a username or password that isn't read-only. */
  writable: boolean;
}

/** A content script's answer to a probe. */
type ProbeReport = Extract<ContentRequest, { kind: "focus_report" | "top_report" | "form_report" | "frame_report" }>;

/** A probe in flight, see [PageController.probeFrames]. */
interface Probe {
  tabId: number;
  /** Takes an answer of a frame of its tab; true once the probe has what it waits for. */
  report(frame: FrameRef, report: ProbeReport): boolean;
}

/** What an issued token binds, by its kind: a menu and its card share their [MenuDetailsLink]. */
type TokenKind =
  { kind: "save_bar" } | { kind: "menu"; link: MenuDetailsLink } | { kind: "menu_details"; link: MenuDetailsLink };

/**
 * The child pages of an embedded page, as the card of the inline menu: their
 * tokens are issued with the parent's, and they close with it, see
 * [PageController.end]. The parent's token and session share it with the
 * children's.
 */
export interface ChildPages {
  /** The children's tokens, until their pages bind: they can't once the parent's token or session goes. */
  readonly tokens: Set<string>;
  /** The children's sessions, once bound. */
  readonly sessions: Set<PageSession>;
}

type IssuedToken = TokenKind & TokenFields & { issuedAt: number };

/** An [IssuedToken] of [kind]. */
type TokenOf<K extends IssuedToken["kind"]> = Extract<IssuedToken, { kind: K }>;

interface TokenFields {
  frame: FrameRef;
  role: FieldRole | null;
  /** The field has the `webauthn` autocomplete token. */
  webauthn?: boolean;
  /** A `menu_focus` came before the menu's page bound. */
  focus?: boolean;
  /** The menu's `popup_shown` came before its page bound. */
  shown?: boolean;
  /** The menu's first matches, asked for as the token was issued; see [PageController.prefetchMenu]. */
  prefetch?: MenuPrefetch;
  /** The pages that the page of this token owns. */
  children?: ChildPages;
  /** For the token of a child page: the [ChildPages] of its parent. */
  parent?: ChildPages;
}

/**
 * How long the inline menu waits for the passkeys of a page's conditional
 * request, from when it asks. The app answers at once unless it fetches a
 * related origin's `/.well-known/webauthn`; past this, the menu shows the
 * logins alone, so it never waits on a slow site. Reopening it asks again.
 */
export const PASSKEY_WAIT_MS = 1_000;

/** The requests of a menu refresh, see [PageController.fetchMenu]. */
interface MenuFetch {
  /** The logins of the page; fails as the request does. */
  logins: Promise<ResponseOf<"suggest">>;
  /** The passkeys of the page's conditional request: none once [PASSKEY_WAIT_MS] passed. Never fails. */
  passkeys: Promise<MenuPasskey[]>;
  icon: Promise<string | null>;
}

/** A menu's first [MenuFetch], started with its token: the app answers while the menu's page loads. */
interface MenuPrefetch {
  fetch: MenuFetch;
  /** Cancels its requests: the token expired or was replaced, its tab closed, or the app's state changed. */
  abort: AbortController;
  /** The app session it asked; handles die with theirs. */
  sessionId: number | null;
}

/** What every embedded page's session has, see [PageController.end]. */
export interface EmbeddedSession {
  port: PagePort;
  /** An action of the page runs, see [PageController.act]. */
  busy: boolean;
  /** Aborts when the page closes: its requests in flight get a `cancel`. */
  abort: AbortController;
  /** The pages it owns, from its token: they close with it. */
  children?: ChildPages;
  /** For a child page: the [ChildPages] of its parent, from its token. */
  parent?: ChildPages;
}

/** An inline menu, from its bind until it closes. */
export interface MenuSession extends EmbeddedSession {
  kind: "menu";
  frame: FrameRef;
  role: FieldRole;
  webauthn: boolean;
  /** The items the menu shows, by handle. */
  items: Map<string, SuggestItem>;
  /** The view the menu shows. */
  shown: MenuView;
  /** The card beside it, see ./menuDetails.ts. */
  details: MenuDetailsLink;
  /** The passkeys the menu shows, by handle. */
  passkeys: Map<string, MenuPasskey>;
  /** Counts the refreshes of the menu: only the latest one shows its result. */
  refreshes: number;
}

interface SaveBarSession extends EmbeddedSession {
  kind: "save_bar";
  frame: FrameRef;
}

/** The session of an embedded page, from its bind until it closes. */
export type PageSession = MenuSession | SaveBarSession | MenuDetailsSession;

type PageRequest = MenuRequest | MenuDetailsRequest | SaveBarRequest;

/** What [PageController.connect] does for one kind of embedded page. */
interface EmbeddedPage<S extends PageSession, R extends PageRequest> extends PageHooks<S> {
  /** The page's path, see isOwnPage. */
  path: string;
  parse(message: unknown): R | null;
  /** The session of a page bound by [issued], or `null` when it has nothing to show. */
  bind(port: PagePort, issued: TokenOf<S["kind"]>): S | null;
  handle(session: S, request: R): Promise<void>;
}

/** What one kind of embedded page does as a page of the kind closes or acts. */
interface PageHooks<S extends PageSession> {
  /** Forgets [session], which ends, see [PageController.end]. */
  ended(session: S): void;
  /** Has the content script close the page's frame, for an end that notifies. */
  close?(session: S): void;
  /** Runs before each action of the page, see [PageController.act]. */
  beforeAction?(session: S): void;
  /** Runs after each action of the page that it stays open for. */
  afterAction?(session: S): void;
}

interface PendingSave {
  /** Tells this offer from those that replace it: the bar's actions name the offer they're for. */
  offer: number;
  /** The frame where the login was submitted: its URL is what's saved. */
  frame: FrameRef;
  /** The frame's host, which the bar shows. */
  host: string;
  candidate: SaveCandidate;
  /** The icon of the site, from its tab when the login was submitted, see [PageDeps.siteIcon]. */
  icon: string | null;
  at: number;
}

/** The last login fill on a tab, for the next step of a sign-in and "Fill TOTP". */
interface FillMemory {
  handle: string;
  /** The item filled, when it has a TOTP: what "Fill TOTP" shows. */
  totp: MenuItem | null;
  /** The origin of the page filled: only its pages get the memory. */
  origin: string;
  /**
   * The username of a login with a password that the fill wrote alone in
   * the top frame, on a username step or a sign-in form: the next page there
   * may get the password, for that account, see [PageController.passwordStep].
   * `null` for other fills, and once tried.
   */
  passwordStep: string | null;
  at: number;
  sessionId: number | null;
}

/** An item the popup lists: what its fill and its copy of the password need. */
type ListedItem = MenuItem & Pick<SuggestItem, "has_password" | "password_hidden">;

/** The items the popup lists on a tab, for its fill: those of its last `suggest` and of its last `search`. */
interface Listed {
  suggest: ReadonlyMap<string, ListedItem>;
  search: ReadonlyMap<string, ListedItem>;
}

function newChildPages(): ChildPages {
  return { tokens: new Set(), sessions: new Set() };
}

/** Of a login that a fill remembers, what the menu shows. */
function toMenuItem(item: MenuItem): MenuItem {
  return {
    handle: item.handle,
    name: item.name,
    ...(item.username !== undefined ? { username: item.username } : {}),
    account: item.account,
    has_totp: item.has_totp,
    reprompt: item.reprompt,
    matched: item.matched,
  };
}

/** The passkeys of an item of a `suggest` with `"passkey"`. */
function toMenuPasskeys(item: SuggestItem): MenuPasskey[] {
  return (item.passkeys ?? []).map((passkey) => ({
    handle: passkey.handle,
    user_name: passkey.user_name,
    ...(passkey.user_display_name !== undefined ? { user_display_name: passkey.user_display_name } : {}),
    item: item.name,
    account: item.account,
  }));
}

/** A field of a sign-in step, where the login filled last may continue. */
function isSignInRole(role: FieldRole | null): boolean {
  return role === "username" || role === "current-password";
}

function hostOf(url: string): string {
  return webUrl(url)?.hostname ?? "";
}

/**
 * The URL of [frame] as its content script read it, [reported]: a single-page
 * app changes it without a navigation. [frame]'s own for anything but an
 * http(s) URL, as an about:blank or srcdoc frame's; `null` for another origin.
 */
function currentUrl(frame: FrameRef, reported: string | undefined): string | null {
  const url = webUrl(reported);
  if (url === null) return frame.url;
  return url.origin === frame.origin ? url.href : null;
}

/** Whether two usernames name the same account: as typed, the case and the spaces around may differ. */
function sameAccount(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase();
}

export class PageController {
  private readonly tokens = new Map<string, IssuedToken>();
  private readonly menus = new Set<MenuSession>();
  private readonly saveBars = new Set<SaveBarSession>();
  private readonly fills = new Map<number, FillMemory>();
  private readonly listed = new Map<number, Listed>();
  private readonly saves = new Map<number, PendingSave>();
  /** The username of a username-first step on a tab, by the origin of its page. */
  private readonly usernames = new Map<number, { username: string; origin: string; at: number }>();
  private readonly probes = new Map<string, Probe>();
  /** The popup's `search` in flight, which a newer one cancels. */
  private searching: AbortController | null = null;
  /** The last [PendingSave.offer]. */
  private offers = 0;
  private readonly now: () => number;
  private readonly randomToken: () => string;
  /** The cards beside the menus. */
  private readonly details: MenuDetailsController;

  /** The popup's calls about a tab's page, see ./api.ts. */
  readonly calls: Pick<CallHandlers, PageCallMethod> = {
    suggest: ({ tabId }) => this.suggest(tabId),
    search: ({ tabId, query }) => this.search(tabId, query),
    fill: ({ tabId, handle, origin }) => this.fillPopup(tabId, handle, origin),
    create: ({ tabId, origin }) => this.createPopup(tabId, origin),
  };

  private readonly menuPage: EmbeddedPage<MenuSession, MenuRequest> = {
    path: MENU_PAGE,
    parse: parseMenuRequest,
    bind: (port, issued) => this.bindMenu(port, issued),
    handle: (menu, request) => this.onMenuRequest(menu, request),
    ended: (menu) => {
      this.menus.delete(menu);
      this.details.menuEnded(menu);
    },
    close: (menu) => void this.notify(menu.frame, { kind: "menu_close" }),
    // The card would hide what the action brings up, as the app's prompt.
    beforeAction: (menu) => this.details.hideDetails(menu),
    // The card of the row the menu still selects shows again.
    afterAction: (menu) => this.details.actionEnded(menu),
  };

  private readonly detailsPage: EmbeddedPage<MenuDetailsSession, MenuDetailsRequest> = {
    path: MENU_DETAILS_PAGE,
    parse: parseMenuDetailsRequest,
    bind: (port, issued) => this.details.bindDetails(port, issued.link),
    handle: (card, request) => this.details.onDetailsRequest(card, request),
    ended: (card) => this.details.detailsEnded(card),
  };

  private readonly saveBarPage: EmbeddedPage<SaveBarSession, SaveBarRequest> = {
    path: SAVE_BAR_PAGE,
    parse: parseSaveBarRequest,
    bind: (port, issued) => this.bindSaveBar(port, issued),
    handle: (bar, request) => this.onSaveBarRequest(bar, request),
    ended: (bar) => this.saveBars.delete(bar),
    close: (bar) => void this.notify(bar.frame, { kind: "save_bar_close" }),
  };

  constructor(private readonly deps: PageDeps) {
    this.now = deps.now ?? Date.now;
    this.randomToken = deps.randomToken ?? randomToken;
    this.details = new MenuDetailsController({
      post: (port, event) => this.post(port, event),
      notify: (frame, command) => this.notify(frame, command),
      hasFocus: (frame) => this.hasFocus(frame),
      focusPopup: (frame, popup) => this.focusPopup(frame, popup),
      guardMenu: (menu, popup) => this.guardMenu(menu, popup),
      act: (card, action) => this.act(card, action),
      closeMenu: (menu) => this.end(menu, { notify: true }),
      pageOf: (frame) => this.pageOf(frame),
      app: deps.app,
      copyText: (text) => deps.copyText(text),
    });
  }

  /**
   * Whether the user is in the middle of something here, which a reload of
   * the extension would cut: an inline menu or a save bar open or about to
   * bind, a save offered, a probe, or a fill or list of the shortcut or the
   * popup.
   */
  get busy(): boolean {
    const now = this.now();
    return (
      this.menus.size > 0 ||
      this.saveBars.size > 0 ||
      this.saves.size > 0 ||
      this.probes.size > 0 ||
      this.deps.tabs.busy ||
      [...this.tokens.values()].some((issued) => this.live(issued, now))
    );
  }

  /**
   * Whether a blocked URI of the user blocks saving on [url], as the app's
   * `suggest` says. `false` when the app can't tell, as with the vault
   * locked: it checks again when the user saves.
   */
  private async isSaveBlocked(frame: FrameRef): Promise<boolean> {
    try {
      // No kinds: the app matches no logins, and answers with the blocks only.
      return (
        await this.deps.app.request("suggest", { url: frame.url, kinds: [], ...privateWindow(frame.privateWindow) })
      ).save_blocked;
    } catch {
      return false;
    }
  }

  /** The icon of the site of [page], a page of its tab, see [PageDeps.siteIcon]; `null` on any failure. */
  private async siteIcon(page: Pick<FrameRef, "tabId" | "url">): Promise<string | null> {
    try {
      return await this.deps.siteIcon(page.tabId, page.url);
    } catch {
      // The tab closed, or the extension can't see it.
      return null;
    }
  }

  // region Content scripts

  /** A `runtime.onMessage` from a content script; resolves with its answer. */
  async onContentMessage(message: unknown, sender: MessageSenderLike | undefined): Promise<unknown> {
    const request = parseContentRequest(message);
    if (request === null) return null;
    const frame = frameOf(sender, this.deps.extensionId, request.doc);
    if (frame === null) return request.kind === "menu_open" ? { error: "unsupported_page" } : null;
    switch (request.kind) {
      case "menu_open": {
        if (!(await this.deps.options.options()).inlineMenu) return { error: "not_allowed" };
        // One menu per frame: a new one replaces the old, with its child pages.
        for (const menu of this.menus) if (sameFrame(menu.frame, frame)) this.end(menu, { notify: false });
        for (const [key, issued] of this.tokens)
          if (issued.kind === "menu" && sameFrame(issued.frame, frame)) this.drop(key);
        // For the page the user is on, see [currentUrl].
        const page = { ...frame, url: currentUrl(frame, request.url) ?? frame.url };
        const prefetch = this.prefetchMenu(page, request.webauthn);
        // The card beside the menu is its child page.
        const link = this.details.newLink();
        const children = newChildPages();
        const { role, webauthn } = request;
        return {
          token: this.issue({ kind: "menu", frame: page, role, webauthn, prefetch, link, children }),
          details: this.issue({ kind: "menu_details", frame: page, role: null, link, parent: children }),
        };
      }
      case "save_candidate":
        await this.offerSave(frame, request.candidate);
        return null;
      case "username_step":
        this.usernames.set(frame.tabId, { username: request.username, origin: frame.origin, at: this.now() });
        return null;
      case "password_step":
        // For the page the user is on, as for menu_open.
        await this.passwordStep(
          { ...frame, url: currentUrl(frame, request.url) ?? frame.url },
          request.step,
          request.account,
        );
        return null;
      case "save_bar_open":
        if (frame.frameId !== 0 || this.pendingSave(frame.tabId) === null) return null;
        return { token: this.issue({ kind: "save_bar", frame, role: null }) };
      case "menu_focus":
        // Only the menu of the sender's document; one still binding gets it once bound.
        for (const menu of this.menus) if (sameDocument(menu.frame, frame)) this.post(menu.port, { kind: "focus" });
        for (const issued of this.tokens.values())
          if (issued.kind === "menu" && sameDocument(issued.frame, frame)) issued.focus = true;
        return null;
      case "popup_shown":
        // As for menu_focus: the popup of the sender's document.
        if (request.popup === "menu_details") {
          for (const menu of this.menus) if (sameDocument(menu.frame, frame)) this.details.detailsShown(menu, request);
          return null;
        }
        // The menu's click gate starts now: the frame was hidden till then.
        for (const menu of this.menus) if (sameDocument(menu.frame, frame)) this.post(menu.port, { kind: "shown" });
        for (const issued of this.tokens.values())
          if (issued.kind === "menu" && sameDocument(issued.frame, frame)) issued.shown = true;
        return null;
      case "menu_blur":
        for (const menu of this.menus) if (sameDocument(menu.frame, frame)) this.details.dropSelection(menu);
        return null;
      case "focus_report":
      case "top_report":
      case "form_report":
      case "frame_report": {
        const probe = this.probes.get(request.probe);
        if (probe?.tabId === frame.tabId) probe.report(frame, request);
        return null;
      }
    }
  }

  private issue(token: TokenKind & TokenFields): string {
    const now = this.now();
    for (const [key, issued] of this.tokens) if (!this.live(issued, now)) this.drop(key);
    const key = this.randomToken();
    const issued: IssuedToken = { ...token, issuedAt: now };
    this.tokens.set(key, issued);
    issued.parent?.tokens.add(key);
    if (issued.prefetch !== undefined) {
      // A token nobody takes keeps neither requests nor matches past its time.
      setTimeout(() => {
        if (this.tokens.get(key) === issued) this.dropPrefetch(issued);
      }, TOKEN_TTL_MS);
    }
    return key;
  }

  /** Takes a token once: for its kind and tab, and before it expires. */
  private take<K extends IssuedToken["kind"]>(key: string, kind: K, tabId: number | undefined): TokenOf<K> | null {
    const issued = this.tokens.get(key);
    if (issued === undefined || issued.kind !== kind || issued.frame.tabId !== tabId) return null;
    if (!this.live(issued)) {
      this.drop(key);
      return null;
    }
    this.tokens.delete(key);
    issued.parent?.tokens.delete(key);
    return issued as TokenOf<K>;
  }

  /** Whether [issued] has not expired yet. */
  private live(issued: IssuedToken, now = this.now()): boolean {
    return now - issued.issuedAt <= TOKEN_TTL_MS;
  }

  /**
   * Forgets a token: its menu's early requests stop, and its child pages
   * close, see [closeChildren]: its page never binds now.
   */
  private drop(key: string) {
    const issued = this.tokens.get(key);
    if (issued === undefined) return;
    this.tokens.delete(key);
    issued.parent?.tokens.delete(key);
    this.dropPrefetch(issued);
    if (issued.children !== undefined) this.closeChildren(issued.children);
  }

  /** Cancels the early requests of a token's menu, which then asks again once bound. */
  private dropPrefetch(issued: IssuedToken) {
    issued.prefetch?.abort.abort();
    issued.prefetch = undefined;
  }

  // endregion

  // region Embedded pages

  /** A `runtime.onConnect` port of an embedded page. */
  connect(port: PagePort): void {
    if (port.name === MENU_PORT) this.serve(port, "menu", this.menuPage);
    else if (port.name === MENU_DETAILS_PORT) this.serve(port, "menu_details", this.detailsPage);
    else if (port.name === SAVE_BAR_PORT) this.serve(port, "save_bar", this.saveBarPage);
    else port.disconnect();
  }

  /** Binds [port] with a token of [kind], then hands the page's requests to its session. */
  private serve<S extends PageSession, R extends PageRequest>(
    port: PagePort,
    kind: S["kind"],
    page: EmbeddedPage<S, R>,
  ) {
    // Only our own page, in a tab: the token then ties it to a frame of that tab.
    if (!isOwnPage(port.sender, this.deps.extensionId, page.path) || port.sender?.tab?.id === undefined) {
      port.disconnect();
      return;
    }
    let session: S | null = null;
    let attempts = 0;
    port.onMessage.addListener((raw) => {
      const request = page.parse(raw);
      if (request === null) return;
      if (session !== null) {
        void page.handle(session, request);
        return;
      }
      const bind: PageRequest = request;
      if (bind.kind !== "bind") return;
      const issued = this.take(bind.token, kind, port.sender?.tab?.id);
      if (issued === null) {
        // The page may post tokens of its own to the frame; only ours bind.
        if (++attempts >= MAX_BIND_ATTEMPTS) port.disconnect();
        return;
      }
      session = page.bind(port, issued);
      if (session === null) return;
      // It owns the child pages of its token, or joins those of its parent.
      session.children = issued.children;
      session.parent = issued.parent;
      issued.parent?.sessions.add(session);
    });
    port.onDisconnect.addListener(() => {
      if (session !== null) this.end(session, { notify: false });
    });
  }

  /** The app's status changed: menus show the new state, and those still binding ask again for another session or state. */
  statusChanged(): void {
    const { snapshot, sessionId } = this.deps.app;
    for (const issued of this.tokens.values()) {
      if (snapshot.kind !== "unlocked" || issued.prefetch?.sessionId !== sessionId) this.dropPrefetch(issued);
    }
    for (const menu of this.menus) void this.refreshMenu(menu);
  }

  /** A frame's conditional passkey request came or went: its `webauthn` menus list passkeys or stop. */
  conditionalChanged(frame: FrameRef): void {
    for (const issued of this.tokens.values()) {
      if (issued.kind === "menu" && issued.webauthn === true && sameFrame(issued.frame, frame))
        this.dropPrefetch(issued);
    }
    for (const menu of this.menus) if (menu.webauthn && sameFrame(menu.frame, frame)) void this.refreshMenu(menu);
  }

  /** A tab closed: forget it. */
  tabRemoved(tabId: number): void {
    this.fills.delete(tabId);
    this.listed.delete(tabId);
    this.saves.delete(tabId);
    this.usernames.delete(tabId);
    for (const [key, issued] of this.tokens) if (issued.frame.tabId === tabId) this.drop(key);
    for (const menu of this.menus) if (menu.frame.tabId === tabId) this.end(menu, { notify: false });
    for (const bar of this.saveBars) if (bar.frame.tabId === tabId) this.end(bar, { notify: false });
  }

  private post(port: PagePort, event: MenuEvent | MenuDetailsEvent | SaveBarEvent) {
    try {
      port.postMessage(event);
    } catch {
      // The page closed.
    }
  }

  /** The [PageHooks] of [kind]. */
  private hooks(kind: PageSession["kind"]): PageHooks<PageSession> {
    switch (kind) {
      case "menu":
        return this.menuPage;
      case "menu_details":
        return this.detailsPage;
      case "save_bar":
        return this.saveBarPage;
    }
  }

  /**
   * Ends a page's session, and those of its child pages first: nothing waits
   * for their answers any more, and their requests in flight get a `cancel`.
   * With [notify], the content script closes the page's frame, and its
   * children's with it.
   */
  private end(session: PageSession, { notify }: { notify: boolean }) {
    const page = this.hooks(session.kind);
    page.ended(session);
    if (session.children !== undefined) this.closeChildren(session.children);
    session.parent?.sessions.delete(session);
    session.abort.abort();
    if (notify) page.close?.(session);
    this.disconnect(session.port);
  }

  /** The parent of [children] closed or was replaced: their tokens can't bind any more, and their pages close. */
  private closeChildren(children: ChildPages) {
    for (const key of children.tokens) this.drop(key);
    for (const child of children.sessions) this.end(child, { notify: false });
  }

  private disconnect(port: PagePort) {
    try {
      port.disconnect();
    } catch {
      // Already closed.
    }
  }

  // endregion

  // region Inline menu

  private bindMenu(port: PagePort, issued: TokenOf<"menu">): MenuSession {
    const menu: MenuSession = {
      kind: "menu",
      port,
      frame: issued.frame,
      role: issued.role ?? "username",
      webauthn: issued.webauthn === true,
      items: new Map(),
      shown: { kind: "loading" },
      details: issued.link,
      passkeys: new Map(),
      busy: false,
      refreshes: 0,
      abort: new AbortController(),
    };
    this.details.menuBound(menu);
    this.menus.add(menu);
    this.post(port, { kind: "bound" });
    if (issued.focus === true) this.post(port, { kind: "focus" });
    if (issued.shown === true) this.post(port, { kind: "shown" });
    // The early requests are the menu's now: closing it cancels them.
    const prefetch = issued.prefetch;
    issued.prefetch = undefined;
    if (prefetch !== undefined)
      menu.abort.signal.addEventListener("abort", () => prefetch.abort.abort(), { once: true });
    void this.refreshMenu(menu, prefetch);
    return menu;
  }

  /** Shows [view] in the menu, then in the card beside it, see [MenuDetailsController.viewShown]. */
  private showMenu(menu: MenuSession, view: MenuView) {
    if (menu.abort.signal.aborted) return;
    menu.shown = view;
    this.post(menu.port, { kind: "view", view });
    this.details.viewShown(menu, view);
  }

  /**
   * Shows the app's status, or the matches for the menu's page: the logins and
   * the passkeys in one view, so no row moves once it shows. A failure or a
   * blocked page shows at once; the passkeys get [PASSKEY_WAIT_MS] at most.
   * Refreshes may finish out of order: an older one, still waiting for the
   * app, shows nothing. Closing the menu cancels its requests. The first
   * refresh takes the requests [prefetch] started with the token, unless the
   * app's state changed since.
   */
  private async refreshMenu(menu: MenuSession, prefetch?: MenuPrefetch) {
    const refresh = ++menu.refreshes;
    const latest = () => refresh === menu.refreshes && !menu.abort.signal.aborted;
    const status = this.deps.app.snapshot;
    const early =
      prefetch !== undefined &&
      status.kind === "unlocked" &&
      prefetch.sessionId === this.deps.app.sessionId &&
      !prefetch.abort.signal.aborted
        ? prefetch.fetch
        : null;
    if (early === null) prefetch?.abort.abort();
    if (status.kind === "locked") {
      this.showMenu(menu, { kind: "locked", unlocking: status.unlocking });
      return;
    }
    if (status.kind !== "unlocked") {
      this.showMenu(menu, { kind: "status", status });
      return;
    }
    if (menu.items.size === 0 && menu.passkeys.size === 0) this.showMenu(menu, { kind: "loading" });
    const host = hostOf(menu.frame.url);
    const {
      logins,
      passkeys,
      icon: iconRequest,
    } = early ?? this.fetchMenu(menu.frame, menu.webauthn, menu.abort.signal);
    let response: ResponseOf<"suggest">;
    let icon: string | null;
    try {
      [response, icon] = await Promise.all([logins, iconRequest]);
    } catch (error) {
      if (latest()) {
        console.warn("Keyguard: the menu's suggest failed", error);
        this.post(menu.port, { kind: "error", error: callErrorOf(error) });
      }
      return;
    }
    if (!latest()) return;
    if (response.blocked) {
      menu.items.clear();
      menu.passkeys.clear();
      this.showMenu(menu, { kind: "blocked", host });
      return;
    }
    const keys = await passkeys;
    if (!latest()) return;
    const matches = menu.role === "otp" ? response.items.filter((item) => item.has_totp) : response.items;
    const { items, last } = isSignInRole(menu.role)
      ? this.lastFirst(menu.frame.tabId, menu.frame.url, matches)
      : { items: matches, last: null };
    const ordered = matchedFirst(items);
    menu.items = new Map(ordered.map((item) => [item.handle, item]));
    menu.passkeys = new Map(keys.map((passkey) => [passkey.handle, passkey]));
    const remembered = menu.role === "otp" ? this.rememberedTotp(menu.frame) : null;
    this.showMenu(menu, {
      kind: "items",
      role: menu.role,
      host,
      items: ordered.map(toMenuItem),
      last,
      truncated: response.truncated,
      totp: remembered === null ? null : toMenuItem(remembered),
      passkeys: keys,
      icon,
      create: this.canCreate(response),
    });
  }

  /** Starts the requests of a menu refresh for [frame]: the logins, the passkeys and the site's icon, at once. */
  private fetchMenu(frame: FrameRef, webauthn: boolean, signal: AbortSignal): MenuFetch {
    const passkeys = this.suggestPasskeys(frame, webauthn, signal);
    const icon = this.siteIcon(frame);
    const logins = this.deps.app.request("suggest", { url: frame.url, ...privateWindow(frame.privateWindow) }, signal);
    return { logins, passkeys, icon };
  }

  /**
   * Starts the first refresh of the menu of [frame] as its token is issued, so
   * the app answers while the menu's page loads. Only with the vault
   * unlocked: the menu shows any other state at once.
   */
  private prefetchMenu(frame: FrameRef, webauthn: boolean): MenuPrefetch | undefined {
    if (this.deps.app.snapshot.kind !== "unlocked") return undefined;
    const abort = new AbortController();
    const fetch = this.fetchMenu(frame, webauthn, abort.signal);
    // A token nobody takes leaves its failure unread.
    fetch.logins.catch(() => undefined);
    return { fetch, abort, sessionId: this.deps.app.sessionId };
  }

  /**
   * The passkeys for the page's conditional request, on `webauthn` fields only.
   * A `suggest` of its own: with `"passkey"` alone, the app lists just the
   * logins with passkeys for the RP ID, which may not match the page and so
   * aren't offered for filling. An RP ID the app refuses lists none. Past
   * [PASSKEY_WAIT_MS], the request is cancelled and lists none.
   */
  private async suggestPasskeys(frame: FrameRef, webauthn: boolean, signal: AbortSignal): Promise<MenuPasskey[]> {
    const conditional =
      webauthn && this.deps.app.supports("passkeys") ? this.deps.passkeys.conditionalFor(frame) : null;
    if (conditional === null) return [];
    const abort = new AbortController();
    const cancel = () => abort.abort();
    signal.addEventListener("abort", cancel, { once: true });
    const timer = setTimeout(cancel, this.deps.passkeyWaitMs ?? PASSKEY_WAIT_MS);
    try {
      const response = await this.deps.app.request(
        "suggest",
        { url: frame.url, kinds: ["passkey"], rp_id: conditional.rpId, ...privateWindow(frame.privateWindow) },
        abort.signal,
      );
      return response.blocked ? [] : response.items.flatMap(toMenuPasskeys);
    } catch {
      return [];
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", cancel);
    }
  }

  private async onMenuRequest(menu: MenuSession, request: MenuRequest) {
    const { frame } = menu;
    switch (request.kind) {
      case "bind":
        return;
      case "fill": {
        const item = menu.items.get(request.handle);
        if (item === undefined) return this.post(menu.port, { kind: "error", error: "invalid_request" });
        return this.act(menu, async (signal) => {
          if (!(await this.guardMenu(menu))) return;
          await this.fillLogin(frame, item.handle, item, "menu", signal);
        });
      }
      case "totp": {
        const item = menu.items.get(request.handle) ?? this.rememberedTotp(frame) ?? undefined;
        if (item === undefined || item.handle !== request.handle || !item.has_totp) {
          return this.post(menu.port, { kind: "error", error: "invalid_request" });
        }
        return this.act(menu, async (signal) => {
          if (!(await this.guardMenu(menu))) return;
          await this.fillTotp(frame, item.handle, "menu", signal);
        });
      }
      case "passkey": {
        const passkey = menu.passkeys.get(request.handle);
        if (passkey === undefined) return this.post(menu.port, { kind: "error", error: "invalid_request" });
        return this.act(menu, async (signal) => {
          // As for a fill: a single-page app that moved to another URL gets its matches anew instead.
          if (!(await this.guardMenu(menu))) return;
          // The credential goes to the page through its passkey port, not this
          // menu: the page's request, not the menu, waits for it. Closing the
          // menu cancels the pick.
          await this.deps.passkeys.pick(frame, passkey.handle, signal);
          this.end(menu, { notify: true });
        });
      }
      case "generate":
        if (menu.role !== "new-password") return this.post(menu.port, { kind: "error", error: "invalid_request" });
        return this.act(menu, async (signal) => {
          await this.guard(frame);
          const { password } = await this.deps.app.request(
            "generate",
            { url: frame.url, ...privateWindow(frame.privateWindow) },
            signal,
          );
          await this.fill(frame, { kind: "fill_password", target: "menu", password });
        });
      case "create":
        // Offered for the page the menu shows.
        if (menu.shown.kind !== "items" || !menu.shown.create) {
          return this.post(menu.port, { kind: "error", error: "invalid_request" });
        }
        return this.act(menu, async (signal) => {
          if (!(await this.guardMenu(menu))) return;
          await this.createLogin(menu.frame.url, menu.frame.privateWindow, signal);
          // The app's window takes over.
          this.end(menu, { notify: true });
        });
      case "unlock":
        return this.act(menu, async (signal) => {
          await this.deps.app.unlock(signal);
        });
      case "close":
        this.end(menu, { notify: true });
        return;
      case "resize":
        // The content script keeps the menu's frame within its bounds.
        await this.notify(frame, { kind: "menu_resize", height: request.height });
        return;
      case "select":
        this.details.selectDetails(menu, request);
        return;
      case "deselect":
        this.details.deselectDetails(menu);
        return;
      case "details_focus":
        await this.details.focusDetails(menu, request.handle);
        return;
      case "blurred":
        await this.details.blurred(menu);
        return;
    }
  }

  /**
   * Runs one action of an embedded page at a time, reporting its progress and
   * errors. The action's signal aborts when the page closes.
   */
  private async act(session: PageSession, action: (signal: AbortSignal) => Promise<void>) {
    if (session.busy) return;
    session.busy = true;
    const page = this.hooks(session.kind);
    page.beforeAction?.(session);
    this.post(session.port, { kind: "busy", busy: true });
    const signal = session.abort.signal;
    try {
      await action(signal);
    } catch (error) {
      // A cancelled request is no failure: the page closed.
      if (!signal.aborted && !isCancelled(error)) {
        console.warn(`Keyguard: an action in the ${session.kind} failed`, error);
        this.post(session.port, { kind: "error", error: callErrorOf(error) });
      }
    } finally {
      session.busy = false;
      if (!signal.aborted) {
        this.post(session.port, { kind: "busy", busy: false });
        page.afterAction?.(session);
      }
    }
  }

  /**
   * The content script's clickjacking checks for the menu and its field, or
   * for the save bar, then those of each parent of another origin that it
   * names, see [parentShows]. Resolves with the frame's URL now, as its
   * content script reads it: an about:blank or srcdoc frame, whose own URL
   * isn't the page's, keeps [frame]'s.
   */
  private async guard(frame: FrameRef, popup: GuardedPopup = "menu"): Promise<string> {
    let answer: unknown;
    try {
      answer = await this.send(frame, { kind: "guard", popup });
    } catch {
      // The frame navigated or closed since, as for [fill].
      throw new CallFailure("origin");
    }
    // A missing or malformed answer: as from a document that's gone.
    const result = parseDocumentResult(answer, "origin");
    if (!result.ok) throw new CallFailure(result.reason);
    const url = currentUrl(frame, result.url);
    // The document answers for its origin only, see ../page/messages.ts.
    if (url === null) throw new CallFailure("origin");
    let parent = result.parent;
    for (let checks = 0; parent !== undefined; checks++) {
      if (checks === MAX_PARENT_CHECKS) throw new CallFailure("frame_unchecked");
      parent = await this.parentShows(frame.tabId, parent);
    }
    return url;
  }

  /**
   * Asks the content script of the parent of frame [check.frame], of another
   * origin, whether it shows the frame where the popup is; resolves with the
   * next parent of another origin to ask, if any. Without an answer in time,
   * as from a parent without a content script or of an opaque origin, the
   * action is refused: the popup and the shortcut still fill.
   */
  private async parentShows(tabId: number, check: FrameCheck): Promise<FrameCheck | undefined> {
    let answer = null as Extract<ProbeReport, { kind: "frame_report" }> | null;
    await this.probeFrames(
      tabId,
      // Frames that don't hold it don't answer, which may fail the send.
      (probe) => this.deps.sendToTab(tabId, { kind: "frame_guard", probe, ...check }).catch(() => undefined),
      (_frame, report) => {
        if (report.kind !== "frame_report") return false;
        answer = report;
        return true;
      },
    );
    if (answer === null) throw new CallFailure("frame_unchecked");
    if (!answer.ok) throw new CallFailure(answer.reason);
    return answer.parent;
  }

  /**
   * The checks before a fill from the menu, or a copy from the card beside
   * it, [popup]. A single-page app may have moved to another URL since the
   * menu opened, as one a blocked URI covers: the menu forgets the matches of
   * the old URL at once, and its selection, so the card hides; then it shows
   * the matches for the new one, and Space shows a card again. Nothing fills
   * or copies till they come, nor after a refresh that fails. False then.
   */
  private async guardMenu(menu: MenuSession, popup: GuardedPopup = "menu"): Promise<boolean> {
    const url = await this.guard(menu.frame, popup);
    if (url === menu.frame.url) return true;
    menu.frame = { ...menu.frame, url };
    // The passkeys too: the old URL's `suggest` listed them, past its blocked URIs.
    menu.items.clear();
    menu.passkeys.clear();
    this.details.dropSelection(menu);
    void this.refreshMenu(menu);
    return false;
  }

  /** Has the content script of [frame] move focus into the frame of [popup], the menu or its card; whether it did. */
  private async focusPopup(frame: FrameRef, popup: "menu" | "menu_details"): Promise<boolean> {
    try {
      return parseDocumentResult(await this.send(frame, { kind: "focus_popup", popup }), "popup_covered").ok;
    } catch {
      return false;
    }
  }

  /** Whether the document of [frame] still has focus, as its content script tells; false when it's gone. */
  private async hasFocus(frame: FrameRef): Promise<boolean> {
    try {
      return parseDocumentResult(await this.send(frame, { kind: "has_focus" }), "popup_covered").ok;
    } catch {
      return false;
    }
  }

  // endregion

  // region Fills

  /** Sends [command] to the document of [frame], with its token and origin: another document refuses it. */
  private send(frame: FrameRef, command: DocumentCommand): Promise<unknown> {
    if (frame.doc === undefined) return Promise.reject(new CallFailure("origin"));
    return this.deps.sendToFrame(
      frame.tabId,
      { ...command, doc: frame.doc, origin: frame.origin },
      frameTarget(frame, this.deps.documentIds),
    );
  }

  /**
   * Sends values to the one document they're for. Resolves with whether a
   * `fill` wrote the username alone of a sign-in, see [DocumentResult].
   */
  private async fill(
    frame: FrameRef,
    command: Extract<DocumentCommand, { kind: "fill" | "fill_totp" | "fill_password" }>,
  ): Promise<boolean> {
    let answer: unknown;
    try {
      answer = await this.send(frame, command);
    } catch {
      // The frame navigated or closed since.
      throw new CallFailure("origin");
    }
    // `null`: the content script's fill threw, and it logged why.
    if (answer === null) throw new CallFailure("unknown");
    const result = parseDocumentResult(answer, "no_form");
    if (!result.ok) throw new CallFailure(result.reason);
    return result.passwordStep === true;
  }

  /** Sends a command that has no answer, ignoring a document that's gone. */
  private async notify(frame: FrameRef, command: DocumentCommand) {
    await this.send(frame, command).catch(() => undefined);
  }

  /**
   * The page of a `fill` or `totp` for [frame]: its URL, and the origin of the
   * tab's top frame when the frame is embedded in another origin's page, for
   * the app's prompt. Both from the browser's senders: when the tab's URL
   * doesn't tell, the top frame's answer to a probe does. Refuses, before
   * anything is released, a frame whose top frame doesn't answer.
   */
  private async pageOf(frame: FrameRef): Promise<FramePage> {
    let top = frame.topOrigin;
    if (top === null) {
      top = await this.topOrigin(frame.tabId);
      if (top === null) throw new CallFailure("unsupported_page");
    }
    return {
      url: frame.url,
      ...(top !== undefined && top !== frame.origin ? { top_origin: top } : {}),
      ...privateWindow(frame.privateWindow),
    };
  }

  /**
   * A login fill into [frame]: the app releases [handle] for the frame's page
   * (see [pageOf], which may refuse first), its document gets the values, and
   * the tab remembers the fill, with [item] for "Fill TOTP". See
   * [submitsAfterFill] for the sign-in that may follow.
   */
  private async fillLogin(
    frame: FrameRef,
    handle: string,
    item: MenuItem | undefined,
    target: FillTarget,
    signal?: AbortSignal,
  ) {
    const page = await this.pageOf(frame);
    const values = fillValues(await this.deps.app.request("fill", { handle, ...page }, signal));
    const submit = await this.submitsAfterFill(frame);
    const usernameAlone = await this.fill(frame, { kind: "fill", target, ...values, ...(submit ? { submit } : {}) });
    // The username alone of a login with a password, in the top frame, as on a username-first step.
    const passwordStep =
      frame.frameId === 0 && values.password !== undefined && usernameAlone ? (values.username ?? null) : null;
    this.rememberFill(frame, handle, item, passwordStep);
  }

  /**
   * Whether a login fill of [frame] asks its document to sign in after it:
   * with the option on, in the tab the user is on. Never in a background
   * tab, where nobody sees what it submits. One-time code and generated
   * password fills never do.
   */
  private async submitsAfterFill(frame: FrameRef): Promise<boolean> {
    // Not when a read fails: a tab that closed fails the fill anyway.
    return (await this.activeTabWith(frame, "submitAfterFill")) !== null;
  }

  /** The tab of [frame] when [option] is on and it's the active tab, read in that order; `null` else, or when a read fails. */
  private async activeTabWith(
    frame: FrameRef,
    option: "fillPasswordStep" | "submitAfterFill",
  ): Promise<TabInfo | null> {
    try {
      if (!(await this.deps.options.options())[option]) return null;
      const tab = await this.deps.tab(frame.tabId);
      return tab.active === true ? tab : null;
    } catch {
      // The options can't be read, or the tab closed.
      return null;
    }
  }

  /** A one-time code fill into [frame], as for [fillLogin]. */
  private async fillTotp(frame: FrameRef, handle: string, target: FillTarget, signal?: AbortSignal) {
    const page = await this.pageOf(frame);
    const { code } = await this.deps.app.request("totp", { handle, ...page }, signal);
    await this.fill(frame, { kind: "fill_totp", target, code });
  }

  /**
   * The popup's fill: picks the frame to fill for the tab's URL [url] (see
   * [formFrame]), then fills its best login form with [handle], an item the
   * popup listed. A tab with nothing to fill releases nothing.
   */
  async fillTab(tabId: number, url: string, handle: string, signal?: AbortSignal): Promise<void> {
    const frame = await this.formFrame(tabId, url);
    const listed = this.listed.get(tabId);
    await this.fillLogin(frame, handle, listed?.suggest.get(handle) ?? listed?.search.get(handle), "page", signal);
  }

  /**
   * The frame of a tab with a login form: the top frame if it has one, else
   * the one focused last, as an embedded sign-in page. Frames whose form
   * has a writable username or password go first: a read-only one may be an
   * account the page only shows. Only frames on the site of [url], which the
   * popup suggested for, loosely: a pre-filter only, as the app then checks
   * the frame's URL against the handle's site. Injects the content script once
   * if the tab has none.
   */
  private async formFrame(tabId: number, url: string): Promise<FrameRef> {
    let reports = await this.probeForms(tabId);
    if (reports === null) {
      try {
        await this.deps.injectContentScript(tabId);
      } catch {
        throw new CallFailure("unsupported_page");
      }
      reports = await this.probeForms(tabId);
      if (reports === null) throw new CallFailure("unsupported_page");
    }
    const [best] = reports
      .filter((report) => sameSite(report.frame.url, url))
      .sort(
        (a, b) =>
          Number(b.writable) - Number(a.writable) ||
          Number(b.frame.frameId === 0) - Number(a.frame.frameId === 0) ||
          (b.focusedAt ?? -1) - (a.focusedAt ?? -1),
      );
    if (best === undefined) throw new CallFailure("no_form");
    return best.frame;
  }

  /**
   * The fill shortcut: a fresh `suggest` for the frame with focus, then its
   * best match into the focused form: on a username or password field, the
   * login used last, see [lastFirst]. On a one-time-code field it fills the
   * code of the login filled on this tab within TOTP_WINDOW_MS. Its prompts
   * close when the tab closes or leaves the page.
   *
   * Without a vault to fill from, as when Keyguard isn't paired or running,
   * it opens the popup instead: at once, before anything awaits, so call it
   * from the command's handler. A locked vault asks to unlock first.
   */
  async fillCommand(tabId: number): Promise<void> {
    const status = this.deps.app.snapshot.kind;
    if (status !== "locked" && status !== "unlocked") {
      await this.deps.openPopup().catch(() => undefined);
      return;
    }
    const tab = await this.deps.tab(tabId);
    await this.deps.tabs.run(tabId, tab.url, (signal) => this.fillFocused(tabId, tab, signal));
  }

  private async fillFocused(tabId: number, tab: TabInfo, signal: AbortSignal): Promise<void> {
    const app = this.deps.app;
    if (app.snapshot.kind === "locked") await app.unlock(signal);
    if (app.snapshot.kind !== "unlocked") return;
    const report = await this.probe(tabId);
    if (report === null) {
      // No content script has focus, as in a tab opened before the install:
      // the best login form of the tab, as for the popup.
      const url = tab.url;
      if (url === undefined || webUrl(url) === null) return;
      const best = await this.bestItem(url, tab.incognito, signal);
      if (best === null) return;
      await this.fillLogin(await this.formFrame(tabId, url), best.handle, best, "page", signal);
      return;
    }
    const { frame, role } = report;
    const remembered = role === "otp" ? this.rememberedTotp(frame) : null;
    if (remembered !== null) {
      await this.fillTotp(frame, remembered.handle, "focused", signal);
      return;
    }
    const best = await this.bestItem(frame.url, frame.privateWindow, signal, isSignInRole(role) ? tabId : undefined);
    if (best === null) return;
    await this.fillLogin(frame, best.handle, best, "focused", signal);
  }

  /**
   * The match for [url], a page in a private window or not, to fill: on a
   * sign-in step of tab [tabId], the login used last, see [lastFirst], then
   * one with a password, then the first. Matched logins first: a suggested
   * one only when none matches, and the app prompts for it.
   */
  private async bestItem(
    url: string,
    isPrivate: boolean | undefined,
    signal: AbortSignal,
    tabId?: number,
  ): Promise<SuggestItem | null> {
    const response = await this.deps.app.request("suggest", { url, ...privateWindow(isPrivate) }, signal);
    if (response.blocked) return null;
    const last = tabId === undefined ? null : this.lastFirst(tabId, url, response.items).last;
    const matched = response.items.filter((item) => item.matched);
    const items = matched.length > 0 ? matched : response.items;
    return items.find((item) => item.handle === last) ?? items.find((item) => item.has_password) ?? items[0] ?? null;
  }

  /** Asks every frame of a tab which one has focus. */
  private async probe(tabId: number): Promise<{ frame: FrameRef; role: FieldRole | null } | null> {
    let focused = null as { frame: FrameRef; role: FieldRole | null } | null;
    await this.probeFrames(
      tabId,
      // Frames without focus don't answer, which may fail the send.
      (probe) => this.deps.sendToTab(tabId, { kind: "focus_probe", probe }).catch(() => undefined),
      (frame, report) => {
        if (report.kind !== "focus_report") return false;
        focused = { frame, role: report.role };
        return true;
      },
    );
    return focused;
  }

  /**
   * Asks the top frame of a tab for its origin, which its answer's sender
   * tells as [frameOf] does: an about:blank page has its opener's. `null`
   * when no answer comes in time: no content script, an opaque or data:
   * page, or an about: page whose origin the browser doesn't report.
   */
  private async topOrigin(tabId: number): Promise<string | null> {
    let origin = null as string | null;
    await this.probeFrames(
      tabId,
      // The answer comes as a message of its own, see [frameOf].
      (probe) => this.deps.sendToFrame(tabId, { kind: "top_probe", probe }, { frameId: 0 }).catch(() => undefined),
      (frame, report) => {
        // Only the tab's top frame; its origin is its sender's, see frameOf.
        if (report.kind !== "top_report" || frame.frameId !== 0) return false;
        origin = frame.origin;
        return true;
      },
    );
    return origin;
  }

  /**
   * Asks every frame of a tab for a login form. Resolves with the frames that
   * have one once the top frame answers with a writable form or the time is
   * up; `null` when no frame has a content script.
   */
  private async probeForms(tabId: number): Promise<FormReport[] | null> {
    const reports: FormReport[] = [];
    const sent = await this.probeFrames(
      tabId,
      (probe) => this.deps.sendToTab(tabId, { kind: "form_probe", probe }),
      (frame, report) => {
        if (report.kind !== "form_report") return false;
        reports.push({ frame, focusedAt: report.focused_at, writable: report.writable });
        return frame.frameId === 0 && report.writable;
      },
    );
    // As in a tab opened before the install.
    return sent ? reports : null;
  }

  /**
   * Sends a probe with [send], then hands the answers of the frames of tab
   * [tabId] to [report] until it returns true or the time is up. False, at
   * once, when [send] fails.
   */
  private probeFrames(
    tabId: number,
    send: (probe: string) => Promise<unknown>,
    report: Probe["report"],
  ): Promise<boolean> {
    const probe = this.randomToken();
    return new Promise((resolve) => {
      const done = (sent: boolean) => {
        clearTimeout(timer);
        this.probes.delete(probe);
        resolve(sent);
      };
      const timer = setTimeout(() => done(true), this.deps.probeTimeoutMs ?? 300);
      this.probes.set(probe, {
        tabId,
        report: (frame, answer) => {
          if (!report(frame, answer)) return false;
          done(true);
          return true;
        },
      });
      send(probe).catch(() => done(false));
    });
  }

  /**
   * Each login fill replaces the tab's memory: the next step of a sign-in and
   * "Fill TOTP" are for the account filled last. [item] is the item filled,
   * where the caller listed it. [passwordStep]: see [FillMemory.passwordStep].
   */
  private rememberFill(frame: FrameRef, handle: string, item: MenuItem | undefined, passwordStep: string | null) {
    this.fills.set(frame.tabId, {
      handle,
      totp: item?.has_totp === true ? toMenuItem(item) : null,
      origin: frame.origin,
      passwordStep,
      at: this.now(),
      sessionId: this.deps.app.sessionId,
    });
  }

  /**
   * The login filled last on this tab within [windowMs], for a page of [url]
   * of its origin: another origin, even of the same site as the app sees it,
   * gets nothing. Forgotten with its session, whose handles died, or after
   * the longest window.
   */
  private lastFill(tabId: number, url: string, windowMs: number): FillMemory | null {
    const memory = this.fills.get(tabId);
    if (memory === undefined) return null;
    const age = this.now() - memory.at;
    if (
      memory.sessionId !== this.deps.app.sessionId ||
      age > Math.max(TOTP_WINDOW_MS, CONTINUE_WINDOW_MS, PASSWORD_STEP_WINDOW_MS)
    ) {
      this.fills.delete(tabId);
      return null;
    }
    return age <= windowMs && memory.origin === originOf(url) ? memory : null;
  }

  /**
   * [items], the matches for [url], with the login used last first, and its
   * handle. That's the login filled on the tab within CONTINUE_WINDOW_MS, as
   * on the next step of a sign-in, or else the app's `last_used` item, used
   * last on this device. The tab's own fill wins: two tabs may sign in to two
   * accounts of a site. Only reorders: one that [items] lacks isn't added.
   */
  lastFirst<T extends Pick<SuggestItem, "handle" | "last_used">>(
    tabId: number,
    url: string,
    items: readonly T[],
  ): { items: T[]; last: string | null } {
    const handle = this.lastFill(tabId, url, CONTINUE_WINDOW_MS)?.handle;
    const last = items.find((item) => item.handle === handle) ?? items.find((item) => item.last_used === true);
    if (last === undefined) return { items: [...items], last: null };
    return { items: [last, ...items.filter((item) => item !== last)], last: last.handle };
  }

  /** The item of a login fill on this tab within TOTP_WINDOW_MS, for "Fill TOTP": only one with a TOTP. */
  private rememberedTotp(frame: FrameRef): MenuItem | null {
    return this.lastFill(frame.tabId, frame.url, TOTP_WINDOW_MS)?.totp ?? null;
  }

  /**
   * A `password_step` of [frame], whose field its document bound to
   * [token]: fills the password of the login whose username alone a fill
   * wrote there, without a click. Only when all hold:
   * - the option is on;
   * - [frame] is the tab's top frame: a frame's page may be laid out by
   *   another site's above it;
   * - that fill was in the top frame, on the same origin, in this app
   *   session, within PASSWORD_STEP_WINDOW_MS;
   * - the step shows no account, [account], or that fill's;
   * - the vault is unlocked: nothing prompts to unlock;
   * - the tab is the active one.
   * Each still holds once the app released the password, just before it's
   * sent.
   * One try per username fill: a page that asks again, as after a wrong
   * password, gets nothing. Its failures stay here: nobody asked for it.
   */
  private async passwordStep(frame: FrameRef, token: string, account: string | undefined): Promise<void> {
    if (this.passwordStepFill(frame, account) === null) return;
    try {
      const tab = await this.activeTabWith(frame, "fillPasswordStep");
      if (tab === null) return;
      // Checked again, and taken, with no wait in between: two requests never both fill.
      const memory = this.passwordStepFill(frame, account);
      if (memory === null || this.deps.app.snapshot.kind !== "unlocked") return;
      memory.passwordStep = null;
      // As the shortcut's fill: the tab closing or leaving the origin cancels it.
      await this.deps.tabs.run(frame.tabId, tab.url, (signal) => this.fillStep(frame, memory, token, signal));
    } catch {
      // The tab closed, the user declined, or the page changed.
    }
  }

  /**
   * The fill of the password step [token] of [frame] with the password of
   * [memory]'s login, as for [fillLogin]: the document fills the field it
   * reported, if [stepHolds] once the app released the password.
   */
  private async fillStep(frame: FrameRef, memory: FillMemory, token: string, signal: AbortSignal) {
    const page = await this.pageOf(frame);
    const { password } = await this.deps.app.request("fill", { handle: memory.handle, ...page }, signal);
    const submit = await this.submitsAfterFill(frame);
    if (!(await this.stepHolds(frame, memory))) return;
    await this.fill(frame, {
      kind: "fill",
      target: "step",
      step: token,
      ...fillValues({ password }),
      ...(submit ? { submit } : {}),
    });
    this.rememberFill(frame, memory.handle, memory.totp ?? undefined, null);
  }

  /**
   * Whether the fill of the password step of [frame] for [memory] may still
   * go: while the app asked, the user may have moved to another tab, locked
   * the vault or turned the option off, or another fill replaced this one.
   * The reads go first: nothing waits between the checks and the send.
   */
  private async stepHolds(frame: FrameRef, memory: FillMemory): Promise<boolean> {
    return (
      (await this.activeTabWith(frame, "fillPasswordStep")) !== null &&
      this.deps.app.snapshot.kind === "unlocked" &&
      this.lastFill(frame.tabId, frame.url, PASSWORD_STEP_WINDOW_MS) === memory
    );
  }

  /** The tab's memory of a username fill whose password step [frame], showing [account], may be, see [passwordStep]. */
  private passwordStepFill(frame: FrameRef, account: string | undefined): FillMemory | null {
    if (frame.frameId !== 0) return null;
    const memory = this.lastFill(frame.tabId, frame.url, PASSWORD_STEP_WINDOW_MS);
    const username = memory?.passwordStep ?? null;
    return username !== null && (account === undefined || sameAccount(account, username)) ? memory : null;
  }

  // endregion

  // region The popup

  /**
   * The popup's matches for a tab's page: the matched logins, then the
   * suggested ones, each with its login used last first; see [lastFirst].
   */
  private async suggest(tabId: number): Promise<PageSuggestions> {
    const info = await this.deps.tab(tabId);
    const url = webUrl(info.url);
    if (url === null) return { kind: "unsupported" };
    // The tab may close while the app answers, see [list].
    return this.deps.tabs.run(tabId, undefined, async (tab): Promise<PageSuggestions> => {
      const iconRequest = this.siteIcon({ tabId, url: url.href });
      const response = await this.deps.app.request("suggest", { url: url.href, ...privateWindow(info.incognito) });
      if (response.blocked) return { kind: "blocked", host: url.hostname };
      this.list(tabId, "suggest", response.items, tab);
      const { items, last } = this.lastFirst(tabId, url.href, response.items);
      return {
        kind: "items",
        origin: url.origin,
        host: url.hostname,
        truncated: response.truncated,
        items: matchedFirst(items),
        last,
        icon: await iconRequest,
        create: this.canCreate(response),
      };
    });
  }

  /** The popup's search of the vault for a tab's page. A newer search cancels this one; a short query gets no items. */
  private async search(tabId: number, text: string): Promise<PageSearch> {
    this.searching?.abort();
    const abort = new AbortController();
    this.searching = abort;
    try {
      const query = searchQuery(text);
      const info = await this.deps.tab(tabId);
      const url = webUrl(info.url);
      if (url === null) return { kind: "unsupported" };
      if (query === null) return { kind: "items", origin: url.origin, truncated: false, items: [] };
      return await this.deps.tabs.run(tabId, undefined, async (tab): Promise<PageSearch> => {
        const response = await this.deps.app.request(
          "search",
          { query, url: url.href, ...privateWindow(info.incognito) },
          abort.signal,
        );
        if (response.blocked) return { kind: "blocked", host: url.hostname };
        this.list(tabId, "search", response.items, tab);
        return { kind: "items", origin: url.origin, truncated: response.truncated, items: response.items };
      });
    } finally {
      if (this.searching === abort) this.searching = null;
    }
  }

  /**
   * The popup lists [items] on a tab: they replace those of its last call of
   * the kind. Not once [tab] aborts: the tab closed while the app answered,
   * and nothing would forget them.
   */
  private list(tabId: number, call: keyof Listed, items: readonly ListedItem[], tab: AbortSignal) {
    if (tab.aborted) return;
    const listed = this.listed.get(tabId) ?? { suggest: new Map(), search: new Map() };
    this.listed.set(tabId, { ...listed, [call]: new Map(items.map((item) => [item.handle, item])) });
  }

  /**
   * Whether the popup may copy the password of [handle] on a tab: an item it
   * listed there whose collections don't hide the password. The app releases
   * that password to a fill all the same, so the copy is refused here.
   */
  mayCopyPassword(tabId: number, handle: string): boolean {
    const listed = this.listed.get(tabId);
    const item = listed?.suggest.get(handle) ?? listed?.search.get(handle);
    return item !== undefined && copiesPassword(item);
  }

  /**
   * The popup's fill, see [fillTab], for the page of [origin] it listed the
   * item for: a tab that went on to another origin since fails with `origin`.
   * The popup closes as the app prompts: the tab is what still waits.
   */
  private async fillPopup(tabId: number, handle: string, origin: unknown): Promise<null> {
    const { url } = await this.popupPage(tabId, origin);
    await this.deps.tabs.run(tabId, url.href, (signal) => this.fillTab(tabId, url.href, handle, signal));
    return null;
  }

  /** The popup's "New login", for the page of [origin] it listed the logins for, as [fillPopup]. */
  private async createPopup(tabId: number, origin: unknown): Promise<null> {
    const { url, incognito } = await this.popupPage(tabId, origin);
    await this.deps.tabs.run(tabId, url.href, (signal) => this.createLogin(url.href, incognito, signal));
    return null;
  }

  /** The tab's page for an action of the popup, which listed the page of [origin]: another one fails with `origin`. */
  private async popupPage(tabId: number, origin: unknown): Promise<{ url: URL; incognito: boolean | undefined }> {
    const info = await this.deps.tab(tabId);
    const url = webUrl(info.url);
    if (url === null) throw new CallFailure("unsupported_page");
    if (url.origin !== origin) throw new CallFailure("origin");
    return { url, incognito: info.incognito };
  }

  // endregion

  // region Save

  private async offerSave(frame: FrameRef, candidate: SaveCandidate) {
    const url = webUrl(frame.url);
    if (url === null || !this.deps.app.supports("save")) return;
    if (!(await this.deps.options.options()).savePrompts) return;
    // From the tab of the submitted page: the bar may show on the page that follows.
    const [blocked, icon] = await Promise.all([this.isSaveBlocked(frame), this.siteIcon(frame)]);
    // Saving is blocked there, as after "Never for this site": the app would refuse this one.
    if (blocked) return;
    // The username step of the same origin only.
    const recent = this.usernames.get(frame.tabId);
    const username =
      candidate.username ??
      (recent !== undefined && this.now() - recent.at <= USERNAME_TTL_MS && recent.origin === frame.origin
        ? recent.username
        : undefined);
    const save: PendingSave = {
      offer: ++this.offers,
      frame,
      host: url.hostname,
      candidate: username === undefined ? candidate : { ...candidate, username },
      icon,
      at: this.now(),
    };
    this.saves.set(frame.tabId, save);
    // An offer nobody takes doesn't keep the password in memory, nor its bar
    // on the page: a later Save would only close it. A bar that saves goes on.
    setTimeout(() => {
      if (this.saves.get(frame.tabId) !== save) return;
      this.saves.delete(frame.tabId);
      for (const bar of this.saveBars)
        if (bar.frame.tabId === frame.tabId && !bar.busy) this.end(bar, { notify: true });
    }, SAVE_TTL_MS);
    let shown = false;
    for (const bar of this.saveBars) {
      if (bar.frame.tabId !== frame.tabId) continue;
      this.showSaveBar(bar, save);
      shown = true;
    }
    // A bar that shows takes the newer offer: a new frame would replace it,
    // and cancel its Save or Never. When its page goes, as on a navigation,
    // the page that follows asks for the offer as it loads.
    if (shown) return;
    // The top frame shows the bar: now, or after the navigation that follows.
    await this.deps.sendToFrame(frame.tabId, { kind: "save_bar_show" }, { frameId: 0 }).catch(() => undefined);
  }

  private pendingSave(tabId: number): PendingSave | null {
    const save = this.saves.get(tabId);
    if (save === undefined) return null;
    if (this.now() - save.at > SAVE_TTL_MS) {
      this.saves.delete(tabId);
      return null;
    }
    return save;
  }

  private bindSaveBar(port: PagePort, issued: IssuedToken): SaveBarSession | null {
    const save = this.pendingSave(issued.frame.tabId);
    if (save === null) {
      port.disconnect();
      return null;
    }
    // One bar per tab: a newer one replaces the old.
    for (const bar of this.saveBars) if (bar.frame.tabId === issued.frame.tabId) this.end(bar, { notify: true });
    const bar: SaveBarSession = {
      kind: "save_bar",
      port,
      frame: issued.frame,
      busy: false,
      abort: new AbortController(),
    };
    this.saveBars.add(bar);
    this.post(port, { kind: "bound" });
    this.showSaveBar(bar, save);
    return bar;
  }

  private showSaveBar(bar: SaveBarSession, save: PendingSave) {
    this.post(bar.port, {
      kind: "view",
      offer: save.offer,
      host: save.host,
      username: save.candidate.username ?? null,
      icon: save.icon,
    });
  }

  private async onSaveBarRequest(bar: SaveBarSession, request: SaveBarRequest) {
    const tabId = bar.frame.tabId;
    const save = this.pendingSave(tabId);
    if (request.kind === "bind") return;
    if (request.kind === "resize") {
      // Layout only: an offer that expired meanwhile ends on the next action, as before.
      // The content script keeps the bar's frame within its bounds.
      await this.notify(bar.frame, { kind: "save_bar_resize", height: request.height });
      return;
    }
    // Save or Never runs, and the offer may expire meanwhile: the bar takes no
    // other action, not even an Escape's dismiss, which would cancel it.
    if (bar.busy) return;
    if (save === null) {
      this.end(bar, { notify: true });
      return;
    }
    if (request.offer !== save.offer) {
      // A newer offer replaced the one the user acted on: the action is
      // dropped, and the bar shows the newer offer again.
      this.showSaveBar(bar, save);
      return;
    }
    switch (request.kind) {
      case "save":
        // A bar that closes first, as on a navigation, cancels: the offer stays
        // for the next page.
        await this.act(bar, async (signal) => {
          await this.guard(bar.frame, "save_bar");
          const { username, password, previous_password: previous } = save.candidate;
          const fields: RequestOf<"save"> = {
            url: save.frame.url,
            ...(username !== undefined ? { username } : {}),
            password,
            ...(previous !== undefined ? { previous_password: previous } : {}),
            ...privateWindow(save.frame.privateWindow),
          };
          await this.requestUnlocked("save", fields, signal);
          if (this.saves.get(tabId) === save) this.saves.delete(tabId);
          // A newer offer, submitted meanwhile, stays on the bar, which shows it since it came.
          if (this.pendingSave(tabId) === null) this.end(bar, { notify: true });
        });
        break;
      case "dismiss":
        this.saves.delete(tabId);
        this.end(bar, { notify: true });
        return;
      case "never":
        // The app adds a blocked URI of saving only for the site, which every
        // later offer's check sees. A bar that closes first cancels, as for save.
        await this.act(bar, async (signal) => {
          await this.guard(bar.frame, "save_bar");
          await this.requestUnlocked(
            "never_save",
            { url: save.frame.url, ...privateWindow(save.frame.privateWindow) },
            signal,
          );
          if (this.saves.get(tabId) === save) this.saves.delete(tabId);
          this.end(bar, { notify: true });
        });
        break;
    }
    // Save or Never failed after its offer expired: the bar's buttons would only
    // close it. A newer offer stays on the bar, as above.
    if (this.saveBars.has(bar) && this.pendingSave(tabId) === null) this.end(bar, { notify: true });
  }

  /**
   * Whether "New login" shows for the page of a `suggest` [response]: the app
   * refuses a `save` where saving is blocked.
   */
  private canCreate(response: ResponseOf<"suggest">): boolean {
    return this.deps.app.supports("save") && !response.save_blocked;
  }

  /**
   * "New login" of the popup or the inline menu: a `save` with the page's URL
   * alone, for which the app opens its Save login window with a new login.
   */
  private async createLogin(url: string, isPrivate: boolean | undefined, signal: AbortSignal) {
    await this.requestUnlocked("save", { url, ...privateWindow(isPrivate) }, signal);
  }

  /** A request that needs the vault unlocked: when the vault locked meanwhile, it unlocks and asks again. */
  private async requestUnlocked<T extends RequestType>(
    type: T,
    fields: RequestOf<T>,
    signal: AbortSignal,
  ): Promise<ResponseOf<T>> {
    try {
      return await this.deps.app.request(type, fields, signal);
    } catch (error) {
      if (!(error instanceof ProtocolError) || error.code !== "vault_locked") throw error;
      await this.deps.app.unlock(signal);
      return this.deps.app.request(type, fields, signal);
    }
  }

  // endregion
}
