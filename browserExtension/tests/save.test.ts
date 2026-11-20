// @vitest-environment happy-dom
// When the save bar is offered: submissions of passwords the user typed.
import { afterEach, describe, expect, it, vi } from "vitest";
import { SAVE_TTL_MS } from "../src/lib/background/pages";
import { detectFormOf } from "../src/lib/content/detect";
import { fillLogin, type LoginValues } from "../src/lib/content/fill";
import { isSubmitButton, REPORT_TTL_MS, saveCandidateOf, SaveTracker, SUBMIT_GRACE_MS } from "../src/lib/content/save";
import { SUBMIT_DELAY_MS, submitAfterFill } from "../src/lib/content/submit";
import type { SaveCandidate } from "../src/lib/page/messages";
import { byId, detectOptions, render, submitSetup } from "./support/dom";

/** Listeners of each test, removed after it. */
let abort = new AbortController();
afterEach(() => {
  abort.abort();
  abort = new AbortController();
  document.body.innerHTML = "";
});

/** Events a test marks as the user's; happy-dom can't make trusted events. */
const userEvents = new WeakSet<Event>();

function user<T extends Event>(event: T): T {
  userEvents.add(event);
  return event;
}

function type(input: HTMLInputElement, value: string, trusted = true) {
  input.value = value;
  const event = new InputEvent("input", { bubbles: true, composed: true });
  input.dispatchEvent(trusted ? user(event) : event);
}

/** A click of the user on [element]. */
function click(element: Element) {
  element.dispatchEvent(user(new MouseEvent("click", { bubbles: true, composed: true })));
}

function setup(filled = new Map<HTMLInputElement, string>()) {
  const reports: SaveCandidate[] = [];
  const usernames: string[] = [];
  const tracker = new SaveTracker({
    ...detectOptions,
    report: (candidate) => reports.push(candidate),
    reportUsername: (username) => usernames.push(username),
    filledValue: (input) => filled.get(input),
    isTrusted: (event) => userEvents.has(event),
  });
  tracker.listen(document, abort.signal);
  return { tracker, reports, usernames };
}

/** Waits for [expected]: a report waits for its digest. */
function reported(reports: SaveCandidate[], expected: SaveCandidate[]) {
  return vi.waitFor(() => expect(reports).toEqual(expected));
}

/** The strings an object keeps, through its fields, arrays, sets and maps; not those of the page's nodes. */
function stringsIn(value: unknown, seen = new Set<unknown>()): string[] {
  if (typeof value === "string") return [value];
  if (typeof value !== "object" || value === null || value instanceof Node || seen.has(value)) return [];
  seen.add(value);
  const children =
    value instanceof Map ? [...value.keys(), ...value.values()] : value instanceof Set || Array.isArray(value) ? [...value] : Object.values(value);
  return children.flatMap((child) => stringsIn(child, seen));
}

describe("save candidates", () => {
  it("skips a login whose password Keyguard filled and nobody changed", () => {
    render(`<form><input id="u" name="email" value="ann"><input id="p" type="password" value="secret"></form>`);
    const form = detectFormOf(byId("p"), detectOptions)!;
    expect(saveCandidateOf(form, (input) => (input === byId("p") ? "secret" : undefined))).toBeNull();
    expect(saveCandidateOf(form, (input) => (input === byId("p") ? "old" : undefined))).toEqual({
      username: "ann",
      password: "secret",
    });
  });

  it("takes a sign-up's password only when the confirmation matches", () => {
    render(`<form><input id="u" autocomplete="username" value="ann"><input id="p1" type="password" value="new"><input id="p2" type="password" value="nwe"></form>`);
    const form = detectFormOf(byId("p1"), detectOptions)!;
    expect(saveCandidateOf(form, () => undefined)).toBeNull();
    byId("p2").value = "new";
    expect(saveCandidateOf(form, () => undefined)).toEqual({ username: "ann", password: "new" });
  });

  it("sends the previous password of a change", () => {
    render(`<form><input id="c" type="password" autocomplete="current-password" value="old"><input id="n" type="password" autocomplete="new-password" value="new"></form>`);
    const form = detectFormOf(byId("c"), detectOptions)!;
    expect(saveCandidateOf(form, () => undefined)).toEqual({ password: "new", previous_password: "old" });
    byId("n").value = "old";
    expect(saveCandidateOf(form, () => undefined)).toBeNull();
  });
});

