// The extension's UI in a page: one host element with a closed shadow root,
// holding the inline icon, the inline menu and the save bar. The host is a
// built-in `div`: a page that defined the name of a custom element host first
// would get its closed root through `ElementInternals`.
//
// The menu and the save bar are extension pages in iframes, so the page can't
// read what they show; the content script only hands them a one-time token
// (by `postMessage` to the frame once it loads, for the extension's origins
// only, not in its URL). The pages post nothing to the page.
// Beside the menu, a card shows the details of its selected login: a third
// extension page, whose fields copy on a click, with the checks of the menu's.
// ./detailsCard.ts places it, shows and hides it.
// They and the icon sit in popovers, in the top layer, so page elements can't
// stack over them, and ancestors' opacity and transforms don't apply. For a
// field in a modal dialog, the host goes into the dialog: the page outside it
// is inert.
import { BIND_MESSAGE, type GuardedPopup, type PopupShown } from "../page/messages";
import {
  LOGO_BACKGROUND,
  LOGO_BODY,
  LOGO_GRADIENT_CSS,
  LOGO_PATH_COLORS,
  LOGO_PATHS,
  LOGO_SHACKLE,
  LOGO_TILE_RADIUS,
  LOGO_VIEW_BOX,
} from "../ui/logo";
import type { FrameColors } from "../ui/storedTheme";
import { boxShadow, DetailsCard, ELEVATION_2_LAYERS, ELEVATION_3_LAYERS } from "./detailsCard";
import { flatParentOf } from "./guards";

const ICON_SIZE = 20;
const MENU_WIDTH = 320;
const MENU_MIN_HEIGHT = 48;
const MENU_MAX_HEIGHT = 360;
const MENU_GAP = 4;
/** The menu shows once its page tells its height, or after this long without it. */
const MENU_REVEAL_TIMEOUT_MS = 300;
/** Frames in a row without a move, after which the UI checks its fields less often. */
const STILL_FRAMES = 30;
/** How often the UI checks fields that stay still. */
const STILL_CHECK_MS = 500;
const SAVE_BAR_WIDTH = 400;
/** The save bar's height until its page tells the height of its content. */
const SAVE_BAR_HEIGHT = 204;
const SAVE_BAR_MIN_HEIGHT = 96;
const SAVE_BAR_MAX_HEIGHT = 400;
/** The save bar's distance to the top and the right of the window, and the least to its left and bottom. */
const SAVE_BAR_MARGIN = 16;
/** Narrower fields get the icon next to them instead of inside. */
const MIN_WIDTH_FOR_INNER_ICON = 96;

// The pages' look (../ui/theme.css), which the shadow root can't read: the
// surface container under a frame until its page draws (unless the browser's
// theme gives another, see [ContentUi.setFrameColors]), and Material 3
// elevation levels: 1 for the hovered icon, 2 for the menu and 3 for the card
// beside it and the save bar (./detailsCard.ts: the card's gap derives from
// them). Each page draws its own border, inside the frame.
const SURFACE_CONTAINER_LIGHT = "#f5f5f5";
const SURFACE_CONTAINER_DARK = "#202121";
const ELEVATION_1 = boxShadow([
  [0, 1, 2, 0, 0.3],
  [0, 1, 3, 1, 0.15],
]);
const ELEVATION_2 = boxShadow(ELEVATION_2_LAYERS);
const ELEVATION_3 = boxShadow(ELEVATION_3_LAYERS);
const EASE_STANDARD = "cubic-bezier(0.2, 0, 0, 1)";
/** The icon scales in from this as it appears. */
const ICON_APPEAR_SCALE = 0.6;
const ICON_APPEAR_MS = 150;

// Every property the page could inherit or override is set: the host is in
// the page's DOM, the rest is in the closed shadow root.
const HOST_STYLE = [
  "all: initial",
  "position: fixed",
  "inset: 0 auto auto 0",
  "width: 0",
  "height: 0",
  "display: block",
  "opacity: 1",
  "visibility: visible",
  "filter: none",
  "transform: none",
  "z-index: 2147483647",
  "pointer-events: none",
]
  .map((rule) => `${rule} !important`)
  .join("; ");

