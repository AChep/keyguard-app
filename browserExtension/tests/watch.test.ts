// @vitest-environment happy-dom
// How the content script follows a changing page: it walks what a change
// adds, not the whole document again.
import { afterEach, describe, expect, it } from "vitest";
import { PageWatcher } from "../src/lib/content/watch";
import { byId, render } from "./support/dom";

let watcher: PageWatcher | null = null;
afterEach(() => {
  watcher?.stop();
  watcher = null;
  document.body.innerHTML = "";
});

/** Lets mutation records and the debounce run. */
function settle(ms = 10): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Starts a watcher that records the elements it asks about, as the content script asks the browser for closed roots. */
function watch(options: { closed?: Map<Element, ShadowRoot>; debounceMs?: number } = {}) {
  const visited: Element[] = [];
  const found: ShadowRoot[] = [];
  /** Per scan: whether inputs or labels changed. */
  const inputs: boolean[] = [];
  /** Per scan: the inputs it saw. */
  const seen: HTMLInputElement[][] = [];
  watcher = new PageWatcher({
    shadowRootOf: (element) => {
      visited.push(element);
      return element.shadowRoot ?? options.closed?.get(element) ?? null;
    },
    found: (root) => found.push(root),
    changed: (changed, saw) => {
      inputs.push(changed);
      seen.push([...saw]);
    },
    debounceMs: options.debounceMs ?? 0,
  });
  watcher.start(document);
  return { watcher, visited, found, inputs, seen, changes: () => inputs.length };
}

describe("page watcher", () => {
  it("walks only what a change adds, not the document again", async () => {
    render(Array.from({ length: 200 }, (_, i) => `<div><x-card id="c${i}"></x-card></div>`).join(""));
    for (let i = 0; i < 20; i++) byId<HTMLElement>(`c${i}`).attachShadow({ mode: "open" }).innerHTML = "<span></span>";
    const { visited, found, changes } = watch();
    expect(found).toHaveLength(20);
    expect(visited.length).toBeGreaterThan(400);
    visited.length = 0;

    const added = document.createElement("p");
    added.innerHTML = "<b>new</b>";
    document.body.append(added);
    await settle();

    // `<b>` can't have a shadow root: nobody asks.
    expect(visited).toEqual([added]);
    expect(changes()).toBe(2);
  });

  it("listens in shadow roots of added elements, and walks what's added in them", async () => {
    const closed = new Map<Element, ShadowRoot>();
    const { visited, found } = watch({ closed });
    const host = document.createElement("x-login");
    const root = host.attachShadow({ mode: "open" });
    root.innerHTML = `<form><input id="u"></form>`;
    document.body.append(host);
    await settle();
    expect(found).toEqual([root]);

    visited.length = 0;
    const field = document.createElement("x-field");
    const inner = field.attachShadow({ mode: "closed" });
    closed.set(field, inner);
    inner.innerHTML = `<input id="p" type="password">`;
    root.append(field);
    await settle();
    expect(found).toEqual([root, inner]);
    expect(visited).toEqual([field]);
  });

  it("walks an added element once, not again for each element added in it", async () => {
    const { visited } = watch();
    visited.length = 0;
    const outer = document.createElement("div");
    document.body.append(outer);
    const inner = document.createElement("x-card");
    outer.append(inner);
    await settle();
    expect(visited).toEqual([outer, inner]);
  });

  it("tells whether inputs or labels changed, so the content script skips re-checking fields otherwise", async () => {
    render(`<input id="field">`);
    const { inputs } = watch();
    expect(inputs).toEqual([true]);

    const banner = document.createElement("div");
    banner.innerHTML = "<span>Ad</span><x-card></x-card>";
    document.body.append(banner);
    await settle();
    expect(inputs).toEqual([true, false]);

    const label = document.createElement("div");
    label.innerHTML = "<label for='field'>Email</label>";
    document.body.append(label);
    await settle();
    document.body.append(document.createElement("input"));
    await settle();
    byId("field").setAttribute("type", "email");
    await settle();
    expect(inputs).toEqual([true, false, true, true, true]);
  });

  it("tells which inputs a scan saw: those added, and those that changed, not the document's", async () => {
    render(`<form><input id="u"><input id="p" type="password"></form>`);
    const { seen } = watch();
    expect(seen).toEqual([[byId("u"), byId("p")]]);

    const added = document.createElement("div");
    added.innerHTML = `<input id="other" type="password">`;
    document.body.append(added);
    await settle();
    byId("u").setAttribute("autocomplete", "username");
    await settle();
    expect(seen).toEqual([[byId("u"), byId("p")], [byId("other")], [byId("u")]]);
  });

  it("gathers what a burst of changes adds into one scan", async () => {
    const { visited, changes } = watch({ debounceMs: 40 });
    visited.length = 0;
    const first = document.createElement("div");
    const second = document.createElement("div");
    document.body.append(first);
    await settle(5);
    document.body.append(second);
    await settle(80);
    expect(changes()).toBe(2);
    expect(visited).toEqual([first, second]);
  });

  it("scans after an input changes its hints, but not elements removed meanwhile", async () => {
    render(`<input id="field">`);
    const { visited, changes } = watch();
    byId("field").setAttribute("autocomplete", "username");
    await settle();
    expect(changes()).toBe(2);

    visited.length = 0;
    const gone = document.createElement("div");
    document.body.append(gone);
    gone.remove();
    await settle();
    expect(changes()).toBe(3);
    expect(visited).toEqual([]);
  });

  it("scans after an input turns writable, as on a click or a timer", async () => {
    render(`<input id="field" type="password" readonly>`);
    const { changes } = watch();
    byId("field").removeAttribute("readonly");
    await settle();
    expect(changes()).toBe(2);
  });

  it("takes in a root attached after its host was added once a field in it gets focus", async () => {
    const { watcher, found } = watch();
    const host = document.createElement("x-late");
    document.body.append(host);
    await settle();
    // No record tells of the root.
    const root = host.attachShadow({ mode: "open" });
    root.innerHTML = `<x-inner id="inner"></x-inner><form><input id="u"></form>`;
    const nested = byId<HTMLElement>("inner", root).attachShadow({ mode: "open" });
    document.body.append(document.createElement("div"));
    await settle();
    expect(found).toEqual([]);

    watcher.adopt(byId("u", root));
    expect(found).toEqual([root, nested]);
    watcher.adopt(byId("u", root));
    expect(found).toEqual([root, nested]);
  });
});
