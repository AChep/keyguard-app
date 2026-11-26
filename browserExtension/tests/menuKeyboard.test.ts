// @vitest-environment happy-dom
// The keyboard of the inline menu (src/lib/ui/menuKeyboard.ts), on its rows
// as the menu page renders them.
import { afterEach, describe, expect, it, vi } from "vitest";
import { ClickGate, MIN_VISIBLE_MS } from "../src/lib/ui/clickGate";
import { MenuFocus, onDetailsKeydown, onMenuKeydown, onMenuKeyup, towardCard } from "../src/lib/ui/menuKeyboard";
import type { DetailsSide } from "../src/lib/page/messages";
import { byId, render } from "./support/dom";

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
  document.body.innerHTML = "";
});

/** A key event of [type], trusted as the user's unless [trusted] is false. */
function key(type: "keydown" | "keyup", key: string, { trusted = true, repeat = false } = {}) {
  const event = new KeyboardEvent(type, { key, repeat, bubbles: true, cancelable: true });
  if (trusted) Object.defineProperty(event, "isTrusted", { value: true });
  return event;
}

function menu(rows: string, rtl = false, side: DetailsSide | null = null) {
  render(`<div id="menu">${rows}</div>`);
  let closed = 0;
  /** The login whose card shows, as the menu page keeps it. */
  let shown: string | null = null;
  const details: string[] = [];
  const root = byId<HTMLElement>("menu");
  // As the menu page wires it.
  const focus = new MenuFocus();
  root.addEventListener("focusin", (event) => focus.focusIn(event.target));
  const press = (name: string, options?: { trusted?: boolean; repeat?: boolean }) => {
    const event = key("keydown", name, options);
    onMenuKeydown(event, root, {
      close: () => closed++,
      details: shown,
      select: (handle) => (shown = handle),
      focusDetails: (handle) => details.push(handle),
      rtl,
      side,
    });
    return event;
  };
  const release = (name: string) => {
    const event = key("keyup", name);
    onMenuKeyup(event, root);
    return event;
  };
  return {
    root,
    press,
    release,
    focus,
    closed: () => closed,
    details,
    shown: () => shown,
    show: (handle: string | null) => (shown = handle),
  };
}

