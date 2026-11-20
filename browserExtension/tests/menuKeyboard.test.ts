// @vitest-environment happy-dom
// The keyboard of the inline menu (src/lib/ui/menuKeyboard.ts), on its rows
// as the menu page renders them.
import { afterEach, describe, expect, it, vi } from "vitest";
import { ClickGate, MIN_VISIBLE_MS } from "../src/lib/ui/clickGate";
import { MenuFocus, onMenuKeydown } from "../src/lib/ui/menuKeyboard";
import { byId, render } from "./support/dom";

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = "";
});

function menu(rows: string) {
  render(`<div id="menu">${rows}</div>`);
  let closed = 0;
  const root = byId<HTMLElement>("menu");
  // As the menu page wires it.
  const focus = new MenuFocus();
  root.addEventListener("focusin", (event) => focus.focusIn(event.target));
  const press = (key: string) => {
    const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
    onMenuKeydown(event, root, () => closed++);
    return event;
  };
  return { root, press, focus, closed: () => closed };
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

  it("leaves Enter and Space to the rows, whose click goes through the click gate", () => {
    const { press, closed } = menu(`<button id="a"></button>`);
    let now = 0;
    const gate = new ClickGate(() => now);
    gate.setVisible(true);
    let acted = 0;
    // As the menu page's rows do.
    byId("a").addEventListener("click", (event) => {
      if (gate.allows(event)) acted++;
    });
    byId("a").focus();
    for (const key of ["Enter", " "]) expect(press(key).defaultPrevented).toBe(false);
    expect(closed()).toBe(0);

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
