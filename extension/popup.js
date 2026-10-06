function plog(level, msg, extra) {
  try {
    chrome.runtime.sendMessage({
      __kgLog: {
        ts: new Date().toISOString(),
        level: level,
        msg: String(msg),
        extra: extra === undefined ? undefined : String(extra),
      },
      __kgFrom: "popup",
    });
  } catch (e) {}
}

function status(msg) {
  var el = document.getElementById("status");
  if (el) el.textContent = msg;
}

window.addEventListener("error", function (e) {
  plog("error", "popup uncaught", (e && e.message) || String(e));
  status("JS error: " + ((e && e.message) || e));
});

// Firefox's chrome.* namespace may return a Promise OR require a callback
// (depending on version / MV2). These wrappers handle both forms.
function kgSendMessage(msg) {
  return new Promise(function (resolve, reject) {
    var r = chrome.runtime.sendMessage(msg, function (res) {
      if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message || "sendMessage failed"));
      else resolve(res);
    });
    if (r && typeof r.then === "function") r.then(resolve, reject);
  });
}
function kgTabsQuery(filter) {
  return new Promise(function (resolve, reject) {
    var r = chrome.tabs.query(filter, function (tabs) {
      if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message || "tabs.query failed"));
      else resolve(tabs);
    });
    if (r && typeof r.then === "function") r.then(resolve, reject);
  });
}
function kgTabsSendMessage(tabId, msg) {
  return new Promise(function (resolve, reject) {
    var r = chrome.tabs.sendMessage(tabId, msg, function (res) {
      if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message || "tabs.sendMessage failed"));
      else resolve(res);
    });
    if (r && typeof r.then === "function") r.then(resolve, reject);
  });
}

var ICON_OTP =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>';
var ICON_PASSKEY =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="15" r="4"/><path d="M10.8 12.5 19 4.3M16 4h4v4M14.5 5.5 18 9"/></svg>';
var ICON_COPY_USER =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>';
var ICON_COPY_PASS =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>';
var ICON_COPY =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';

plog("info", "popup script start");

// Neutral globe icon used when no tab favicon is available (avoids external
// requests which break under MV2 CORS restrictions).
var ICON_GLOBE = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%236b6b70' stroke-width='2'%3E%3Ccircle cx='12' cy='12' r='10'/%3E%3Cpath d='M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z'/%3E%3C/svg%3E";
// Keyguard lock icon shown in the header when the vault is locked.
var ICON_LOCK = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%236b6b70' stroke-width='2'%3E%3Crect x='3' y='11' width='18' height='11' rx='2' ry='2'/%3E%3Cpath d='M7 11V7a5 5 0 0 1 10 0v4'/%3E%3C/svg%3E";

function faviconFor(tabFavicon) {
  return tabFavicon || ICON_GLOBE;
}

function activeTab() {
  return kgTabsQuery({ active: true, currentWindow: true }).then(function (tabs) {
    return (tabs && tabs[0]) || null;
  });
}

function getDomain() {
  return activeTab().then(function (tab) {
    if (!tab || !tab.url) {
      plog("warn", "popup: no active tab url");
      return { domain: "", uri: "", favicon: "" };
    }
    var hostname = "";
    try {
      hostname = new URL(tab.url).hostname;
    } catch (e) {
      hostname = "";
    }
    plog("debug", "popup: active tab", tab.url);
    return { domain: hostname, uri: tab.url, favicon: tab.favIconUrl || "" };
  });
}

function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  }
  return new Promise(function (resolve, reject) {
    try {
      var ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      var ok = document.execCommand("copy");
      document.body.removeChild(ta);
      if (ok) resolve();
      else reject(new Error("execCommand('copy') failed"));
    } catch (e) {
      reject(e);
    }
  });
}

function fetchSecret(itemId) {
  return kgSendMessage({ type: "secret", itemId: itemId }).then(function (secret) {
    secret = secret || {};
    if (secret.error) throw new Error(secret.error);
    return secret;
  });
}

