// @vitest-environment happy-dom
// The checks before showing the UI or filling: sandboxes, hidden fields, and
// DOM clickjacking (transparent pages, covered fields, overlays on the menu).
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  fieldExposure,
  frameElementOf,
  frameExposure,
  isFillable,
  isOpaqueOrigin,
  popupExposure,
  sameOriginFrames,
  TopLayerWatch,
  transparency,
} from "../src/lib/content/guards";
import { shadowRootReader } from "../src/lib/content/shadowRoots";
import { ClickGate, MIN_VISIBLE_MS, tracksVisibility } from "../src/lib/ui/clickGate";
import { byId, render, testLayout, TOP_LAYER, type Box } from "./support/dom";

afterEach(() => {
  document.body.innerHTML = "";
  document.documentElement.removeAttribute("style");
  document.body.removeAttribute("style");
});

describe("opaque origins", () => {
  it("never serves sandboxed or opaque documents (CVE-2025-65203)", () => {
    // CSP `sandbox` or a sandboxed iframe: the URL is https, the origin isn't.
    expect(isOpaqueOrigin({ origin: "null", location: { origin: "https://bank.example" } })).toBe(true);
    // data: and similar URLs.
    expect(isOpaqueOrigin({ origin: "null", location: { origin: "null" } })).toBe(true);
    expect(isOpaqueOrigin({ location: { origin: "null" } })).toBe(true);
    expect(isOpaqueOrigin({ origin: "https://bank.example", location: { origin: "https://bank.example" } })).toBe(false);
  });

  it("serves about:blank and srcdoc frames as the origin they inherit, unless sandboxed", () => {
    // `window.origin` and `location.origin` as browsers report them: an
    // about:blank or about:srcdoc URL has the origin "null", while the frame
    // runs as its parent's.
    const frames: [string, { origin: string; location: { origin: string } }, boolean][] = [
      ["about:blank", { origin: "https://bank.example", location: { origin: "null" } }, false],
      ["about:srcdoc", { origin: "https://bank.example", location: { origin: "null" } }, false],
      ["sandboxed about:srcdoc", { origin: "null", location: { origin: "null" } }, true],
      ["data:", { origin: "null", location: { origin: "null" } }, true],
    ];
    for (const [name, win, opaque] of frames) expect(isOpaqueOrigin(win), name).toBe(opaque);
  });
});

describe("fillable fields", () => {
  it("takes enabled, rendered and visible inputs the page doesn't opt out only", () => {
    render(`
      <input id="ok">
      <input id="readonly" readonly>
      <input id="disabled" disabled>
      <input id="hidden-type" type="hidden">
      <div style="display:none"><input id="undisplayed"></div>
      <input id="invisible" style="visibility:hidden">
      <input id="ignored" data-1p-ignore>
      <form data-lpignore="true"><input id="in-ignored-form"></form>`);
    const layout = testLayout();
    // A read-only field may turn writable on focus; fills check then.
    for (const id of ["ok", "readonly"]) expect(isFillable(byId(id), layout), id).toBe(true);
    for (const id of ["disabled", "hidden-type", "undisplayed", "invisible", "ignored", "in-ignored-form"]) {
      expect(isFillable(byId(id), layout), id).toBe(false);
    }
    const detached = document.createElement("input");
    expect(isFillable(detached, layout)).toBe(false);
  });
});

