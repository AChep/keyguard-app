// @vitest-environment happy-dom
// Signing in after a fill: which control is selected, when, and when nothing is.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { filledLogin, SUBMIT_DELAY_MS, SUBMIT_WAIT_MS, submitAfterFill, watchSignIn } from "../src/lib/content/submit";
import { byId, render, submitSetup, type WatchedFill } from "./support/dom";

/** Values Keyguard filled, as the content script records them. */
const filled = new Map<HTMLInputElement, string>();
const { options, fill } = submitSetup(filled);

/** Listeners of each test, removed after it. */
let abort = new AbortController();
/** The ids of the elements clicked, and of the forms submitted, in order. */
let events: string[];

beforeEach(() => {
  vi.useFakeTimers();
  events = [];
  document.addEventListener("click", (event) => events.push(`click:${(event.target as Element).id}`), { capture: true, signal: abort.signal });
  document.addEventListener(
    "submit",
    (event) => {
      // happy-dom would navigate.
      event.preventDefault();
      events.push(`submit:${(event.target as Element).id}`);
    },
    { capture: true, signal: abort.signal },
  );
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  abort.abort();
  abort = new AbortController();
  filled.clear();
  document.body.innerHTML = "";
});

/** Starts the sign-in after a fill; resolves with whether it submitted. */
function start(filling: WatchedFill, signal = new AbortController().signal): Promise<boolean> {
  return submitAfterFill(filling, options, signal, filling.watch);
}

/** [filling] with a new watch, for another sign-in: each ends its own. */
function again(filling: WatchedFill): WatchedFill {
  return { ...filling, watch: watchSignIn(filling.form.root) };
}

/** The sign-in after a fill, past SUBMIT_DELAY_MS: for a control that's ready, or none. */
async function submit(filling: WatchedFill): Promise<boolean> {
  const done = start(filling);
  await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
  return done;
}

/** Dispatches [event] on [target] as the user's own. */
function trusted(target: EventTarget, event: Event) {
  Object.defineProperty(event, "isTrusted", { value: true });
  target.dispatchEvent(event);
}

