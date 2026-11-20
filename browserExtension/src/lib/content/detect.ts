// Finds login, sign-up, change-password and one-time-password forms, and the
// role of each field in them. Pure DOM reads: no extension APIs and no layout,
// except through [DetectOptions.rendered].
//
// Signals, strongest first:
// 1. `autocomplete` tokens: `username`, `email`, `current-password`,
//    `new-password`, `one-time-code`, and `webauthn`.
// 2. The input type: `password`, `email`, `tel`, `number`.
// 3. Words in the name, id, labels, `aria-label` and placeholder, or else in
//    the text right before the field.
// 4. The order of fields: the last text field before a password is its username.
//
// Fields a page opts out of password managers, on the field or its form, are
// skipped. Read-only ones count: pages lift `readonly` on focus to keep
// autofill out, and fills check again then (./fill.ts).
import type { FieldRole } from "../page/messages";

export type { FieldRole };

export type FormKind =
  /** A username (optional) and the current password. */
  | "login"
  /** The username step of a username-first sign-in. */
  | "username"
  /** The password step of a username-first sign-in. */
  | "password"
  /** A sign-up form: a new password, often with a confirmation. */
  | "new-password"
  /** The current password and a new one. */
  | "change-password"
  | "otp";

/** A form, or the inputs of one document or shadow root outside any form. */
export type FormRoot = HTMLFormElement | Document | ShadowRoot;

export interface DetectedForm {
  kind: FormKind;
  root: FormRoot;
  username: HTMLInputElement | null;
  currentPassword: HTMLInputElement | null;
  /** The new password and its confirmations. */
  newPasswords: HTMLInputElement[];
  /** One field, or one box per digit. */
  otp: HTMLInputElement[];
  /** Fields with the `webauthn` autocomplete token, for passkeys. */
  webauthn: HTMLInputElement[];
}

export interface DetectOptions {
  /** Whether the input has a box. Defaults to `getClientRects().length > 0`. */
  rendered?(input: HTMLInputElement): boolean;
  /** The shadow root of a host: open or, where the browser lets extensions, closed. */
  shadowRootOf?(element: Element): ShadowRoot | null;
  /** An element to skip with its subtree: the extension's own UI. */
  ignore?: Element | null;
}

const TEXT_TYPES: ReadonlySet<string> = new Set(["text", "email", "tel", "number", "password", ""]);
/** The built-in elements that can host a shadow root; custom elements can too. */
const SHADOW_HOSTS: ReadonlySet<string> = new Set([
  "article",
  "aside",
  "blockquote",
  "body",
  "div",
  "footer",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "header",
  "main",
  "nav",
  "p",
  "section",
  "span",
]);

// Matched against normalized text, where whitespace and `_` became `-`. The
// `(^|[^a-z])` prefix keeps "renew" and "golden" out; camelCase still matches.
const USERNAME_WORDS = /user|login|log-in|logon|e-?mail|account|identifier|sign-?in|phone|mobile/i;
const NEW_PASSWORD_WORDS =
  /(^|[^a-z])new|confirm|repeat|re-?type|re-?enter|again|verif|create|choose|sign-?up|regist|password-?2|passwd-?2|pass-?2|pwd-?2/i;
const CURRENT_PASSWORD_WORDS = /current|(^|[^a-z])old|existing/i;
const OTP_WORDS =
  /one-?time|(^|[^a-z])otp|totp|2fa|mfa|two-?factor|verification-?code|verify-?code|auth(entication|enticator)?-?code|security-?code|passcode|sms-?code/i;
/** A name or id that is a code field only when it also looks numeric or short. */
const CODE_NAME = /^(code|token|pin)$/i;
/** Fields about something else than signing in. */
const IGNORED_WORDS = /search|captcha|coupon|promo|voucher|postal|zip-?code|newsletter|subscribe/i;
/**
 * Attributes that opt a field or form out of password managers: Keyguard's,
 * 1Password's, LastPass's, Bitwarden's and Proton Pass's. Any value but
 * "false" counts; React writes `data-lpignore={false}` as "false".
 */
const IGNORE_ATTRIBUTES = ["data-keyguard-ignore", "data-1p-ignore", "data-lpignore", "data-bwignore", "data-protonpass-ignore"];
/** Text before a field longer than this is prose, not its label. */
const NEARBY_TEXT_MAX = 64;
/** Nodes read for the text before a field: a label has few. */
const NEARBY_NODES_MAX = 32;
const CONTROLS: ReadonlySet<string> = new Set(["input", "select", "textarea", "button"]);
const UNSHOWN: ReadonlySet<string> = new Set(["script", "style", "template", "noscript"]);

/** The `autocomplete` tokens of an input, lowercase. */
export function autocompleteTokens(input: HTMLInputElement): string[] {
  return (input.getAttribute("autocomplete") ?? "").toLowerCase().trim().split(/\s+/).filter(Boolean);
}