describe("transparency", () => {
  it("refuses opacity below 1 anywhere from the field up to <html>", () => {
    render(`<div id="wrap"><input id="field"></div>`);
    const layout = testLayout();
    const field = byId("field");
    expect(transparency(field, layout)).toBeNull();

    document.documentElement.style.opacity = "0.99";
    expect(transparency(field, layout)).toBe("transparent");
    document.documentElement.removeAttribute("style");

    document.body.style.opacity = "0";
    expect(transparency(field, layout)).toBe("transparent");
    document.body.removeAttribute("style");

    byId<HTMLElement>("wrap").style.opacity = "0.5";
    expect(transparency(field, layout)).toBe("transparent");
    byId<HTMLElement>("wrap").removeAttribute("style");

    byId<HTMLElement>("wrap").style.filter = "opacity(40%)";
    expect(transparency(field, layout)).toBe("transparent");
    byId<HTMLElement>("wrap").removeAttribute("style");

    field.style.visibility = "hidden";
    expect(transparency(field, layout)).toBe("hidden");
  });

  it("crosses shadow roots to their hosts", () => {
    render(`<x-login id="host"></x-login>`);
    const host = byId<HTMLElement>("host");
    const root = host.attachShadow({ mode: "open" });
    root.innerHTML = `<input id="inner">`;
    host.style.opacity = "0.1";
    expect(transparency(byId("inner", root), testLayout())).toBe("transparent");
  });

  it("follows a slotted field into the shadow tree it renders in", () => {
    render(`<x-login id="host"><input id="field"></x-login>`);
    const root = byId<HTMLElement>("host").attachShadow({ mode: "open" });
    root.innerHTML = `<div id="wrap"><slot id="slot"></slot></div>`;
    const field = byId("field");
    // happy-dom doesn't assign slots.
    Object.defineProperty(field, "assignedSlot", { value: byId("slot", root) });
    expect(transparency(field, testLayout())).toBeNull();

    byId<HTMLElement>("wrap", root).style.opacity = "0.1";
    expect(transparency(field, testLayout())).toBe("transparent");
    byId<HTMLElement>("wrap", root).removeAttribute("style");

    // A `display: contents` slot has no box to fade, but it counts.
    byId<HTMLElement>("slot", root).style.opacity = "0.5";
    expect(transparency(field, testLayout())).toBe("transparent");
  });

  it("finds the slot of a closed shadow root through the layout", () => {
    render(`<x-login id="host"><input id="field"></x-login>`);
    const host = byId<HTMLElement>("host");
    const root = host.attachShadow({ mode: "closed" });
    root.innerHTML = `<slot name="other"></slot><div id="wrap"><slot></slot></div>`;
    const layout = testLayout({ shadowRoot: (element) => (element === host ? root : element.shadowRoot) });
    // `assignedSlot` hides it; the first slot isn't the field's.
    const field = byId("field");
    expect(transparency(field, layout)).toBeNull();

    byId<HTMLElement>("wrap", root).style.opacity = "0.1";
    expect(transparency(field, layout)).toBe("transparent");
  });

  it("fails closed when a closed shadow root has too many slots to look through", () => {
    render(`<x-login id="host"><input id="field"></x-login>`);
    const host = byId<HTMLElement>("host");
    const root = host.attachShadow({ mode: "closed" });
    root.innerHTML = `${`<slot name="decoy"></slot>`.repeat(32)}<slot></slot>`;
    const layout = testLayout({ shadowRoot: (element) => (element === host ? root : element.shadowRoot) });
    expect(transparency(byId("field"), layout)).toBe("transparent");
  });
});

describe("field exposure", () => {
  it("needs the field on top at its center, in the viewport, with a real size", () => {
    render(`<input id="field"><div id="overlay"></div>`);
    const field = byId("field");
    const overlay = byId<HTMLElement>("overlay");
    const box: Box = { left: 100, top: 100, width: 200, height: 30 };
    const boxes = new Map<Element, Box>([[field, box]]);
    let hit: Element | null = field;
    const layout = testLayout({ boxes, hit: (x, y) => (x === 200 && y === 115 ? hit : null) });
    expect(fieldExposure(field, layout)).toBeNull();

    // Covered by a page element.
    hit = overlay;
    expect(fieldExposure(field, layout)).toBe("obscured");
    hit = field;

    // Scrolled out of the viewport.
    boxes.set(field, { left: 100, top: 2000, width: 200, height: 30 });
    expect(fieldExposure(field, testLayout({ boxes, hit: () => field }))).toBe("obscured");

    // Shrunk to nothing.
    boxes.set(field, { left: 100, top: 100, width: 1, height: 1 });
    expect(fieldExposure(field, testLayout({ boxes, hit: () => field }))).toBe("obscured");

    // Faded out.
    boxes.set(field, box);
    document.body.style.opacity = "0.5";
    expect(fieldExposure(field, testLayout({ boxes, hit: () => field }))).toBe("obscured");
  });

  it("refuses a field that is read-only with focus: the page shows it", () => {
    render(`<input id="field" readonly>`);
    const field = byId("field");
    const layout = testLayout({ hit: () => field });
    expect(fieldExposure(field, layout)).toBe("obscured");
    field.readOnly = false;
    expect(fieldExposure(field, layout)).toBeNull();
  });

  it("accepts a hit inside the field's shadow root", () => {
    render(`<x-field id="host"></x-field>`);
    const root = byId<HTMLElement>("host").attachShadow({ mode: "open" });
    root.innerHTML = `<input id="inner">`;
    const inner = byId("inner", root);
    expect(fieldExposure(inner, testLayout({ hit: () => inner }))).toBeNull();
    expect(fieldExposure(inner, testLayout({ hit: () => byId<HTMLElement>("host") }))).toBe("obscured");
  });
});

