// Checks a content script runs before it shows its UI or fills a field.
//
// - An opaque origin (a sandboxed frame, CSP `sandbox`, a `data:` frame) never
//   gets anything: its URL isn't the origin it runs as (CVE-2025-65203).
// - A field must be fillable: enabled, not `type=hidden`, rendered, and not
//   opted out by the page. A read-only one may turn writable on focus: a fill
//   checks once it has focus (./fill.ts), and the UI skips it.
// - DOM clickjacking (2025): a page can make the field or the extension's own
//   UI transparent, or cover it with decoys, and trick a click. Before showing
//   the UI and before acting on a click, nothing from the field up to the root
//   may be transparent or hidden, and the field and the menu must be what's on
//   top at their centers. Before acting on a click in the menu or the save
//   bar, the page may have no top layer of its own open, nor have had one
//   shortly before: decoys over them that no hit test sees can only be there.
//   A page can also fade or cover the frame that holds the menu: each parent
//   must show the frame where the menu is, with no top layer of its own and
//   no transform but a translation. The content script checks the parents of
//   its origin; a parent of another origin checks itself when the background
//   asks, on browsers without IntersectionObserver v2 (../ui/clickGate.ts).
//
// Layout comes through [Layout], so the checks run in tests without a browser.
import type { FillRefusal } from "../page/messages";
import { isIgnored } from "./detect";

export interface Layout {
  /** Whether the element has boxes: attached and not `display: none`. */
  rendered(element: Element): boolean;
  rect(element: Element): { left: number; top: number; width: number; height: number };
  /** The deepest element at a point of the viewport, through reachable shadow roots. */
  hit(x: number, y: number): Element | null;
  /** The top-level hit: what `document.elementFromPoint` returns. */
  topHit(x: number, y: number): Element | null;
  viewport(): { width: number; height: number };
  style(element: Element): CSSStyleDeclaration;
  /** The element's shadow root, closed ones included where the content script sees them. */
  shadowRoot(element: Element): ShadowRoot | null;
  /** The elements of [root], not of shadow roots in it, in the top layer: modal dialogs and open popovers. */
  topLayer(root: Document | ShadowRoot): Element[];
}

/**
 * A document's own origin. `window.origin` is the document's real one: "null"
 * in a sandbox even when the URL is https, and for data: URLs. An about:blank
 * or srcdoc frame inherits its parent's, while its `location.origin`, the
 * URL's, is "null".
 */
export function ownOrigin(win: { origin?: string; location: { origin: string } }): string {
  return win.origin ?? win.location.origin;
}

/** Whether a document runs with an opaque origin. Its URL then isn't who it is. */
export function isOpaqueOrigin(win: { origin?: string; location: { origin: string } }): boolean {
  return ownOrigin(win) === "null";
}

/**
 * Whether a fill may pick a field: enabled, not hidden, rendered, not opted
 * out. It may still be read-only, see [setFieldValue].
 */
export function isFillable(input: HTMLInputElement, layout: Pick<Layout, "rendered" | "style">): boolean {
  return fillableStyle(input, layout) !== null;
}

/** The computed style of a fillable field, for the checks after; `null` when it isn't fillable. */
function fillableStyle(input: HTMLInputElement, layout: Pick<Layout, "rendered" | "style">): CSSStyleDeclaration | null {
  if (!input.isConnected || input.disabled || input.type === "hidden" || isIgnored(input)) return null;
  if (!layout.rendered(input)) return null;
  const style = layout.style(input);
  return style.visibility !== "hidden" && style.visibility !== "collapse" ? style : null;
}

function opacityOf(style: CSSStyleDeclaration): number {
  const opacity = style.opacity === "" ? 1 : Number.parseFloat(style.opacity);
  // `filter: opacity(…)` fades too.
  const filter = /opacity\(\s*([\d.]+)(%?)\s*\)/.exec(style.filter ?? "");
  const filtered = filter === null ? 1 : Number.parseFloat(filter[1]!) / (filter[2] === "%" ? 100 : 1);
  return Math.min(Number.isNaN(opacity) ? 1 : opacity, Number.isNaN(filtered) ? 1 : filtered);
}

/** The parent of an element in the DOM, crossing out of a shadow root to its host. */
function parentOf(element: Element): Element | null {
  if (element.parentElement !== null) return element.parentElement;
  const root = element.getRootNode();
  return root !== element && "host" in root ? (root as ShadowRoot).host : null;
}

/** How many slots of a closed shadow root [flatParentOf] looks through. */
const MAX_SLOTS = 32;

