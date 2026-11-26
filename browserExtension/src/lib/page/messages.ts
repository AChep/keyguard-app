// The messages between the background, the content scripts and the extension
// pages that content scripts embed (the inline menu and the save bar).
//
// - Content script → background: `runtime.sendMessage`, one [ContentMessage].
//   The background takes the frame and its URL from the sender, never from the
//   message.
// - Background → content script: `tabs.sendMessage` to one frame (and, on
//   Chromium, one document), one [ContentCommand]. Secrets only travel this way.
// - Each content script makes a random token for its document at start, kept
//   in its isolated world. Its messages carry it, and so do the background's
//   commands for that document: a document refuses another's. On Firefox,
//   which has no `documentId`, this keeps a command from the next document of
//   a frame that navigated within its origin.
// - Inline menu / its details card / save bar ⇄ background: a `runtime.Port`
//   per embedded page, bound to the frame that embedded it by a one-time token.
//   These pages never get secrets, only names, usernames and the details the
//   card shows. The content script gets no details: only the card's place and size.
//   The card copies a login's fields and URIs: the background writes the clipboard.
import type { CallError, ExtensionStatus } from "../background/api";
import type { SearchItem, SuggestItem } from "../protocol/messages";
import { isCopyAction, popupActions, type CopyAction } from "../ui/popupKeys";
import { randomBytes, toHex } from "../util/bytes";
import { isObject } from "../util/json";

/** The role of a field in its form, see ../content/detect.ts. */
export type FieldRole = "username" | "current-password" | "new-password" | "otp";

/** Which form a fill goes to, in the frame that gets it. */
export type FillTarget =
  /** The form of the field that opened the inline menu. */
  | "menu"
  /** The form of the focused field, for the keyboard shortcut. */
  | "focused"
  /** The best login form of the document, for the popup. */
  | "page"
  /** The field of the `password_step` that the command names by its token: its password only. */
  | "step";

/** What a login form submission would save, see ../content/save.ts. */
export interface SaveCandidate {
  username?: string;
  password: string;
  previous_password?: string;
}

export type ContentRequest =
  /**
   * A user clicked the inline icon of a field. `webauthn`: the field has that
   * `autocomplete` token, so the menu lists the passkeys of a pending
   * conditional request. `url`: the frame's URL now, which a single-page app
   * may have changed since it loaded; the background takes it for the
   * sender's origin only. Answers [MenuOpenResult].
   */
  | { kind: "menu_open"; role: FieldRole; webauthn: boolean; url?: string }
  /** A form with a typed password was submitted. Answers `null`. */
  | { kind: "save_candidate"; candidate: SaveCandidate }
  /** A username-only step was submitted, for the password step that follows. Answers `null`. */
  | { kind: "username_step"; username: string }
  /**
   * A form of the top frame asks for the password of a sign-in whose
   * username Keyguard may have filled on the step before, see
   * ../content/fill.ts `isPasswordStep`. The background may fill it, as
   * target `step`. `step`: a new token per report, which the fill names: the
   * document fills the field it reported, once. `account`: the username the
   * form shows, if any. `url`: as for `menu_open`. Answers `null`.
   */
  | { kind: "password_step"; step: string; account?: string; url?: string }
  /** The top frame asks whether to show the save bar. Answers [UiToken] or `null`. */
  | { kind: "save_bar_open" }
  /**
   * A trusted ArrowDown moved focus from a field into the frame of its menu:
   * the menu's page focuses its first row. Answers `null`.
   */
  | { kind: "menu_focus" }
  /** The frame of an embedded popup shows now, or hides, see [PopupShown]. Answers `null`. */
  | ({ kind: "popup_shown" } & PopupShown)
  /**
   * Focus left the menu and the card beside it for the page, which keeps the
   * menu, as for its field: the card hides. Answers `null`.
   */
  | { kind: "menu_blur" }
  /** The answer of a frame with focus to a `focus_probe`. Answers `null`. */
  | { kind: "focus_report"; probe: string; role: FieldRole | null }
  /**
   * The answer of a frame with a login form to a `form_probe`. `focused_at`:
   * when a field with a role last got focus there, by `Date.now()`.
   * `writable`: whether the form's username or password isn't read-only, as
   * an account the page only shows. Answers `null`.
   */
  | { kind: "form_report"; probe: string; focused_at: number | null; writable: boolean }
  /** The top frame's answer to a `top_probe`: the background reads its origin from the sender. Answers `null`. */
  | { kind: "top_report"; probe: string }
  /**
   * The answer to a `frame_guard` of the document that holds the frame: as a
   * [DocumentResult] of a `guard`, without a URL. Answers `null`.
   */
  | ({ kind: "frame_report"; probe: string } & (
      { ok: true; parent?: FrameCheck } | { ok: false; reason: FillRefusal }
    ));

