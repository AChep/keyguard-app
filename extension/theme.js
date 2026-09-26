// Shared theme helper for all extension pages.
// Applies the user's theme preference (system / light / dark) to
// <html data-theme> and updates live on changes. The initial paint follows
// the OS setting synchronously (no async flash), then the stored preference
// is applied once it loads.
(function () {
  function systemDark() {
    return !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  }

  function apply(themePref) {
    var dark;
    if (themePref === "light") dark = false;
    else if (themePref === "dark") dark = true;
    else dark = systemDark();
    var root = document.documentElement;
    root.dataset.theme = dark ? "dark" : "light";
    root.style.colorScheme = dark ? "dark" : "light";
  }

  function pref(cb) {
    try {
      chrome.storage.local.get({ theme: "system" }, function (s) {
        cb((s && s.theme) || "system");
      });
    } catch (e) {
      cb("system");
    }
  }

  // Initial paint from the OS setting (synchronous, no flash).
  apply("system");
  // Then honour the stored preference.
  pref(apply);

  // React to preference changes from the options page.
  if (chrome.storage && chrome.storage.onChanged) {
    chrome.storage.onChanged.addListener(function (changes, area) {
      if (area === "local" && changes.theme) {
        apply(changes.theme.newValue || "system");
      }
    });
  }

  // React to OS changes while in "system" mode.
  if (window.matchMedia) {
    var mq = window.matchMedia("(prefers-color-scheme: dark)");
    var onSystem = function () {
      pref(function (p) {
        if (p === "system") apply("system");
      });
    };
    if (mq.addEventListener) mq.addEventListener("change", onSystem);
    else if (mq.addListener) mq.addListener(onSystem);
  }
})();