describe("popup exposure", () => {
  function setup() {
    render(`<div id="host"></div><div id="decoy"></div>`);
    const host = byId<HTMLElement>("host");
    const root = host.attachShadow({ mode: "closed" });
    const popup = document.createElement("div");
    root.append(popup);
    const boxes = new Map<Element, Box>([[popup, { left: 100, top: 140, width: 320, height: 200 }]]);
    return { host, popup, boxes, decoy: byId<HTMLElement>("decoy") };
  }

  it("needs the menu to be what the page hits at its center", () => {
    const { host, popup, boxes, decoy } = setup();
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => host }), document)).toBeNull();
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => decoy }), document)).toBe("obscured");
  });

  it("refuses when the page fades the host or the root", () => {
    const { host, popup, boxes } = setup();
    host.style.setProperty("opacity", "0", "important");
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => host }), document)).toBe("obscured");
    host.removeAttribute("style");
    document.documentElement.style.opacity = "0.9";
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => host }), document)).toBe("obscured");
  });

  /** Puts [element] in the top layer, as an open popover or a modal dialog. */
  function open(element: Element) {
    element.setAttribute(TOP_LAYER, "");
  }

  it("refuses any open top-layer element of the page, wherever it is: decoys under it don't hit", () => {
    const { host, popup, boxes, decoy } = setup();
    const layout = testLayout({ boxes, topHit: () => host });
    expect(popupExposure(host, popup, layout, document)).toBeNull();
    // Far from the menu, it still counts: its content needn't be in its box.
    boxes.set(decoy, { left: 600, top: 600, width: 50, height: 50 });
    open(decoy);
    expect(popupExposure(host, popup, layout, document)).toBe("obscured");
  });

  it("refuses a 1x1 popover whose fixed child covers the menu, without pointer events", () => {
    render(`<div id="host"></div>
      <div id="popover" popover="manual" style="width:1px;height:1px">
        <div style="position:fixed;inset:0;pointer-events:none"></div>
      </div>`);
    const host = byId<HTMLElement>("host");
    const popup = document.createElement("div");
    host.attachShadow({ mode: "closed" }).append(popup);
    const popover = byId<HTMLElement>("popover");
    const boxes = new Map<Element, Box>([
      [popup, { left: 100, top: 140, width: 320, height: 200 }],
      [popover, { left: 0, top: 0, width: 1, height: 1 }],
    ]);
    // The document still hits the host: the child takes no pointer events.
    const layout = testLayout({ boxes, topHit: () => host });
    expect(popupExposure(host, popup, layout, document)).toBeNull();
    open(popover);
    expect(popupExposure(host, popup, layout, document)).toBe("obscured");
  });

  it("refuses a popover whose backdrop covers the menu", () => {
    render(`<style>#popover::backdrop { background: white; pointer-events: none; }</style>
      <div id="host"></div><div id="popover" popover="manual"></div>`);
    const host = byId<HTMLElement>("host");
    const popup = document.createElement("div");
    host.attachShadow({ mode: "closed" }).append(popup);
    const popover = byId<HTMLElement>("popover");
    const boxes = new Map<Element, Box>([
      [popup, { left: 100, top: 140, width: 320, height: 200 }],
      [popover, { left: 900, top: 700, width: 10, height: 10 }],
    ]);
    open(popover);
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => host }), document)).toBe("obscured");
  });

  it("refuses a popover in a shadow root of the page, open or closed where the content script sees it", () => {
    const { host, popup, boxes } = setup();
    for (const mode of ["open", "closed"] as const) {
      const outer = document.createElement("x-widget");
      document.body.append(outer);
      const outerRoot = outer.attachShadow({ mode });
      // Any element can host a shadow root, and nest another.
      const inner = document.createElement("div");
      outerRoot.append(inner);
      const innerRoot = inner.attachShadow({ mode: "open" });
      const popover = document.createElement("div");
      innerRoot.append(popover);
      const roots = new Map<Element, ShadowRoot>([
        [outer, outerRoot],
        [inner, innerRoot],
      ]);
      const layout = testLayout({ boxes, topHit: () => host, shadowRoot: (element) => roots.get(element) ?? element.shadowRoot });
      expect(popupExposure(host, popup, layout, document), mode).toBeNull();
      open(popover);
      expect(popupExposure(host, popup, layout, document), mode).toBe("obscured");
      outer.remove();
    }
  });

  it("finds a popover in a closed root of a built-in element at guard time, where the browser shows content scripts the root", () => {
    const { host, popup, boxes } = setup();
    const ownRoot = popup.getRootNode() as ShadowRoot;
    const outer = document.createElement("div");
    document.body.append(outer);
    const closed = outer.attachShadow({ mode: "closed" });
    const popover = document.createElement("div");
    closed.append(popover);
    open(popover);
    // Firefox: `Element.openOrClosedShadowRoot`; Chromium: `dom.openOrClosedShadowRoot`.
    for (const element of [outer, host]) {
      Object.defineProperty(element, "openOrClosedShadowRoot", { value: element === host ? ownRoot : closed, configurable: true });
    }
    const chromium = { openOrClosedShadowRoot: (element: Element) => (element === outer ? closed : element === host ? ownRoot : null) };
    for (const [name, dom] of [["firefox", undefined], ["chromium", chromium]] as const) {
      // Detection asks custom elements only: it doesn't see this one.
      const detection = shadowRootReader(dom, host, { everyHost: false });
      expect(detection(outer), name).toBeNull();
      expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => host, shadowRoot: detection }), document), name).toBeNull();
      const guard = shadowRootReader(dom, host, { everyHost: true });
      expect(guard(outer), name).toBe(closed);
      // Never the extension's own root.
      expect(guard(host), name).toBeNull();
      expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => host, shadowRoot: guard }), document), name).toBe("obscured");
    }
  });

  it("looks for the top layer neither in the host's own shadow root nor in the host", () => {
    const { host, popup, boxes } = setup();
    // The menu's and the icon's popovers are the extension's.
    open(popup);
    const root = new Map<Element, ShadowRoot>([[host, popup.getRootNode() as ShadowRoot]]);
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => host, shadowRoot: (element) => root.get(element) ?? null }), document)).toBeNull();
  });

  it("takes the modal dialog around the host as under the menu, but not a foreign one or the fullscreen element", () => {
    const { host, popup, boxes, decoy } = setup();
    // The login form's dialog, which the host went into.
    const dialog = document.createElement("dialog");
    document.body.append(dialog);
    dialog.append(host);
    dialog.showModal();
    open(dialog);
    const layout = testLayout({ boxes, topHit: () => host });
    expect(popupExposure(host, popup, layout, document)).toBeNull();
    open(decoy);
    expect(popupExposure(host, popup, layout, document)).toBe("obscured");
    decoy.removeAttribute(TOP_LAYER);
    const fullscreen = { fullscreenElement: dialog, querySelectorAll: (selector: string) => document.querySelectorAll(selector) } as unknown as Document;
    expect(popupExposure(host, popup, layout, fullscreen)).toBe("obscured");
  });

  it("refuses a popover around the host's dialog, which the page can show again over the menu", () => {
    const { host, popup, boxes } = setup();
    const popover = document.createElement("div");
    const dialog = document.createElement("dialog");
    document.body.append(popover);
    popover.append(dialog);
    dialog.append(host);
    dialog.showModal();
    open(dialog);
    const layout = testLayout({ boxes, topHit: () => host });
    expect(popupExposure(host, popup, layout, document)).toBeNull();
    open(popover);
    expect(popupExposure(host, popup, layout, document)).toBe("obscured");
  });

  it("looks through the page's shadow root when the host is in a dialog there", () => {
    render(`<x-modal id="modal"></x-modal>`);
    const modal = byId<HTMLElement>("modal");
    const shadow = modal.attachShadow({ mode: "open" });
    shadow.innerHTML = `<dialog id="dialog" open></dialog>`;
    const host = document.createElement("div");
    byId("dialog", shadow).append(host);
    const popup = document.createElement("div");
    host.attachShadow({ mode: "closed" }).append(popup);
    const boxes = new Map<Element, Box>([[popup, { left: 100, top: 140, width: 320, height: 200 }]]);
    // The document hits the component; its root, the host.
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => modal, hit: () => host }), document)).toBeNull();
    expect(popupExposure(host, popup, testLayout({ boxes, topHit: () => modal, hit: () => modal }), document)).toBe("obscured");
  });
});

