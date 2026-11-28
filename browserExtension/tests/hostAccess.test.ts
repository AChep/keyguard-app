// Chromium's toolbar request for site access: asked for on navigations to
// sites without access where the vault has logins, withdrawn where it has none
// and as the vault locks.
import { describe, expect, it } from "vitest";
import type { ExtensionStatus } from "../src/lib/background/api";
import type { TabInfo } from "../src/lib/background/frames";
import { HostAccessRequests, SCAN_CONCURRENCY, type HostAccessDeps } from "../src/lib/background/hostAccess";
import type { JsonObject } from "../src/lib/protocol/framing";
import { parseResponse, type SuggestResponse } from "../src/lib/protocol/messages";
import { item, LOGIN_ORIGIN, LOGIN_URL, settle } from "./support/pages";

const URL_B = "https://other.example.org/";
const LOCKED: ExtensionStatus = { kind: "locked", unlocking: false };
const UNLOCKED: ExtensionStatus = { kind: "unlocked" };

function response(fields: Partial<SuggestResponse> = {}): SuggestResponse {
  return { blocked: false, save_blocked: false, truncated: false, items: [], ...fields };
}

/**
 * A [HostAccessRequests] with a fake app, browser and request API, recording
 * what it asks. A tab is on [LOGIN_URL] unless [urls] says otherwise. [deps]
 * replace the fakes.
 */
function setup({
  access = [] as string[],
  allSites = false,
  status = UNLOCKED,
  suggest = (_request: JsonObject): Promise<SuggestResponse> | SuggestResponse => response(),
  tabs = [] as (TabInfo & { id?: number })[],
  deps = {} as Partial<HostAccessDeps>,
} = {}) {
  const calls: string[] = [];
  const requests: JsonObject[] = [];
  const watched: boolean[] = [];
  const signals: (AbortSignal | undefined)[] = [];
  const state = { status, suggest, tabs, allSites, access: new Set(access), urls: new Map<number, string>() };
  const fakes: HostAccessDeps = {
    api: {
      add: (tabId) => calls.push(`add ${tabId}`),
      remove: (tabId) => calls.push(`remove ${tabId}`),
    },
    hasAccess: async (url) => state.access.has(new URL(url).origin),
    allSites: async () => state.allSites,
    app: {
      get snapshot() {
        return state.status;
      },
      // The answer comes even after the signal aborts: the class drops it.
      request: async (type, fields, signal) => {
        const request = { type, ...fields } as JsonObject;
        requests.push(request);
        signals.push(signal);
        return (await state.suggest(request)) as never;
      },
    },
    tabs: async () => state.tabs,
    tab: async (tabId) => ({ url: state.urls.get(tabId) ?? LOGIN_URL }),
    watch: (active) => watched.push(active),
  };
  return {
    requests: new HostAccessRequests({ ...fakes, ...deps }),
    calls,
    sent: requests,
    signals,
    state,
    watched,
  };
}

const matched = () => response({ items: [item("h1")] });

/** Answers of `suggest` that wait for the test, in the order asked. */
function deferredAnswers() {
  const answers: ((value: SuggestResponse) => void)[] = [];
  const suggest = () => new Promise<SuggestResponse>((resolve) => answers.push(resolve));
  return { answers, suggest };
}