function recordLastChoice(domain, itemId) {
  if (!domain || !itemId) return;
  try {
    chrome.storage.local.get({ lastChoice: {} }, function (s) {
      var m = s.lastChoice || {};
      m[domain] = itemId;
      chrome.storage.local.set({ lastChoice: m });
    });
  } catch (e) {}
}

function getAutoSubmit() {
  return new Promise(function (resolve) {
    try {
      chrome.storage.local.get({ autoSubmit: false }, function (s) {
        resolve(!!(s && s.autoSubmit));
      });
    } catch (e) {
      resolve(false);
    }
  });
}

function makeCopyButton(title, icon, getValue) {
  var btn = document.createElement("button");
  btn.className = "pop-act";
  btn.title = title;
  btn.innerHTML = icon;
  btn.onclick = function (e) {
    e.stopPropagation();
    btn.disabled = true;
    Promise.resolve()
      .then(getValue)
      .then(function (value) {
        if (!value) throw new Error("nothing to copy");
        return copyText(value);
      })
      .then(function () {
        plog("info", "popup: copied", title);
        status("Copied: " + title);
        btn.classList.add("copied");
        setTimeout(function () {
          btn.classList.remove("copied");
          btn.disabled = false;
        }, 1200);
      })
      .catch(function (err) {
        plog("error", "popup copy failed", title + ": " + err);
        status("Copy failed: " + (err && err.message ? err.message : err));
        btn.disabled = false;
      });
  };
  return btn;
}

function itemRow(item, favicon, domain, isLast) {
  var el = document.createElement("button");
  el.className = "pop-item" + (isLast ? " pop-item-last" : "");

  var fav = document.createElement("img");
  fav.className = "fav";
  fav.alt = "";
  fav.src = favicon;
  fav.onerror = function () { fav.style.visibility = "hidden"; };
  el.appendChild(fav);

  var main = document.createElement("div");
  main.className = "pop-item-main";
  var name = document.createElement("div");
  name.className = "pop-name";
  name.textContent = item.name || "?";
  var user = document.createElement("div");
  user.className = "pop-user";
  user.textContent = item.username || "—";
  main.append(name, user);
  el.appendChild(main);

  var badges = document.createElement("div");
  badges.className = "pop-badges";
  if (item.has_totp) {
    var b = document.createElement("span");
    b.className = "badge";
    b.title = "One-time code (TOTP)";
    b.innerHTML = ICON_OTP;
    badges.appendChild(b);
  }
  if (item.has_passkey) {
    var p = document.createElement("span");
    p.className = "badge";
    p.title = "Passkey available";
    p.innerHTML = ICON_PASSKEY;
    badges.appendChild(p);
  }
  if (badges.childElementCount) el.appendChild(badges);

  if (isLast) {
    var last = document.createElement("span");
    last.className = "badge-last";
    last.textContent = "Last used";
    el.appendChild(last);
  }

  var actions = document.createElement("div");
  actions.className = "pop-actions";
  actions.appendChild(
    makeCopyButton("Copy username", ICON_COPY_USER, function () {
      return fetchSecret(item.item_id).then(function (s) { return s.username; });
    }),
  );
  actions.appendChild(
    makeCopyButton("Copy password", ICON_COPY_PASS, function () {
      return fetchSecret(item.item_id).then(function (s) { return s.password; });
    }),
  );
  if (item.has_totp) {
    actions.appendChild(
      makeCopyButton("Copy TOTP code", ICON_COPY, function () {
        return fetchSecret(item.item_id).then(function (s) { return s.totp; });
      }),
    );
  }
  el.appendChild(actions);

  el.onclick = function () {
    plog("info", "popup: item selected", item.name + (item.username ? " (" + item.username + ")" : ""));
    recordLastChoice(domain, item.item_id);
    plog("info", "popup requesting secret", item.item_id);
    fetchSecret(item.item_id)
      .then(function (secret) {
        plog("info", "popup secret result", "hasPassword=" + !!secret.password + " hasTotp=" + !!secret.totp);
        activeTab().then(function (tab) {
          if (tab && tab.id != null) {
            return getAutoSubmit().then(function (autoSubmit) {
              plog("info", "popup: dispatching fill", "tab=" + tab.id + " user=" + !!secret.username + " pass=" + !!secret.password + " totp=" + !!secret.totp + " autoSubmit=" + autoSubmit);
              return kgTabsSendMessage(tab.id, {
                type: "fill",
                username: secret.username,
                password: secret.password,
                totp: secret.totp,
                autoSubmit: autoSubmit,
              });
            }).then(function () {
              window.close();
            });
          }
          window.close();
        });
      })
      .catch(function (e) {
        plog("error", "popup secret failed", String(e));
      });
  };
  return el;
}

