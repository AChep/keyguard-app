// @vitest-environment happy-dom
// The Firefox theme's tokens as stored (src/lib/ui/storedTheme.ts): what's
// taken back from storage, where they apply, and how the pages and the
// content script follow them.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeBrowser } from "wxt/testing/fake-browser";
import type { ObservableStorageArea } from "../src/lib/options/store";
import {
  applyTheme,
  followTheme,
  frameColors,
  parseStoredTheme,
  parseThemeJson,
  THEME_KEY,
  THEME_TOKENS,
  themeApplies,
  toStoredTheme,
  watchTheme,
  type StoredTheme,
  type ThemeTokens,
} from "../src/lib/ui/storedTheme";

/** A color for each token, different for each and for [base]. */
function tokens(base: number): ThemeTokens {
  return Object.fromEntries(
    THEME_TOKENS.map((token, index) => [token, `#${(base + index).toString(16).padStart(6, "0")}`]),
  ) as ThemeTokens;
}

const DARK: StoredTheme = { v: 1, dark: true, tokens: tokens(0x101000) };
const LIGHT: StoredTheme = { v: 1, dark: false, tokens: tokens(0xf0f000) };

const area = () => fakeBrowser.storage.local as unknown as ObservableStorageArea;

async function settled() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

beforeEach(() => {
  fakeBrowser.reset();
});

afterEach(() => {
  const style = document.documentElement.style;
  for (const token of THEME_TOKENS) style.removeProperty(`--${token}`);
  style.removeProperty("color-scheme");
  vi.restoreAllMocks();
});

describe("parseStoredTheme", () => {
  it("takes what the background stores, in the tokens' order", () => {
    expect(parseStoredTheme(JSON.parse(JSON.stringify(DARK)))).toEqual(DARK);
    expect(parseStoredTheme(LIGHT)).toEqual(LIGHT);
    const shuffled = { tokens: Object.fromEntries(Object.entries(DARK.tokens).reverse()), dark: true, v: 1 };
    expect(JSON.stringify(parseStoredTheme(shuffled))).toBe(JSON.stringify(DARK));
    expect(JSON.stringify(toStoredTheme({ dark: true, tokens: shuffled.tokens as ThemeTokens }))).toBe(
      JSON.stringify(DARK),
    );
  });

  const { surface: _, ...missing } = DARK.tokens;
  it.each([
    ["nothing", undefined],
    ["null", null],
    ["a string", JSON.stringify(DARK)],
    ["an array", [DARK]],
    ["an empty object", {}],
    ["another version", { ...DARK, v: 2 }],
    ["no version", { dark: true, tokens: DARK.tokens }],
    ["a version as text", { ...DARK, v: "1" }],
    ["a scheme as text", { ...DARK, dark: "true" }],
    ["no tokens", { v: 1, dark: true }],
    ["tokens as an array", { ...DARK, tokens: Object.values(DARK.tokens) }],
    ["a key more", { ...DARK, extra: 1 }],
    ["a token missing", { ...DARK, tokens: missing }],
    ["a token unknown", { ...DARK, tokens: { ...DARK.tokens, "surface-bright": "#ffffff" } }],
    ["a token renamed", { ...DARK, tokens: { ...missing, "--surface": "#ffffff" } }],
    ["a color in capitals", { ...DARK, tokens: { ...DARK.tokens, surface: "#FFFFFF" } }],
    ["a short color", { ...DARK, tokens: { ...DARK.tokens, surface: "#fff" } }],
    ["a color with alpha", { ...DARK, tokens: { ...DARK.tokens, surface: "#ffffff80" } }],
    ["a named color", { ...DARK, tokens: { ...DARK.tokens, surface: "red" } }],
    ["a color function", { ...DARK, tokens: { ...DARK.tokens, surface: "rgb(0, 0, 0)" } }],
    ["a variable", { ...DARK, tokens: { ...DARK.tokens, surface: "var(--x)" } }],
    ["more CSS", { ...DARK, tokens: { ...DARK.tokens, surface: "#000000; background: url(https://example.com)" } }],
    ["a color as a number", { ...DARK, tokens: { ...DARK.tokens, surface: 0 } }],
  ])("ignores %s", (_name, value) => {
    expect(parseStoredTheme(value)).toBeNull();
  });

  it("ignores a malformed copy in localStorage", () => {
    expect(parseThemeJson(JSON.stringify(DARK))).toEqual(DARK);
    expect(parseThemeJson(null)).toBeNull();
    expect(parseThemeJson("")).toBeNull();
    expect(parseThemeJson("{")).toBeNull();
    expect(parseThemeJson("null")).toBeNull();
    expect(parseThemeJson(JSON.stringify({ ...DARK, v: 2 }))).toBeNull();
  });
});

