// The background controller end to end: native port, Noise session through the
// Wasm, pairing and storage, against a fake host and app. The popup's calls
// about a tab's page go through a PageController on it.
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import type { ExtensionStatus } from "../src/lib/background/api";
import { BackgroundController } from "../src/lib/background/controller";
import { TabWatch, type MessageSenderLike } from "../src/lib/background/frames";
import { PageController } from "../src/lib/background/pages";
import { siteIconUrl } from "../src/lib/background/siteIcon";
import { UnsupportedStoreVersion, type StorageArea } from "../src/lib/pairing/store";
import { NativeCryptoError } from "../src/lib/crypto/nativeCrypto";
import type { NativeCryptoNoise, NoiseKeyPair } from "../src/lib/crypto/noise";
import { loadNoise } from "../src/lib/crypto/wasm";
import { NATIVE_HOST_NAME } from "../src/lib/native/connection";
import { OptionsStore } from "../src/lib/options/store";
import { PROTOCOL_VERSIONS, type FramePage } from "../src/lib/protocol/messages";
import { fromBase64, toHex } from "../src/lib/util/bytes";
import { FakeHost, FakeNativePort, type FakeApp, type Handler, type Reply } from "./support/fakeApp";
import { frozen, responseFields, supportedVersions } from "./support/fixtures";

const EXTENSION_ID = "abcdefghijklmnopabcdefghijklmnop";
const ORIGIN = `chrome-extension://${EXTENSION_ID}/`;
/** The token of the content scripts' documents. */
const DOC = "d".repeat(32);
/** The origin of the tabs' pages, which the popup's fill and copies name. */
const LOGIN_ORIGIN = "https://login.example.com";
let noise: NativeCryptoNoise;

beforeAll(async () => {
  noise = await loadNoise();
});

beforeEach(() => {
  fakeBrowser.reset();
});

/** The real `setTimeout`: [until] polls on it, also in tests where the background's timers are fake. */
const realSetTimeout = globalThis.setTimeout;

async function until(check: () => boolean | Promise<boolean>, what = "condition") {
  for (let i = 0; i < 500; i++) {
    if (await check()) return;
    await new Promise((resolve) => realSetTimeout(resolve, 2));
  }
  throw new Error(`Timed out waiting for ${what}`);
}

async function stored(): Promise<{ publicKey: string; pairing?: { appKey: string } } | undefined> {
  return (await fakeBrowser.storage.local.get("keyguard")).keyguard as never;
}

/** The tab in a private window. */
const PRIVATE_TAB = 3;

/** The content script of the frame of a tab on [tabUrl] that shows [page]: the top frame, or a frame of the top origin's page. */
function frameSender(tabId: number, tabUrl: string, page: FramePage): MessageSenderLike {
  const frameId = page.top_origin === undefined ? 0 : 4;
  const tab = { id: tabId, url: tabUrl, ...(tabId === PRIVATE_TAB ? { incognito: true } : {}) };
  return {
    id: EXTENSION_ID,
    tab,
    frameId,
    documentId: `doc-${frameId}`,
    url: page.url,
    origin: new URL(page.url).origin,
  };
}

function setup(
  options: {
    handler?: Handler;
    connectorMissing?: boolean;
    capabilities?: string[];
    noise?: NativeCryptoNoise;
    backoffMs?: number;
  } = {},
) {
  const ports: FakeNativePort[] = [];
  const hosts: FakeHost[] = [];
  const statuses: ExtensionStatus[] = [];
  /** The fills that reached a content script: the tab, the origin of its frame and the values. */
  const fills: unknown[][] = [];
  const copies: string[] = [];
  const app = { keys: noise.generateKeyPair() as NoiseKeyPair, pairedKeys: new Set<string>() };
  const tabs: Record<number, string> = {
    1: "https://login.example.com/sign-in?next=1",
    2: "chrome://settings/",
    [PRIVATE_TAB]: "https://login.example.com/sign-in?private=1",
  };
  const tabOf = async (tabId: number) => ({ url: tabs[tabId], incognito: tabId === PRIVATE_TAB });
  /** The page of the frame with the form that the fill picks: the tab's by default, `null` for none. */
  const formFrames: Record<number, FramePage | null> = {};
  const tabWatch = new TabWatch();
  /** Holds or fails the writes of the static key and the pairing, as a slow or broken IndexedDB. */
  const writes = { held: null as Promise<void> | null, error: null as Error | null, waiting: 0 };
  const storage: StorageArea = {
    get: (key) => fakeBrowser.storage.local.get(key),
    set: async (items) => {
      if (writes.held !== null) {
        writes.waiting++;
        await writes.held;
        writes.waiting--;
      }
      if (writes.error !== null) throw writes.error;
      await fakeBrowser.storage.local.set(items);
    },
  };
  /** Holds the writes until the returned function runs. */
  const holdWrites = () => {
    let release: () => void = () => undefined;
    writes.held = new Promise((resolve) => (release = resolve));
    return () => {
      writes.held = null;
      release();
    };
  };
  const controller = new BackgroundController({
    connectNative: (application) => {
      expect(application).toBe(NATIVE_HOST_NAME);
      const port = new FakeNativePort();
      ports.push(port);
      hosts.push(
        new FakeHost(port, () => ({
          noise,
          origin: ORIGIN,
          keys: app.keys,
          pairedKeys: app.pairedKeys,
          ...(options.handler ? { handler: options.handler } : {}),
          ...(options.capabilities ? { capabilities: options.capabilities } : {}),
        })),
      );
      if (options.connectorMissing) queueMicrotask(() => port.close("Specified native messaging host not found."));
      return port;
    },
    lastError: () => undefined,
    loadNoise: () => Promise.resolve(options.noise ?? noise),
    storage,
    origin: ORIGIN,
    browserName: "Chrome",
    tab: tabOf,
    copyText: async (text) => {
      copies.push(text);
    },
    mayCopyPassword: (tabId, handle) => pages.mayCopyPassword(tabId, handle),
    onStatus: (status) => statuses.push(status),
    initialBackoffMs: options.backoffMs ?? 1,
  });
  // The tabs' content scripts: the frame with the form answers a `form_probe`, and takes the fill.
  const pages: PageController = new PageController({
    extensionId: EXTENSION_ID,
    app: controller,
    options: new OptionsStore(fakeBrowser.storage.local),
    passkeys: { conditionalFor: () => null, pick: async () => undefined },
    sendToFrame: async (tabId, message) => {
      if (message.kind === "fill")
        fills.push([tabId, message.origin, { username: message.username, password: message.password }]);
      return { ok: true };
    },
    sendToTab: async (tabId, message) => {
      const page = formFrames[tabId] === undefined ? { url: tabs[tabId]! } : formFrames[tabId];
      if (message.kind === "form_probe" && page !== null) {
        const report = { kind: "form_report", probe: message.probe, focused_at: null, writable: true, doc: DOC };
        // A message of its own, before any timer: before the probe's time is up, however slow the machine.
        queueMicrotask(() => void pages.onContentMessage(report, frameSender(tabId, tabs[tabId]!, page)));
      }
      return null;
    },
    injectContentScript: async () => undefined,
    tab: tabOf,
    // Chromium's, as entrypoints/background.ts composes it.
    siteIcon: async (tabId, url) =>
      siteIconUrl({ url, tabUrl: tabs[tabId] }, { source: "chromium", getURL: (path) => new URL(path, ORIGIN).href }),
    tabs: tabWatch,
    openPopup: async () => undefined,
    documentIds: true,
    probeTimeoutMs: 20,
  });
  controller.start();
  const host = () => hosts.at(-1)!;
  const status = () => controller.snapshot;
  const waitFor = (kind: ExtensionStatus["kind"]) => until(() => status().kind === kind, kind);
  return {
    controller,
    pages,
    ports,
    hosts,
    host,
    statuses,
    status,
    waitFor,
    app,
    fills,
    formFrames,
    copies,
    tabWatch,
    writes,
    holdWrites,
  };
}