describe("in a form", () => {
  it("clicks the submit button the page shows, once the fill's answer had time", async () => {
    render(`
      <form id="f">
        <input id="u" name="email"><input id="p" type="password">
        <button id="eye" type="button">Show</button>
        <button id="none" style="display:none">Go</button>
        <button id="invisible" style="visibility:hidden">Go</button>
        <button id="go">Weiter</button>
      </form>`);
    const done = start(await fill("p", { username: "ann", password: "secret" }));
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS - 1);
    expect(events).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(await done).toBe(true);
    expect(events).toEqual(["click:go", "submit:f"]);
    // Once.
    await vi.advanceTimersByTimeAsync(SUBMIT_WAIT_MS);
    expect(events).toHaveLength(2);
  });

  it("takes, among several submit buttons, the only one with a hint that it signs in", async () => {
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button>Help</button><button id="go">Sign in</button></form>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go", "submit:f"]);

    for (const buttons of [
      // A third-party sign-in first: both say they continue.
      `<button name="provider" value="okta">Continue with Okta</button><button>Sign in</button>`,
      `<button>Weiter</button><input type="submit" value="Weiter">`,
    ]) {
      events.length = 0;
      render(`<form id="f"><input id="u" name="email"><input id="p" type="password">${buttons}</form>`);
      expect(await submit(await fill("p", { username: "ann", password: "secret" })), buttons).toBe(false);
      expect(events, buttons).toEqual([]);
    }
  });

  it("takes a submit button outside the form that names it", async () => {
    render(`<form id="f"><input id="p" type="password"></form><button id="go" form="f">Sign in</button>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go", "submit:f"]);
  });

  it("skips the submit buttons that do something else, and submits nothing with only those", async () => {
    render(`
      <form id="f">
        <input id="p" type="password">
        <button id="google" name="provider" value="google">Continue with Google</button>
        <button id="passkey">Use a passkey</button>
        <button id="eye" aria-pressed="false"></button>
        <button id="go">Sign in</button>
      </form>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go", "submit:f"]);

    events.length = 0;
    byId<HTMLElement>("go").remove();
    byId("p").value = "";
    expect(await submit(await fill("p", { password: "secret" }))).toBe(false);
    expect(events).toEqual([]);
  });

  it("submits nothing that changes the account, as a form that asks for the password to confirm it", async () => {
    for (const html of [
      `<form id="f"><p>Enter your password to delete your account.</p><input id="p" type="password" autocomplete="current-password"><button id="del" class="btn btn-danger">Delete my account</button></form>`,
      `<form id="f"><input id="p" type="password"><button id="ok">Confirm</button></form>`,
      // Only a label that says sign in or log in counts: not going on, nor an id, name or class.
      `<form id="f"><h2>Delete account</h2><input id="p" type="password"><button id="ok" name="submit">Confirm</button></form>`,
      `<form id="f"><h2>Delete account</h2><input id="p" type="password"><button id="ok">Continue</button></form>`,
      `<form id="f"><input id="p" type="password"><button id="ok" name="login" class="btn-signin">Confirm</button></form>`,
      `<div><h2>Delete account</h2><input id="p" type="password"><button id="ok" type="button">Continue</button></div>`,
      `<div><h2>Delete account</h2><input id="p" type="password"><div id="ok" role="button" class="submit">OK</div></div>`,
    ]) {
      render(html);
      const filling = await fill("p", { password: "secret" });
      expect(filling.form.kind, html).toBe("password");
      expect(await submit(filling), html).toBe(false);
      expect(events, html).toEqual([]);
    }
    for (const html of [
      `<form id="f"><input id="p" type="password"><button id="ok" name="submit">Log in</button></form>`,
      `<div><input id="p" type="password"><button id="ok" type="button" aria-label="Sign in"><svg></svg></button></div>`,
    ]) {
      events.length = 0;
      render(html);
      expect(await submit(await fill("p", { password: "secret" })), html).toBe(true);
      expect(events[0], html).toBe("click:ok");
    }
    events.length = 0;
    // An email and the current password read as a login.
    for (const html of [
      `<form id="f"><label for="e">New email</label><input id="e" type="email" name="new_email"><input id="p" type="password"><button id="chg">Change email</button></form>`,
      `<form id="f"><input id="e" type="email" name="email"><input id="p" type="password"><button id="del">Delete account</button></form>`,
    ]) {
      render(html);
      const change = await fill("p", { username: "ann@example.com", password: "secret" });
      expect(change.form.kind, html).toBe("login");
      expect(await submit(change), html).toBe(false);
      expect(events, html).toEqual([]);
    }

    // The password step of a sign-in, with a button of any words.
    render(`<form id="f"><input id="p" type="password"><button id="ok">Weiter</button></form>`);
    expect(await submit({ ...(await fill("p", { password: "secret" })), step: { account: "" } })).toBe(true);
    expect(events).toEqual(["click:ok", "submit:f"]);
  });

  it("submits a form without submit buttons as Enter would, with requestSubmit, never by GET with a password", async () => {
    /** The method of each submit: the form's, as no submit button names another. */
    const methods: string[] = [];
    document.addEventListener("submit", (event) => methods.push((event.target as HTMLFormElement).method), { signal: abort.signal });
    // One field to type in: Enter submits, by the form's method.
    render(`<form id="f" method="post"><input id="p" type="password"><button type="button">Help</button><button type="button">Cancel</button></form>`);
    expect(await submit({ ...(await fill("p", { password: "secret" })), step: { account: "" } })).toBe(true);
    expect(events).toEqual(["submit:f"]);
    expect(methods).toEqual(["post"]);

    // By GET, the password would go in the URL.
    events.length = 0;
    methods.length = 0;
    render(`<form id="f"><input id="p" type="password"><button type="button">Help</button></form>`);
    expect(await submit({ ...(await fill("p", { password: "secret" })), step: { account: "" } })).toBe(false);
    // Not outside the password step: nothing says it signs in.
    render(`<form id="f" method="post"><input id="p" type="password"></form>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(false);
    expect(events).toEqual([]);

    // A username alone may go by GET.
    render(`<form id="f"><input id="u" autocomplete="username"></form>`);
    expect(await submit(await fill("u", { username: "ann" }))).toBe(true);
    expect(methods).toEqual(["get"]);
  });

  it("submits nothing when its submit buttons are hidden: one may name another method or action", async () => {
    render(`
      <form id="f">
        <input id="u" name="email"><input id="p" type="password">
        <button formmethod="post" formaction="/session" style="display:none">Sign in</button>
      </form>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);
    expect(events).toEqual([]);
  });

  it("clicks the sign-in button that the page's script handles, in a form where Enter does nothing", async () => {
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go" type="button">Anmelden</button></form>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go"]);

    // Two of them: nothing tells which.
    events.length = 0;
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button type="button">Anmelden</button><button type="button">Hilfe</button></form>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);
    expect(events).toEqual([]);
  });

  it("clicks no control of another form, and looks no higher than the form of the fields", async () => {
    render(`
      <div>
        <form id="f"><input id="u" name="email"><input id="p" type="password"></form>
        <form id="g"><button id="other" type="button">Sign in</button></form>
      </div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);

    render(`<div><form id="f"><input id="u" name="email"><input id="p" type="password"></form><div id="other" role="button">Sign in</div></div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);

    render(`<div><input id="u" name="email"><input id="p" type="password"><form id="g"><button id="other" type="button">Sign in</button></form></div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);

    // A `[role=button]` has no form owner: the form around it is its form.
    render(`<div><input id="u" name="email"><input id="p" type="password"><form id="g"><div id="other" role="button">Sign in</div></form></div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);
    expect(events).toEqual([]);
  });
});

