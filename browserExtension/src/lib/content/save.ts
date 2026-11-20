// When to offer saving a login: a form with a password the user typed is
// submitted, or the page goes away right after the user clicked or pressed
// Enter in that form, as with a button that isn't known to submit. A password
// the user typed and left, or that the extension filled and the user didn't
// change, isn't offered.
import type { SaveCandidate } from "../page/messages";
import { concatBytes, randomBytes, toHex, utf8 } from "../util/bytes";
import {
  detectFormOf,
  formInputs,
  formRootOf,
  inputOf,
  isIgnored,
  type DetectedForm,
  type DetectOptions,
  type FormRoot,
} from "./detect";

/** What a submitted form would save, or `null` when there's nothing new. */
export function saveCandidateOf(
  form: DetectedForm,
  filledValue: (input: HTMLInputElement) => string | undefined,
): SaveCandidate | null {
  const username = form.username?.value.trim() || undefined;
  const withUsername = (candidate: SaveCandidate): SaveCandidate =>
    username === undefined ? candidate : { username, ...candidate };
  switch (form.kind) {
    case "login":
    case "password": {
      const field = form.currentPassword!;
      const password = field.value;
      if (password === "" || filledValue(field) === password) return null;
      return withUsername({ password });
    }
    case "new-password":
    case "change-password": {
      const [first, ...confirmations] = form.newPasswords;
      const password = first!.value;
      // A confirmation that doesn't match fails on the page anyway.
      if (password === "" || confirmations.some((input) => input.value !== "" && input.value !== password)) return null;
      const previous = form.currentPassword?.value || undefined;
      if (previous === password) return null;
      return withUsername(previous === undefined ? { password } : { password, previous_password: previous });
    }
    case "username":
    case "otp":
      return null;
  }
}

/** Words of buttons that submit a sign-in, sign-up or password change. */
const SUBMIT_WORDS = /log-?\s?in|sign-?\s?in|sign-?\s?up|register|continue|next|submit|save|change|update|create|join/i;
/** The text of a button read for [SUBMIT_WORDS], in characters. */
const BUTTON_TEXT_MAX = 60;

/** Whether a click on [button] submits its form, as far as a page can tell. */
export function isSubmitButton(button: Element): boolean {
  if (button.getAttribute("aria-pressed") !== null) return false;
  if (button.localName === "input") {
    const type = (button as HTMLInputElement).type;
    return type === "submit" || type === "image";
  }
  if (button.localName === "button") {
    const type = (button as HTMLButtonElement).type;
    if (type === "submit" && (button as HTMLButtonElement).form !== null) return true;
    if (type === "reset") return false;
  }
  const text = [textStart(button), button.getAttribute("aria-label"), button.getAttribute("value"), button.id]
    .map((it) => (it ?? "").trim().slice(0, BUTTON_TEXT_MAX))
    .join(" ");
  return SUBMIT_WORDS.test(text);
}

/**
 * The start of an element's text, past its leading whitespace: a whole page
 * can be in a `[role=button]`, and only the start is read.
 */
function textStart(element: Element): string {
  let text = "";
  const walker = element.ownerDocument.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null && text.length < BUTTON_TEXT_MAX; node = walker.nextNode()) {
    const data = (node as Text).data;
    text += text === "" ? data.trimStart().slice(0, BUTTON_TEXT_MAX) : data.slice(0, BUTTON_TEXT_MAX - text.length);
  }
  return text;
}

/** A report holds back the same candidate for this long: the background's offer of it lasts as long (SAVE_TTL_MS of ../background/pages.ts). */
export const REPORT_TTL_MS = 2 * 60_000;
/**
 * A page that goes away within this long of a trusted click or Enter in a
 * form reports what was typed there: the click may have submitted it.
 */
export const SUBMIT_GRACE_MS = 5_000;

/** Input types that take text: a click in one focuses it, it doesn't submit. */
const TEXT_INPUT = /^(text|password|email|tel|url|search|number)$/;