/** A [ContentRequest] as sent, with the token of the sender's document. */
export type ContentMessage = ContentRequest & { doc: string };

/**
 * The side of the menu that the card beside it shows on, on the screen: the
 * arrow keys toward it move focus into it, and those away from it back.
 */
export type DetailsSide = "left" | "right";

/**
 * What a content script reports of the frame of an embedded popup, with
 * `popup_shown`: its page's click gate starts, or waits again.
 * - `menu`: the menu's frame shows now; it stays hidden until its page tells
 *   its height. It shows once, and never hides while the menu is open.
 * - `menu_details`: the card beside the menu shows now, or hides. [side]:
 *   where it shows, while it does, and sent again when it moves to the other
 *   side. [room]: `false` for a card that fits on neither side of the menu,
 *   which drops the menu's selection; not for one hidden for a while, as with
 *   its row scrolled out of the menu.
 */
export type PopupShown =
  { popup: "menu"; shown: true } | { popup: "menu_details"; shown: boolean; side?: DetailsSide; room?: false };

/** `token` binds the menu's page; `details`, the page of the card beside it, see [MenuDetailsRequest]. */
export type MenuOpenResult = { token: string; details: string } | { error: CallError };
export interface UiToken {
  token: string;
}

/** The popups whose actions the content script checks first, see [DocumentCommand] `guard`. */
export type GuardedPopup = "menu" | "menu_details" | "save_bar";

/** Why a frame didn't fill, or refused a click in a popup. */
export type FillRefusal =
  /** No form for the fill. */
  | "no_form"
  /** The form has no field for the values: no new-password field, or none for the login's values. */
  | "nothing_to_fill"
  /** A one-time code fill's form has no one-time code field. */
  | "no_otp_field"
  /** The fill's field is disabled, or stays read-only once it has focus. */
  | "not_editable"
  /** The menu's field, or the fill's, is no longer in the document. */
  | "field_gone"
  /** The page opts the field out of password managers, as with `data-1p-ignore`, on it or its form. */
  | "field_opted_out"
  /** The page doesn't show the field: not rendered, `visibility: hidden`, transparent, or under 4x4 px. */
  | "field_hidden"
  /** The field's center is outside the viewport. */
  | "field_off_screen"
  /** Another element is on top at the field's center. */
  | "field_covered"
  /**
   * The menu, the card beside it or the save bar isn't on top, or is
   * transparent; or a top-layer element of the page is open, or was shortly
   * before.
   */
  | "popup_covered"
  /** An ancestor frame hides, covers or transforms the frame, see ../content/guards.ts `frameExposure`. */
  | "frame_hidden"
  /**
   * An ancestor frame couldn't be checked: the browser doesn't name the frame,
   * a parent of another origin didn't answer, or there are too many of them.
   */
  | "frame_unchecked"
  /** The frame shows another origin than the one the values are for. */
  | "origin";

/**
 * A command for one document. It goes with the document's token and origin,
 * see [ContentCommand]: another document, or one of another origin, refuses
 * it.
 */
