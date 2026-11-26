// The card beside the inline menu (see ./ui.ts): the details of the menu's
// selected login, in a popover of its own, level with the selected row and
// DETAILS_GAP away from the menu. [DetailsCard] places it, shows and hides
// it, tells which side of the menu it shows on, and moves focus between its
// frame and the menu's. The UI makes its popover with the card's page, puts
// it in the top layer after the menu's, and styles it.
import type { DetailsSide, PopupShown } from "../page/messages";

/** A layer of a box shadow: its x and y offsets, blur and spread in px, and the alpha of its black. */
export type ShadowLayer = readonly [x: number, y: number, blur: number, spread: number, alpha: number];

export function boxShadow(layers: readonly ShadowLayer[]): string {
  return layers
    .map(([x, y, blur, spread, alpha]) => `${x}px ${y}px ${blur}px ${spread}px rgb(0 0 0 / ${alpha})`)
    .join(", ");
}

/**
 * How far a shadow paints past the side of its box. Chromium blurs with a
 * standard deviation of half the blur radius and paints 3 of them: 1.5 times
 * the radius, plus the spread and the offset.
 */
export function shadowReach(layers: readonly ShadowLayer[]): number {
  return Math.max(...layers.map(([x, , blur, spread]) => Math.abs(x) + Math.ceil(1.5 * blur) + spread));
}

// Material 3 elevation levels of the pages' look (../ui/theme.css): 2 for
// the menu, and 3 for the card beside it and the save bar.
export const ELEVATION_2_LAYERS: readonly ShadowLayer[] = [
  [0, 1, 2, 0, 0.3],
  [0, 2, 6, 2, 0.15],
];
export const ELEVATION_3_LAYERS: readonly ShadowLayer[] = [
  [0, 1, 3, 0, 0.3],
  [0, 4, 8, 3, 0.15],
];
/**
 * The space between the menu and the card beside it, above the menu: past
 * the reach of the card's shadow, and 1px more. Chromium counts a shadow over
 * a frame as covering it, which would turn off the click gate of the menu's
 * page (../ui/clickGate.ts) while the card shows.
 */
export const DETAILS_GAP = shadowReach(ELEVATION_3_LAYERS) + 1;

export interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** The window's size. */
export interface Viewport {
  width: number;
  height: number;
}

/** Where the card goes beside the menu: see [DetailsCard.show]. */
interface Card {
  /** The top of the selected row in the menu's frame. */
  top: number;
  /** The height of the selected row. */
  row: number;
  /** The height of the card's content. */
  height: number;
}

/** How the card fits beside the menu. */
export interface CardLayout {
  /** The card's width. */
  width: number;
  /** The least distance from the card to the window's edges. */
  margin: number;
  /** Whether the UI's language is right-to-left: the card goes before the menu first. */
  rtl: boolean;
}

/** The card's popover, which the UI made with the card's page. */
export interface CardPopup {
  element: HTMLElement;
  frame: HTMLIFrameElement;
  /** Removes the popup's listeners. */
  abort: AbortController;
}

/**
 * Where the card goes beside [menu], the menu's box, level with its selected
 * row, and on which side of it: after the menu, else before it, the other way
 * round in a right-to-left UI. `"no_room"` when it fits on neither side within
 * the window; `null` while the row is scrolled out of the menu. It keeps
 * DETAILS_GAP from the menu: it never overlaps it, nor does its shadow.
 */
function detailsBox(
  menu: Box,
  card: Card,
  viewport: Viewport,
  layout: CardLayout,
): (Box & { side: DetailsSide }) | "no_room" | null {
  const { width, margin } = layout;
  const row = menu.top + card.top;
  const middle = row + card.row / 2;
  if (middle < menu.top || middle > menu.top + menu.height) return null;
  // As tall as its content, within the window: past it, its page scrolls.
  const height = Math.round(Math.min(card.height, viewport.height - 2 * margin));
  if (height <= 0) return "no_room";
  const after = menu.left + menu.width + DETAILS_GAP;
  const before = menu.left - DETAILS_GAP - width;
  const left = (layout.rtl ? [before, after] : [after, before]).find(
    (x) => x >= margin && x + width <= viewport.width - margin,
  );
  if (left === undefined) return "no_room";
  const top = Math.min(Math.max(row, margin), viewport.height - margin - height);
  const side = left === after ? "right" : "left";
  return { left: Math.round(left), top: Math.round(top), width, height, side };
}

/**
 * The card beside a menu, for as long as the menu is open. It loads with the
 * menu, in the top layer but hidden ("pending"): Firefox lays out no frame
 * that isn't displayed. It shows while the menu shows, a row is selected and
 * there's room beside the menu; otherwise it's hidden again.
 */
