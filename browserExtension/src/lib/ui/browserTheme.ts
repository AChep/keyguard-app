// The user's Firefox theme as the color tokens of src/lib/ui/theme.css, so the
// popup, the inline menu and the save bar look like the browser's own menus.
// Pure: the background reads `theme.getCurrent()` and stores the tokens, or
// none to keep Keyguard's colors (../background/theme.ts). Only the background
// loads this module and its color library; pages and content scripts use
// ./storedTheme.ts.
//
// Firefox returns the colors a theme declares, as its author wrote them, and
// none for its built-in System, Light and Dark themes (bug 1435216). For a
// theme with a `dark_theme`, it returns the light colors even while the dark
// ones show (bug 1542044). So the tokens are of the theme's own scheme, and
// the pages keep Keyguard's colors where a light theme meets a dark page
// (see `themeApplies` in ./storedTheme.ts).
//
// The theme gives a surface and a text color and, from its menus, a border
// and a selection. Material 3's other roles come from a scheme of the theme's
// accent. The scheme only keeps their contrast on its own surfaces, so each
// one is checked again on the theme's.
import {
  Contrast,
  Hct,
  SchemeFidelity,
  SchemeMonochrome,
  argbFromHex,
  argbFromRgb,
  blueFromArgb,
  greenFromArgb,
  hexFromArgb,
  lstarFromArgb,
  redFromArgb,
  xyzFromArgb,
  type DynamicScheme,
} from "@material/material-color-utilities";
import type { ThemeScheme, ThemeTokens } from "./storedTheme";

/** A color of a theme: channels 0 to 255, alpha 0 to 1. */
export interface ParsedColor {
  r: number;
  g: number;
  b: number;
  a: number;
}

/** WCAG's minimum contrast of text. */
const TEXT_CONTRAST = 4.5;
/** WCAG's minimum contrast of a control's boundary or a focus ring. */
const NON_TEXT_CONTRAST = 3;
/** The contrast of Keyguard's outline-variant: 1.7:1 on its light surface, 2:1 on its dark one. */
const DIVIDER_CONTRAST = 1.75;
/** Below this, a theme's border can't be told from its surface, and draws no divider. */
const VISIBLE_BORDER_CONTRAST = 1.1;
/** HCT chroma below which an accent is grey: there's no hue to build a scheme on. */
const MIN_ACCENT_CHROMA = 10;

/** Keyguard's brand color, the seed of theme.css, for a theme without an accent. */
const BRAND_SEED = argbFromHex("#219bcc");

/**
 * KeyguardTheme's error roles, as theme.css has them: errors look the same
 * under every theme.
 */
const KEYGUARD_ERRORS = {
  light: {
    error: argbFromHex("#cc0808"),
    onError: argbFromHex("#ffffff"),
    errorContainer: argbFromHex("#dfb6be"),
    onErrorContainer: argbFromHex("#3c4850"),
  },
  dark: {
    error: argbFromHex("#db3d3d"),
    onError: argbFromHex("#690005"),
    errorContainer: argbFromHex("#633a40"),
    onErrorContainer: argbFromHex("#bbc8d2"),
  },
};

const NAMED_COLORS = new Map<string, ParsedColor>([
  ["white", { r: 255, g: 255, b: 255, a: 1 }],
  ["black", { r: 0, g: 0, b: 0, a: 1 }],
  ["transparent", { r: 0, g: 0, b: 0, a: 0 }],
]);

