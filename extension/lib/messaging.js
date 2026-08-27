// Internal messaging — message listeners for popup, content script, and options page.

import { logBg, getLogs, clearLogs, pushLog } from "./logger.js";
import { setSharedSecret, isPaired } from "./ws-client.js";

export function initMessaging(useNativeMessaging, query, secret, requestForeground, setLockIcon) {
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    // Cross-context log relay (popup, content script → background)
    if (message && message.__kgLog && message.__kgFrom && message.__kgFrom !== "bg") {
      pushLog(message.__kgLog);
      return false;
    }
    if (message && message.__kgGetLogs) { sendResponse(getLogs()); return true; }
    if (message && message.__kgClear) { clearLogs(); sendResponse({ ok: true }); return true; }
    // Shared secret — only accept from extension pages (popup, options).
    if (message && message.__kgSetSecret) {
      const url = sender.url || "";
      if (!url.startsWith("chrome-extension://") && !url.startsWith("moz-extension://")) {
        logBg("warn", "Rejected __kgSetSecret from untrusted origin:", url);
        sendResponse({ ok: false, error: "unauthorized" });
        return true;
      }
      sendResponse(setSharedSecret(message.__kgSetSecret));
      return true;
    }
    if (message && message.__kgGetStatus) {
      sendResponse({ mode: useNativeMessaging() ? "nm" : "ws", paired: isPaired() });
      return true;
    }
    // Agent request messages
    if (message.type === "query") {
      logBg("info", "onMessage: query", `${message.domain} / ${message.uri}`);
      query(message.domain, message.uri)
        .then((res) => {
          logBg("info", "onMessage: query result", `locked=${!!res.locked} items=${(res.items || []).length}`);
          setLockIcon(!!res.locked);
          sendResponse({ type: "queryResult", ...res });
        })
        .catch((e) => {
          logBg("error", "onMessage: query failed", String(e));
          sendResponse({ type: "queryResult", locked: true, items: [], error: String(e) });
        });
      return true;
    }
    if (message.type === "secret") {
      secret(message.itemId)
        .then((res) => sendResponse({ type: "secretResult", ...res }))
        .catch((e) => sendResponse({ type: "secretResult", locked: true, error: String(e) }));
      return true;
    }
    if (message.type === "requestForeground") {
      requestForeground()
        .then((res) => sendResponse({ type: "requestForegroundResult", ...res }))
        .catch((e) => sendResponse({ type: "requestForegroundResult", success: false, error: String(e) }));
      return true;
    }
    if (message.type === "fill") {
      const tabId = sender.tab?.id;
      if (tabId == null) return false;
      logBg("info", "onMessage: fill", `tab=${tabId}`);
      if (tabId != null) {
        logBg("info", "fill dispatched", `tab=${tabId} user=${!!message.username} pass=${!!message.password} totp=${!!message.totp}`);
        chrome.tabs.sendMessage(tabId, {
          type: "fill", username: message.username, password: message.password, totp: message.totp,
        });
      }
      return false;
    }
    return false;
  });

  chrome.runtime.onInstalled.addListener(function (details) {
    logBg("info", "onInstalled", details.reason || "?");
    if (details.reason === "install") {
      chrome.tabs.create({ url: chrome.runtime.getURL("welcome.html") });
    }
  });
}
