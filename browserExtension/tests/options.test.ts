// The options in storage.local.
import { beforeEach, describe, expect, it } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import {
  DEFAULT_OPTIONS,
  OPTIONS_KEY,
  OptionsStore,
  parseOptions,
  watchOptions,
  type ExtensionOptions,
  type ObservableStorageArea,
} from "../src/lib/options/store";

/** `storage.local` of the fake browser. */
const localArea = () => fakeBrowser.storage.local as unknown as ObservableStorageArea;
/** The options record as stored. */
const storedRecord = async () => (await localArea().get(OPTIONS_KEY))[OPTIONS_KEY];

beforeEach(() => {
  fakeBrowser.reset();
});

describe("options", () => {
  it("defaults to everything on but the submit, and ignores malformed values", () => {
    expect(DEFAULT_OPTIONS).toEqual({
      inlineMenu: true,
      fillPasswordStep: true,
      submitAfterFill: false,
      savePrompts: true,
      passkeys: true,
    });
    expect(parseOptions(undefined)).toEqual(DEFAULT_OPTIONS);
    expect(parseOptions({ inlineMenu: "no", savePrompts: false })).toEqual({ ...DEFAULT_OPTIONS, savePrompts: false });
  });

  it("reads the password step and submit options", () => {
    // Stored before these options existed.
    expect(parseOptions({ inlineMenu: false, savePrompts: true, passkeys: true })).toEqual({
      ...DEFAULT_OPTIONS,
      inlineMenu: false,
    });
    expect(parseOptions({ fillPasswordStep: "false", submitAfterFill: 1 })).toEqual(DEFAULT_OPTIONS);
    expect(parseOptions({ fillPasswordStep: null, submitAfterFill: "true" })).toEqual(DEFAULT_OPTIONS);
    expect(parseOptions({ fillPasswordStep: false, submitAfterFill: true })).toEqual({
      ...DEFAULT_OPTIONS,
      fillPasswordStep: false,
      submitAfterFill: true,
    });
    expect(parseOptions({ fillPasswordStep: true, submitAfterFill: false })).toEqual(DEFAULT_OPTIONS);
  });

  it("stores changes, which content scripts follow", async () => {
    const storage = localArea();
    const store = new OptionsStore(storage);
    const seen: ExtensionOptions[] = [];
    const stop = watchOptions(storage, (options) => seen.push(options));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(seen).toEqual([DEFAULT_OPTIONS]);

    await store.setOptions({ inlineMenu: false });
    await store.setOptions({ passkeys: false });
    await store.setOptions({ fillPasswordStep: false, submitAfterFill: true });
    const changed = {
      ...DEFAULT_OPTIONS,
      inlineMenu: false,
      fillPasswordStep: false,
      submitAfterFill: true,
      passkeys: false,
    };
    expect(await store.options()).toEqual(changed);
    expect(seen.at(-1)).toEqual(changed);
    expect(await storedRecord()).toEqual({
      version: 1,
      inlineMenu: false,
      fillPasswordStep: false,
      submitAfterFill: true,
      passkeys: false,
    });

    stop();
    await store.setOptions({ savePrompts: false });
    expect(seen.at(-1)!.savePrompts).toBe(true);
  });

  it("stores only the options the user set, so a changed default reaches the others", async () => {
    const storage = localArea();
    await new OptionsStore(storage).setOptions({ inlineMenu: false });
    expect(await storedRecord()).toEqual({ version: 1, inlineMenu: false });

    const defaults = DEFAULT_OPTIONS as ExtensionOptions;
    defaults.submitAfterFill = true;
    try {
      expect(await new OptionsStore(storage).options()).toEqual({ ...DEFAULT_OPTIONS, inlineMenu: false, submitAfterFill: true });
    } finally {
      defaults.submitAfterFill = false;
    }
  });

  it("drops unknown and malformed options when it stores a change", async () => {
    const storage = localArea();
    await storage.set({ [OPTIONS_KEY]: { inlineMenu: "no", passkeys: false, unknown: true } });
    await new OptionsStore(storage).setOptions({ savePrompts: false });
    expect(await storedRecord()).toEqual({ version: 1, passkeys: false, savePrompts: false });
  });

  it("reads a record of a newer version as no options set, and replaces it on a change", async () => {
    const storage = localArea();
    await storage.set({ [OPTIONS_KEY]: { version: 2, inlineMenu: false } });
    const store = new OptionsStore(storage);
    expect(await store.options()).toEqual(DEFAULT_OPTIONS);
    await store.setOptions({ passkeys: false });
    expect(await storedRecord()).toEqual({ version: 1, passkeys: false });
  });

  it("drops a first read that a change overtook", async () => {
    let resolve!: (stored: Record<string, unknown>) => void;
    const listeners: ((changes: Record<string, { newValue?: unknown }>) => void)[] = [];
    const slow = {
      get: () => new Promise<Record<string, unknown>>((it) => (resolve = it)),
      set: async () => undefined,
      onChanged: { addListener: (it: (typeof listeners)[number]) => listeners.push(it), removeListener: () => undefined },
    };
    const seen: ExtensionOptions[] = [];
    watchOptions(slow, (options) => seen.push(options));
    for (const listener of listeners) listener({ [OPTIONS_KEY]: { newValue: { inlineMenu: false } } });
    resolve({ [OPTIONS_KEY]: { inlineMenu: true } });
    await new Promise((it) => setTimeout(it, 0));
    expect(seen).toEqual([{ ...DEFAULT_OPTIONS, inlineMenu: false }]);
  });
});
