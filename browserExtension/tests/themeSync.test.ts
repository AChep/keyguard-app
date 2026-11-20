// The background's copy of the Firefox theme (src/lib/background/theme.ts):
// it stores the tokens of the current theme, only when they change, removes
// them without a theme, and never fails the background.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import { ThemeSync, type FirefoxThemeApi, type ThemeMirror } from "../src/lib/background/theme";
import { themeTokens } from "../src/lib/ui/browserTheme";
import { parseStoredTheme, parseThemeJson, THEME_KEY, type StoredTheme } from "../src/lib/ui/storedTheme";

const LIGHT = { colors: { popup: "#ffffff", popup_text: "#15141a", tab_line: "#0060df" } };
const DARK = { colors: { popup: "#1e2926", popup_text: "#e2f0e9", tab_line: "#4ecda0" } };
const NO_COLORS = { colors: null, images: null, properties: null };

/** Firefox's `theme`, with the theme the test sets. */
function fakeTheme(current: unknown) {
  const listeners: ((update: unknown) => void)[] = [];
  const api = {
    current,
    getCurrent: vi.fn(async (): Promise<unknown> => api.current),
    onUpdated: { addListener: (listener: (update: unknown) => void) => void listeners.push(listener) },
    /** Sets the theme and tells, as Firefox does. */
    update(theme: unknown) {
      api.current = theme;
      for (const listener of listeners) listener({ theme });
    },
    listeners,
  };
  return api;
}

/** A localStorage that counts its writes. */
function fakeMirror() {
  const items = new Map<string, string>();
  const mirror = {
    items,
    getItem: (key: string) => items.get(key) ?? null,
    setItem: vi.fn((key: string, value: string) => void items.set(key, value)),
    removeItem: vi.fn((key: string) => void items.delete(key)),
  };
  return mirror;
}

function setup(theme: FirefoxThemeApi | undefined, mirror: () => ThemeMirror = () => fakeMirror()) {
  const area = fakeBrowser.storage.local;
  const set = vi.spyOn(area, "set");
  const remove = vi.spyOn(area, "remove");
  const sync = new ThemeSync({ theme, themeTokens, area, mirror });
  return { sync, set, remove };
}

async function stored(): Promise<unknown> {
  return (await fakeBrowser.storage.local.get(THEME_KEY))[THEME_KEY];
}

let warn: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  fakeBrowser.reset();
  warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ThemeSync", () => {
  it("stores the theme's tokens and their copy on start, then writes only what changed", async () => {
    const theme = fakeTheme(LIGHT);
    const mirror = fakeMirror();
    const { sync, set } = setup(theme, () => mirror);
    sync.start();
    await sync.sync();

    const expected: StoredTheme = { v: 1, ...themeTokens(LIGHT)! };
    expect(await stored()).toEqual(expected);
    expect(parseStoredTheme(await stored())).toEqual(expected);
    expect(parseThemeJson(mirror.getItem(THEME_KEY))).toEqual(expected);
    expect(set).toHaveBeenCalledTimes(1);
    expect(mirror.setItem).toHaveBeenCalledTimes(1);

    // Another start, as the event page wakes again, with the same theme.
    await new ThemeSync({ theme, themeTokens, area: fakeBrowser.storage.local, mirror: () => mirror }).sync();
    expect(set).toHaveBeenCalledTimes(1);
    expect(mirror.setItem).toHaveBeenCalledTimes(1);
  });

  it("follows the theme's changes", async () => {
    const theme = fakeTheme(LIGHT);
    const mirror = fakeMirror();
    const { sync, set } = setup(theme, () => mirror);
    sync.start();
    await sync.sync();
    expect(theme.listeners).toHaveLength(1);

    theme.update(DARK);
    await sync.sync();
    expect(await stored()).toEqual({ v: 1, ...themeTokens(DARK)! });
    expect(parseThemeJson(mirror.getItem(THEME_KEY))!.dark).toBe(true);
    expect(set).toHaveBeenCalledTimes(2);
    // The theme of the last focused window.
    expect(theme.getCurrent).toHaveBeenLastCalledWith();
  });

  it("removes the tokens when the theme has none, once", async () => {
    const theme = fakeTheme(DARK);
    const mirror = fakeMirror();
    const { sync, remove } = setup(theme, () => mirror);
    sync.start();
    await sync.sync();

    theme.update(NO_COLORS);
    await sync.sync();
    expect(await stored()).toBeUndefined();
    expect(mirror.items.has(THEME_KEY)).toBe(false);
    expect(remove).toHaveBeenCalledTimes(1);
    expect(mirror.removeItem).toHaveBeenCalledTimes(1);

    // A theme the extension can't use.
    theme.update({ colors: { popup: "#777777", popup_text: "#9a9a9a" } });
    await sync.sync();
    expect(remove).toHaveBeenCalledTimes(1);
    expect(mirror.removeItem).toHaveBeenCalledTimes(1);
  });

  it("keeps Keyguard's colors without the theme API, and doesn't throw", async () => {
    await fakeBrowser.storage.local.set({ [THEME_KEY]: { v: 1, ...themeTokens(DARK)! } });
    const mirror = fakeMirror();
    mirror.items.set(THEME_KEY, "{}");
    const { sync } = setup(undefined, () => mirror);
    expect(() => sync.start()).not.toThrow();
    await sync.sync();
    expect(await stored()).toBeUndefined();
    expect(mirror.items.has(THEME_KEY)).toBe(false);
    expect(warn).not.toHaveBeenCalled();
  });

  it("replaces a malformed stored value, and a stale copy", async () => {
    await fakeBrowser.storage.local.set({ [THEME_KEY]: { v: 1, dark: false, tokens: { surface: "red" } } });
    const mirror = fakeMirror();
    mirror.items.set(THEME_KEY, "{");
    const { sync, set } = setup(fakeTheme(LIGHT), () => mirror);
    await sync.sync();
    expect(parseStoredTheme(await stored())).not.toBeNull();
    expect(parseThemeJson(mirror.getItem(THEME_KEY))).toEqual(parseStoredTheme(await stored()));
    expect(set).toHaveBeenCalledTimes(1);

    // Only the copy is stale, as after the user cleared the site data.
    mirror.items.clear();
    await sync.sync();
    expect(set).toHaveBeenCalledTimes(1);
    expect(parseThemeJson(mirror.getItem(THEME_KEY))).toEqual(parseStoredTheme(await stored()));
  });

  it("stores the tokens without localStorage", async () => {
    const { sync } = setup(fakeTheme(LIGHT), () => {
      throw new DOMException("The operation is insecure.", "SecurityError");
    });
    await sync.sync();
    expect(parseStoredTheme(await stored())).not.toBeNull();
    expect(warn).not.toHaveBeenCalled();
  });

  it("keeps what's stored when the theme can't be read, and doesn't reject", async () => {
    const theme = fakeTheme(LIGHT);
    const { sync, set, remove } = setup(theme);
    await sync.sync();
    theme.getCurrent.mockRejectedValueOnce(new Error("No window"));
    await expect(sync.sync()).resolves.toBeUndefined();
    expect(set).toHaveBeenCalledTimes(1);
    expect(remove).not.toHaveBeenCalled();
    expect(parseStoredTheme(await stored())).not.toBeNull();
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it("takes the tokens of a mapper loaded later", async () => {
    const sync = new ThemeSync({
      theme: fakeTheme(DARK),
      themeTokens: async (theme) => themeTokens(theme),
      area: fakeBrowser.storage.local,
      mirror: () => fakeMirror(),
    });
    await sync.sync();
    expect(parseStoredTheme(await stored())!.dark).toBe(true);
  });
});