const SHADOW_CSS = `
:host { all: initial !important; }
* { box-sizing: border-box; }
.icon {
  all: initial;
  position: fixed;
  width: ${ICON_SIZE}px;
  height: ${ICON_SIZE}px;
  padding: 0;
  border: none;
  border-radius: ${ICON_SIZE * LOGO_TILE_RADIUS}px;
  /* The logo's tile. A CSS gradient: an SVG one would need an id in the shadow root. */
  background: ${LOGO_GRADIENT_CSS};
  box-shadow: none;
  cursor: pointer;
  pointer-events: auto;
  visibility: visible;
  opacity: 1;
  transition: box-shadow ${ICON_APPEAR_MS}ms ${EASE_STANDARD};
}
.icon svg { display: block; width: 100%; height: 100%; pointer-events: none; }
/* The state layer, in the logo's light color: lighter on hover, darker while pressed. */
.icon::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: transparent;
  pointer-events: none;
  transition: background-color ${ICON_APPEAR_MS}ms ${EASE_STANDARD};
}
.icon:hover { box-shadow: ${ELEVATION_1}; }
.icon:hover::after { background: color-mix(in srgb, ${LOGO_BODY} 16%, transparent); }
.icon:active { box-shadow: none; }
.icon:active::after { background: color-mix(in srgb, ${LOGO_SHACKLE} 16%, transparent); }
.icon:focus-visible { outline: 2px solid ${LOGO_BACKGROUND}; outline-offset: 2px; }
.icon[hidden] { display: none; }
.popup {
  all: initial;
  display: block;
  position: fixed;
  margin: 0;
  padding: 0;
  border: none;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: ${ELEVATION_2};
  pointer-events: auto;
  visibility: visible;
  opacity: 1;
  color-scheme: light dark;
}
.popup.save-bar { border-radius: 28px; box-shadow: ${ELEVATION_3}; }
/* Beside the menu, DETAILS_GAP away; above it in the top layer, shown after it. */
.popup.menu-details { box-shadow: ${ELEVATION_3}; }
/*
 * Chromium's IntersectionObserver v2 counts the transparent backdrop of a
 * popover in the top layer as covering every one below it: the card's would
 * cover the menu beside it, the menu's the save bar, and the icon's the menu,
 * which would turn off their pages' click gates (../ui/clickGate.ts).
 */
.popup::backdrop, .icon::backdrop { display: none; }
@media (prefers-reduced-motion: reduce) {
  .icon, .icon::after { transition: none; }
}
.popup:not(:popover-open):not(.open) { display: none; }
.popup.open { display: block; z-index: 2147483647; }
.popup iframe { all: initial; display: block; width: 100%; height: 100%; border: none; color-scheme: light dark; }
/* A menu until its page tells its height: in the top layer, but neither seen nor clicked. */
.popup.pending, .popup.pending iframe { visibility: hidden; pointer-events: none; }
`;

const FRAME_COLOR = /^#[0-9a-f]{6}$/;

/**
 * The background of the frames, after SHADOW_CSS: [colors] for a light and a
 * dark page, Keyguard's where they have none. Only `#rrggbb` goes into the
 * style sheet.
 */
function frameCss(colors: FrameColors): string {
  const color = (value: string | null, fallback: string) =>
    value !== null && FRAME_COLOR.test(value) ? value : fallback;
  return `
.popup { background: ${color(colors.light, SURFACE_CONTAINER_LIGHT)}; }
@media (prefers-color-scheme: dark) {
  .popup { background: ${color(colors.dark, SURFACE_CONTAINER_DARK)}; }
}
`;
}

export interface UiCallbacks {
  /** A trusted click on the icon of [field]. */
  iconClicked(field: HTMLInputElement): void;
  /**
   * A trusted ArrowDown in [field] while its icon shows. True when the menu
   * opens for it; [ContentUi.focusMenu] then moves focus into it.
   */
  arrowDownPressed(field: HTMLInputElement): boolean;
  /**
   * Focus moved from the field into the menu's frame. The menu's page then
   * focuses a row only when the background tells it to: the page can focus
   * the frame too.
   */
  menuFocused(): void;
  /** The user dismissed the menu: Escape, or a click elsewhere. */
  menuDismissed(): void;
  /**
   * The frame of a popup shows now, or hides: its page's click gate starts,
   * or waits again. The menu's shows once, see [ContentUi.openMenu]. The
   * card beside it reports again when it moves to the other side of the
   * menu; one that fits on neither side, `room: false`, the UI then
   * forgets, see [DetailsCard].
   */
  popupShown(report: PopupShown): void;
}

interface Popup {
  element: HTMLDivElement;
  frame: HTMLIFrameElement;
  /** Removes the popup's listeners. */
  abort: AbortController;
}

interface Menu extends Popup {
  field: HTMLInputElement;
  height: number;
  /** The card beside it, when its page came with the menu. */
  details: DetailsCard | null;
  /** Whether its page loaded and got the token. */
  ready: boolean;
  /** Whether focus goes into it once it's ready and shows. */
  focus: boolean;
  /** Whether it shows: it's hidden until its page tells its height. */
  shown: boolean;
}

