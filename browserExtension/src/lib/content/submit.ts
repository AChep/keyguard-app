// Signs in after a login fill, when the background asks ("Sign in after
// filling"): selects the form's sign-in or next button, once the fill was
// answered. Only on a sign-in form whose every login field the user could
// type in holds what the fill wrote. Nothing is clicked that the page
// doesn't show or keeps disabled, or that looks like it does something else:
// a password reset, a sign-up, a step back, a field's own toggle, a passkey,
// a third-party sign-in, or a change to the account, as a form that asks for
// the password to delete it. When it can't tell, it doesn't submit; nor once
// the user acts, the form is submitted another way, or the page hides.
// A click of the content script isn't trusted: the save tracker
// (./save.ts) offers nothing for it.
import {
  collectElements,
  detectFormOf,
  formInputs,
  isElement,
  type DetectedForm,
  type DetectOptions,
  type FormKind,
  type FormRoot,
} from "./detect";
import { shownAccount, type CanFill, type FilledValue, type LoginValues } from "./fill";
import { isWithin, parentOf, shownStyle, transparency, type Layout } from "./guards";
import { isFormSubmitter, textStart } from "./save";

/** A submit waits this long after the fill: the page's frameworks take in the values first. */
export const SUBMIT_DELAY_MS = 150;
/** A control the page keeps disabled or faded gets this long to turn usable. */
export const SUBMIT_WAIT_MS = 1_000;
/** How often a control that isn't usable yet is checked again. */
const RETRY_MS = 100;

/** The forms a fill may sign in with: a login, or a step of one. */
const SUBMIT_KINDS: ReadonlySet<FormKind> = new Set<FormKind>(["login", "username", "password"]);

// Words of a control, lowercase, each alone and joined with the next: "Sign
// in", "signIn" and "sign_in" all give "signin". English only: elsewhere a
// form's only submit button, or the only control near the field, still counts.
/** Words of a control that signs in. */
const SIGN_IN_WORDS: ReadonlySet<string> = new Set(["signin", "login", "logon"]);
/** Words of a control that signs in or goes to the next step. */
const ACTION_WORDS: ReadonlySet<string> = new Set([...SIGN_IN_WORDS, "next", "continue", "submit"]);
/** Words of a control that does something else. */
const OTHER_WORDS: ReadonlySet<string> = new Set([
  "forgot",
  "reset",
  "signup",
  "register",
  "create",
  "cancel",
  "back",
  "close",
  "show",
  "hide",
  "reveal",
  "toggle",
  "visibility",
  "eye",
  "clear",
  "passkey",
  "passkeys",
  "google",
  "apple",
  "microsoft",
  "facebook",
  "github",
  "sso",
  // Changes to the account, payments and the like, as forms that ask for the password to confirm them.
  "delete",
  "remove",
  "deactivate",
  "disable",
  "change",
  "update",
  "save",
  "pay",
  "purchase",
  "buy",
  "order",
  "transfer",
  "send",
]);
/** Each text of a control read for its words, in characters. */
const HINT_TEXT_MAX = 100;
/** How many elements of `aria-labelledby` are read for a control's words. */
const LABELLED_BY_MAX = 4;
/** The fields that keep Enter from submitting a form without a submit button when it has more than one. */
const BLOCKS_ENTER = /^(text|search|url|tel|email|password|date|month|week|time|datetime-local|number)$/;

export interface SubmitOptions extends DetectOptions {
  layout: Pick<Layout, "rendered" | "style" | "shadowRoot">;
  canFill: CanFill;
  /** The value Keyguard filled into a field, if any. */
  filledValue: FilledValue;
}

/** A login fill to sign in after. */
export interface LoginFill {
  form: DetectedForm;
  /** The fields the fill wrote. */
  written: readonly HTMLInputElement[];
  /** The values it wrote. */
  values: LoginValues;
  /**
   * Only on the password step: its username is the one a fill wrote before,
   * or `account`, the one its form showed when the content script reported
   * it, or "": the one the background checked.
   */
  step?: { account: string };
}

