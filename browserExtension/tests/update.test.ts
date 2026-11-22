// The extension's own updates: a downloaded update installs once the
// extension is idle, or at the deadline; the app's `extension_update_required`
// asks the browser for the update; open tabs get the new content scripts.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ExtensionStatus } from "../src/lib/background/api";
import {
  ExtensionUpdater,
  IDLE_CHECK_MS,
  reinjectContentScripts,
  UPDATE_DEADLINE_MS,
  type UpdateCheck,
  type UpdaterDeps,
} from "../src/lib/background/update";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function setup(deps: Partial<UpdaterDeps> = {}) {
  const state = { idle: true, reloads: 0 };
  const updater = new ExtensionUpdater({
    idle: () => state.idle,
    reload: () => void state.reloads++,
    ...deps,
  });
  return { updater, state };
}

const UPDATE_REQUIRED: ExtensionStatus = { kind: "extension_update_required" };

/** A call that settles when the test says. */
function pending<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

describe("a downloaded update", () => {
  it("installs at once when nothing goes on", () => {
    const { updater, state } = setup();
    updater.updateAvailable();
    expect(state.reloads).toBe(1);
  });

  it("waits while the extension is busy, and installs at the first check after", () => {
    const { updater, state } = setup();
    state.idle = false;
    updater.updateAvailable();
    vi.advanceTimersByTime(3 * IDLE_CHECK_MS);
    expect(state.reloads).toBe(0);

    state.idle = true;
    vi.advanceTimersByTime(IDLE_CHECK_MS - 1);
    expect(state.reloads).toBe(0);
    vi.advanceTimersByTime(1);
    expect(state.reloads).toBe(1);
    // Its timers stopped.
    vi.advanceTimersByTime(UPDATE_DEADLINE_MS);
    expect(state.reloads).toBe(1);
  });

  it("waits while a call is being answered, and installs at the first check after it settles", async () => {
    const { updater, state } = setup();
    // Between the app's requests: the rest of the extension is idle.
    const call = pending<string>();
    const answer = updater.track(call.promise);
    updater.updateAvailable();
    expect(state.reloads).toBe(0);
    vi.advanceTimersByTime(3 * IDLE_CHECK_MS);
    expect(state.reloads).toBe(0);

    call.resolve("paired");
    await expect(answer).resolves.toBe("paired");
    vi.advanceTimersByTime(IDLE_CHECK_MS - 1);
    expect(state.reloads).toBe(0);
    vi.advanceTimersByTime(1);
    expect(state.reloads).toBe(1);
  });

  it("counts each call, and one that fails releases it as well", async () => {
    const { updater, state } = setup();
    const first = pending<null>();
    const second = pending<null>();
    const firstAnswer = updater.track(first.promise);
    const secondAnswer = updater.track(second.promise);
    updater.updateAvailable();

    first.resolve(null);
    await firstAnswer;
    vi.advanceTimersByTime(IDLE_CHECK_MS);
    expect(state.reloads).toBe(0);

    // The failure still reaches the caller.
    const error = new Error("The tab closed");
    second.reject(error);
    await expect(secondAnswer).rejects.toBe(error);
    vi.advanceTimersByTime(IDLE_CHECK_MS);
    expect(state.reloads).toBe(1);
  });

  it("installs at the deadline while the extension stays busy", () => {
    const { updater, state } = setup();
    state.idle = false;
    updater.updateAvailable();
    // Another event of the same update doesn't start another wait.
    vi.advanceTimersByTime(60_000);
    updater.updateAvailable();
    vi.advanceTimersByTime(UPDATE_DEADLINE_MS - 60_000 - 1);
    expect(state.reloads).toBe(0);
    vi.advanceTimersByTime(1);
    expect(state.reloads).toBe(1);
    vi.advanceTimersByTime(UPDATE_DEADLINE_MS);
    expect(state.reloads).toBe(1);
  });

  it("installs as the background suspends, which ends what it waited for", () => {
    const { updater, state } = setup();
    updater.suspending();
    expect(state.reloads).toBe(0);

    state.idle = false;
    updater.updateAvailable();
    updater.suspending();
    expect(state.reloads).toBe(1);
    vi.advanceTimersByTime(UPDATE_DEADLINE_MS);
    expect(state.reloads).toBe(1);
  });
});

describe("an app that needs a newer extension", () => {
  /** Lets the check's answer go through. */
  const answered = () => vi.advanceTimersByTimeAsync(0);

  it("asks the browser for an update once, and no other status does", async () => {
    const check = vi.fn(async (): Promise<UpdateCheck> => ({ status: "update_available" }));
    const { updater, state } = setup({ requestUpdateCheck: check });
    for (const status of [
      { kind: "update_required" },
      { kind: "app_update_required" },
      { kind: "unlocked" },
    ] as ExtensionStatus[]) {
      updater.statusChanged(status);
    }
    await answered();
    expect(check).not.toHaveBeenCalled();

    updater.statusChanged(UPDATE_REQUIRED);
    updater.statusChanged({ kind: "starting" });
    updater.statusChanged(UPDATE_REQUIRED);
    await answered();
    expect(check).toHaveBeenCalledTimes(1);
    // The update installs as it comes in, through `onUpdateAvailable`.
    expect(state.reloads).toBe(0);
  });

  it("takes `throttled`, `no_update` and failures quietly", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const answers: (() => Promise<UpdateCheck>)[] = [
      async () => ({ status: "throttled" }),
      async () => ({ status: "no_update" }),
      async () => Promise.reject(new Error("Not supported")),
      () => {
        throw new TypeError("requestUpdateCheck is not a function");
      },
    ];
    for (const answer of answers) {
      const { updater, state } = setup({ requestUpdateCheck: answer });
      updater.statusChanged(UPDATE_REQUIRED);
      await answered();
      expect(state.reloads).toBe(0);
    }
    expect(warn).toHaveBeenCalledTimes(answers.length);
  });

  it("asks nothing where the browser has no update check, as Firefox", () => {
    const { updater } = setup();
    expect(() => updater.statusChanged(UPDATE_REQUIRED)).not.toThrow();
  });
});

describe("open tabs after an update", () => {
  const WEB = ["http://*/*", "https://*/*"];

  it("get the manifest's isolated content scripts where they match, past tabs that fail", async () => {
    const queries: unknown[] = [];
    const injected: [number, string[], boolean][] = [];
    await reinjectContentScripts({
      contentScripts: [
        { matches: WEB, js: ["content-scripts/content.js"], all_frames: true },
        { matches: WEB, js: ["content-scripts/webauthn.js"], all_frames: true, world: "MAIN" },
        { matches: ["https://only.example/*"], js: ["content-scripts/top.js"] },
        // Nothing to start.
        { matches: WEB, css: ["style.css"] } as never,
      ],
      tabs: async (query) => {
        queries.push(query);
        return query.url === WEB ? [{ id: 1 }, {}, { id: 5 }, { id: 6 }] : [{ id: 7 }];
      },
      inject: async (tabId, files, allFrames) => {
        if (tabId === 5) throw new Error("Cannot access contents of the page.");
        injected.push([tabId, files, allFrames]);
      },
    });
    expect(queries).toEqual([
      { url: WEB, discarded: false },
      { url: ["https://only.example/*"], discarded: false },
    ]);
    expect(injected).toEqual([
      [1, ["content-scripts/content.js"], true],
      [6, ["content-scripts/content.js"], true],
      [7, ["content-scripts/top.js"], false],
    ]);
  });
});