/**
 * The parent in the flat tree, the one boxes nest in: the slot an element is
 * assigned to, its parent, or a shadow root's host. `assignedSlot` hides
 * slots of closed roots; with [shadowRootOf], the slot is looked up in the
 * parent's closed root, and `undefined` means it has too many slots to tell.
 */
export function flatParentOf(element: Element): Element | null;
export function flatParentOf(element: Element, shadowRootOf: (element: Element) => ShadowRoot | null): Element | null | undefined;
export function flatParentOf(
  element: Element,
  shadowRootOf?: (element: Element) => ShadowRoot | null,
): Element | null | undefined {
  if (element.assignedSlot) return element.assignedSlot;
  const parent = element.parentElement;
  if (parent !== null) {
    const closed = parent.shadowRoot === null ? (shadowRootOf?.(parent) ?? null) : null;
    return closed === null ? parent : closedSlotOf(element, closed);
  }
  const root = element.getRootNode();
  return root !== element && "host" in root ? (root as ShadowRoot).host : null;
}

/** The slot of [root], its parent's closed root, that a child is assigned to; its parent when none is. */
function closedSlotOf(child: Element, root: ShadowRoot): Element | undefined {
  const slots = root.querySelectorAll("slot");
  for (let i = 0; i < slots.length; i++) {
    if (i === MAX_SLOTS) return undefined;
    const slot = slots[i]!;
    if (slot instanceof HTMLSlotElement && slot.assignedNodes().includes(child)) return slot;
  }
  // Not assigned: it has no box either.
  return root.host;
}

/**
 * Why an element isn't fully visible through its ancestors in the flat tree,
 * up to and including `<html>`: any of them with opacity below 1, or the
 * element itself with `visibility: hidden`. `null` when it's fully visible.
 * [style] is the element's own, when the caller read it already.
 */
export function transparency(
  element: Element,
  layout: Pick<Layout, "style" | "shadowRoot">,
  style: CSSStyleDeclaration = layout.style(element),
): "transparent" | "hidden" | null {
  if (style.visibility === "hidden" || style.visibility === "collapse") return "hidden";
  const shadowRootOf = (node: Element) => layout.shadowRoot(node);
  for (let node: Element | null | undefined = element; node !== null; node = flatParentOf(node, shadowRootOf)) {
    // A slot it can't find fails closed. A slot is `display: contents` unless
    // styled: without a box, its own opacity fades nothing, but it counts.
    if (node === undefined || opacityOf(node === element ? style : layout.style(node)) < 1) return "transparent";
  }
  return null;
}

function center(rect: { left: number; top: number; width: number; height: number }) {
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
}

function inViewport(point: { x: number; y: number }, viewport: { width: number; height: number }) {
  return point.x >= 0 && point.y >= 0 && point.x < viewport.width && point.y < viewport.height;
}

/**
 * Whether `inner` is `outer` or inside it in the DOM, crossing shadow roots:
 * the dialogs the host went into, and fields, inputs, take no slotted content.
 */
function isWithin(inner: Element, outer: Element): boolean {
  for (let node: Element | null = inner; node !== null; node = parentOf(node)) {
    if (node === outer) return true;
  }
  return false;
}

/**
 * Whether the page shows a field: fully opaque and visible, in the viewport,
 * and on top at its center (anything else there is covering it).
 */
export function fieldExposure(field: HTMLInputElement, layout: Layout): FillRefusal | null {
  // Read-only with focus: the page shows the field, it doesn't ask for it.
  const style = field.readOnly ? null : fillableStyle(field, layout);
  if (style === null || transparency(field, layout, style) !== null) return "obscured";
  const rect = layout.rect(field);
  if (rect.width < 4 || rect.height < 4) return "obscured";
  const point = center(rect);
  if (!inViewport(point, layout.viewport())) return "obscured";
  const hit = layout.hit(point.x, point.y);
  return hit !== null && isWithin(hit, field) ? null : "obscured";
}

/**
 * Whether the extension's popup (menu or save bar) is what the user sees at
 * its center: open, fully opaque, the top hit there, and with no top-layer
 * element of the page open, see [pageTopLayer].
 *
 * [host] is the element of the page that holds the extension's closed shadow
 * root; [popup] is the popover in it.
 */