function load() {
  plog("info", "popup load() start");
  var favEl = document.getElementById("fav");
  var domainEl = document.getElementById("domain");
  var subEl = document.getElementById("sub");
  var list = document.getElementById("items");
  if (list) list.innerHTML = "";
  status("Loading…");
  plog("info", "popup opened");

  var timedOut = false;
  var timeout = new Promise(function (_, reject) {
    setTimeout(function () {
      timedOut = true;
      plog("warn", "popup timeout fired (8s, no response)");
      reject(new Error("popup timeout: no response from agent in 8s"));
    }, 8000);
  });

  getDomain()
    .then(function (ref) {
      var domain = ref.domain;
      var uri = ref.uri;
      var favicon = faviconFor(ref.favicon);
      plog("info", "popup domain", domain || "(none)");
      if (favEl) {
        favEl.src = favicon;
        favEl.onerror = function () { favEl.src = ICON_GLOBE; };
      }
      if (domainEl) domainEl.textContent = domain || "Keyguard";
      if (subEl) subEl.textContent = domain ? "Autofill" : "No page";

      plog("debug", "popup sending query message to background");
      return Promise.race([kgSendMessage({ type: "query", domain: domain, uri: uri }), timeout])
        .then(function (res) {
          if (timedOut) return;
          res = res || {};
          plog("info", "popup query result", "locked=" + !!res.locked + " items=" + ((res.items || []).length));
          if (res.locked) {
            if (favEl) favEl.src = ICON_LOCK;
            if (domainEl) domainEl.textContent = "Keyguard";
            if (subEl) subEl.textContent = "Vault locked";
            status("");

            var btn = document.createElement("button");
            btn.className = "pop-unlock-btn";
            btn.textContent = "Bring Keyguard to Front";
            btn.onclick = function () {
              btn.disabled = true;
              btn.textContent = "Requesting…";
              kgSendMessage({ type: "requestForeground" })
                .then(function () {
                  btn.textContent = "Done — unlock in Keyguard";
                  setTimeout(function () { window.close(); }, 1500);
                })
                .catch(function () {
                  btn.disabled = false;
                  btn.textContent = "Bring Keyguard to Front";
                });
            };
            list.appendChild(btn);
            return;
          }
          var items = Array.isArray(res.items) ? res.items : [];
          if (!items.length) {
            if (subEl) subEl.textContent = "No match";
            status("No logins for this site");
            return;
          }
          if (subEl) subEl.textContent = items.length + (items.length > 1 ? " logins" : " login");
          status("");
          chrome.storage.local.get({ lastChoice: {} }, function (s) {
            var lastId = ((s && s.lastChoice) || {})[domain] || null;
            if (lastId) {
              items = items.slice().sort(function (a, b) {
                if (a.item_id === lastId) return -1;
                if (b.item_id === lastId) return 1;
                return 0;
              });
            }
            items.forEach(function (item) {
              list.appendChild(itemRow(item, favicon, domain, item.item_id === lastId));
            });
          });
        });
    })
    .catch(function (e) {
      plog("error", "popup error", e && e.message ? e.message : String(e));
      status("Error: " + (e && e.message ? e.message : e));
    });
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", load);
} else {
  load();
}

document.getElementById("options").addEventListener("click", function (e) {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
});
document.getElementById("about").addEventListener("click", function (e) {
  e.preventDefault();
  chrome.tabs.create({ url: chrome.runtime.getURL("welcome.html") });
});