describe("themeApplies", () => {
  it.each([
    ["a dark theme on a light page", DARK, false, true],
    ["a dark theme on a dark page", DARK, true, true],
    ["a light theme on a light page", LIGHT, false, true],
    // A dark_theme that Firefox reports as its light colors, or a page that
    // follows the website appearance.
    ["a light theme on a dark page", LIGHT, true, false],
    ["no theme on a light page", null, false, false],
    ["no theme on a dark page", null, true, false],
  ])("%s", (_name, stored, pageDark, applies) => {
    expect(themeApplies(stored, pageDark)).toBe(applies);
  });
});

describe("applyTheme", () => {
  function tokensOn(root: HTMLElement): Record<string, string> {
    return Object.fromEntries(THEME_TOKENS.map((token) => [token, root.style.getPropertyValue(`--${token}`)]));
  }
  const none = Object.fromEntries(THEME_TOKENS.map((token) => [token, ""]));

  it("sets every token and the theme's scheme where it applies", () => {
    const root = document.createElement("div");
    applyTheme(root, DARK, false);
    expect(tokensOn(root)).toEqual(DARK.tokens);
    expect(root.style.getPropertyValue("color-scheme")).toBe("dark");
    applyTheme(root, LIGHT, false);
    expect(tokensOn(root)).toEqual(LIGHT.tokens);
    expect(root.style.getPropertyValue("color-scheme")).toBe("light");
    applyTheme(root, DARK, true);
    expect(tokensOn(root)).toEqual(DARK.tokens);
    expect(root.style.getPropertyValue("color-scheme")).toBe("dark");
  });

  it("removes them for theme.css where it doesn't apply, and only them", () => {
    const root = document.createElement("div");
    root.style.setProperty("--frame-radius", "16px");
    applyTheme(root, LIGHT, false);
    applyTheme(root, LIGHT, true);
    expect(tokensOn(root)).toEqual(none);
    expect(root.style.getPropertyValue("color-scheme")).toBe("");
    expect(root.style.getPropertyValue("--frame-radius")).toBe("16px");
    applyTheme(root, DARK, true);
    applyTheme(root, null, false);
    expect(tokensOn(root)).toEqual(none);
    expect(root.style.getPropertyValue("color-scheme")).toBe("");
  });
});

describe("frameColors", () => {
  it("is the theme's frame color where the theme applies", () => {
    const dark = DARK.tokens["surface-container"];
    const light = LIGHT.tokens["surface-container"];
    expect(frameColors(DARK)).toEqual({ light: dark, dark });
    expect(frameColors(LIGHT)).toEqual({ light, dark: null });
    expect(frameColors(null)).toEqual({ light: null, dark: null });
  });
});

describe("watchTheme", () => {
  it("reads the stored theme, then follows it", async () => {
    await fakeBrowser.storage.local.set({ [THEME_KEY]: DARK });
    const seen: (StoredTheme | null)[] = [];
    const stop = watchTheme(area(), (stored) => seen.push(stored));
    await settled();
    expect(seen).toEqual([DARK]);

    await fakeBrowser.storage.local.set({ [THEME_KEY]: LIGHT });
    await fakeBrowser.storage.local.set({ [THEME_KEY]: { ...LIGHT, v: 2 } });
    await fakeBrowser.storage.local.set({ options: { inlineMenu: false } });
    await fakeBrowser.storage.local.remove(THEME_KEY);
    expect(seen).toEqual([DARK, LIGHT, null, null]);

    stop();
    await fakeBrowser.storage.local.set({ [THEME_KEY]: DARK });
    expect(seen).toHaveLength(4);
  });

  it("drops a first read that a change overtook", async () => {
    let resolve!: (stored: Record<string, unknown>) => void;
    const listeners: ((changes: Record<string, { newValue?: unknown }>) => void)[] = [];
    const slow = {
      get: () => new Promise<Record<string, unknown>>((it) => (resolve = it)),
      onChanged: {
        addListener: (it: (typeof listeners)[number]) => listeners.push(it),
        removeListener: () => undefined,
      },
    };
    const seen: (StoredTheme | null)[] = [];
    watchTheme(slow, (stored) => seen.push(stored));
    for (const listener of listeners) listener({ [THEME_KEY]: { newValue: LIGHT } });
    resolve({ [THEME_KEY]: DARK });
    await settled();
    expect(seen).toEqual([LIGHT]);
  });
});

