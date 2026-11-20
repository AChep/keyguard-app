// The side of an embedded page (inline menu, save bar) that talks to the
// background. Once the page loaded, the content script that embedded it posts
// a one-time token to it; the page sends that over its own port, which the
// background then binds to the content script's frame. The page posts nothing
// to its parent: the origin of its messages would give the web page Firefox's
// per-install extension UUID.
import { mount, type Component } from "svelte";
import { browser } from "wxt/browser";
import type { ObservableStorageArea } from "../options/store";
import { BIND_MESSAGE, isToken } from "../page/messages";
import { ClickGate, observeVisibility } from "./clickGate";
import { localizePage } from "./pageLocale";
import { followTheme } from "./storedTheme";
import "./embedded.css";

export interface EmbeddedPort<Request, Event> {
  send(request: Request): void;
  /** Called for each event of the background. */
  onEvent(listener: (event: Event) => void): void;
}

/** The parts of `runtime.Port` an embedded page uses. */
export interface RuntimePort {
  postMessage(message: unknown): void;
  onMessage: { addListener(callback: (message: unknown) => void): void };
  onDisconnect: { addListener(callback: () => void): void };
}

/**
 * Connects to the background as [name] and binds with the tokens the parent
 * frame posts. The page can post tokens to this frame too; the background
 * accepts only the one it issued, and only once.
 */
export function connectEmbedded<Request, Event extends { kind: string }>(
  name: string,
  win: Window = window,
  connect: (name: string) => RuntimePort = (name) => browser.runtime.connect({ name }),
): EmbeddedPort<Request | { kind: "bind"; token: string }, Event> {
  const port = connect(name);
  const listeners: ((event: Event) => void)[] = [];
  let bound = false;

  const onToken = (message: MessageEvent) => {
    if (bound || message.source !== win.parent) return;
    const data = message.data as { type?: unknown; token?: unknown } | null;
    if (data?.type !== BIND_MESSAGE || !isToken(data.token)) return;
    port.postMessage({ kind: "bind", token: data.token });
  };
  win.addEventListener("message", onToken);

  port.onMessage.addListener((raw: unknown) => {
    const event = raw as Event;
    if (event.kind === "bound") {
      bound = true;
      win.removeEventListener("message", onToken);
    }
    for (const listener of listeners) listener(event);
  });
  port.onDisconnect.addListener(() => win.removeEventListener("message", onToken));

  return {
    send: (request) => {
      try {
        port.postMessage(request);
      } catch {
        // The background dropped the port.
      }
    },
    onEvent: (listener) => void listeners.push(listener),
  };
}

/**
 * Starts an embedded page: mounts [App] with a port connected as [name] and a
 * click gate. [hidden]: the content script shows the frame once the page has
 * its height, and the page's gate waits for the background's word of it.
 */
export function mountEmbedded<Request, Event extends { kind: string }>(
  App: Component<{ port: EmbeddedPort<Request, Event>; gate: ClickGate }>,
  name: string,
  { hidden = false }: { hidden?: boolean } = {},
): void {
  localizePage(document);
  // Firefox: the colors of the browser's theme, from the first paint. This
  // frame has no `theme` API; the background reads it for the page.
  if (import.meta.env.FIREFOX) followTheme(window, browser.storage.local as unknown as ObservableStorageArea);

  const port = connectEmbedded<Request, Event>(name);
  const gate = new ClickGate(undefined, { hidden });
  observeVisibility(gate, document);

  mount(App, { target: document.getElementById("app")!, props: { port, gate } });
}