interface SaveBar extends Popup {
  /** The height of its content, within its bounds. */
  height: number;
}

/** The kinds of popups, as their classes in SHADOW_CSS. */
type PopupKind = "menu" | "menu-details" | "save-bar";

/** `element.matches`, false for a selector the browser doesn't know. */
function matches(element: Element, selector: string): boolean {
  try {
    return element.matches(selector);
  } catch {
    return false;
  }
}

/** Whether a field left the page or its layout: disconnected, or without a box. */
function gone(field: Element, rect: DOMRect): boolean {
  return !field.isConnected || (rect.width === 0 && rect.height === 0);
}

/** What the UI's place depends on: the window's size and the boxes of the fields with the icon and the menu. */
interface Placement {
  width: number;
  height: number;
  icon: DOMRect | null;
  menu: DOMRect | null;
}

function sameBox(a: DOMRect | null, b: DOMRect | null): boolean {
  if (a === null || b === null) return a === b;
  return a.left === b.left && a.top === b.top && a.width === b.width && a.height === b.height;
}

function samePlacement(a: Placement, b: Placement): boolean {
  return a.width === b.width && a.height === b.height && sameBox(a.icon, b.icon) && sameBox(a.menu, b.menu);
}

/** Creates elements in the isolated world: the page can't see constructors we call. */
function svgIcon(doc: Document): SVGSVGElement {
  const svg = doc.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", LOGO_VIEW_BOX);
  svg.setAttribute("aria-hidden", "true");
  LOGO_PATHS.forEach((d, index) => {
    const path = doc.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", d);
    path.setAttribute("fill", LOGO_PATH_COLORS[index]!);
    path.setAttribute("fill-rule", "evenodd");
    svg.append(path);
  });
  return svg;
}

export class ContentUi {
  readonly host: HTMLElement;
  private readonly root: ShadowRoot;
  /** The frames' background, see [setFrameColors]. */
  private readonly frameStyle: HTMLStyleElement;
  private readonly icon: HTMLButtonElement;
  private iconField: HTMLInputElement | null = null;
  /** Whether [iconField] is right-to-left: the icon goes to its left end. */
  private iconRtl = false;
  private menu: Menu | null = null;
  private saveBar: SaveBar | null = null;
  private frameRequest = 0;
  /** The check of fields that stayed still, see [follow]. */
  private stillTimer: ReturnType<typeof setTimeout> | undefined;
  /** Frames in a row the placement didn't change. */
  private stillFrames = 0;
  /** What wakes [follow] while the icon or the menu shows; `null` otherwise. */
  private following: { abort: AbortController; fields: ResizeObserver | null; observed: Element[] } | null = null;
  /** The [layout] the UI was last placed for. */
  private placed: Placement | null = null;
  private readonly abort = new AbortController();
  /** The `focusout` events that left the UI's frames, see [leftUi]. */
  private readonly focusouts = new WeakSet<Event>();
  /**
   * The host's container and the dialogs around it. The page moving the host,
   * or closing and reopening one of them, could put its own top layer over the
   * menu: the UI goes. And the host itself: `inert` on it goes as it comes.
   */
  private readonly placement = new MutationObserver((records) => {
    this.host.removeAttribute("inert");
    const moved = records.some(
      (record) =>
        (record.type === "attributes" && record.target !== this.host) ||
        Array.from(record.removedNodes).includes(this.host),
    );
    if (!moved) return;
    this.hideIcon();
    if (this.menu !== null) this.callbacks.menuDismissed();
  });

