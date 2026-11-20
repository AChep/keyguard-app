// Finds the shadow roots of a changing page: walks the document once, then
// only what's added to it, and observes each root it finds. Busy pages add
// nodes all the time; walking every element of them after each change costs
// too much.
//
// `attachShadow` makes no mutation record, so a root attached after its host
// was added is found when a field in it gets focus, see [PageWatcher.adopt].
import { canHostShadow } from "./detect";

export interface PageWatcherOptions {
  /** The shadow root of a host: open or, where the browser lets extensions, closed. */
  shadowRootOf(element: Element): ShadowRoot | null;
  /** A shadow root seen for the first time; it's observed already. */
  found(root: ShadowRoot): void;
  /**
   * After a scan: elements were added, or inputs changed type, hints or
   * `readonly`. [inputs]: an input or a label was among them, or an input
   * changed; else forms are as they were.
   */
  changed(inputs: boolean): void;
  /** How long changes gather before a scan. */
  debounceMs: number;
}

export class PageWatcher {
  private readonly known = new WeakSet<ShadowRoot>();
  private readonly observer: MutationObserver;
  /** Elements added since the last scan; dropped at the scan. */
  private added = new Set<Element>();
  /** Whether an input changed since the last scan. */
  private inputChanged = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly options: PageWatcherOptions) {
    this.observer = new MutationObserver((records) => this.onMutations(records));
  }

  /** Walks the whole document, then watches it. */
  start(doc: Document): void {
    this.observe(doc);
    this.visit(doc);
    this.options.changed(true);
  }

  stop(): void {
    this.observer.disconnect();
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    this.added.clear();
    this.inputChanged = false;
  }

  /** Takes in the shadow roots around an element, as a field that got focus. */
  adopt(element: Element): void {
    let outer: ShadowRoot | null = null;
    let root = element.getRootNode();
    while (isShadowRoot(root) && !this.known.has(root)) {
      this.register(root);
      outer = root;
      root = root.host.getRootNode();
    }
    if (outer !== null) this.visit(outer);
  }

  private onMutations(records: MutationRecord[]) {
    // Only new elements, or inputs that changed type, hints or `readonly`, matter.
    let relevant = false;
    for (const record of records) {
      if (record.type === "attributes") {
        if ((record.target as Element).localName === "input") {
          relevant = true;
          this.inputChanged = true;
        }
        continue;
      }
      for (const node of Array.from(record.addedNodes)) {
        if (node.nodeType !== Node.ELEMENT_NODE) continue;
        this.added.add(node as Element);
        relevant = true;
      }
    }
    if (!relevant || this.timer !== null) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.scan();
    }, this.options.debounceMs);
  }

  private scan() {
    const added = this.added;
    this.added = new Set();
    let inputs = this.inputChanged;
    this.inputChanged = false;
    for (const element of added) {
      // Removed since: walked again if it comes back. In another added one: walked with it.
      if (!element.isConnected || hasAncestorIn(element, added)) continue;
      if (this.visitElement(element)) inputs = true;
      if (this.visit(element)) inputs = true;
    }
    this.options.changed(inputs);
  }

  private observe(root: Document | ShadowRoot) {
    // A field turns writable on a click or a timer too, not only on focus.
    const attributeFilter = ["type", "autocomplete", "readonly"];
    this.observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter });
  }

  private register(root: ShadowRoot) {
    if (this.known.has(root)) return;
    this.known.add(root);
    this.observe(root);
    this.options.found(root);
  }

  /** Takes in the shadow roots under a node, and theirs; whether it saw an input or a label. */
  private visit(node: Document | ShadowRoot | Element): boolean {
    let inputs = false;
    for (const element of node.querySelectorAll("*")) if (this.visitElement(element)) inputs = true;
    return inputs;
  }

  /** Takes in an element's shadow root, and what's in it; whether it saw an input or a label. */
  private visitElement(element: Element): boolean {
    const name = element.localName;
    if (name === "input" || name === "label") return true;
    if (!canHostShadow(element)) return false;
    const shadow = this.options.shadowRootOf(element);
    if (shadow === null) return false;
    this.register(shadow);
    return this.visit(shadow);
  }
}

function isShadowRoot(node: Node): node is ShadowRoot {
  return node.nodeType === Node.DOCUMENT_FRAGMENT_NODE && "host" in node;
}

/** Whether an ancestor of [element] is one of [elements]. */
function hasAncestorIn(element: Element, elements: ReadonlySet<Element>): boolean {
  for (let node = element.parentElement; node !== null; node = node.parentElement) {
    if (elements.has(node)) return true;
  }
  return false;
}
