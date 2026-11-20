// The content script: finds login fields, shows the inline icon and menu,
// fills what the background sends to this frame, and reports submitted
// passwords for the save bar. It runs in the isolated world of every http(s)
// frame and of about:blank frames that inherit an http(s) origin.
//
// It never fills on its own: fills come from the background after a trusted
// click in the inline menu, a click in the popup, or the keyboard shortcut.
// The password step of a sign-in one of those started may follow without a
// click in the top frame, as the background decides
// (../../src/lib/content/passwordStep.ts). After a login fill, it selects
// the sign-in button when the background asks
// (../../src/lib/content/submit.ts).
import { browser } from "wxt/browser";
import type { ContentScriptContext } from "wxt/utils/content-script-context";
import { defineContentScript } from "wxt/utils/define-content-script";
import {
  detectFormOf,
  detectForms,
  inputOf,
  roleIn,
  type DetectedForm,
  type DetectOptions,
  type FieldRole,
} from "../../src/lib/content/detect";
import {
  fillLogin,
  fillNewPassword,
  fillOtp,
  hasWritableLogin,
  pageForm,
  type FilledValue,
  type LoginValues,
} from "../../src/lib/content/fill";
import {
  browserLayout,
  fieldExposure,
  frameElementOf,
  frameExposure,
  isFillable,
  isOpaqueOrigin,
  ownOrigin,
  popupCenter,
  popupExposure,
  sameOriginFrames,
  TopLayerWatch,
  transparency,
  type Layout,
  type WatchedDocument,
} from "../../src/lib/content/guards";
import { PasswordStepReporter, type StepReport } from "../../src/lib/content/passwordStep";
import { SaveTracker } from "../../src/lib/content/save";
import { shadowRootReader, type ClosedRootApi } from "../../src/lib/content/shadowRoots";
import { submitAfterFill, watchSignIn, type SignInWatch, type SubmitOptions } from "../../src/lib/content/submit";
import { ContentUi } from "../../src/lib/content/ui";
import { PageWatcher } from "../../src/lib/content/watch";
import { t } from "../../src/lib/i18n";
import { watchOptions, type ExtensionOptions, type ObservableStorageArea } from "../../src/lib/options/store";
import {
  documentCommandOf,
  MENU_PAGE,
  parseFrameCheck,
  randomToken,
  SAVE_BAR_PAGE,
  type ContentCommand,
  type ContentMessage,
  type ContentRequest,
  type DocumentResult,
  type GuardedPopup,
  type MenuOpenResult,
  type UiToken,
} from "../../src/lib/page/messages";
import { MIN_VISIBLE_MS, tracksVisibility } from "../../src/lib/ui/clickGate";
import { frameColors, watchTheme } from "../../src/lib/ui/storedTheme";
import { isObject } from "../../src/lib/util/json";

export default defineContentScript({
  matches: ["http://*/*", "https://*/*"],
  allFrames: true,
  matchAboutBlank: true,
  runAt: "document_idle",
  // WXT's start message would be visible to the page.
  noScriptStartedPostMessage: true,
  main(ctx) {
    startContentScript(ctx);
  },
});

/**
 * The frame's own origin: what it really runs as, unlike its URL in a sandbox,
 * or in an about:blank or srcdoc frame, which runs as its parent's.
 */
function frameOrigin(): string | null {
  if (isOpaqueOrigin(window)) return null;
  const origin = ownOrigin(window);
  return /^https?:\/\//.test(origin) ? origin : null;
}

const DEBOUNCE_MS = 300;

/** A command that fills, answered once the fields are filled. */
type FillCommand = Extract<ContentCommand, { kind: "fill" | "fill_totp" | "fill_password" }>;