export type DocumentCommand =
  /**
   * Fills a login. Answers a [DocumentResult]. `step`: for target `step`, the
   * token of its `password_step`. `submit`: then signs in, see
   * ../content/submit.ts.
   */
  | { kind: "fill"; target: FillTarget; step?: string; username?: string; password?: string; submit?: boolean }
  /** Fills a one-time password. Answers a [DocumentResult]. */
  | { kind: "fill_totp"; target: FillTarget; code: string }
  /** Fills a generated password into the new-password fields. Answers a [DocumentResult]. */
  | { kind: "fill_password"; target: FillTarget; password: string }
  /**
   * Checks, before acting on a click in a popup, that the page shows it: the
   * menu and its field, the card beside the menu and the menu's field, or the
   * save bar. Answers a [DocumentResult] with the frame's URL now.
   */
  | { kind: "guard"; popup: GuardedPopup }
  | { kind: "menu_resize"; height: number }
  | { kind: "menu_close" }
  /**
   * Shows the card of the menu's selected login beside it: [top] and [row],
   * the row's top and height in the menu's frame; [height], the height of the
   * card's content.
   */
  | { kind: "menu_details"; top: number; row: number; height: number }
  | { kind: "menu_details_hide" }
  /**
   * Moves focus between the frames of the menu and of the card beside it,
   * into [popup]'s: a trusted key in the other asked for it. Into the card
   * only while it shows. Answers a [DocumentResult]: `ok` once [popup]'s
   * frame has focus. Its page then focuses a button on the background's
   * word only.
   */
  | { kind: "focus_popup"; popup: "menu" | "menu_details" }
  /**
   * Whether the document has focus, once focus settled: also while it's in
   * the frame of the menu or of the card. Answers a [DocumentResult]: `ok`
   * when it has; else `popup_covered`. See [MenuRequest] `blurred`.
   */
  | { kind: "has_focus" }
  | { kind: "save_bar_resize"; height: number }
  | { kind: "save_bar_close" };

/**
 * A frame whose parent, of another origin, must show it where a popup is:
 * [frame] by its ID, and the popup's center in its viewport. Without
 * IntersectionObserver v2, as on Firefox, nothing else sees that parent fade
 * or cover the frame: its content script checks, see `frame_guard`.
 */
export interface FrameCheck {
  frame: number;
  x: number;
  y: number;
}

/**
 * A document's answer to a [DocumentCommand] that expects one. A `guard`
 * passes with `url`, the frame's URL as its content script reads it: a
 * single-page app may change it without a navigation; and with `parent` when
 * a parent of another origin must check too. A `fill` passes with
 * `passwordStep` when it wrote the username alone, of a username step or of
 * a sign-in form whose password it couldn't write: the password step may
 * follow.
 */
export type DocumentResult =
  { ok: true; url?: string; parent?: FrameCheck; passwordStep?: true } | { ok: false; reason: FillRefusal };

/** Each [DocumentCommand]: whether it expects a [DocumentResult], or only notifies. */
const DOCUMENT_COMMANDS: { readonly [K in DocumentCommand["kind"]]: "answer" | "notify" } = {
  fill: "answer",
  fill_totp: "answer",
  fill_password: "answer",
  guard: "answer",
  menu_resize: "notify",
  menu_close: "notify",
  menu_details: "notify",
  menu_details_hide: "notify",
  focus_popup: "answer",
  has_focus: "answer",
  save_bar_resize: "notify",
  save_bar_close: "notify",
};

/**
 * Whether a command of [kind] is for one document: `"answer"` for one that
 * expects a [DocumentResult], `"notify"` for one that doesn't, `null` for the
 * rest.
 */
export function documentCommandOf(kind: unknown): "answer" | "notify" | null {
  return typeof kind === "string" && Object.hasOwn(DOCUMENT_COMMANDS, kind)
    ? DOCUMENT_COMMANDS[kind as DocumentCommand["kind"]]
    : null;
}

export type ContentCommand =
  /** With the token and the origin of the document it's for. */
  | (DocumentCommand & { doc: string; origin: string })
  /** Sent to the top frame, whatever its document: now, or after the navigation that follows. */
  | { kind: "save_bar_show" }
  /** The frame with focus answers with a `focus_report`. Sent to all frames. */
  | { kind: "focus_probe"; probe: string }
  /**
   * Frames with a login form to fill answer with a `form_report`. Sent to all
   * frames, each of which answers `null` so the background sees the tab has
   * a content script.
   */
  | { kind: "form_probe"; probe: string }
  /**
   * The top frame answers with a `top_report`, for a release in a frame of a
   * tab whose URL doesn't tell the top frame's origin. Sent to the top frame,
   * whatever its document.
   */
  | { kind: "top_probe"; probe: string }
  /**
   * The content script whose document holds frame `frame` checks that it
   * shows the frame at `x`, `y` of the frame's viewport, as a `guard` does,
   * and answers with a `frame_report`. The others don't answer. Sent to all
   * frames: the background doesn't know which is the parent.
   */
  | ({ kind: "frame_guard"; probe: string } & FrameCheck);

