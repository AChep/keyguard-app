// The clicks an embedded page (inline menu, save bar) acts on: trusted, and
// only after the page was visible for a while. A page that slides the menu
// under the cursor of a click aimed elsewhere, or shows it through a
// transparent layer, gets nothing. Enter and Space on a button fire its click,
// trusted like the key: keyboard activation takes the same checks.
//
// Chromium reports real visibility with IntersectionObserver v2: occlusion,
// opacity, filters and transforms of every ancestor, across frames. Elsewhere,
// as on Firefox, the gate only waits, and the content script's checks
// (../content/guards.ts) are what sees a page cover the popup; they run before
// every action either way.
//
// A page its content script keeps hidden while it loads, as the inline menu
// until it has its height, can't see that on other browsers: its gate waits
// for the background's word that the frame shows, see [ClickGate.shown].

/** How long the page must be visible before a click counts. */
export const MIN_VISIBLE_MS = 400;

export class ClickGate {
  private visible = false;
  /** Whether the content script shows the page's frame. */
  private embedderShows: boolean;
  private visibleSince: number | null = null;

  /** [hidden]: the content script shows the frame later, and says so, see [shown]. */
  constructor(
    private readonly now: () => number = () => performance.now(),
    { hidden = false }: { hidden?: boolean } = {},
  ) {
    this.embedderShows = !hidden;
  }

  /** The page became visible, or stopped being visible, as the browser tells. */
  setVisible(visible: boolean): void {
    this.visible = visible;
    this.update();
  }

  /** The content script shows the frame now, which it hid while the page loaded. */
  shown(): void {
    this.embedderShows = true;
    this.update();
  }

  /**
   * The page shows something new, as the save bar a newer offer: the wait
   * starts over, so a click aimed at what showed before doesn't act on it.
   */
  restart(): void {
    if (this.visibleSince !== null) this.visibleSince = this.now();
  }

  private update() {
    if (!this.visible || !this.embedderShows) this.visibleSince = null;
    else this.visibleSince ??= this.now();
  }

  allows(event: { isTrusted: boolean }): boolean {
    return event.isTrusted && this.visibleSince !== null && this.now() - this.visibleSince >= MIN_VISIBLE_MS;
  }
}

interface VisibilityEntry {
  isIntersecting: boolean;
  isVisible?: boolean;
}

/** Whether this browser has IntersectionObserver v2. */
export function tracksVisibility(win: { IntersectionObserverEntry?: { prototype: object } }): boolean {
  return win.IntersectionObserverEntry !== undefined && "isVisible" in win.IntersectionObserverEntry.prototype;
}

/** Feeds a gate from the browser for the life of the page. */
export function observeVisibility(gate: ClickGate, doc: Document): void {
  const win = doc.defaultView!;
  const update = (visible: boolean) => gate.setVisible(visible && doc.visibilityState === "visible");
  doc.addEventListener("visibilitychange", () => {
    if (doc.visibilityState !== "visible") gate.setVisible(false);
    else if (!tracksVisibility(win)) gate.setVisible(true);
  });
  if (!tracksVisibility(win)) {
    update(true);
    return;
  }
  const observer = new win.IntersectionObserver(
    (entries) => {
      const entry = entries.at(-1) as VisibilityEntry | undefined;
      if (entry !== undefined) update(entry.isIntersecting && entry.isVisible === true);
    },
    { threshold: [0, 1], trackVisibility: true, delay: 100 } as IntersectionObserverInit,
  );
  observer.observe(doc.documentElement);
}
