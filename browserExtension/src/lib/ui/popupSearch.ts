// The popup's vault search: asks the background once typing pauses, for
// queries of 2 code points or more, and shows the answer to the latest query
// only. A cleared field shows the page's matches again.
import type { CallError, PageSearch } from "../background/api";
import { searchQuery } from "../protocol/messages";
import { callErrorCode } from "./popupClient";

/** The pause in typing before a search. */
export const SEARCH_DELAY_MS = 200;

export type SearchView =
  /** No query: the page's matches show. */
  | { kind: "matches" }
  /**
   * The results of the query, `null` until the first answer. A new query
   * keeps the previous one's until its own come.
   */
  | { kind: "results"; result: PageSearch | null; error: CallError | null };

export interface PopupSearchDeps {
  search(query: string): Promise<PageSearch>;
  onView(view: SearchView): void;
  delayMs?: number;
}

export class PopupSearch {
  private view: SearchView = { kind: "matches" };
  /** The trimmed query of the field, `null` when too short to send. */
  private query: string | null = null;
  /** Counts queries: answers to older ones are dropped. */
  private generation = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(private readonly deps: PopupSearchDeps) {}

  /** The field's text changed. */
  input(text: string): void {
    const query = searchQuery(text);
    if (query === this.query) return;
    this.query = query;
    const generation = ++this.generation;
    clearTimeout(this.timer);
    if (query === null) {
      this.show({ kind: "matches" });
      return;
    }
    this.show({ kind: "results", result: this.view.kind === "results" ? this.view.result : null, error: null });
    this.timer = setTimeout(() => void this.run(query, generation), this.deps.delayMs ?? SEARCH_DELAY_MS);
  }

  /**
   * A key in the field. Escape clears it, as long as it holds text: an empty
   * field leaves Escape to close the popup. A cleared field fires no `input`:
   * the event's default is prevented then.
   */
  keydown(event: KeyboardEvent): void {
    const field = event.currentTarget as HTMLInputElement;
    // An Escape that cancels an IME composition is the field's.
    if (event.key !== "Escape" || event.isComposing || field.value === "") return;
    event.preventDefault();
    field.value = "";
    this.input("");
  }

  /** Asks again for the field's query now, as after the tab went on to another page. */
  refresh(): void {
    const query = this.query;
    if (query === null) return;
    const generation = ++this.generation;
    clearTimeout(this.timer);
    void this.run(query, generation);
  }

  /** The popup closed. */
  dispose(): void {
    clearTimeout(this.timer);
    this.generation++;
  }

  private async run(query: string, generation: number) {
    let view: SearchView;
    try {
      view = { kind: "results", result: await this.deps.search(query), error: null };
    } catch (failure) {
      // A newer query cancels this one in the background too; its failure is dropped below.
      view = { kind: "results", result: null, error: callErrorCode(failure) };
    }
    if (generation === this.generation) this.show(view);
  }

  private show(view: SearchView) {
    this.view = view;
    this.deps.onView(view);
  }
}