const COMPONENT = /^([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?)(%|deg)?$/;

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** A number of a color function with its unit: "", "%" or "deg". */
function component(text: string | undefined): { value: number; unit: string } | null {
  const match = text === undefined ? null : COMPONENT.exec(text);
  if (match === null) return null;
  const value = Number(match[1]);
  return Number.isFinite(value) ? { value, unit: match[2] ?? "" } : null;
}

/** A channel of `rgb()`: 0 to 255, or a percentage of 255. */
function rgbChannel(text: string | undefined): number | null {
  const it = component(text);
  if (it === null || it.unit === "deg") return null;
  return Math.round(clamp(it.unit === "%" ? (it.value / 100) * 255 : it.value, 0, 255));
}

/** The alpha of a color function, 1 when it has none. */
function alphaChannel(text: string | undefined): number | null {
  if (text === undefined) return 1;
  const it = component(text);
  if (it === null || it.unit === "deg") return null;
  return clamp(it.unit === "%" ? it.value / 100 : it.value, 0, 1);
}

/** The hue of `hsl()`, in degrees. */
function hue(text: string | undefined): number | null {
  const it = component(text);
  if (it === null || it.unit === "%") return null;
  return ((it.value % 360) + 360) % 360;
}

/** The saturation or lightness of `hsl()`, 0 to 1. Modern syntax may leave out the `%`. */
function fraction(text: string | undefined): number | null {
  const it = component(text);
  if (it === null || it.unit === "deg") return null;
  return clamp(it.value / 100, 0, 1);
}

/** CSS Color 4's `hslToRgb`: [h] in degrees, [s] and [l] 0 to 1; channels 0 to 255. */
function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  const channel = (n: number) => {
    const k = (n + h / 30) % 12;
    const value = l - s * Math.min(l, 1 - l) * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(value * 255);
  };
  return [channel(0), channel(8), channel(4)];
}

