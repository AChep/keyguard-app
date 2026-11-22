// Which frame a content-script message came from, and where its answer may go.
// Everything here comes from the browser's `MessageSender`, never from the
// message: a page controls what its frames' content scripts see.

/** The parts of `tabs.Tab` the background reads. */
export interface TabInfo {
  /** The URL of its top frame, when the extension can see it. */
  url?: string;
  /** In a private window, such as an incognito one. */
  incognito?: boolean;
  /** The selected tab of its window. */
  active?: boolean;
}

/** The parts of `runtime.MessageSender` the background reads. */
export interface MessageSenderLike {
  id?: string;
  url?: string;
  origin?: string;
  frameId?: number;
  documentId?: string;
  documentLifecycle?: string;
  tab?: TabInfo & { id?: number };
}

/** A frame of a tab, as the browser identified it. */
export interface FrameRef {
  tabId: number;
  frameId: number;
  /** The document, on browsers that report it; a navigation gives a new one. */
  documentId?: string;
  /**
   * The token of the content script's document, on every browser, see
   * ../page/messages.ts. Absent for a port of the webauthn bridge, which gets
   * no commands.
   */
  doc?: string;
  /** The frame's URL: what `suggest` matches and `fill` is for. */
  url: string;
  origin: string;
  /**
   * The origin of the tab's top frame, when it isn't [origin]: the page that
   * embeds the frame. `null` when the tab's URL doesn't tell: it isn't
   * http(s), as an about:blank popup with its opener's origin, or the browser
   * didn't report it. A release then asks the top frame, see ./pages.ts.
   */
  topOrigin?: string | null;
  /** The tab is in a private window: requests for the frame say so, see [privateWindow]. */
  privateWindow?: true;
}

function isWebUrl(url: URL): boolean {
  return url.protocol === "https:" || url.protocol === "http:";
}

/** [href] parsed, or `null` when it isn't a URL. */
function parseUrl(href: string | undefined): URL | null {
  if (href === undefined) return null;
  try {
    return new URL(href);
  } catch {
    return null;
  }
}

/** [href] when it's an http(s) URL, else `null`. */
export function webUrl(href: string | undefined): URL | null {
  const url = parseUrl(href);
  return url !== null && isWebUrl(url) ? url : null;
}

/**
 * The frame of a content script's message, or `null` when Keyguard doesn't
 * serve it:
 * - not our extension's content script (no tab, another extension);
 * - a document that isn't active (prerendered, in the back/forward cache);
 * - an opaque origin: a sandboxed frame or CSP `sandbox`, whose URL isn't who
 *   it is (CVE-2025-65203);
 * - anything but http(s). An `about:blank` or `about:srcdoc` frame that
 *   inherits an http(s) origin is served as that origin, when the browser
 *   reports it.
 *
 * A cross-origin iframe is its own frame with its own URL, so it's matched by
 * that URL and never by the top page's. [doc] is the token the message
 * carried.
 */
export function frameOf(sender: MessageSenderLike | undefined, extensionId: string, doc?: string): FrameRef | null {
  if (sender === undefined || sender.id !== extensionId) return null;
  const tabId = sender.tab?.id;
  const frameId = sender.frameId;
  if (tabId === undefined || tabId < 0 || frameId === undefined || frameId < 0) return null;
  if (sender.documentLifecycle !== undefined && sender.documentLifecycle !== "active") return null;
  if (sender.origin === "null") return null;
  const url = parseUrl(sender.url);
  if (url === null) return null;
  const documentId = sender.documentId;
  const top = frameId === 0 ? undefined : (webUrl(sender.tab?.url)?.origin ?? null);
  const frame = (href: string, origin: string): FrameRef => ({
    tabId,
    frameId,
    ...(documentId !== undefined ? { documentId } : {}),
    ...(doc !== undefined ? { doc } : {}),
    url: href,
    origin,
    ...(top !== undefined && top !== origin ? { topOrigin: top } : {}),
    ...(sender.tab?.incognito === true ? { privateWindow: true } : {}),
  });
  if (isWebUrl(url)) {
    // The browser's origin must agree with the URL's.
    if (sender.origin !== undefined && sender.origin !== url.origin) return null;
    return frame(url.href, url.origin);
  }
  if (url.protocol === "about:" && sender.origin !== undefined) {
    const origin = webUrl(sender.origin);
    if (origin === null || origin.origin !== sender.origin) return null;
    return frame(`${origin.origin}/`, origin.origin);
  }
  return null;
}

