// @vitest-environment happy-dom
// The extension's UI in a page (src/lib/content/ui.ts): where its host goes
// for a field in a modal dialog, how the menu fits a small frame, how it
// follows its field, and the keyboard path into the menu.
// happy-dom has no layout, top layer or `:modal`: the tests give the boxes and
// say which dialogs are modal. Animation frames run when a test says so.
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ContentUi } from "../src/lib/content/ui";
import { BIND_MESSAGE } from "../src/lib/page/messages";
import {
  frameColors,
  THEME_TOKENS,
  type FrameColors,
  type StoredTheme,
  type ThemeTokens,
} from "../src/lib/ui/storedTheme";
import { EXTENSION_ORIGINS, frames, runFrame, setup, useContentUiHooks, viewport } from "./support/contentUi";
import { byId, render } from "./support/dom";

useContentUiHooks();

/** Opens [dialog] with `showModal()`; it then matches `:modal`, as in a browser. */
function showModal(dialog: HTMLDialogElement) {
  const matches = dialog.matches.bind(dialog);
  Object.defineProperty(dialog, "matches", {
    configurable: true,
    value: (selector: string) => (selector === ":modal" ? dialog.open : matches(selector)),
  });
  dialog.showModal();
}

async function mutations() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe("placement", () => {
  it("hosts the UI in a built-in element, which a page can't define first to reach the closed root", () => {
    // A page's custom element of the name would get the root through ElementInternals.
    class Squatter extends HTMLElement {}
    customElements.define("keyguard-ui", Squatter);
    const { ui } = setup();
    expect(ui.host.localName).toBe("div");
    expect(ui.host).not.toBeInstanceOf(Squatter);
    expect(ui.host.getAttribute("style")).toContain("all: initial !important");
    expect(ui.host.shadowRoot).toBeNull();
  });

  it("puts the host in the modal dialog of the field, through shadow roots, and back at the root after", async () => {
    render(`<input id="page"><dialog id="dialog"><x-login id="login"></x-login></dialog>`);
    const shadow = byId<HTMLElement>("login").attachShadow({ mode: "open" });
    shadow.innerHTML = `<input id="field">`;
    const field = byId("field", shadow);
    const dialog = byId<HTMLDialogElement>("dialog");
    const { ui } = setup();

    ui.showIcon(byId("page"));
    expect(ui.host.parentNode).toBe(document.documentElement);
    showModal(dialog);
    ui.showIcon(field);
    expect(ui.host.parentNode).toBe(dialog);
    expect(ui.iconTarget).toBe(field);
    ui.openMenu(field, "token", "inline-menu.html");
    expect(ui.host.parentNode).toBe(dialog);
    expect(ui.menuField).toBe(field);

    dialog.close();
    await mutations();
    ui.showIcon(byId("page"));
    expect(ui.host.parentNode).toBe(document.documentElement);
    expect(ui.iconTarget).toBe(byId("page"));
  });

  it("takes the innermost of nested modal dialogs, and a dialog in a shadow root the field is slotted into", () => {
    render(`
      <dialog id="outer"><dialog id="inner"><input id="nested"></dialog></dialog>
      <x-dialog id="component"><input id="slotted"></x-dialog>`);
    const { ui } = setup();
    showModal(byId<HTMLDialogElement>("outer"));
    showModal(byId<HTMLDialogElement>("inner"));
    ui.showIcon(byId("nested"));
    expect(ui.host.parentNode).toBe(byId("inner"));

    const shadow = byId<HTMLElement>("component").attachShadow({ mode: "open" });
    shadow.innerHTML = `<dialog id="shadowed"><slot></slot></dialog>`;
    // happy-dom doesn't assign slots.
    Object.defineProperty(byId("slotted"), "assignedSlot", { value: shadow.querySelector("slot") });
    showModal(byId<HTMLDialogElement>("shadowed", shadow));
    ui.showIcon(byId("slotted"));
    expect(ui.host.parentNode).toBe(byId("shadowed", shadow));
  });

  it("tells focus leaving its frames in a page's shadow root, open or closed, which the document hears from the page's element", () => {
    for (const mode of ["open", "closed"] as const) {
      render(`<x-login id="login"></x-login>`);
      const page = byId<HTMLElement>("login").attachShadow({ mode });
      page.innerHTML = `<dialog id="dialog"><input id="field"></dialog>`;
      const field = byId("field", page);
      const { ui, shadow } = setup();
      showModal(byId<HTMLDialogElement>("dialog", page));
      ui.openMenu(field, "token", "inline-menu.html", { token: "d".repeat(32), page: "menu-details.html" });
      expect(ui.host.parentNode).toBe(byId("dialog", page));
      const heard: FocusEvent[] = [];
      const listening = new AbortController();
      document.addEventListener("focusout", (event) => heard.push(event), { capture: true, signal: listening.signal });
      // From the menu's frame and the card's, then from the field.
      const frames = Array.from(shadow.querySelectorAll("iframe"));
      expect(frames).toHaveLength(2);
      for (const target of [...frames, field]) {
        target.dispatchEvent(new FocusEvent("focusout", { bubbles: true, composed: true }));
      }
      listening.abort();
      // A browser retargets each to the page's element, which happy-dom doesn't.
      expect(heard).toHaveLength(3);
      expect(heard.map((event) => ui.leftUi(event))).toEqual([true, true, false]);
      ui.destroy();
    }
  });

  it("keeps the host at the root for a dialog that isn't modal", () => {
    render(`<dialog id="dialog"><input id="field"></dialog>`);
    const { ui } = setup();
    byId<HTMLDialogElement>("dialog").show();
    ui.showIcon(byId("field"));
    expect(ui.host.parentNode).toBe(document.documentElement);
  });

  it("takes the UI down when the page closes the dialog or moves the host", async () => {
    render(`<dialog id="dialog"><input id="field"></dialog>`);
    const dialog = byId<HTMLDialogElement>("dialog");
    const field = byId("field");
    const { ui, dismissed } = setup();
    showModal(dialog);
    ui.showIcon(field);
    ui.openMenu(field, "token", "inline-menu.html");
    // Closed and reopened at once: it's over the menu now.
    dialog.close();
    showModal(dialog);
    await mutations();
    expect(ui.iconTarget).toBeNull();
    expect(ui.menuField).toBeNull();
    expect(dismissed()).toBe(1);

    ui.showIcon(field);
    expect(ui.iconTarget).toBe(field);
    document.body.append(ui.host);
    await mutations();
    expect(ui.iconTarget).toBeNull();
    // Shown again, the host goes back.
    ui.showIcon(field);
    expect(ui.host.parentNode).toBe(dialog);
  });

  it("takes the UI down when the page closes a dialog around the host's own", async () => {
    render(`<dialog id="outer"><dialog id="inner"><input id="field"></dialog></dialog>`);
    const outer = byId<HTMLDialogElement>("outer");
    const field = byId("field");
    const { ui, dismissed } = setup();
    showModal(outer);
    showModal(byId<HTMLDialogElement>("inner"));
    ui.showIcon(field);
    ui.openMenu(field, "token", "inline-menu.html");
    expect(ui.host.parentNode).toBe(byId("inner"));
    // Closed and reopened at once: it's over the menu now.
    outer.close();
    showModal(outer);
    await mutations();
    expect(ui.iconTarget).toBeNull();
    expect(ui.menuField).toBeNull();
    expect(dismissed()).toBe(1);
  });

  it("keeps the host at the root while the save bar shows, without an icon it would make inert", () => {
    render(`<dialog id="dialog"><input id="field"></dialog>`);
    const { ui } = setup();
    ui.openSaveBar("token", "save-bar.html");
    showModal(byId<HTMLDialogElement>("dialog"));
    ui.showIcon(byId("field"));
    expect(ui.host.parentNode).toBe(document.documentElement);
    expect(ui.iconTarget).toBeNull();
    ui.closeSaveBar();
    ui.showIcon(byId("field"));
    expect(ui.host.parentNode).toBe(byId("dialog"));
  });
});

