// The shadow roots a content script sees: open ones, and closed ones where
// the browser lets extensions see them: Chromium's `dom.openOrClosedShadowRoot`
// and Firefox's `Element.openOrClosedShadowRoot`.
import { canHostShadow } from "./detect";

/** Chromium's `dom` API. */
export interface ClosedRootApi {
  openOrClosedShadowRoot?(element: Element): ShadowRoot | null;
}

/**
 * Reads the shadow root of an element; never that of [own], the host of the
 * extension's UI. Closed roots are asked for on custom elements only, unless
 * [everyHost]: asking about every div of a page costs too much on each focus
 * change and scan. The checks before an action ask on every element that
 * can host one: a page can hide a decoy in a closed root of a div.
 */
export function shadowRootReader(
  dom: ClosedRootApi | undefined,
  own: Element,
  { everyHost }: { everyHost: boolean },
): (element: Element) => ShadowRoot | null {
  return (element) => {
    if (element === own) return null;
    if (element.shadowRoot) return element.shadowRoot;
    if (everyHost ? !canHostShadow(element) : !element.localName.includes("-")) return null;
    try {
      if (dom?.openOrClosedShadowRoot) return dom.openOrClosedShadowRoot(element) ?? null;
      return (element as Element & { openOrClosedShadowRoot?: ShadowRoot | null }).openOrClosedShadowRoot ?? null;
    } catch {
      return null;
    }
  };
}
