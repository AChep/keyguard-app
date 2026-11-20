// The Firefox theme's color tokens as the background stores them (see
// ../background/theme.ts), and their use: the popup and the embedded pages
// (the inline menu, the save bar) set them over theme.css, and the content
// script paints the embedded pages' frames with them until the pages draw.
//
// Without the color library, which only the background loads
// (./browserTheme.ts): pages and content scripts stay small.
//
// What's read back is checked before it's used: content scripts can read
// storage.local, and so can whatever writes to it.
import type { ObservableStorageArea } from "../options/store";
import { isObject } from "../util/json";

/**
 * The color tokens of theme.css, without `--`. All of them, so that none of
 * Keyguard's colors is left next to the theme's.
 */
export const THEME_TOKENS = [
  "primary",
  "on-primary",
  "primary-container",
  "on-primary-container",
  "inverse-primary",
  "secondary",
  "on-secondary",
  "secondary-container",
  "on-secondary-container",
  "tertiary",
  "on-tertiary",
  "tertiary-container",
  "on-tertiary-container",
  "error",
  "on-error",
  "error-container",
  "on-error-container",
  "background",
  "on-background",
  "surface",
  "on-surface",
  "surface-variant",
  "on-surface-variant",
  "surface-tint",
  "surface-container-lowest",
  "surface-container-low",
  "surface-container",
  "surface-container-high",
  "surface-container-highest",
  "inverse-surface",
  "inverse-on-surface",
  "outline",
  "outline-variant",
  "scrim",
] as const;

export type ThemeToken = (typeof THEME_TOKENS)[number];

/** A color for each token, as `#rrggbb`. */
export type ThemeTokens = Record<ThemeToken, string>;

/** A theme's tokens, of its own scheme: dark when its text is lighter than its surface. */
export interface ThemeScheme {
  dark: boolean;
  tokens: ThemeTokens;
}

/** A [ThemeScheme] as stored, with the version of its shape. */
export interface StoredTheme extends ThemeScheme {
  v: 1;
}

/**
 * The key of the stored theme in storage.local, and of its copy in the
 * extension origin's localStorage, which pages read before their first paint.
 */
export const THEME_KEY = "browserTheme";

const COLOR = /^#[0-9a-f]{6}$/;

/** [scheme] as stored, with its tokens in THEME_TOKENS' order: the same theme is always the same JSON. */
export function toStoredTheme(scheme: ThemeScheme): StoredTheme {
  const tokens = Object.fromEntries(THEME_TOKENS.map((token) => [token, scheme.tokens[token]])) as ThemeTokens;
  return { v: 1, dark: scheme.dark, tokens };
}

function hasExactly(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const own = Object.keys(value);
  return own.length === keys.length && keys.every((key) => Object.hasOwn(value, key));
}

/**
 * A stored theme, or null when [value] is anything else: another version or
 * shape, a token missing or unknown, or a color other than `#rrggbb`.
 */
export function parseStoredTheme(value: unknown): StoredTheme | null {
  if (!isObject(value) || !hasExactly(value, ["v", "dark", "tokens"])) return null;
  const { v, dark, tokens } = value;
  if (v !== 1 || typeof dark !== "boolean" || !isObject(tokens) || !hasExactly(tokens, THEME_TOKENS)) return null;
  const valid = THEME_TOKENS.every((token) => {
    const color = tokens[token];
    return typeof color === "string" && COLOR.test(color);
  });
  return valid ? toStoredTheme({ dark, tokens: tokens as ThemeTokens }) : null;
}

/** The stored theme of the localStorage copy, or null when it's missing or malformed. */
export function parseThemeJson(json: string | null): StoredTheme | null {
  if (json === null) return null;
  try {
    return parseStoredTheme(JSON.parse(json));
  } catch {
    return null;
  }
}

/**
 * Whether [stored] applies to a page that prefers dark when [pageDark]. A
 * light theme doesn't apply to a dark page: Firefox reports a theme's light
 * colors while its `dark_theme` shows (bug 1542044), and a page may follow
 * the website appearance rather than the theme. A dark theme applies to
 * every page: Firefox reports the colors of the theme as they are, and some
 * light themes have dark menus.
 */
export function themeApplies(stored: StoredTheme | null, pageDark: boolean): stored is StoredTheme {
  return stored !== null && (stored.dark || !pageDark);
}

/**
 * Sets the tokens of [stored] on [root], over theme.css, with their scheme as
 * `color-scheme` for form controls and scroll bars; or removes them when it
 * doesn't apply (see [themeApplies]).
 */
export function applyTheme(root: HTMLElement, stored: StoredTheme | null, pageDark: boolean): void {
  const style = root.style;
  if (themeApplies(stored, pageDark)) {
    for (const token of THEME_TOKENS) style.setProperty(`--${token}`, stored.tokens[token]);
    style.setProperty("color-scheme", stored.dark ? "dark" : "light");
  } else {
    for (const token of THEME_TOKENS) style.removeProperty(`--${token}`);
    style.removeProperty("color-scheme");
  }
}

/** The color under an embedded page's frame, for a light and for a dark page; null for Keyguard's. */
export interface FrameColors {
  light: string | null;
  dark: string | null;
}

/** The embedded pages' frame color of [stored] (src/lib/ui/embedded.css), where it applies. */
export function frameColors(stored: StoredTheme | null): FrameColors {
  const color = (pageDark: boolean) => (themeApplies(stored, pageDark) ? stored.tokens["surface-container"] : null);
  return { light: color(false), dark: color(true) };
}

/**
 * Calls [listener] with the stored theme, or null, now and on every change;
 * returns a function that stops it.
 */
export function watchTheme(
  area: Pick<ObservableStorageArea, "get" | "onChanged">,
  listener: (stored: StoredTheme | null) => void,
): () => void {
  let stopped = false;
  // A change that comes first is newer than what the first read gets.
  let changed = false;
  const onChanged = (changes: Record<string, { newValue?: unknown }>) => {
    if (!(THEME_KEY in changes)) return;
    changed = true;
    listener(parseStoredTheme(changes[THEME_KEY]!.newValue));
  };
  area.onChanged.addListener(onChanged);
  area.get(THEME_KEY).then(
    (stored) => {
      if (!stopped && !changed) listener(parseStoredTheme(stored[THEME_KEY]));
    },
    () => undefined,
  );
  return () => {
    stopped = true;
    area.onChanged.removeListener(onChanged);
  };
}

/**
 * Keeps the theme on a page of [win]: at once from the localStorage copy,
 * before the page mounts, then as storage.local and the page's
 * `prefers-color-scheme` change. Returns a function that stops it.
 */
export function followTheme(win: Window, area: Pick<ObservableStorageArea, "get" | "onChanged">): () => void {
  const root = win.document.documentElement;
  const query = win.matchMedia("(prefers-color-scheme: dark)");
  let stored: StoredTheme | null;
  try {
    stored = parseThemeJson(win.localStorage.getItem(THEME_KEY));
  } catch {
    // No localStorage: storage.local brings the theme a moment later.
    stored = null;
  }
  const apply = () => applyTheme(root, stored, query.matches);
  apply();
  query.addEventListener("change", apply);
  const stop = watchTheme(area, (next) => {
    stored = next;
    apply();
  });
  return () => {
    stop();
    query.removeEventListener("change", apply);
  };
}
