// The popup's port to the background (src/lib/ui/popupClient.ts): calls and
// their answers, and a new port when Chromium stops the service worker.
import { describe, expect, it } from "vitest";
import type { ExtensionStatus, PopupMessage } from "../src/lib/background/api";
import { MAX_RECONNECTS, PopupClient, type PopupPort } from "../src/lib/ui/popupClient";

class FakePort implements PopupPort {
  readonly posted: PopupMessage[] = [];
  /** Whether posting throws, as on a port that closed without telling yet. */
  broken = false;
  private readonly messageListeners: ((message: unknown) => void)[] = [];
  private readonly disconnectListeners: (() => void)[] = [];
  readonly onMessage = {
    addListener: (listener: (message: unknown) => void) => void this.messageListeners.push(listener),
  };
  readonly onDisconnect = { addListener: (listener: () => void) => void this.disconnectListeners.push(listener) };

  postMessage(message: unknown): void {
    if (this.broken) throw new Error("Attempting to use a disconnected port object");
    this.posted.push(message as PopupMessage);
  }

  /** A message of the background. */
  emit(message: unknown): void {
    for (const listener of this.messageListeners) listener(message);
  }

  /** The background's side went away, as when its service worker stops. */
  close(): void {
    for (const listener of this.disconnectListeners) listener();
  }

  /** Answers the last call. */
  answer(value: unknown): void {
    this.emit({ kind: "result", id: this.posted.at(-1)!.id, ok: true, value });
  }
}

function setup() {
  const ports: FakePort[] = [];
  const statuses: ExtensionStatus["kind"][] = [];
  const client = new PopupClient(
    (status) => statuses.push(status.kind),
    () => {
      const port = new FakePort();
      ports.push(port);
      return port;
    },
  );
  return { client, ports, statuses };
}

describe("popup client", () => {
  it("shows the background's status and answers calls", async () => {
    const { client, ports, statuses } = setup();
    ports[0]!.emit({ kind: "status", status: { kind: "unlocked" } });
    expect(statuses).toEqual(["unlocked"]);
    const result = client.call("suggest", { tabId: 7 });
    ports[0]!.answer({ kind: "unsupported" });
    await expect(result).resolves.toEqual({ kind: "unsupported" });
    const failed = client.call("unlock", {});
    ports[0]!.emit({ kind: "result", id: ports[0]!.posted.at(-1)!.id, ok: false, error: "user_denied" });
    await expect(failed).rejects.toMatchObject({ code: "user_denied" });
  });

  it("connects again when the service worker stops, and fails the calls in flight", async () => {
    const { client, ports, statuses } = setup();
    const pending = client.call("suggest", { tabId: 7 });
    ports[0]!.close();
    await expect(pending).rejects.toMatchObject({ code: "disconnected" });
    // The new port wakes the worker, which sends the status again.
    expect(ports).toHaveLength(2);
    ports[1]!.emit({ kind: "status", status: { kind: "starting" } });
    ports[1]!.emit({ kind: "status", status: { kind: "unlocked" } });
    expect(statuses).toEqual(["starting", "unlocked"]);
    const next = client.call("suggest", { tabId: 7 });
    expect(ports[0]!.posted).toHaveLength(1);
    ports[1]!.answer({ kind: "unsupported" });
    await expect(next).resolves.toEqual({ kind: "unsupported" });
    // The old port says nothing any more.
    ports[0]!.emit({ kind: "status", status: { kind: "app_unavailable" } });
    expect(statuses).toEqual(["starting", "unlocked"]);
  });

  it("takes a port that can't post as closed", async () => {
    const { client, ports } = setup();
    const waiting = client.call("suggest", { tabId: 7 });
    ports[0]!.broken = true;
    await expect(client.call("unlock", {})).rejects.toMatchObject({ code: "disconnected" });
    await expect(waiting).rejects.toMatchObject({ code: "disconnected" });
    expect(ports).toHaveLength(2);
    const next = client.call("unlock", {});
    ports[1]!.answer(null);
    await expect(next).resolves.toBeNull();
  });

  it("stops connecting when the background keeps closing the port before it says anything", async () => {
    const { client, ports } = setup();
    for (let i = 0; i <= MAX_RECONNECTS; i++) ports.at(-1)!.close();
    expect(ports).toHaveLength(MAX_RECONNECTS + 1);
    await expect(client.call("unlock", {})).rejects.toMatchObject({ code: "disconnected" });
  });
});