/** The options of `tabs.sendMessage` that reach exactly this frame and document. */
export function frameTarget(frame: Pick<FrameRef, "frameId" | "documentId">, documentIds: boolean): {
  frameId: number;
  documentId?: string;
} {
  // Firefox has no `documentId`, and rejects unknown options.
  return documentIds && frame.documentId !== undefined
    ? { frameId: frame.frameId, documentId: frame.documentId }
    : { frameId: frame.frameId };
}

/**
 * Whether a port or message comes from one of the extension's own pages at
 * [path]: our extension, an extension URL (the host may be Chromium's dynamic
 * ID of `use_dynamic_url`), and that page.
 */
export function isOwnPage(sender: MessageSenderLike | undefined, extensionId: string, path: string): boolean {
  if (sender?.id !== extensionId) return false;
  const url = parseUrl(sender.url);
  return url !== null && (url.protocol === "chrome-extension:" || url.protocol === "moz-extension:") && url.pathname === path;
}

/** The same frame of a tab, whatever document it shows. */
export function sameFrame(a: FrameRef, b: FrameRef): boolean {
  return a.tabId === b.tabId && a.frameId === b.frameId;
}

/** The same document of a frame: by its token, and by its `documentId` where the browser reports one. */
export function sameDocument(a: FrameRef, b: FrameRef): boolean {
  return sameFrame(a, b) && a.doc !== undefined && a.doc === b.doc && a.documentId === b.documentId;
}

/**
 * The same document of a frame as far as the browser tells: by its
 * `documentId` when both have one. For the webauthn bridge's ports, which
 * carry no token.
 */
export function sameDocumentLoose(a: FrameRef, b: FrameRef): boolean {
  return sameFrame(a, b) && (a.documentId === undefined || b.documentId === undefined || a.documentId === b.documentId);
}

/**
 * Ends work on a tab's page that has no port to the page, such as the popup's
 * fill and the fill shortcut, once its result can't be used: when the tab
 * closes, or its top frame navigates to another origin. Such a navigation
 * replaces every document of the tab, and fills check the origin.
 */
export class TabWatch {
  private readonly watches = new Map<number, Set<{ origin: string | null; abort: AbortController }>>();
  private count = 0;

  /**
   * [watching] hears `true` when the first watch starts and `false` when the
   * last one ends: navigations matter only in between.
   */
  constructor(private readonly watching: (active: boolean) => void = () => undefined) {}

  /** Whether an action runs. */
  get busy(): boolean {
    return this.count > 0;
  }

  /**
   * Runs [action] with a signal that aborts when tab [tabId] closes, or
   * leaves the origin of [url], the URL of its top frame if known.
   */
  async run<T>(tabId: number, url: string | undefined, action: (signal: AbortSignal) => Promise<T>): Promise<T> {
    const watch = { origin: url === undefined ? null : originOf(url), abort: new AbortController() };
    let watches = this.watches.get(tabId);
    if (watches === undefined) this.watches.set(tabId, (watches = new Set()));
    watches.add(watch);
    if (this.count++ === 0) this.watching(true);
    try {
      return await action(watch.abort.signal);
    } finally {
      watches.delete(watch);
      if (watches.size === 0 && this.watches.get(tabId) === watches) this.watches.delete(tabId);
      if (--this.count === 0) this.watching(false);
    }
  }

  /** The top frame of a tab has a new URL, from `tabs.onUpdated`. */
  navigated(tabId: number, url: string): void {
    const origin = originOf(url);
    for (const watch of this.watches.get(tabId) ?? []) {
      if (watch.origin !== null && watch.origin !== origin) watch.abort.abort();
    }
  }

  /** A tab closed. */
  removed(tabId: number): void {
    for (const watch of this.watches.get(tabId) ?? []) watch.abort.abort();
  }
}

/** The origin of [url]: scheme, host and port; "null" for none. */
export function originOf(url: string): string {
  return parseUrl(url)?.origin ?? "null";
}

/**
 * Whether two URLs are on the same site: http(s), the same scheme and,
 * loosely, the same registrable domain. Only a pre-filter of what the app
 * checks again, with the public suffix list, as the frames a popup's fill may
 * pick (./pages.ts): never a key of what the background remembers of a page.
 */
export function sameSite(a: string, b: string): boolean {
  const [x, y] = [webUrl(a), webUrl(b)];
  return x !== null && y !== null && x.protocol === y.protocol && looselySameSite(x.hostname, y.hostname);
}

/**
 * Whether two hosts belong to the same site, loosely: equal, or sharing their
 * last two labels, so `a.co.uk` and `b.co.uk` do too. Only for [sameSite].
 */
function looselySameSite(a: string, b: string): boolean {
  if (a === b) return true;
  const tail = (host: string) => (/^[\d.]+$|:/.test(host) ? host : host.split(".").slice(-2).join("."));
  return tail(a) === tail(b);
}