describe("menu size", () => {
  it("fits the menu in a small frame, on the side of the field with more room", () => {
    render(`<input id="field">`);
    const field = byId("field");
    let box = { left: 10, top: 100, width: 200, height: 30 };
    field.getBoundingClientRect = () =>
      ({ ...box, right: box.left + box.width, bottom: box.top + box.height }) as DOMRect;
    const { ui } = setup();
    // A 300×250 embedded sign-in frame.
    viewport(300, 250);
    ui.openMenu(field, "token", "inline-menu.html");
    ui.resizeMenu(360);
    const style = ui.menuElement!.style;
    expect(style.top).toBe("134px");
    expect(style.height).toBe("112px");

    box = { ...box, top: 190 };
    ui.resizeMenu(360);
    expect(style.top).toBe("4px");
    expect(style.height).toBe("182px");

    // No room on either side: still one row.
    viewport(300, 60);
    box = { ...box, top: 15 };
    ui.resizeMenu(360);
    expect(style.height).toBe("48px");

    viewport(1024, 768);
    ui.resizeMenu(360);
    expect(style.height).toBe("360px");
  });
});

describe("showing the menu", () => {
  /**
   * Whether the menu is hidden, and its popover and frame (which resets what it
   * inherits) take no clicks. happy-dom lets the host's `visibility: visible
   * !important` win over the hidden menu's own rule, which a browser doesn't:
   * the class stands for the visibility.
   */
  function hidden(ui: ContentUi): boolean[] {
    const element = ui.menuElement!;
    const noClicks = [element, element.querySelector("iframe")!].map(
      (it) => getComputedStyle(it).pointerEvents === "none",
    );
    return [element.classList.contains("pending"), ...noClicks];
  }

  it("keeps the menu in the top layer but hidden until its page tells its height, then shows it at that height", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    render(`<input id="field">`);
    viewport(1024, 768);
    const { ui, shown } = setup();
    ui.openMenu(byId("field"), "token", "inline-menu.html");
    // Shown as a box over the page: happy-dom has no popovers.
    expect(getComputedStyle(ui.menuElement!).display).toBe("block");
    expect(hidden(ui)).toEqual([true, true, true]);
    expect(shown()).toBe(0);

    ui.resizeMenu(150);
    expect(hidden(ui)).toEqual([false, false, false]);
    expect(ui.menuElement!.style.height).toBe("150px");
    expect(shown()).toBe(1);
    // Later sizes only size it.
    ui.resizeMenu(200);
    vi.advanceTimersByTime(1000);
    expect(ui.menuElement!.style.height).toBe("200px");
    expect(shown()).toBe(1);
  });

  it("shows the menu after 300 ms without its height, and never a menu that closed", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    render(`<input id="field">`);
    const { ui, shown } = setup();
    ui.openMenu(byId("field"), "token", "inline-menu.html");
    vi.advanceTimersByTime(299);
    expect(hidden(ui)).toEqual([true, true, true]);
    vi.advanceTimersByTime(1);
    expect(hidden(ui)).toEqual([false, false, false]);
    expect(shown()).toBe(1);

    ui.openMenu(byId("field"), "token", "inline-menu.html");
    expect(hidden(ui)).toEqual([true, true, true]);
    ui.closeMenu();
    vi.advanceTimersByTime(1000);
    expect(shown()).toBe(1);
  });
});

