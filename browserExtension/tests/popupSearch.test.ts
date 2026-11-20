// @vitest-environment happy-dom
// The popup's vault search (src/lib/ui/popupSearch.ts): when it asks the
// background, which answer shows, and when the page's matches come back.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PageSearch } from "../src/lib/background/api";
import { PopupCallError } from "../src/lib/ui/popupClient";
import { PopupSearch, SEARCH_DELAY_MS, type SearchView } from "../src/lib/ui/popupSearch";
import { byId, render } from "./support/dom";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = "";
});

function results(...handles: string[]): PageSearch {
  return {
    kind: "items",
    origin: "https://login.example.com",
    truncated: false,
    items: handles.map((handle) => ({
      handle,
      name: handle,
      account: "Personal",
      has_password: true,
      has_totp: false,
      reprompt: false,
      matched: false,
    })),
  };
}

function setup() {
  const asked: { query: string; resolve(result: PageSearch): void; reject(error: unknown): void }[] = [];
  const views: SearchView[] = [];
  const search = new PopupSearch({
    search: (query) => new Promise((resolve, reject) => asked.push({ query, resolve, reject })),
    onView: (view) => views.push(view),
  });
  /** Lets the timers of [ms] run, and the answers come in. */
  const wait = (ms = 0) => vi.advanceTimersByTimeAsync(ms);
  return { search, asked, views, view: () => views.at(-1), wait };
}

describe("PopupSearch", () => {
  it("asks again for the field's query on a refresh, as after the tab went on to another page", async () => {
    const { search, asked, view, wait } = setup();
    search.refresh();
    expect(asked).toEqual([]);
    search.input("git");
    await wait(SEARCH_DELAY_MS);
    search.refresh();
    expect(asked.map((it) => it.query)).toEqual(["git", "git"]);
    // Only the latest answer shows.
    asked[1]!.resolve(results("b"));
    asked[0]!.resolve(results("a"));
    await wait();
    expect(view()).toEqual({ kind: "results", result: results("b"), error: null });
  });

  it("asks once typing pauses, from 2 code points, with the trimmed query", async () => {
    const { search, asked, views, wait } = setup();
    search.input("g");
    search.input(" 😀 ");
    await wait(SEARCH_DELAY_MS);
    expect(asked).toEqual([]);
    expect(views).toEqual([]);

    search.input("gi");
    expect(views).toEqual([{ kind: "results", result: null, error: null }]);
    await wait(SEARCH_DELAY_MS - 1);
    search.input("git ");
    await wait(SEARCH_DELAY_MS - 1);
    expect(asked).toEqual([]);
    await wait(1);
    expect(asked.map((it) => it.query)).toEqual(["git"]);
    // The same query once trimmed.
    search.input(" git");
    await wait(SEARCH_DELAY_MS);
    expect(asked).toHaveLength(1);
  });

  it("keeps the results until the new query's come, and shows the latest query's only", async () => {
    const { search, asked, view, wait } = setup();
    search.input("git");
    await wait(SEARCH_DELAY_MS);
    asked[0]!.resolve(results("a"));
    await wait();
    expect(view()).toEqual({ kind: "results", result: results("a"), error: null });

    search.input("gith");
    expect(view()).toEqual({ kind: "results", result: results("a"), error: null });
    await wait(SEARCH_DELAY_MS);
    search.input("githu");
    await wait(SEARCH_DELAY_MS);
    asked[2]!.resolve(results("c"));
    // The background cancels the older one.
    asked[1]!.reject(new PopupCallError("cancelled"));
    await wait();
    expect(view()).toEqual({ kind: "results", result: results("c"), error: null });

    search.input("github");
    await wait(SEARCH_DELAY_MS);
    asked[3]!.reject(new PopupCallError("not_supported"));
    await wait();
    expect(view()).toEqual({ kind: "results", result: null, error: "not_supported" });
  });

  it("shows the matches again when the field is cleared, and drops the late answer", async () => {
    const { search, asked, view, wait } = setup();
    search.input("git");
    await wait(SEARCH_DELAY_MS);
    search.input("g");
    expect(view()).toEqual({ kind: "matches" });
    asked[0]!.resolve(results("a"));
    await wait();
    expect(view()).toEqual({ kind: "matches" });

    // A pending query is dropped too.
    search.input("git");
    search.input("");
    await wait(SEARCH_DELAY_MS);
    expect(asked).toHaveLength(1);
    expect(view()).toEqual({ kind: "matches" });
  });

  it("clears the field on Escape, and leaves Escape on an empty field to close the popup", async () => {
    const { search, asked, view, wait } = setup();
    render(`<input id="field" type="search" />`);
    const field = byId<HTMLInputElement>("field");
    field.addEventListener("keydown", (event) => search.keydown(event));
    const escape = (isComposing = false) => {
      const event = new KeyboardEvent("keydown", { key: "Escape", isComposing, cancelable: true });
      field.dispatchEvent(event);
      return event;
    };
    field.value = "git";
    search.input(field.value);

    // Escape cancels an IME composition first: the field keeps its text.
    expect(escape(true).defaultPrevented).toBe(false);
    expect(field.value).toBe("git");
    // The default is prevented, which tells the popup the field was cleared: it fires no `input`.
    expect(escape().defaultPrevented).toBe(true);
    expect(field.value).toBe("");
    expect(view()).toEqual({ kind: "matches" });
    await wait(SEARCH_DELAY_MS);
    expect(asked).toEqual([]);
    expect(escape().defaultPrevented).toBe(false);
  });
});
