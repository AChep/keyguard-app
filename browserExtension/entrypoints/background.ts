import { browser, type Browser, type PublicPath } from "wxt/browser";
import { defineBackground } from "wxt/utils/define-background";
import {
  POPUP_PAGE,
  POPUP_PORT,
  type BackgroundMessage,
  type CallArgs,
  type CallHandlers,
  type CallMethod,
  type CallResult,
  type ExtensionStatus,
  type PopupMessage,
} from "../src/lib/background/api";
import {
  ClipboardWriter,
  clipboardRoute,
  OFFSCREEN_PAGE,
  type ClipboardDeps,
  type OffscreenApi,
} from "../src/lib/background/clipboard";
import { BackgroundController, callErrorOf } from "../src/lib/background/controller";
import { isOwnPage, TabWatch } from "../src/lib/background/frames";
import { PageController, type PagePort } from "../src/lib/background/pages";
import { PasskeyController } from "../src/lib/background/passkeys";
import { siteIconSource, siteIconUrl, type SiteIconEnv } from "../src/lib/background/siteIcon";
import { ThemeSync, type FirefoxThemeApi } from "../src/lib/background/theme";
import { ExtensionUpdater, reinjectContentScripts } from "../src/lib/background/update";
import { extensionWasmOptions, loadNoise } from "../src/lib/crypto/wasm";
import type { NativePort } from "../src/lib/native/connection";
import { OPTIONS_KEY, OptionsStore, parseOptions } from "../src/lib/options/store";
import type { ContentCommand } from "../src/lib/page/messages";
import { isCancelled } from "../src/lib/protocol/session";
import { IndexedDbArea } from "../src/lib/storage/indexedDb";
import { needsAttention, statusTitle } from "../src/lib/ui/statusText";
import { browserName, extensionOrigin } from "../src/lib/util/browserInfo";
import { isObject } from "../src/lib/util/json";
import { WEBAUTHN_PORT } from "../src/lib/webauthn/bridge";

/** The command of the fill shortcut, see wxt.config.ts. */
const FILL_COMMAND = "fill-login";
/** The content script, as WXT outputs it. */
const CONTENT_SCRIPT = "/content-scripts/content.js";

/** An event that takes a filter, as Firefox's `tabs.onUpdated` does. */
interface FilteredEvent<T> {
  addListener(callback: T, filter: { properties: string[] }): void;
}

/** A popup's call of a method that [calls] answers. */
function isCall(message: unknown, calls: CallHandlers): message is PopupMessage {
  if (!isObject(message)) return false;
  const { kind, id, method, args } = message;
  return (
    kind === "call" &&
    Number.isSafeInteger(id) &&
    typeof method === "string" &&
    Object.hasOwn(calls, method) &&
    isObject(args)
  );
}

/** Answers a popup's call. */
function answer<M extends CallMethod>(calls: CallHandlers, method: M, args: CallArgs<M>): Promise<CallResult<M>> {
  return calls[method](args);
}

function post(port: Browser.runtime.Port, message: BackgroundMessage) {
  try {
    port.postMessage(message);
  } catch {
    // The popup closed.
  }
}

function showStatus(status: ExtensionStatus) {
  // The badge carries no words: a mark for statuses that need the user, and
  // the localized status as the button's title. Colors: error and primary of
  // the light scheme, src/lib/ui/theme.css.
  void browser.action.setBadgeText({ text: needsAttention(status) ? "!" : "" });
  void browser.action.setBadgeBackgroundColor({
    color: status.kind === "pinned_key_mismatch" || status.kind === "unverified_host" ? "#cc0808" : "#006689",
  });
  void browser.action.setTitle({ title: statusTitle(status) });
}

function createClipboard(): ClipboardWriter {
  const offscreen = (browser as unknown as { offscreen?: OffscreenApi }).offscreen;
  const route = clipboardRoute({
    firefox: import.meta.env.FIREFOX,
    offscreen: offscreen !== undefined,
    document: typeof document !== "undefined" && typeof navigator.clipboard?.writeText === "function",
  });
  const deps: ClipboardDeps =
    route === "offscreen" && offscreen !== undefined
      ? {
          route,
          offscreen,
          offscreenUrl: browser.runtime.getURL(OFFSCREEN_PAGE as PublicPath),
          sendMessage: (message) => browser.runtime.sendMessage(message),
        }
      : route === "document"
        ? { route, writeText: (text) => navigator.clipboard.writeText(text) }
        : { route: null };
  return new ClipboardWriter(deps);
}