/** What a submit does now: click a control, submit a form that shows none, wait for a control to turn usable, or nothing. */
type SubmitAction =
  { kind: "click"; control: HTMLElement } | { kind: "request"; form: HTMLFormElement } | { kind: "wait" } | null;

/**
 * The fields of [form], [fill]'s or the one detected again since, that must
 * hold what Keyguard wrote for a sign-in to follow [fill], with those values:
 * the fields it wrote, with what it wrote, and each other login field the
 * user could type in. On the password step, that's its username as a fill
 * wrote it before, or as the step showed it, see [LoginFill.step]; on any
 * other step, nothing does. `null` when [form] isn't a sign-in form, a field
 * the fill wrote isn't one of its login fields, or a login field the user
 * could type in holds no value of these fills, as a username the login has
 * none for.
 */
export function filledLogin(
  fill: LoginFill,
  options: SubmitOptions,
  form = fill.form,
): Map<HTMLInputElement, string> | null {
  const { step } = fill;
  const logins = [form.username, form.currentPassword];
  if (!SUBMIT_KINDS.has(form.kind) || fill.written.length === 0) return null;
  const fields = new Map<HTMLInputElement, string>();
  for (const input of fill.written) {
    const value =
      input === form.username
        ? fill.values.username
        : input === form.currentPassword
          ? fill.values.password
          : undefined;
    if (value === undefined) return null;
    fields.set(input, value);
  }
  for (const field of logins) {
    if (field === null || fields.has(field) || field.readOnly || !options.canFill(field)) continue;
    const account = step !== undefined && field === form.username && step.account !== "" ? step.account : undefined;
    const before = step === undefined ? undefined : (options.filledValue(field) ?? account);
    if (before === undefined) return null;
    fields.set(field, before);
  }
  return fields;
}

function holdsFill(fields: ReadonlyMap<HTMLInputElement, string>): boolean {
  return [...fields].every(([field, value]) => field.isConnected && field.value === value);
}

/**
 * What signs in after [fill] now, in a tab the user sees: a tab hidden
 * before the watch began gets no `visibilitychange`. The page may have
 * changed the form since: the form of a field the fill wrote, detected
 * again, must still be its form, of the same kind, with the fields of
 * [filledLogin] holding their values, and on the password step its
 * account, read-only or not; then see [submitAction].
 */
function attempt(fill: LoginFill, options: SubmitOptions): SubmitAction {
  const [first] = fill.written;
  if (first === undefined || !fill.written.every((input) => input.isConnected)) return null;
  if (first.ownerDocument.visibilityState !== "visible") return null;
  const form = detectFormOf(first, options);
  if (form === null || form.root !== fill.form.root || form.kind !== fill.form.kind) return null;
  if (fill.step !== undefined && shownAccount(form) !== fill.step.account) return null;
  const fields = filledLogin(fill, options, form);
  return fields !== null && holdsFill(fields)
    ? submitAction(form, [...fields.keys()], options, fill.step !== undefined)
    : null;
}

/**
 * What signs in [form] now, whose [fields] were filled, see [filledLogin]:
 * - in a `<form>`, the submit button the page shows, but those that look
 *   like they do something else, see [doesOther]: the only one, or the only
 *   one with ACTION_WORDS. One that has submit buttons, none shown, isn't
 *   submitted. One that has none is submitted as by Enter, with its handlers
 *   and validation, where Enter would submit it, and never by GET with a
 *   password, which would put it in the URL;
 * - else, the controls of the nearest ancestor of [fields] that has any,
 *   across shadow roots and up to the `<form>` of the fields, if any: the
 *   only one, or the only one with a hint that it submits, see
 *   [hasSubmitHint]. A field's own controls, in the parent that holds it
 *   alone, as its eye or clear button, count for their hint only.
 * A form of the password alone may be about something else than signing in,
 * as deleting the account: but on the password step ([step]), only a control
 * whose label says it signs in does, see [saysSignIn]. The control waits
 * while it's disabled or faded.
 */
