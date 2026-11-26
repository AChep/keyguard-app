// @vitest-environment happy-dom
// The card beside the inline menu (src/lib/content/detailsCard.ts), through
// the UI that holds it: where it goes beside the menu, when it shows, the
// sides it reports, and focus between its frame and the menu's.
// happy-dom has no layout or top layer: the tests give the boxes.
import { describe, expect, it, vi } from "vitest";
import { DETAILS_GAP, ELEVATION_2_LAYERS, ELEVATION_3_LAYERS, shadowReach } from "../src/lib/content/detailsCard";
import { BIND_MESSAGE, parseContentRequest } from "../src/lib/page/messages";
import { EXTENSION_ORIGINS, runFrame, setup, useContentUiHooks, viewport } from "./support/contentUi";
import { byId, render } from "./support/dom";

useContentUiHooks();

describe("menu details", () => {
  const DETAILS = { token: "d".repeat(32), page: "menu-details.html" };

  /** Opens the menu, with its card, below a field at [box]; the menu shows at a height of 200. */
  function open(box: { left: number; top: number; width: number; height: number }, direction?: "ltr" | "rtl") {
    render(`<input id="field">`);
    const field = byId("field");
    const at = (next: typeof box) => {
      field.getBoundingClientRect = () => new DOMRect(next.left, next.top, next.width, next.height);
    };
    at(box);
    const context = setup({ direction });
    context.ui.openMenu(field, "token", "inline-menu.html", DETAILS);
    const card = () => context.shadow.querySelector<HTMLElement>(".popup.menu-details");
    /** The card's box, or `null` while it's hidden. */
    const place = () => {
      const element = card()!;
      if (element.classList.contains("pending")) return null;
      return [element.style.left, element.style.top, element.style.width, element.style.height].map((it) =>
        Number.parseFloat(it),
      );
    };
    const menu = () => {
      const style = context.ui.menuElement!.style;
      return [style.left, style.top, style.width, style.height].map((it) => Number.parseFloat(it));
    };
    return { ...context, card, place, menu, at };
  }

  it("loads hidden with the menu, then takes the pointer and focus as the menu does, and says when it shows", () => {
    viewport(1024, 768);
    const { ui, card, cardShown } = open({ left: 10, top: 100, width: 200, height: 30 });
    const element = card()!;
    // Shown as a box over the page: happy-dom has no popovers.
    expect(getComputedStyle(element).display).toBe("block");
    expect(element.classList.contains("pending")).toBe(true);
    expect(ui.menuDetailsElement).toBeNull();
    const frame = element.querySelector("iframe")!;
    // The token goes to its frame as to the menu's.
    const post = vi.spyOn(frame.contentWindow!, "postMessage").mockImplementation(() => undefined);
    frame.dispatchEvent(new Event("load"));
    expect(post.mock.calls).toEqual(
      EXTENSION_ORIGINS.map((origin) => [{ type: BIND_MESSAGE, token: DETAILS.token }, origin]),
    );

    ui.resizeMenu(200);
    ui.showMenuDetails(40, 48, 150);
    expect(element.classList.contains("pending")).toBe(false);
    // Interactive: its fields copy.
    expect(element.hasAttribute("inert")).toBe(false);
    expect(element.hasAttribute("aria-hidden")).toBe(false);
    expect(frame.hasAttribute("tabindex")).toBe(false);
    for (const it of [element, frame]) expect(getComputedStyle(it).pointerEvents).not.toBe("none");
    // The checks before a copy look at it while it shows.
    expect(ui.menuDetailsElement).toBe(element);
    // Each change once: its page's click gate starts, or waits again.
    ui.showMenuDetails(60, 48, 150);
    ui.hideMenuDetails();
    ui.hideMenuDetails();
    ui.showMenuDetails(40, 48, 150);
    expect(cardShown).toEqual([true, false, true]);
    expect(ui.menuDetailsElement).toBe(element);

    ui.closeMenu();
    expect(card()).toBeNull();
    expect(ui.menuDetailsElement).toBeNull();
    // Without the card's page, none.
    ui.openMenu(byId("field"), "token", "inline-menu.html");
    expect(card()).toBeNull();
    ui.showMenuDetails(40, 48, 150);
    expect(card()).toBeNull();
    expect(cardShown).toEqual([true, false, true]);
  });

  it("moves focus from the menu into the card that shows, and back, on the background's word only", () => {
    viewport(1024, 768);
    const { ui, card, shadow } = open({ left: 10, top: 100, width: 200, height: 30 });
    const menuFrame = ui.menuElement!.querySelector("iframe")!;
    const cardFrame = card()!.querySelector("iframe")!;
    ui.resizeMenu(200);
    // Not from the field, nor into a card that doesn't show.
    expect(ui.focusMenuDetails()).toBe(false);
    menuFrame.focus();
    expect(shadow.activeElement).toBe(menuFrame);
    expect(ui.focusMenuDetails()).toBe(false);
    expect(ui.focusMenuFrame()).toBe(false);
    expect(shadow.activeElement).toBe(menuFrame);

    ui.showMenuDetails(40, 48, 150);
    expect(ui.focusMenuDetails()).toBe(true);
    expect(shadow.activeElement).toBe(cardFrame);
    // Again: focus isn't in the menu any more.
    expect(ui.focusMenuDetails()).toBe(false);
    expect(ui.focusMenuFrame()).toBe(true);
    expect(shadow.activeElement).toBe(menuFrame);
    expect(ui.focusMenuFrame()).toBe(false);

    // The card hides with focus in it: focus goes back to the menu.
    expect(ui.focusMenuDetails()).toBe(true);
    ui.hideMenuDetails();
    expect(shadow.activeElement).toBe(menuFrame);

    // The menu closes with focus in its card: focus goes back to the field.
    ui.showMenuDetails(40, 48, 150);
    expect(ui.focusMenuDetails()).toBe(true);
    ui.closeMenu();
    expect(document.activeElement).toBe(byId("field"));
  });

  it("shows only while the menu shows, level with the selected row, after the menu", () => {
    viewport(1024, 768);
    const { ui, place, menu } = open({ left: 10, top: 100, width: 200, height: 30 });
    ui.showMenuDetails(40, 48, 150);
    expect(place()).toBeNull();
    ui.resizeMenu(200);
    expect(menu()).toEqual([10, 134, 320, 200]);
    // As wide as the menu, as high as its content.
    expect(place()).toEqual([10 + 320 + DETAILS_GAP, 134 + 40, 320, 150]);
    // Within the window: it shifts up.
    ui.showMenuDetails(40, 48, 1000);
    expect(place()).toEqual([330 + DETAILS_GAP, 4, 320, 768 - 8]);
    ui.hideMenuDetails();
    expect(place()).toBeNull();
    // Malformed: nothing changes.
    ui.showMenuDetails(Number.NaN, 48, 150);
    expect(place()).toBeNull();
  });

  it("goes before the menu without room after it, and hides without room on either side", () => {
    viewport(1024, 768);
    const right = open({ left: 800, top: 100, width: 200, height: 30 });
    right.ui.resizeMenu(200);
    right.ui.showMenuDetails(0, 48, 150);
    expect(right.menu()[0]).toBe(700);
    expect(right.place()).toEqual([700 - DETAILS_GAP - 320, 134, 320, 150]);
    right.ui.destroy();

    viewport(600, 768);
    const narrow = open({ left: 140, top: 100, width: 200, height: 30 });
    narrow.ui.resizeMenu(200);
    narrow.ui.showMenuDetails(0, 48, 150);
    expect(narrow.place()).toBeNull();
  });

  it("tells the background once when the card has no room on either side, and forgets it: not while its row is scrolled out", () => {
    viewport(1024, 768);
    const { ui, place, cardShown, cardNoRoom, at } = open({ left: 10, top: 100, width: 200, height: 30 });
    ui.resizeMenu(200);
    // The row scrolled out of the menu, and back: hidden for a while only.
    ui.showMenuDetails(-100, 48, 150);
    expect(place()).toBeNull();
    expect(cardShown).toEqual([]);
    ui.showMenuDetails(40, 48, 150);
    ui.showMenuDetails(-100, 48, 150);
    expect(cardShown).toEqual([true, false]);
    expect(cardNoRoom).toEqual([false]);

    // The window narrowed past both sides of the menu while the card showed.
    ui.showMenuDetails(40, 48, 150);
    viewport(600, 768);
    at({ left: 140, top: 100, width: 200, height: 30 });
    ui.resizeMenu(201);
    expect(place()).toBeNull();
    expect(cardShown).toEqual([true, false, true, false]);
    expect(cardNoRoom).toEqual([false, true]);
    // Forgotten: the menu moving, or the window widening, brings back nothing, and tells nothing again.
    ui.resizeMenu(202);
    viewport(1024, 768);
    ui.resizeMenu(203);
    expect(place()).toBeNull();
    expect(cardNoRoom).toEqual([false, true]);
    // Nothing to hide either.
    ui.hideMenuDetails();
    expect(cardShown).toEqual([true, false, true, false]);

    // A card that never showed, as for a login selected in the narrow window: told too.
    viewport(600, 768);
    ui.resizeMenu(204);
    ui.showMenuDetails(40, 48, 150);
    expect(place()).toBeNull();
    expect(cardShown).toEqual([true, false, true, false, false]);
    expect(cardNoRoom).toEqual([false, true, true]);
  });

  it("reports the menu's frame and the card's as the background's parser takes them", () => {
    viewport(1024, 768);
    const { ui, reports, at } = open({ left: 10, top: 100, width: 200, height: 30 });
    ui.resizeMenu(200);
    ui.showMenuDetails(40, 48, 150);
    ui.hideMenuDetails();
    ui.showMenuDetails(40, 48, 150);
    // No room on either side.
    viewport(600, 768);
    at({ left: 140, top: 100, width: 200, height: 30 });
    ui.resizeMenu(201);
    expect(reports).toEqual([
      { popup: "menu", shown: true },
      { popup: "menu_details", shown: true, side: "right" },
      { popup: "menu_details", shown: false },
      { popup: "menu_details", shown: true, side: "right" },
      { popup: "menu_details", shown: false, room: false },
    ]);
    const doc = "d".repeat(32);
    for (const report of reports) {
      const request = { kind: "popup_shown", ...report, doc };
      expect(parseContentRequest(request), JSON.stringify(report)).toEqual(request);
    }
  });

  it("tells the side of the menu it shows on, on the screen, and again when it moves to the other", () => {
    viewport(1024, 768);
    const { ui, cardSides, at } = open({ left: 10, top: 100, width: 200, height: 30 });
    ui.resizeMenu(200);
    ui.showMenuDetails(40, 48, 150);
    // Moved within its side: nothing new.
    ui.showMenuDetails(60, 48, 150);
    expect(cardSides).toEqual(["right"]);
    // The field moved to the window's right edge: the card goes before the menu.
    at({ left: 800, top: 100, width: 200, height: 30 });
    ui.resizeMenu(201);
    expect(cardSides).toEqual(["right", "left"]);
    ui.hideMenuDetails();
    expect(cardSides).toEqual(["right", "left", null]);
    ui.destroy();

    // Right to left, it goes before the menu first: on its left.
    const rtl = open({ left: 600, top: 100, width: 200, height: 30 }, "rtl");
    rtl.ui.resizeMenu(200);
    rtl.ui.showMenuDetails(40, 48, 150);
    expect(rtl.cardSides).toEqual(["left"]);
  });

  it("hides when only a card closer than DETAILS_GAP would fit", () => {
    // After the menu at 4, a gap of 4 would fit: 4 + 320 + 4 + 320 <= 660 - 4.
    viewport(660, 768);
    const { ui, place, menu } = open({ left: 0, top: 100, width: 200, height: 30 });
    ui.resizeMenu(200);
    ui.showMenuDetails(0, 48, 150);
    expect(menu()[0]).toBe(4);
    expect(place()).toBeNull();
  });

  it("is over the menu, a level higher, and the popovers' backdrops cover nothing", () => {
    viewport(1024, 768);
    const { ui, card, shadow } = open({ left: 10, top: 100, width: 200, height: 30 });
    ui.resizeMenu(200);
    ui.showMenuDetails(40, 48, 150);
    const element = card()!;
    const menuElement = ui.menuElement!;
    // Shown after the menu: later in the top layer, or later in the root with the same z-index.
    expect(menuElement.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(getComputedStyle(element).boxShadow).not.toBe(getComputedStyle(menuElement).boxShadow);
    const css = Array.from(shadow.querySelectorAll("style"), (style) => style.textContent).join("\n");
    expect(css).toContain(".popup::backdrop, .icon::backdrop { display: none; }");
    expect(css).toMatch(/\.popup\.menu-details \{ box-shadow: 0px 1px 3px 0px rgb\(0 0 0 \/ 0\.3\), 0px 4px 8px 3px/);
  });

  it("keeps the card past its shadow's reach, as measured in Chrome", () => {
    // Chrome 157: the menu's frame showed with ELEVATION_2 12px away, not 10px;
    // with ELEVATION_3 16px away, not 14px.
    expect(shadowReach(ELEVATION_2_LAYERS)).toBe(11);
    expect(shadowReach(ELEVATION_3_LAYERS)).toBe(15);
    expect(DETAILS_GAP).toBe(shadowReach(ELEVATION_3_LAYERS) + 1);
  });

  it("goes before the menu first in a right-to-left UI", () => {
    viewport(1400, 768);
    const ltr = open({ left: 600, top: 100, width: 200, height: 30 });
    ltr.ui.resizeMenu(200);
    ltr.ui.showMenuDetails(0, 48, 150);
    expect(ltr.place()![0]).toBe(600 + 320 + DETAILS_GAP);
    ltr.ui.destroy();

    const rtl = open({ left: 600, top: 100, width: 200, height: 30 }, "rtl");
    rtl.ui.resizeMenu(200);
    rtl.ui.showMenuDetails(0, 48, 150);
    expect(rtl.place()![0]).toBe(600 - DETAILS_GAP - 320);
    rtl.ui.destroy();

    // Without room there, after it.
    const edge = open({ left: 10, top: 100, width: 200, height: 30 }, "rtl");
    edge.ui.resizeMenu(200);
    edge.ui.showMenuDetails(0, 48, 150);
    expect(edge.place()![0]).toBe(10 + 320 + DETAILS_GAP);
  });

  it("stays within the window, and follows the menu when it flips above the field", () => {
    viewport(1024, 768);
    const { ui, place, menu, at } = open({ left: 10, top: 100, width: 200, height: 30 });
    ui.resizeMenu(200);
    ui.showMenuDetails(140, 48, 300);
    expect(place()).toEqual([330 + DETAILS_GAP, 134 + 140, 320, 300]);

    // The page scrolls the field down: the menu goes above it.
    at({ left: 10, top: 600, width: 200, height: 30 });
    runFrame();
    expect(menu()).toEqual([10, 396, 320, 200]);
    // Level with the row would end below the window.
    expect(place()).toEqual([330 + DETAILS_GAP, 768 - 4 - 300, 320, 300]);
    ui.showMenuDetails(0, 48, 150);
    expect(place()).toEqual([330 + DETAILS_GAP, 396, 320, 150]);

    // A small window: the card is as high as it allows.
    viewport(1024, 300);
    at({ left: 10, top: 20, width: 200, height: 30 });
    runFrame();
    ui.showMenuDetails(0, 48, 1000);
    expect(place()).toEqual([330 + DETAILS_GAP, 4, 320, 300 - 8]);
  });

  it("hides while the selected row is scrolled out of the menu", () => {
    viewport(1024, 768);
    const { ui, place } = open({ left: 10, top: 100, width: 200, height: 30 });
    ui.resizeMenu(200);
    // Mostly above the menu's top, then mostly below its bottom.
    ui.showMenuDetails(-30, 48, 150);
    expect(place()).toBeNull();
    ui.showMenuDetails(180, 48, 150);
    expect(place()).toBeNull();
    // Half of it shows.
    ui.showMenuDetails(-24, 48, 150);
    expect(place()).toEqual([330 + DETAILS_GAP, 110, 320, 150]);
  });

  it("keeps DETAILS_GAP from the menu: neither the card nor its shadow covers the menu's frame", () => {
    for (const width of [400, 640, 800, 1024, 1400]) {
      for (const left of [0, 100, 300, 500, 900, 1200]) {
        for (const direction of ["ltr", "rtl"] as const) {
          viewport(width, 768);
          const { ui, place, menu } = open({ left, top: 100, width: 200, height: 30 }, direction);
          ui.resizeMenu(200);
          ui.showMenuDetails(20, 48, 150);
          const card = place();
          const [menuLeft, , menuWidth] = menu();
          if (card !== null) {
            const [cardLeft, , cardWidth] = card;
            const apart =
              cardLeft! >= menuLeft! + menuWidth! + DETAILS_GAP || cardLeft! + cardWidth! <= menuLeft! - DETAILS_GAP;
            expect(apart, `${width} ${left} ${direction}`).toBe(true);
            expect(cardLeft! >= 4 && cardLeft! + cardWidth! <= width - 4, `${width} ${left} ${direction}`).toBe(true);
          }
          ui.destroy();
          document.body.innerHTML = "";
        }
      }
    }
  });
});
