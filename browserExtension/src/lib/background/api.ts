// The messages between the popup and the background, over a `runtime.Port`
// named [POPUP_PORT]. The background posts the status when the popup connects
// and on every change; the popup sends calls and gets one result per call.
import type { SearchItem, SuggestItem } from "../protocol/messages";

export const POPUP_PORT = "popup";
/** The popup's page, the only one that may connect as [POPUP_PORT]. */
export const POPUP_PAGE = "/popup.html";

export type PairingProgress =
  | { phase: "idle" }
  | { phase: "starting" }
  /** Waiting for the user to confirm `code` in the app. */
  | { phase: "confirm"; code: string }
  | { phase: "failed"; error: CallError };

export type ExtensionStatus =
  | { kind: "starting" }
  | { kind: "connector_missing" }
  | { kind: "app_unavailable" }
  | { kind: "integration_disabled" }
  | { kind: "update_required" }
  /** The app no longer speaks this extension's protocol versions. */
  | { kind: "extension_update_required" }
  | { kind: "app_update_required" }
  | { kind: "unverified_host" }
  | { kind: "too_many_sessions" }
  /** The session broke; the background reconnects with backoff, or waits for Retry after an invalid `hello` version. */
  | { kind: "protocol_error" }
  | { kind: "connecting" }
  | { kind: "not_paired"; pairing: PairingProgress }
  | { kind: "pinned_key_mismatch" }
  | { kind: "locked"; unlocking: boolean; error?: CallError }
  | { kind: "unlocked" };

/**
 * Why a call failed: an error code of protocol/README.md §9, or
 * `disconnected` (no session), `unsupported_page` (the tab can't be filled),
 * a fill refusal of ../page/messages.ts (`no_form`, `obscured`, `origin`),
 * `invalid_response`, `unknown`.
 */
export type CallError = string;

export type PageSuggestions =
  | { kind: "unsupported" }
  | { kind: "blocked"; host: string }
  /**
   * `origin`: of the page the matches are for, which the popup's fill and
   * copies name. `items`: the matched logins, then the suggested ones, see
   * [matchedFirst]. `last`: the handle of the login filled last on the tab,
   * listed first of its section, for the next step of a sign-in. `icon`: the
   * URL of the icon of the page's site, for these logins, see ./siteIcon.ts;
   * `null` for none. `create`: whether the popup offers "New login", see the
   * call `create`.
   */
  | {
      kind: "items";
      origin: string;
      host: string;
      truncated: boolean;
      items: SuggestItem[];
      last: string | null;
      icon: string | null;
      create: boolean;
    };

/**
 * No icon: the results with `matched` are for the page's site, which has the
 * icon of [PageSuggestions]; the rest may be for any site. `origin`: as for
 * [PageSuggestions].
 */
export type PageSearch =
  | { kind: "unsupported" }
  | { kind: "blocked"; host: string }
  | { kind: "items"; origin: string; truncated: boolean; items: SearchItem[] };

/** Each call with its arguments and its result. */
export interface Calls {
  /** Reconnects now, after an error or a missing connector. */
  retry: [Record<string, never>, null];
  pair: [Record<string, never>, null];
  /** After a pinned-key mismatch: forgets the pairing and pairs again. */
  repair: [Record<string, never>, null];
  unlock: [Record<string, never>, null];
  suggest: [{ tabId: number }, PageSuggestions];
  /** Searches the vault for the tab's page. A newer search cancels this one; a short query gets no items. */
  search: [{ tabId: number; query: string }, PageSearch];
  /**
   * Fills the tab through its content script: the top frame, or a frame of its
   * site with the form. `origin`: of the page the popup listed the item for;
   * the call fails with `origin` once the tab shows another.
   */
  fill: [{ tabId: number; handle: string; origin: string }, null];
  /**
   * The app opens its Save login window with a new login for the tab's page.
   * `origin` as for `fill`.
   */
  create: [{ tabId: number; origin: string }, null];
  /**
   * The background copies: the app's approval prompt takes focus and closes
   * the popup, which would lose its own write. `origin` as for `fill`.
   */
  copy_password: [{ tabId: number; handle: string; origin: string }, null];
  copy_totp: [{ tabId: number; handle: string; origin: string }, null];
  generate: [{ tabId?: number }, { password: string }];
}

export type CallMethod = keyof Calls;
export type CallArgs<M extends CallMethod> = Calls[M][0];
export type CallResult<M extends CallMethod> = Calls[M][1];
/** What answers each call. */
export type CallHandlers = { readonly [M in CallMethod]: (args: CallArgs<M>) => Promise<CallResult<M>> };
/** The calls about a tab's page, which PageController answers; BackgroundController answers the rest. */
export type PageCallMethod = "suggest" | "search" | "fill" | "create";

export type PopupMessage = { kind: "call"; id: number; method: CallMethod; args: unknown };

export type BackgroundMessage =
  | { kind: "status"; status: ExtensionStatus }
  | { kind: "result"; id: number; ok: true; value: unknown }
  | { kind: "result"; id: number; ok: false; error: CallError };
