// The icon of a page's site, which the popup, the inline menu and the save bar
// show for the logins that match the page. It's the browser's favicon of the
// tab: the extension never fetches a site's icon on its own.
import { sameSite, webUrl } from "./frames";

/**
 * Where site icons come from: Chromium's favicon cache, served from the
 * extension's origin at `_favicon/` with the `favicon` permission; the
 * `favIconUrl` of the tab on Firefox; or nowhere.
 */
export type SiteIconSource = "chromium" | "firefox" | "none";

export interface SiteIconEnv {
  source: SiteIconSource;
  /** `runtime.getURL`. */
  getURL(path: string): string;
}

/** The page whose logins get the icon, and its tab as the browser reports it. */
export interface SiteIconPage {
  /** The URL of the frame the logins match. */
  url: string;
  /** The URL of its tab, which is its top frame's. */
  tabUrl?: string;
  /** The tab's `favIconUrl`, on Firefox. */
  favIconUrl?: string;
}

/** The size, in pixels, of the icon Chromium serves. */
const CHROMIUM_ICON_SIZE = 32;

const DATA_IMAGE = /^data:image\//i;

/** The [SiteIconSource] of the browser: Firefox, or Chromium with the `favicon` permission. */
export function siteIconSource(env: { firefox: boolean; permissions: readonly string[] }): SiteIconSource {
  if (env.firefox) return "firefox";
  return env.permissions.includes("favicon") ? "chromium" : "none";
}

/**
 * The URL of the icon of [page]'s site, or `null` for none: the icon of the
 * tab's top page, for an http(s) frame of its site only. A frame of another
 * site, as a sign-in widget embedded in a page, gets none: the top page's
 * icon isn't its.
 *
 * - Chromium: the favicon of the tab's URL from the browser's cache, which is
 *   a generic globe for a page it has no icon for.
 * - Firefox: the tab's `favIconUrl`, when it's `https:` or a `data:image/`
 *   URL. Anything else, as `http:` or the browser's own icons, isn't shown.
 */
export function siteIconUrl(page: SiteIconPage, env: SiteIconEnv): string | null {
  const tab = webUrl(page.tabUrl);
  if (tab === null || !sameSite(page.url, tab.href)) return null;
  switch (env.source) {
    case "chromium": {
      const icon = new URL(env.getURL("/_favicon/"));
      icon.searchParams.set("pageUrl", tab.href);
      icon.searchParams.set("size", String(CHROMIUM_ICON_SIZE));
      return icon.href;
    }
    case "firefox":
      return firefoxIcon(page.favIconUrl);
    case "none":
      return null;
  }
}

function firefoxIcon(href: string | undefined): string | null {
  if (href === undefined) return null;
  if (DATA_IMAGE.test(href)) return href;
  const url = webUrl(href);
  return url?.protocol === "https:" ? url.href : null;
}