/**
 * Firefox: stores the colors of the browser's theme for the extension's pages,
 * now and as the theme changes. The listener goes in as the event page starts,
 * as its listeners must; each start also reads the theme again, for changes
 * made while the page was suspended.
 */
function syncTheme() {
  new ThemeSync({
    theme: (browser as unknown as { theme?: FirefoxThemeApi }).theme,
    // Imported here, so the color library stays out of other browsers' builds.
    themeTokens: async (theme) => (await import("../src/lib/ui/browserTheme")).themeTokens(theme),
    area: browser.storage.local,
    // The event page's DOM, see src/lib/background/clipboard.ts.
    mirror: () => localStorage,
  }).start();
}

export default defineBackground(() => {
  if (import.meta.env.FIREFOX) syncTheme();
  const popups = new Set<Browser.runtime.Port>();
  const clipboard = createClipboard();
  const options = new OptionsStore(browser.storage.local);
  const extensionId = browser.runtime.id;
  const getTab = (tabId: number) => browser.tabs.get(tabId);
  // Chromium serves icons with the `favicon` permission, see wxt.config.ts.
  const siteIcons: SiteIconEnv = {
    source: siteIconSource({
      firefox: import.meta.env.FIREFOX,
      permissions: browser.runtime.getManifest().permissions ?? [],
    }),
    getURL: (path) => browser.runtime.getURL(path as PublicPath),
  };

  // Cancels the requests of fills that have no port to the page when their
  // tab closes or leaves the page. Tab updates wake the worker only while it
  // watches one.
  const onUpdated = (tabId: number, change: Browser.tabs.OnUpdatedInfo) => {
    if (change.url !== undefined) tabs.navigated(tabId, change.url);
  };
  const tabs = new TabWatch((watching) => {
    const updates = browser.tabs.onUpdated;
    if (!watching) {
      updates.removeListener(onUpdated);
    } else if (import.meta.env.FIREFOX) {
      // Firefox sends only the updates asked for.
      (updates as unknown as FilteredEvent<typeof onUpdated>).addListener(onUpdated, { properties: ["url", "status"] });
    } else {
      updates.addListener(onUpdated);
    }
  });

  const controller: BackgroundController = new BackgroundController({
    connectNative: (application) => browser.runtime.connectNative(application) as unknown as NativePort,
    lastError: () => browser.runtime.lastError?.message,
    // The Wasm loads from the extension's files: wxt.config.ts copies the file there, outside the typed public paths.
    loadNoise: () => loadNoise(extensionWasmOptions((path) => browser.runtime.getURL(path as PublicPath))),
    // Content scripts can read `storage.local`, but not the extension origin's IndexedDB.
    storage: new IndexedDbArea(indexedDB),
    origin: extensionOrigin(browser.runtime),
    browserName: browserName(),
    tab: getTab,
    copyText: (text) => clipboard.write(text),
    mayCopyPassword: (tabId, handle) => pages.mayCopyPassword(tabId, handle),
    onStatus: (status) => {
      showStatus(status);
      for (const port of popups) post(port, { kind: "status", status });
      passkeys.statusChanged();
      pages.statusChanged();
      updater.statusChanged(status);
    },
  });

  const passkeys = new PasskeyController({
    extensionId,
    app: controller,
    options,
    conditionalChanged: (frame) => pages.conditionalChanged(frame),
  });

  const pages: PageController = new PageController({
    extensionId,
    app: controller,
    options,
    passkeys,
    sendToFrame: (tabId, message: ContentCommand, target) => browser.tabs.sendMessage(tabId, message, target),
    sendToTab: (tabId, message: ContentCommand) => browser.tabs.sendMessage(tabId, message),
    injectContentScript: async (tabId) => {
      await browser.scripting.executeScript({ target: { tabId, frameIds: [0] }, files: [CONTENT_SCRIPT as never] });
    },
    tab: getTab,
    siteIcon: async (tabId, url) => {
      const tab = await browser.tabs.get(tabId);
      return siteIconUrl({ url, tabUrl: tab.url, favIconUrl: tab.favIconUrl }, siteIcons);
    },
    tabs,
    // Called before anything awaits: Firefox before 149 opens the popup only
    // while it handles the user's input.
    openPopup: () => browser.action.openPopup(),
    // The card beside the inline menu copies as the popup does: through the background.
    copyText: (text) => clipboard.write(text),
    // Firefox has no `documentId` in `tabs.sendMessage`, and rejects it.
    documentIds: !import.meta.env.FIREFOX,
  });

  const updater = new ExtensionUpdater({
    idle: () => popups.size === 0 && !controller.busy && !pages.busy && !passkeys.busy,
    reload: () => browser.runtime.reload(),
    // Firefox has none.
    requestUpdateCheck: import.meta.env.FIREFOX ? undefined : () => browser.runtime.requestUpdateCheck(),
  });

  const calls: CallHandlers = { ...controller.calls, ...pages.calls };

  browser.runtime.onConnect.addListener((port) => {
    if (port.name === WEBAUTHN_PORT) {
      // A passkey request of a content script: PasskeyController checks the sender.
      passkeys.connect(port as unknown as PagePort);
      return;
    }
    if (port.name !== POPUP_PORT) {
      // The inline menu and the save bar: PageController checks the sender.
      pages.connect(port as unknown as PagePort);
      return;
    }
    // Only the popup: not content scripts, and not the inline menu or the save
    // bar, which on Firefox are extension pages a web page can load.
    if (!isOwnPage(port.sender, browser.runtime.id, POPUP_PAGE)) {
      port.disconnect();
      return;
    }
    popups.add(port);
    port.onDisconnect.addListener(() => popups.delete(port));
    port.onMessage.addListener((message: unknown) => {
      if (!isCall(message, calls)) return;
      updater.track(answer(calls, message.method, message.args as never)).then(
        (value) => post(port, { kind: "result", id: message.id, ok: true, value }),
        (error: unknown) => post(port, { kind: "result", id: message.id, ok: false, error: callErrorOf(error) }),
      );
    });
    post(port, { kind: "status", status: controller.snapshot });
  });

  // Content scripts. Extension pages get no answer here.
  browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (sender.tab === undefined) return false;
    updater.track(pages.onContentMessage(message, sender)).then(sendResponse, (error: unknown) => {
      console.warn("Keyguard: a content script request failed", error);
      sendResponse(null);
    });
    return true;
  });

  browser.commands.onCommand.addListener((command, tab) => {
    if (command !== FILL_COMMAND || tab?.id === undefined) return;
    // At once, in the handler of the user's key: it may open the popup, see PageController.fillCommand.
    pages.fillCommand(tab.id).catch((error: unknown) => {
      // The tab closed or left the page while the app asked.
      if (!isCancelled(error)) console.warn("Keyguard: the fill shortcut failed", error);
    });
  });

  browser.tabs.onRemoved.addListener((tabId) => {
    tabs.removed(tabId);
    pages.tabRemoved(tabId);
  });

  // Turning passkeys off hands the waiting conditional requests to the browser.
  // The event's value applies at once: a pick that comes next must see it.
  browser.storage.onChanged.addListener((changes, area) => {
    if (area !== "local" || !(OPTIONS_KEY in changes)) return;
    options.invalidate();
    passkeys.optionsChanged(parseOptions(changes[OPTIONS_KEY]?.newValue));
  });

  // Coming back to the browser reconnects a dropped port at once.
  browser.windows.onFocusChanged.addListener((windowId) => {
    if (windowId !== browser.windows.WINDOW_ID_NONE) controller.focused();
  });

  // A downloaded update installs once the extension is idle, see src/lib/background/update.ts.
  browser.runtime.onUpdateAvailable.addListener(() => updater.updateAvailable());
  browser.runtime.onSuspend.addListener(() => updater.suspending());

  browser.runtime.onInstalled.addListener(({ reason }) => {
    // The extension creates its static key on install (protocol §2).
    controller.ensureStaticKey().catch((error: unknown) => console.error("Keyguard: no static key", error));
    // Open tabs get this version's content scripts in place of the copies the
    // update orphaned. Firefox starts the manifest's content scripts in open
    // tabs itself as the extension starts.
    if (reason === "update" && !import.meta.env.FIREFOX) {
      void reinjectContentScripts({
        contentScripts: browser.runtime.getManifest().content_scripts ?? [],
        tabs: (query) => browser.tabs.query(query),
        inject: (tabId, files, allFrames) =>
          browser.scripting.executeScript({ target: { tabId, allFrames }, files: files as never[] }),
      });
    }
  });
  // A startup listener starts the worker with the browser, so the toolbar
  // button shows the status before the popup opens.
  browser.runtime.onStartup.addListener(() => undefined);

  showStatus(controller.snapshot);
  controller.start();
});