function submitAction(
  form: DetectedForm,
  fields: readonly HTMLInputElement[],
  options: SubmitOptions,
  step: boolean,
): SubmitAction {
  const signIn = step || form.kind !== "password";
  const owner = isElement(form.root) ? form.root : null;
  // Each control is read once: it comes up again at each ancestor of the fields.
  const words = once(readWords);
  const qualifies = once((control: HTMLElement) => mayTake(control, owner, options, words));
  const take = (control: HTMLElement) => (signIn || saysSignIn(words(control)) ? readiness(control, options) : null);
  if (owner !== null) {
    // With those outside it that name it in their `form` attribute.
    const submitters = Array.from(owner.elements).filter(isFormSubmitter);
    const shown = submitters.filter((control) => shownStyle(control, options.layout) !== null);
    if (shown.length > 0) {
      const eligible = shown.filter((control) => !doesOther(control, words(control)));
      const hinted = eligible.filter((control) => hasSignInHint(words(control)));
      const control = eligible.length === 1 ? eligible[0] : hinted.length === 1 ? hinted[0] : undefined;
      return control === undefined ? null : take(control);
    }
    // A hidden submit button may name another method or action than the form's.
    if (submitters.length > 0) return null;
    // Enter submits a form of one field to type in. Else it does nothing: as
    // with a sign-in button the page's script handles, which is looked for
    // as outside a form.
    const inputs = formInputs(owner);
    const enter = inputs.filter((input) => BLOCKS_ENTER.test(input.type)).length <= 1;
    const leaks = owner.method === "get" && inputs.some((input) => input.type === "password");
    if (enter && !leaks) return signIn ? { kind: "request", form: owner } : null;
  }
  const wrappers = ownWrappers(fields);
  // The only control near the fields is taken whatever its words, but not past a field's own.
  let lone = true;
  for (
    let node = commonAncestor(fields);
    node !== null && (owner === null || isWithin(node, owner));
    node = parentOf(node)
  ) {
    const controls = controlsIn(node, options).filter(qualifies);
    if (controls.length === 0) continue;
    const own = controls.filter((control) => wrappers.some((wrapper) => isWithin(control, wrapper)));
    const hinted = controls.filter((control) => hasSubmitHint(control, words(control)));
    if (lone && own.length === 0 && controls.length === 1) return take(controls[0]!);
    if (hinted.length === 1) return take(hinted[0]!);
    if (hinted.length > 0 || own.length < controls.length) return null;
    lone = false;
  }
  return null;
}

/**
 * Signs in after [fill], once: after SUBMIT_DELAY_MS, as [attempt] tells,
 * if the fields still hold what it wrote. A control that isn't usable yet
 * gets SUBMIT_WAIT_MS. [signal] cancels it, and so does a sign-in another
 * way that [watch] saw, begun before the fill wrote its first field: the
 * page's handlers of the fill's events may sign in on their own. Resolves
 * with whether it submitted; [watch] ends with it.
 */
export async function submitAfterFill(
  fill: LoginFill,
  options: SubmitOptions,
  signal: AbortSignal,
  watch: SignInWatch,
): Promise<boolean> {
  const stop = AbortSignal.any([signal, watch.signal]);
  try {
    if (filledLogin(fill, options) === null || !(await pause(SUBMIT_DELAY_MS, stop))) return false;
    for (let waited = 0; ; waited += RETRY_MS) {
      const action = attempt(fill, options);
      if (action?.kind === "click") {
        action.control.click();
        return true;
      }
      if (action?.kind === "request") return requestSubmit(action.form);
      if (action === null || waited >= SUBMIT_WAIT_MS || !(await pause(RETRY_MS, stop))) return false;
    }
  } finally {
    watch.stop();
  }
}

/** A watch for a sign-in another way, see [watchSignIn]. */
export interface SignInWatch {
  /** Aborts once the user or the page may be signing in already, or the watch stopped. */
  readonly signal: AbortSignal;
  /** Ends the watch, and the sign-in that waits on it. */
  stop(): void;
}

/**
 * Watches for the user or the page signing in already: the form [root] is
 * submitted, a trusted click or Enter lands in it, a trusted click lands on
 * a control of it outside it, or the page hides or goes away. The fill's own
 * events aren't trusted, but the page's handlers of them may submit it.
 */
