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
//   top at their centers. The icon, and the menu as it opens, take a field on
//   top at a quarter or the middle of its width, or under its own label there,
//   as one a floating label lies over; a click takes it at the center only.
//   Fills from the menu and of password steps take fully opaque fields only.
//   Fills from the popup or the shortcut take fields at MIN_SHOWN_OPACITY or
//   more, as a form dimmed while busy, but not an invisible honeypot; the
//   shortcut takes the focused field at any, as an invisible code input under
//   drawn boxes. Before acting on a click in the menu or the save bar, the
//   page may have no top layer of its own open, nor have had one shortly
//   before, but what was open as the popup opened and stayed open since:
//   decoys over them that no hit test sees can only be there, and only once
//   opened after them.
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

/** The opacity from which fills from the popup or the shortcut take a field as shown, see [fieldRefusal]. */
export const MIN_SHOWN_OPACITY = 0.1;

/**
 * Why a fill may not write into a field: it left the document, is disabled,
 * opted out by the page, `type=hidden`, not rendered, `visibility: hidden`
 * or shown below [minOpacity], see [opacityOf]; `null` when it may. It may
 * still be read-only, see [setFieldValue].
 */
export function fieldRefusal(
  input: HTMLInputElement,
  layout: Pick<Layout, "rendered" | "style" | "shadowRoot">,
  minOpacity = 1,
): FillRefusal | null {
  if (!input.isConnected) return "field_gone";
  if (input.disabled) return "not_editable";
  if (isIgnored(input)) return "field_opted_out";
  if (input.type === "hidden") return "field_hidden";
  const style = shownStyle(input, layout);
  return style === null || opacityOf(input, layout, style) < minOpacity ? "field_hidden" : null;
}

/** The computed style of an element with boxes and not `visibility: hidden`; `null` when the page doesn't show it. */
export function shownStyle(element: Element, layout: Pick<Layout, "rendered" | "style">): CSSStyleDeclaration | null {
  if (!layout.rendered(element)) return null;
  const style = layout.style(element);
  return style.visibility !== "hidden" && style.visibility !== "collapse" ? style : null;
}

function styleOpacity(style: CSSStyleDeclaration): number {
  const opacity = style.opacity === "" ? 1 : Number.parseFloat(style.opacity);
  // `filter: opacity(…)` fades too.
  const filter = /opacity\(\s*([\d.]+)(%?)\s*\)/.exec(style.filter ?? "");
  const filtered = filter === null ? 1 : Number.parseFloat(filter[1]!) / (filter[2] === "%" ? 100 : 1);
  return Math.min(Number.isNaN(opacity) ? 1 : opacity, Number.isNaN(filtered) ? 1 : filtered);
}

/** The parent of an element in the DOM, crossing out of a shadow root to its host. */
export function parentOf(element: Element): Element | null {
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
export function flatParentOf(
  element: Element,
  shadowRootOf: (element: Element) => ShadowRoot | null,
): Element | null | undefined;
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
  return opacityOf(element, layout, style) < 1 ? "transparent" : null;
}

/**
 * How opaque an element shows through its ancestors in the flat tree, up to
 * and including `<html>`: the product of their opacities. [style] is the
 * element's own, when the caller read it already.
 */
