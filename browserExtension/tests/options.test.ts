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
    const storage = fakeBrowser.storage.local as unknown as ObservableStorageArea;
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
    expect((await storage.get(OPTIONS_KEY))[OPTIONS_KEY]).toEqual(changed);

    stop();
    await store.setOptions({ savePrompts: false });
    expect(seen.at(-1)!.savePrompts).toBe(true);
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