describe("host access requests", () => {
  it("asks nothing on a site with access", async () => {
    const { requests, calls, sent } = setup({ access: [LOGIN_ORIGIN], suggest: matched });
    await requests.navigated(1, LOGIN_URL);
    expect(sent).toEqual([]);
    expect(calls).toEqual([]);
  });

  it("checks nothing while the vault is locked", async () => {
    const checked: string[] = [];
    const { requests, calls, sent } = setup({
      status: LOCKED,
      suggest: matched,
      deps: {
        hasAccess: async (url) => {
          checked.push(url);
          return false;
        },
      },
    });
    await requests.navigated(1, LOGIN_URL);
    expect(checked).toEqual([]);
    expect(sent).toEqual([]);
    expect(calls).toEqual([]);
  });

  it("asks nothing for a page that isn't a website", async () => {
    const { requests, calls, sent } = setup({ suggest: matched });
    for (const url of ["chrome://newtab/", "file:///tmp/a.html", "about:blank", "not a url"]) {
      await requests.navigated(1, url);
    }
    expect(sent).toEqual([]);
    expect(calls).toEqual([]);
  });

  it("adds the request for a site with a matched login", async () => {
    const { requests, calls, sent } = setup({ suggest: matched });
    await requests.navigated(1, LOGIN_URL);
    expect(sent).toEqual([{ type: "suggest", url: LOGIN_URL }]);
    expect(calls).toEqual(["add 1"]);
  });

  it("removes the request for a site with only suggested logins, or none", async () => {
    const { requests, calls, state } = setup({
      suggest: () => response({ items: [item("h1", { matched: false })] }),
    });
    await requests.navigated(1, LOGIN_URL);
    state.suggest = () => response();
    await requests.navigated(2, LOGIN_URL);
    expect(calls).toEqual(["remove 1", "remove 2"]);
  });

  it("removes the request for a blocked site", async () => {
    const { requests, calls } = setup({ suggest: () => response({ blocked: true, items: [item("h1")] }) });
    await requests.navigated(1, LOGIN_URL);
    expect(calls).toEqual(["remove 1"]);
  });

  it("adds the request for a login of an older app, which doesn't say `matched`", async () => {
    const { requests, calls } = setup({
      suggest: () =>
        parseResponse("suggest", {
          blocked: false,
          save_blocked: false,
          truncated: false,
          items: [
            { handle: "h1", name: "A", account: "Personal", has_password: true, has_totp: false, reprompt: false },
          ],
        }),
    });
    await requests.navigated(1, LOGIN_URL);
    expect(calls).toEqual(["add 1"]);
  });

  it("says when the page is in a private window", async () => {
    const { requests, sent } = setup({ suggest: matched });
    await requests.navigated(1, LOGIN_URL, true);
    await requests.navigated(2, LOGIN_URL, false);
    expect(sent).toEqual([
      { type: "suggest", url: LOGIN_URL, private_window: true },
      { type: "suggest", url: LOGIN_URL },
    ]);
  });

  it("drops the answer for a page the tab already left", async () => {
    const answers = new Map<string, (value: SuggestResponse) => void>();
    const { requests, calls } = setup({
      suggest: (request) => new Promise((resolve) => answers.set(request.url as string, resolve)),
    });
    const first = requests.navigated(1, LOGIN_URL);
    await settle();
    const second = requests.navigated(1, URL_B);
    await settle();
    answers.get(URL_B)!(response());
    answers.get(LOGIN_URL)!(matched());
    await Promise.all([first, second]);
    expect(calls).toEqual(["remove 1"]);
  });

  it("drops the answer for a page the tab left for one it doesn't check", async () => {
    let answer = (_value: SuggestResponse) => undefined as void;
    const { requests, calls } = setup({ suggest: () => new Promise((resolve) => (answer = resolve)) });
    const first = requests.navigated(1, LOGIN_URL);
    await settle();
    await requests.navigated(1, "chrome://newtab/");
    answer(matched());
    await first;
    expect(calls).toEqual([]);
  });

  it("drops a page the tab left while its access was checked", async () => {
    const access = new Map<string, (value: boolean) => void>();
    const { requests, calls, sent } = setup({
      suggest: matched,
      deps: { hasAccess: (url) => new Promise((resolve) => access.set(url, resolve)) },
    });
    const first = requests.navigated(1, LOGIN_URL);
    const second = requests.navigated(1, URL_B);
    access.get(URL_B)!(true);
    access.get(LOGIN_URL)!(false);
    await Promise.all([first, second]);
    expect(sent).toEqual([]);
    expect(calls).toEqual([]);
  });

  it("checks the open tabs once the vault unlocks, once per unlock", async () => {
    const { requests, calls, sent, state } = setup({
      status: LOCKED,
      suggest: (request) => (request.url === LOGIN_URL ? matched() : response()),
      tabs: [
        { id: 1, url: LOGIN_URL },
        { id: 2, url: URL_B, incognito: true },
        { id: 3, url: "chrome://settings/" },
        { url: LOGIN_URL },
        { id: 4 },
      ],
    });
    requests.statusChanged();
    await settle();
    expect(sent).toEqual([]);

    state.status = UNLOCKED;
    requests.statusChanged();
    await settle();
    expect(sent).toEqual([
      { type: "suggest", url: LOGIN_URL },
      { type: "suggest", url: URL_B, private_window: true },
    ]);
    expect(calls.sort()).toEqual(["add 1", "remove 2"]);

    // Another status event of the same unlock.
    requests.statusChanged();
    await settle();
    expect(sent).toHaveLength(2);

    state.status = LOCKED;
    requests.statusChanged();
    state.status = UNLOCKED;
    requests.statusChanged();
    await settle();
    expect(sent).toHaveLength(4);
  });

  it("watches navigations only while the vault is unlocked", () => {
    const { requests, state, watched } = setup({ status: LOCKED });
    requests.statusChanged();
    expect(watched).toEqual([]);

    state.status = UNLOCKED;
    requests.statusChanged();
    requests.statusChanged();
    expect(watched).toEqual([true]);

    state.status = LOCKED;
    requests.statusChanged();
    state.status = { kind: "app_unavailable" };
    requests.statusChanged();
    expect(watched).toEqual([true, false]);

    state.status = UNLOCKED;
    requests.statusChanged();
    expect(watched).toEqual([true, false, true]);
  });

  it("watches navigations only while some site lacks access", async () => {
    const { requests, state, watched } = setup({ allSites: true });
    await requests.accessChanged();
    requests.statusChanged();
    expect(watched).toEqual([]);

    state.allSites = false;
    await requests.accessChanged();
    await requests.accessChanged();
    expect(watched).toEqual([true]);

    state.allSites = true;
    await requests.accessChanged();
    expect(watched).toEqual([true, false]);

    // Access lost while locked waits for the unlock.
    state.status = LOCKED;
    requests.statusChanged();
    state.allSites = false;
    await requests.accessChanged();
    expect(watched).toEqual([true, false]);
    state.status = UNLOCKED;
    requests.statusChanged();
    expect(watched).toEqual([true, false, true]);
  });

  it("checks the open tabs as access to every website goes, not on an unlock with it", async () => {
    const { requests, sent, state } = setup({ allSites: true, suggest: matched, tabs: [{ id: 1, url: LOGIN_URL }] });
    await requests.accessChanged();
    requests.statusChanged();
    await settle();
    expect(sent).toEqual([]);

    state.allSites = false;
    await requests.accessChanged();
    expect(sent).toEqual([{ type: "suggest", url: LOGIN_URL }]);

    // Still without it: nothing new to check.
    await requests.accessChanged();
    expect(sent).toHaveLength(1);
  });

  it("drops an older answer about every website", async () => {
    const answers: ((value: boolean) => void)[] = [];
    const { requests, watched } = setup({
      deps: { allSites: () => new Promise((resolve) => answers.push(resolve)) },
    });
    requests.statusChanged();
    const first = requests.accessChanged();
    const second = requests.accessChanged();
    answers[1]!(true);
    answers[0]!(false);
    await Promise.all([first, second]);
    expect(watched).toEqual([true, false]);
  });

  it("checks the open tabs two at a time", async () => {
    const { answers, suggest } = deferredAnswers();
    const { requests, sent, state } = setup({
      status: LOCKED,
      suggest,
      tabs: [1, 2, 3, 4, 5].map((id) => ({ id, url: `https://site${id}.example/` })),
    });
    requests.statusChanged();
    state.status = UNLOCKED;
    requests.statusChanged();
    await settle();
    expect(sent).toHaveLength(SCAN_CONCURRENCY);
    for (let answered = 0; answered < 5; answered++) {
      expect(sent.length - answered).toBeLessThanOrEqual(SCAN_CONCURRENCY);
      answers[answered]!(response());
      await settle();
    }
    expect(sent).toHaveLength(5);
  });

  it("cancels the checks as the vault locks, and adds nothing after", async () => {
    const { answers, suggest } = deferredAnswers();
    const { requests, calls, sent, signals, state } = setup({
      status: LOCKED,
      suggest,
      tabs: [1, 2, 3, 4].map((id) => ({ id, url: LOGIN_URL })),
    });
    requests.statusChanged();
    state.status = UNLOCKED;
    requests.statusChanged();
    const navigation = requests.navigated(9, LOGIN_URL);
    await settle();
    expect(sent).toHaveLength(3);

    state.status = LOCKED;
    requests.statusChanged();
    expect(signals.every((signal) => signal?.aborted)).toBe(true);
    // Late answers of the app, as one that answered before the cancel.
    answers.forEach((answer) => answer(matched()));
    await navigation;
    await settle();
    // The tabs still waiting in the scan aren't asked about.
    expect(sent).toHaveLength(3);
    expect(calls).toEqual([]);
  });

  it("withdraws the requests it added as the vault locks", async () => {
    const { requests, calls, state } = setup({
      suggest: (request) => (request.url === LOGIN_URL ? matched() : response()),
    });
    await requests.navigated(1, LOGIN_URL);
    await requests.navigated(2, LOGIN_URL);
    await requests.navigated(3, URL_B);
    // Tab 2 left for a site without logins: its request is already withdrawn.
    await requests.navigated(2, URL_B);
    calls.length = 0;

    state.status = { kind: "app_unavailable" };
    requests.statusChanged();
    await settle();
    expect(calls).toEqual(["remove 1"]);

    // Withdrawn once.
    state.status = LOCKED;
    requests.statusChanged();
    await settle();
    expect(calls).toEqual(["remove 1"]);
  });

  it("withdraws a request the browser added as the vault locked", async () => {
    let added = () => undefined as void;
    const { requests, calls, state } = setup({
      suggest: matched,
      deps: {
        api: {
          add: (tabId) => {
            calls.push(`add ${tabId}`);
            return new Promise<void>((resolve) => (added = resolve));
          },
          remove: (tabId) => calls.push(`remove ${tabId}`),
        },
      },
    });
    const navigation = requests.navigated(1, LOGIN_URL);
    await settle();
    expect(calls).toEqual(["add 1"]);
    state.status = LOCKED;
    requests.statusChanged();
    added();
    await navigation;
    expect(calls).toEqual(["add 1", "remove 1"]);
  });

  it("adds nothing for a tab that left the page while the app answered", async () => {
    const { requests, calls, state } = setup({ suggest: matched });
    state.urls.set(1, URL_B);
    await requests.navigated(1, LOGIN_URL);
    expect(calls).toEqual([]);

    // Another page of the same site still asks.
    state.urls.set(1, `${LOGIN_ORIGIN}/account`);
    await requests.navigated(1, LOGIN_URL);
    expect(calls).toEqual(["add 1"]);
  });

  it("checks the open tabs again as a site's access goes", async () => {
    const { requests, sent, state } = setup({ status: LOCKED, suggest: matched, tabs: [{ id: 1, url: LOGIN_URL }] });
    await requests.accessChanged({ recheck: true });
    expect(sent).toEqual([]);

    state.status = UNLOCKED;
    requests.statusChanged();
    await settle();
    expect(sent).toHaveLength(1);

    // A grant that leaves some site without access: nothing new to check.
    await requests.accessChanged();
    expect(sent).toHaveLength(1);
    await requests.accessChanged({ recheck: true });
    expect(sent).toHaveLength(2);

    // Nor with access to every website.
    state.allSites = true;
    await requests.accessChanged({ recheck: true });
    expect(sent).toHaveLength(2);
  });

  it("swallows the failures of the app, the browser and the request API", async () => {
    const disconnected = setup({ suggest: () => Promise.reject(new Error("disconnected")) });
    await expect(disconnected.requests.navigated(1, LOGIN_URL)).resolves.toBeUndefined();

    const failing = setup({
      suggest: (request) => (request.url === LOGIN_URL ? matched() : response()),
      deps: {
        api: {
          add: () => Promise.reject(new Error("No tab with id: 1")),
          remove: () => {
            throw new Error("No tab with id: 2");
          },
        },
        allSites: () => Promise.reject(new Error("permissions")),
        tabs: () => Promise.reject(new Error("no tabs")),
      },
    });
    await expect(failing.requests.navigated(1, LOGIN_URL)).resolves.toBeUndefined();
    await expect(failing.requests.navigated(2, URL_B)).resolves.toBeUndefined();
    await expect(failing.requests.accessChanged()).resolves.toBeUndefined();
    failing.requests.statusChanged();
    await settle();

    const noAccessCheck = setup({ deps: { hasAccess: () => Promise.reject(new Error("permissions")) } });
    await expect(noAccessCheck.requests.navigated(1, LOGIN_URL)).resolves.toBeUndefined();
  });
});
