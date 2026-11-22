// The language, direction and title of the extension's pages: the popup, the
// options page and the embedded pages.
import { browser } from "wxt/browser";
import { t } from "../i18n";

/**
 * The direction of the extension's messages in the browser's language:
 * Arabic and Hebrew read right to left.
 */
export function messageDirection(getMessage: (name: string) => string): "ltr" | "rtl" {
  return getMessage("@@bidi_dir") === "rtl" ? "rtl" : "ltr";
}

/** Sets the language, the direction and the title of a page of the extension. */
export function localizePage(doc: Document): void {
  doc.documentElement.lang = browser.i18n.getUILanguage();
  doc.documentElement.dir = messageDirection((name) =>
    (browser.i18n as unknown as { getMessage(name: string): string }).getMessage(name),
  );
  doc.title = t("browser_extension_name");
}