describe("save tracker", () => {
  it("reports a typed password on submit", async () => {
    const { reports } = setup();
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"></form>`);
    byId("u").value = "ann@example.com";
    type(byId("p"), "hunter2");
    byId<HTMLFormElement>("f").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await reported(reports, [{ username: "ann@example.com", password: "hunter2" }]);
  });

  it("ignores passwords the page typed, and ones Keyguard filled", () => {
    const filled = new Map<HTMLInputElement, string>();
    const { reports } = setup(filled);
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"></form>`);
    type(byId("p"), "by-script", false);
    byId<HTMLFormElement>("f").dispatchEvent(new Event("submit", { bubbles: true }));
    expect(reports).toEqual([]);

    type(byId("p"), "from-vault");
    filled.set(byId("p"), "from-vault");
    byId<HTMLFormElement>("f").dispatchEvent(new Event("submit", { bubbles: true }));
    expect(reports).toEqual([]);
  });

  it("reports on a sign-in button of a script form, once with its submit", async () => {
    const { reports } = setup();
    render(`<div><input id="u" name="login"><input id="p" type="password"><div id="go" role="button">Sign in</div></div>`);
    byId("u").value = "ann";
    type(byId("p"), "pw1");
    byId<HTMLElement>("go").dispatchEvent(user(new MouseEvent("click", { bubbles: true, composed: true })));
    byId<HTMLElement>("go").dispatchEvent(user(new MouseEvent("click", { bubbles: true, composed: true })));
    await reported(reports, [{ username: "ann", password: "pw1" }]);
  });

  it("doesn't take a show-password toggle for a submit", () => {
    const { reports } = setup();
    render(`<form><input id="p" type="password"><button id="eye" type="button" aria-label="Show password">👁</button><button id="toggle" type="button" aria-pressed="false">Sign in</button></form>`);
    type(byId("p"), "pw1");
    for (const id of ["eye", "toggle"]) {
      byId<HTMLElement>(id).dispatchEvent(user(new MouseEvent("click", { bubbles: true, composed: true })));
    }
    expect(reports).toEqual([]);
    expect(isSubmitButton(byId<HTMLElement>("eye"))).toBe(false);
  });

  it("reads the start of a button's text only, past leading whitespace", () => {
    const filler = "x".repeat(60);
    render(`
      <div id="spaced" role="button">${" ".repeat(80)}<span> \n </span><span>Sign in</span></div>
      <div id="late" role="button"><span>${filler}</span><span>Sign in</span></div>`);
    expect(isSubmitButton(byId<HTMLElement>("spaced"))).toBe(true);
    expect(isSubmitButton(byId<HTMLElement>("late"))).toBe(false);
  });

  it("remembers the username of a username-first step after a click outside any form, among many fields", () => {
    const { usernames } = setup();
    render(`
      <input id="search" name="q"><input id="u" autocomplete="username">
      <form><input id="a" name="first-name"><input id="b" name="last-name"></form>
      <div id="next" role="button">Next</div>`);
    byId("u").value = "ann@example.com";
    byId<HTMLElement>("next").dispatchEvent(user(new MouseEvent("click", { bubbles: true, composed: true })));
    expect(usernames).toEqual(["ann@example.com"]);
  });

  it("reports on Enter in the password field", async () => {
    const { reports } = setup();
    render(`<div><input id="p" type="password" autocomplete="current-password"></div>`);
    type(byId("p"), "pw1");
    byId("p").dispatchEvent(user(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, composed: true })));
    await reported(reports, [{ password: "pw1" }]);
  });

  it("offers a password the user generated", async () => {
    const { tracker, reports } = setup();
    render(`<form id="f"><input id="u" autocomplete="username"><input id="p1" type="password" autocomplete="new-password"><input id="p2" type="password" autocomplete="new-password"></form>`);
    type(byId("u"), "bob");
    // Filled by the extension: the page's events aren't trusted, the user's click was.
    for (const id of ["p1", "p2"]) {
      byId(id).value = "Gen3rated";
      tracker.track(byId(id));
    }
    byId<HTMLFormElement>("f").dispatchEvent(new Event("submit", { bubbles: true }));
    await reported(reports, [{ username: "bob", password: "Gen3rated" }]);
  });

  it("reports what's left when the page goes away right after a click in its form, as on a button it doesn't know", () => {
    const { tracker, reports } = setup();
    render(`<form><input id="u" name="email"><input id="p" type="password"><span id="go" class="icon-arrow"></span></form>`);
    byId("u").value = "ann";
    type(byId("p"), "pw1");
    click(byId("go"));
    expect(reports).toEqual([]);
    tracker.flush();
    expect(reports).toEqual([{ username: "ann", password: "pw1" }]);
    tracker.flush();
    expect(reports).toHaveLength(1);
  });

  it("reports what's left after Enter in another field of its form, as the username", () => {
    const { tracker, reports } = setup();
    render(`<form><input id="u" name="email"><input id="p" type="password"></form>`);
    type(byId("p"), "pw1");
    byId("u").value = "ann";
    byId("u").dispatchEvent(user(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, composed: true })));
    tracker.flush();
    expect(reports).toEqual([{ username: "ann", password: "pw1" }]);
  });

  it("reports no password the user typed and left, as the page goes away", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    try {
      const { tracker, reports } = setup();
      render(`
        <form id="login"><input id="u" name="email"><input id="p" type="password"><span id="go"></span></form>
        <form id="search"><input id="q" name="q"><button id="find" type="button">Find</button></form>
        <a id="away" href="#elsewhere">Elsewhere</a>`);
      byId("u").value = "ann";
      // A click into the field focuses it; typing is no submit.
      click(byId("p"));
      type(byId("p"), "pw1");
      tracker.flush();
      expect(reports).toEqual([]);

      // A link elsewhere, a button of another form, Enter in another form.
      type(byId("p"), "pw1");
      click(byId("away"));
      click(byId("find"));
      byId("q").dispatchEvent(user(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, composed: true })));
      tracker.flush();
      expect(reports).toEqual([]);

      // A click in the form, but too long before the page went away; and the page's own click.
      type(byId("p"), "pw1");
      click(byId("go"));
      vi.setSystemTime(Date.now() + SUBMIT_GRACE_MS + 1);
      byId("go").dispatchEvent(new MouseEvent("click", { bubbles: true, composed: true }));
      tracker.flush();
      expect(reports).toEqual([]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("takes a fill's events for neither typing nor a submit", async () => {
    const { tracker, reports, usernames } = setup();
    render(`
      <form id="step"><input id="u" autocomplete="username" value="bob"></form>
      <form id="login"><input id="lu" name="email"><input id="lp" type="password"></form>`);
    // Its key events, and the blur and focus that end it, aren't the user's;
    // the keydown comes before the write, with the page's value still in.
    await fillLogin(detectFormOf(byId("u"), detectOptions)!, { username: "ann" }, () => true);
    await fillLogin(detectFormOf(byId("lp"), detectOptions)!, { username: "ann", password: "secret" }, () => true);
    tracker.flush();
    expect(usernames).toEqual([]);
    expect(reports).toEqual([]);
  });

  it("offers nothing after the sign-in that follows a fill: its click isn't the user's", async () => {
    const filled = new Map<HTMLInputElement, string>();
    const { tracker, reports, usernames } = setup(filled);
    const { options, fill } = submitSetup(filled);
    /** Fills the form of [id] as the content script does, then signs in with it; resolves with whether it did. */
    const fillAndSubmit = async (id: string, values: LoginValues) => {
      const filling = await fill(id, values);
      const done = submitAfterFill(filling, options, abort.signal, filling.watch);
      await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
      return done;
    };
    document.addEventListener("submit", (event) => event.preventDefault(), { signal: abort.signal });
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      // A password the user typed, then the fill's.
      render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form>`);
      type(byId("p"), "typed");
      expect(await fillAndSubmit("p", { username: "ann", password: "secret" })).toBe(true);
      tracker.flush();
      expect(reports).toEqual([]);
      expect(usernames).toEqual([]);

      // A username step is remembered for the password step, as after the user's own submit.
      render(`<form id="f"><input id="u" autocomplete="username"><button id="next">Next</button></form>`);
      expect(await fillAndSubmit("u", { username: "ann" })).toBe(true);
      tracker.flush();
      expect(usernames).toEqual(["ann"]);
      expect(reports).toEqual([]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("remembers the username of a username-first step", () => {
    const { reports, usernames } = setup();
    render(`<form id="f"><input id="u" autocomplete="username"><button id="next">Next</button></form>`);
    byId("u").value = "ann@example.com";
    byId<HTMLFormElement>("f").dispatchEvent(new Event("submit", { bubbles: true }));
    expect(usernames).toEqual(["ann@example.com"]);
    expect(reports).toEqual([]);
  });

  it("never takes fields the page opts out", () => {
    const { reports, usernames } = setup();
    render(`
      <form id="f"><input id="u" name="email"><input id="p" type="password" data-1p-ignore></form>
      <form id="step" data-lpignore="true"><input id="su" autocomplete="username"></form>`);
    type(byId("p"), "hunter2");
    byId<HTMLFormElement>("f").dispatchEvent(new Event("submit", { bubbles: true }));
    byId("su").value = "ann@example.com";
    byId<HTMLFormElement>("step").dispatchEvent(new Event("submit", { bubbles: true }));
    expect(reports).toEqual([]);
    expect(usernames).toEqual([]);
  });

  it("forgets typed fields that left the page", () => {
    const { tracker, reports } = setup();
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"></form>`);
    const form = byId<HTMLFormElement>("f");
    type(byId("p"), "pw1");
    form.remove();
    tracker.prune();
    // Back on the page, it's as if nobody typed in it.
    document.body.append(form);
    tracker.flush();
    expect(reports).toEqual([]);
  });

  it("listens in shadow roots it's given", async () => {
    const { tracker, reports } = setup();
    render(`<x-login id="host"></x-login>`);
    const root = byId<HTMLElement>("host").attachShadow({ mode: "open" });
    root.innerHTML = `<form id="f"><input id="u" name="username"><input id="p" type="password"></form>`;
    tracker.listen(root, abort.signal);
    byId("u", root).value = "ann";
    type(byId("p", root), "pw1");
    // `submit` doesn't leave the shadow root.
    byId<HTMLFormElement>("f", root).dispatchEvent(new Event("submit", { bubbles: true }));
    await reported(reports, [{ username: "ann", password: "pw1" }]);
  });
});