export function opacityOf(
  element: Element,
  layout: Pick<Layout, "style" | "shadowRoot">,
  style: CSSStyleDeclaration = layout.style(element),
): number {
  const shadowRootOf = (node: Element) => layout.shadowRoot(node);
  let opacity = 1;
  for (let node: Element | null | undefined = element; node !== null; node = flatParentOf(node, shadowRootOf)) {
    // A slot it can't find fails closed. A slot is `display: contents` unless
    // styled: without a box, its own opacity fades nothing, but it counts.
    if (node === undefined) return 0;
    opacity *= Math.min(styleOpacity(node === element ? style : layout.style(node)), 1);
  }
  return opacity;
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
export function isWithin(inner: Element, outer: Element): boolean {
  for (let node: Element | null = inner; node !== null; node = parentOf(node)) {
    if (node === outer) return true;
  }
  return false;
}

/**
 * Whether the page shows a field: not read-only, one a fill may write, see
 * [fieldRefusal], at least 4x4 px, in the viewport, and on top at its
 * center (anything else there is covering it). Before acting on a click.
 */
export function fieldExposure(field: HTMLInputElement, layout: Layout): FillRefusal | null {
  const box = fieldBox(field, layout);
  if (typeof box === "string") return box;
  const point = center(box);
  const hit = layout.hit(point.x, point.y);
  return hit !== null && isWithin(hit, field) ? null : "field_covered";
}

/**
 * Whether the page shows a field enough for the icon and to open the menu:
 * as [fieldExposure], but on top at the left quarter, the center or the right
 * quarter of its middle, those in the viewport, or under one of its labels
 * there. A floating label, a mask or a placeholder layer may lie over the
 * center; a click in the menu still takes [fieldExposure].
 */
export function fieldShown(field: HTMLInputElement, layout: Layout): FillRefusal | null {
  const box = fieldBox(field, layout);
  if (typeof box === "string") return box;
  const viewport = layout.viewport();
  const labels = Array.from(field.labels ?? []);
  const y = box.top + box.height / 2;
  for (const share of [0.5, 0.25, 0.75]) {
    const point = { x: box.left + box.width * share, y };
    if (!inViewport(point, viewport)) continue;
    const hit = layout.hit(point.x, point.y);
    if (hit !== null && (isWithin(hit, field) || labels.some((label) => isWithin(hit, label)))) return null;
  }
  return "field_covered";
}

/**
 * The checks of [fieldExposure] and [fieldShown] before the hit test: the
 * field's box when they pass, with its center in the viewport.
 */
function fieldBox(
  field: HTMLInputElement,
  layout: Layout,
): FillRefusal | { left: number; top: number; width: number; height: number } {
  // Read-only with focus: the page shows the field, it doesn't ask for it.
  if (field.readOnly && field.isConnected) return "not_editable";
  const refusal = fieldRefusal(field, layout);
  if (refusal !== null) return refusal;
  const rect = layout.rect(field);
  if (rect.width < 4 || rect.height < 4) return "field_hidden";
  return inViewport(center(rect), layout.viewport()) ? rect : "field_off_screen";
}

/**
 * Whether the extension's popup (menu or save bar) is what the user sees at
 * its center: open, fully opaque, the top hit there, and with no top-layer
 * element of the page open but those of [baseline], see [pageTopLayer].
 *
 * [host] is the element of the page that holds the extension's closed shadow
 * root; [popup] is the popover in it. [baseline] is what [TopLayerWatch] saw
 * open in [doc] as the popup opened.
 */
export function popupExposure(
  host: Element,
  popup: HTMLElement,
  layout: Layout,
  doc: Document,
  baseline?: ReadonlySet<Element>,
): FillRefusal | null {
  if (!layout.rendered(popup)) return "popup_covered";
  // Top-layer elements escape their ancestors' effects, but the page can
  // still style the host; check both.
  if (transparency(popup, layout) !== null) return "popup_covered";
  const point = center(layout.rect(popup));
  if (!inViewport(point, layout.viewport())) return "popup_covered";
  // From the page, every node of the closed shadow root is the host. In a
  // dialog in a shadow root of the page, the document hits that root's host;
  // look through.
  const top =
    host.getRootNode() === host.ownerDocument ? layout.topHit(point.x, point.y) : layout.hit(point.x, point.y);
  if (top !== host) return "popup_covered";
  return pageTopLayer(host, layout, doc, baseline) ? "popup_covered" : null;
}

/**
 * Whether the page has an element of its own in the top layer, in its
 * document or in any shadow root the content script sees: an open popover, a
 * modal dialog or the fullscreen element. Decoys with `pointer-events: none`
 * don't hit, and the page can put them over the extension's popups only from
 * the top layer, where an element opened after the popup goes over it. Such
 * an element may be small, as a 1x1 popover, and cover the popup with a
 * `position: fixed` child or its `::backdrop`: any of them counts, wherever it
 * is. The modal dialogs around [host] don't: the popup opened over them, and
 * the content script closes it when one closes, as to show it again. Nor do
 * the elements of [baseline]: open since before the popup, they're under it.
 * The fullscreen element always counts.
 */
function pageTopLayer(host: Element, layout: Layout, doc: Document, baseline?: ReadonlySet<Element>): boolean {
  if (doc.fullscreenElement) return true;
  for (const root of rootsOf(doc, host, layout)) if (hasPageTopLayer(root, host, layout, baseline)) return true;
  return false;
}

/** The elements of the page in the top layer of [root]: any but a modal dialog around [host]. */
function pageTopLayerOf(root: Document | ShadowRoot, host: Element, layout: Pick<Layout, "topLayer">): Element[] {
  return layout.topLayer(root).filter((element) => element.localName !== "dialog" || !isWithin(host, element));
}

/** Whether [root] has an element of the page in the top layer, see [pageTopLayerOf], but those of [baseline]. */
function hasPageTopLayer(
  root: Document | ShadowRoot,
  host: Element,
  layout: Pick<Layout, "topLayer">,
  baseline?: ReadonlySet<Element>,
): boolean {
  return pageTopLayerOf(root, host, layout).some((element) => baseline?.has(element) !== true);
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
 *
 * What was open in the UI's document as the popup opened, its [baseline], is
 * under the popup and doesn't count while it stays open, nor as it closes.
 * Its toggle or `close`, or a frame without it, takes it out: open again, it
 * counts. The ancestors' documents have none: the frame that holds the popup
 * isn't in the top layer, and any of theirs can cover it.
 */
export class TopLayerWatch {
  private seenAt = Number.NEGATIVE_INFINITY;
  private running: {
    abort: AbortController;
    /** Each root watched, with the document it's in. */
    roots: Map<Document | ShadowRoot, WatchedDocument>;
    documents: WatchedDocument[];
    baseline: Set<Element>;
    /** Elements of the baseline that closed, while they stay closed: their closing events don't count. */
    closed: Set<Element>;
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
    const baseline = new Set<Element>();
    this.running = { abort, roots: new Map(), documents, baseline, closed: new Set(), frame: 0 };
    for (const watched of documents) {
      for (const root of rootsOf(watched.doc, watched.host, watched.layout)) {
        this.watch(root, watched);
        if (watched !== documents[0]) continue;
        for (const element of pageTopLayerOf(root, watched.host, watched.layout)) baseline.add(element);
      }
      watched.doc.addEventListener("fullscreenchange", () => this.mark(), { capture: true, signal: abort.signal });
    }
    this.onFrame();
  }

  /**
   * Watches a shadow root of the UI's document found while a popup is open, as
   * one attached since. What's open in it already counts: it may be newer than
   * the popup.
   */
  adopt(root: ShadowRoot): void {
    const watched = this.running?.documents[0];
    if (watched !== undefined) this.watch(root, watched);
  }

  /** The elements of the page open in the top layer of the UI's document since before the popup, see [popupExposure]. */
  get baseline(): ReadonlySet<Element> {
    return this.running?.baseline ?? EMPTY;
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
    // A modal dialog closed, where the browser has no toggle events for dialogs.
    root.addEventListener("close", (event) => this.leaveBaseline(event.target), options);
  }

  private mark() {
    this.seenAt = this.now();
  }

  /** [element] closed: it leaves the baseline, if in it. */
  private leaveBaseline(element: EventTarget | null) {
    const running = this.running;
    if (running === null || !(element instanceof Element) || !running.baseline.delete(element)) return;
    running.closed.add(element);
  }

  /**
   * A popover of the page opening or closing, or a modal dialog but one
   * around [host]: one closing is still modal as `beforetoggle` fires, one
   * opening as `toggle` does. `<details>` toggles too, outside the top layer.
   * One of the [baseline] closing leaves it and doesn't count; one opening
   * counts: it closed unseen, as out of the document. The `toggle` of its
   * opening may come after the popup opened.
   */
  private onToggle(event: Event, host: Element) {
    const target = event.target;
    if (!(target instanceof Element) || target === host) return;
    const running = this.running;
    const state = (event as Event & { newState?: unknown }).newState;
    if (running !== null && state === "closed" && (running.baseline.has(target) || running.closed.has(target))) {
      this.leaveBaseline(target);
      return;
    }
    if (running?.baseline.has(target) && event.type === "toggle" && state === "open") return;
    running?.baseline.delete(target);
    running?.closed.delete(target);
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
    const open = new Set<Element>();
    for (const [root, { host, layout }] of running.roots) {
      for (const element of pageTopLayerOf(root, host, layout)) {
        open.add(element);
        seen ||= !running.baseline.has(element);
      }
    }
    // Closed unseen: open again, it's over the popup.
    for (const element of running.baseline) if (!open.has(element)) this.leaveBaseline(element);
    if (seen) this.mark();
    running.frame = running.documents[0]?.doc.defaultView?.requestAnimationFrame(() => this.onFrame()) ?? 0;
  }
}

const EMPTY: ReadonlySet<Element> = new Set();

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
  for (let current: Pick<Window, "frameElement"> | null = win; current !== null;) {
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
  for (
    let node: Element | null | undefined = frame;
    node !== null;
    node = flatParentOf(node, (it) => layout.shadowRoot(it))
  ) {
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