// region Embedded pages

export const MENU_PORT = "inline-menu";
export const MENU_DETAILS_PORT = "menu-details";
export const SAVE_BAR_PORT = "save-bar";
/**
 * Once an embedded page's frame loads, the content script posts BIND_MESSAGE
 * and the token to it, for the extension's origins only: Chromium loads
 * `use_dynamic_url` pages by a dynamic URL, which may not be the origin they
 * run as. The page posts nothing to the web page it's in: the origin of its
 * messages would tell the page Firefox's per-install extension UUID. It binds
 * over its own port, which tells the background it has the token.
 */
export const BIND_MESSAGE = "keyguard:bind";
/** The paths of the embedded pages, as web-accessible resources. */
export const MENU_PAGE = "/inline-menu.html";
export const MENU_DETAILS_PAGE = "/menu-details.html";
export const SAVE_BAR_PAGE = "/save-bar.html";

export interface MenuItem {
  handle: string;
  name: string;
  username?: string;
  account: string;
  has_totp: boolean;
  reprompt: boolean;
  /** A URI of the item matches the page: else it's suggested, by its name, and fills with a prompt. */
  matched: boolean;
}

/** The details of a login that its row doesn't show. */
export interface ItemDetails {
  /** Its URIs, in order, at most [MAX_DETAIL_URIS]. */
  uris: string[];
  /** How many URIs it has besides [uris]. */
  more: number;
  /** The name of its folder. */
  folder?: string;
  /** When it was last modified, as an ISO-8601 instant. */
  revision_date?: string;
}

/** The URIs of a login that its details list; they count the rest. */
export const MAX_DETAIL_URIS = 5;

/** The [ItemDetails] of [item], from the app's `suggest`. */
export function itemDetailsOf(item: Pick<SuggestItem, "uris" | "uri_count" | "folder" | "revision_date">): ItemDetails {
  const all = item.uris ?? [];
  const uris = all.slice(0, MAX_DETAIL_URIS);
  return {
    uris,
    more: Math.max(item.uri_count ?? 0, all.length) - uris.length,
    ...(item.folder !== undefined ? { folder: item.folder } : {}),
    ...(item.revision_date !== undefined ? { revision_date: item.revision_date } : {}),
  };
}

/**
 * A login as the popup's detail pane and the card beside the inline menu show
 * it (../ui/components/ItemDetail.svelte): metadata only, never a secret. The
 * background sends the card this, see [MenuDetailsEvent] `view`.
 */
export interface ItemView {
  name: string;
  username?: string;
  account: string;
  /** The fields it copies, see [popupActions]: not a password that its collections hide. */
  copies: CopyAction[];
  reprompt: boolean;
  /** A URI of it matches the page: else it's suggested. */
  matched: boolean;
  /** Filled last on the tab. */
  last: boolean;
  /** The URL of its avatar's icon, see [itemIcon]; `null` for the name's letter. */
  icon: string | null;
  passkeys: { user_name: string; user_display_name?: string }[];
  /** Its URIs, folder and date: for a page's suggestions, not for search results. */
  details?: ItemDetails;
}

/** The icon of [item]'s avatar: [pageIcon], of the page's site, unless the login is suggested, maybe for another site. */
export function itemIcon(item: { matched: boolean }, pageIcon: string | null): string | null {
  return item.matched ? pageIcon : null;
}

/** The [ItemView] of [item], with [pageIcon] for its avatar (see [itemIcon]) and [last] for its chip. */
export function itemViewOf(item: SuggestItem | SearchItem, pageIcon: string | null, last: boolean): ItemView {
  return {
    name: item.name,
    ...(item.username !== undefined ? { username: item.username } : {}),
    account: item.account,
    copies: popupActions(item).filter(isCopyAction),
    reprompt: item.reprompt,
    matched: item.matched,
    last,
    icon: itemIcon(item, pageIcon),
    passkeys: ("passkeys" in item ? (item.passkeys ?? []) : []).map((passkey) => ({
      user_name: passkey.user_name,
      ...(passkey.user_display_name !== undefined ? { user_display_name: passkey.user_display_name } : {}),
    })),
    // Only `suggest` has them: a search result has none of these fields.
    ...("uris" in item || "folder" in item || "revision_date" in item ? { details: itemDetailsOf(item) } : {}),
  };
}

