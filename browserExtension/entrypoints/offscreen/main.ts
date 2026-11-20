// The offscreen document of Chromium's background: it writes the clipboard,
// which a service worker can't. See src/lib/background/clipboard.ts.
import { browser } from "wxt/browser";
import { isOffscreenCopy } from "../../src/lib/background/clipboard";

/**
 * `navigator.clipboard` needs a focused document, which an offscreen
 * document never is; `execCommand("copy")` of a selection works.
 */
function copy(text: string): boolean {
  const area = document.createElement("textarea");
  area.value = text;
  document.body.append(area);
  try {
    area.select();
    return document.execCommand("copy");
  } finally {
    area.value = "";
    area.remove();
  }
}

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  // Only the background: content scripts' messages reach this page too.
  if (sender.id !== browser.runtime.id || sender.tab !== undefined || !isOffscreenCopy(message)) return false;
  sendResponse({ ok: copy(message.text) });
  return false;
});
