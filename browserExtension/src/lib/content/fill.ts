// Puts values into fields the way typing would, so pages and their frameworks
// see them, and only into fields [CanFill] allows that are writable once they
// have focus.
import type { DetectedForm } from "./detect";

/**
 * Sets a field's value with the native setter, between the events typing
 * fires: `keydown`, `keypress`, `input`, `keyup` and `change`. False when
 * [canFill] no longer allows the field once it has focus, or once its key
 * handlers ran, or it's read-only.
 *
 * Frameworks like React wrap the `value` property of their inputs and ignore
 * plain assignments; the setter of `HTMLInputElement.prototype` bypasses the
 * wrapper, and the `input` event that follows makes them read the new value.
 * Pages that validate or enable their submit on key events (jQuery Validation,
 * Knockout) need those too; the events carry no key.
 */
export async function setFieldValue(input: HTMLInputElement, value: string, canFill: CanFill): Promise<boolean> {
  const view = input.ownerDocument.defaultView;
  if (input.ownerDocument.activeElement !== input) input.focus({ preventScroll: true });
  // The page's focus handlers, and the promises they start, go first: some
  // React OTP widgets reset the field on focus, over a value written before.
  await Promise.resolve();
  // The page may have hidden or covered the field meanwhile. Some lift
  // `readonly` on focus to keep autofill out; a field still read-only now
  // shows a value, as the account on a password step.
  if (input.readOnly || !canFill(input)) return false;
  const KeyboardEventType = view?.KeyboardEvent ?? KeyboardEvent;
  const key = (type: string) =>
    input.dispatchEvent(new KeyboardEventType(type, { bubbles: true, cancelable: true, composed: true }));
  const prototype = view?.HTMLInputElement.prototype ?? HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
  key("keydown");
  key("keypress");
  // Their handlers may have changed the field too.
  if (input.readOnly || !canFill(input)) return false;
  if (setter) setter.call(input, value);
  else input.value = value;
  const InputEventType = view?.InputEvent ?? InputEvent;
  input.dispatchEvent(
    new InputEventType("input", { bubbles: true, composed: true, inputType: "insertReplacementText", data: value }),
  );
  key("keyup");
  input.dispatchEvent(new (view?.Event ?? Event)("change", { bubbles: true }));
  return true;
}

/** Whether a fill may write into a field, see ./guards.ts. */
export type CanFill = (input: HTMLInputElement) => boolean;
/** The value Keyguard filled into a field, if any. */
export type FilledValue = (input: HTMLInputElement) => string | undefined;

/** The values of a login fill: the username, the password. */
export interface LoginValues {
  username?: string | undefined;
  password?: string | undefined;
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

/** Fills [values] into [fields] one after another; the fields it filled. */
async function fillEach(fields: HTMLInputElement[], values: string[], canFill: CanFill): Promise<HTMLInputElement[]> {
  const filled: HTMLInputElement[] = [];
  for (const [i, input] of fields.entries()) {
    if (await setFieldValue(input, values[i]!, canFill)) filled.push(input);
  }
  refocusLast(filled);
  return filled;
}

/** A login: the username, then the current password, so focus ends on the password. */
export async function fillLogin(form: DetectedForm, values: LoginValues, canFill: CanFill): Promise<HTMLInputElement[]> {
  const filled: HTMLInputElement[] = [];
  const username = form.username;
  if (values.username !== undefined && username !== null && canFill(username)) {
    if (await setFieldValue(username, values.username, canFill)) filled.push(username);
  }
  // A sign-up form gets the username only; a change-password form its current password.
  const password = form.currentPassword;
  if (values.password !== undefined && password !== null && canFill(password)) {
    if (await setFieldValue(password, values.password, canFill)) filled.push(password);
  }
  refocusLast(filled);
  return filled;
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
export function pageForm(recent: DetectedForm | null, all: () => DetectedForm[], canFill: CanFill): DetectedForm | null {
  if (recent !== null && hasWritableLogin(recent)) return recent;
  const forms = all()
    .filter((form) => PAGE_FORM_KINDS.includes(form.kind))
    .filter((form) => [form.username, form.currentPassword].some((field) => field !== null && canFill(field)));
  const rank = (form: DetectedForm) => (hasWritableLogin(form) ? 0 : PAGE_FORM_KINDS.length) + PAGE_FORM_KINDS.indexOf(form.kind);
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
  const changed = (input: HTMLInputElement | null) => input !== null && (filledValue(input) ?? input.value) !== input.value;
  if (changed(username) || changed(password)) return false;
  return username === null || !canFill(username) || username.value !== "";
}

/** The account [form] shows: its username's value, or "" without one. */
export function shownAccount(form: DetectedForm): string {
  return form.username?.value ?? "";
}

/** A generated password into the new password and its confirmations. */
export function fillNewPassword(form: DetectedForm, password: string, canFill: CanFill): Promise<HTMLInputElement[]> {
  const fields = form.newPasswords.filter(canFill);
  return fillEach(fields, fields.map(() => password), canFill);
}

/** A one-time code: into one field, or one character per box. */
export async function fillOtp(form: DetectedForm, code: string, canFill: CanFill): Promise<HTMLInputElement[]> {
  const fields = form.otp.filter(canFill);
  if (fields.length === 0) return [];
  if (fields.length === 1 || fields.length < code.length) return fillEach([fields[0]!], [code], canFill);
  const characters = Array.from(code);
  return fillEach(fields.slice(0, characters.length), characters, canFill);
}