/** Pairs with the app, which needs its vault unlocked for that; then locks it. */
async function pair(context: ReturnType<typeof setup>) {
  const app = context.host().ready();
  await context.waitFor("not_paired");
  app.vault = "unlocked";
  await context.controller.calls.pair({});
  app.vault = "locked";
  app.event({ event: "vault", state: "locked" });
  await context.waitFor("locked");
}

/** Pairs, then reconnects to an app that answers `hello` with [reply]; `identity` is the stored key and pin. */
async function reconnectWithHello(reply: (app: FakeApp) => Reply, options: { backoffMs?: number } = {}) {
  let answering = false;
  const context = setup({
    ...options,
    handler: (request, app) => (answering && request.type === "hello" ? reply(app) : undefined),
  });
  await pair(context);
  const identity = await stored();
  answering = true;
  context.host().status("app_unavailable");
  context.host().ready();
  return { context, identity, stopAnswering: () => void (answering = false) };
}

/** A `hello` answer with [protocol]. */
const helloWith = (protocol: number) => (app: FakeApp) => ({
  protocol,
  capabilities: [],
  paired: app.paired,
  vault: app.vault,
});

/** Starts a session on the last port, which the app refuses: the extension ends it and reconnects with backoff. */
async function refuseSession(context: ReturnType<typeof setup>) {
  const port = context.ports.at(-1)!;
  context.host().ready();
  await until(() => port.disconnected, "the refused session's end");
  expect(context.status().kind).toBe("protocol_error");
}

/** Lets [ms] pass on the fake clock: a new port opens in its last millisecond, not before. */
async function reconnectsAfter(context: ReturnType<typeof setup>, ms: number) {
  const ports = context.ports.length;
  await vi.advanceTimersByTimeAsync(ms - 1);
  expect(context.ports, `ports after ${ms - 1} ms`).toHaveLength(ports);
  await vi.advanceTimersByTimeAsync(1);
  expect(context.ports, `ports after ${ms} ms`).toHaveLength(ports + 1);
}

