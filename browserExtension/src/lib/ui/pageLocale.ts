// The language, direction and title of the extension's pages: the popup, the
// options page and the embedded pages.
import { browser } from "wxt/browser";
import { t, uiDirection } from "../i18n";

/** Sets the language, the direction and the title of a page of the extension. */
export function localizePage(doc: Document): void {
  doc.documentElement.lang = browser.i18n.getUILanguage();
  doc.documentElement.dir = uiDirection();
  doc.title = t("browser_extension_name");
}