describe("save bar size", () => {
  it("sizes the save bar to its content, within its bounds and the window", () => {
    viewport(1024, 768);
    const { ui, shadow } = setup();
    ui.openSaveBar("token", "save-bar.html");
    const bar = shadow.querySelector<HTMLElement>(".popup.save-bar")!;
    // Until its page tells its height.
    expect([bar.style.width, bar.style.height, bar.style.top, bar.style.right]).toEqual([
      "400px",
      "204px",
      "16px",
      "16px",
    ]);

    ui.resizeSaveBar(203.4);
    expect(bar.style.height).toBe("203px");
    ui.resizeSaveBar(10);
    expect(bar.style.height).toBe("96px");
    ui.resizeSaveBar(5000);
    expect(bar.style.height).toBe("400px");

    // A small window: within its margins, and it follows the window.
    viewport(300, 200);
    window.dispatchEvent(new Event("resize"));
    expect([bar.style.width, bar.style.height]).toEqual(["268px", "168px"]);

    ui.closeSaveBar();
    ui.resizeSaveBar(200);
    expect(shadow.querySelector(".popup.save-bar")).toBeNull();
  });
});

describe("frame color", () => {
  const device = (window as unknown as { happyDOM: { settings: { device: { prefersColorScheme: string } } } }).happyDOM
    .settings.device;
  afterEach(() => {
    device.prefersColorScheme = "light";
  });

  /** A stored theme whose frame color is [surfaceContainer]. */
  function theme(dark: boolean, surfaceContainer: string): StoredTheme {
    const tokens = Object.fromEntries(THEME_TOKENS.map((token) => [token, "#000000"])) as ThemeTokens;
    return { v: 1, dark, tokens: { ...tokens, "surface-container": surfaceContainer } };
  }

  /** The background of the save bar's frame, on a page that prefers [scheme]. */
  function frameOn(scheme: "light" | "dark", colors?: FrameColors): string {
    device.prefersColorScheme = scheme;
    const { ui, shadow } = setup();
    if (colors !== undefined) ui.setFrameColors(colors);
    ui.openSaveBar("token", "save-bar.html");
    return getComputedStyle(shadow.querySelector<HTMLElement>(".popup.save-bar")!).backgroundColor;
  }

  it("is Keyguard's surface container without a theme", () => {
    expect(frameOn("light")).toBe("#f5f5f5");
    expect(frameOn("dark")).toBe("#202121");
    expect(frameOn("light", frameColors(null))).toBe("#f5f5f5");
    expect(frameOn("dark", frameColors(null))).toBe("#202121");
  });

  it("is a dark theme's on every page, and a light theme's on a light page only", () => {
    const dark = frameColors(theme(true, "#2b2640"));
    expect(frameOn("light", dark)).toBe("#2b2640");
    expect(frameOn("dark", dark)).toBe("#2b2640");
    const light = frameColors(theme(false, "#f4fbf6"));
    expect(frameOn("light", light)).toBe("#f4fbf6");
    expect(frameOn("dark", light)).toBe("#202121");
  });

  it("follows the theme while a frame shows", () => {
    const { ui, shadow } = setup();
    ui.openSaveBar("token", "save-bar.html");
    const bar = shadow.querySelector<HTMLElement>(".popup.save-bar")!;
    ui.setFrameColors(frameColors(theme(false, "#f4fbf6")));
    expect(getComputedStyle(bar).backgroundColor).toBe("#f4fbf6");
    ui.setFrameColors(frameColors(null));
    expect(getComputedStyle(bar).backgroundColor).toBe("#f5f5f5");
  });

  it("takes nothing but #rrggbb into its style sheet", () => {
    expect(frameOn("light", { light: "#000; background-image: url(https://example.com)", dark: null })).toBe("#f5f5f5");
    expect(frameOn("dark", { light: null, dark: "var(--surface-container)" })).toBe("#202121");
    expect(frameOn("light", { light: "#FFF", dark: null })).toBe("#f5f5f5");
  });
});

