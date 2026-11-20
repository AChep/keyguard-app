// The Firefox theme as the extension's color tokens (src/lib/ui/browserTheme.ts):
// which themes give tokens and of which scheme, the colors taken as the theme
// has them, and the contrast every output keeps.
import { readFileSync } from "node:fs";
import { Hct, argbFromHex } from "@material/material-color-utilities";
import { describe, expect, it } from "vitest";
import { parseThemeColor, themeTokens } from "../src/lib/ui/browserTheme";
import { THEME_TOKENS, type ThemeScheme, type ThemeToken, type ThemeTokens } from "../src/lib/ui/storedTheme";

const THEME_CSS = readFileSync(new URL("../src/lib/ui/theme.css", import.meta.url), "utf8");

/** The `--name: #rrggbb` declarations of the first block after [opening] in theme.css. */
function cssColors(opening: string): Map<string, string> {
  const start = THEME_CSS.indexOf(opening);
  const block = THEME_CSS.slice(start, THEME_CSS.indexOf("}", start));
  return new Map([...block.matchAll(/--([a-z-]+):\s*(#[0-9a-f]{6});/g)].map((it) => [it[1]!, it[2]!]));
}

const LIGHT_CSS = cssColors(":root {");
const DARK_CSS = cssColors("@media (prefers-color-scheme: dark) {");

/** WCAG 2's relative luminance of `#rrggbb`, 0 to 1, apart from the code under test. */
function luminance(hex: string): number {
  const channel = (index: number) => {
    const c = parseInt(hex.slice(index, index + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

function hue(hex: string): number {
  return Hct.fromInt(argbFromHex(hex)).hue;
}

function hueDistance(a: number, b: number): number {
  const d = Math.abs(a - b) % 360;
  return Math.min(d, 360 - d);
}

function isGrey(hex: string): boolean {
  return hex.slice(1, 3) === hex.slice(3, 5) && hex.slice(3, 5) === hex.slice(5, 7);
}

const SURFACES: ThemeToken[] = [
  "surface",
  "surface-container-lowest",
  "surface-container-low",
  "surface-container",
  "surface-container-high",
  "surface-container-highest",
];

/** The pairs the mapper keeps readable: [foreground, background, minimum contrast]. */
const GUARANTEES: [ThemeToken, ThemeToken, number][] = [
  ...SURFACES.flatMap((surface): [ThemeToken, ThemeToken, number][] => [
    ["on-surface", surface, 4.5],
    ["on-surface-variant", surface, 4.5],
  ]),
  ["on-background", "background", 4.5],
  ["inverse-on-surface", "inverse-surface", 4.5],
  ["primary", "surface", 4.5],
  ["secondary", "surface", 3],
  ["error", "surface", 3],
  ["outline", "surface", 3],
  ["on-primary", "primary", 4.5],
  ["on-primary-container", "primary-container", 4.5],
  ["on-secondary", "secondary", 4.5],
  ["on-secondary-container", "secondary-container", 4.5],
  ["on-tertiary", "tertiary", 4.5],
  ["on-tertiary-container", "tertiary-container", 4.5],
  ["on-error", "error", 4.5],
  ["on-error-container", "error-container", 4.5],
];

function expectGuarantees(tokens: ThemeTokens, dark: boolean): void {
  expect(Object.keys(tokens).sort()).toEqual([...THEME_TOKENS].sort());
  for (const [token, value] of Object.entries(tokens)) expect(value, token).toMatch(/^#[0-9a-f]{6}$/);
  for (const [foreground, background, ratio] of GUARANTEES) {
    // A hair of slack for floating point: both sides compute the same 8-bit colors.
    expect(contrast(tokens[foreground], tokens[background]), `${foreground} on ${background}`).toBeGreaterThanOrEqual(
      ratio - 1e-9,
    );
  }
  expect(luminance(tokens["on-surface"]) > luminance(tokens.surface)).toBe(dark);
}

// The colors that matter of Firefox's built-in Alpenglow, as `theme.getCurrent()`
// reports them: its `theme` colors, also while its `dark_theme` shows (bug
// 1542044). Its menus are dark on a light toolbar, which is translucent.
const ALPENGLOW = {
  colors: {
    frame: "#ffc999",
    toolbar: "hsla(0, 0%, 100%, .76)",
    toolbar_text: "hsla(258, 66%, 48%, 1)",
    icons_attention: "hsla(180, 100%, 32%, 1)",
    toolbar_field_border: "transparent",
    toolbar_field_border_focus: "hsla(265, 100%, 72%, 1)",
    bookmark_text: "hsla(261, 53%, 15%, 1)",
    tab_line: "hsla(265, 100%, 72%, 1)",
    popup: "hsla(254, 46%, 21%, 1)",
    popup_text: "hsla(255, 100%, 94%, 1)",
    popup_border: "hsla(255, 100%, 94%, .32)",
    popup_highlight: "hsla(255, 100%, 94%, .12)",
    popup_highlight_text: "hsla(0, 0%, 100%, 1)",
  },
  images: {},
  properties: {},
};

// Alpenglow's colors on light menus: a translucent selection in its accent.
const ALPENGLOW_LIKE_LIGHT = {
  colors: {
    frame: "#ffc999",
    toolbar: "hsla(0, 0%, 100%, .76)",
    toolbar_text: "hsla(258, 66%, 48%, 1)",
    tab_line: "hsla(265, 100%, 72%, 1)",
    popup: "#fbfbfe",
    popup_text: "#20123a",
    popup_border: "rgba(32, 18, 58, 0.24)",
    popup_highlight: "hsla(265, 100%, 72%, .32)",
    popup_highlight_text: "#20123a",
  },
  images: {},
  properties: {},
};

// What Firefox Color (color.firefox.com) exports: `rgb()` strings.
const FIREFOX_COLOR = {
  colors: {
    toolbar: "rgb(36, 50, 46)",
    toolbar_text: "rgb(226, 240, 233)",
    frame: "rgb(20, 28, 26)",
    tab_background_text: "rgb(200, 214, 207)",
    toolbar_field: "rgb(20, 28, 26)",
    toolbar_field_text: "rgb(226, 240, 233)",
    tab_line: "rgb(78, 205, 160)",
    popup: "rgb(30, 41, 38)",
    popup_text: "rgb(226, 240, 233)",
    popup_border: "rgb(61, 79, 73)",
    popup_highlight: "rgb(52, 84, 72)",
    popup_highlight_text: "rgb(255, 255, 255)",
  },
  images: { additional_backgrounds: [] },
  properties: {},
};

const FRAME_ONLY = { colors: { frame: "#3a2b4c", tab_background_text: "#ffffff" } };

const TOOLBAR_ONLY = {
  colors: { frame: "#ffd8b1", toolbar: "#fff4e6", bookmark_text: "#4a2b0f", tab_line: "#e66000" },
};

const LOW_CONTRAST = { colors: { popup: "#777777", popup_text: "#9a9a9a", tab_line: "#0060df" } };

const GREY_ACCENT = { colors: { popup: "#ffffff", popup_text: "#15141a", tab_line: "#808080" } };

const ARRAYS_AND_SHORTHAND = {
  colors: { popup: [250, 250, 250], popup_text: "#123", tab_line: [0, 96, 223], popup_highlight: "#0060df33" },
};

// Mid-tone and saturated surfaces, on which a scheme's own tones fall short.
const MID_GREY_DARK = { colors: { popup: "#6e6e6e", popup_text: "#ffffff", tab_line: "#0060df" } };
const MID_GREY_LIGHT = { colors: { popup: "#9a9a9a", popup_text: "#000000", tab_line: "#ffd567" } };
const SATURATED = {
  colors: {
    popup: "#c80000",
    popup_text: "#ffffff",
    popup_highlight: "#ff4f5e",
    popup_highlight_text: "#ffffff",
    tab_line: "#ffe900",
  },
};

const FIXTURES: Record<string, unknown> = {
  ALPENGLOW,
  ALPENGLOW_LIKE_LIGHT,
  FIREFOX_COLOR,
  FRAME_ONLY,
  TOOLBAR_ONLY,
  LOW_CONTRAST,
  GREY_ACCENT,
  ARRAYS_AND_SHORTHAND,
  MID_GREY_DARK,
  MID_GREY_LIGHT,
  SATURATED,
};

describe("parseThemeColor", () => {
  it.each([
    ["#fff", { r: 255, g: 255, b: 255, a: 1 }],
    ["#123", { r: 17, g: 34, b: 51, a: 1 }],
    ["#1230", { r: 17, g: 34, b: 51, a: 0 }],
    ["#AbCdEf", { r: 171, g: 205, b: 239, a: 1 }],
    ["#11223380", { r: 17, g: 34, b: 51, a: 128 / 255 }],
    ["rgb(1, 2, 3)", { r: 1, g: 2, b: 3, a: 1 }],
    ["rgba(1, 2, 3, 0.5)", { r: 1, g: 2, b: 3, a: 0.5 }],
    ["rgba(0,0,0,.4)", { r: 0, g: 0, b: 0, a: 0.4 }],
    ["rgb(1 2 3 / 50%)", { r: 1, g: 2, b: 3, a: 0.5 }],
    ["rgb(100%, 0%, 50%)", { r: 255, g: 0, b: 128, a: 1 }],
    ["rgb(300, -4, 12.6)", { r: 255, g: 0, b: 13, a: 1 }],
    ["hsl(0, 100%, 50%)", { r: 255, g: 0, b: 0, a: 1 }],
    ["hsla(120, 100%, 25%, .5)", { r: 0, g: 128, b: 0, a: 0.5 }],
    ["hsl(240deg 100% 50% / 0.25)", { r: 0, g: 0, b: 255, a: 0.25 }],
    ["hsla(254, 46%, 21%, 1)", { r: 40, g: 29, b: 78, a: 1 }],
    [" White ", { r: 255, g: 255, b: 255, a: 1 }],
    ["black", { r: 0, g: 0, b: 0, a: 1 }],
    ["transparent", { r: 0, g: 0, b: 0, a: 0 }],
    [[255, 0, 0], { r: 255, g: 0, b: 0, a: 1 }],
    [[0, 0, 255, 0.5], { r: 0, g: 0, b: 255, a: 0.5 }],
  ])("parses %j", (value, color) => {
    const parsed = parseThemeColor(value);
    expect(parsed).not.toBeNull();
    expect(parsed!.r).toBe(color.r);
    expect(parsed!.g).toBe(color.g);
    expect(parsed!.b).toBe(color.b);
    expect(parsed!.a).toBeCloseTo(color.a, 6);
  });

  it.each([
    "red",
    "currentcolor",
    "constructor",
    "#12",
    "#12345",
    "#ggg",
    "rgb(1, 2)",
    "rgb(1, 2, 3, 4, 5)",
    "rgb(1, 2, 3,)",
    "rgb(1 2 3 / 4 / 5)",
    "rgb(1deg, 2, 3)",
    "hsl(10%, 20%, 30%)",
    "color(srgb 1 0 0)",
    "",
    42,
    null,
    undefined,
    [1, 2],
    [1, 2, "3"],
    [1, 2, Number.NaN],
    { r: 1, g: 2, b: 3 },
  ])("rejects %j", (value) => {
    expect(parseThemeColor(value)).toBeNull();
  });
});

/** The tokens of [theme], which must have them, after checking their scheme. */
function tokensOf(theme: unknown, dark: boolean): ThemeTokens {
  const scheme = themeTokens(theme);
  expect(scheme).not.toBeNull();
  expect(scheme!.dark).toBe(dark);
  return scheme!.tokens;
}

describe("themeTokens", () => {
  it("has no tokens for a theme without colors", () => {
    // Firefox 146+ for its built-in System, Light and Dark themes.
    expect(themeTokens({ colors: null, images: null, properties: null })).toBeNull();
    expect(themeTokens({})).toBeNull();
    expect(themeTokens(undefined)).toBeNull();
    expect(themeTokens("theme")).toBeNull();
    expect(themeTokens({ colors: {} })).toBeNull();
  });

  it("takes Alpenglow's dark menus, as a dark theme", () => {
    const tokens = tokensOf(ALPENGLOW, true);
    expect(tokens.surface).toBe("#281d4e");
    expect(tokens.background).toBe("#281d4e");
    expect(tokens["on-surface"]).toBe("#e8e0ff");
    expect(tokens["on-background"]).toBe("#e8e0ff");
    // The translucent selection and border, as the browser paints them on the menu.
    expect(tokens["secondary-container"]).toBe("#3f3463");
    expect(tokens["on-secondary-container"]).toBe("#ffffff");
    expect(tokens["outline-variant"]).toBe("#655b87");
  });

  it("takes light menus with the theme's accent", () => {
    const tokens = tokensOf(ALPENGLOW_LIKE_LIGHT, false);
    expect(tokens.surface).toBe("#fbfbfe");
    expect(tokens["on-surface"]).toBe("#20123a");
    expect(tokens["secondary-container"]).toBe("#e2cffe");
    expect(tokens["on-secondary-container"]).toBe("#20123a");
    expect(tokens["outline-variant"]).toBe("#c6c3cf");
    // Fidelity keeps the accent's hue in the primary container.
    expect(hueDistance(hue(tokens["primary-container"]), hue("#ac70ff"))).toBeLessThan(5);
  });

  it("takes a Firefox Color theme", () => {
    const tokens = tokensOf(FIREFOX_COLOR, true);
    expect(tokens.surface).toBe("#1e2926");
    expect(tokens["on-surface"]).toBe("#e2f0e9");
    expect(tokens["inverse-surface"]).toBe("#e2f0e9");
    expect(tokens["inverse-on-surface"]).toBe("#1e2926");
    expect(tokens["surface-tint"]).toBe("#e2f0e9");
    expect(tokens["secondary-container"]).toBe("#345448");
    expect(tokens["on-secondary-container"]).toBe("#ffffff");
    expect(tokens["outline-variant"]).toBe("#3d4f49");
  });

  it("has no tokens for a theme with only a frame", () => {
    expect(themeTokens(FRAME_ONLY)).toBeNull();
  });

  it("falls back to the toolbar, with the bookmarks' text", () => {
    const tokens = tokensOf(TOOLBAR_ONLY, false);
    expect(tokens.surface).toBe("#fff4e6");
    expect(tokens["on-surface"]).toBe("#4a2b0f");
    // No menu border: a divider of Keyguard's contrast.
    expect(contrast(tokens["outline-variant"], tokens.surface)).toBeGreaterThanOrEqual(1.5);
    expect(contrast(tokens["outline-variant"], tokens.surface)).toBeLessThanOrEqual(2.1);
  });

  it("prefers the toolbar's text to the bookmarks'", () => {
    const tokens = tokensOf({ colors: { ...TOOLBAR_ONLY.colors, toolbar_text: "#000000" } }, false);
    expect(tokens["on-surface"]).toBe("#000000");
  });

  it("falls back to the toolbar when the menus are incomplete or translucent", () => {
    const toolbar = { toolbar: "#eeeeee", toolbar_text: "#111111" };
    expect(tokensOf({ colors: { ...toolbar, popup: "#ffffff" } }, false).surface).toBe("#eeeeee");
    expect(
      tokensOf({ colors: { ...toolbar, popup: "rgba(255, 255, 255, 0.9)", popup_text: "#000000" } }, false).surface,
    ).toBe("#eeeeee");
    expect(
      tokensOf({ colors: { ...toolbar, popup: "#ffffff", popup_text: "rgba(0, 0, 0, 0.9)" } }, false).surface,
    ).toBe("#eeeeee");
  });

  it("gives the scheme of the colors reported, which for a dark_theme are its light ones", () => {
    // Firefox reports the `theme` colors while the `dark_theme` shows: the
    // pages, which know their own scheme, decide (src/lib/ui/storedTheme.ts).
    expect(themeTokens(ALPENGLOW_LIKE_LIGHT)!.dark).toBe(false);
    expect(themeTokens(TOOLBAR_ONLY)!.dark).toBe(false);
    // A light theme may have dark menus, as Alpenglow's `theme` does.
    expect(themeTokens(ALPENGLOW)!.dark).toBe(true);
    expect(themeTokens(FIREFOX_COLOR)!.dark).toBe(true);
  });

  it("has no tokens when the text can't be read on the surface", () => {
    expect(themeTokens(LOW_CONTRAST)).toBeNull();
  });

  it("builds a grey scheme on a grey accent", () => {
    const tokens = tokensOf(GREY_ACCENT, false);
    expect(tokens.surface).toBe("#ffffff");
    expect(tokens["on-surface"]).toBe("#15141a");
    for (const token of [
      "primary",
      "primary-container",
      "secondary",
      "secondary-container",
      "tertiary",
      "tertiary-container",
    ] as const) {
      expect(isGrey(tokens[token]), `${token} ${tokens[token]}`).toBe(true);
    }
  });

  it("reads arrays and hex shorthand", () => {
    const tokens = tokensOf(ARRAYS_AND_SHORTHAND, false);
    expect(tokens.surface).toBe("#fafafa");
    expect(tokens["on-surface"]).toBe("#112233");
  });

  it("takes the first accent, skipping a transparent one", () => {
    const colors = { popup: "#ffffff", popup_text: "#15141a" };
    const red = tokensOf(
      { colors: { ...colors, tab_line: "transparent", toolbar_field_border_focus: "#d70022", icons_attention: "#00b3f4" } },
      false,
    );
    expect(hueDistance(hue(red["primary-container"]), hue("#d70022"))).toBeLessThan(5);
    const blue = tokensOf({ colors: { ...colors, icons_attention: "#00b3f4" } }, false);
    expect(hueDistance(hue(blue["primary-container"]), hue("#00b3f4"))).toBeLessThan(5);
  });

  it("takes a colorful selection as the accent, and Keyguard's otherwise", () => {
    const colors = { popup: "#ffffff", popup_text: "#15141a", popup_highlight_text: "#ffffff" };
    const selection = tokensOf({ colors: { ...colors, popup_highlight: "#0a84ff" } }, false);
    expect(hueDistance(hue(selection["primary-container"]), hue("#0a84ff"))).toBeLessThan(5);
    const brand = tokensOf({ colors: { ...colors, popup_highlight: "#e0e0e0" } }, false);
    expect(hueDistance(hue(brand["primary-container"]), hue("#219bcc"))).toBeLessThan(5);
    const none = tokensOf({ colors: { popup: "#ffffff", popup_text: "#15141a" } }, false);
    expect(hueDistance(hue(none["primary-container"]), hue("#219bcc"))).toBeLessThan(5);
  });

  it("keeps the scheme's selection when the theme's can't be read or seen", () => {
    const colors = { popup: "#ffffff", popup_text: "#15141a", tab_line: "#0060df" };
    // White on this blue is 3.7:1.
    const unreadable = tokensOf(
      { colors: { ...colors, popup_highlight: "#0a84ff", popup_highlight_text: "#ffffff" } },
      false,
    );
    expect(unreadable["secondary-container"]).not.toBe("#0a84ff");
    const invisible = tokensOf(
      { colors: { ...colors, popup_highlight: "transparent", popup_highlight_text: "#15141a" } },
      false,
    );
    expect(invisible["secondary-container"]).not.toBe(invisible.surface);
  });

  it("draws its own divider when the menus' border is the surface", () => {
    const tokens = tokensOf(
      { colors: { popup: "#ffffff", popup_text: "#15141a", popup_border: "#ffffff", tab_line: "#0060df" } },
      false,
    );
    expect(contrast(tokens["outline-variant"], tokens.surface)).toBeGreaterThanOrEqual(1.5);
    expect(contrast(tokens["outline-variant"], tokens.surface)).toBeLessThanOrEqual(2.1);
  });

  it("keeps Keyguard's error colors", () => {
    const light = tokensOf({ colors: { popup: "#ffffff", popup_text: "#000000" } }, false);
    const dark = tokensOf({ colors: { popup: "#141616", popup_text: "#e6e0e9" } }, true);
    for (const token of ["error", "error-container", "on-error-container"] as const) {
      expect(light[token], token).toBe(LIGHT_CSS.get(token));
      expect(dark[token], token).toBe(DARK_CSS.get(token));
    }
  });
});

describe("themeTokens contrast", () => {
  for (const [name, theme] of Object.entries(FIXTURES)) {
    it(name, () => {
      const scheme: ThemeScheme | null = themeTokens(theme);
      if (scheme !== null) expectGuarantees(scheme.tokens, scheme.dark);
    });
  }

  it("has tokens for the fixtures meant to have them, of their scheme", () => {
    const produced = Object.entries(FIXTURES).flatMap(([name, theme]) => {
      const scheme = themeTokens(theme);
      return scheme === null ? [] : [`${name}:${scheme.dark ? "dark" : "light"}`];
    });
    expect(produced.sort()).toEqual(
      [
        "ALPENGLOW:dark",
        "ALPENGLOW_LIKE_LIGHT:light",
        "ARRAYS_AND_SHORTHAND:light",
        "FIREFOX_COLOR:dark",
        "GREY_ACCENT:light",
        "MID_GREY_DARK:dark",
        "MID_GREY_LIGHT:light",
        "SATURATED:dark",
        "TOOLBAR_ONLY:light",
      ].sort(),
    );
  });
});

describe("THEME_TOKENS", () => {
  it("is every color token of theme.css, in both schemes", () => {
    expect([...LIGHT_CSS.keys()].sort()).toEqual([...THEME_TOKENS].sort());
    expect([...DARK_CSS.keys()].sort()).toEqual([...THEME_TOKENS].sort());
  });
});