function parseHex(digits: string): ParsedColor | null {
  if (!/^(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/.test(digits)) return null;
  const short = digits.length <= 4;
  const byte = (index: number) =>
    parseInt(short ? digits.charAt(index).repeat(2) : digits.substring(index * 2, index * 2 + 2), 16);
  const alpha = digits.length === 4 || digits.length === 8 ? byte(3) / 255 : 1;
  return { r: byte(0), g: byte(1), b: byte(2), a: alpha };
}

/** `rgb()`, `rgba()`, `hsl()` or `hsla()`, with commas or with spaces and a `/` before the alpha. */
function parseFunction(name: string, body: string): ParsedColor | null {
  let parts: string[];
  let alphaText: string | undefined;
  if (body.includes(",")) {
    parts = body.split(",").map((it) => it.trim());
    if (parts.length === 4) alphaText = parts.pop();
  } else {
    const [channels = "", alpha, ...rest] = body.split("/");
    if (rest.length > 0) return null;
    parts = channels.trim().split(/\s+/);
    alphaText = alpha?.trim();
  }
  const a = alphaChannel(alphaText);
  if (parts.length !== 3 || a === null) return null;
  if (name.startsWith("rgb")) {
    const [r, g, b] = parts.map(rgbChannel);
    if (r == null || g == null || b == null) return null;
    return { r, g, b, a };
  }
  const h = hue(parts[0]);
  const s = fraction(parts[1]);
  const l = fraction(parts[2]);
  if (h === null || s === null || l === null) return null;
  const [r, g, b] = hslToRgb(h, s, l);
  return { r, g, b, a };
}

/** Firefox's legacy `[r, g, b]`, or `[r, g, b, a]`. */
function parseArray(value: unknown[]): ParsedColor | null {
  if (value.length !== 3 && value.length !== 4) return null;
  if (!value.every((it) => typeof it === "number" && Number.isFinite(it))) return null;
  const [r = 0, g = 0, b = 0, a = 1] = value as number[];
  return {
    r: Math.round(clamp(r, 0, 255)),
    g: Math.round(clamp(g, 0, 255)),
    b: Math.round(clamp(b, 0, 255)),
    a: clamp(a, 0, 1),
  };
}

/**
 * A color of a theme, as its author wrote it: hex, `rgb()`, `hsl()` (with or
 * without `a`), `white`, `black`, `transparent` or an array. Other named
 * colors and color spaces are null, as if the theme left the color out.
 */
export function parseThemeColor(value: unknown): ParsedColor | null {
  if (Array.isArray(value)) return parseArray(value);
  if (typeof value !== "string") return null;
  const text = value.trim().toLowerCase();
  if (text.startsWith("#")) return parseHex(text.slice(1));
  const named = NAMED_COLORS.get(text);
  if (named !== undefined) return { ...named };
  const match = /^(rgba?|hsla?)\((.*)\)$/.exec(text);
  return match === null ? null : parseFunction(match[1]!, match[2]!);
}

/** The relative luminance of [argb], 0 to 100. */
function luminance(argb: number): number {
  return xyzFromArgb(argb)[1]!;
}

/** WCAG's contrast ratio of two colors, 1 to 21. */
function contrast(a: number, b: number): number {
  return Contrast.ratioOfYs(luminance(a), luminance(b));
}

/** [color] painted over the opaque [background], as the browser draws a translucent theme color. */
function over(color: ParsedColor, background: number): number {
  const mix = (top: number, bottom: number) => Math.round(top * color.a + bottom * (1 - color.a));
  return argbFromRgb(
    mix(color.r, redFromArgb(background)),
    mix(color.g, greenFromArgb(background)),
    mix(color.b, blueFromArgb(background)),
  );
}

/**
 * [value] as an opaque color, or null when it doesn't parse or is
 * translucent: a surface or a text color would show what's under it, which
 * is different in each place the browser draws it.
 */
function opaque(value: unknown): number | null {
  const color = parseThemeColor(value);
  return color === null || color.a < 1 ? null : argbFromRgb(color.r, color.g, color.b);
}

function withTone(color: Hct, tone: number): number {
  return Hct.from(color.hue, color.chroma, clamp(tone, 0, 100)).toInt();
}

/**
 * The color of [color]'s hue and chroma that has [ratio] against
 * [background], at the tone nearest the background's on the lighter or the
 * darker side. Null when that side has none.
 */
function toneFor(color: Hct, background: number, ratio: number, lighter: boolean): number | null {
  const backgroundTone = lstarFromArgb(background);
  const end = lighter ? 100 : 0;
  const start = lighter ? Contrast.lighter(backgroundTone, ratio) : Contrast.darker(backgroundTone, ratio);
  // `Contrast` gives up near the ends of the range, where white or black may
  // still do. Its tone is also for an exact color: the 8-bit one may need a
  // little more.
  let tone = start < 0 ? end : start;
  for (;;) {
    const argb = withTone(color, tone);
    if (contrast(argb, background) >= ratio) return argb;
    if (tone === end) return null;
    tone = lighter ? Math.min(end, tone + 0.5) : Math.max(end, tone - 0.5);
  }
}

/**
 * [color], or, when it lacks [ratio] against [background], the color of its
 * hue and chroma at the nearest tone away from the background that has it.
 * Crosses to the other side of the background when its own side can't.
 */
function ensureContrast(color: number, background: number, ratio: number): number {
  if (contrast(color, background) >= ratio) return color;
  const hct = Hct.fromInt(color);
  const lighter = hct.tone >= lstarFromArgb(background);
  return (
    toneFor(hct, background, ratio, lighter) ??
    toneFor(hct, background, ratio, !lighter) ??
    // Out of reach for ratios up to 4.5:1: white or black has at least 4.58:1 on anything.
    (contrast(0xffffffff, background) >= contrast(0xff000000, background) ? 0xffffffff : 0xff000000)
  );
}

type ThemeColors = (key: string) => unknown;

/** The colors of a `theme.getCurrent()` result, or null when it has none. */
function colorsOf(theme: unknown): ThemeColors | null {
  if (typeof theme !== "object" || theme === null) return null;
  const colors = (theme as { colors?: unknown }).colors;
  if (typeof colors !== "object" || colors === null) return null;
  return (key) => (Object.hasOwn(colors, key) ? (colors as Record<string, unknown>)[key] : undefined);
}

/** The colors the extension takes as they are. */
interface Base {
  surface: number;
  text: number;
  /** The menus' border and selection, which only the popup colors have. */
  border: ParsedColor | null;
  highlight: ParsedColor | null;
  highlightText: ParsedColor | null;
}

/**
 * The surface and the text: Firefox's menus and panels, the nearest thing to
 * the extension's popup, else its toolbar.
 */
function pickBase(colors: ThemeColors): Base | null {
  const popup = opaque(colors("popup"));
  const popupText = opaque(colors("popup_text"));
  if (popup !== null && popupText !== null) {
    return {
      surface: popup,
      text: popupText,
      border: parseThemeColor(colors("popup_border")),
      highlight: parseThemeColor(colors("popup_highlight")),
      highlightText: parseThemeColor(colors("popup_highlight_text")),
    };
  }
  const toolbar = opaque(colors("toolbar"));
  const toolbarText = opaque(colors("toolbar_text")) ?? opaque(colors("bookmark_text"));
  if (toolbar !== null && toolbarText !== null) {
    return { surface: toolbar, text: toolbarText, border: null, highlight: null, highlightText: null };
  }
  return null;
}

/**
 * The theme's accent: the selected tab's line, the focused address bar's
 * border or the attention dot, else the menus' selection when it has a hue,
 * else Keyguard's brand color.
 */
function accentOf(colors: ThemeColors): number {
  for (const key of ["tab_line", "toolbar_field_border_focus", "icons_attention"]) {
    const color = parseThemeColor(colors(key));
    // Alpha only fades the accent in; a transparent one is no accent at all.
    if (color !== null && color.a > 0) return argbFromRgb(color.r, color.g, color.b);
  }
  const highlight = parseThemeColor(colors("popup_highlight"));
  if (highlight !== null && highlight.a > 0) {
    const argb = argbFromRgb(highlight.r, highlight.g, highlight.b);
    if (Hct.fromInt(argb).chroma >= MIN_ACCENT_CHROMA) return argb;
  }
  return BRAND_SEED;
}

/**
 * The menus' selection painted on the surface, as the secondary container:
 * the look of a selected row in the browser's own menus. Null when it can't
 * be seen on the surface or its text can't be read on it.
 */
function selectionOf(base: Base): { container: number; text: number } | null {
  if (base.highlight === null || base.highlightText === null) return null;
  const container = over(base.highlight, base.surface);
  const text = over(base.highlightText, container);
  if (container === base.surface || contrast(text, container) < TEXT_CONTRAST) return null;
  return { container, text };
}

/**
 * The color tokens of a Firefox `theme.getCurrent()` result, in the theme's
 * own scheme: dark when its text is lighter than its surface. Null to keep
 * Keyguard's colors: when the theme has no usable surface and text, or when
 * the text can't be read on the surface.
 */
export function themeTokens(theme: unknown): ThemeScheme | null {
  const colors = colorsOf(theme);
  const base = colors === null ? null : pickBase(colors);
  if (colors === null || base === null) return null;
  const { surface, text } = base;
  // The theme's scheme, which the containers' steps of tone, the accent's
  // scheme and the error colors follow.
  const dark = luminance(text) > luminance(surface);
  if (contrast(text, surface) < TEXT_CONTRAST) return null;

  const surfaceHct = Hct.fromInt(surface);
  const textHct = Hct.fromInt(text);
  // Material 3's containers at its steps of tone from the surface, toward the
  // text in all but the lowest. The text stays readable on each: a container
  // too close to it moves back toward the surface.
  const container = (lightStep: number, darkStep: number) =>
    ensureContrast(withTone(surfaceHct, surfaceHct.tone + (dark ? darkStep : lightStep)), text, TEXT_CONTRAST);
  const containerLowest = container(2, -2);
  const containerLow = container(-2, 4);
  const containerMid = container(-4, 6);
  const containerHigh = container(-6, 11);
  const containerHighest = container(-8, 16);
  // Secondary text, a quarter of the way from the text to the surface as
  // Material 3's is. It must be readable on the highest container, the
  // nearest to it in tone, and so on every other one and the surface.
  const onSurfaceVariant = ensureContrast(
    withTone(textHct, textHct.tone + (surfaceHct.tone - textHct.tone) / 4),
    containerHighest,
    TEXT_CONTRAST,
  );
  // Material 3's outline sits about halfway between the surface and the text.
  const outline = ensureContrast(
    withTone(surfaceHct, (surfaceHct.tone + textHct.tone) / 2),
    surface,
    NON_TEXT_CONTRAST,
  );
  const border = base.border === null ? null : over(base.border, surface);
  const outlineVariant =
    border !== null && contrast(border, surface) >= VISIBLE_BORDER_CONTRAST
      ? border
      : (toneFor(surfaceHct, surface, DIVIDER_CONTRAST, dark) ?? outline);

  const seed = Hct.fromInt(accentOf(colors));
  // Fidelity keeps the theme's accent itself as the primary container. A grey
  // accent has no hue for it, and would get a tint the theme doesn't have.
  const scheme: DynamicScheme =
    seed.chroma < MIN_ACCENT_CHROMA ? new SchemeMonochrome(seed, dark, 0) : new SchemeFidelity(seed, dark, 0);
  // Links and text buttons are primary text on the surface; the focus ring is
  // secondary.
  const primary = ensureContrast(scheme.primary, surface, TEXT_CONTRAST);
  const secondary = ensureContrast(scheme.secondary, surface, NON_TEXT_CONTRAST);
  const selection = selectionOf(base);
  const secondaryContainer = selection?.container ?? scheme.secondaryContainer;
  const errors = dark ? KEYGUARD_ERRORS.dark : KEYGUARD_ERRORS.light;
  const error = ensureContrast(errors.error, surface, NON_TEXT_CONTRAST);
  const on = (color: number, background: number) => ensureContrast(color, background, TEXT_CONTRAST);

  const hex = hexFromArgb;
  const tokens: ThemeTokens = {
    primary: hex(primary),
    "on-primary": hex(on(scheme.onPrimary, primary)),
    "primary-container": hex(scheme.primaryContainer),
    "on-primary-container": hex(on(scheme.onPrimaryContainer, scheme.primaryContainer)),
    "inverse-primary": hex(scheme.inversePrimary),
    secondary: hex(secondary),
    "on-secondary": hex(on(scheme.onSecondary, secondary)),
    "secondary-container": hex(secondaryContainer),
    "on-secondary-container": hex(selection?.text ?? on(scheme.onSecondaryContainer, secondaryContainer)),
    tertiary: hex(scheme.tertiary),
    "on-tertiary": hex(on(scheme.onTertiary, scheme.tertiary)),
    "tertiary-container": hex(scheme.tertiaryContainer),
    "on-tertiary-container": hex(on(scheme.onTertiaryContainer, scheme.tertiaryContainer)),
    error: hex(error),
    "on-error": hex(on(errors.onError, error)),
    "error-container": hex(errors.errorContainer),
    "on-error-container": hex(on(errors.onErrorContainer, errors.errorContainer)),
    background: hex(surface),
    "on-background": hex(text),
    surface: hex(surface),
    "on-surface": hex(text),
    "surface-variant": hex(containerHighest),
    "on-surface-variant": hex(onSurfaceVariant),
    // Keyguard's state layers and tinted fills are overlays of the text color.
    "surface-tint": hex(text),
    "surface-container-lowest": hex(containerLowest),
    "surface-container-low": hex(containerLow),
    "surface-container": hex(containerMid),
    "surface-container-high": hex(containerHigh),
    "surface-container-highest": hex(containerHighest),
    "inverse-surface": hex(text),
    "inverse-on-surface": hex(surface),
    outline: hex(outline),
    "outline-variant": hex(outlineVariant),
    scrim: "#000000",
  };
  return { dark, tokens };
}
