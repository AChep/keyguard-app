// A ContentUi (src/lib/content/ui.ts) under happy-dom, for the tests of the
// UI and of the card beside its menu. happy-dom has no layout: fields get a
// box from the tests, and animation frames run when a test says so.
import { afterEach, beforeAll, beforeEach, vi } from "vitest";
import { ContentUi } from "../../src/lib/content/ui";
import type { PopupShown } from "../../src/lib/page/messages";

const requested = new Map<number, FrameRequestCallback>();
let frameIds = 0;

/** The animation frames requested and not run yet. */
export const frames: ReadonlyMap<number, FrameRequestCallback> = requested;

/** Runs the animation frames requested so far. */
export function runFrame(): void {
  const callbacks = Array.from(requested.values());
  requested.clear();
  for (const callback of callbacks) callback(performance.now());
}

const uis: ContentUi[] = [];

/**
 * Registers the hooks the UI's tests share: iframes that load, fields with a
 * box, animation frames that run on [runFrame], and a clean page after each
 * test. Call it at the top of the test file.
 */
export function useContentUiHooks(): void {
  // The menu and the save bar are iframes; at about:blank here.
  beforeAll(() => {
    (
      window as unknown as { happyDOM: { settings: { enableIframePageLoading: boolean } } }
    ).happyDOM.settings.enableIframePageLoading = true;
  });

  beforeEach(() => {
    // Fields are shown, unless a test gives them other boxes.
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(() => new DOMRect(10, 10, 200, 32));
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      requested.set(++frameIds, callback);
      return frameIds;
    });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation((id) => void requested.delete(id));
  });

  afterEach(() => {
    for (const ui of uis.splice(0)) ui.destroy();
    vi.restoreAllMocks();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    requested.clear();
    document.body.innerHTML = "";
    delete (window as { innerWidth?: number }).innerWidth;
    delete (window as { innerHeight?: number }).innerHeight;
  });
}

/** The origins the extension's pages may run as: Chromium's dynamic one, and the extension's own. */
export const EXTENSION_ORIGINS = [
  "chrome-extension://3f1c9e0a-dynamic",
  "chrome-extension://abcdefghijklmnopabcdefghijklmnop",
];

export function setup(
  options: {
    arrowDownPressed?: (ui: ContentUi, field: HTMLInputElement) => boolean;
    direction?: "ltr" | "rtl";
  } = {},
) {
  let dismissed = 0;
  let focused = 0;
  let shown = 0;
  /** Each time the card beside the menu showed or hid. */
  const cardShown: boolean[] = [];
  /** The side of the menu it showed on each time, `null` when it hid. */
  const cardSides: (string | null)[] = [];
  /** Each time it hid, whether for lack of room beside the menu. */
  const cardNoRoom: boolean[] = [];
  /** What the UI reported of the menu's and the card's frames, in order. */
  const reports: PopupShown[] = [];
  // The closed shadow root, which only the content script holds.
  const attach = vi.spyOn(Element.prototype, "attachShadow");
  const ui: ContentUi = new ContentUi(
    document,
    {
      iconClicked: () => undefined,
      arrowDownPressed: (field) => options.arrowDownPressed?.(ui, field) ?? false,
      menuFocused: () => focused++,
      menuDismissed: () => {
        dismissed++;
        ui.closeMenu();
      },
      popupShown: (report) => {
        reports.push(report);
        if (report.popup === "menu") {
          shown++;
          return;
        }
        cardShown.push(report.shown);
        cardSides.push(report.side ?? null);
        if (!report.shown) cardNoRoom.push(report.room === false);
      },
    },
    () => "about:blank",
    // The about:blank frames stand in for the extension's pages.
    EXTENSION_ORIGINS,
    "Keyguard",
    options.direction,
  );
  const shadow = attach.mock.results[0]!.value as ShadowRoot;
  attach.mockRestore();
  uis.push(ui);
  return {
    ui,
    shadow,
    icon: shadow.querySelector<HTMLElement>(".icon")!,
    dismissed: () => dismissed,
    focused: () => focused,
    shown: () => shown,
    cardShown,
    cardSides,
    cardNoRoom,
    reports,
  };
}

/** Sets the window's size. */
export function viewport(width: number, height: number): void {
  Object.defineProperty(window, "innerWidth", { configurable: true, value: width });
  Object.defineProperty(window, "innerHeight", { configurable: true, value: height });
}