describe("BackgroundController", () => {
  it("pairs: shows the app's code, then pins the app's key", async () => {
    const context = setup();
    const app = context.host().ready();
    await context.waitFor("not_paired");
    // Only the highest protocol version and the capabilities the extension implements.
    expect(app.requests[0]).toEqual({
      id: 1,
      type: "hello",
      protocol: PROTOCOL_VERSIONS.max,
      capabilities: ["passkeys", "save"],
    });

    app.vault = "unlocked";
    await context.controller.calls.pair({});
    const confirm = context.statuses.find((it) => it.kind === "not_paired" && it.pairing.phase === "confirm");
    expect(confirm).toEqual({ kind: "not_paired", pairing: { phase: "confirm", code: app.pairingCode } });
    expect(app.requests[1]).toMatchObject({ name: "Chrome" });
    expect(context.status()).toEqual({ kind: "unlocked" });

    const saved = await stored();
    expect(saved!.pairing).toEqual({ appKey: expect.any(String) });
    expect(fromBase64(saved!.pairing!.appKey)).toEqual(app.keys.publicKey);
    expect(fromBase64(saved!.publicKey)).toEqual(app.extensionKey);
  });

  it("finishes pairing with the vault of pair_reveal, before the pin is stored", async () => {
    const context = setup();
    const app = context.host().ready();
    await context.waitFor("not_paired");
    app.vault = "unlocked";
    const release = context.holdWrites();
    const pairing = context.controller.calls.pair({});
    await context.waitFor("unlocked");
    // No request after `pair_reveal`: its answer tells the vault.
    expect(app.requests.map((it) => it.type)).toEqual(["hello", "pair_commit", "pair_reveal"]);
    expect((await stored())!.pairing).toBeUndefined();
    // Events apply on top while the pin is written.
    app.event({ event: "vault", state: "locked" });
    await context.waitFor("locked");
    release();
    await expect(pairing).resolves.toBeNull();
    expect(fromBase64((await stored())!.pairing!.appKey)).toEqual(app.keys.publicKey);
    expect(context.status()).toEqual({ kind: "locked", unlocking: false });
    expect(app.requests).toHaveLength(3);
  });

  it("reconnects as paired and follows the vault", async () => {
    const context = setup();
    await pair(context);
    context.host().status("app_unavailable");
    expect(context.status().kind).toBe("app_unavailable");

    const app = context.host().ready();
    await context.waitFor("locked");
    expect(app.paired).toBe(true);
    app.event({ event: "vault", state: "unlocked" });
    await context.waitFor("unlocked");
    app.event({ event: "vault", state: "locked" });
    await context.waitFor("locked");
    await context.controller.calls.unlock({});
    expect(context.status().kind).toBe("unlocked");
    expect(context.statuses).toContainEqual({ kind: "locked", unlocking: true });
  });

  it("stops at a pinned-key mismatch and pairs again only on request", async () => {
    const context = setup();
    await pair(context);
    const before = await stored();
    context.app.keys = noise.generateKeyPair();
    context.host().ready();
    await context.waitFor("pinned_key_mismatch");
    // Message 3 was never sent: suite ID and message 1 only.
    expect(context.host().sent).toHaveLength(2);
    expect(context.ports[0]!.disconnected).toBe(true);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(context.ports).toHaveLength(1);
    // Only the user restarts it: focus doesn't.
    context.controller.focused();
    expect(context.ports).toHaveLength(1);

    await context.controller.calls.repair({});
    expect(context.ports).toHaveLength(2);
    const after = await stored();
    expect(after!.pairing).toBeUndefined();
    expect(after!.publicKey).not.toBe(before!.publicKey);
    context.host().ready();
    await context.waitFor("not_paired");
  });

  it("forgets a pairing the app removed", async () => {
    const context = setup();
    await pair(context);
    context.app.pairedKeys.clear();
    context.host().ready();
    await context.waitFor("not_paired");
    expect((await stored())!.pairing).toBeUndefined();
  });

  it("forgets the pairing when the app revokes it", async () => {
    const context = setup();
    await pair(context);
    context.host().app!.event({ event: "revoked" });
    await context.waitFor("not_paired");
    expect((await stored())!.pairing).toBeUndefined();
  });

  it("forgets a pairing the unlocked vault doesn't confirm, and pairs again in the session", async () => {
    const context = setup();
    await pair(context);
    const app = context.host().app!;
    const before = await stored();
    // A vault reset: `hello` said paired, but the unlocked vault doesn't confirm it.
    app.paired = false;
    app.vault = "unlocked";
    app.event({ event: "vault", state: "unlocked" });
    await context.waitFor("unlocked");
    await expect(context.pages.calls.suggest({ tabId: 1 })).rejects.toMatchObject({ code: "unpaired" });
    expect(context.status()).toEqual({ kind: "not_paired", pairing: { phase: "idle" } });
    const after = await stored();
    expect(after!.pairing).toBeUndefined();
    // Not a key mismatch: the key stays.
    expect(after!.publicKey).toBe(before!.publicKey);
    await expect(context.controller.calls.generate({})).rejects.toMatchObject({ code: "disconnected" });

    await context.controller.calls.pair({});
    expect(context.status()).toEqual({ kind: "unlocked" });
    expect(app.requests.slice(-3).map((it) => it.type)).toEqual(["suggest", "pair_commit", "pair_reveal"]);
    expect(context.ports).toHaveLength(1);
    expect(fromBase64((await stored())!.pairing!.appKey)).toEqual(app.keys.publicKey);
  });

  it("starts over with a new key when the app knows a key it never confirmed", async () => {
    const context = setup();
    context.host().ready();
    await context.waitFor("not_paired");
    const key = (await stored())!.publicKey;
    context.app.pairedKeys.add(toHex(fromBase64(key)!));
    context.host().status("app_unavailable");
    context.host().ready();
    await until(() => context.ports.length === 2, "a new port");
    expect((await stored())!.publicKey).not.toBe(key);
    context.host().ready();
    await context.waitFor("not_paired");
  });

  it("starts over with a new key when it can't pin a pairing the app confirmed", async () => {
    const context = setup();
    context.host().ready();
    await context.waitFor("not_paired");
    // Earlier in this worker, the app knew the key without a pin, and the extension replaced it.
    context.app.pairedKeys.add(toHex(fromBase64((await stored())!.publicKey)!));
    context.host().status("app_unavailable");
    context.host().ready();
    await until(() => context.ports.length === 2, "a new port");
    let app = context.host().ready();
    await context.waitFor("not_paired");

    app.vault = "unlocked";
    context.writes.error = new Error("QuotaExceededError");
    await expect(context.controller.calls.pair({})).rejects.toThrow("QuotaExceededError");
    // No second `pair_commit`, which the now paired app refuses: the session ends.
    expect(app.requests.map((it) => it.type)).toEqual(["hello", "pair_commit", "pair_reveal"]);
    expect(context.status()).toEqual({ kind: "protocol_error" });
    await until(() => context.ports.length === 3, "a new port");

    context.writes.error = null;
    const key = (await stored())!.publicKey;
    context.host().ready();
    await until(() => context.ports.length === 4, "a new port after the new key");
    expect((await stored())!.publicKey).not.toBe(key);
    app = context.host().ready();
    await context.waitFor("not_paired");
    app.vault = "unlocked";
    await context.controller.calls.pair({});
    expect(context.status()).toEqual({ kind: "unlocked" });
  });

  it.each(supportedVersions)("resumes a paired session with the frozen v%i app hello", async (version) => {
    let useFrozen = false;
    const { response } = frozen(version).exchanges.hello!;
    const context = setup({ handler: (request) => (useFrozen && request.type === "hello" ? response : undefined) });
    await pair(context);
    useFrozen = true;
    context.host().status("app_unavailable");
    const app = context.host().ready();
    await context.waitFor("locked");
    expect(app.requests.map((request) => request.type)).toEqual(["hello"]);
    expect(app.requests[0]).toMatchObject({ protocol: PROTOCOL_VERSIONS.max });
    expect(context.controller.supports("passkeys")).toBe(true);
  });

  it("ends the session and waits for Retry when the app answers a version above the extension's", async () => {
    const { context, identity, stopAnswering } = await reconnectWithHello(helloWith(PROTOCOL_VERSIONS.max + 1));
    await context.waitFor("protocol_error");
    expect(context.ports[0]!.disconnected).toBe(true);
    expect(await stored()).toEqual(identity);
    // The app broke the protocol: reconnecting would get the same answer, so not even on focus.
    await new Promise((resolve) => setTimeout(resolve, 20));
    context.controller.focused();
    expect(context.ports).toHaveLength(1);

    stopAnswering();
    await context.controller.calls.retry({});
    expect(context.ports).toHaveLength(2);
    context.host().ready();
    await context.waitFor("locked");
    expect(await stored()).toEqual(identity);
  });

  it("asks to update the app when it answers a version below the extension's, and reconnects on focus", async () => {
    // The next retry is a minute away: the new port comes from the focus.
    const { context, identity, stopAnswering } = await reconnectWithHello(helloWith(PROTOCOL_VERSIONS.min - 1), {
      backoffMs: 60_000,
    });
    await context.waitFor("update_required");
    expect(context.ports[0]!.disconnected).toBe(true);
    expect(await stored()).toEqual(identity);

    // As the host's `update_required`: the user updates the app and comes back.
    stopAnswering();
    context.controller.focused();
    expect(context.ports).toHaveLength(2);
    expect(context.status().kind).toBe("update_required");
    context.host().ready();
    await context.waitFor("locked");
    expect(await stored()).toEqual(identity);
  });

  it("asks to update the extension when the app supports none of its versions, and keeps the pin", async () => {
    const { context, identity, stopAnswering } = await reconnectWithHello(() => ({ error: "not_supported" }));
    await context.waitFor("extension_update_required");
    expect(context.ports[0]!.disconnected).toBe(true);
    expect(await stored()).toEqual(identity);
    // It waits for the update, which reloads the extension: no reconnect, not even on focus.
    await new Promise((resolve) => setTimeout(resolve, 20));
    context.controller.focused();
    expect(context.ports).toHaveLength(1);

    // Started again, it resumes the pairing.
    stopAnswering();
    await context.controller.calls.retry({});
    context.host().ready();
    await context.waitFor("locked");
    expect(await stored()).toEqual(identity);
  });

  it.each(["hello", "unlock"])("keeps the pin and reconnects after an app update required by %s", async (type) => {
    let needsUpdate = false;
    const context = setup({
      handler: (request) => (needsUpdate && request.type === type ? { error: "update_required" } : undefined),
    });
    await pair(context);
    const identity = await stored();
    needsUpdate = true;
    if (type === "hello") {
      context.host().status("app_unavailable");
      context.host().ready();
    } else {
      await expect(context.controller.unlock()).rejects.toMatchObject({ code: "update_required" });
    }
    await context.waitFor("app_update_required");
    expect(await stored()).toEqual(identity);

    needsUpdate = false;
    await context.controller.calls.retry({});
    const app = context.host().ready();
    await context.waitFor("locked");
    expect(app.requests.map((request) => request.type)).toEqual(["hello"]);
    expect(await stored()).toEqual(identity);
  });

  it("keeps the status of a session that ends while it forgets a pairing the app removed", async () => {
    const context = setup();
    await pair(context);
    context.app.pairedKeys.clear();
    const release = context.holdWrites();
    context.host().status("app_unavailable");
    context.host().ready();
    await context.waitFor("not_paired");
    context.host().status("app_unavailable");
    release();
    await until(async () => (await stored())!.pairing === undefined, "the pairing forgotten");
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(context.status()).toEqual({ kind: "app_unavailable" });
  });

  it("keeps a later status when the app revokes the pairing", async () => {
    const context = setup();
    await pair(context);
    const app = context.host().app!;
    const release = context.holdWrites();
    app.event({ event: "revoked" });
    await context.waitFor("not_paired");
    app.event({ event: "disabled" });
    await context.waitFor("integration_disabled");
    release();
    await until(async () => (await stored())!.pairing === undefined, "the pairing forgotten");
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(context.status()).toEqual({ kind: "integration_disabled" });
  });

  it("leaves a newer session alone once it replaced a key the app knew without a pin", async () => {
    const context = setup();
    context.host().ready();
    await context.waitFor("not_paired");
    const key = (await stored())!.publicKey;
    context.app.pairedKeys.add(toHex(fromBase64(key)!));
    const release = context.holdWrites();
    context.host().status("app_unavailable");
    context.host().ready();
    await until(() => context.writes.waiting === 1, "the new key's write");
    // The app restarts while the new key is stored.
    context.host().status("app_unavailable");
    const fresh = context.host().ready();
    release();
    await context.waitFor("not_paired");
    expect(context.ports).toHaveLength(1);
    const rotated = (await stored())!.publicKey;
    expect(rotated).not.toBe(key);
    expect(fresh.extensionKey).toEqual(fromBase64(rotated));
  });

  it("refuses a key that a newer version stored, and keeps it", async () => {
    const newer = { version: 2, identity: "opaque" };
    await fakeBrowser.storage.local.set({ keyguard: newer });
    const context = setup();
    context.host().ready();
    await context.waitFor("protocol_error");
    // No handshake: the suite ID would come first.
    expect(context.host().sent).toEqual([]);
    await expect(context.controller.ensureStaticKey()).rejects.toBeInstanceOf(UnsupportedStoreVersion);
    expect(await stored()).toEqual(newer);
  });

  it("shows the host's statuses", async () => {
    const context = setup();
    for (const status of [
      "integration_disabled",
      "update_required",
      "unverified_host",
      "too_many_sessions",
      "app_unavailable",
    ] as const) {
      context.host().status(status);
      expect(context.status().kind).toBe(status);
    }
    context.host().status("something_new");
    expect(context.status().kind).toBe("app_unavailable");
  });

  it.each(["update_required", "unverified_host"] as const)(
    "keeps showing %s after the host exits and reconnects",
    async (status) => {
      const context = setup();
      context.host().status(status);
      context.ports[0]!.close();
      expect(context.status().kind).toBe(status);
      await until(() => context.ports.length === 2, "a new port");
      expect(context.status().kind).toBe(status);
      context.host().ready();
      await context.waitFor("not_paired");
    },
  );

  it("reports a missing connector and keeps retrying", async () => {
    const context = setup({ connectorMissing: true });
    await context.waitFor("connector_missing");
    await until(() => context.ports.length >= 3, "retries");
  });

  it("reconnects at once when a browser window gets focus", async () => {
    // The user installs the connector in the app; the next retry is a minute away.
    const options = { connectorMissing: true, backoffMs: 60_000 };
    const context = setup(options);
    await context.waitFor("connector_missing");
    options.connectorMissing = false;
    context.controller.focused();
    expect(context.ports).toHaveLength(2);
    context.host().ready();
    await context.waitFor("not_paired");
    // An open port stays as it is.
    context.controller.focused();
    expect(context.ports).toHaveLength(2);
  });

  it("keeps an open port while the app is unavailable, whatever the focus", async () => {
    const context = setup({ backoffMs: 60_000 });
    context.host().status("app_unavailable");
    context.controller.focused();
    expect(context.ports).toHaveLength(1);
    expect(context.ports[0]!.disconnected).toBe(false);
  });

  it("reconnects after the app breaks the protocol", async () => {
    const context = setup();
    const app = context.host().ready();
    await context.waitFor("not_paired");
    app.write({ id: 42, ok: true });
    await context.waitFor("protocol_error");
    expect(context.ports[0]!.disconnected).toBe(true);
    await until(() => context.ports.length === 2, "a new port");
  });

  it("backs off while the app refuses every session, and starts over once one starts", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      // As when the app can't read its pairings; the connector says `ready` on each new port.
      let refusing = true;
      const context = setup({
        handler: (request) => (refusing && request.type === "hello" ? { error: "not_allowed" } : undefined),
        backoffMs: 5,
      });
      // The wait doubles from 5 ms.
      for (let i = 0; i < 4; i++) {
        await refuseSession(context);
        await reconnectsAfter(context, 5 * 2 ** i);
      }

      // Focus skips the wait, but counts the attempt.
      for (let i = 4; i < 12; i++) {
        await refuseSession(context);
        context.controller.focused();
      }
      expect(context.ports).toHaveLength(13);
      await refuseSession(context);
      await reconnectsAfter(context, 5 * 2 ** 12);

      refusing = false;
      context.host().ready();
      await context.waitFor("not_paired");
      // The session started: the host exits, and a new port opens after 5 ms again.
      context.ports.at(-1)!.close();
      await reconnectsAfter(context, 5);
    } finally {
      vi.useRealTimers();
    }
  });

  it("starts the backoff over when the user retries", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      const context = setup({
        handler: (request) => (request.type === "hello" ? { error: "not_allowed" } : undefined),
        backoffMs: 5,
      });
      for (let i = 0; i < 4; i++) {
        await refuseSession(context);
        context.controller.focused();
      }
      await context.controller.calls.retry({});
      expect(context.ports).toHaveLength(6);
      // If this session fails too, its reconnect waits 5 ms, not 5 * 2^4 ms.
      await refuseSession(context);
      await reconnectsAfter(context, 5);
    } finally {
      vi.useRealTimers();
    }
  });

  it("reconnects when the crypto fails to start the handshake", async () => {
    const broken = Object.assign(Object.create(noise) as NativeCryptoNoise, {
      xx: () => {
        throw new NativeCryptoError("noise_handshake_open", "RESOURCE_LIMIT");
      },
    });
    const context = setup({ noise: broken });
    context.host().ready();
    await context.waitFor("protocol_error");
    expect(context.ports[0]!.disconnected).toBe(true);
    await until(() => context.ports.length === 2, "a new port");
  });

  it("drops a port that sends a malformed frame", async () => {
    const context = setup();
    context.host().ready();
    await context.waitFor("not_paired");
    context.ports[0]!.emit({ type: "noise", data: "not base64!" });
    expect(context.ports[0]!.disconnected).toBe(true);
    await until(() => context.ports.length === 2, "a new port");
  });

  it("suggests and fills on the tab's page", async () => {
    const context = setup({
      handler: (request) => {
        switch (request.type) {
          case "suggest":
            return {
              blocked: false,
              save_blocked: false,
              truncated: false,
              items: [
                {
                  handle: "h1",
                  name: "Example",
                  username: "ann",
                  account: "Personal",
                  has_password: true,
                  has_totp: false,
                  reprompt: false,
                },
              ],
            };
          case "fill":
            return request.handle === "h1" ? { username: "ann", password: "secret" } : { error: "invalid_request" };
          case "totp":
            // The item has no TOTP.
            return { error: "invalid_request" };
          default:
            return undefined;
        }
      },
    });
    await pair(context);
    const suggestions = await context.pages.calls.suggest({ tabId: 1 });
    expect(suggestions).toMatchObject({ kind: "items", host: "login.example.com", truncated: false });
    // The tab's favicon, from Chromium's cache at the extension's origin.
    expect(suggestions).toMatchObject({
      icon: `${ORIGIN}_favicon/?pageUrl=https%3A%2F%2Flogin.example.com%2Fsign-in%3Fnext%3D1&size=32`,
    });
    const app = context.host().app!;
    expect(app.requests.at(-1)).toMatchObject({ type: "suggest", url: "https://login.example.com/sign-in?next=1" });

    await expect(context.pages.calls.fill({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN })).resolves.toBeNull();
    // The content script of the top frame fills, with the tab's URL from the browser.
    expect(app.requests.filter((it) => it.type === "fill")).toMatchObject([
      { handle: "h1", url: "https://login.example.com/sign-in?next=1" },
    ]);
    expect(context.fills).toEqual([[1, "https://login.example.com", { username: "ann", password: "secret" }]]);
    // The background copies: the popup may close while the app prompts.
    await expect(
      context.controller.calls.copy_password({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN }),
    ).resolves.toBeNull();
    expect(context.copies).toEqual(["secret"]);
    await expect(
      context.controller.calls.copy_totp({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN }),
    ).rejects.toMatchObject({
      code: "invalid_request",
    });
    expect(context.copies).toEqual(["secret"]);
    await expect(context.pages.calls.suggest({ tabId: 2 })).resolves.toEqual({ kind: "unsupported" });
    await expect(context.pages.calls.fill({ tabId: 2, handle: "h1", origin: LOGIN_ORIGIN })).rejects.toMatchObject({
      code: "unsupported_page",
    });
    // The tab went on to another origin than the popup's list is for.
    const released = app.requests.length;
    for (const call of ["copy_password", "copy_totp"] as const) {
      await expect(
        context.controller.calls[call]({ tabId: 1, handle: "h1", origin: "https://evil.example" }),
        call,
      ).rejects.toMatchObject({
        code: "origin",
      });
    }
    expect(app.requests).toHaveLength(released);
    expect(context.copies).toEqual(["secret"]);
  });

  it("copies no password of an item whose collections hide it, nor of one the popup didn't list", async () => {
    const item = { name: "Example", account: "Work", has_password: true, has_totp: false, reprompt: false };
    const items = [
      { ...item, handle: "h1", password_hidden: true },
      { ...item, handle: "h2" },
    ];
    const context = setup({
      handler: (request) => {
        switch (request.type) {
          case "suggest":
            return { blocked: false, save_blocked: false, truncated: false, items };
          case "fill":
            return { username: "ann", password: "secret" };
          default:
            return undefined;
        }
      },
    });
    await pair(context);
    await context.pages.calls.suggest({ tabId: 1 });
    const app = context.host().app!;
    for (const handle of ["h1", "unlisted"]) {
      await expect(
        context.controller.calls.copy_password({ tabId: 1, handle, origin: LOGIN_ORIGIN }),
        handle,
      ).rejects.toMatchObject({
        code: "not_allowed",
      });
    }
    expect(app.requests.filter((it) => it.type === "fill")).toEqual([]);
    expect(context.copies).toEqual([]);
    // Its fill still releases the password.
    await expect(context.pages.calls.fill({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN })).resolves.toBeNull();
    expect(context.fills).toEqual([[1, "https://login.example.com", { username: "ann", password: "secret" }]]);
    await expect(
      context.controller.calls.copy_password({ tabId: 1, handle: "h2", origin: LOGIN_ORIGIN }),
    ).resolves.toBeNull();
    expect(context.copies).toEqual(["secret"]);
  });

  it("lists the login filled last on the tab first in the popup, for the next step of a sign-in", async () => {
    const items = ["h1", "h2"].map((handle) => ({
      handle,
      name: handle,
      account: "Personal",
      has_password: true,
      has_totp: false,
      reprompt: false,
    }));
    const context = setup({
      handler: (request) =>
        request.type === "suggest" ? { blocked: false, save_blocked: false, truncated: false, items } : undefined,
    });
    await pair(context);
    await expect(context.pages.calls.suggest({ tabId: 1 })).resolves.toMatchObject({
      items: [{ handle: "h1" }, { handle: "h2" }],
      last: null,
    });
    await context.pages.calls.fill({ tabId: 1, handle: "h2", origin: LOGIN_ORIGIN });
    await expect(context.pages.calls.suggest({ tabId: 1 })).resolves.toEqual({
      kind: "items",
      origin: LOGIN_ORIGIN,
      host: "login.example.com",
      truncated: false,
      items: [items[1], items[0]],
      last: "h2",
      icon: `${ORIGIN}_favicon/?pageUrl=https%3A%2F%2Flogin.example.com%2Fsign-in%3Fnext%3D1&size=32`,
    });
  });

  it("searches the vault for the popup tab's page, and fills and copies its results", async () => {
    const searched = responseFields("search") as { items: { handle: string }[] };
    const [, github] = searched.items;
    const context = setup({
      handler: (request) => {
        switch (request.type) {
          case "fill":
            return request.handle === github!.handle
              ? { username: "ann", password: "secret" }
              : { error: "invalid_request" };
          case "totp":
            return { code: "123456" };
          default:
            return undefined;
        }
      },
    });
    await pair(context);
    const app = context.host().app!;
    await expect(context.pages.calls.search({ tabId: 1, query: "  git " })).resolves.toEqual({
      kind: "items",
      origin: LOGIN_ORIGIN,
      truncated: false,
      items: searched.items,
    });
    expect(app.requests.at(-1)).toEqual({
      id: app.requests.at(-1)!.id,
      type: "search",
      query: "git",
      url: "https://login.example.com/sign-in?next=1",
    });

    await expect(
      context.pages.calls.fill({ tabId: 1, handle: github!.handle, origin: LOGIN_ORIGIN }),
    ).resolves.toBeNull();
    expect(app.requests.at(-1)).toMatchObject({
      type: "fill",
      handle: github!.handle,
      url: "https://login.example.com/sign-in?next=1",
    });
    expect(context.fills).toEqual([[1, "https://login.example.com", { username: "ann", password: "secret" }]]);
    await context.controller.calls.copy_totp({ tabId: 1, handle: github!.handle, origin: LOGIN_ORIGIN });
    expect(context.copies).toEqual(["123456"]);
    expect(app.requests.at(-1)).toMatchObject({
      type: "totp",
      handle: github!.handle,
      url: "https://login.example.com/sign-in?next=1",
    });
  });

  it("names a page of a private window in each request of the popup, and no other", async () => {
    const searched = responseFields("search") as { items: { handle: string }[] };
    const handle = searched.items[1]!.handle;
    const context = setup({
      handler: (request) => {
        switch (request.type) {
          case "fill":
            return { username: "ann", password: "secret" };
          case "totp":
            return { code: "123456" };
          default:
            return undefined;
        }
      },
    });
    await pair(context);
    const app = context.host().app!;
    for (const tabId of [1, PRIVATE_TAB]) {
      const from = app.requests.length;
      await context.pages.calls.suggest({ tabId });
      await context.pages.calls.search({ tabId, query: "git" });
      await context.pages.calls.fill({ tabId, handle, origin: LOGIN_ORIGIN });
      await context.controller.calls.copy_password({ tabId, handle, origin: LOGIN_ORIGIN });
      await context.controller.calls.copy_totp({ tabId, handle, origin: LOGIN_ORIGIN });
      await context.controller.calls.generate({ tabId });
      const requests = app.requests.slice(from);
      expect(requests.map((it) => it.type)).toEqual(["suggest", "search", "fill", "fill", "totp", "generate"]);
      for (const request of requests) {
        if (tabId === PRIVATE_TAB)
          expect(request, String(request.type)).toMatchObject({
            url: "https://login.example.com/sign-in?private=1",
            private_window: true,
          });
        else expect(request, String(request.type)).not.toHaveProperty("private_window");
      }
    }
    // Not for a page at all.
    await context.controller.calls.generate({});
    expect(app.requests.at(-1)).not.toHaveProperty("private_window");
  });

  it("sends no search for a query under 2 code points, nor for a page it can't fill", async () => {
    const context = setup();
    await pair(context);
    const app = context.host().app!;
    for (const query of ["", "g", "  g  ", "😀"]) {
      await expect(context.pages.calls.search({ tabId: 1, query })).resolves.toEqual({
        kind: "items",
        origin: LOGIN_ORIGIN,
        truncated: false,
        items: [],
      });
    }
    await expect(context.pages.calls.search({ tabId: 2, query: "git" })).resolves.toEqual({ kind: "unsupported" });
    expect(app.requests.filter((it) => it.type === "search")).toEqual([]);
  });

  it("cancels the popup's search when a newer one comes", async () => {
    const context = setup({
      handler: (request) =>
        request.type === "search" && request.query !== "github" ? new Promise(() => undefined) : undefined,
    });
    await pair(context);
    const app = context.host().app!;
    const searchIds = () => app.requests.filter((it) => it.type === "search").map((it) => it.id);

    const older = context.pages.calls.search({ tabId: 1, query: "git" });
    await until(() => searchIds().length === 1, "the search request");
    const newer = context.pages.calls.search({ tabId: 1, query: "github" });
    await expect(older).rejects.toMatchObject({ code: "cancelled" });
    await expect(newer).resolves.toMatchObject({ kind: "items" });
    expect(app.cancels).toEqual([searchIds()[0]]);

    // A query too short to send cancels it too.
    const stale = context.pages.calls.search({ tabId: 1, query: "gi" });
    await until(() => searchIds().length === 3, "the third search request");
    await expect(context.pages.calls.search({ tabId: 1, query: "g" })).resolves.toMatchObject({ items: [] });
    await expect(stale).rejects.toMatchObject({ code: "cancelled" });
    await until(() => app.cancels.length === 2, "the second cancel");
    expect(app.cancels).toEqual([searchIds()[0], searchIds()[2]]);
  });

  it("searches nothing on a page that matches a blocked URI", async () => {
    const context = setup({
      handler: (request) => (request.type === "search" ? { blocked: true, truncated: false, items: [] } : undefined),
    });
    await pair(context);
    await expect(context.pages.calls.search({ tabId: 1, query: "git" })).resolves.toEqual({
      kind: "blocked",
      host: "login.example.com",
    });
  });

  it("asks the app to fill for the frame with the form, and for nothing without one", async () => {
    const context = setup({
      handler: (request) => (request.type === "fill" ? { username: "ann", password: "secret" } : undefined),
    });
    await pair(context);
    const app = context.host().app!;
    // A sign-in page that the tab's page embeds.
    context.formFrames[1] = { url: "https://idmsa.example.com/signin", top_origin: "https://login.example.com" };
    await expect(context.pages.calls.fill({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN })).resolves.toBeNull();
    expect(app.requests.filter((it) => it.type === "fill")).toMatchObject([
      { handle: "h1", url: "https://idmsa.example.com/signin", top_origin: "https://login.example.com" },
    ]);

    context.formFrames[1] = null;
    await expect(context.pages.calls.fill({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN })).rejects.toMatchObject({
      code: "no_form",
    });
    expect(app.requests.filter((it) => it.type === "fill")).toHaveLength(1);
  });

  it("refuses calls without a paired session", async () => {
    const context = setup();
    context.host().ready();
    await context.waitFor("not_paired");
    await expect(context.controller.calls.generate({})).rejects.toMatchObject({ code: "disconnected" });
    await expect(context.controller.calls.unlock({})).rejects.toMatchObject({ code: "disconnected" });
  });

  it("asks to unlock the app when it refuses to pair while locked", async () => {
    const context = setup();
    const app = context.host().ready();
    await context.waitFor("not_paired");
    await expect(context.controller.calls.pair({})).rejects.toMatchObject({ code: "vault_locked" });
    expect(context.status()).toEqual({ kind: "not_paired", pairing: { phase: "failed", error: "vault_locked" } });

    // The user unlocks in the app and tries again in the same session.
    app.vault = "unlocked";
    await context.controller.calls.pair({});
    expect(context.status()).toEqual({ kind: "unlocked" });
  });

  it("offers every capability it implements, and keeps what the app agreed to", async () => {
    const context = setup();
    context.host().ready();
    await context.waitFor("not_paired");
    expect(context.controller.supports("passkeys")).toBe(true);
    expect(context.controller.supports("save")).toBe(true);

    for (const capabilities of [["save"], []]) {
      const older = setup({ capabilities });
      const app = older.host().ready();
      await older.waitFor("not_paired");
      expect(app.requests[0]).toMatchObject({ type: "hello", capabilities: ["passkeys", "save"] });
      expect(older.controller.supports("passkeys"), capabilities.join()).toBe(false);
      expect(older.controller.supports("save"), capabilities.join()).toBe(capabilities.includes("save"));
    }
  });

  it("shares one unlock while the app prompts, and stays locked if the vault does", async () => {
    let answer: (vault: string) => void = () => undefined;
    const context = setup({
      handler: (request) =>
        request.type === "unlock" ? new Promise((resolve) => (answer = (vault) => resolve({ vault }))) : undefined,
    });
    await pair(context);
    const app = context.host().app!;
    const first = context.controller.unlock();
    const second = context.controller.calls.unlock({});
    await until(() => app.requests.some((it) => it.type === "unlock"), "the unlock request");
    expect(context.status()).toEqual({ kind: "locked", unlocking: true });
    // The user approved, but the vault stayed locked (protocol §5).
    answer("locked");
    await Promise.all([first, second]);
    expect(app.requests.filter((it) => it.type === "unlock")).toHaveLength(1);
    expect(context.status()).toEqual({ kind: "locked", unlocking: false });
  });

  it("is busy while the app answers a request, as while it prompts", async () => {
    let answer: () => void = () => undefined;
    const context = setup({
      handler: (request) =>
        request.type === "unlock"
          ? new Promise((resolve) => (answer = () => resolve({ vault: "unlocked" })))
          : undefined,
    });
    await pair(context);
    expect(context.controller.busy).toBe(false);
    const unlock = context.controller.unlock();
    await until(() => context.host().app!.requests.some((it) => it.type === "unlock"), "the unlock request");
    expect(context.controller.busy).toBe(true);
    answer();
    await unlock;
    expect(context.controller.busy).toBe(false);
  });

  it("cancels a shared unlock once no caller waits, and shows no error", async () => {
    const context = setup({
      handler: (request) => (request.type === "unlock" ? new Promise(() => undefined) : undefined),
    });
    await pair(context);
    const app = context.host().app!;
    const menu = new AbortController();
    const bar = new AbortController();
    const fromMenu = context.controller.unlock(menu.signal);
    const fromBar = context.controller.unlock(bar.signal);
    await until(() => app.requests.some((it) => it.type === "unlock"), "the unlock request");
    menu.abort();
    await expect(fromMenu).rejects.toMatchObject({ code: "cancelled" });
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(app.cancels).toEqual([]);
    bar.abort();
    await expect(fromBar).rejects.toMatchObject({ code: "cancelled" });
    await until(() => app.cancels.length === 1, "the cancel");
    expect(app.cancels).toEqual([app.requests.find((it) => it.type === "unlock")!.id]);
    await until(
      () => context.status().kind === "locked" && !(context.status() as { unlocking: boolean }).unlocking,
      "the status",
    );
    expect(context.status()).toEqual({ kind: "locked", unlocking: false });

    // The popup closes as the app prompts: its unlock isn't cancelled.
    const page = new AbortController();
    const fromPopup = context.controller.calls.unlock({});
    void context.controller.unlock(page.signal).catch(() => undefined);
    await until(() => app.requests.filter((it) => it.type === "unlock").length === 2, "the second unlock");
    page.abort();
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(app.cancels).toHaveLength(1);
    void fromPopup.catch(() => undefined);
  });

  it("cancels the popup's fill when its tab closes or leaves the page's origin", async () => {
    const context = setup({
      handler: (request) => (request.type === "fill" ? new Promise(() => undefined) : undefined),
    });
    await pair(context);
    const app = context.host().app!;
    const fillIds = () => app.requests.filter((it) => it.type === "fill").map((it) => it.id);

    const navigated = context.pages.calls.fill({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN });
    await until(() => fillIds().length === 1, "the fill request");
    // Same origin, as a single-page app's route change: still waiting.
    context.tabWatch.navigated(1, "https://login.example.com/two-factor");
    context.tabWatch.navigated(2, "https://other.example/");
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(app.cancels).toEqual([]);
    context.tabWatch.navigated(1, "https://other.example/");
    await expect(navigated).rejects.toMatchObject({ code: "cancelled" });

    const closed = context.pages.calls.fill({ tabId: 1, handle: "h1", origin: LOGIN_ORIGIN });
    await until(() => fillIds().length === 2, "the second fill request");
    context.tabWatch.removed(1);
    await expect(closed).rejects.toMatchObject({ code: "cancelled" });
    await until(() => app.cancels.length === 2, "the cancels");
    expect(app.cancels).toEqual(fillIds());
    expect(context.fills).toEqual([]);
  });
});