describe("frames that hold the menu", () => {
  /** An iframe of the parent's document at [box], with a 2px border, hit wherever it is. */
  function setup(html = `<div id="wrap"><iframe id="frame" style="border: 2px solid"></iframe></div><div id="decoy"></div>`) {
    render(html);
    const frame = byId<HTMLIFrameElement>("frame");
    const box: Box = { left: 100, top: 50, width: 400, height: 300 };
    const boxes = new Map<Element, Box>([[frame, box]]);
    let hit: Element | null = frame;
    const inside = (x: number, y: number) => x >= box.left && y >= box.top && x < box.left + box.width && y < box.top + box.height;
    const layout = testLayout({ boxes, hit: (x, y) => (inside(x, y) ? hit : null) });
    return { frame, layout, cover: (element: Element | null) => (hit = element) };
  }

  it("maps the popup's center into the parent past the frame's border, when the parent shows the frame there", () => {
    const { frame, layout } = setup();
    expect(frameExposure(frame, { x: 60, y: 40 }, layout, document)).toEqual({ x: 162, y: 92 });
    // Outside the frame in the parent, as a popup scrolled out of the parent's view.
    expect(frameExposure(frame, { x: 600, y: 40 }, layout, document)).toBeNull();
  });

  it("refuses a frame the parent fades or hides, or one of its ancestors", () => {
    const { frame, layout } = setup();
    for (const [element, style] of [
      [frame, "border: 2px solid; opacity: 0.2"],
      [byId("wrap"), "opacity: 0.99"],
      [byId("wrap"), "filter: opacity(0)"],
      [frame, "border: 2px solid; visibility: hidden"],
      [byId("wrap"), "display: none"],
    ] as const) {
      const before = element.getAttribute("style");
      element.setAttribute("style", style);
      expect(frameExposure(frame, { x: 60, y: 40 }, layout, document), style).toBeNull();
      if (before === null) element.removeAttribute("style");
      else element.setAttribute("style", before);
    }
    expect(frameExposure(frame, { x: 60, y: 40 }, layout, document)).not.toBeNull();
  });

  it("refuses a frame the parent covers where the popup is", () => {
    const { frame, layout, cover } = setup();
    cover(byId("decoy"));
    expect(frameExposure(frame, { x: 60, y: 40 }, layout, document)).toBeNull();
  });

  it("refuses a frame the parent scales, rotates or skews, but takes a translation", () => {
    const { frame, layout } = setup();
    const wrap = byId<HTMLElement>("wrap");
    // As browsers compute them: matrices.
    for (const transform of ["matrix(1, 0, 0, 1, 30, -20)", "matrix3d(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 30, -20, 0, 1)"]) {
      wrap.style.transform = transform;
      expect(frameExposure(frame, { x: 60, y: 40 }, layout, document), transform).toEqual({ x: 162, y: 92 });
    }
    for (const transform of [
      "matrix(0.1, 0, 0, 0.1, 0, 0)",
      "matrix(2, 0, 0, 2, 0, 0)",
      "matrix(0.98, 0.17, -0.17, 0.98, 0, 0)",
      "matrix(1, 0, 0.5, 1, 0, 0)",
      "matrix3d(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, -0.002, 0, 0, 0, 1)",
    ]) {
      wrap.style.transform = transform;
      expect(frameExposure(frame, { x: 60, y: 40 }, layout, document), transform).toBeNull();
    }
    wrap.style.transform = "none";
    const scaled = { ...getComputedStyle(frame), transform: "none", scale: "0.5", rotate: "none", translate: "none" } as CSSStyleDeclaration;
    const rotated = { ...scaled, scale: "none", rotate: "45deg" } as CSSStyleDeclaration;
    const zoomed = { ...scaled, scale: "none", zoom: "0.5" } as CSSStyleDeclaration;
    for (const style of [scaled, rotated, zoomed]) {
      expect(frameExposure(frame, { x: 60, y: 40 }, { ...layout, style: (element) => (element === frame ? style : getComputedStyle(element)) }, document)).toBeNull();
    }
  });

  it("refuses a frame under a top-layer element of the parent, but not in a modal dialog around it", () => {
    const { frame, layout } = setup();
    const decoy = byId("decoy");
    decoy.setAttribute(TOP_LAYER, "");
    expect(frameExposure(frame, { x: 60, y: 40 }, layout, document)).toBeNull();
    decoy.removeAttribute(TOP_LAYER);
    const dialog = document.createElement("dialog");
    document.body.append(dialog);
    dialog.append(byId("wrap"));
    dialog.showModal();
    dialog.setAttribute(TOP_LAYER, "");
    expect(frameExposure(frame, { x: 60, y: 40 }, layout, document)).toEqual({ x: 162, y: 92 });
  });

  it("goes up through the ancestors of the frame's origin, and stops below one of another", () => {
    render(`<iframe id="outer"></iframe>`);
    const outer = byId("outer");
    // The frame's parent is in an iframe of this document, whose window is the top.
    const inner = document.createElement("iframe");
    const parentWindow = { frameElement: outer };
    let parent: Pick<Window, "frameElement"> = parentWindow;
    Object.defineProperty(inner, "ownerDocument", { get: () => ({ defaultView: parent }) });
    expect(sameOriginFrames({ frameElement: inner })).toEqual([inner, outer]);
    expect(sameOriginFrames(window)).toEqual([]);
    // A parent of another origin: `frameElement` is null, or throws.
    const crossOrigin = {
      get frameElement(): Element {
        throw new DOMException("Blocked", "SecurityError");
      },
    };
    parent = crossOrigin;
    expect(sameOriginFrames({ frameElement: inner })).toEqual([inner]);
    // Too deep to check.
    const deep = document.createElement("iframe");
    const loop = { frameElement: deep };
    Object.defineProperty(deep, "ownerDocument", { value: { defaultView: loop } });
    expect(sameOriginFrames(loop)).toBeNull();
  });

  it("finds the element of a child frame by its ID, in shadow roots too, and only frames", () => {
    render(`<div id="host"></div><iframe id="a"></iframe><div id="widget"></div><object id="object"></object>`);
    const closed = byId("widget").attachShadow({ mode: "closed" });
    closed.innerHTML = `<iframe id="b"></iframe>`;
    const ids = new Map<Element, number>([
      [byId("a"), 4],
      [byId("b", closed), 5],
      [byId("object"), 6],
    ]);
    const frameIdOf = (element: Element) => {
      const id = ids.get(element);
      if (id === undefined) throw new Error("Invalid frame element");
      return id;
    };
    const shadowRoot = (element: Element) => (element === byId("widget") ? closed : null);
    const host = byId("host");
    expect(frameElementOf(document, host, { shadowRoot }, 4, frameIdOf)).toBe(byId("a"));
    expect(frameElementOf(document, host, { shadowRoot }, 5, frameIdOf)).toBe(byId("b", closed));
    expect(frameElementOf(document, host, { shadowRoot }, 6, frameIdOf)).toBeNull();
    expect(frameElementOf(document, host, { shadowRoot }, 7, frameIdOf)).toBeNull();
  });
});