function startContentScript(ctx: ContentScriptContext) {
  // A sandboxed or opaque frame gets nothing (CVE-2025-65203).
  const origin = frameOrigin();
  if (origin === null) return;

  // This document's token, in the isolated world only. Requests carry it, and
  // commands for another document of this frame are refused as for another
  // origin, see ../../src/lib/page/messages.ts.
  const documentToken = randomToken();
  function send<T>(request: ContentRequest): Promise<T | undefined> {
    const message: ContentMessage = { ...request, doc: documentToken };
    return (browser.runtime.sendMessage(message) as Promise<T | undefined>).catch(() => undefined);
  }

  const doc = document;
  const isTop = window === window.top;
  const abort = new AbortController();
  const signal = abort.signal;
  ctx.onInvalidated(() => abort.abort());

  // The extension's pages run as its own origin; Chromium may load them by a
  // dynamic URL (`use_dynamic_url`), which is what `getURL` returns here.
  const extensionOrigins = [
    ...new Set([new URL(browser.runtime.getURL("/" as never)).origin, `chrome-extension://${browser.runtime.id}`]),
  ];
  const ui = new ContentUi(
    doc,
    {
      iconClicked: (field) => toggleMenu(field),
      arrowDownPressed: (field) => openMenu(field, true),
      menuFocused: () => void send({ kind: "menu_focus" }),
      menuDismissed: () => ui.closeMenu(),
      menuShown: () => void send({ kind: "menu_shown" }),
    },
    (path) => browser.runtime.getURL(path as never),
    extensionOrigins,
    t("browser_extension_name"),
  );
  ctx.onInvalidated(() => ui.destroy());

  const dom = (browser as unknown as { dom?: ClosedRootApi }).dom;
  const shadowRootOf = shadowRootReader(dom, ui.host, { everyHost: false });
  const layout = browserLayout(doc, shadowRootOf);
  // The checks before an action, and the watch of the page's top layer while a
  // popup is open, look into closed roots of built-in elements too.
  const guardReader = shadowRootReader(dom, ui.host, { everyHost: true });
  const guardLayout = browserLayout(doc, guardReader);

  /**
   * The frame elements that hold this frame, up through the ancestors of its
   * origin, each with its document; `null` when there are too many to check.
   * A parent of another origin isn't among them, see [checkAncestors].
   */
  function ancestors(): (WatchedDocument & { layout: Layout })[] | null {
    return (
      sameOriginFrames(window)?.map((frame) => ({
        doc: frame.ownerDocument,
        host: frame,
        layout: browserLayout(frame.ownerDocument, guardReader),
      })) ?? null
    );
  }

  const topLayer = new TopLayerWatch({
    documents: () => [{ doc, host: ui.host, layout: guardLayout }, ...(ancestors() ?? [])],
    open: () => ui.menuElement !== null || ui.saveBarElement !== null,
  });
  signal.addEventListener("abort", () => topLayer.stop());
  const detect: DetectOptions = { shadowRootOf, rendered: (input) => layout.rendered(input), ignore: ui.host };
  /** Whether a fill may write into a field: fillable and not faded out. */
  const canFill = (input: HTMLInputElement) => isFillable(input, layout) && transparency(input, layout) === null;

  /** Values Keyguard filled from the vault, to tell them from typed ones. */
  const filled = new WeakMap<HTMLInputElement, string>();
  const filledValue: FilledValue = (input) => filled.get(input);
  /** The last field with a role that had focus. */
  let lastField: HTMLInputElement | null = null;
  /** When [lastField] got focus, by `Date.now()`: the popup fills the frame focused last. */
  let focusedAt: number | null = null;

  // region Options

  /** The options, once read: most frames, as ads, never need them. */
  let options: ExtensionOptions | null = null;
  let optionsRead: Promise<void> | null = null;

  /** Reads the options, then follows their changes; resolves on the first read. */
  function readOptions(): Promise<void> {
    optionsRead ??= new Promise((resolve) => {
      if (signal.aborted) return;
      // A menu opens only once the options are read: its frame's color comes with them.
      watchFrameTheme();
      const stop = watchOptions(browser.storage.local as unknown as ObservableStorageArea, (next) => {
        options = next;
        resolve();
        if (!next.inlineMenu) {
          ui.hideIcon();
          ui.closeMenu();
        }
        // The first read shows the icon of the field that asked for them.
        const active = inputOf(deepActiveElement());
        if (next.inlineMenu && active !== null) evaluate(active);
      });
      signal.addEventListener("abort", stop);
    });
    return optionsRead;
  }

  // endregion

  // region Theme

  let themeWatched = false;

  /**
   * Firefox: follows the browser's theme for the color under the frames of
   * the menu and the save bar while their pages load. Once one may open: most
   * frames never show one.
   */
  function watchFrameTheme() {
    if (!import.meta.env.FIREFOX || themeWatched || signal.aborted) return;
    themeWatched = true;
    const stop = watchTheme(browser.storage.local as unknown as ObservableStorageArea, (theme) =>
      ui.setFrameColors(frameColors(theme)),
    );
    signal.addEventListener("abort", stop);
  }

  // endregion

  // region Fields and the inline icon

  function deepActiveElement(): Element | null {
    let element = doc.activeElement;
    for (let depth = 0; element !== null && depth < 32; depth++) {
      const inner = shadowRootOf(element)?.activeElement ?? null;
      if (inner === null) break;
      element = inner;
    }
    return element;
  }

  /** The form of [input], [form] when detected already, and the input's role in it. */
  function roleOf(input: HTMLInputElement, form = detectFormOf(input, detect)): { form: DetectedForm; role: FieldRole } | null {
    const role = form === null ? null : roleIn(form, input);
    return form === null || role === null ? null : { form, role };
  }

  /** Shows the icon on a focused field with a role, if the page shows the field. [form]: its form, when detected already. */
  function evaluate(input: HTMLInputElement, form?: DetectedForm | null) {
    const found = roleOf(input, form);
    if (found === null) {
      if (ui.iconTarget === input) ui.hideIcon();
      return;
    }
    lastField = input;
    // Read on the first field with a role; the read evaluates it again.
    if (options === null) {
      void readOptions();
      return;
    }
    if (!options.inlineMenu || fieldExposure(input, layout) !== null) {
      ui.hideIcon();
      return;
    }
    ui.showIcon(input);
  }

  doc.addEventListener(
    "focusin",
    (event) => {
      // A closed shadow root shows up as its host; look inside where reachable.
      const input = inputOf(event.composedPath()[0]) ?? inputOf(deepActiveElement());
      if (input === null) return;
      const form = detectFormOf(input, detect);
      // A password step may show without a change the watcher sees, as by a
      // style. Not as a fill moves focus: it asks again once answered. Its
      // reads go before the icon's writes.
      if (input.type === "password" && !filling) passwordSteps.focused(input, form);
      evaluate(input, form);
      if (lastField === input) focusedAt = Date.now();
      // A shadow root attached after its host was added makes no mutation
      // record: take in the roots around the field.
      watcher.adopt(input);
    },
    { capture: true, signal },
  );
  doc.addEventListener(
    "focusout",
    () => {
      // After focus settles. A window losing focus (to Keyguard's prompt) keeps
      // the UI; focus moving into the menu lands on the host.
      setTimeout(() => {
        if (signal.aborted || !doc.hasFocus()) return;
        const active = deepActiveElement();
        if (active === ui.host) return;
        if (active !== ui.iconTarget) ui.hideIcon();
        if (active !== ui.menuField) ui.closeMenu();
      }, 0);
    },
    { capture: true, signal },
  );

  function toggleMenu(field: HTMLInputElement) {
    if (ui.menuField === field) ui.closeMenu();
    else openMenu(field, false);
  }

  /** Opens the menu of [field], with focus in it for [keyboard]. False when the field gets none. */
  function openMenu(field: HTMLInputElement, keyboard: boolean): boolean {
    const found = roleOf(field);
    if (found === null || !options?.inlineMenu || fieldExposure(field, layout) !== null) return false;
    void requestMenu(field, found, keyboard);
    return true;
  }

  async function requestMenu(
    field: HTMLInputElement,
    { form, role }: { form: DetectedForm; role: FieldRole },
    keyboard: boolean,
  ) {
    const result = await send<MenuOpenResult>({ kind: "menu_open", role, webauthn: form.webauthn.includes(field), url: location.href });
    if (result === undefined || !("token" in result) || signal.aborted || !field.isConnected) return;
    ui.openMenu(field, result.token, MENU_PAGE);
    topLayer.start();
    if (keyboard) ui.focusMenu();
  }

  // endregion

  // region Fills

  function bestPageForm(): DetectedForm | null {
    const recent = lastField?.isConnected ? (roleOf(lastField)?.form ?? null) : null;
    return pageForm(recent, () => detectForms(doc, detect), canFill);
  }

  function targetForm(command: FillCommand): { form: DetectedForm; field: HTMLInputElement | null; report?: StepReport } | null {
    switch (command.target) {
      case "menu":
      case "focused": {
        const field =
          command.target === "menu" ? ui.menuField : (inputOf(deepActiveElement()) ?? (lastField?.isConnected ? lastField : null));
        const form = field === null ? null : roleOf(field)?.form;
        return form ? { form, field } : null;
      }
      case "page": {
        const form = bestPageForm();
        return form === null ? null : { form, field: null };
      }
      case "step": {
        // The field of that report, once, if it's still one: the page may have changed since.
        if (command.kind !== "fill" || typeof command.step !== "string") return null;
        const report = passwordSteps.take(command.step);
        if (report === undefined) return null;
        const form = passwordSteps.formOf(report);
        return form === null ? null : { form, field: report.field, report };
      }
    }
  }

  /** The sign-in after the last login fill: the next fill, or the end of this script, cancels it. */
  let submitting: SignInWatch | null = null;
  /** Whether a fill is running: it moves focus. */
  let filling = false;
  const submitOptions: SubmitOptions = { ...detect, layout, canFill, filledValue };

  /**
   * A fill, and what follows its answer: the sign-in when the command asks,
   * and after the username alone of a sign-in, a look for its password step.
   */
  async function fill(command: FillCommand): Promise<{ result: DocumentResult; after?: () => void }> {
    // A login fill: the look for a password step after it takes those found since it began.
    const since = command.kind === "fill" ? passwordSteps.beginFill() : Infinity;
    submitting?.stop();
    submitting = null;
    const found = targetForm(command);
    if (found === null) return { result: { ok: false, reason: "no_form" } };
    const { form, field, report } = found;
    // Watched from before the first field is written: the page's handlers
    // of the fill's events may sign in on their own, and then nothing is clicked.
    const watch = command.kind === "fill" && command.submit === true ? watchSignIn(form.root) : null;
    submitting = watch;
    let values: LoginValues = {};
    let fields: HTMLInputElement[] = [];
    try {
      switch (command.kind) {
        case "fill":
          // A password step's username is the one on the page. Its field must
          // still be the step the user sees, up to the moment it's written.
          values = report ? { password: command.password } : { username: command.username, password: command.password };
          fields = await fillLogin(form, values, report ? (input) => input === report.field && passwordSteps.formOf(report) !== null : canFill);
          for (const input of fields) filled.set(input, input.value);
          break;
        case "fill_totp":
          fields = await fillOtp(form, command.code, canFill);
          break;
        case "fill_password":
          fields = await fillNewPassword(form, command.password, canFill);
          // A password field the detector didn't take as new still gets the value.
          if (fields.length === 0 && field !== null && field.type === "password" && canFill(field)) {
            fields = await fillNewPassword({ ...form, newPasswords: [field] }, command.password, canFill);
          }
          // The user asked for it: offer to save it like a typed one.
          for (const input of fields) tracker.track(input);
          break;
      }
    } finally {
      if (fields.length === 0) watch?.stop();
    }
    if (fields.length === 0) return { result: { ok: false, reason: "obscured" } };
    ui.closeMenu();
    if (command.kind !== "fill") return { result: { ok: true } };
    const wrote = (input: HTMLInputElement | null) => input !== null && fields.includes(input);
    // The username alone of a username step, or of a sign-in form whose password it couldn't write.
    const arms = wrote(form.username) && !wrote(form.currentPassword) && (form.kind === "username" || form.kind === "login");
    return {
      result: arms ? { ok: true, passwordStep: true } : { ok: true },
      after: () => {
        // Once the background took this answer, it may fill a password step
        // it refused before, as one a single-page app showed meanwhile.
        if (arms) setTimeout(() => passwordSteps.rescan(since), DEBOUNCE_MS);
        if (watch !== null) {
          const step = report === undefined ? {} : { step: { account: report.account } };
          void submitAfterFill({ form, written: fields, values, ...step }, submitOptions, signal, watch);
        }
      },
    };
  }

  /** Firefox's `runtime.getFrameId`; Chromium has IntersectionObserver v2 instead. */
  const runtime = browser.runtime as unknown as { getFrameId?(target: Element | Window): number };
  const frameIdOf = typeof runtime.getFrameId === "function" ? (target: Element | Window) => runtime.getFrameId!(target) : null;

  /**
   * Checks that the same-origin ancestors show this frame at [point] of its
   * viewport, each in its parent, see [frameExposure]: a page can fade or
   * cover the frame that holds the menu. A parent of another origin above
   * them is named in `parent`, with the point in the viewport of the frame it
   * holds, for the background to ask its content script: on Chromium the
   * menu's click gate sees it (../../src/lib/ui/clickGate.ts) and nothing is
   * named. A frame that can't be named fails closed.
   */
  function checkAncestors(point: { x: number; y: number }): DocumentResult {
    const levels = ancestors();
    if (levels === null) return { ok: false, reason: "obscured" };
    let at: { x: number; y: number } | null = point;
    for (const { host: frame, doc: parent, layout } of levels) {
      at = frameExposure(frame, at, layout, parent);
      if (at === null) return { ok: false, reason: "obscured" };
    }
    // The outermost window of this origin: its parent, if any, is of another.
    const outer = levels.at(-1)?.doc.defaultView ?? window;
    if (outer === outer.top || tracksVisibility(window)) return { ok: true };
    let frame = -1;
    try {
      frame = frameIdOf?.(outer) ?? -1;
    } catch {
      // A window the browser doesn't name.
    }
    return frame > 0 ? { ok: true, parent: { frame, x: at.x, y: at.y } } : { ok: false, reason: "obscured" };
  }

  /**
   * The clickjacking checks before the background acts on a click in the menu
   * or the save bar, in this frame and in its same-origin ancestors. A page
   * element in the top layer within the click gate's wait counts as one there
   * now. With the frame's URL now, which a single-page app may have changed
   * since the menu opened.
   */
  function guard(popup: GuardedPopup): DocumentResult {
    const shown = popup === "save_bar" ? ui.saveBarElement : ui.menuElement;
    const field = popup === "save_bar" ? null : ui.menuField;
    if (shown === null || (popup === "menu" && field === null)) return { ok: false, reason: "obscured" };
    const refusal = (field === null ? null : fieldExposure(field, guardLayout)) ?? popupExposure(ui.host, shown, guardLayout, doc);
    if (refusal !== null) return { ok: false, reason: refusal };
    if (topLayer.seenWithin(MIN_VISIBLE_MS)) return { ok: false, reason: "obscured" };
    const result = checkAncestors(popupCenter(shown, guardLayout));
    return result.ok ? { ...result, url: location.href } : result;
  }

  /**
   * The checks of a child frame of another origin, `frame_guard`: whether this
   * document shows its frame element [frame] at [point] of the child's
   * viewport, then this frame through its own ancestors.
   */
  function frameGuard(frame: Element, point: { x: number; y: number }): DocumentResult {
    const at = frameExposure(frame, point, guardLayout, doc);
    return at === null ? { ok: false, reason: "obscured" } : checkAncestors(at);
  }

  // endregion

  // region The password step

  const passwordSteps = new PasswordStepReporter({
    ...detect,
    doc,
    layout,
    canFill,
    filledValue,
    isTop,
    off: () => options?.fillPasswordStep === false,
    // The options are read once a form is one.
    send: (request) =>
      readOptions().then(async () => {
        if (!signal.aborted && options?.fillPasswordStep) await send(request);
      }),
    signal,
  });
  passwordSteps.listen();

  // endregion

  // region Save

  const tracker = new SaveTracker({
    ...detect,
    filledValue,
    // The options are read once a field with a role had focus; else now.
    report: (candidate) =>
      void readOptions().then(() => {
        if (options?.savePrompts) void send({ kind: "save_candidate", candidate });
      }),
    reportUsername: (username) =>
      void readOptions().then(() => {
        if (options?.savePrompts) void send({ kind: "username_step", username });
      }),
  });
  tracker.listen(doc, signal);
  // Only what was submitted, or typed in a form the user clicked in or pressed Enter in just before.
  window.addEventListener("pagehide", () => tracker.flush(), { capture: true, signal });

  async function openSaveBar() {
    if (!isTop) return;
    const result = await send<UiToken | null>({ kind: "save_bar_open" });
    if (!result || signal.aborted) return;
    watchFrameTheme();
    ui.openSaveBar(result.token, SAVE_BAR_PAGE);
    topLayer.start();
  }

  // endregion

  // region Dynamic pages

  // Listens in shadow roots as they're found. After DOM changes, forgets
  // removed fields; after changes to inputs or labels, re-checks the focused
  // one: its form may have changed.
  const watcher = new PageWatcher({
    shadowRootOf,
    found: (root) => {
      tracker.listen(root, signal);
      topLayer.adopt(root);
    },
    changed: (inputs, seen) => {
      // The changes may have moved the field with the icon or the menu.
      ui.pageChanged();
      tracker.prune();
      if (lastField !== null && !lastField.isConnected) lastField = null;
      // The password steps' reads go before the icon's writes.
      if (inputs) passwordSteps.report(seen);
      const active = inputs ? inputOf(deepActiveElement()) : null;
      if (active !== null) evaluate(active);
      else if (ui.iconTarget && !ui.iconTarget.isConnected) ui.hideIcon();
    },
    debounceMs: DEBOUNCE_MS,
  });
  signal.addEventListener("abort", () => watcher.stop());
  watcher.start(doc);

  // endregion

  // region Messages from the background

  const onMessage = (message: unknown, sender: { id?: string }, sendResponse: (response?: unknown) => void) => {
    if (sender.id !== browser.runtime.id || !isObject(message)) return false;
    const command = message as ContentCommand;
    // A command for one document and origin: a frame that navigated since gets
    // nothing, and one that expects an answer gets `origin`.
    const forDocument = documentCommandOf(command.kind);
    if (forDocument !== null && (message.doc !== documentToken || message.origin !== frameOrigin())) {
      if (forDocument === "answer") sendResponse({ ok: false, reason: "origin" } satisfies DocumentResult);
      return false;
    }
    switch (command.kind) {
      case "fill":
      case "fill_totp":
      case "fill_password":
        // Answers once the fields are filled; `true` keeps the channel open till then.
        filling = true;
        fill(command)
          .finally(() => (filling = false))
          .then(
            ({ result, after }) => {
              sendResponse(result);
              // Only then: a sign-in may navigate, and the answer would go with the page.
              after?.();
            },
            () => sendResponse(null),
          );
        return true;
      case "guard":
        sendResponse(guard(command.popup === "save_bar" ? "save_bar" : "menu"));
        return false;
      case "menu_resize":
        if (typeof command.height === "number") ui.resizeMenu(command.height);
        return false;
      case "menu_close":
        ui.closeMenu();
        return false;
      case "save_bar_show":
        void openSaveBar();
        return false;
      case "save_bar_resize":
        if (typeof command.height === "number") ui.resizeSaveBar(command.height);
        return false;
      case "save_bar_close":
        ui.closeSaveBar();
        return false;
      case "focus_probe": {
        const active = deepActiveElement();
        // `hasFocus()` holds for every frame on the way to the focused one; the
        // focused frame is the one whose active element isn't a frame.
        if (doc.hasFocus() && !(active instanceof Element && /^(iframe|frame|object|embed)$/.test(active.localName))) {
          const input = inputOf(active);
          void send({ kind: "focus_report", probe: command.probe, role: input === null ? null : (roleOf(input)?.role ?? null) });
        }
        return false;
      }
      case "form_probe": {
        // The popup's fill picks among the frames with a form; see PageController.fillTab.
        const form = bestPageForm();
        if (form !== null) {
          void send({ kind: "form_report", probe: command.probe, focused_at: focusedAt, writable: hasWritableLogin(form) });
        }
        sendResponse(null);
        return false;
      }
      case "top_probe":
        // A message of its own, not an answer: the background reads this frame's origin from its sender.
        void send({ kind: "top_report", probe: command.probe });
        return false;
      case "frame_guard": {
        // Only the document that holds the frame answers, with a message of its own.
        const check = parseFrameCheck(command);
        const frame = check === null || frameIdOf === null ? null : frameElementOf(doc, ui.host, guardLayout, check.frame, frameIdOf);
        if (check === null || frame === null) return false;
        const result = frameGuard(frame, check);
        void send({ kind: "frame_report", probe: command.probe, ok: result.ok, ...(result.ok && result.parent ? { parent: result.parent } : {}) });
        return false;
      }
      default:
        return false;
    }
  };
  browser.runtime.onMessage.addListener(onMessage);
  signal.addEventListener("abort", () => browser.runtime.onMessage.removeListener(onMessage));

  // endregion

  // A save bar of the page before a navigation shows on the next one.
  void openSaveBar();
}
