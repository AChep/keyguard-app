// The extension's access to websites, its host permissions. Firefox treats
// them as optional in Manifest V3, and Chromium lets the user withhold them.
// Without access the content script doesn't run, so no inline menu or save bar.
// No browser imports: wxt.config.ts reads WEB_PAGES from here.
import { webUrl } from "../background/frames";

/** The pages the extension works on. */
export const WEB_PAGES: readonly string[] = ["http://*/*", "https://*/*"];

/** Every website, the access the manifest asks for. */
export const ALL_SITES = { origins: WEB_PAGES } as const;

/** The argument of `permissions.contains` and `permissions.request`. */
export interface PermissionSet {
  origins?: readonly string[];
  permissions?: readonly string[];
}

/** `permissions.onAdded` and `permissions.onRemoved`. */
export interface PermissionsEvent {
  addListener(callback: (permissions: PermissionSet) => void): void;
  removeListener(callback: (permissions: PermissionSet) => void): void;
}

/** The part of `browser` this needs. */
export interface SiteAccessApi {
  permissions: {
    contains(permissions: PermissionSet): Promise<boolean>;
    request(permissions: PermissionSet): Promise<boolean>;
    onAdded: PermissionsEvent;
    onRemoved: PermissionsEvent;
  };
}

/**
 * The match pattern of [url]'s site, or `null` when it isn't an http(s) URL.
 * Without the port: Firefox rejects patterns with one, and a pattern without
 * one matches every port.
 */
export function sitePattern(url: string | undefined): string | null {
  const parsed = webUrl(url);
  return parsed === null ? null : portless(parsed);
}

function portless(url: URL): string {
  return `${url.protocol}//${url.hostname}/*`;
}

/**
 * Whether the extension may run on [url]'s site, or on every website without
 * [url]. A page that isn't a website has no access to ask about. Access to
 * every website counts for each site: the browser matches the patterns.
 */
export async function hasSiteAccess(api: SiteAccessApi, url?: string): Promise<boolean> {
  if (url === undefined) return api.permissions.contains(ALL_SITES);
  const parsed = webUrl(url);
  if (parsed === null) return false;
  if (await api.permissions.contains({ origins: [portless(parsed)] })) return true;
  // Chromium's extension settings grant a site with its port, which the
  // pattern without one doesn't hold. Firefox may reject a pattern with one.
  if (parsed.port === "") return false;
  try {
    return await api.permissions.contains({ origins: [`${parsed.origin}/*`] });
  } catch {
    return false;
  }
}

/**
 * Calls [onChange] with [hasSiteAccess] of [url] now and each time the user
 * grants or withdraws a permission, in the browser's prompt or its extension
 * settings. An older check's late answer is dropped, a failed one ignored.
 * Returns the unsubscribe, after which nothing more is called.
 */
export function observeSiteAccess(
  api: SiteAccessApi,
  url: string | undefined,
  onChange: (granted: boolean) => void,
): () => void {
  let checks = 0;
  const check = () => {
    const id = ++checks;
    hasSiteAccess(api, url).then(
      (granted) => {
        if (id === checks) onChange(granted);
      },
      () => {},
    );
  };
  api.permissions.onAdded.addListener(check);
  api.permissions.onRemoved.addListener(check);
  check();
  return () => {
    checks++;
    api.permissions.onAdded.removeListener(check);
    api.permissions.onRemoved.removeListener(check);
  };
}

/**
 * Asks for every website. Call it from the click handler itself, with nothing
 * awaited first: the permission request needs the user's gesture.
 */
export function requestAllSites(api: SiteAccessApi): Promise<boolean> {
  return api.permissions.request(ALL_SITES);
}

/**
 * Asks for [url]'s site only, or gives `false` when it isn't a web page. Call
 * it from the click handler itself, as [requestAllSites].
 */
export function requestSite(api: SiteAccessApi, url: string | undefined): Promise<boolean> {
  const pattern = sitePattern(url);
  if (pattern === null) return Promise.resolve(false);
  return api.permissions.request({ origins: [pattern] });
}
