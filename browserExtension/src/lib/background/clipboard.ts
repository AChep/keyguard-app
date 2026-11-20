// Clipboard writes from the background. The popup can't copy a value that
// needed an approval: the app's prompt takes focus, the popup closes, and its
// write is lost. The background has no clipboard of its own on Chromium (a
// service worker), so it writes through an offscreen document; Firefox runs
// the background as an event page with a DOM, which writes directly.
import { isObject } from "../util/json";
import { SerialQueue } from "../util/serial";

export type ClipboardRoute = "offscreen" | "document";

/** Where the background writes the clipboard, or `null` when it can't. */
export function clipboardRoute(env: { firefox: boolean; offscreen: boolean; document: boolean }): ClipboardRoute | null {
  if (env.firefox) return env.document ? "document" : null;
  if (env.offscreen) return "offscreen";
  return env.document ? "document" : null;
}

export const OFFSCREEN_PAGE = "/offscreen.html";

/** The message the background sends to the offscreen document. */
export interface OffscreenCopy {
  target: "offscreen";
  kind: "copy";
  text: string;
}

export function isOffscreenCopy(message: unknown): message is OffscreenCopy {
  if (!isObject(message)) return false;
  const { target, kind, text } = message;
  return target === "offscreen" && kind === "copy" && typeof text === "string";
}

/** The parts of `chrome.offscreen` the writer uses. */
export interface OffscreenApi {
  hasDocument?(): Promise<boolean>;
  createDocument(parameters: { url: string; reasons: string[]; justification: string }): Promise<void>;
  closeDocument(): Promise<void>;
}

/** What the writer needs for the [clipboardRoute] taken. */
export type ClipboardDeps =
  | {
      route: "offscreen";
      offscreen: OffscreenApi;
      /** The URL of OFFSCREEN_PAGE. */
      offscreenUrl: string;
      /** `runtime.sendMessage`, which reaches the offscreen document. */
      sendMessage(message: OffscreenCopy): Promise<unknown>;
    }
  /** `navigator.clipboard.writeText` of a page with a DOM. */
  | { route: "document"; writeText(text: string): Promise<void> }
  | { route: null };

export class ClipboardUnavailable extends Error {
  constructor() {
    super("The clipboard is unavailable");
    this.name = "ClipboardUnavailable";
  }
}

export class ClipboardWriter {
  // One write at a time: they share the offscreen document.
  private readonly queue = new SerialQueue();

  constructor(private readonly deps: ClipboardDeps) {}

  write(text: string): Promise<void> {
    return this.queue.run(() => this.writeNow(text));
  }

  private async writeNow(text: string): Promise<void> {
    const deps = this.deps;
    if (deps.route === "document") {
      await deps.writeText(text);
      return;
    }
    if (deps.route === null) throw new ClipboardUnavailable();
    const { offscreen, offscreenUrl, sendMessage } = deps;
    if (!(await offscreen.hasDocument?.())) {
      try {
        await offscreen.createDocument({
          url: offscreenUrl,
          reasons: ["CLIPBOARD"],
          justification: "Copies a value the user asked for after Keyguard approved it.",
        });
      } catch (error) {
        // Another write created it first.
        if (!/single offscreen/i.test(String(error))) throw error;
      }
    }
    try {
      const reply = (await sendMessage({ target: "offscreen", kind: "copy", text })) as { ok?: boolean } | undefined;
      if (reply?.ok !== true) throw new ClipboardUnavailable();
    } finally {
      // The document holds the value in its DOM until it closes.
      await offscreen.closeDocument().catch(() => undefined);
    }
  }
}