export function popupExposure(host: Element, popup: HTMLElement, layout: Layout, doc: Document): FillRefusal | null {
  if (!layout.rendered(popup)) return "obscured";
  // Top-layer elements escape their ancestors' effects, but the page can
  // still style the host; check both.
  if (transparency(popup, layout) !== null) return "obscured";
  const point = center(layout.rect(popup));
  if (!inViewport(point, layout.viewport())) return "obscured";
  // From the page, every node of the closed shadow root is the host. In a
  // dialog in a shadow root of the page, the document hits that root's host;
  // look through.
  const top = host.getRootNode() === host.ownerDocument ? layout.topHit(point.x, point.y) : layout.hit(point.x, point.y);
  if (top !== host) return "obscured";
  return pageTopLayer(host, layout, doc) ? "obscured" : null;
}

/**
 * Whether the page has an element of its own in the top layer, in its
 * document or in any shadow root the content script sees: an open popover, a
 * modal dialog or the fullscreen element. Decoys with `pointer-events: none`
 * don't hit, and the page can put them over the extension's popups only from
 * the top layer, where an element opened after the popup goes over it. Such
 * an element may be small, as a 1x1 popover, and cover the popup with a
 * `position: fixed` child or its `::backdrop`: any of them counts, wherever it
 * is. Only the modal dialogs around [host] don't: the popup opened over them,
 * and the content script closes it when one closes, as to show it again.
 */
function pageTopLayer(host: Element, layout: Layout, doc: Document): boolean {
  if (doc.fullscreenElement) return true;
  for (const root of rootsOf(doc, host, layout)) if (hasPageTopLayer(root, host, layout)) return true;
  return false;
}

/** Whether [root] has an element of the page in the top layer: any but a modal dialog around [host]. */
function hasPageTopLayer(root: Document | ShadowRoot, host: Element, layout: Pick<Layout, "topLayer">): boolean {
  return layout.topLayer(root).some((element) => element.localName !== "dialog" || !isWithin(host, element));
}

/**
 * The document, then each shadow root in it that [layout] sees, as they're
 * found: one's own roots are walked once it's taken. The extension's own
 * popups, in the host's root, aren't the page's.
 */
function* rootsOf(doc: Document, host: Element, layout: Pick<Layout, "shadowRoot">): Generator<Document | ShadowRoot> {
  const roots: (Document | ShadowRoot)[] = [doc];
  for (let i = 0; i < roots.length; i++) {
    const root = roots[i]!;
    yield root;
    for (const element of Array.from(root.querySelectorAll("*"))) {
      const shadow = element === host ? null : layout.shadowRoot(element);
      if (shadow !== null) roots.push(shadow);
    }
  }
}

/**
 * A document whose top layer [TopLayerWatch] follows: the document of the
 * extension's UI with its [host], or a same-origin ancestor's with the frame
 * element that holds the one below as [host].
 */
export interface WatchedDocument {
  doc: Document;
  host: Element;
  layout: Pick<Layout, "shadowRoot" | "topLayer">;
}

/** What a [TopLayerWatch] needs. */
export interface TopLayerWatchDeps {
  /** The documents to watch, read as a popup opens: the UI's first. */
  documents(): WatchedDocument[];
  /** Whether the menu or the save bar is open: the watch stops on the first frame without one. */
  open(): boolean;
  now?(): number;
}

/**
 * When the page last had an element of its own in the top layer, see
 * [pageTopLayer], while the menu or the save bar is open. The checks before
 * an action run once the click reached the popup: on Firefox, which has no
 * IntersectionObserver v2, a decoy that hid on mousedown, or as the window
 * lost focus to the popup's frame, is gone by then. Each animation frame
 * samples the top layer of the documents and of the shadow roots found when
 * the popup opened or since; each `beforetoggle`, `toggle` and
 * `fullscreenchange` counts too, as for a decoy shown and hidden between two
 * frames. Events of the extension's own popovers stay in its closed root.
 */
export class TopLayerWatch {
  private seenAt = Number.NEGATIVE_INFINITY;
  private running: {
    abort: AbortController;
    /** Each root watched, with the document it's in. */
    roots: Map<Document | ShadowRoot, WatchedDocument>;
    documents: WatchedDocument[];
    frame: number;
  } | null = null;
  private readonly now: () => number;

  constructor(private readonly deps: TopLayerWatchDeps) {
    this.now = deps.now ?? (() => performance.now());
  }

  /** A popup opened: watches until none is open. */
  start(): void {
    if (this.running !== null) return;
    const abort = new AbortController();
    const documents = this.deps.documents();
    this.running = { abort, roots: new Map(), documents, frame: 0 };
    for (const watched of documents) {
      for (const root of rootsOf(watched.doc, watched.host, watched.layout)) this.watch(root, watched);
      watched.doc.addEventListener("fullscreenchange", () => this.mark(), { capture: true, signal: abort.signal });
    }
    this.onFrame();
  }

