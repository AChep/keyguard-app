// Puts values into fields the way typing would, so pages and their frameworks
// see them, and only into fields [FieldCheck] allows that are writable once
// they have focus. A fill that writes nothing tells why.
import type { FillRefusal } from "../page/messages";
import type { DetectedForm } from "./detect";

/**
 * Sets a field's value with the native setter, between the events typing
 * fires: `keydown`, `keypress`, `input`, `keyup` and `change`. `null` once
 * written; else why not: `not_editable` when it's still read-only
 * [READONLY_WAIT_MS] after it got focus, or once its key handlers ran, or the
 * reason [check] refuses it then.
 *
 * Frameworks like React wrap the `value` property of their inputs and ignore
 * plain assignments; the setter of `HTMLInputElement.prototype` bypasses the
 * wrapper, and the `input` event that follows makes them read the new value.
 * Pages that validate or enable their submit on key events (jQuery Validation,
 * Knockout) need those too; the events carry no key.
 */
export async function setFieldValue(
  input: HTMLInputElement,
  value: string,
  check: FieldCheck,
): Promise<FillRefusal | null> {
  const view = input.ownerDocument.defaultView;
  if (input.ownerDocument.activeElement !== input) input.focus({ preventScroll: true });
  // The page's focus handlers, and the promises they start, go first: some
  // React OTP widgets reset the field on focus, over a value written before.
  await Promise.resolve();
  // Some pages lift `readonly` on focus to keep autofill out, a timer later.
  // A field still read-only then shows a value, as the account on a password
  // step. The page may also have hidden or covered the field meanwhile.
  if (input.readOnly) await writableOrTimeout(input);
  const focused = writableNow(input, check);
  if (focused !== null) return focused;
  const KeyboardEventType = view?.KeyboardEvent ?? KeyboardEvent;
  const key = (type: string) =>
    input.dispatchEvent(new KeyboardEventType(type, { bubbles: true, cancelable: true, composed: true }));
  const prototype = view?.HTMLInputElement.prototype ?? HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
  key("keydown");
  key("keypress");
  // Their handlers may have changed the field too.
  const pressed = writableNow(input, check);
  if (pressed !== null) return pressed;
  if (setter) setter.call(input, value);
  else input.value = value;
  const InputEventType = view?.InputEvent ?? InputEvent;
  input.dispatchEvent(
    new InputEventType("input", { bubbles: true, composed: true, inputType: "insertReplacementText", data: value }),
  );
  key("keyup");
  input.dispatchEvent(new (view?.Event ?? Event)("change", { bubbles: true }));
  return null;
}

/** How long [setFieldValue] waits for a focused field to stop being read-only. */
export const READONLY_WAIT_MS = 200;

/** Resolves once [input] stops being read-only, or after [READONLY_WAIT_MS]. */
function writableOrTimeout(input: HTMLInputElement): Promise<void> {
  const view = input.ownerDocument.defaultView ?? window;
  return new Promise((resolve) => {
    const observer = new view.MutationObserver(() => {
      if (!input.readOnly) done();
    });
    const timer = view.setTimeout(() => done(), READONLY_WAIT_MS);
    function done() {
      observer.disconnect();
      view.clearTimeout(timer);
      resolve();
    }
    observer.observe(input, { attributes: true, attributeFilter: ["readonly"] });
  });
}

/** Why [setFieldValue] may not write into [input] now: read-only, or [check] refuses it. */
function writableNow(input: HTMLInputElement, check: FieldCheck): FillRefusal | null {
  return input.readOnly ? "not_editable" : check(input);
}

/** Whether a fill may write into a field, see ./guards.ts. */
export type CanFill = (input: HTMLInputElement) => boolean;
/** Why a fill may not write into a field, see ./guards.ts `fieldRefusal`; `null` when it may. */
export type FieldCheck = (input: HTMLInputElement) => FillRefusal | null;
/** The value Keyguard filled into a field, if any. */
export type FilledValue = (input: HTMLInputElement) => string | undefined;

/** The values of a login fill: the username, the password. */
export interface LoginValues {
  username?: string | undefined;
  password?: string | undefined;
}

/** The fields a fill wrote, and why it wrote none: `null` when it wrote any. */
export interface FillResult {
  written: HTMLInputElement[];
  refusal: FillRefusal | null;
}

/**
 * Blurs the last filled field and focuses it again, so pages that validate or
 * update their model on blur see its value, and Enter still submits. Moving
 * focus already blurred the others.
 */
function refocusLast(filled: HTMLInputElement[]): void {
  const last = filled.at(-1);
  if (last === undefined) return;
  last.blur();
  last.focus({ preventScroll: true });
}

/**
 * Fills [values] into [fields] one after another, see [setFieldValue]; with
 * [checkFirst], only those [check] allows before they get focus. Its
 * `refusal` is the first a field hit, `null` without any field.
 */
async function fillEach(
  fields: HTMLInputElement[],
  values: string[],
  check: FieldCheck,
  checkFirst = false,
): Promise<FillResult> {
  const written: HTMLInputElement[] = [];
  let refusal: FillRefusal | null = null;
  for (const [i, input] of fields.entries()) {
    const refused = (checkFirst ? check(input) : null) ?? (await setFieldValue(input, values[i]!, check));
    if (refused === null) written.push(input);
    else refusal ??= refused;
  }
  refocusLast(written);
  return { written, refusal: written.length === 0 ? refusal : null };
}

