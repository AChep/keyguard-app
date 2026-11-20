// DOM helpers for tests under happy-dom, which has computed styles but no
// layout: boxes, hit testing and visibility come from the test.
import type { Layout } from "../../src/lib/content/guards";

export interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

const DEFAULT_BOX: Box = { left: 10, top: 10, width: 200, height: 32 };

function parentOf(element: Element): Element | null {
  if (element.parentElement !== null) return element.parentElement;
  const root = element.getRootNode();
  return root !== element && "host" in root ? (root as ShadowRoot).host : null;
}

/** Whether nothing from the element up has `display: none`. */
export function displayed(element: Element): boolean {
  if (!element.isConnected) return false;
  for (let node: Element | null = element; node !== null; node = parentOf(node)) {
    if (getComputedStyle(node).display === "none") return false;
  }
  return true;
}

/** The attribute of the elements that [testLayout] puts in the top layer: happy-dom has no `:modal` or `:popover-open`. */
export const TOP_LAYER = "data-top-layer";

/**
 * A layout where every element has [DEFAULT_BOX] unless [boxes] says
 * otherwise, hit tests answer [hit] (by default: whatever is asked about),
 * closed shadow roots come from [shadowRoot], and the elements with
 * [TOP_LAYER] are in the top layer.
 */
export function testLayout(options: {
  boxes?: Map<Element, Box>;
  hit?: (x: number, y: number) => Element | null;
  topHit?: (x: number, y: number) => Element | null;
  viewport?: { width: number; height: number };
  shadowRoot?: (element: Element) => ShadowRoot | null;
} = {}): Layout {
  return {
    rendered: displayed,
    rect: (element) => options.boxes?.get(element) ?? DEFAULT_BOX,
    hit: options.hit ?? (() => null),
    topHit: options.topHit ?? (() => null),
    viewport: () => options.viewport ?? { width: 1024, height: 768 },
    style: (element) => getComputedStyle(element),
    shadowRoot: options.shadowRoot ?? ((element) => element.shadowRoot),
    topLayer: (root) => Array.from(root.querySelectorAll(`[${TOP_LAYER}]`)),
  };
}

/** Replaces the body with [html]. */
export function render(html: string): void {
  document.body.innerHTML = html;
}

export function byId<T extends Element = HTMLInputElement>(id: string, root: Document | ShadowRoot = document): T {
  const element = root.getElementById(id);
  if (element === null) throw new Error(`No #${id}`);
  return element as unknown as T;
}

/** Detection options for happy-dom: `display: none` hides. */
export const detectOptions = { rendered: (input: HTMLInputElement) => displayed(input) };
