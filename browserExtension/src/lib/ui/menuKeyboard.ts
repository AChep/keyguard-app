// The keyboard of the inline menu. ArrowDown and ArrowUp move between its
// buttons. Escape and Tab close it, and the content script puts focus back on
// the field. Enter and Space are left to the buttons: the click they fire is
// trusted like the key, and goes through the click gate as a pointer's does.
//
// The content script focuses the menu's frame on an ArrowDown in the field,
// and the background then sends the page a `focus` event: the first button
// takes focus, at once or once it shows. The frame getting focus alone moves
// it to no button: the page can focus the frame too, through
// `window.frames`, and have the next Enter press a row.

/** The buttons the arrows move between, in order. */
function buttonsOf(root: HTMLElement): HTMLButtonElement[] {
  return Array.from(root.querySelectorAll<HTMLButtonElement>("button:not(:disabled)"));
}

function focusButton(button: HTMLButtonElement | undefined) {
  button?.focus({ preventScroll: true });
  button?.scrollIntoView({ block: "nearest" });
}

/** Handles a key pressed in the menu's page; [close] asks the background to close it. */
export function onMenuKeydown(event: KeyboardEvent, root: HTMLElement, close: () => void): void {
  if (event.key === "Escape" || event.key === "Tab") {
    // Tab would leave for the page; the field gets focus back instead.
    event.preventDefault();
    close();
    return;
  }
  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
  const buttons = buttonsOf(root);
  if (buttons.length === 0) return;
  event.preventDefault();
  const index = buttons.indexOf(root.ownerDocument.activeElement as HTMLButtonElement);
  const step = event.key === "ArrowDown" ? 1 : -1;
  const next = index < 0 ? (step > 0 ? 0 : buttons.length - 1) : (index + step + buttons.length) % buttons.length;
  focusButton(buttons[next]);
}

/** Which button of the menu's page gets focus, and when. */
export class MenuFocus {
  /** The background's `focus` came, and no button took it yet. */
  private requested = false;
  /** A button had focus since the frame got it. */
  private held = false;
  /** The button that had focus last: it takes focus back after an action. */
  private last: HTMLButtonElement | null = null;

  /** The background's `focus` event. */
  request(root: HTMLElement): void {
    this.requested = true;
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