/** [result], or when it wrote nothing, why: [first], a refusal before it, else its own, else [none]. */
function orRefusal(result: FillResult, first: FillRefusal | null, none: FillRefusal): FillResult {
  return result.written.length > 0 ? result : { written: [], refusal: first ?? result.refusal ?? none };
}

/** The fields of [fields] that [check] allows, and the first refusal of the others. */
function allowedOf(
  fields: readonly HTMLInputElement[],
  check: FieldCheck,
): { allowed: HTMLInputElement[]; refusal: FillRefusal | null } {
  const allowed: HTMLInputElement[] = [];
  let refusal: FillRefusal | null = null;
  for (const input of fields) {
    const refused = check(input);
    if (refused === null) allowed.push(input);
    else refusal ??= refused;
  }
  return { allowed, refusal };
}

/**
 * A login: the username, then the current password, so focus ends on the
 * password. A sign-up form gets the username only; a change-password form
 * its current password. `nothing_to_fill` without a field for [values].
 */
export async function fillLogin(form: DetectedForm, values: LoginValues, check: FieldCheck): Promise<FillResult> {
  const fields: HTMLInputElement[] = [];
  const texts: string[] = [];
  if (values.username !== undefined && form.username !== null) {
    fields.push(form.username);
    texts.push(values.username);
  }
  if (values.password !== undefined && form.currentPassword !== null) {
    fields.push(form.currentPassword);
    texts.push(values.password);
  }
  return orRefusal(await fillEach(fields, texts, check, true), null, "nothing_to_fill");
}

/** Kinds of forms the popup's fill takes, best first. */
const PAGE_FORM_KINDS: readonly string[] = ["login", "password", "username", "change-password"];

/** Whether a form has a username or password that isn't read-only: not only an account the page shows. */
export function hasWritableLogin(form: DetectedForm): boolean {
  return [form.username, form.currentPassword].some((field) => field !== null && !field.readOnly);
}

/**
 * The form the popup's fill takes in a frame: [recent], the form focused
 * last, if it has a writable login, else the best kind of [all], writable
 * ones first. A form of read-only fields only, which may turn writable on
 * focus, is the last resort.
 */
export function pageForm(
  recent: DetectedForm | null,
  all: () => DetectedForm[],
  canFill: CanFill,
): DetectedForm | null {
  if (recent !== null && hasWritableLogin(recent)) return recent;
  const forms = all()
    .filter((form) => PAGE_FORM_KINDS.includes(form.kind))
    .filter((form) => [form.username, form.currentPassword].some((field) => field !== null && canFill(field)));
  const rank = (form: DetectedForm) =>
    (hasWritableLogin(form) ? 0 : PAGE_FORM_KINDS.length) + PAGE_FORM_KINDS.indexOf(form.kind);
  forms.sort((a, b) => rank(a) - rank(b));
  return forms[0] ?? null;
}

/**
 * Whether [form] is the password step of a sign-in: a login or password
 * form whose current password a fill may write and is empty. Its username,
 * if any, isn't one left to type: hidden or covered, or showing an account,
 * read-only or not, that the background compares with the login's. A field
 * Keyguard filled, [filledValue], still holds that value: the user may have
 * typed another account since. An empty username to type, or an empty
 * read-only one as pages keep autofill out, isn't one a fill wrote.
 */
export function isPasswordStep(form: DetectedForm, canFill: CanFill, filledValue: FilledValue): boolean {
  const { username, currentPassword: password } = form;
  if ((form.kind !== "login" && form.kind !== "password") || password === null) return false;
  if (password.value !== "" || !canFill(password)) return false;
  const changed = (input: HTMLInputElement | null) =>
    input !== null && (filledValue(input) ?? input.value) !== input.value;
  if (changed(username) || changed(password)) return false;
  return username === null || !canFill(username) || username.value !== "";
}

/** The account [form] shows: its username's value, or "" without one. */
export function shownAccount(form: DetectedForm): string {
  return form.username?.value ?? "";
}

/**
 * A generated password into the new password and its confirmations, those
 * [check] allows. `nothing_to_fill` without a new password field.
 */
export async function fillNewPassword(form: DetectedForm, password: string, check: FieldCheck): Promise<FillResult> {
  const { allowed, refusal } = allowedOf(form.newPasswords, check);
  const result = await fillEach(
    allowed,
    allowed.map(() => password),
    check,
  );
  return orRefusal(result, refusal, "nothing_to_fill");
}

/**
 * A one-time code: into one field, or one character per box, of those
 * [check] allows. `no_otp_field` without a one-time code field.
 */
export async function fillOtp(form: DetectedForm, code: string, check: FieldCheck): Promise<FillResult> {
  const { allowed: fields, refusal } = allowedOf(form.otp, check);
  const characters = Array.from(code);
  const result =
    fields.length === 0
      ? { written: [], refusal: null }
      : fields.length === 1 || fields.length < code.length
        ? await fillEach([fields[0]!], [code], check)
        : await fillEach(fields.slice(0, characters.length), characters, check);
  return orRefusal(result, refusal, "no_otp_field");
}
