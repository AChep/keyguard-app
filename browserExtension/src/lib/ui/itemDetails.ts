// The details of a login, in the UI's language: what the popup's detail pane
// and the card beside the inline menu show (./components/ItemDetail.svelte),
// and the markers of its row in their lists.
import { browser } from "wxt/browser";
import { t, tp } from "../i18n";
import type { ItemDetails } from "../page/messages";
import type { IconName } from "./icons";
import type { CopyAction } from "./popupKeys";

/** A copy of linked URI [uri] of [ItemDetails.uris], by its index: it has no keyboard shortcut. */
export interface UriCopy {
  uri: number;
}

/** What a press in the detail pane copies: a field, as the popup's actions, or a linked URI. */
export type ItemCopy = CopyAction | UriCopy;

/** The text of [ItemDetails], in the UI's language. */
export interface DetailLines {
  /**
   * The header of [uris], as the app's: "Linked URI", or "Linked URIs" for a
   * login with more than one; `null` for one without. A login may have URIs
   * that [uris] lacks, as those past the app's length cap: [more] counts them.
   */
  urisHeader: string | null;
  uris: string[];
  /** "and N more", when the login has more URIs than [uris], with none of them too. */
  more: string | null;
  folder: string | null;
  /** "Last modified at …"; `null` without a date, or with one that can't be read. */
  modified: string | null;
}

const DATE_STYLE: Intl.DateTimeFormatOptions = { dateStyle: "medium", timeStyle: "short" };
/** The date formats, by locale. */
const dateFormats = new Map<string, Intl.DateTimeFormat>();

function dateFormat(locale: string): Intl.DateTimeFormat {
  let format = dateFormats.get(locale);
  if (format === undefined) {
    try {
      format = new Intl.DateTimeFormat(locale, DATE_STYLE);
    } catch {
      // A language tag Intl refuses.
      format = new Intl.DateTimeFormat(undefined, DATE_STYLE);
    }
    dateFormats.set(locale, format);
  }
  return format;
}

/** When [date], an ISO-8601 instant, was, as a medium date and a short time of [locale]; `null` when it isn't one. */
export function formatDate(date: string | undefined, locale = browser.i18n.getUILanguage()): string | null {
  if (date === undefined) return null;
  const time = new Date(date);
  if (Number.isNaN(time.getTime())) return null;
  return dateFormat(locale).format(time);
}

export function detailLines(details: ItemDetails): DetailLines {
  const date = formatDate(details.revision_date);
  const uris = details.uris.length + details.more;
  return {
    urisHeader: uris === 0 ? null : t(uris > 1 ? "linked_uris" : "linked_uri"),
    uris: details.uris,
    more: details.more > 0 ? tp("skipped_and_more_note", details.more, details.more) : null,
    folder: details.folder !== undefined && details.folder !== "" ? details.folder : null,
    modified: date === null ? null : t("vault_view_revision_label", date),
  };
}

/** The parts of a line, as "username · account", without the empty ones. */
export function joined(...parts: (string | undefined)[]): string {
  return parts.filter((part) => part !== undefined && part !== "").join(" · ");
}

export interface ItemMarker {
  icon: IconName;
  label: string;
}

/**
 * The small icons of a login's row in the inline menu and the popup's list:
 * what else it has. The menu's rows have no passkeys.
 */
export function itemMarkers(item: {
  matched: boolean;
  has_totp: boolean;
  reprompt: boolean;
  passkeys?: readonly unknown[];
}): ItemMarker[] {
  const markers: ItemMarker[] = [];
  if (!item.matched) markers.push({ icon: "warning_amber", label: t("browser_extension_search_unmatched") });
  if (item.has_totp) markers.push({ icon: "two_fa", label: t("one_time_password") });
  if (item.reprompt) markers.push({ icon: "lock", label: t("additem_auth_reprompt_title") });
  if (item.passkeys?.length) markers.push({ icon: "key", label: t("passkeys") });
  return markers;
}
