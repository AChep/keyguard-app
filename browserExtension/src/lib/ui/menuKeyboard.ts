// The keyboard of the inline menu. ArrowDown and ArrowUp move between its
// buttons. Escape and Tab close it, and the content script puts focus back on
// the field. Enter is left to the buttons, and Space to all but a login's
// row: the click they fire is trusted like the key, and goes through the
// click gate as a pointer's does.
//
// The content script focuses the menu's frame on an ArrowDown in the field,
// and the background then sends the page a `focus` event: the first button
// takes focus, at once or once it shows. The frame getting focus alone moves
// it to no button: the page can focus the frame too, through
// `window.frames`, and have the next Enter press a row.
//
// The card beside the menu shows the details of a login: Space on its row
// shows it, and hides it again; focus moving to another row or Escape hides
// it too. The pointer neither shows nor changes it. While it shows, the arrow
// toward the card on its row moves focus into it, through the background as
// for `focus`; in the card, ArrowUp and ArrowDown move between its fields,
// and the arrow toward the menu or Escape goes back to the row: see
// [onDetailsKeydown]. The card shows on the side of the menu that has room,
// as the content script tells: see [towardCard]. The card's page takes focus
// by the same [MenuFocus].

import type { DetailsSide } from "../page/messages";

/** The buttons the arrows move between, in order. */
function buttonsOf(root: HTMLElement): HTMLButtonElement[] {
  return Array.from(root.querySelectorAll<HTMLButtonElement>("button:not(:disabled)"));
}

function focusButton(button: HTMLButtonElement | undefined) {
  button?.focus({ preventScroll: true });
  button?.scrollIntoView({ block: "nearest" });
}

/**
 * The arrow key that points from the menu toward the card beside it: toward
 * [side], the side it shows on. While that's unknown, toward the end of a
 * line, where the card goes first: ArrowRight, or ArrowLeft right to left.
 */
export function towardCard(side: DetailsSide | null | undefined, rtl: boolean): "ArrowLeft" | "ArrowRight" {
  const right = side === undefined || side === null ? !rtl : side === "right";
  return right ? "ArrowRight" : "ArrowLeft";
}

/** The arrow key that points from the card back toward the menu, see [towardCard]. */
function towardMenu(side: DetailsSide | null | undefined, rtl: boolean): "ArrowLeft" | "ArrowRight" {
  return towardCard(side, rtl) === "ArrowRight" ? "ArrowLeft" : "ArrowRight";
}

/** ArrowDown and ArrowUp: focus moves to the next or the previous of the enabled buttons, round. */
function moveFocus(event: KeyboardEvent, root: HTMLElement) {
  const buttons = buttonsOf(root);
  if (buttons.length === 0) return;
  event.preventDefault();
  const index = buttons.indexOf(root.ownerDocument.activeElement as HTMLButtonElement);
  const step = event.key === "ArrowDown" ? 1 : -1;
  const next = index < 0 ? (step > 0 ? 0 : buttons.length - 1) : (index + step + buttons.length) % buttons.length;
  focusButton(buttons[next]);
}

/** What the keys of the menu's page ask of the background. */
export interface MenuKeys {
  /** Closes the menu. */
  close(): void;
  /** The login whose card shows beside the menu, if any. */
  details?: string | null;
  /** Shows the card of login [handle], or hides it for `null`, see [onMenuKeydown]. */
  select?(handle: string | null): void;
  /** Moves focus into the card, which shows login [handle], see [onMenuKeydown]. */
  focusDetails?(handle: string): void;
  /** The page reads right to left. */
  rtl?: boolean;
  /** The side of the menu that the card shows on, once the content script told; see [towardCard]. */
  side?: DetailsSide | null;
}

/** The login's handle of the focused row, a button with `data-handle`, if it's a login's. */
function focusedLogin(root: HTMLElement): string | null {
  const active = root.ownerDocument.activeElement;
  return active instanceof HTMLButtonElement && root.contains(active) ? (active.dataset.handle ?? null) : null;
}

/**
 * Handles a key pressed in the menu's page. Space on a login's row, a button
 * with `data-handle`, shows its card, or hides it: never a fill. ArrowDown
 * and ArrowUp hide it as focus leaves the row, and Escape hides it before it
 * closes the menu. The arrow toward the card, see [towardCard], moves focus
 * into it while it shows the row's login.
 */
