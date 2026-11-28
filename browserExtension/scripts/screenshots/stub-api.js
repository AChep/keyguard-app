// A stand-in for the extension APIs in the store screenshots, which load the
// built pages over HTTP. Runs before the scripts of every frame; a frame with
// #stub=<name> gets `chrome` with what window.EXTENSION_STUBS of the top page
// (./data.js) has for <name>: the background's events on each port it opens,
// and the results of its calls.
(() => {
  const name = new URLSearchParams(location.hash.slice(1)).get("stub");
  if (!name) return;
  const stub = window.parent.EXTENSION_STUBS?.[name] ?? {};

  // _locales/en, substituted as chrome.i18n does.
  const request = new XMLHttpRequest();
  request.open("GET", "/_locales/en/messages.json", false);
  request.send();
  const messages = JSON.parse(request.responseText);
  const getMessage = (key, substitutions) => {
    if (key === "@@bidi_dir") return "ltr";
    if (key === "@@ui_locale") return "en";
    const entry = messages[key];
    if (!entry) return "";
    const args = substitutions == null ? [] : [substitutions].flat();
    const placeholders = entry.placeholders ?? {};
    return entry.message
      .replace(/\$([A-Za-z0-9_@]+)\$/g, (match, id) => placeholders[id.toLowerCase()]?.content ?? match)
      .replace(/\$(\d)/g, (_match, index) => String(args[Number(index) - 1] ?? ""))
      .replace(/\$\$/g, "$");
  };

  const event = () => {
    const listeners = new Set();
    return {
      addListener: (listener) => listeners.add(listener),
      removeListener: (listener) => listeners.delete(listener),
      hasListener: (listener) => listeners.has(listener),
    };
  };

  /** A port that gets the stub's events once the page listens, and answers its calls. */
  function connect({ name: portName } = {}) {
    const listeners = new Set();
    const emit = (message) => setTimeout(() => listeners.forEach((listener) => listener(message)), 0);
    for (const message of stub.events ?? []) emit(message);
    return {
      name: portName,
      postMessage(message) {
        if (message.kind === "call") {
          const value = stub.calls?.[message.method];
          if (value !== undefined) emit({ kind: "result", id: message.id, ok: true, value });
        } else if (message.kind === "resize" && window.frameElement) {
          // As the content script, which sizes the popover to the page.
          window.frameElement.parentElement.style.height = `${message.height}px`;
        }
      },
      onMessage: {
        addListener: (listener) => listeners.add(listener),
        removeListener: (listener) => listeners.delete(listener),
      },
      onDisconnect: event(),
      disconnect() {},
    };
  }

  const storage = { get: async () => ({}), set: async () => {}, remove: async () => {}, onChanged: event() };

  globalThis.chrome = {
    runtime: {
      id: "keyguard-store-screenshots",
      connect,
      getURL: (path) => new URL(path, location.origin).href,
      sendMessage: async () => null,
      onMessage: event(),
      openOptionsPage: async () => {},
    },
    i18n: { getMessage, getUILanguage: () => "en" },
    tabs: { query: async () => [{ id: 1, url: stub.tabUrl ?? "https://example.com/" }], onUpdated: event() },
    permissions: { contains: async () => true, request: async () => true, onAdded: event(), onRemoved: event() },
    storage: { local: storage, session: storage, sync: storage, onChanged: event() },
    action: { openPopup: async () => {} },
  };
})();