describe("outside a form", () => {
  it("clicks the only control near the fields, whatever its words", async () => {
    render(`<div><div><input id="u" autocomplete="username"></div><div id="next" role="button">Weiter</div></div><button id="far">Hilfe</button>`);
    expect(await submit(await fill("u", { username: "ann" }))).toBe(true);
    expect(events).toEqual(["click:next"]);
  });

  it("clicks the one control with a hint that it submits, among several", async () => {
    render(`<div><input id="u" name="email"><input id="p" type="password"><button id="help">Help</button><button id="go" class="btn btn-primary">Log in</button></div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go"]);

    events.length = 0;
    render(`<div><input id="u" name="email"><input id="p" type="password"><button id="help">Hilfe</button><button id="go" type="submit">Anmelden</button></div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go"]);

    // Its name, on the password step: off it, a form of the password alone needs a label that says so.
    events.length = 0;
    render(`<div><input id="p" type="password"><button>Hilfe</button><button id="go" data-testid="x" name="signIn">→</button></div>`);
    expect(await submit({ ...(await fill("p", { password: "secret" })), step: { account: "" } })).toBe(true);
    expect(events).toEqual(["click:go"]);
  });

  it("clicks nothing when it can't tell which", async () => {
    for (const buttons of [`<button>Hilfe</button><button>Anmelden</button>`, `<button>Sign in</button><button>Next</button>`]) {
      render(`<div><input id="p" type="password">${buttons}</div>`);
      expect(await submit(await fill("p", { password: "secret" })), buttons).toBe(false);
      expect(events, buttons).toEqual([]);
    }
  });

  it("skips controls that do something else, as a field's own toggle", async () => {
    render(`
      <div>
        <input id="u" name="email">
        <div><input id="p" type="password"><button aria-label="Toggle password visibility"></button></div>
        <a href="#" role="button">Forgot password?</a>
        <button>Sign in with Apple</button>
        <button>Create account</button>
        <button>Back</button>
        <div role="button">Use a passkey</div>
        <button id="go">Anmelden</button>
      </div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go"]);

    events.length = 0;
    byId<HTMLElement>("go").remove();
    byId("p").value = "";
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);
    expect(events).toEqual([]);
  });

  it("never takes a field's own eye or clear button, nor a control without words", async () => {
    // An eye with a generated class, next to the field.
    render(`<div><div class="field"><input id="p" type="password"><button type="button" class="css-1q2w3e"><svg></svg></button></div><button id="go" type="button">Sign in</button></div>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go"]);
    // With an eye that has a label, the only other control near the field doesn't count for its place alone.
    events.length = 0;
    render(`<div><div class="field"><input id="p" type="password"><button type="button" aria-label="Passwort anzeigen"><svg></svg></button></div><button type="button">Anmelden</button></div>`);
    expect(await submit({ ...(await fill("p", { password: "secret" })), step: { account: "" } })).toBe(false);
    expect(events).toEqual([]);

    // An icon button with a generated class one wrapper above the field, past the wrapper of its own.
    render(`<div><div class="field"><span><input id="u" autocomplete="username"></span><button type="button" class="sc-x7f3a"><svg></svg></button></div><button id="go" type="button">Weiter</button></div>`);
    expect(await submit(await fill("u", { username: "ann" }))).toBe(true);
    expect(events).toEqual(["click:go"]);
    // A class that says it submits is a hint all the same.
    events.length = 0;
    render(`<div><div><input id="u" autocomplete="username"></div><button id="go" class="submit-arrow"><svg></svg></button></div>`);
    expect(await submit(await fill("u", { username: "ann" }))).toBe(true);
    expect(events).toEqual(["click:go"]);

    // An eye without words, in a form whose sign-in is a plain button.
    events.length = 0;
    render(`<form id="f"><input name="email" readonly value="ann@example.com"><div><input id="p" type="password"><button type="button"><svg></svg></button></div><button id="go" type="button">Sign in</button></form>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go"]);

    // A clear button on a username step.
    events.length = 0;
    render(`<div><div><input id="u" autocomplete="username"><button type="button">×</button></div><button id="go" type="button">Weiter</button></div>`);
    expect(await submit(await fill("u", { username: "ann" }))).toBe(true);
    expect(events).toEqual(["click:go"]);

    // Alone.
    events.length = 0;
    render(`<div><input id="u" autocomplete="username"><button type="button"><svg></svg></button></div>`);
    expect(await submit(await fill("u", { username: "ann" }))).toBe(false);
    expect(events).toEqual([]);
  });

  it("looks no lower than the fields' common ancestor, where their own controls are", async () => {
    render(`
      <div>
        <div><input id="u" name="email"><button class="x1">×</button></div>
        <div><input id="p" type="password"><button class="x2">◉</button></div>
        <button id="go">Anmelden</button><button>Hilfe</button><button type="submit" id="submit">Anmelden</button>
      </div>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:submit"]);
  });

  it("skips hidden controls", async () => {
    render(`<div><input id="p" type="password"><button style="display:none">Sign in</button><button style="visibility:hidden">Sign in</button><button id="go">Sign in</button></div>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go"]);
  });

  it("looks across shadow roots", async () => {
    render(`<div><x-login id="host"></x-login><button id="go">Next</button><button>Help</button></div>`);
    const root = byId<HTMLElement>("host").attachShadow({ mode: "open" });
    root.innerHTML = `<div><input id="u" autocomplete="username"></div>`;
    expect(await submit(await fill("u", { username: "ann" }, root))).toBe(true);
    expect(events).toEqual(["click:go"]);

    // In the root of the field, next to the wrapper the walk comes from.
    events.length = 0;
    root.innerHTML = `<div><input id="u" autocomplete="username"></div><button id="inner">Weiter</button>`;
    let clicked = "";
    byId<HTMLElement>("inner", root).addEventListener("click", () => (clicked = "inner"));
    expect(await submit(await fill("u", { username: "ann" }, root))).toBe(true);
    expect(clicked).toBe("inner");
  });
});

describe("when it waits or does nothing", () => {
  it("waits for a disabled control to turn enabled, up to SUBMIT_WAIT_MS", async () => {
    for (const disabled of [`disabled`, `aria-disabled="true"`]) {
      render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go" ${disabled}>Sign in</button></form>`);
      const done = start(await fill("p", { username: "ann", password: "secret" }));
      await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS + SUBMIT_WAIT_MS / 2);
      expect(events, disabled).toEqual([]);
      byId<HTMLElement>("go").removeAttribute(disabled.replace(/=.*/, ""));
      await vi.advanceTimersByTimeAsync(SUBMIT_WAIT_MS / 2);
      expect(await done, disabled).toBe(true);
      expect(events, disabled).toEqual(["click:go", "submit:f"]);
      events.length = 0;
    }
  });

  it("gives up quietly on a control that stays disabled, without submitting the form another way", async () => {
    render(`<form id="f"><input id="p" type="password"><fieldset disabled><button id="go">Sign in</button></fieldset></form>`);
    const done = start(await fill("p", { password: "secret" }));
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS + SUBMIT_WAIT_MS);
    expect(await done).toBe(false);
    await vi.advanceTimersByTimeAsync(SUBMIT_WAIT_MS);
    expect(events).toEqual([]);
  });

  it("waits for a faded control, and gives up on one that stays so", async () => {
    render(`<div><input id="p" type="password"><button id="go" style="opacity:0">Sign in</button></div>`);
    const done = start(await fill("p", { password: "secret" }));
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS + SUBMIT_WAIT_MS);
    expect(await done).toBe(false);
    expect(events).toEqual([]);
  });

  it("submits nothing once the page changed a filled value", async () => {
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    const reset = await fill("p", { username: "ann", password: "secret" });
    byId("p").value = "";
    expect(await submit(reset)).toBe(false);

    // Nor while it waits for the control.
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go" disabled>Sign in</button></form>`);
    const done = start(await fill("p", { username: "ann", password: "secret" }));
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
    byId("u").value = "bob";
    byId<HTMLElement>("go").removeAttribute("disabled");
    await vi.advanceTimersByTimeAsync(SUBMIT_WAIT_MS);
    expect(await done).toBe(false);
    expect(events).toEqual([]);
  });

  it("compares the fields with what the fill wrote, not with what the page's handlers left", async () => {
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    byId("p").addEventListener("input", () => (byId("p").value = byId("p").value.slice(0, 3)));
    const truncated = await fill("p", { username: "ann", password: "secret" });
    expect(filled.get(byId("p"))).toBe("sec");
    expect(await submit(truncated)).toBe(false);
    expect(events).toEqual([]);
  });

  it("checks the password step's account again at each try, read-only or not", async () => {
    for (const readonly of ["readonly", ""]) {
      render(`<form id="f"><input id="u" name="email" ${readonly} value="ann"><input id="p" type="password"><button id="go" disabled>Sign in</button></form>`);
      const done = start({ ...(await fill("p", { password: "secret" })), step: { account: "ann" } });
      await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
      byId("u").value = "bob";
      byId<HTMLElement>("go").removeAttribute("disabled");
      await vi.advanceTimersByTimeAsync(SUBMIT_WAIT_MS);
      expect(await done, readonly).toBe(false);
      expect(events, readonly).toEqual([]);
    }
    // A step that showed no account, which shows one now.
    render(`<form id="f"><input id="u" name="email" readonly><input id="p" type="password"><button id="go" disabled>Sign in</button></form>`);
    const done = start({ ...(await fill("p", { password: "secret" })), step: { account: "" } });
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
    byId("u").value = "bob";
    byId<HTMLElement>("go").removeAttribute("disabled");
    await vi.advanceTimersByTimeAsync(SUBMIT_WAIT_MS);
    expect(await done).toBe(false);
    expect(events).toEqual([]);
  });

  it("checks the form again at each try: a field the page showed since, or fields moved to another form", async () => {
    render(`<form id="f"><input id="u" autocomplete="username"><input id="p" type="password" style="display:none"><button id="go">Next</button></form>`);
    const done = start(await fill("u", { username: "ann" }));
    byId("p").style.display = "";
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
    expect(await done).toBe(false);

    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form><form id="g"><button id="go2">Sign in</button></form>`);
    const moved = start(await fill("p", { username: "ann", password: "secret" }));
    byId("g").prepend(byId("u"), byId("p"));
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
    expect(await moved).toBe(false);
    expect(events).toEqual([]);
  });

  it("submits nothing once cancelled", async () => {
    render(`<form id="f"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    const cancel = new AbortController();
    const done = start(await fill("p", { password: "secret" }), cancel.signal);
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS - 1);
    cancel.abort();
    await vi.advanceTimersByTimeAsync(SUBMIT_WAIT_MS);
    expect(await done).toBe(false);
    expect(events).toEqual([]);
  });

  it("submits nothing once the user or the page signs in another way, or the page hides", async () => {
    const SIGN_IN = `<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form><button id="ext" type="button" form="f"><span id="icon">Send a code</span></button>`;
    const meanwhile: [string, () => void][] = [
      ["a click", () => trusted(byId("u"), new MouseEvent("click", { bubbles: true }))],
      ["a click on a control of the form outside it", () => trusted(byId("icon"), new MouseEvent("click", { bubbles: true }))],
      ["Enter", () => trusted(byId("p"), new KeyboardEvent("keydown", { key: "Enter", bubbles: true }))],
      ["the page's submit", () => byId<HTMLFormElement>("f").requestSubmit()],
      ["pagehide", () => window.dispatchEvent(new Event("pagehide"))],
      [
        "a hidden tab",
        () => {
          vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
          document.dispatchEvent(new Event("visibilitychange"));
        },
      ],
    ];
    for (const [name, act] of meanwhile) {
      render(SIGN_IN);
      const done = start(await fill("p", { username: "ann", password: "secret" }));
      await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS / 2);
      events.length = 0;
      act();
      await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS + SUBMIT_WAIT_MS);
      expect(await done, name).toBe(false);
      expect(events.filter((it) => it === "click:go"), name).toEqual([]);
      vi.restoreAllMocks();
    }
    // A tab in the background since before: no `visibilitychange` follows.
    render(SIGN_IN);
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(false);
    expect(events).toEqual([]);
    vi.restoreAllMocks();

    // The page's own click, as the fill's events may cause, isn't the user's.
    render(SIGN_IN);
    const done = start(await fill("p", { username: "ann", password: "secret" }));
    byId<HTMLElement>("u").click();
    byId<HTMLElement>("icon").click();
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS);
    expect(await done).toBe(true);
  });

  it("submits nothing once the page signed in on the fill's own events, watched from before the fill", async () => {
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    // An AJAX sign-in as the password changes.
    byId("p").addEventListener("change", () => byId<HTMLFormElement>("f").requestSubmit());
    const filling = await fill("p", { username: "ann", password: "secret" });
    expect(events).toEqual(["submit:f"]);
    const done = start(filling);
    await vi.advanceTimersByTimeAsync(SUBMIT_DELAY_MS + SUBMIT_WAIT_MS);
    expect(await done).toBe(false);
    expect(events).toEqual(["submit:f"]);

    // The fill's own events don't count.
    events.length = 0;
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    expect(await submit(await fill("p", { username: "ann", password: "secret" }))).toBe(true);
    expect(events).toEqual(["click:go", "submit:f"]);
  });

  it("submits a sign-in form only, after a fill of each login field the user could type in", async () => {
    // The login has no username.
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(false);

    // A read-only username shows the account: the password is all there is to fill.
    render(`<form id="f"><input id="u" name="email" readonly value="ann"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    expect(await submit(await fill("p", { password: "secret" }))).toBe(true);

    // A username a fill wrote before counts on the password step only.
    events.length = 0;
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    await fill("u", { username: "ann" });
    const password = await fill("p", { password: "secret" });
    expect(await submit(password)).toBe(false);
    expect(await submit({ ...again(password), step: { account: "ann" } })).toBe(true);
    expect(events).toEqual(["click:go", "submit:f"]);

    // A username the site filled with the account the background checked, on the password step only.
    events.length = 0;
    render(`<form id="f"><input id="u" name="email" value="ann"><input id="p" type="password"><button id="go">Sign in</button></form>`);
    const prefilled = await fill("p", { password: "secret" });
    expect(await submit({ ...prefilled, step: { account: "bob" } })).toBe(false);
    expect(await submit(again(prefilled))).toBe(false);
    expect(await submit({ ...again(prefilled), step: { account: "ann" } })).toBe(true);
    expect(events).toEqual(["click:go", "submit:f"]);

    // A sign-up.
    events.length = 0;
    render(`<form id="f"><input id="u" name="email"><input id="p" type="password" autocomplete="new-password"><button id="go">Sign up</button></form>`);
    const signUp = await fill("p", { username: "ann" });
    expect(signUp.written).toEqual([byId("u")]);
    expect(filledLogin(signUp, options)).toBeNull();
    expect(await submit(signUp)).toBe(false);
    expect(events).toEqual([]);
  });
});