describe("following the field", () => {
  function boxOf(field: HTMLElement, box: { left: number; top: number; width: number; height: number }) {
    field.getBoundingClientRect = () => new DOMRect(box.left, box.top, box.width, box.height);
  }

  it("moves the icon and the menu with a field the page moves without a scroll, on the next frame", () => {
    render(`<input id="field">`);
    const field = byId("field");
    boxOf(field, { left: 10, top: 100, width: 200, height: 30 });
    viewport(1024, 768);
    const { ui, icon } = setup();
    ui.showIcon(field);
    ui.openMenu(field, "token", "inline-menu.html");
    expect(icon.style.top).toBe("105px");
    expect(ui.menuElement!.style.top).toBe("134px");

    // A message shows above the field.
    boxOf(field, { left: 10, top: 140, width: 200, height: 30 });
    runFrame();
    expect(icon.style.top).toBe("145px");
    expect(ui.menuElement!.style.top).toBe("174px");
  });

  it("hides the icon of a field without a box, and stops once nothing shows, the page is hidden or the UI goes", () => {
    render(`<input id="field">`);
    const field = byId("field");
    const { ui } = setup();
    ui.showIcon(field);
    runFrame();
    expect(frames.size).toBe(1);

    // `display: none`.
    boxOf(field, { left: 0, top: 0, width: 0, height: 0 });
    runFrame();
    expect(ui.iconTarget).toBeNull();
    expect(frames.size).toBe(0);

    boxOf(field, { left: 10, top: 100, width: 200, height: 30 });
    ui.openMenu(field, "token", "inline-menu.html");
    runFrame();
    expect(frames.size).toBe(1);
    ui.closeMenu();
    runFrame();
    expect(frames.size).toBe(0);

    ui.showIcon(field);
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    runFrame();
    expect(frames.size).toBe(0);
    delete (document as { hidden?: boolean }).hidden;
    document.dispatchEvent(new Event("visibilitychange"));
    expect(frames.size).toBe(1);

    ui.destroy();
    expect(frames.size).toBe(0);
  });

  /** A ResizeObserver whose callback a test calls. */
  class FakeResizeObserver {
    static last: FakeResizeObserver | null = null;
    readonly observed = new Set<Element>();
    constructor(private readonly callback: () => void) {
      FakeResizeObserver.last = this;
    }
    observe(target: Element) {
      this.observed.add(target);
    }
    unobserve(target: Element) {
      this.observed.delete(target);
    }
    disconnect() {
      this.observed.clear();
    }
    resized() {
      this.callback();
    }
  }

  /** Runs animation frames while more are asked for; how many ran. */
  function framesUntilIdle(): number {
    let count = 0;
    for (; frames.size > 0 && count < 1000; count++) runFrame();
    return count;
  }

  it("checks every frame while the field moves, then every 500 ms once it stays still for 30 frames", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    render(`<input id="field">`);
    const field = byId("field");
    boxOf(field, { left: 10, top: 100, width: 200, height: 30 });
    viewport(1024, 768);
    const { ui, icon } = setup();
    ui.showIcon(field);
    // The first frame places the UI, then 30 frames without a move.
    expect(framesUntilIdle()).toBe(31);

    vi.advanceTimersByTime(499);
    expect(frames.size).toBe(0);
    vi.advanceTimersByTime(1);
    expect(frames.size).toBe(0);

    // The page moves the field without an event: the next check sees it, and checks every frame again.
    boxOf(field, { left: 10, top: 140, width: 200, height: 30 });
    vi.advanceTimersByTime(500);
    expect(icon.style.top).toBe("145px");
    expect(frames.size).toBe(1);
    expect(framesUntilIdle()).toBe(30);

    // Hidden: no more checks.
    ui.hideIcon();
    vi.advanceTimersByTime(5000);
    expect(frames.size).toBe(0);
  });

  it("checks every frame again on a scroll, a resize, a focus change, a change of the page or of the field's size", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    vi.stubGlobal("ResizeObserver", FakeResizeObserver);
    render(`<div id="scroller"><input id="field"></div><input id="other">`);
    const field = byId("field");
    const { ui } = setup();
    ui.showIcon(field);
    framesUntilIdle();
    const observer = FakeResizeObserver.last!;
    expect([...observer.observed]).toEqual([field]);

    for (const [name, wake] of [
      ["scroll of an element", () => byId("scroller").dispatchEvent(new Event("scroll"))],
      ["scroll of the page", () => document.dispatchEvent(new Event("scroll"))],
      ["resize of the window", () => window.dispatchEvent(new Event("resize"))],
      ["focus change", () => byId("other").focus()],
      ["change of the page", () => ui.pageChanged()],
      ["resize of the field", () => observer.resized()],
    ] as const) {
      expect(frames.size, name).toBe(0);
      wake();
      expect(frames.size, name).toBe(1);
      expect(framesUntilIdle(), name).toBe(30);
    }

    // The menu's field is followed too; once nothing shows, nothing wakes the loop.
    const menuField = byId("other");
    ui.openMenu(menuField, "token", "inline-menu.html");
    expect([...observer.observed]).toEqual([field, menuField]);
    ui.hideIcon();
    expect([...observer.observed]).toEqual([menuField]);
    ui.closeMenu();
    expect(observer.observed.size).toBe(0);
    window.dispatchEvent(new Event("resize"));
    ui.pageChanged();
    expect(frames.size).toBe(0);
  });
});

