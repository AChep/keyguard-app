// What the extension knows about its browser.

const CHROMIUM_ID = /^[a-p]{32}$/;

/**
 * The extension origin of the protocol's prologue: `chrome-extension://<id>/`
 * on Chromium browsers, the add-on ID on Firefox (protocol/README.md §2). It's
 * what the browser passes to the host, whatever scheme its pages show.
 */
export function extensionOrigin(runtime: { id: string; getURL(path: string): string }): string {
  const firefox = runtime.getURL("").startsWith("moz-extension:");
  return !firefox && CHROMIUM_ID.test(runtime.id) ? `chrome-extension://${runtime.id}/` : runtime.id;
}

interface UserAgentData {
  brands?: { brand: string }[];
}

/** The browser's product name, a hint for the app. Not localized: it's a name. */
export function browserName(nav: { userAgent: string; userAgentData?: UserAgentData } = navigator): string {
  const ua = nav.userAgent;
  if (/Firefox\//.test(ua)) return "Firefox";
  if (/Edg\//.test(ua)) return "Microsoft Edge";
  if (/OPR\//.test(ua)) return "Opera";
  if (/Vivaldi\//.test(ua)) return "Vivaldi";
  const brands = nav.userAgentData?.brands?.map((it) => it.brand) ?? [];
  if (brands.includes("Brave")) return "Brave";
  if (brands.includes("Google Chrome")) return "Google Chrome";
  if (/Chrome\//.test(ua)) return "Chromium";
  return "Browser";
}
