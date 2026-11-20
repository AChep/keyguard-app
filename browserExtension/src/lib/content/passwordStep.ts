// The password step of a sign-in, see ./fill.ts `isPasswordStep`: which
// forms of the top frame the content script reports to the background
// (`password_step`), and the field that a fill of target `step` then takes.
import { randomToken, type ContentRequest } from "../page/messages";
import { collectInputs, detectFormOf, formRootOf, type DetectedForm, type DetectOptions, type FormRoot } from "./detect";
import { isPasswordStep, shownAccount, type CanFill, type FilledValue } from "./fill";
import { fieldExposure, type Layout } from "./guards";

/**
 * A `password_step` the content script sent: its password field, the root of
 * its form and the page's URL then, which a single-page app may reuse for
 * another form or change, and the account its form showed then, or "".
 */
export interface StepReport {
  field: HTMLInputElement;
  root: FormRoot;
  url: string;
  account: string;
}

export interface PasswordStepOptions extends DetectOptions {
  doc: Document;
  layout: Layout;
  canFill: CanFill;
  filledValue: FilledValue;
  /** Whether this is the top frame: the others report nothing. */
  isTop: boolean;
  /** Whether the options were read with the option off; till they are, [send] checks. */
  off(): boolean;
  /** Sends a report, once the option is on; settles once the background answered it, or won't. */
  send(request: Extract<ContentRequest, { kind: "password_step" }>): Promise<unknown>;
  signal: AbortSignal;
}

/**
 * Reports the password steps of a document, and keeps each report's field
 * till a fill takes it. A step of a tab in the background is reported once
 * the tab is in front, see [listen].
 */
export class PasswordStepReporter {
  /** How many login fills began: the generation a scan finds a password step in, see [firstFound]. */
  private generation = 0;
  /** The smallest [report] `since` asked for while the tab was in the background, till [listen] reports it; null when none. */
  private missedSince: number | null = null;
  /**
   * The password fields a scan found to be a password step, by the
   * generation it first found each in: a scan reports each once, and the
   * look after a username fill only those found since that fill began.
   */
  private readonly firstFound = new WeakMap<HTMLInputElement, number>();
  /** The field of each `password_step` by its token, till the background answered it: a fill of target `step` takes it once. */
  private readonly reports = new Map<string, StepReport>();

  constructor(private readonly options: PasswordStepOptions) {}

  /**
   * Once the tab is in front again, reports the password steps it missed in
   * the background, as a page that loaded there; nothing when it missed none.
   */
  listen(): void {
    const { doc, isTop, signal } = this.options;
    if (!isTop) return;
    doc.addEventListener(
      "visibilitychange",
      () => {
        const since = this.missedSince;
        if (doc.visibilityState !== "visible" || since === null) return;
        this.missedSince = null;
        this.rescan(since);
      },
      { signal },
    );
  }

  /** A login fill begins: the generation that [rescan] after it takes. */
  beginFill(): number {
    return ++this.generation;
  }

  /**
   * After a fill of generation [since] wrote a username alone, reports each
   * password step that no scan found before that fill began, as one a
   * single-page app showed during it. Not one shown before: the user didn't
   * go on to it, and focus tells when they do.
   */
  rescan(since: number): void {
    if (this.options.signal.aborted) return;
    this.report(collectInputs(this.options.doc, this.options), since);
  }

  /** Reports [field], which got focus, again, if [form], its form, is a password step: focus tells which one the user is on. */
  focused(field: HTMLInputElement, form: DetectedForm | null): void {
    this.report([field], 0, () => form);
  }

  /**
   * Tells the background of each password step among the forms of [fields],
   * password fields a scan saw or that got focus, see [showsStep]: it may
   * fill the password when a fill wrote the username alone on the step
   * before. A scan reports a field once, see [firstFound], and the look
   * after a fill of generation [since] again those found since. A scan that
   * finds several reports none: nothing tells which one the user is on. In
   * the top frame only, with the option on: the options are read once a
   * form is one. In a tab in the background, nobody sees a step: it detects
   * nothing, and [listen] looks again once the tab is in front.
   */
  report(
    fields: Iterable<HTMLInputElement>,
    since = Infinity,
    formOf = (input: HTMLInputElement) => detectFormOf(input, this.options),
  ): void {
    if (!this.options.isTop || this.options.off()) return;
    if (this.options.doc.visibilityState !== "visible") {
      // The smallest: the look then takes what each missed report would have.
      this.missedSince = Math.min(this.missedSince ?? Infinity, since);
      return;
    }
    const fresh = (input: HTMLInputElement) => {
      const first = this.firstFound.get(input);
      return first === undefined || first >= since;
    };
    // Only empty password fields, one detection per form: most pages have none.
    const roots = new Map<FormRoot, HTMLInputElement>();
    for (const input of fields) {
      if (input.type !== "password" || input.value !== "" || !input.isConnected || !fresh(input)) continue;
      const root = formRootOf(input);
      if (!roots.has(root)) roots.set(root, input);
    }
    const steps: { form: DetectedForm; field: HTMLInputElement }[] = [];
    for (const input of roots.values()) {
      const form = formOf(input);
      const field = form?.currentPassword ?? null;
      if (form === null || field === null || !fresh(field) || !this.showsStep(form, field)) continue;
      if (!this.firstFound.has(field)) this.firstFound.set(field, this.generation);
      steps.push({ form, field });
    }
    // Several at once: nothing tells which one the user is on.
    const [only, ...others] = steps;
    if (only === undefined || others.length > 0) return;
    const { form, field } = only;
    const account = shownAccount(form);
    const url = location.href;
    const token = randomToken();
    this.reports.set(token, { field, root: form.root, url, account });
    void this.options
      .send({ kind: "password_step", step: token, url, ...(account !== "" ? { account } : {}) })
      // The background answers once it filled the step, or won't.
      .finally(() => this.reports.delete(token));
  }

  /** The report of [token], once: a fill of target `step` takes its field. */
  take(token: string): StepReport | undefined {
    const report = this.reports.get(token);
    this.reports.delete(token);
    return report;
  }

  /** The form of [report]'s field, if it's still the password step the user saw: the same form, page and account. */
  formOf({ field, root, url, account }: StepReport): DetectedForm | null {
    const form = field.isConnected && location.href === url ? detectFormOf(field, this.options) : null;
    if (form === null || form.root !== root || shownAccount(form) !== account) return null;
    return this.showsStep(form, field) ? form : null;
  }

  /**
   * Whether [form] is a password step with [field] its password, see
   * isPasswordStep, that the user sees: the tab is in front, and the page
   * shows the field, as for the inline icon. Of type `password`: a field
   * the page switched to plain text would show what's written.
   */
  private showsStep(form: DetectedForm, field: HTMLInputElement): boolean {
    const { doc, layout, canFill, filledValue } = this.options;
    return (
      form.currentPassword === field &&
      field.type === "password" &&
      isPasswordStep(form, canFill, filledValue) &&
      doc.visibilityState === "visible" &&
      fieldExposure(field, layout) === null
    );
  }
}