describe("followTheme", () => {
  /** A page's window: its document, a localStorage and a `prefers-color-scheme` that tests change. */
  function page(options: { dark: boolean; mirror?: string | null; storage?: "throws" }) {
    const listeners = new Set<() => void>();
    const query = {
      matches: options.dark,
      addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
      removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
    };
    const items = new Map<string, string>();
    if (options.mirror != null) items.set(THEME_KEY, options.mirror);
    const win = {
      document,
      matchMedia: (media: string) => {
        expect(media).toBe("(prefers-color-scheme: dark)");
        return query;
      },
      get localStorage() {
        if (options.storage === "throws") throw new DOMException("The operation is insecure.", "SecurityError");
        return { getItem: (key: string) => items.get(key) ?? null };
      },
    } as unknown as Window;
    const setDark = (dark: boolean) => {
      query.matches = dark;
      for (const listener of listeners) listener();
    };
    return { win, setDark, listeners };
  }

  const surface = () => document.documentElement.style.getPropertyValue("--surface");
  const scheme = () => document.documentElement.style.getPropertyValue("color-scheme");

  it("applies the localStorage copy before it returns", () => {
    const { win } = page({ dark: false, mirror: JSON.stringify(DARK) });
    followTheme(win, area());
    expect(surface()).toBe(DARK.tokens.surface);
    expect(scheme()).toBe("dark");
  });

  it("follows storage.local and the page's scheme", async () => {
    const { win, setDark, listeners } = page({ dark: false, mirror: null });
    const stop = followTheme(win, area());
    expect(surface()).toBe("");

    await fakeBrowser.storage.local.set({ [THEME_KEY]: LIGHT });
    expect(surface()).toBe(LIGHT.tokens.surface);
    expect(scheme()).toBe("light");
    setDark(true);
    expect(surface()).toBe("");
    expect(scheme()).toBe("");
    await fakeBrowser.storage.local.set({ [THEME_KEY]: DARK });
    expect(surface()).toBe(DARK.tokens.surface);
    expect(scheme()).toBe("dark");
    await fakeBrowser.storage.local.remove(THEME_KEY);
    expect(surface()).toBe("");

    stop();
    expect(listeners.size).toBe(0);
    await fakeBrowser.storage.local.set({ [THEME_KEY]: DARK });
    expect(surface()).toBe("");
  });

  it("takes storage.local over a stale copy", async () => {
    await fakeBrowser.storage.local.set({ [THEME_KEY]: LIGHT });
    const { win } = page({ dark: false, mirror: JSON.stringify(DARK) });
    followTheme(win, area());
    expect(surface()).toBe(DARK.tokens.surface);
    await settled();
    expect(surface()).toBe(LIGHT.tokens.surface);
  });

  it("starts with theme.css without a usable copy", async () => {
    for (const options of [
      { mirror: "{" },
      { mirror: JSON.stringify({ ...DARK, v: 2 }) },
      { storage: "throws" as const },
    ]) {
      const { win } = page({ dark: false, ...options });
      const stop = followTheme(win, area());
      expect(surface()).toBe("");
      stop();
    }
    const { win } = page({ dark: false, storage: "throws" });
    followTheme(win, area());
    await fakeBrowser.storage.local.set({ [THEME_KEY]: DARK });
    expect(surface()).toBe(DARK.tokens.surface);
  });
});
