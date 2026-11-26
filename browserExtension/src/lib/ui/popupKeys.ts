// The popup's keyboard, as the app's Quick search: quickSearchKeyEventAction
// of common/.../feature/home/vault/quicksearch/QuickSearchInteractions.kt and
// the shortcuts of QuickSearchState.kt. In the search field, ArrowDown and
// ArrowUp move the selection and Enter fills; anywhere in the popup, Mod+C
// copies the username, Mod+Shift+C the password and Mod+Alt+C the one-time
// password. Mod is ⌘ on macOS and Ctrl elsewhere. Escape is PopupSearch's.
import { copiesPassword } from "../protocol/messages";

export type PopupAction = "fill" | "copy_username" | "copy_password" | "copy_totp";

/** The actions in the order the popup shows them, as quickSearchActionTypes. */
export const POPUP_ACTIONS: readonly PopupAction[] = ["fill", "copy_username", "copy_password", "copy_totp"];

/** The actions that copy a field, which the detail pane and the card beside the inline menu offer too. */
export type CopyAction = Exclude<PopupAction, "fill">;

export function isCopyAction(value: unknown): value is CopyAction {
  return value !== "fill" && POPUP_ACTIONS.includes(value as PopupAction);
}

export interface Shortcut {
  /** A letter in upper case, or "Enter". */
  key: string;
  /** Ctrl, or ⌘ on macOS. */
  mod?: boolean;
  shift?: boolean;
  alt?: boolean;
}

export const SHORTCUTS: Readonly<Record<PopupAction, Shortcut>> = {
  fill: { key: "Enter" },
  copy_username: { key: "C", mod: true },
  copy_password: { key: "C", mod: true, shift: true },
  copy_totp: { key: "C", mod: true, alt: true },
};

/** What an item offers: the fields of SuggestItem and SearchItem it needs. */
export interface ActionItem {
  username?: string;
  has_password: boolean;
  has_totp: boolean;
  password_hidden?: boolean;
}

/** The actions that apply to [item]. */
export function popupActions(item: ActionItem): PopupAction[] {
  return POPUP_ACTIONS.filter((action) => {
    switch (action) {
      case "fill":
        return item.has_password || !!item.username;
      case "copy_username":
        return !!item.username;
      case "copy_password":
        return copiesPassword(item);
      case "copy_totp":
        return item.has_totp;
    }
  });
}

/** The fields of a KeyboardEvent that the keyboard reads. */
export interface KeyInput {
  key: string;
  code: string;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
  isComposing?: boolean;
  getModifierState?(key: string): boolean;
}

export interface KeyContext {
  mac: boolean;
  /** The search field has focus: the arrows and Enter are for it. */
  inField: boolean;
  /** Text is selected, in the field or the page: Mod+C copies it, as usual. */
  hasSelection: boolean;
}

export type PopupKey = { kind: "move"; step: 1 | -1 } | { kind: "action"; action: PopupAction };

/**
 * The letter of a key, in upper case: of `key`, or of the physical key when
 * `key` is no Latin letter, as with ⌥ on macOS ("ç") or a Cyrillic layout.
 */
function letterOf(input: KeyInput): string | null {
  if (/^[a-z]$/i.test(input.key)) return input.key.toUpperCase();
  return /^Key([A-Z])$/.exec(input.code)?.[1] ?? null;
}

/**
 * Whether a Ctrl+Alt chord types a character: AltGr is Ctrl+Alt on Windows,
 * and types "ć" on a Polish layout, say. macOS has no AltGr.
 */
function typesCharacter(input: KeyInput, mac: boolean): boolean {
  if (mac || !input.altKey) return false;
  if (input.getModifierState?.("AltGraph") === true) return true;
  return Array.from(input.key).length === 1 && !/^[a-z]$/i.test(input.key);
}

/** What a keydown does in the popup, or `null` to leave it to the browser. */
export function popupKey(input: KeyInput, context: KeyContext): PopupKey | null {
  // A key that confirms an IME composition is the field's.
  if (input.isComposing) return null;
  const mod = context.mac ? input.metaKey : input.ctrlKey;
  const otherMod = context.mac ? input.ctrlKey : input.metaKey;
  if (mod && !otherMod) {
    if (typesCharacter(input, context.mac)) return null;
    if (letterOf(input) !== "C" || (input.shiftKey && input.altKey)) return null;
    if (input.shiftKey) return { kind: "action", action: "copy_password" };
    if (input.altKey) return { kind: "action", action: "copy_totp" };
    return context.hasSelection ? null : { kind: "action", action: "copy_username" };
  }
  if (!context.inField || input.ctrlKey || input.metaKey || input.altKey || input.shiftKey) return null;
  switch (input.key) {
    case "ArrowDown":
      return { kind: "move", step: 1 };
    case "ArrowUp":
      return { kind: "move", step: -1 };
    case "Enter":
      return { kind: "action", action: "fill" };
    default:
      return null;
  }
}

/**
 * The index [step] away from [current] in a list of [count], clamped to its
 * ends, as moveSelectedResultId of QuickSearchState.kt: from no selection
 * (-1), down goes to the first and up to the last. `null` for an empty list.
 */
export function moveSelection(current: number, count: number, step: number): number | null {
  if (count === 0) return null;
  const from = current >= 0 && current < count ? current : step >= 0 ? -1 : count;
  return Math.min(Math.max(from + step, 0), count - 1);
}

/** Whether [platform], of `navigator.userAgentData` or `navigator.platform`, is macOS. */
export function isMacPlatform(platform: string | undefined): boolean {
  return /^mac/i.test(platform ?? "");
}

/** Whether the popup runs on macOS. */
export function runsOnMac(): boolean {
  const data = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData;
  // `platform` is "" where the browser withholds it.
  return isMacPlatform(data?.platform || navigator.platform);
}

/**
 * The keys of [shortcut] as the app labels them (ShortcutText.kt): "⌘", "⇧"
 * and "⌥" on macOS, "Ctrl", "Shift" and "Alt" elsewhere, in that order.
 * Enter is "⏎" on macOS, as Java names it there.
 */
export function shortcutKeys(shortcut: Shortcut, mac: boolean): string[] {
  const keys: string[] = [];
  if (shortcut.mod) keys.push(mac ? "⌘" : "Ctrl");
  if (shortcut.shift) keys.push(mac ? "⇧" : "Shift");
  if (shortcut.alt) keys.push(mac ? "⌥" : "Alt");
  keys.push(shortcut.key === "Enter" && mac ? "⏎" : shortcut.key);
  return keys;
}

/** The label of [shortcut], such as "⌘+C" or "Ctrl+Shift+C". */
export function shortcutLabel(shortcut: Shortcut, mac: boolean): string {
  return shortcutKeys(shortcut, mac).join("+");
}

/** [shortcut] as an `aria-keyshortcuts` value, such as "Meta+Shift+C". */
export function ariaKeyShortcuts(shortcut: Shortcut, mac: boolean): string {
  const keys: string[] = [];
  if (shortcut.mod) keys.push(mac ? "Meta" : "Control");
  if (shortcut.shift) keys.push("Shift");
  if (shortcut.alt) keys.push("Alt");
  keys.push(shortcut.key);
  return keys.join("+");
}