type Hint = FieldRole | "email" | null;

/** The role that `autocomplete` gives, skipping `webauthn` and section tokens. */
function autocompleteHint(tokens: string[]): Hint {
  for (const token of tokens) {
    switch (token) {
      case "username":
        return "username";
      case "email":
        return "email";
      case "current-password":
        return "current-password";
      case "new-password":
        return "new-password";
      case "one-time-code":
        return "otp";
    }
  }
  return null;
}

function normalized(text: string | null | undefined): string {
  return (text ?? "").trim().replace(/[\s_]+/g, "-").slice(0, 200);
}

/**
 * Text that describes an input: its name, id, labels, ARIA label and
 * placeholder. Without a label, the text right before it, as in
 * `<div>Email</div><input>`.
 */
export function describe(input: HTMLInputElement): string {
  const parts: (string | null | undefined)[] = [input.name, input.id, input.getAttribute("aria-label"), input.placeholder];
  const labels = Array.from(input.labels ?? []);
  for (const label of labels) parts.push(label.textContent);
  const labelledBy = input.getAttribute("aria-labelledby");
  if (labelledBy) {
    const root = input.getRootNode() as Document | ShadowRoot;
    for (const id of labelledBy.split(/\s+/)) parts.push(root.getElementById?.(id)?.textContent);
  } else if (labels.length === 0) {
    parts.push(nearbyText(input));
  }
  return parts.map(normalized).filter(Boolean).join(" ");
}

/**
 * The shown text of a node before an input: "" when it has none, `null` when
 * it can't be the input's label: a field, a link, another field's label, or
 * more text than a label has.
 */