describe("top-layer watch", () => {
  /** Animation frames, which run when a test says so. */
  function frames() {
    const pending = new Map<number, FrameRequestCallback>();
    let ids = 0;
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      pending.set(++ids, callback);
      return ids;
    });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation((id) => void pending.delete(id));
    return {
      run: () => {
        const callbacks = [...pending.values()];
        pending.clear();
        for (const callback of callbacks) callback(0);
      },
      pending: () => pending.size,
    };
  }

  afterEach(() => vi.restoreAllMocks());

  function setup(layoutOptions: Parameters<typeof testLayout>[0] = {}) {
    render(`<div id="host"></div><div id="decoy" popover="manual"></div><details id="details"></details>`);
    const host = byId<HTMLElement>("host");
    host.attachShadow({ mode: "closed" });
    let now = 10_000;
    let open = true;
    const frame = frames();
    const watch = new TopLayerWatch({
      documents: () => [{ doc: document, host, layout: testLayout(layoutOptions) }],
      open: () => open,
      now: () => now,
    });
    return {
      watch,
      host,
      frame,
      decoy: byId<HTMLElement>("decoy"),
      advance: (ms: number) => (now += ms),
      setOpen: (value: boolean) => (open = value),
    };
  }

  /** Fires a toggle event of the popover API at [element], as the browser does: not composed. */
  function toggle(element: Element, type: "beforetoggle" | "toggle") {
    element.dispatchEvent(new Event(type));
  }

  it("remembers a page element in the top layer while a popup is open, for the click gate's wait", () => {
    const { watch, decoy, frame, advance } = setup();
    watch.start();
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(false);
    // Open over a frame, then gone as the click came.
    decoy.setAttribute(TOP_LAYER, "");
    frame.run();
    decoy.removeAttribute(TOP_LAYER);
    advance(100);
    frame.run();
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);
    advance(MIN_VISIBLE_MS);
    frame.run();
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(false);
  });

  it("sees a decoy shown and hidden between two frames, as on mousedown, by its toggle events", () => {
    const { watch, decoy, advance } = setup();
    watch.start();
    toggle(decoy, "beforetoggle");
    advance(MIN_VISIBLE_MS);
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);
    advance(1);
    toggle(decoy, "toggle");
    advance(MIN_VISIBLE_MS + 1);
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(false);
    // A `<details>` toggles outside the top layer.
    toggle(byId("details"), "toggle");
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(false);
    // So does a dialog that isn't modal.
    const dialog = document.createElement("dialog");
    document.body.append(dialog);
    toggle(dialog, "toggle");
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(false);
  });

  it("takes fullscreen changes, and the fullscreen element on each frame", () => {
    const { watch, frame, advance } = setup();
    watch.start();
    document.dispatchEvent(new Event("fullscreenchange"));
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);
    advance(MIN_VISIBLE_MS + 1);
    Object.defineProperty(document, "fullscreenElement", { value: byId("decoy"), configurable: true });
    try {
      frame.run();
      expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);
    } finally {
      delete (document as { fullscreenElement?: unknown }).fullscreenElement;
    }
  });

  it("watches shadow roots found when the popup opened or since, closed ones of built-in elements included", () => {
    render(`<div id="host"></div><div id="outer"></div>`);
    const host = byId<HTMLElement>("host");
    const outer = byId<HTMLElement>("outer");
    const closed = outer.attachShadow({ mode: "closed" });
    closed.innerHTML = `<div id="inside" popover="manual"></div>`;
    let now = 0;
    frames();
    const shadowRoot = shadowRootReader({ openOrClosedShadowRoot: (element) => (element === outer ? closed : null) }, host, { everyHost: true });
    const watch = new TopLayerWatch({ documents: () => [{ doc: document, host, layout: testLayout({ shadowRoot }) }], open: () => true, now: () => now });
    watch.start();
    // Not composed: the document doesn't see it, the root's listener does.
    toggle(byId("inside", closed), "beforetoggle");
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);

    now += MIN_VISIBLE_MS + 1;
    const later = document.createElement("x-later");
    document.body.append(later);
    const root = later.attachShadow({ mode: "open" });
    root.innerHTML = `<div id="late" popover="manual"></div>`;
    watch.adopt(root);
    toggle(byId("late", root), "toggle");
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);
  });

  it("takes the modal dialog around the host as under the popup", () => {
    const { watch, host, frame, advance } = setup();
    const dialog = document.createElement("dialog");
    document.body.append(dialog);
    dialog.append(host);
    Object.defineProperty(dialog, "matches", { value: (selector: string) => selector === ":modal", configurable: true });
    dialog.setAttribute(TOP_LAYER, "");
    watch.start();
    toggle(dialog, "beforetoggle");
    frame.run();
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(false);
    // Another modal dialog counts.
    const other = document.createElement("dialog");
    document.body.append(other);
    Object.defineProperty(other, "matches", { value: (selector: string) => selector === ":modal", configurable: true });
    advance(1);
    toggle(other, "toggle");
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);
  });

  it("stops once no popup is open, and starts again with the next", () => {
    const { watch, decoy, frame, advance, setOpen } = setup();
    watch.start();
    expect(frame.pending()).toBe(1);
    setOpen(false);
    frame.run();
    expect(frame.pending()).toBe(0);
    toggle(decoy, "beforetoggle");
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(false);
    advance(1);
    setOpen(true);
    watch.start();
    toggle(decoy, "beforetoggle");
    expect(watch.seenWithin(MIN_VISIBLE_MS)).toBe(true);
  });
});