describe("repeated reports", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  /** A password-only form whose field the user submits with Enter. */
  function passwordStep() {
    render(`<div><input id="p" type="password" autocomplete="current-password"></div>`);
    return (password: string) => {
      type(byId("p"), password);
      byId("p").dispatchEvent(user(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, composed: true })));
    };
  }

  it("drops a repeat of the last report, also one submitted before its digest is in", async () => {
    const { reports } = setup();
    const submit = passwordStep();
    submit("pw1");
    submit("pw1");
    await reported(reports, [{ password: "pw1" }]);
    submit("pw1");
    // Reported once the repeat before it was dropped.
    submit("pw2");
    await reported(reports, [{ password: "pw1" }, { password: "pw2" }]);
  });

  it("reports other candidates, in order", async () => {
    const { reports } = setup();
    const submit = passwordStep();
    submit("pw1");
    submit("pw2");
    submit("pw1");
    await reported(reports, [{ password: "pw1" }, { password: "pw2" }, { password: "pw1" }]);
  });

  it("keeps no password once it reported", async () => {
    const { tracker, reports } = setup();
    const submit = passwordStep();
    submit("hunter2");
    await reported(reports, [{ password: "hunter2" }]);
    expect(stringsIn(tracker).filter((text) => text.includes("hunter2"))).toEqual([]);
  });

  it("reports the same candidate again once the save offer is over", async () => {
    vi.useFakeTimers();
    const { reports } = setup();
    const submit = passwordStep();
    const start = Date.now();
    submit("pw1");
    await reported(reports, [{ password: "pw1" }]);
    vi.setSystemTime(start + REPORT_TTL_MS - 1);
    submit("pw1");
    vi.setSystemTime(start + REPORT_TTL_MS);
    submit("pw1");
    // Reported once those before it were.
    submit("pw2");
    await reported(reports, [{ password: "pw1" }, { password: "pw1" }, { password: "pw2" }]);
    expect(REPORT_TTL_MS).toBe(SAVE_TTL_MS);
  });

  it("reports what waits for its digest when the page goes away, once", async () => {
    const { tracker, reports } = setup();
    const submit = passwordStep();
    submit("pw1");
    tracker.flush();
    expect(reports).toEqual([{ password: "pw1" }]);
    // Back from the back/forward cache.
    submit("pw2");
    await reported(reports, [{ password: "pw1" }, { password: "pw2" }]);
  });

  it("reports every candidate without WebCrypto, as on http pages", async () => {
    vi.stubGlobal("crypto", { getRandomValues: (bytes: Uint8Array) => bytes });
    const { tracker, reports } = setup();
    const submit = passwordStep();
    submit("pw1");
    submit("pw1");
    await reported(reports, [{ password: "pw1" }, { password: "pw1" }]);
    expect(stringsIn(tracker).filter((text) => text.includes("pw1"))).toEqual([]);
  });
});
