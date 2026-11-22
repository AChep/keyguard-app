// @vitest-environment happy-dom
// The embedded pages' side of their binding (src/lib/ui/embedded.ts): the token
// comes from the content script; the page posts nothing to the web page.
import { describe, expect, it, vi } from "vitest";
import { BIND_MESSAGE } from "../src/lib/page/messages";
import { connectEmbedded, type RuntimePort } from "../src/lib/ui/embedded";
import { messageDirection } from "../src/lib/ui/pageLocale";

const TOKEN = "a".repeat(32);

class FakePort implements RuntimePort {
  readonly posted: unknown[] = [];
  private readonly listeners: ((message: unknown) => void)[] = [];
  readonly onMessage = { addListener: (listener: (message: unknown) => void) => void this.listeners.push(listener) };
  readonly onDisconnect = { addListener: () => undefined };

  postMessage(message: unknown): void {
    this.posted.push(message);
  }

  /** A message of the background. */
  emit(message: unknown): void {
    for (const listener of this.listeners) listener(message);
  }
}

/** The page's window in a frame of [parent]. */
function frameWindow(parent: Window) {
  const target = new EventTarget();
  return Object.assign(target, { parent }) as unknown as Window;
}

describe("embedded pages", () => {
  it("post nothing to the web page they're in: its messages' origin would tell the extension's ID", () => {
    const parent = { postMessage: vi.fn() } as unknown as Window;
    const port = new FakePort();
    connectEmbedded("inline-menu", frameWindow(parent), () => port);
    expect(parent.postMessage).not.toHaveBeenCalled();
    expect(port.posted).toEqual([]);
  });

  it("bind with the token their parent posts, until bound", () => {
    const parent = { postMessage: vi.fn() } as unknown as Window;
    const other = {} as Window;
    const win = frameWindow(parent);
    const port = new FakePort();
    connectEmbedded("save-bar", win, () => port);
    const post = (data: unknown, source: Window) =>
      win.dispatchEvent(new MessageEvent("message", { data, source: source as never }));

    post({ type: BIND_MESSAGE, token: TOKEN }, other);
    post({ type: BIND_MESSAGE, token: "short" }, parent);
    post({ type: "keyguard:ready", token: TOKEN }, parent);
    expect(port.posted).toEqual([]);
    post({ type: BIND_MESSAGE, token: TOKEN }, parent);
    expect(port.posted).toEqual([{ kind: "bind", token: TOKEN }]);
    port.emit({ kind: "bound" });
    post({ type: BIND_MESSAGE, token: "b".repeat(32) }, parent);
    expect(port.posted).toHaveLength(1);
  });
});

describe("page direction", () => {
  it("is right to left where the browser's language reads so", () => {
    expect(messageDirection((name) => (name === "@@bidi_dir" ? "rtl" : ""))).toBe("rtl");
    expect(messageDirection((name) => (name === "@@bidi_dir" ? "ltr" : ""))).toBe("ltr");
    expect(messageDirection(() => "")).toBe("ltr");
  });
});