/** A passkey for a conditional request, from `suggest`. */
export interface MenuPasskey {
  handle: string;
  user_name: string;
  user_display_name?: string;
  /** The name of the item that holds it. */
  item: string;
  account: string;
}

export type MenuView =
  | { kind: "loading" }
  /** Not connected, not paired and the like: the popup has the details. */
  | { kind: "status"; status: ExtensionStatus }
  | { kind: "locked"; unlocking: boolean }
  | { kind: "blocked"; host: string }
  | {
      kind: "items";
      /** The field's role: a `new-password` field offers "Generate password". */
      role: FieldRole;
      host: string;
      /** The matched logins, then the suggested ones. */
      items: MenuItem[];
      /** On a sign-in step, the handle of the login filled last on this tab, listed first of its section. */
      last: string | null;
      truncated: boolean;
      /** The item of a login fill on this tab within 2 minutes, for "Fill TOTP". */
      totp: MenuItem | null;
      /** Passkeys for the page's conditional request, on `webauthn` fields. */
      passkeys: MenuPasskey[];
      /** The URL of the icon of the page's site, for [items] and [totp], see ../background/siteIcon.ts; `null` for none. */
      icon: string | null;
      /** Whether the menu offers "New login", see [MenuRequest] `create`. */
      create: boolean;
    };

export type MenuRequest =
  | { kind: "bind"; token: string }
  /** Fills the login of an item. */
  | { kind: "fill"; handle: string }
  /** Fills the one-time password of an item. */
  | { kind: "totp"; handle: string }
  /** Answers the page's conditional request with a passkey. */
  | { kind: "passkey"; handle: string }
  | { kind: "generate" }
  /** The app opens its Save login window with a new login for the page. */
  | { kind: "create" }
  | { kind: "unlock" }
  | { kind: "close" }
  | { kind: "resize"; height: number }
  /**
   * A trusted Space on a login's row selected it: the card beside the menu
   * shows its details. Sent again as the row moves. [top] and [height]: the
   * row's box in the menu's frame.
   */
  | { kind: "select"; handle: string; top: number; height: number }
  /** No login's row is selected: the card hides. */
  | { kind: "deselect" }
  /**
   * A trusted ArrowRight (ArrowLeft right to left) on the row of login
   * [handle]: focus moves into the card, if it shows that login.
   */
  | { kind: "details_focus"; handle: string }
  /**
   * The menu's frame lost focus. Once the page lost it too, as the window did
   * to the app's prompt, the card hides and the menu selects no login, as for
   * [ContentRequest] `menu_blur`. Focus that moved into the card keeps them.
   * The menu stays.
   */
  | { kind: "blurred" };

/**
 * The page of the card beside the menu. [seq]: of the view whose content has
 * [height]: the card shows only at the height of what it shows.
 */
export type MenuDetailsRequest =
  | { kind: "bind"; token: string }
  | { kind: "resize"; height: number; seq: number }
  /**
   * Copies a field of the login [handle] that the card shows in view [seq]: a
   * trusted click, past its click gate. A newer view refuses it.
   */
  | { kind: "copy"; action: CopyAction; handle: string; seq: number }
  /**
   * Copies linked URI [index] of the login [handle] that the card shows in
   * view [seq]: of its [ItemDetails.uris]. As `copy`; a URI is no secret, so
   * the app isn't asked.
   */
  | { kind: "copy_uri"; handle: string; index: number; seq: number }
  /** ArrowLeft (ArrowRight right to left) or Escape: focus goes back to the login's row in the menu. */
  | { kind: "back" }
  /** Tab: the menu closes, as on Tab in the menu. */
  | { kind: "close" }
  /** The card's frame lost focus: as the menu's [MenuRequest] `blurred`. */
  | { kind: "blurred" };

