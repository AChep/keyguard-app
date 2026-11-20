// Firefox: the colors of the user's theme for the extension's pages. The
// popup can read the theme itself, but the inline menu and the save bar, in
// frames of web pages, have no `theme` API. So the background reads it, maps
// it to tokens (../ui/browserTheme.ts) and stores them for all of them
// (../ui/storedTheme.ts).
//
// It reads the theme on every start (each popup or inline menu wakes it) and
// when the theme changes; per-window themes that other extensions set are
// ignored, as the theme of the last focused window is read. Every write
// reaches every content script and page, so it writes only what changed.
import {
  parseStoredTheme,
  THEME_KEY,
  toStoredTheme,
  type StoredTheme,
  type ThemeScheme,
} from "../ui/storedTheme";
import { SerialQueue } from "../util/serial";

/** The parts of Firefox's `theme` API the sync uses. */
export interface FirefoxThemeApi {
  /** The theme of a window, or of the last focused one. */
  getCurrent(windowId?: number): Promise<unknown>;
  onUpdated: { addListener(callback: (update: unknown) => void): void };
}

/** The part of `storage.local` the sync uses. */
export interface ThemeArea {
  get(keys: string): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
  remove(keys: string): Promise<void>;
}

/** The part of `localStorage` the sync uses. */
export type ThemeMirror = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export interface ThemeSyncDeps {
  /** Firefox's `theme`: missing on other browsers, and on Firefox for Android. */
  theme: FirefoxThemeApi | undefined;
  /** The tokens of a theme, see ../ui/browserTheme.ts. */
  themeTokens(theme: unknown): ThemeScheme | null | Promise<ThemeScheme | null>;
  area: ThemeArea;
  /**
   * The extension origin's `localStorage`, which the pages read before their
   * first paint. It may throw: storage can be off.
   */
  mirror(): ThemeMirror;
}

export class ThemeSync {
  // One sync at a time: their reads and writes would interleave.
  private readonly queue = new SerialQueue();

  constructor(private readonly deps: ThemeSyncDeps) {}

  /** Follows the theme's changes and syncs now. Call it once, as the background starts. */
  start(): void {
    this.deps.theme?.onUpdated.addListener(() => void this.sync());
    void this.sync();
  }

  /** Stores the tokens of the current theme, or removes them. Never rejects. */
  sync(): Promise<void> {
    return this.queue.run(async () => {
      try {
        await this.syncNow();
      } catch (error) {
        console.warn("Keyguard: the browser's theme couldn't be read", error);
      }
    });
  }

  private async syncNow(): Promise<void> {
    const { theme, area } = this.deps;
    // Without the API, no theme: Keyguard's colors.
    const scheme = theme === undefined ? null : await this.deps.themeTokens(await theme.getCurrent());
    const next: StoredTheme | null = scheme === null ? null : toStoredTheme(scheme);
    const json = next === null ? null : JSON.stringify(next);

    const stored = (await area.get(THEME_KEY))[THEME_KEY];
    if (json === null) {
      if (stored !== undefined) await area.remove(THEME_KEY);
    } else if (stored === undefined || JSON.stringify(parseStoredTheme(stored)) !== json) {
      await area.set({ [THEME_KEY]: next });
    }
    this.writeMirror(json);
  }

  private writeMirror(json: string | null) {
    try {
      const mirror = this.deps.mirror();
      if (mirror.getItem(THEME_KEY) === json) return;
      if (json === null) mirror.removeItem(THEME_KEY);
      else mirror.setItem(THEME_KEY, json);
    } catch {
      // No localStorage: pages wait for storage.local.
    }
  }
}