  constructor(
    private readonly doc: Document,
    private readonly callbacks: UiCallbacks,
    private readonly pageUrl: (path: string) => string,
    /** The origins the extension's pages may run as: only they get a token. */
    private readonly extensionOrigins: readonly string[],
    label: string,
    /** The direction of the UI's language: the card beside the menu goes to its left first in "rtl". */
    private readonly direction: "ltr" | "rtl" = "ltr",
  ) {
    this.host = doc.createElement("div");
    this.host.setAttribute("style", HOST_STYLE);
    this.root = this.host.attachShadow({ mode: "closed" });
    const style = doc.createElement("style");
    style.textContent = SHADOW_CSS;
    this.frameStyle = doc.createElement("style");
    this.frameStyle.textContent = frameCss({ light: null, dark: null });
    this.icon = doc.createElement("button");
    this.icon.className = "icon";
    this.icon.type = "button";
    this.icon.hidden = true;
    if ("popover" in this.icon) this.icon.setAttribute("popover", "manual");
    this.icon.title = label;
    this.icon.setAttribute("aria-label", label);
    this.icon.append(svgIcon(doc));
    this.root.append(style, this.frameStyle, this.icon);

    const signal = this.abort.signal;
    this.icon.addEventListener(
      "click",
      (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (event.isTrusted && this.iconField !== null) this.callbacks.iconClicked(this.iconField);
      },
      { signal },
    );
    // Keep the field's focus when the icon is pressed.
    this.icon.addEventListener("mousedown", (event) => event.preventDefault(), { signal });
    // Wherever the host is: the document hears a focusout from a page's
    // shadow root, closed too, as from the page's element. See [leftUi].
    this.host.addEventListener("focusout", (event) => this.focusouts.add(event), { capture: true, signal });
    const win = doc.defaultView!;
    doc.addEventListener("visibilitychange", () => this.follow(), { signal });
    win.addEventListener(
      "pointerdown",
      (event) => {
        if (this.menu !== null && event.composedPath()[0] !== this.host && !event.composedPath().includes(this.host)) {
          if (event.composedPath()[0] !== this.menu.field) this.callbacks.menuDismissed();
        }
      },
      { capture: true, signal },
    );
    win.addEventListener(
      "keydown",
      (event) => {
        if (event.key === "Escape" && this.menu !== null && event.isTrusted) this.callbacks.menuDismissed();
      },
      { capture: true, signal },
    );
    // After the page's handlers: a field with suggestions of its own keeps the key.
    win.addEventListener("keydown", (event) => this.onKeydown(event), { signal });
  }