export class DetailsCard {
  /** The selected row, while there is one: the card hides while it's `null`. */
  private card: Card | null = null;
  /** Whether the card shows: it has a [card] and room beside the menu, which shows. */
  private shown = false;
  /** The menu's side that the card shows on, while it does. */
  private side: DetailsSide | null = null;
  /** The menu's box and the window's size, as the UI placed the menu last: the card goes beside it. */
  private menu: { box: Box; viewport: Viewport } | null = null;
  /** Whether the menu shows: it's hidden until its page tells its height. */
  private menuShown = false;

  constructor(
    private readonly popup: CardPopup,
    /** The frame of the menu it's beside: focus moves between the two. */
    private readonly menuFrame: HTMLIFrameElement,
    /** The UI's shadow root, which tells where focus is. */
    private readonly root: ShadowRoot,
    private readonly layout: CardLayout,
    /**
     * The card shows now, on a side of the menu, or hides; see
     * [UiCallbacks.popupShown]. Called again when a card that shows moves to
     * the other side.
     */
    private readonly report: (shown: Extract<PopupShown, { popup: "menu_details" }>) => void,
  ) {
    popup.element.classList.add("pending");
  }

  /** The card's popover, shown or not: the UI puts it in the top layer after the menu's. */
  get popover(): HTMLElement {
    return this.popup.element;
  }

  /** The card's popover while it shows, for the clickjacking checks. */
  get element(): HTMLElement | null {
    return this.shown ? this.popup.element : null;
  }

  /** Whether focus is in the card's frame. */
  get focused(): boolean {
    return this.root.activeElement === this.popup.frame;
  }

  /**
   * Shows the card level with the menu's selected row: [top] and [row], the
   * row's top and height in the menu's frame; [height], the height of the
   * card's content. Malformed numbers change nothing.
   */
  show(top: number, row: number, height: number): void {
    if (![top, row, height].every(Number.isFinite)) return;
    this.card = { top, row, height };
    this.place();
  }

  hide(): void {
    if (this.card === null) return;
    this.card = null;
    this.place();
  }

  /** The UI placed the menu at [box], in a window of [viewport]'s size: the card follows it. */
  menuPlaced(box: Box, viewport: Viewport): void {
    this.menu = { box, viewport };
    this.place();
  }

  /** The menu shows now: so does the card of a row selected meanwhile. */
  menuRevealed(): void {
    this.menuShown = true;
    this.place();
  }

  /**
   * Moves focus from the menu's frame into the card's, which shows: on the
   * background's word, after a trusted key in the menu. False when focus
   * isn't in the menu, or the card doesn't show.
   */
  focus(): boolean {
    if (!this.shown || this.root.activeElement !== this.menuFrame) return false;
    this.popup.frame.focus({ preventScroll: true });
    return this.focused;
  }

  /** Moves focus from the card's frame back into the menu's, as [focus]. False when focus isn't in the card. */
  focusMenu(): boolean {
    if (!this.focused) return false;
    this.menuFrame.focus({ preventScroll: true });
    return this.root.activeElement === this.menuFrame;
  }

  /** Removes the card's popover and its listeners, with the menu. */
  remove(): void {
    this.popup.abort.abort();
    this.popup.element.remove();
  }

  /**
   * Places the card beside the menu's box last placed, see [detailsBox];
   * hidden while the menu is. Each time it shows, hides or moves to the menu's
   * other side, its page's click gate and the arrow keys hear of it. Without
   * room on either side, the card is forgotten and the background hears of
   * it, once: the menu drops its selection, see [PopupShown].
   */
  private place() {
    if (this.menu === null) return;
    const { box, viewport } = this.menu;
    const found = this.card === null || !this.menuShown ? null : detailsBox(box, this.card, viewport, this.layout);
    const noRoom = found === "no_room";
    const place = noRoom ? null : found;
    if (noRoom) this.card = null;
    const element = this.popup.element;
    element.classList.toggle("pending", place === null);
    if (place !== null) {
      const style = element.style;
      style.left = `${place.left}px`;
      style.top = `${place.top}px`;
      style.width = `${place.width}px`;
      style.height = `${place.height}px`;
    }
    const side = place?.side ?? null;
    if (!noRoom && this.shown === (place !== null) && this.side === side) return;
    this.shown = place !== null;
    this.side = side;
    // Hidden with focus in it: focus goes back to the menu, which the keys then reach.
    if (place === null && this.focused) this.menuFrame.focus({ preventScroll: true });
    if (side !== null) this.report({ popup: "menu_details", shown: true, side });
    else if (noRoom) this.report({ popup: "menu_details", shown: false, room: false });
    else this.report({ popup: "menu_details", shown: false });
  }
}