function labelText(node: Node): string | null {
  if (node.nodeType === Node.TEXT_NODE) return shortText(node.textContent ?? "");
  if (node.nodeType !== Node.ELEMENT_NODE) return "";
  const element = node as Element;
  if (UNSHOWN.has(element.localName) || element.hasAttribute("hidden")) return "";
  if (element.localName === "a" || (element.localName === "label" && (element as HTMLLabelElement).control !== null)) {
    return null;
  }
  let text = "";
  let count = 0;
  const walker = element.ownerDocument.createTreeWalker(element, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
  for (let current: Node | null = walker.currentNode; current !== null; current = walker.nextNode()) {
    if (++count > NEARBY_NODES_MAX) return null;
    if (current.nodeType === Node.TEXT_NODE) text += current.textContent ?? "";
    else if (isControl(current as Element)) return null;
  }
  return shortText(text);
}

/** [text] with its whitespace collapsed, or `null` when it's longer than a label. */
function shortText(text: string): string | null {
  const collapsed = text.replace(/\s+/g, " ").trim();
  return collapsed.length > NEARBY_TEXT_MAX ? null : collapsed;
}

function isControl(element: Element): boolean {
  return CONTROLS.has(element.localName) && (element as HTMLInputElement).type !== "hidden";
}

/**
 * The nearest text before an input in its parent, or before the wrapper that
 * holds the input alone. Never the input's own value or placeholder.
 */
function nearbyText(input: HTMLInputElement): string {
  let node: Element = input;
  for (let level = 0; level < 2; level++) {
    for (let sibling = node.previousSibling; sibling !== null; sibling = sibling.previousSibling) {
      const text = labelText(sibling);
      if (text === null) return "";
      if (text === "") continue;
      const own = [input.value, input.placeholder].map((it) => it.trim().toLowerCase());
      return own.includes(text.toLowerCase()) ? "" : text;
    }
    const parent = node.parentElement;
    if (parent === null || /^(body|html|form)$/.test(parent.localName) || !wrapsAlone(parent, input)) return "";
    node = parent;
  }
  return "";
}

/** Whether [wrapper] is small and holds no field but [input]. */
function wrapsAlone(wrapper: Element, input: HTMLInputElement): boolean {
  const walker = wrapper.ownerDocument.createTreeWalker(wrapper, NodeFilter.SHOW_ELEMENT);
  let count = 0;
  for (let current = walker.nextNode(); current !== null; current = walker.nextNode()) {
    if (++count > NEARBY_NODES_MAX || (current !== input && isControl(current as Element))) return false;
  }
  return true;
}

function isFillableType(input: HTMLInputElement): boolean {
  return TEXT_TYPES.has(input.type) && !input.disabled;
}

function optsOut(element: Element): boolean {
  return IGNORE_ATTRIBUTES.some((name) => {
    const value = element.getAttribute(name);
    return value !== null && value.trim().toLowerCase() !== "false";
  });
}

/** Whether the page opts a field out of password managers, on the field or its form. */
export function isIgnored(input: HTMLInputElement): boolean {
  return optsOut(input) || (input.form !== null && optsOut(input.form));
}

/** The writable ones of [inputs], or all when none is: a read-only field only stands in for a missing one. */
function preferWritable(inputs: HTMLInputElement[]): HTMLInputElement[] {
  const writable = inputs.filter((input) => !input.readOnly);
  return writable.length > 0 ? writable : inputs;
}

function defaultRendered(input: HTMLInputElement): boolean {
  return input.getClientRects().length > 0;
}

function isElement(node: FormRoot): node is HTMLFormElement {
  return node.nodeType === 1;
}

/** Whether a field is for a one-time code. */
function looksLikeOtp(input: HTMLInputElement, hint: Hint, words: string): boolean {
  if (hint === "otp") return true;
  if (hint !== null || input.type === "password" || input.type === "email") return false;
  if (OTP_WORDS.test(words)) return true;
  const numeric =
    input.inputMode === "numeric" || input.type === "number" || input.type === "tel" || /\\d|\[0-9\]/.test(input.pattern);
  const short = input.maxLength >= 4 && input.maxLength <= 10;
  return (CODE_NAME.test(input.name) || CODE_NAME.test(input.id)) && (numeric || short);
}

/**
 * Groups of 4 to 8 one-character boxes with a common parent (or grandparent,
 * as boxes are often wrapped once), as in a code entered digit by digit.
 */
function otpBoxes(inputs: HTMLInputElement[]): Set<HTMLInputElement> {
  const groups = new Map<Element | null, HTMLInputElement[]>();
  for (const input of inputs) {
    if (input.maxLength !== 1 || input.type === "password" || input.type === "email") continue;
    const parent = input.parentElement;
    const key = parent?.childElementCount === 1 ? parent.parentElement : parent;
    const group = groups.get(key ?? null) ?? [];
    group.push(input);
    groups.set(key ?? null, group);
  }
  const boxes = new Set<HTMLInputElement>();
  for (const group of groups.values()) {
    if (group.length >= 4 && group.length <= 8) for (const input of group) boxes.add(input);
  }
  return boxes;
}

/** Whether an element can have a shadow root: a custom element or one of [SHADOW_HOSTS]. */
export function canHostShadow(element: Element): boolean {
  return element.localName.includes("-") || SHADOW_HOSTS.has(element.localName);
}

/** [target] when it's an input. */
export function inputOf(target: EventTarget | null | undefined): HTMLInputElement | null {
  return target instanceof Element && target.localName === "input" ? (target as HTMLInputElement) : null;
}

/** The inputs of a form, with those outside it that name it in their `form` attribute. */
export function formInputs(form: HTMLFormElement): HTMLInputElement[] {
  return Array.from(form.elements).filter((element): element is HTMLInputElement => element.localName === "input");
}

/** All inputs under a root, through the shadow roots that [DetectOptions.shadowRootOf] reaches, in tree order. */
export function collectInputs(root: Document | ShadowRoot | Element, options: DetectOptions = {}): HTMLInputElement[] {
  const shadowRootOf = options.shadowRootOf ?? ((element: Element) => element.shadowRoot);
  const result: HTMLInputElement[] = [];
  const visit = (node: Document | ShadowRoot | Element) => {
    for (const element of Array.from(node.querySelectorAll("*"))) {
      if (element === options.ignore) continue;
      if (element.localName === "input") {
        result.push(element as HTMLInputElement);
        continue;
      }
      if (canHostShadow(element)) {
        const shadow = shadowRootOf(element);
        if (shadow) visit(shadow);
      }
    }
  };
  visit(root);
  return result;
}

/** The form an input belongs to: its form, or else its document or shadow root. */
export function formRootOf(input: HTMLInputElement): FormRoot {
  return input.form ?? (input.getRootNode() as Document | ShadowRoot);
}

/** Classifies the inputs of one form root, in tree order. */
export function classifyForm(root: FormRoot, inputs: HTMLInputElement[], options: DetectOptions = {}): DetectedForm | null {
  const rendered = options.rendered ?? defaultRendered;
  const fields = inputs.filter((input) => isFillableType(input) && !isIgnored(input) && rendered(input));
  if (fields.length === 0) return null;

  const tokens = new Map(fields.map((input) => [input, autocompleteTokens(input)]));
  const hints = new Map(fields.map((input) => [input, autocompleteHint(tokens.get(input)!)]));
  const words = new Map(fields.map((input) => [input, describe(input)]));
  const webauthn = fields.filter((input) => tokens.get(input)!.includes("webauthn"));
  const boxes = otpBoxes(fields);

  // Show/hide toggles turn a password field into a text field: keep it by its hint.
  const isPassword = (input: HTMLInputElement) =>
    input.type === "password" ||
    (input.type === "text" && (hints.get(input) === "current-password" || hints.get(input) === "new-password"));
  const isOtp = (input: HTMLInputElement) =>
    !isPassword(input) && (boxes.has(input) || looksLikeOtp(input, hints.get(input)!, words.get(input)!));
  const passwords = preferWritable(fields.filter(isPassword));
  const otp = preferWritable(fields.filter(isOtp));

  const isUsernameCandidate = (input: HTMLInputElement) =>
    !isPassword(input) && !isOtp(input) && input.type !== "number" && !IGNORED_WORDS.test(words.get(input)!);
  const strongUsername = (input: HTMLInputElement) =>
    hints.get(input) === "username" ||
    hints.get(input) === "email" ||
    tokens.get(input)!.includes("webauthn") ||
    USERNAME_WORDS.test(words.get(input)!);

  const form: DetectedForm = { kind: "login", root, username: null, currentPassword: null, newPasswords: [], otp, webauthn };

  if (passwords.length === 0) {
    if (otp.length > 0) return { ...form, kind: "otp" };
    // A read-only username without a password shows the account, as on a
    // profile page: not a step to fill.
    const candidates = fields.filter((input) => !input.readOnly && isUsernameCandidate(input));
    const username =
      candidates.find((input) => hints.get(input) === "username") ??
      candidates.find((input) => tokens.get(input)!.includes("webauthn")) ??
      candidates.find(strongUsername);
    if (username === undefined) return null;
    // A username step has the username and little else, unlike a long form
    // that happens to ask for an email.
    const explicit = hints.get(username) === "username" || tokens.get(username)!.includes("webauthn");
    if (candidates.length > 2 && !explicit) return null;
    return { ...form, kind: "username", username };
  }

  const roleOf = (input: HTMLInputElement): "current-password" | "new-password" | null => {
    const hint = hints.get(input);
    if (hint === "current-password" || hint === "new-password") return hint;
    const text = words.get(input)!;
    if (CURRENT_PASSWORD_WORDS.test(text)) return "current-password";
    if (NEW_PASSWORD_WORDS.test(text)) return "new-password";
    return null;
  };
  const roles = passwords.map(roleOf);

  if (passwords.length === 1) {
    if (roles[0] === "new-password") form.newPasswords = [passwords[0]!];
    else form.currentPassword = passwords[0]!;
  } else {
    const current = roles.indexOf("current-password");
    if (current >= 0) {
      form.currentPassword = passwords[current]!;
      form.newPasswords = passwords.filter((_, i) => i !== current);
    } else if (passwords.length >= 3) {
      // Current, new, confirmation.
      form.currentPassword = passwords[0]!;
      form.newPasswords = passwords.slice(1);
    } else {
      // A new password and its confirmation.
      form.newPasswords = passwords;
    }
  }

  // The username: by its hint, or else the last text field before the first
  // password. A read-only one, as the account shown on a password step, only
  // when no writable one is there.
  const firstPassword = passwords[0]!;
  const before = preferWritable(
    fields.filter(
      (input) =>
        isUsernameCandidate(input) &&
        (firstPassword.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_PRECEDING) !== 0,
    ),
  );
  form.username =
    before.find((input) => hints.get(input) === "username") ??
    before.find((input) => hints.get(input) === "email") ??
    before.at(-1) ??
    null;

  if (form.newPasswords.length > 0) {
    form.kind = form.currentPassword !== null ? "change-password" : "new-password";
  } else {
    form.kind = form.username !== null ? "login" : "password";
  }
  return form;
}

/** Classifies the form of one input. */
export function detectFormOf(input: HTMLInputElement, options: DetectOptions = {}): DetectedForm | null {
  const root = formRootOf(input);
  // Outside a form: the inputs of its own tree only, not of shadow roots in it.
  const inputs = isElement(root) ? formInputs(root) : Array.from(root.querySelectorAll("input")).filter((it) => it.form === null);
  return classifyForm(root, inputs, options);
}

/** The role of an input in its form, or `null` when Keyguard has nothing for it. */
export function roleIn(form: DetectedForm, input: HTMLInputElement): FieldRole | null {
  if (form.username === input) return "username";
  if (form.currentPassword === input) return "current-password";
  if (form.newPasswords.includes(input)) return "new-password";
  if (form.otp.includes(input)) return "otp";
  return null;
}

/** All forms under a root, through reachable shadow roots, in tree order. */
export function detectForms(root: Document | ShadowRoot, options: DetectOptions = {}): DetectedForm[] {
  const groups = new Map<FormRoot, HTMLInputElement[]>();
  for (const input of collectInputs(root, options)) {
    const formRoot = formRootOf(input);
    const group = groups.get(formRoot) ?? [];
    group.push(input);
    groups.set(formRoot, group);
  }
  const forms: DetectedForm[] = [];
  for (const [formRoot, inputs] of groups) {
    const form = classifyForm(formRoot, inputs, options);
    if (form !== null) forms.push(form);
  }
  return forms;
}