  /**
   * A trusted ArrowDown without modifiers in the field with the icon opens
   * the menu, as a click on the icon does, and moves focus into it. Not again
   * for each repeat of a held key.
   */
  private onKeydown(event: KeyboardEvent) {
    const field = this.iconField;
    if (field === null || event.key !== "ArrowDown" || !event.isTrusted || event.defaultPrevented) return;
    if (event.repeat || event.isComposing || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    // Keys go to what has focus; this sees into the field's closed shadow root too.
    if ((field.getRootNode() as Document | ShadowRoot).activeElement !== field) return;
    if (this.menu?.field === field) this.focusMenu();
    else if (!this.callbacks.arrowDownPressed(field)) return;
    event.preventDefault();
  }

  /**
   * The color under the frames of the menu, its card and the save bar until
   * their pages draw, for a light and a dark page: the browser theme's, which
   * the pages take too (../ui/storedTheme.ts), or null for Keyguard's. In the
   * closed shadow root, where the page can't change it.
   */
  setFrameColors(colors: FrameColors): void {
    this.frameStyle.textContent = frameCss(colors);
  }

  /**
   * Whether focus left the UI with [event], a `focusout`, as from the menu's
   * frame or the card's: the host may be in a page's shadow root, see
   * [containerFor], where the document hears the event as from the page's
   * element. Known once the event reached the host, after the capturing
   * listeners of the document.
   */
  leftUi(event: Event): boolean {
    return this.focusouts.has(event);
  }

  /** The field whose icon shows, if any. */
  get iconTarget(): HTMLInputElement | null {
    return this.iconField;
  }

  /** The field that opened the menu, while it's open. */
  get menuField(): HTMLInputElement | null {
    return this.menu?.field ?? null;
  }

  /** The popover of the menu, for the clickjacking checks. */
  get menuElement(): HTMLElement | null {
    return this.menu?.element ?? null;
  }

  /** The popover of the card beside the menu while it shows, for the clickjacking checks. */
  get menuDetailsElement(): HTMLElement | null {
    return this.menu?.details?.element ?? null;
  }

  /** The popover of the save bar, for the clickjacking checks. */
  get saveBarElement(): HTMLElement | null {
    return this.saveBar?.element ?? null;
  }

  /**
   * What the clickjacking checks of [popup] look at: its popover while it
   * shows, and the menu's field for the menu and the card beside it. `null`
   * when it doesn't show, or for an unknown popup.
   */
  guardTarget(popup: GuardedPopup): { element: HTMLElement; field: HTMLInputElement | null } | null {
    let element: HTMLElement | null;
    switch (popup) {
      case "save_bar":
        element = this.saveBarElement;
        return element === null ? null : { element, field: null };
      case "menu":
        element = this.menuElement;
        break;
      case "menu_details":
        element = this.menuDetailsElement;
        break;
      default:
        return null;
    }
    const field = this.menuField;
    return element === null || field === null ? null : { element, field };
  }

  /**
   * Where the host goes for [field]: the innermost open modal dialog around
   * it, as the page outside is inert; else the root.
   */
  private containerFor(field: Element): Element {
    for (let node = flatParentOf(field); node !== null; node = flatParentOf(node)) {
      if (node.localName === "dialog" && matches(node, ":modal")) return node;
    }
    return this.doc.documentElement;
  }

  /**
   * Moves the host into [container]. False while the save bar is open: a
   * moved iframe reloads, and its one-time token is spent.
   */
  private place(container: Element): boolean {
    if (this.host.parentNode === container) return true;
    if (this.saveBar !== null && this.host.isConnected) return false;
    // Moving takes the popovers out of the top layer and reloads the menu.
    this.hideIcon();
    this.closeMenu();
    this.placement.disconnect();
    container.append(this.host);
    // Disconnecting dropped the records of an `inert` set since.
    this.host.removeAttribute("inert");
    this.placement.observe(this.host, { attributeFilter: ["inert"] });
    // The container, for the host, and every dialog around it: re-showing a
    // modal dialog over the menu needs `close()` first, which drops `open`.
    for (let node: Element | null = container; node !== null; node = flatParentOf(node)) {
      if (node === container || node.localName === "dialog") {
        this.placement.observe(node, { childList: node === container, attributeFilter: ["open"] });
      }
    }
    return true;
  }

  showIcon(field: HTMLInputElement): void {
    // Kept out of the field's dialog, the icon would be inert.
    if (!this.place(this.containerFor(field))) {
      this.hideIcon();
      return;
    }
    const raise = this.iconField !== field || !matches(this.icon, ":popover-open");
    if (this.iconField !== field) this.iconRtl = this.doc.defaultView?.getComputedStyle(field).direction === "rtl";
    this.iconField = field;
    this.icon.hidden = false;
    this.position();
    // For another field, again: over what the page opened since, as its dialog.
    if (raise && !this.icon.hidden) {
      this.show(this.icon);
      this.appear(this.icon);
    }
    this.follow();
  }

  /** Scales [element] in with a fade, unless the user asked for reduced motion. */
  private appear(element: HTMLElement) {
    const win = this.doc.defaultView;
    if (
      typeof element.animate !== "function" ||
      win?.matchMedia?.("(prefers-reduced-motion: reduce)").matches !== false
    )
      return;
    element.animate(
      [
        { opacity: 0, transform: `scale(${ICON_APPEAR_SCALE})` },
        { opacity: 1, transform: "none" },
      ],
      {
        duration: ICON_APPEAR_MS,
        easing: EASE_STANDARD,
      },
    );
  }

  hideIcon(): void {
    this.iconField = null;
    this.icon.hidden = true;
    this.hide(this.icon);
    this.watchLayout();
  }

  /**
   * Opens the menu below [field] and hands [token] to it once it loads. The
   * menu is in the top layer at once, but hidden until its page tells its
   * height, or MENU_REVEAL_TIMEOUT_MS passed: it shows at the size of its
   * rows, not of a placeholder.
   *
   * [details]: the page of the card beside it and its token. It loads with
   * the menu, in the top layer but hidden: Firefox lays out no frame that
   * isn't displayed. It shows on [showMenuDetails]. Focus moves into it on
   * the background's word only, see [focusMenuDetails].
   */
  openMenu(field: HTMLInputElement, token: string, page: string, details?: { token: string; page: string }): void {
    this.closeMenu();
    if (!this.place(this.containerFor(field))) return;
    const popup = this.createPopup("menu", page, token, () => {
      menu.ready = true;
      this.passFocus(menu);
    });
    const menu: Menu = {
      ...popup,
      field,
      height: MENU_MIN_HEIGHT * 2,
      details:
        details === undefined
          ? null
          : new DetailsCard(
              this.createPopup("menu-details", details.page, details.token),
              popup.frame,
              this.root,
              // As wide as the menu.
              { width: MENU_WIDTH, margin: MENU_GAP, rtl: this.direction === "rtl" },
              (report) => this.callbacks.popupShown(report),
            ),
      ready: false,
      focus: false,
      shown: false,
    };
    menu.element.classList.add("pending");
    const reveal = setTimeout(() => this.revealMenu(menu), MENU_REVEAL_TIMEOUT_MS);
    menu.abort.signal.addEventListener("abort", () => clearTimeout(reveal), { once: true });
    this.menu = menu;
    this.position();
    this.show(menu.element);
    // After the menu, above it in the top layer: the only place either is shown.
    if (menu.details !== null) this.show(menu.details.popover);
    this.follow();
  }

  /** Shows the menu that [openMenu] hid; its page's click gate starts. */
  private revealMenu(menu: Menu) {
    if (menu.shown || this.menu !== menu) return;
    menu.shown = true;
    menu.element.classList.remove("pending");
    // The card of a row selected meanwhile.
    menu.details?.menuRevealed();
    this.callbacks.popupShown({ popup: "menu", shown: true });
    this.passFocus(menu);
  }

  /**
   * Moves focus from the field into the menu once its page loaded and it
   * shows: the frame takes it, and the page hands it to its first row on the
   * background's word (see [UiCallbacks.menuFocused]).
   */
  focusMenu(): void {
    const menu = this.menu;
    if (menu === null) return;
    menu.focus = true;
    this.passFocus(menu);
  }

  /** Moves focus into the menu, if asked for, once it's ready and shows: a hidden frame takes none. */
  private passFocus(menu: Menu) {
    if (!menu.focus || !menu.ready || !menu.shown) return;
    menu.focus = false;
    // Not when the user went on from the field since.
    if ((menu.field.getRootNode() as Document | ShadowRoot).activeElement === menu.field) {
      menu.frame.focus({ preventScroll: true });
      this.callbacks.menuFocused();
    }
  }

  /** Sizes the menu to the [height] of its page's content; the first one shows it, see [openMenu]. */
  resizeMenu(height: number): void {
    const menu = this.menu;
    if (menu === null) return;
    menu.height = Math.min(Math.max(Math.round(height), MENU_MIN_HEIGHT), MENU_MAX_HEIGHT);
    this.position();
    this.revealMenu(menu);
  }

  /**
   * Shows the card beside the menu, level with its selected row: [top] and
   * [row], the row's top and height in the menu's frame; [height], the height
   * of the card's content. See [DetailsCard.show].
   */
  showMenuDetails(top: number, row: number, height: number): void {
    this.menu?.details?.show(top, row, height);
  }

  hideMenuDetails(): void {
    this.menu?.details?.hide();
  }

  /** Moves focus into the frame of [popup], see [focusMenuDetails] and [focusMenuFrame]; false for an unknown one. */
  focusPopup(popup: "menu" | "menu_details"): boolean {
    switch (popup) {
      case "menu":
        return this.focusMenuFrame();
      case "menu_details":
        return this.focusMenuDetails();
      default:
        return false;
    }
  }

  /**
   * Moves focus from the menu's frame into the card's, which shows: on the
   * background's word, after a trusted key in the menu. The card's page then
   * focuses a field when the background tells it to: the page can focus the
   * frame too. False when focus isn't in the menu, or the card doesn't show.
   */
  focusMenuDetails(): boolean {
    return this.menu?.details?.focus() ?? false;
  }

  /** Moves focus from the card's frame back into the menu's, as [focusMenuDetails]. False when focus isn't in the card. */
  focusMenuFrame(): boolean {
    return this.menu?.details?.focusMenu() ?? false;
  }

  closeMenu(): void {
    const menu = this.menu;
    if (menu === null) return;
    // Focus in the menu or its card goes back to its field: Escape, Tab or an action closed it.
    const focused = this.root.activeElement === menu.frame || menu.details?.focused === true;
    menu.abort.abort();
    menu.element.remove();
    menu.details?.remove();
    this.menu = null;
    this.watchLayout();
    if (focused) menu.field.focus({ preventScroll: true });
  }

  /** Opens the save bar at the top right of the window. */
  openSaveBar(token: string, page: string): void {
    this.closeSaveBar();
    // At the root: a dialog may close or go while the bar shows.
    this.place(this.doc.documentElement);
    const bar: SaveBar = { ...this.createPopup("save-bar", page, token), height: SAVE_BAR_HEIGHT };
    const style = bar.element.style;
    style.top = `${SAVE_BAR_MARGIN}px`;
    style.right = `${SAVE_BAR_MARGIN}px`;
    style.left = "auto";
    this.saveBar = bar;
    this.placeSaveBar();
    this.doc.defaultView!.addEventListener("resize", () => this.placeSaveBar(), { signal: bar.abort.signal });
    this.show(bar.element);
  }

  /** Sizes the save bar to the [height] of its content, within its bounds and the window. */
  resizeSaveBar(height: number): void {
    if (this.saveBar === null) return;
    this.saveBar.height = Math.min(Math.max(Math.round(height), SAVE_BAR_MIN_HEIGHT), SAVE_BAR_MAX_HEIGHT);
    this.placeSaveBar();
  }

  /** Fits the save bar in the window, less the margins: a taller page scrolls in it. */
  private placeSaveBar() {
    const bar = this.saveBar;
    if (bar === null) return;
    const win = this.doc.defaultView!;
    const width = Math.min(SAVE_BAR_WIDTH, win.innerWidth - 2 * SAVE_BAR_MARGIN);
    const height = Math.max(Math.min(bar.height, win.innerHeight - 2 * SAVE_BAR_MARGIN), SAVE_BAR_MIN_HEIGHT);
    bar.element.style.width = `${Math.round(Math.max(width, 0))}px`;
    bar.element.style.height = `${Math.round(height)}px`;
  }

  closeSaveBar(): void {
    this.saveBar?.abort.abort();
    this.saveBar?.element.remove();
    this.saveBar = null;
  }

  /** Removes everything from the page. */
  destroy(): void {
    this.abort.abort();
    this.placement.disconnect();
    this.stopFollowing();
    this.host.remove();
    this.menu = null;
    this.saveBar = null;
    this.iconField = null;
  }

  /** [onReady]: its page loaded, and has the token. */
  private createPopup(kind: PopupKind, page: string, token: string, onReady?: () => void): Popup {
    const element = this.doc.createElement("div");
    element.className = `popup ${kind}`;
    if ("popover" in element) element.setAttribute("popover", "manual");
    const frame = this.doc.createElement("iframe");
    frame.src = this.pageUrl(page);
    frame.setAttribute("referrerpolicy", "no-referrer");
    frame.setAttribute("allow", "");
    const abort = new AbortController();
    this.abort.signal.addEventListener("abort", () => abort.abort(), { signal: abort.signal });
    // The token goes to this frame once its page loaded, which listens by
    // then, and only while it shows one of the extension's pages: the page may
    // navigate the frame, but then the origin is another, and the browser
    // drops the message. The extension's page sends it to the background.
    frame.addEventListener(
      "load",
      () => {
        const target = frame.contentWindow;
        if (target === null) return;
        for (const origin of this.extensionOrigins) {
          try {
            target.postMessage({ type: BIND_MESSAGE, token }, origin);
          } catch {
            // Not an origin the browser takes.
          }
        }
        onReady?.();
      },
      { signal: abort.signal },
    );
    element.append(frame);
    this.root.append(element);
    return { element, frame, abort };
  }

  /** Shows a popover at the top of the top layer: an open one again, to be over what opened since. */
  private show(element: HTMLElement) {
    if (typeof element.showPopover === "function") {
      try {
        this.hide(element);
        element.showPopover();
        return;
      } catch {
        // Not connected, or the browser refused: fall back to a fixed box.
      }
    }
    element.classList.add("open");
  }

  private hide(element: HTMLElement) {
    if (matches(element, ":popover-open")) element.hidePopover();
  }

  /**
   * Follows the fields while the icon or the menu shows, in a document the
   * user sees. Once a frame while they move: pages move fields without a
   * scroll too, as for a message shown above them. After STILL_FRAMES frames
   * without a move, every STILL_CHECK_MS, until a scroll, a resize of the
   * window or of a field, a focus change or a change of the page (see
   * [pageChanged]) has it check every frame again.
   */
  private follow() {
    if (this.watchLayout()) this.wake();
  }

  /** The page changed, as the content script's watcher saw: its fields may have moved. */
  pageChanged(): void {
    this.wake();
  }

  /** Checks every frame again, while [follow] follows. */
  private wake() {
    if (this.following === null) return;
    this.stillFrames = 0;
    if (this.stillTimer !== undefined) clearTimeout(this.stillTimer);
    this.stillTimer = undefined;
    if (this.frameRequest === 0) this.frameRequest = this.doc.defaultView!.requestAnimationFrame(() => this.onFrame());
  }

  private onFrame() {
    this.frameRequest = 0;
    const moved = this.reposition();
    if (this.following === null) return;
    this.stillFrames = moved ? 0 : this.stillFrames + 1;
    if (this.stillFrames < STILL_FRAMES)
      this.frameRequest = this.doc.defaultView!.requestAnimationFrame(() => this.onFrame());
    else this.stillTimer = setTimeout(() => this.onStillCheck(), STILL_CHECK_MS);
  }

  private onStillCheck() {
    this.stillTimer = undefined;
    const moved = this.reposition();
    if (this.following === null) return;
    if (moved) this.wake();
    else this.stillTimer = setTimeout(() => this.onStillCheck(), STILL_CHECK_MS);
  }

  /** Places the UI again if the [layout] changed; whether it did. */
  private reposition(): boolean {
    if (!this.watchLayout()) return false;
    const layout = this.layout();
    if (this.placed !== null && samePlacement(layout, this.placed)) return false;
    this.placed = layout;
    this.position(layout);
    return true;
  }

  /**
   * Keeps what wakes [follow] while the icon or the menu shows in a document
   * the user sees: listeners for scrolls, resizes and focus changes, and an
   * observer of the size of the fields. Otherwise stops it all, and returns
   * false.
   */
  private watchLayout(): boolean {
    const fields: Element[] = [];
    for (const field of [this.iconField, this.menu?.field]) if (field && !fields.includes(field)) fields.push(field);
    if (this.doc.hidden || fields.length === 0) {
      this.stopFollowing();
      return false;
    }
    if (this.following === null) {
      const abort = new AbortController();
      const win = this.doc.defaultView!;
      const wake = () => this.wake();
      const options = { capture: true, passive: true, signal: abort.signal };
      win.addEventListener("scroll", wake, options);
      win.addEventListener("resize", wake, options);
      this.doc.addEventListener("focusin", wake, options);
      this.doc.addEventListener("focusout", wake, options);
      const resize = typeof ResizeObserver === "function" ? new ResizeObserver(wake) : null;
      this.following = { abort, fields: resize, observed: [] };
    }
    const following = this.following;
    if (
      fields.length !== following.observed.length ||
      fields.some((field, index) => field !== following.observed[index])
    ) {
      following.fields?.disconnect();
      for (const field of fields) following.fields?.observe(field);
      following.observed = fields;
    }
    return true;
  }

  private stopFollowing() {
    if (this.frameRequest !== 0) this.doc.defaultView?.cancelAnimationFrame(this.frameRequest);
    this.frameRequest = 0;
    if (this.stillTimer !== undefined) clearTimeout(this.stillTimer);
    this.stillTimer = undefined;
    this.stillFrames = 0;
    this.following?.abort.abort();
    this.following?.fields?.disconnect();
    this.following = null;
  }

  /** The [Placement] now. */
  private layout(): Placement {
    const win = this.doc.defaultView!;
    const icon = this.iconField?.getBoundingClientRect() ?? null;
    const field = this.menu?.field ?? null;
    // Read once when the icon is on the menu's field.
    const menu = field === null ? null : field === this.iconField ? icon : field.getBoundingClientRect();
    return { width: win.innerWidth, height: win.innerHeight, icon, menu };
  }

  private position(layout: Placement = this.layout()) {
    const field = this.iconField;
    if (field !== null && layout.icon !== null) {
      const rect = layout.icon;
      if (gone(field, rect)) {
        this.hideIcon();
      } else {
        // At the end of the field: its right, or its left in a right-to-left one.
        const inner = rect.width >= MIN_WIDTH_FOR_INNER_ICON;
        const left = this.iconRtl
          ? inner
            ? rect.left + 6
            : rect.left - ICON_SIZE - 4
          : inner
            ? rect.right - ICON_SIZE - 6
            : rect.right + 4;
        this.icon.style.left = `${Math.round(left)}px`;
        this.icon.style.top = `${Math.round(rect.top + (rect.height - ICON_SIZE) / 2)}px`;
      }
    }
    const menu = this.menu;
    if (menu !== null && layout.menu !== null) {
      const rect = layout.menu;
      if (gone(menu.field, rect)) {
        this.callbacks.menuDismissed();
        return;
      }
      const width = Math.min(Math.max(rect.width, MENU_WIDTH), layout.width - 2 * MENU_GAP);
      // The space below and above the field, less the gaps to it and to the
      // frame's edge. In a small frame, as an embedded sign-in page, the menu
      // gets the larger and scrolls.
      const below = layout.height - rect.bottom - 2 * MENU_GAP;
      const above = rect.top - 2 * MENU_GAP;
      const downwards = below >= menu.height || below >= above;
      const height = Math.round(Math.max(Math.min(menu.height, downwards ? below : above), MENU_MIN_HEIGHT));
      const top = downwards ? rect.bottom + MENU_GAP : rect.top - MENU_GAP - height;
      const left = Math.min(Math.max(rect.left, MENU_GAP), layout.width - width - MENU_GAP);
      const box = {
        left: Math.round(left),
        top: Math.round(Math.max(top, MENU_GAP)),
        width: Math.round(width),
        height,
      };
      const style = menu.element.style;
      style.left = `${box.left}px`;
      style.top = `${box.top}px`;
      style.width = `${box.width}px`;
      style.height = `${box.height}px`;
      // After the menu: the card follows it, as when it flips above the field.
      menu.details?.menuPlaced(box, { width: layout.width, height: layout.height });
    }
  }
}