export interface SaveTrackerOptions extends DetectOptions {
  /** Reports a candidate; the background decides whether to offer saving it. */
  report(candidate: SaveCandidate): void;
  /** Reports the username of a username-only step. */
  reportUsername(username: string): void;
  /** The value Keyguard filled into a field, if any. */
  filledValue(input: HTMLInputElement): string | undefined;
  /** Whether an event comes from the user. Tests can't make trusted events. */
  isTrusted?(event: Event): boolean;
}

/**
 * Listens for submissions in a document and the shadow roots it's given.
 * Only password fields that got trusted input count, so a page can't make the
 * extension offer values the user didn't type.
 */
export class SaveTracker {
  private readonly typed = new Set<HTMLInputElement>();
  /** This document's salt of [lastReport]. */
  private readonly salt = randomBytes(32);
  /** The last report as a salted SHA-256, and when it was submitted: the password isn't kept. */
  private lastReport: { digest: string; at: number } | null = null;
  /** Submitted candidates waiting for their digest, in order. */
  private readonly waiting: { candidate: SaveCandidate; at: number }[] = [];
  /** The running [drain]; [flush] drops it. */
  private draining: object | null = null;
  /** When the user last clicked or pressed Enter in each form root, by `Date.now()`, for [flush]. */
  private readonly acted = new WeakMap<FormRoot, number>();
  private readonly isTrusted: (event: Event) => boolean;

  constructor(private readonly options: SaveTrackerOptions) {
    this.isTrusted = options.isTrusted ?? ((event) => event.isTrusted);
  }

  /** Listens on a document or shadow root; `signal` removes the listeners. */
  listen(root: Document | ShadowRoot, signal: AbortSignal): void {
    const options = { capture: true, signal };
    root.addEventListener("input", (event) => this.onInput(event), options);
    root.addEventListener("submit", (event) => this.onSubmit(event), options);
    root.addEventListener("click", (event) => this.onClick(event), options);
    root.addEventListener("keydown", (event) => this.onKeydown(event as KeyboardEvent), options);
  }

  /** Counts a password the user put in another way, as with "Generate password". */
  track(input: HTMLInputElement): void {
    this.typed.add(input);
  }

  /** Forgets fields that left the page: they can't be submitted, and would keep their page alive. */
  prune(): void {
    for (const input of this.typed) if (!input.isConnected) this.typed.delete(input);
  }

  /**
   * The page is going away: report what was typed and not reported yet in the
   * forms the user clicked or pressed Enter in within SUBMIT_GRACE_MS. A
   * password typed and left there is no submission.
   */
  flush(): void {
    const now = Date.now();
    for (const input of this.typed) {
      const at = this.acted.get(formRootOf(input));
      if (input.isConnected && at !== undefined && now - at <= SUBMIT_GRACE_MS) this.check(input);
    }
    this.typed.clear();
    // Their digests may never come now (Firefox drops them): what waits goes
    // now, unchecked against the last report.
    this.draining = null;
    for (const { candidate } of this.waiting.splice(0)) this.options.report(candidate);
  }

  private onInput(event: Event) {
    const input = inputOf(event.composedPath()[0]);
    if (input === null || (input.type !== "password" && !/password/.test(input.autocomplete))) return;
    if (this.isTrusted(event) && !isIgnored(input)) this.typed.add(input);
  }

  private onSubmit(event: Event) {
    const form = event.composedPath()[0];
    if (!(form instanceof Element) || form.localName !== "form") return;
    this.submitted(formInputs(form as HTMLFormElement));
  }

  private onClick(event: Event) {
    if (!this.isTrusted(event)) return;
    const target = event.composedPath()[0];
    if (!(target instanceof Element)) return;
    this.actedIn(target, true);
    const button = target.closest("button, input[type=submit], input[type=image], input[type=button], [role=button]");
    if (button === null || !isSubmitButton(button)) return;
    const form = (button as HTMLButtonElement).form ?? null;
    const inputs = Array.from(this.typed).filter((input) =>
      form !== null ? input.form === form : input.isConnected && input.getRootNode() === button.getRootNode(),
    );
    this.submitted(inputs.length > 0 ? inputs : usernameInputsNear(button));
  }