describe("keyboard", () => {
  /** A key pressed in [target]; by the user unless [trusted] is false, as the page's own events are. */
  function press(
    target: Element,
    key: string,
    { trusted = true, ...init }: KeyboardEventInit & { trusted?: boolean } = {},
  ) {
    const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true, composed: true, ...init });
    if (trusted) Object.defineProperty(event, "isTrusted", { value: true });
    target.dispatchEvent(event);
    return event;
  }

  /** The menu's page loaded, and got the token. */
  function loadMenu(ui: ContentUi) {
    ui.menuElement!.querySelector("iframe")!.dispatchEvent(new Event("load"));
  }

  it("opens the menu on a trusted ArrowDown in the field with the icon, and moves focus into it once it loads", () => {
    render(`<input id="field"><input id="other">`);
    const field = byId("field");
    let opens = true;
    // As the content script, once the background answers.
    const { ui, focused } = setup({
      arrowDownPressed: (ui, field) => {
        if (!opens) return false;
        ui.openMenu(field, "token", "inline-menu.html");
        ui.focusMenu();
        return true;
      },
    });
    ui.showIcon(field);
    field.focus();

    // The page's own events, a key with a modifier, another field.
    expect(press(field, "ArrowDown", { trusted: false }).defaultPrevented).toBe(false);
    expect(press(field, "ArrowDown", { altKey: true }).defaultPrevented).toBe(false);
    byId("other").focus();
    expect(press(byId("other"), "ArrowDown").defaultPrevented).toBe(false);
    expect(ui.menuField).toBeNull();
    // The content script doesn't open one: no role, or the option is off.
    field.focus();
    opens = false;
    expect(press(field, "ArrowDown").defaultPrevented).toBe(false);

    opens = true;
    expect(press(field, "ArrowDown").defaultPrevented).toBe(true);
    expect(ui.menuField).toBe(field);
    // Focus stays in the field till the menu's page loaded and the menu shows.
    expect(document.activeElement).toBe(field);
    expect(focused()).toBe(0);
    loadMenu(ui);
    expect(document.activeElement).toBe(field);
    // The page tells its height.
    ui.resizeMenu(200);
    expect(document.activeElement).toBe(ui.host);
    // Then the background has the page focus its first row.
    expect(focused()).toBe(1);

    // A menu opened by a click: the key moves focus into it.
    ui.closeMenu();
    ui.openMenu(field, "token", "inline-menu.html");
    loadMenu(ui);
    ui.resizeMenu(200);
    expect(document.activeElement).toBe(field);
    expect(press(field, "ArrowDown").defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(ui.host);
  });

  it("puts focus back on the field when the menu closes with focus in it: Escape, Tab or an action", () => {
    render(`<input id="field"><input id="other">`);
    const field = byId("field");
    const { ui } = setup();
    ui.showIcon(field);
    field.focus();
    ui.openMenu(field, "token", "inline-menu.html");
    ui.focusMenu();
    loadMenu(ui);
    ui.resizeMenu(200);
    expect(document.activeElement).toBe(ui.host);
    // The menu's page asks the background, which closes it here.
    ui.closeMenu();
    expect(document.activeElement).toBe(field);
    expect(ui.iconTarget).toBe(field);

    // Closed with focus elsewhere: it stays there.
    ui.openMenu(field, "token", "inline-menu.html");
    byId("other").focus();
    ui.closeMenu();
    expect(document.activeElement).toBe(byId("other"));
  });
});