describe("click gate", () => {
  it("acts on trusted clicks once the page was visible long enough", () => {
    let now = 1000;
    const gate = new ClickGate(() => now);
    expect(gate.allows({ isTrusted: true })).toBe(false);
    gate.setVisible(true);
    now += MIN_VISIBLE_MS - 1;
    expect(gate.allows({ isTrusted: true })).toBe(false);
    now += 1;
    expect(gate.allows({ isTrusted: true })).toBe(true);
    // A page can't click for the user.
    expect(gate.allows({ isTrusted: false })).toBe(false);
    // Hidden, then shown again: the wait starts over.
    gate.setVisible(false);
    gate.setVisible(true);
    expect(gate.allows({ isTrusted: true })).toBe(false);
  });

  it("waits for the content script to show a page it hid, whatever the browser says first", () => {
    let now = 1000;
    // The inline menu: hidden until it has its height. A browser without
    // IntersectionObserver v2 calls the page visible as it loads.
    const gate = new ClickGate(() => now, { hidden: true });
    gate.setVisible(true);
    now += MIN_VISIBLE_MS * 2;
    expect(gate.allows({ isTrusted: true })).toBe(false);
    gate.shown();
    now += MIN_VISIBLE_MS - 1;
    expect(gate.allows({ isTrusted: true })).toBe(false);
    now += 1;
    expect(gate.allows({ isTrusted: true })).toBe(true);

    // Shown, then visible by IntersectionObserver v2 a while later: the wait starts then.
    const tracked = new ClickGate(() => now, { hidden: true });
    tracked.shown();
    now += MIN_VISIBLE_MS;
    tracked.setVisible(true);
    expect(tracked.allows({ isTrusted: true })).toBe(false);
    now += MIN_VISIBLE_MS;
    expect(tracked.allows({ isTrusted: true })).toBe(true);
  });

  it("waits again when the page shows something new, as the save bar a newer offer", () => {
    let now = 1000;
    const gate = new ClickGate(() => now);
    // Before the page shows, there's nothing to wait from.
    gate.restart();
    gate.setVisible(true);
    now += MIN_VISIBLE_MS;
    expect(gate.allows({ isTrusted: true })).toBe(true);
    gate.restart();
    now += MIN_VISIBLE_MS - 1;
    expect(gate.allows({ isTrusted: true })).toBe(false);
    now += 1;
    expect(gate.allows({ isTrusted: true })).toBe(true);
  });

  it("uses IntersectionObserver v2 where the browser has it", () => {
    expect(tracksVisibility({ IntersectionObserverEntry: { prototype: { isVisible: false } } })).toBe(true);
    expect(tracksVisibility({ IntersectionObserverEntry: { prototype: {} } })).toBe(false);
    expect(tracksVisibility({})).toBe(false);
  });
});
