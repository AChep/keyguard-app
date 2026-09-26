// Context menu — dynamic per-site credential list.

import { logBg } from "./logger.js";

const KG_ROOT = "kg-root";
let kgChildIds = [];

function clearKgChildren(cb) {
  const ids = kgChildIds;
  if (!ids.length) { if (cb) cb(); return; }
  let pendingCount = ids.length;
  ids.forEach(function (id) {
    chrome.contextMenus.remove(id, function () {
      pendingCount--;
      if (pendingCount === 0) { kgChildIds = []; if (cb) cb(); }
    });
  });
}

function rebuildContextMenu(tab, query) {
  clearKgChildren(function () {
    if (!tab || !tab.url) return;
    let domain = "";
    try { domain = new URL(tab.url).hostname; } catch (e) { return; }
    if (!domain) return;
    query(domain, tab.url)
      .then(function (res) {
        (res.items || []).slice(0, 10).forEach(function (it) {
          const id = "kg-item-" + it.item_id;
          kgChildIds.push(id);
          chrome.contextMenus.create({
            id, parentId: KG_ROOT,
            title: (it.name || "?") + (it.username ? "  •  " + it.username : ""),
            contexts: ["editable"],
          });
        });
      })
      .catch(function () {});
  });
}

function fillItem(itemId, tabId, domain, secret, recordLastChoice) {
  secret(itemId)
    .then(function (s) {
      s = s || {};
      if (domain) recordLastChoice(domain, itemId);
      if (tabId != null) {
        chrome.tabs.sendMessage(tabId, {
          type: "fill", username: s.username, password: s.password, totp: s.totp,
        });
      }
    })
    .catch(function (e) { logBg("error", "context menu fill failed", String(e)); });
}

export function initContextMenu(query, secret, recordLastChoice) {
  if (!chrome.contextMenus) return;

  chrome.contextMenus.removeAll(function () {
    chrome.contextMenus.create({ id: KG_ROOT, title: "Keyguard Autofill", contexts: ["editable"] });
  });

  chrome.contextMenus.onClicked.addListener(function (info, tab) {
    if (info.menuItemId === KG_ROOT) {
      if (!tab || !tab.url) return;
      let domain = "";
      try { domain = new URL(tab.url).hostname; } catch (e) {}
      query(domain, tab.url).then(function (res) {
        const items = res.items || [];
        if (items.length === 1) {
          fillItem(items[0].item_id, tab.id, domain, secret, recordLastChoice);
        } else if (items.length > 1) {
          if (typeof chrome.action.openPopup === "function") {
            try { chrome.action.openPopup(); } catch (e) {}
          }
        }
      });
      return;
    }
    if (typeof info.menuItemId === "string" && info.menuItemId.indexOf("kg-item-") === 0) {
      const itemId = info.menuItemId.slice("kg-item-".length);
      let domain = "";
      try { domain = new URL(info.pageUrl || (tab && tab.url) || "").hostname; } catch (e) {}
      fillItem(itemId, tab && tab.id, domain, secret, recordLastChoice);
    }
  });

  if (chrome.tabs && chrome.tabs.onActivated) {
    chrome.tabs.onActivated.addListener(function (activeInfo) {
      chrome.tabs.get(activeInfo.tabId, function (tab) { rebuildContextMenu(tab, query); });
    });
  }
  if (chrome.webNavigation && chrome.webNavigation.onCompleted) {
    chrome.webNavigation.onCompleted.addListener(function (details) {
      if (details.frameId === 0) {
        chrome.tabs.get(details.tabId, function (tab) { rebuildContextMenu(tab, query); });
      }
    });
  }
  if (chrome.contextMenus.onShown) {
    chrome.contextMenus.onShown.addListener(function () {
      chrome.tabs.query({ active: true, currentWindow: true }, function (tabs) {
        rebuildContextMenu(tabs && tabs[0], query);
      });
    });
  }
}