describe("binding the pages", () => {
  /** The popover's frame, whose page posts are spied on. */
  function frameOf(popup: HTMLElement) {
    const frame = popup.querySelector("iframe")!;
    const post = vi.spyOn(frame.contentWindow!, "postMessage").mockImplementation(() => undefined);
    return { frame, post };
  }

  it("posts the token to the frame of the menu or the save bar once it loads, for the extension's origins only", () => {
    render(`<input id="field">`);
    const { ui } = setup();
    ui.openMenu(byId("field"), "a".repeat(32), "inline-menu.html");
    const menu = frameOf(ui.menuElement!);
    expect(menu.post).not.toHaveBeenCalled();
    menu.frame.dispatchEvent(new Event("load"));
    expect(menu.post.mock.calls).toEqual(
      EXTENSION_ORIGINS.map((origin) => [{ type: BIND_MESSAGE, token: "a".repeat(32) }, origin]),
    );

    ui.openSaveBar("b".repeat(32), "save-bar.html");
    const bar = frameOf(ui.saveBarElement!);
    bar.frame.dispatchEvent(new Event("load"));
    expect(bar.post.mock.calls).toEqual(
      EXTENSION_ORIGINS.map((origin) => [{ type: BIND_MESSAGE, token: "b".repeat(32) }, origin]),
    );
    // Never to any origin, which a frame the page navigated would have.
    for (const [, origin] of [...menu.post.mock.calls, ...bar.post.mock.calls]) expect(origin).not.toBe("*");
  });

  it("answers no message of the page or of the frame: the frame says nothing to the page", () => {
    render(`<input id="field">`);
    const { ui } = setup();
    ui.openMenu(byId("field"), "a".repeat(32), "inline-menu.html");
    const { frame, post } = frameOf(ui.menuElement!);
    const source = frame.contentWindow!;
    window.dispatchEvent(
      new MessageEvent("message", { data: { type: "keyguard:ready" }, origin: EXTENSION_ORIGINS[1], source }),
    );
    expect(post).not.toHaveBeenCalled();
  });

  it("posts nothing once the popup closed", () => {
    render(`<input id="field">`);
    const { ui } = setup();
    ui.openMenu(byId("field"), "a".repeat(32), "inline-menu.html");
    const { frame, post } = frameOf(ui.menuElement!);
    ui.closeMenu();
    frame.dispatchEvent(new Event("load"));
    expect(post).not.toHaveBeenCalled();
  });
});

describe("right-to-left fields", () => {
  it("puts the icon at the left end of a right-to-left field", () => {
    render(`<input id="ltr"><input id="rtl" dir="rtl" style="direction: rtl">`);
    const { ui, icon } = setup();
    ui.showIcon(byId("ltr"));
    // The field's box is 10, 10, 200 by 32: the icon goes inside its end.
    expect(icon.style.left).toBe(`${10 + 200 - 20 - 6}px`);
    ui.showIcon(byId("rtl"));
    expect(icon.style.left).toBe(`${10 + 6}px`);
  });
});

describe("inert", () => {
  it("takes `inert` off the host, and leaves the page's own", async () => {
    render(`<input id="field">`);
    const field = byId("field");
    const { ui } = setup();
    ui.showIcon(field);
    ui.host.setAttribute("inert", "");
    document.body.setAttribute("inert", "");
    await mutations();
    expect(ui.host.hasAttribute("inert")).toBe(false);
    expect(document.body.hasAttribute("inert")).toBe(true);
    // The UI stays.
    expect(ui.iconTarget).toBe(field);
    document.body.removeAttribute("inert");
  });
});