export type MenuDetailsEvent =
  | { kind: "bound" }
  /** The details of login [handle] to show; [seq] tells this view from those that replace it. */
  | { kind: "view"; seq: number; handle: string; item: ItemView }
  /**
   * The content script shows the card's frame, or hid it: the click gate's
   * wait starts, or stops. See [MenuEvent] `shown`. [side]: the menu's side
   * that the card shows on; sent again when it moves.
   */
  | { kind: "shown"; side?: DetailsSide }
  | { kind: "hidden" }
  /** Focus the first field: the content script moved focus into the frame on a trusted key. See [MenuEvent] `focus`. */
  | { kind: "focus" }
  | { kind: "busy"; busy: boolean }
  /** The background copied the field the card asked for. */
  | { kind: "copied" }
  | { kind: "error"; error: PageError };

/** Why an action of an embedded page failed: a [CallError] or a [FillRefusal]. */
export type PageError = CallError | FillRefusal;

export type MenuEvent =
  | { kind: "bound" }
  /**
   * Focus the first row: the content script moved focus into the frame on a
   * trusted key. The page itself can focus the frame, but never send this.
   */
  | { kind: "focus" }
  /**
   * The content script shows the frame, hidden till then: the click gate's
   * wait starts. The page itself can't tell, see ../ui/clickGate.ts.
   */
  | { kind: "shown" }
  | { kind: "view"; view: MenuView }
  /** Focus went back from the card beside the menu: focus the row of login [handle], on the background's word as `focus`. */
  | { kind: "focus_row"; handle: string }
  /** The card beside the menu shows on [side], see [DetailsSide]. */
  | { kind: "details_side"; side: DetailsSide }
  /**
   * Focus left the menu and the card for the page, or the window: the page
   * selects no login, see [ContentRequest] `menu_blur` and [MenuRequest] `blurred`.
   */
  | { kind: "deselect" }
  | { kind: "busy"; busy: boolean }
  | { kind: "error"; error: PageError };

/** The save bar's actions name the offer of the view they were taken on: a newer offer doesn't take them. */
export type SaveBarRequest =
  | { kind: "bind"; token: string }
  | { kind: "save"; offer: number }
  | { kind: "dismiss"; offer: number }
  | { kind: "never"; offer: number }
  | { kind: "resize"; height: number };

export type SaveBarEvent =
  | { kind: "bound" }
  /**
   * `offer`: what tells the offer from those that replace it. `icon`: the URL
   * of the icon of the login's site, see ../background/siteIcon.ts; `null` for
   * none.
   */
  | { kind: "view"; offer: number; host: string; username: string | null; icon: string | null }
  | { kind: "busy"; busy: boolean }
  | { kind: "error"; error: PageError };

// endregion

// region Validation
//
// Everything that crosses a context boundary is checked before use: the
// background gets messages from every content script, which a compromised
// renderer controls.

const ROLES: ReadonlySet<string> = new Set<FieldRole>(["username", "current-password", "new-password", "otp"]);
/** Tokens, probes and document tokens: 32 hex characters, 128 random bits. */
const TOKEN = /^[0-9a-f]{32}$/;
const MAX_VALUE_LENGTH = 4096;
const MAX_HANDLE_LENGTH = 64;
/** Every [FillRefusal]: the compiler checks that none is missing. */
export const FILL_REFUSALS = Object.keys({
  no_form: true,
  nothing_to_fill: true,
  no_otp_field: true,
  not_editable: true,
  field_gone: true,
  field_opted_out: true,
  field_hidden: true,
  field_off_screen: true,
  field_covered: true,
  popup_covered: true,
  frame_hidden: true,
  frame_unchecked: true,
  origin: true,
} satisfies Record<FillRefusal, true>) as readonly FillRefusal[];
const REFUSALS: ReadonlySet<string> = new Set(FILL_REFUSALS);

const isValue = (value: unknown): value is string => typeof value === "string" && value.length <= MAX_VALUE_LENGTH;
const isOptionalValue = (value: unknown): value is string | undefined =>
  value === undefined || (isValue(value) && value.length > 0);
const isOptionalString = (value: unknown): value is string | undefined =>
  value === undefined || typeof value === "string";
const isOptionalBoolean = (value: unknown): value is boolean | undefined =>
  value === undefined || typeof value === "boolean";
const isFiniteNumber = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value);

export function isToken(value: unknown): value is string {
  return typeof value === "string" && TOKEN.test(value);
}