  /** Watches a shadow root of the UI's document found while a popup is open, as one attached since. */
  adopt(root: ShadowRoot): void {
    const watched = this.running?.documents[0];
    if (watched !== undefined) this.watch(root, watched);
  }

  /** Whether the page had an element of its own in the top layer within the last [ms]. */
  seenWithin(ms: number): boolean {
    return this.now() - this.seenAt <= ms;
  }

  stop(): void {
    const running = this.running;
    if (running === null) return;
    this.running = null;
    running.abort.abort();
    if (running.frame !== 0) running.documents[0]?.doc.defaultView?.cancelAnimationFrame(running.frame);
  }

  private watch(root: Document | ShadowRoot, watched: WatchedDocument) {
    const running = this.running;
    if (running === null || running.roots.has(root)) return;
    running.roots.set(root, watched);
    const options = { capture: true, signal: running.abort.signal };
    root.addEventListener("beforetoggle", (event) => this.onToggle(event, watched.host), options);
    root.addEventListener("toggle", (event) => this.onToggle(event, watched.host), options);
  }

  private mark() {
    this.seenAt = this.now();
  }

  /**
   * A popover of the page opening or closing, or a modal dialog but one
   * around [host]: one closing is still modal as `beforetoggle` fires, one
   * opening as `toggle` does. `<details>` toggles too, outside the top layer.
   */
  private onToggle(event: Event, host: Element) {
    const target = event.target;
    if (!(target instanceof Element) || target === host) return;
    const popover = target.hasAttribute("popover");
    if (!popover && (target.localName !== "dialog" || !matches(target, ":modal"))) return;
    if (popover || !isWithin(host, target)) this.mark();
  }

  private onFrame() {
    const running = this.running;
    if (running === null) return;
    running.frame = 0;
    if (!this.deps.open()) {
      this.stop();
      return;
    }
    let seen = running.documents.some(({ doc }) => doc.fullscreenElement);
    for (const [root, { host, layout }] of running.roots) seen ||= hasPageTopLayer(root, host, layout);
    if (seen) this.mark();
    running.frame = running.documents[0]?.doc.defaultView?.requestAnimationFrame(() => this.onFrame()) ?? 0;
  }
}

/** `element.matches`, false for a selector the browser doesn't know. */
function matches(element: Element, selector: string): boolean {
  try {
    return element.matches(selector);
  } catch {
    return false;
  }
}

// region Frames

/** How many same-origin ancestors [sameOriginFrames] goes up. */
const MAX_FRAME_DEPTH = 32;

/**
 * The frame elements that hold [win] and its ancestors of the same origin,
 * from the innermost: each is in the document of the next. They stop at the
 * top, or below a parent of another origin, whose `frameElement` is `null`.
 * `null` when they go deeper than MAX_FRAME_DEPTH.
 */
export function sameOriginFrames(win: Pick<Window, "frameElement">): Element[] | null {
  const frames: Element[] = [];
  for (let current: Pick<Window, "frameElement"> | null = win; current !== null; ) {
    let frame: Element | null | undefined;
    try {
      frame = current.frameElement;
    } catch {
      frame = null;
    }
    if (!frame) return frames;
    if (frames.length === MAX_FRAME_DEPTH) return null;
    frames.push(frame);
    current = frame.ownerDocument.defaultView;
  }
  return frames;
}

/** The 2D matrix of a computed `transform`, `null` for one that isn't 2D. */
function matrixOf(transform: string): number[] | null {
  if (transform === "" || transform === "none") return [1, 0, 0, 1, 0, 0];
  const values = /^matrix(3d)?\(([^)]*)\)$/.exec(transform);
  if (values === null) return null;
  const m = values[2]!.split(",").map((value) => Number.parseFloat(value));
  if (values[1] === undefined) return m.length === 6 ? m : null;
  // A 3D matrix that draws as a 2D one, as `translateZ(0)` may give.
  const flat = [2, 3, 6, 7, 8, 9, 11, 14].every((i) => m[i] === 0) && m[10] === 1 && m[15] === 1;
  return m.length === 16 && flat ? [m[0]!, m[1]!, m[4]!, m[5]!, m[12]!, m[13]!] : null;
}

/**
 * Whether an element only moves what it draws, if anything: no scale, zoom,
 * rotation, skew or 3D. The center of a popup in a frame then maps into the
 * frame's parent by offsets alone.
 */