export function watchSignIn(root: FormRoot): SignInWatch {
  const watch = new AbortController();
  const stop = () => watch.abort();
  const doc = root.nodeType === Node.DOCUMENT_NODE ? (root as Document) : root.ownerDocument!;
  const options = { capture: true, signal: watch.signal };
  root.addEventListener("submit", stop, options);
  root.addEventListener("click", (event) => event.isTrusted && stop(), options);
  root.addEventListener(
    "keydown",
    (event) => event.isTrusted && (event as KeyboardEvent).key === "Enter" && stop(),
    options,
  );
  if (isElement(root)) {
    // A control that names the form in its `form` attribute.
    const owned = (node: EventTarget) =>
      (node as Node).nodeType === Node.ELEMENT_NODE && (node as HTMLButtonElement).form === root;
    doc.addEventListener("click", (event) => event.isTrusted && event.composedPath().some(owned) && stop(), options);
  }
  doc.addEventListener("visibilitychange", () => doc.visibilityState === "hidden" && stop(), options);
  doc.defaultView?.addEventListener("pagehide", stop, options);
  return { signal: watch.signal, stop };
}

/** `form.requestSubmit()`: never `submit()`, which skips the page's handlers and validation. */
function requestSubmit(form: HTMLFormElement): boolean {
  try {
    form.requestSubmit();
    return true;
  } catch {
    // It left the page.
    return false;
  }
}

/** Waits [ms]; false once [signal] aborts. */
function pause(ms: number, signal: AbortSignal): Promise<boolean> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve(false);
    const abort = () => {
      clearTimeout(timer);
      resolve(false);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve(true);
    }, ms);
    signal.addEventListener("abort", abort, { once: true });
  });
}

/** Whether an element outside a form may sign in when clicked: a button, a button input or a `[role=button]`. */
function isControl(element: Element): element is HTMLElement {
  switch (element.localName) {
    case "button":
      return (element as HTMLButtonElement).type !== "reset";
    case "input":
      return /^(submit|image|button)$/.test((element as HTMLInputElement).type);
    default:
      return element.getAttribute("role") === "button" && typeof (element as HTMLElement).click === "function";
  }
}

/** The nearest element that holds all [fields], across shadow roots. */
function commonAncestor(fields: readonly HTMLInputElement[]): Element | null {
  const [first, ...rest] = fields;
  for (let node = first === undefined ? null : parentOf(first); node !== null; node = parentOf(node)) {
    if (rest.every((field) => isWithin(field, node))) return node;
  }
  return null;
}

/** The parent of each of [fields] that holds no other: the field's own wrapper, with its eye or clear button. */
function ownWrappers(fields: readonly HTMLInputElement[]): Element[] {
  return fields.flatMap((field) => {
    const parent = parentOf(field);
    return parent !== null && fields.every((other) => other === field || !isWithin(other, parent)) ? [parent] : [];
  });
}

/** The controls under [node] and in its own shadow root, see [isControl]. */
function controlsIn(node: Element, options: SubmitOptions): HTMLElement[] {
  const shadow = options.shadowRootOf?.(node) ?? node.shadowRoot;
  return [
    ...collectElements(node, isControl, options),
    ...(shadow === null ? [] : collectElements(shadow, isControl, options)),
  ];
}

/**
 * Whether [control], outside a `<form>` or in [owner], may sign in [owner],
 * the `<form>` of the fields, or a form of fields outside any: the page
 * shows it, with a label, see [ControlWords.label], or a hint that it
 * submits, see [hasSubmitHint]; it doesn't look like it does something
 * else, and isn't of another form. A generated class alone, as an icon
 * button's, isn't a label.
 */
function mayTake(
  control: HTMLElement,
  owner: HTMLFormElement | null,
  options: SubmitOptions,
  words: (control: Element) => ControlWords,
): boolean {
  // A `[role=button]` has no form owner: the `<form>` around it is its form.
  const form = "form" in control ? (control.form as HTMLFormElement | null) : control.closest("form");
  if ((form !== null && form !== owner) || shownStyle(control, options.layout) === null) return false;
  const read = words(control);
  return !doesOther(control, read) && (hasSubmitHint(control, read) || read.label.size > 0);
}