/** A new token, see TOKEN. */
export function randomToken(): string {
  return toHex(randomBytes(16));
}

/** The [FrameCheck] in [value], or `null` when it isn't one. */
export function parseFrameCheck(value: unknown): FrameCheck | null {
  if (!isObject(value) || !Number.isSafeInteger(value.frame) || (value.frame as number) <= 0) return null;
  return isFiniteNumber(value.x) && isFiniteNumber(value.y)
    ? { frame: value.frame as number, x: value.x, y: value.y }
    : null;
}

/**
 * A document's answer to a [DocumentCommand]; refused with [missing] when
 * there's none, or it's malformed, and with `frame_unchecked` when its
 * parent to check is.
 */
export function parseDocumentResult(value: unknown, missing: FillRefusal): DocumentResult {
  if (isObject(value) && value.ok === true) {
    const parent = parseFrameCheck(value.parent);
    // A parent to check that can't be read fails closed.
    if (value.parent !== undefined && parent === null) return { ok: false, reason: "frame_unchecked" };
    return {
      ok: true,
      ...(typeof value.url === "string" ? { url: value.url } : {}),
      ...(parent !== null ? { parent } : {}),
      // Anything but `true` is as nothing.
      ...(value.passwordStep === true ? { passwordStep: true } : {}),
    };
  }
  if (isObject(value) && value.ok === false && isFillRefusal(value.reason)) return { ok: false, reason: value.reason };
  return { ok: false, reason: missing };
}

export function isFillRefusal(value: unknown): value is FillRefusal {
  return typeof value === "string" && REFUSALS.has(value);
}

export function isRole(value: unknown): value is FieldRole {
  return typeof value === "string" && ROLES.has(value);
}