export function onMenuKeydown(event: KeyboardEvent, root: HTMLElement, keys: MenuKeys): void {
  const details = keys.details ?? null;
  if (event.key === "Escape" && details !== null && keys.select !== undefined) {
    event.preventDefault();
    keys.select(null);
    return;
  }
  if (event.key === "Escape" || event.key === "Tab") {
    // Tab would leave for the page; the field gets focus back instead.
    event.preventDefault();
    keys.close();
    return;
  }
  if (event.key === " ") {
    const handle = focusedLogin(root);
    if (handle === null || keys.select === undefined) return;
    // The button's click would fill.
    event.preventDefault();
    // A held key repeats: once per press.
    if (event.isTrusted && !event.repeat) keys.select(details === handle ? null : handle);
    return;
  }
  if (event.key === towardCard(keys.side, keys.rtl === true)) {
    const handle = focusedLogin(root);
    if (keys.focusDetails === undefined || handle === null || handle !== details) return;
    event.preventDefault();
    keys.focusDetails(handle);
    return;
  }
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    moveFocus(event, root);
    // Focus moved to another row: the card hides.
    if (details !== null && focusedLogin(root) !== details) keys.select?.(null);
  }
}

/**
 * Handles a key released in the menu's page: some browsers click a button on
 * the release of Space, which on a login's row shows its card instead, see
 * [onMenuKeydown].
 */
export function onMenuKeyup(event: KeyboardEvent, root: HTMLElement): void {
  if (event.key === " " && focusedLogin(root) !== null) event.preventDefault();
}

/** What the keys of the card beside the menu ask of the background. */
export interface DetailsKeys {
  /** Focus goes back to the login's row in the menu. */
  back(): void;
  /** Closes the menu, with the card. */
  close(): void;
  rtl: boolean;
  /** The side of the menu that the card shows on, see [MenuKeys.side]. */
  side?: DetailsSide | null;
}

/**
 * Handles a key pressed in the page of the card beside the menu: ArrowUp and
 * ArrowDown move between its fields, the arrow toward the menu (see
 * [towardCard]) and Escape go back to it, and Tab closes the menu, as in the menu.
 * Enter and Space are left to the fields, whose click goes through the click gate.
 */
export function onDetailsKeydown(event: KeyboardEvent, root: HTMLElement, keys: DetailsKeys): void {
  if (event.key === "Tab") {
    event.preventDefault();
    keys.close();
    return;
  }
  if (event.key === "Escape" || event.key === towardMenu(keys.side, keys.rtl)) {
    event.preventDefault();
    keys.back();
    return;
  }
  if (event.key === "ArrowDown" || event.key === "ArrowUp") moveFocus(event, root);
}

/** Which button of the menu's page gets focus, and when. */
export class MenuFocus {
  /** The background's `focus` came, and no button took it yet. */
  private requested = false;
  /** A button had focus since the frame got it. */
  private held = false;
  /** The button that had focus last: it takes focus back after an action. */
  private last: HTMLButtonElement | null = null;

  /**
   * The background's `focus` event: the first button takes focus, or
   * [button], as the row that focus goes back to from the card.
   */
  request(root: HTMLElement, button?: HTMLButtonElement): void {
    this.requested = true;
    if (button !== undefined) this.last = button;
    this.settle(root);
  }

  /** A `focusin` in the page. */
  focusIn(target: EventTarget | null): void {
    if (!(target instanceof HTMLButtonElement)) return;
    this.held = true;
    this.requested = false;
    this.last = target;
  }

  /** The frame lost focus: to the page, or with the window. */
  blur(): void {
    this.held = false;
    this.requested = false;
    this.last = null;
  }

  /**
   * The frame got focus, or the buttons changed. With nothing focused, a
   * button takes focus for a request, or when one had it, as before an action
   * disabled them: the one that had it, while it's still there and enabled,
   * else the first. Else focus stays where it is.
   */
  settle(root: HTMLElement): void {
    const doc = root.ownerDocument;
    const active = doc.activeElement;
    if (!(this.requested || this.held) || !doc.hasFocus() || (active !== null && active !== doc.body)) return;
    const buttons = buttonsOf(root);
    focusButton(this.last !== null && buttons.includes(this.last) ? this.last : buttons[0]);
  }
}