function onlyTranslates(style: CSSStyleDeclaration): boolean {
  const matrix = matrixOf(style.transform ?? "");
  if (matrix === null || matrix[0] !== 1 || matrix[1] !== 0 || matrix[2] !== 0 || matrix[3] !== 1) return false;
  const none = (value: string | undefined) => value === undefined || value === "" || value === "none";
  if (!none(style.scale) || !none(style.rotate)) return false;
  // A translation along z changes the size under a perspective.
  if (!none(style.translate) && style.translate.trim().split(/\s+/).length > 2) return false;
  const zoom = (style as CSSStyleDeclaration & { zoom?: string }).zoom;
  return zoom === undefined || zoom === "" || zoom === "normal" || Number.parseFloat(zoom) === 1;
}

/**
 * Whether the parent shows a frame where its popup is: [frame] is the frame
 * element in [doc], the parent's document, and [point] the popup's center in
 * the frame's viewport. Like [popupExposure], one level up: nothing from the
 * frame up is transparent, hidden or transformed past a translation, the
 * frame is on top at that point, and the parent has no top layer of its own
 * open but the modal dialogs around the frame. Resolves with [point] in the
 * parent's viewport, `null` when the parent doesn't show the frame there.
 */
export function frameExposure(
  frame: Element,
  point: { x: number; y: number },
  layout: Layout,
  doc: Document,
): { x: number; y: number } | null {
  if (!layout.rendered(frame) || transparency(frame, layout) !== null) return null;
  for (let node: Element | null | undefined = frame; node !== null; node = flatParentOf(node, (it) => layout.shadowRoot(it))) {
    if (node === undefined || !onlyTranslates(layout.style(node))) return null;
  }
  // The frame's viewport starts inside its border and padding.
  const rect = layout.rect(frame);
  const style = layout.style(frame);
  const px = (value: string) => Number.parseFloat(value) || 0;
  const mapped = {
    x: rect.left + px(style.borderLeftWidth) + px(style.paddingLeft) + point.x,
    y: rect.top + px(style.borderTopWidth) + px(style.paddingTop) + point.y,
  };
  if (!inViewport(mapped, layout.viewport()) || layout.hit(mapped.x, mapped.y) !== frame) return null;
  return pageTopLayer(frame, layout, doc) ? null : mapped;
}

/** The center of [popup], in its document's viewport. */
export function popupCenter(popup: Element, layout: Pick<Layout, "rect">): { x: number; y: number } {
  return center(layout.rect(popup));
}

/**
 * The frame element of [doc], or of a shadow root in it that [layout] sees,
 * whose frame has the ID [id] by [frameIdOf], Firefox's `runtime.getFrameId`.
 * Only `<iframe>` and `<frame>`: a frame in another element isn't found, and
 * its check fails closed.
 */
export function frameElementOf(
  doc: Document,
  host: Element,
  layout: Pick<Layout, "shadowRoot">,
  id: number,
  frameIdOf: (element: Element) => number,
): Element | null {
  for (const root of rootsOf(doc, host, layout)) {
    for (const element of Array.from(root.querySelectorAll("iframe, frame"))) {
      try {
        if (frameIdOf(element) === id) return element;
      } catch {
        // Not a frame the browser knows.
      }
    }
  }
  return null;
}

// endregion

/** The [Layout] of a live document. */
export function browserLayout(doc: Document, shadowRootOf: (element: Element) => ShadowRoot | null): Layout {
  const win = doc.defaultView!;
  return {
    rendered: (element) => element.isConnected && element.getClientRects().length > 0,
    rect: (element) => element.getBoundingClientRect(),
    topHit: (x, y) => doc.elementFromPoint(x, y),
    hit: (x, y) => {
      let element = doc.elementFromPoint(x, y);
      // Into shadow roots: their hosts are what the document sees.
      for (let depth = 0; element !== null && depth < 32; depth++) {
        const root = shadowRootOf(element);
        const inner = root?.elementFromPoint(x, y) ?? null;
        if (inner === null || inner === element) break;
        element = inner;
      }
      return element;
    },
    viewport: () => ({ width: win.innerWidth, height: win.innerHeight }),
    style: (element) => win.getComputedStyle(element),
    shadowRoot: shadowRootOf,
    topLayer: (root) => {
      const found: Element[] = [];
      for (const selector of [":modal", ":popover-open"]) {
        try {
          found.push(...Array.from(root.querySelectorAll(selector)));
        } catch {
          // A browser without the selector has no such elements.
        }
      }
      return found;
    },
  };
}