function isOffer(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function isDetailsSide(value: unknown): value is DetailsSide {
  return value === "left" || value === "right";
}

function isHandle(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= MAX_HANDLE_LENGTH;
}

export function parseContentRequest(message: unknown): ContentMessage | null {
  if (!isObject(message) || !isToken(message.doc)) return null;
  const request = parseRequest(message);
  return request === null ? null : { ...request, doc: message.doc };
}

function parseRequest(message: Record<string, unknown>): ContentRequest | null {
  switch (message.kind) {
    case "menu_open":
      return isRole(message.role) && isOptionalBoolean(message.webauthn) && isOptionalString(message.url)
        ? {
            kind: "menu_open",
            role: message.role,
            webauthn: message.webauthn === true,
            ...(message.url !== undefined ? { url: message.url } : {}),
          }
        : null;
    case "save_candidate": {
      const candidate = message.candidate;
      if (!isObject(candidate) || !isValue(candidate.password) || candidate.password.length === 0) return null;
      if (!isOptionalValue(candidate.username) || !isOptionalValue(candidate.previous_password)) return null;
      return {
        kind: "save_candidate",
        candidate: {
          password: candidate.password,
          ...(candidate.username !== undefined ? { username: candidate.username } : {}),
          ...(candidate.previous_password !== undefined ? { previous_password: candidate.previous_password } : {}),
        },
      };
    }
    case "username_step":
      return isValue(message.username) && message.username.length > 0
        ? { kind: "username_step", username: message.username }
        : null;
    case "password_step":
      return isToken(message.step) && isOptionalValue(message.account) && isOptionalString(message.url)
        ? {
            kind: "password_step",
            step: message.step,
            ...(message.account !== undefined ? { account: message.account } : {}),
            ...(message.url !== undefined ? { url: message.url } : {}),
          }
        : null;
    case "save_bar_open":
    case "menu_focus":
    case "menu_blur":
      return { kind: message.kind };
    case "popup_shown": {
      const { popup, shown, side, room } = message;
      // The menu only shows, and is on no side of anything.
      if (popup === "menu")
        return shown === true && side === undefined && room === undefined
          ? { kind: "popup_shown", popup, shown }
          : null;
      if (popup !== "menu_details") return null;
      if (typeof shown !== "boolean" || (side !== undefined && !isDetailsSide(side))) return null;
      if (room !== undefined && room !== false) return null;
      // Only a card that shows is on a side; only a hidden one lacks room.
      return {
        kind: "popup_shown",
        popup,
        shown,
        ...(shown && side !== undefined ? { side } : {}),
        ...(!shown && room === false ? { room } : {}),
      };
    }
    case "focus_report":
      return isToken(message.probe) && (message.role === null || isRole(message.role))
        ? { kind: "focus_report", probe: message.probe, role: message.role }
        : null;
    case "form_report": {
      const at = message.focused_at;
      return isToken(message.probe) &&
        (at === null || (isFiniteNumber(at) && at >= 0)) &&
        typeof message.writable === "boolean"
        ? { kind: "form_report", probe: message.probe, focused_at: at, writable: message.writable }
        : null;
    }
    case "top_report":
      return isToken(message.probe) ? { kind: "top_report", probe: message.probe } : null;
    case "frame_report": {
      if (!isToken(message.probe)) return null;
      if (message.ok === false) {
        return isFillRefusal(message.reason)
          ? { kind: "frame_report", probe: message.probe, ok: false, reason: message.reason }
          : null;
      }
      const parent = parseFrameCheck(message.parent);
      if (message.ok !== true || (message.parent !== undefined && parent === null)) return null;
      return { kind: "frame_report", probe: message.probe, ok: true, ...(parent !== null ? { parent } : {}) };
    }
    default:
      return null;
  }
}

export function parseMenuRequest(message: unknown): MenuRequest | null {
  if (!isObject(message)) return null;
  switch (message.kind) {
    case "bind":
      return isToken(message.token) ? { kind: "bind", token: message.token } : null;
    case "fill":
    case "totp":
    case "passkey":
      return isHandle(message.handle) ? { kind: message.kind, handle: message.handle } : null;
    case "generate":
    case "create":
    case "unlock":
    case "close":
    case "deselect":
    case "blurred":
      return { kind: message.kind };
    case "details_focus":
      return isHandle(message.handle) ? { kind: "details_focus", handle: message.handle } : null;
    case "resize":
      return parseResize(message);
    case "select":
      return isHandle(message.handle) && isFiniteNumber(message.top) && isFiniteNumber(message.height)
        ? { kind: "select", handle: message.handle, top: message.top, height: message.height }
        : null;
    default:
      return null;
  }
}

export function parseMenuDetailsRequest(message: unknown): MenuDetailsRequest | null {
  if (!isObject(message)) return null;
  switch (message.kind) {
    case "bind":
      return isToken(message.token) ? { kind: "bind", token: message.token } : null;
    case "resize": {
      const resize = parseResize(message);
      return resize !== null && isSeq(message.seq) ? { ...resize, seq: message.seq } : null;
    }
    case "copy":
      return isCopyAction(message.action) && isHandle(message.handle) && isSeq(message.seq)
        ? { kind: "copy", action: message.action, handle: message.handle, seq: message.seq }
        : null;
    case "copy_uri":
      return isHandle(message.handle) && isUriIndex(message.index) && isSeq(message.seq)
        ? { kind: "copy_uri", handle: message.handle, index: message.index, seq: message.seq }
        : null;
    case "back":
    case "close":
    case "blurred":
      return { kind: message.kind };
    default:
      return null;
  }
}

/** The [MenuDetailsEvent] `view` a request of the card is for. */
const isSeq = (value: unknown): value is number => Number.isSafeInteger(value);

/** An index of [ItemDetails.uris], which holds at most MAX_DETAIL_URIS. */
const isUriIndex = (value: unknown): value is number =>
  Number.isSafeInteger(value) && (value as number) >= 0 && (value as number) < MAX_DETAIL_URIS;

/** The content height an embedded page reports; the content script clamps it. */
function parseResize(message: Record<string, unknown>): { kind: "resize"; height: number } | null {
  return isFiniteNumber(message.height) ? { kind: "resize", height: message.height } : null;
}

export function parseSaveBarRequest(message: unknown): SaveBarRequest | null {
  if (!isObject(message)) return null;
  switch (message.kind) {
    case "bind":
      return isToken(message.token) ? { kind: "bind", token: message.token } : null;
    case "save":
    case "dismiss":
    case "never":
      return isOffer(message.offer) ? { kind: message.kind, offer: message.offer } : null;
    case "resize":
      return parseResize(message);
    default:
      return null;
  }
}

// endregion