/** A click of [control] once it's usable: fully opaque and enabled; till then, a wait. */
function readiness(control: HTMLElement, options: SubmitOptions): SubmitAction {
  const usable = control.isConnected && transparency(control, options.layout) === null && isEnabled(control);
  return usable ? { kind: "click", control } : { kind: "wait" };
}

/** Whether [control] takes clicks: not disabled, by itself, by ARIA, or by a disabled fieldset around it. */
function isEnabled(control: Element): boolean {
  return (
    !control.hasAttribute("disabled") &&
    control.getAttribute("aria-disabled")?.trim().toLowerCase() !== "true" &&
    control.closest("fieldset[disabled]") === null
  );
}

/** Whether [control] says it's of type `submit`, outside a form a hint only. */
function isSubmitType(control: Element): boolean {
  return control.getAttribute("type")?.trim().toLowerCase() === "submit";
}

/** Whether [control] says it submits: by `type=submit`, or by ACTION_WORDS. */
function hasSubmitHint(control: Element, words: ControlWords): boolean {
  return isSubmitType(control) || hasSignInHint(words);
}

/** Whether a control says it signs in or goes to the next step, by ACTION_WORDS. */
function hasSignInHint(words: ControlWords): boolean {
  return hasAny(words.all, ACTION_WORDS);
}

/**
 * Whether a control's label says it signs in, by SIGN_IN_WORDS: not only
 * that it goes on or submits, nor by its id, name or classes, as a button
 * "Confirm" named `submit` that deletes the account.
 */
function saysSignIn(words: ControlWords): boolean {
  return hasAny(words.label, SIGN_IN_WORDS);
}

/** Whether [control] looks like it does something else than sign in: a toggle, a menu, or one with OTHER_WORDS. */
function doesOther(control: Element, words: ControlWords): boolean {
  if (control.hasAttribute("aria-pressed") || control.hasAttribute("aria-expanded")) return true;
  return hasAny(words.all, OTHER_WORDS);
}

/** Whether [words] has one of [set]. */
function hasAny(words: ReadonlySet<string>, set: ReadonlySet<string>): boolean {
  for (const word of words) if (set.has(word)) return true;
  return false;
}

/** The words of a control, see [wordsIn]. */
interface ControlWords {
  /** Of its label: its text, value, ARIA label, the elements that label it, and title. */
  label: ReadonlySet<string>;
  /** Of its label, id, name and classes. */
  all: ReadonlySet<string>;
}

function readWords(control: Element): ControlWords {
  const label = wordsIn(labelTexts(control));
  return {
    label,
    all: new Set([...label, ...wordsIn([control.id, control.getAttribute("name"), control.getAttribute("class")])]),
  };
}

/** [read], once per argument. */
function once<K, V>(read: (key: K) => V): (key: K) => V {
  const values = new Map<K, V>();
  return (key) => {
    if (!values.has(key)) values.set(key, read(key));
    return values.get(key)!;
  };
}

function labelTexts(control: Element): (string | null)[] {
  const root = control.getRootNode() as Document | ShadowRoot;
  const labelledBy = (control.getAttribute("aria-labelledby") ?? "")
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, LABELLED_BY_MAX)
    .map((id) => {
      const label = root.getElementById?.(id);
      return label ? textStart(label) : null;
    });
  return [
    textStart(control),
    control.getAttribute("value"),
    control.getAttribute("aria-label"),
    ...labelledBy,
    control.getAttribute("title"),
  ];
}

function wordsIn(texts: readonly (string | null)[]): Set<string> {
  return new Set(texts.flatMap((text) => wordsOf((text ?? "").slice(0, HINT_TEXT_MAX))));
}

/** The lowercase words of [text], split at camelCase humps and at anything but letters and digits; each also joined with the next. */
function wordsOf(text: string): string[] {
  const words = text
    .replace(/(\p{Ll})(\p{Lu})/gu, "$1 $2")
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
  return [...words, ...words.slice(1).map((word, i) => words[i] + word)];
}