describe("menu keyboard", () => {
  it("moves between the enabled rows with the arrows, and scrolls the focused one into view", () => {
    const { press } = menu(`<button id="a"></button><button id="b" disabled></button><button id="c"></button>`);
    const scrolled = vi.spyOn(Element.prototype, "scrollIntoView");
    expect(press("ArrowUp").defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(byId("c"));
    expect(scrolled.mock.contexts.at(-1)).toBe(byId("c"));
    press("ArrowDown");
    expect(document.activeElement).toBe(byId("a"));
    press("ArrowDown");
    expect(document.activeElement).toBe(byId("c"));
    expect(scrolled.mock.contexts.at(-1)).toBe(byId("c"));
  });

  it("closes on Escape and on Tab, which would leave for the page", () => {
    const { press, closed } = menu(`<button id="a"></button>`);
    byId("a").focus();
    press("Escape");
    expect(press("Tab").defaultPrevented).toBe(true);
    expect(closed()).toBe(2);
    expect(document.activeElement).toBe(byId("a"));
  });

  it("moves no row into focus when only the frame gets focus, which the page can give it: Enter then presses nothing", () => {
    const { root, press, closed, focus } = menu(``);
    let clicks = 0;
    root.addEventListener("click", () => clicks++);
    // The page focuses the frame's window, before and after the rows show.
    focus.settle(root);
    root.innerHTML = `<button id="a"></button><button id="b"></button>`;
    focus.settle(root);
    expect(document.activeElement).toBe(document.body);
    expect(press("Enter").defaultPrevented).toBe(false);
    expect(closed()).toBe(0);
    expect(clicks).toBe(0);
  });

  it("focuses the first row on the background's request, at once or once it shows, and keeps the row focused after an action", () => {
    const { root, focus } = menu(``);
    focus.request(root);
    expect(document.activeElement).toBe(document.body);
    root.innerHTML = `<button id="a" disabled></button><button id="b"></button><button id="c"></button>`;
    focus.settle(root);
    expect(document.activeElement).toBe(byId("b"));
    // Not over a row the user moved to.
    byId("c").focus();
    focus.settle(root);
    expect(document.activeElement).toBe(byId("c"));
    // An action disabled the rows, and focus fell to the body.
    byId("c").blur();
    focus.settle(root);
    expect(document.activeElement).toBe(byId("c"));
    // Focus left the frame: when the page brings it back, no row takes it.
    byId("c").blur();
    focus.blur();
    focus.settle(root);
    expect(document.activeElement).toBe(document.body);
  });

  it("focuses the first row after an action when the row that had focus is gone or disabled", () => {
    const { root, focus } = menu(`<button id="a"></button><button id="b"></button><button id="c"></button>`);
    focus.request(root);
    byId("c").focus();
    // The action's view has no such row any more.
    byId("c").remove();
    focus.settle(root);
    expect(document.activeElement).toBe(byId("a"));

    byId("b").focus();
    byId("b").blur();
    byId<HTMLButtonElement>("b").disabled = true;
    focus.settle(root);
    expect(document.activeElement).toBe(byId("a"));
  });

  it("keeps a request that came before the frame had focus till it does, and drops it when focus leaves", () => {
    const { root, focus } = menu(`<button id="a"></button>`);
    const hasFocus = vi.spyOn(document, "hasFocus").mockReturnValue(false);
    focus.request(root);
    expect(document.activeElement).toBe(document.body);
    hasFocus.mockReturnValue(true);
    focus.settle(root);
    expect(document.activeElement).toBe(byId("a"));

    byId("a").blur();
    focus.blur();
    hasFocus.mockReturnValue(false);
    focus.request(root);
    focus.blur();
    hasFocus.mockReturnValue(true);
    focus.settle(root);
    expect(document.activeElement).toBe(document.body);
  });

  it("moves focus into the card on ArrowRight, ArrowLeft right to left, only on the row of the login it shows", () => {
    const rows = `<button id="a" data-handle="h1"></button><button id="b" data-handle="h2"></button><button id="c"></button>`;
    const ltr = menu(rows);
    expect(ltr.press("ArrowRight").defaultPrevented).toBe(false);
    byId("a").focus();
    // No card: the key does nothing.
    expect(ltr.press("ArrowRight").defaultPrevented).toBe(false);
    expect(ltr.details).toEqual([]);
    ltr.show("h1");
    expect(ltr.press("ArrowLeft").defaultPrevented).toBe(false);
    expect(ltr.press("ArrowRight").defaultPrevented).toBe(true);
    expect(ltr.details).toEqual(["h1"]);
    // Not on another login's row, nor on a row that isn't a login's, as Fill TOTP.
    for (const id of ["b", "c"]) {
      byId(id).focus();
      expect(ltr.press("ArrowRight").defaultPrevented).toBe(false);
    }
    expect(ltr.details).toEqual(["h1"]);

    const rtl = menu(rows, true);
    rtl.show("h1");
    byId("a").focus();
    rtl.press("ArrowRight");
    expect(rtl.press("ArrowLeft").defaultPrevented).toBe(true);
    expect(rtl.details).toEqual(["h1"]);
  });

  it("moves focus into the card with the arrow toward the side it shows on, whatever the direction", () => {
    const rows = `<button id="a" data-handle="h1"></button>`;
    // No room after the menu: the card shows before it.
    const ltr = menu(rows, false, "left");
    ltr.show("h1");
    byId("a").focus();
    expect(ltr.press("ArrowRight").defaultPrevented).toBe(false);
    expect(ltr.press("ArrowLeft").defaultPrevented).toBe(true);
    expect(ltr.details).toEqual(["h1"]);

    const rtl = menu(rows, true, "right");
    rtl.show("h1");
    byId("a").focus();
    expect(rtl.press("ArrowLeft").defaultPrevented).toBe(false);
    expect(rtl.press("ArrowRight").defaultPrevented).toBe(true);
    expect(rtl.details).toEqual(["h1"]);
  });

  it("shows a login's card on Space on its row, and hides it on Space again: the row's click doesn't fill", () => {
    const { root, press, release, shown } = menu(
      `<button id="a" data-handle="h1"></button><button id="b" data-handle="h2"></button>`,
    );
    let clicks = 0;
    root.addEventListener("click", () => clicks++);
    byId("a").focus();
    // Both the key and its release: a browser may click on either.
    expect(press(" ").defaultPrevented).toBe(true);
    expect(release(" ").defaultPrevented).toBe(true);
    expect(shown()).toBe("h1");
    // A held key repeats: once per press.
    expect(press(" ", { repeat: true }).defaultPrevented).toBe(true);
    expect(shown()).toBe("h1");
    press(" ");
    expect(shown()).toBeNull();
    // Another login's row shows its own.
    press(" ");
    byId("b").focus();
    press(" ");
    expect(shown()).toBe("h2");
    // Only the user's key: a dispatched one shows nothing, nor fills.
    byId("a").focus();
    expect(press(" ", { trusted: false }).defaultPrevented).toBe(true);
    expect(shown()).toBe("h2");
    expect(clicks).toBe(0);
    // Enter is left to the row, which fills.
    expect(press("Enter").defaultPrevented).toBe(false);
  });

  it("hides the card as ArrowDown or ArrowUp moves focus to another row", () => {
    const { press, shown, show } = menu(`<button id="a" data-handle="h1"></button><button id="b"></button>`);
    byId("a").focus();
    show("h1");
    press("ArrowDown");
    expect(document.activeElement).toBe(byId("b"));
    expect(shown()).toBeNull();
    press("ArrowUp");
    press(" ");
    expect(shown()).toBe("h1");
    press("ArrowUp");
    expect(shown()).toBeNull();

    // The only row: focus stays on it, and so does the card.
    const single = menu(`<button id="a" data-handle="h1"></button>`);
    byId("a").focus();
    single.show("h1");
    single.press("ArrowDown");
    expect(single.shown()).toBe("h1");
  });

  it("hides the card on Escape, then closes the menu on the next", () => {
    const { press, closed, shown, show } = menu(`<button id="a" data-handle="h1"></button>`);
    show("h1");
    byId("a").focus();
    expect(press("Escape").defaultPrevented).toBe(true);
    expect(shown()).toBeNull();
    expect(closed()).toBe(0);
    expect(press("Escape").defaultPrevented).toBe(true);
    expect(closed()).toBe(1);
  });

  it("points toward the card by its side, else by the direction", () => {
    expect(towardCard(null, false)).toBe("ArrowRight");
    expect(towardCard(undefined, true)).toBe("ArrowLeft");
    expect(towardCard("left", false)).toBe("ArrowLeft");
    expect(towardCard("right", true)).toBe("ArrowRight");
  });

  it("leaves Enter to the rows, and Space to those that aren't a login's: their click goes through the click gate", () => {
    const { press, release, closed, shown } = menu(`<button id="a"></button>`);
    let now = 0;
    const gate = new ClickGate(() => now);
    gate.setVisible(true);
    let acted = 0;
    // As the menu page's rows do.
    byId("a").addEventListener("click", (event) => {
      if (gate.allows(event)) acted++;
    });
    byId("a").focus();
    for (const name of ["Enter", " "]) expect(press(name).defaultPrevented).toBe(false);
    expect(release(" ").defaultPrevented).toBe(false);
    expect(closed()).toBe(0);
    expect(shown()).toBeNull();

    // The click of the key: trusted, without a pointer.
    const click = () => {
      const event = new MouseEvent("click", { bubbles: true, detail: 0 });
      Object.defineProperty(event, "isTrusted", { value: true });
      byId("a").dispatchEvent(event);
    };
    click();
    expect(acted).toBe(0);
    now += MIN_VISIBLE_MS;
    click();
    expect(acted).toBe(1);
  });
});

describe("card keyboard", () => {
  function card(fields: string, rtl = false, side: DetailsSide | null = null) {
    render(`<div id="card">${fields}</div>`);
    const root = byId<HTMLElement>("card");
    const asked: string[] = [];
    const press = (key: string) => {
      const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
      onDetailsKeydown(event, root, { back: () => asked.push("back"), close: () => asked.push("close"), rtl, side });
      return event;
    };
    return { root, press, asked };
  }

  it("moves between the fields with ArrowUp and ArrowDown, and leaves Enter and Space to them", () => {
    const { press, asked } = card(`<button id="a"></button><button id="b" disabled></button><button id="c"></button>`);
    press("ArrowDown");
    expect(document.activeElement).toBe(byId("a"));
    press("ArrowDown");
    expect(document.activeElement).toBe(byId("c"));
    press("ArrowUp");
    expect(document.activeElement).toBe(byId("a"));
    for (const key of ["Enter", " "]) expect(press(key).defaultPrevented).toBe(false);
    expect(asked).toEqual([]);
  });

  it("goes back to the menu on ArrowLeft, ArrowRight right to left, and Escape; Tab closes the menu", () => {
    const ltr = card(`<button id="a"></button>`);
    expect(ltr.press("ArrowRight").defaultPrevented).toBe(false);
    for (const key of ["ArrowLeft", "Escape", "Tab"]) expect(ltr.press(key).defaultPrevented).toBe(true);
    expect(ltr.asked).toEqual(["back", "back", "close"]);

    const rtl = card(`<button id="a"></button>`, true);
    expect(rtl.press("ArrowLeft").defaultPrevented).toBe(false);
    rtl.press("ArrowRight");
    expect(rtl.asked).toEqual(["back"]);
  });

  it("goes back with the arrow toward the menu, by the side the card shows on", () => {
    // Left of the menu, left to right: ArrowRight goes back.
    const left = card(`<button id="a"></button>`, false, "left");
    expect(left.press("ArrowLeft").defaultPrevented).toBe(false);
    expect(left.press("ArrowRight").defaultPrevented).toBe(true);
    expect(left.asked).toEqual(["back"]);

    const right = card(`<button id="a"></button>`, true, "right");
    expect(right.press("ArrowRight").defaultPrevented).toBe(false);
    expect(right.press("ArrowLeft").defaultPrevented).toBe(true);
    expect(right.asked).toEqual(["back"]);
  });

  it("focuses a field only on the background's word, as the menu: the page can focus the card's frame too", () => {
    const { root, press } = card(``);
    const focus = new MenuFocus();
    root.addEventListener("focusin", (event) => focus.focusIn(event.target));
    focus.settle(root);
    root.innerHTML = `<button id="a"></button><button id="b"></button>`;
    focus.settle(root);
    expect(document.activeElement).toBe(document.body);
    expect(press("Enter").defaultPrevented).toBe(false);
    focus.request(root);
    expect(document.activeElement).toBe(byId("a"));
  });

  it("focuses the row focus goes back to from the card", () => {
    const { root, focus } = menu(`<button id="a"></button><button id="b"></button>`);
    focus.blur();
    focus.request(root, byId<HTMLButtonElement>("b"));
    expect(document.activeElement).toBe(byId("b"));
  });
});
