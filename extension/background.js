// Keyguard Browser Agent — background.js (MV3 service worker)
//
// Orchestrator: mode detection, request routing, icon, shortcuts.
// Heavy lifting is in lib/ modules.

import { logBg } from "./lib/logger.js";
import { nmRequest } from "./lib/nm-client.js";
import { wsRequest, isWsConnected } from "./lib/ws-client.js";
import { initContextMenu } from "./lib/context-menu.js";
import { initMessaging } from "./lib/messaging.js";

// ---------------------------------------------------------------------------
// Mode detection
// ---------------------------------------------------------------------------

const HAS_NM =
  typeof chrome !== "undefined" &&
  chrome.runtime &&
  typeof chrome.runtime.connectNative === "function";

let modeOverride = "auto";
try {
  chrome.storage.local.get("browserAgentMode", (stored) => {
    if (stored && stored.browserAgentMode) {
      modeOverride = stored.browserAgentMode;
      logBg("info", `Mode override loaded: ${modeOverride}`);
    }
  });
  if (chrome.storage.onChanged) {
    chrome.storage.onChanged.addListener((changes, area) => {
      if (area === "local" && changes.browserAgentMode) {
        modeOverride = changes.browserAgentMode.newValue || "auto";
        logBg("info", `Mode override changed: ${modeOverride}`);
      }
    });
  }
} catch (e) {}

function useNativeMessaging() {
  return HAS_NM && modeOverride !== "ws";
}

logBg("info", `Mode: ${HAS_NM ? "Native Messaging" : "WebSocket"}`);

// ---------------------------------------------------------------------------
// Unified request API
// ---------------------------------------------------------------------------

async function request(obj) {
  if (useNativeMessaging()) return nmRequest(obj);
  return wsRequest(obj);
}

function query(domain, uri) {
  return request({ type: "query", domain, uri });
}

function secret(itemId) {
  return request({ type: "secret", item_id: itemId });
}

function requestForeground() {
  return request({ type: "request_foreground" });
}

// ---------------------------------------------------------------------------
// Storage helpers
// ---------------------------------------------------------------------------

function getLastChoice(domain) {
  return new Promise((resolve) => {
    chrome.storage.local.get({ lastChoice: {} }, (s) => {
      resolve((s.lastChoice && s.lastChoice[domain]) || null);
    });
  });
}

function recordLastChoice(domain, itemId) {
  if (!domain || !itemId) return;
  chrome.storage.local.get({ lastChoice: {} }, (s) => {
    const m = s.lastChoice || {};
    m[domain] = itemId;
    chrome.storage.local.set({ lastChoice: m });
  });
}

function getAutoSubmit() {
  return new Promise((resolve) => {
    chrome.storage.local.get({ autoSubmit: false }, (s) => resolve(!!s.autoSubmit));
  });
}

// ---------------------------------------------------------------------------
// Toolbar icon
// ---------------------------------------------------------------------------

const ICON_LOCKED = {
  16: "icons/lock-16.png", 32: "icons/lock-32.png", 48: "icons/lock-48.png",
  96: "icons/lock-96.png", 128: "icons/lock-128.png",
};
const ICON_UNLOCKED = {
  16: "icons/unlock-16.png", 32: "icons/unlock-32.png", 48: "icons/unlock-48.png",
  96: "icons/unlock-96.png", 128: "icons/unlock-128.png",
};

function setLockIcon(locked) {
  try {
    chrome.action.setIcon({ path: locked ? ICON_LOCKED : ICON_UNLOCKED });
  } catch (e) {
    logBg("error", "setIcon failed", String(e));
  }
}

function refreshIconForActiveTab() {
  if (!useNativeMessaging() && !isWsConnected()) return;
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    const tab = tabs && tabs[0];
    if (!tab || !tab.url) return;
    let domain = "";
    try { domain = new URL(tab.url).hostname; } catch (e) {}
    query(domain, tab.url)
      .then((res) => setLockIcon(!!res.locked))
      .catch(() => setLockIcon(true));
  });
}

// ---------------------------------------------------------------------------
// Keyboard shortcut: Ctrl+Shift+Space
// ---------------------------------------------------------------------------

function activeTab() {
  return new Promise((resolve, reject) => {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message));
      else resolve((tabs && tabs[0]) || null);
    });
  });
}

if (chrome.commands && chrome.commands.onCommand) {
  chrome.commands.onCommand.addListener(function (command) {
    if (command !== "autofill") return;
    logBg("info", "command: autofill triggered");
    activeTab()
      .then(function (tab) {
        if (!tab || !tab.url) return;
        var hostname = "";
        try { hostname = new URL(tab.url).hostname; } catch (e) {}
        return query(hostname, tab.url).then(function (res) {
          var items = (res && res.items) || [];
          if (items.length === 1) {
            logBg("info", "autofill: single match, filling", items[0].name);
            recordLastChoice(hostname, items[0].item_id);
            return secret(items[0].item_id).then(function (s) {
              s = s || {};
              if (tab.id != null) {
                return getAutoSubmit().then(function (autoSubmit) {
                  chrome.tabs.sendMessage(tab.id, {
                    type: "fill", username: s.username, password: s.password,
                    totp: s.totp, autoSubmit: autoSubmit,
                  });
                });
              }
            });
          }
          if (items.length > 1) {
            logBg("info", "autofill: multiple matches (" + items.length + "), opening popup");
            if (typeof chrome.action.openPopup === "function") {
              try { chrome.action.openPopup(); } catch (e) {
                logBg("warn", "autofill: openPopup failed", String(e));
              }
            } else {
              logBg("info", "autofill: openPopup not supported (Firefox)");
            }
          }
        });
      })
      .catch(function (e) { logBg("error", "autofill command failed", String(e)); });
  });
}

// ---------------------------------------------------------------------------
// Tab/window events
// ---------------------------------------------------------------------------

if (chrome.tabs && chrome.tabs.onActivated) {
  chrome.tabs.onActivated.addListener(refreshIconForActiveTab);
}
if (chrome.windows && chrome.windows.onFocusChanged) {
  chrome.windows.onFocusChanged.addListener(refreshIconForActiveTab);
}

// ---------------------------------------------------------------------------
// Init modules
// ---------------------------------------------------------------------------

initContextMenu(query, secret, recordLastChoice);
initMessaging(useNativeMessaging, query, secret, requestForeground, setLockIcon);

logBg("info", "background script loaded");