  private onKeydown(event: KeyboardEvent) {
    if (event.key !== "Enter" || !this.isTrusted(event)) return;
    const target = event.composedPath()[0];
    if (target instanceof Element) this.actedIn(target, false);
    const input = inputOf(target);
    if (input !== null) this.submitted([input]);
  }

  /**
   * The user clicked ([click]) or pressed Enter on [target], which may submit
   * its form root, see [flush]. Not a click into a text field, which only
   * focuses it.
   */
  private actedIn(target: Element, click: boolean) {
    const input = inputOf(target);
    if (click && input !== null && TEXT_INPUT.test(input.type)) return;
    // A control's form may be elsewhere, by its `form` attribute.
    const control = target.closest("button, input") as HTMLButtonElement | HTMLInputElement | null;
    const root = control?.form ?? target.closest("form") ?? (target.getRootNode() as Document | ShadowRoot);
    this.acted.set(root, Date.now());
  }

  /** A form with these inputs was submitted. */
  private submitted(inputs: HTMLInputElement[]) {
    const typed = inputs.find((input) => this.typed.has(input));
    if (typed !== undefined) {
      this.check(typed);
      return;
    }
    // A username step: remember the username for the password step. Inputs
    // of one form root share its detection.
    const forms = new Map<FormRoot, DetectedForm | null>();
    for (const input of inputs) {
      const root = formRootOf(input);
      let form = forms.get(root);
      if (form === undefined) {
        form = detectFormOf(input, this.options);
        forms.set(root, form);
      }
      if (form?.kind === "username" && input.value.trim() !== "" && form.username === input) {
        this.options.reportUsername(input.value.trim());
        return;
      }
    }
  }

  private check(input: HTMLInputElement) {
    const form = detectFormOf(input, this.options);
    if (form === null) return;
    const candidate = saveCandidateOf(form, this.options.filledValue);
    if (candidate === null) return;
    for (const field of [form.currentPassword, ...form.newPasswords]) if (field) this.typed.delete(field);
    this.waiting.push({ candidate, at: Date.now() });
    if (this.draining === null) void this.drain();
  }

  /**
   * Reports the waiting candidates in order, each unless it's the last report
   * again: a click and the submit it causes report the same values once.
   */
  private async drain() {
    const run = {};
    this.draining = run;
    try {
      while (this.waiting.length > 0) {
        const { candidate, at } = this.waiting[0]!;
        const digest = await this.digestOf(candidate);
        // [flush] reported it meanwhile.
        if (this.draining !== run) return;
        this.waiting.shift();
        const last = this.lastReport;
        if (digest !== null && last?.digest === digest && at - last.at < REPORT_TTL_MS) continue;
        this.lastReport = digest === null ? null : { digest, at };
        this.options.report(candidate);
      }
    } finally {
      if (this.draining === run) this.draining = null;
    }
  }

  /** The SHA-256 of [salt] and [candidate]; `null` without WebCrypto, which only secure contexts have. */
  private async digestOf(candidate: SaveCandidate): Promise<string | null> {
    if (!crypto.subtle) return null;
    const json = utf8(JSON.stringify(candidate));
    const data = concatBytes(this.salt, json);
    json.fill(0);
    try {
      return toHex(new Uint8Array(await crypto.subtle.digest("SHA-256", data as BufferSource)));
    } catch {
      return null;
    } finally {
      data.fill(0);
    }
  }
}

/** The text inputs of a button's form, or of its root outside any form. */
function usernameInputsNear(button: Element): HTMLInputElement[] {
  const form = (button as HTMLButtonElement).form ?? button.closest("form");
  const scope: ParentNode = form ?? (button.getRootNode() as Document | ShadowRoot);
  return Array.from(scope.querySelectorAll("input")).filter((input) => input.type !== "password");
}
