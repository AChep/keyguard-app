// @vitest-environment happy-dom
// How fills set values: what pages and their frameworks see.
import { afterEach, describe, expect, it } from "vitest";
import { detectFormOf, detectForms, type DetectedForm } from "../src/lib/content/detect";
import { fillLogin, fillNewPassword, fillOtp, isPasswordStep, pageForm, setFieldValue } from "../src/lib/content/fill";
import { isFillable } from "../src/lib/content/guards";
import { byId, detectOptions, render, testLayout } from "./support/dom";

afterEach(() => {
  document.body.innerHTML = "";
});

const layout = testLayout();
const canFill = (input: HTMLInputElement) => isFillable(input, layout);

function formOf(id: string): DetectedForm {
  return detectFormOf(byId(id), detectOptions)!;
}

/** Records the events a page would see on [input]. */
function recordEvents(input: HTMLInputElement): Event[] {
  const events: Event[] = [];
  for (const type of ["focus", "blur", "keydown", "keypress", "input", "keyup", "change"]) {
    input.addEventListener(type, (event) => events.push(event));
  }
  return events;
}

describe("fill", () => {
  it("sets the value with the native setter, then fires input and change", async () => {
    render(`<input id="field">`);
    const input = byId("field");
    // React-style value tracking: an own `value` property on the element that
    // records assignments; React ignores an input event whose value it saw set.
    const native = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!;
    const assigned: string[] = [];
    Object.defineProperty(input, "value", {
      configurable: true,
      get() {
        return native.get!.call(this);
      },
      set(value: string) {
        assigned.push(value);
        native.set!.call(this, value);
      },
    });
    const events = recordEvents(input);
    let valueAtInput: string | null = null;
    input.addEventListener("input", () => (valueAtInput = input.value));

    expect(await setFieldValue(input, "ann@example.com", canFill)).toBe(true);

    expect(assigned).toEqual([]);
    expect(input.value).toBe("ann@example.com");
    expect(valueAtInput).toBe("ann@example.com");
    const inputEvent = events.find((it) => it.type === "input") as InputEvent;
    expect(inputEvent).toBeInstanceOf(InputEvent);
    expect(inputEvent.bubbles).toBe(true);
    expect(inputEvent.composed).toBe(true);
    expect(inputEvent.inputType).toBe("insertReplacementText");
    expect(events.find((it) => it.type === "change")!.bubbles).toBe(true);
    expect(document.activeElement).toBe(input);
  });

  it("fires key events around the write, in the order typing does", async () => {
    render(`<input id="field">`);
    const input = byId("field");
    const seen: string[] = [];
    for (const type of ["focus", "keydown", "keypress", "input", "keyup", "change"]) {
      input.addEventListener(type, () => seen.push(`${type}:${input.value}`));
    }
    const events = recordEvents(input);

    await setFieldValue(input, "ann", canFill);

    expect(seen).toEqual(["focus:", "keydown:", "keypress:", "input:ann", "keyup:ann", "change:ann"]);
    for (const event of events.filter((it) => it.type.startsWith("key"))) {
      expect(event).toBeInstanceOf(KeyboardEvent);
      expect(event.bubbles).toBe(true);
      expect(event.cancelable).toBe(true);
      expect(event.composed).toBe(true);
      expect((event as KeyboardEvent).key).toBe("");
    }
  });

  it("writes after the page's focus handlers and the microtasks they queue", async () => {
    render(`<input id="field">`);
    const input = byId("field");
    // A React OTP widget: focus resets the field to its state, a promise later.
    input.addEventListener("focus", () => queueMicrotask(() => (input.value = "")));

    await setFieldValue(input, "123456", canFill);

    expect(input.value).toBe("123456");
  });

  it("skips a field the page hid while it took focus", async () => {
    render(`<form><input id="u" name="email"><input id="p" type="password"></form>`);
    byId("p").addEventListener("focus", () => (byId("p").style.display = "none"));

    expect(await fillLogin(formOf("p"), { username: "ann", password: "secret" }, canFill)).toEqual([byId("u")]);
    expect(byId("p").value).toBe("");
  });

  it("blurs the last field of a login, then focuses it again", async () => {
    render(`<form><input id="u" name="email"><input id="p" type="password"></form>`);
    const username = recordEvents(byId("u"));
    const password = recordEvents(byId("p"));

    await fillLogin(formOf("p"), { username: "ann", password: "secret" }, canFill);

    expect(username.map((it) => it.type).filter((it) => it === "blur")).toEqual(["blur"]);
    expect(password.map((it) => it.type)).toEqual(["focus", "keydown", "keypress", "input", "keyup", "change", "blur", "focus"]);
    expect(document.activeElement).toBe(byId("p"));
  });

  it("sends key events out of an open shadow root", async () => {
    render(`<x-login id="host"></x-login>`);
    const host = byId<HTMLElement>("host");
    const root = host.attachShadow({ mode: "open" });
    root.innerHTML = `<input id="field">`;
    const seen: string[] = [];
    for (const type of ["keydown", "keypress", "input", "keyup"]) host.addEventListener(type, () => seen.push(type));

    await setFieldValue(byId("field", root), "ann", canFill);

    expect(byId("field", root).value).toBe("ann");
    expect(seen).toEqual(["keydown", "keypress", "input", "keyup"]);
  });

  it("fills a login, ending on the password", async () => {
    render(`<form><input id="u" name="email"><input id="p" type="password"></form>`);
    const filled = await fillLogin(formOf("p"), { username: "ann", password: "secret" }, canFill);
    expect(filled).toEqual([byId("u"), byId("p")]);
    expect(byId("u").value).toBe("ann");
    expect(byId("p").value).toBe("secret");
    expect(document.activeElement).toBe(byId("p"));
  });

  it("never fills hidden, disabled or read-only fields", async () => {
    render(`
      <form id="a"><input id="u1" name="user" readonly value="kept"><input id="p1" type="password"></form>
      <form id="b"><input id="u2" name="user"><input id="p2" type="password"></form>`);
    // Read-only once focused.
    expect(await fillLogin(formOf("p1"), { username: "ann", password: "secret" }, canFill)).toEqual([byId("p1")]);
    expect(byId("u1").value).toBe("kept");

    // Fields that change after detection are checked again at fill time.
    const second = formOf("p2");
    byId("u2").disabled = true;
    byId("p2").style.display = "none";
    expect(await fillLogin(second, { username: "ann", password: "secret" }, canFill)).toEqual([]);
    expect(byId("u2").value).toBe("");
    expect(byId("p2").value).toBe("");

    const hidden = document.createElement("input");
    hidden.type = "hidden";
    document.body.append(hidden);
    expect(canFill(hidden)).toBe(false);
  });

  it("fills fields that turn writable when they get focus", async () => {
    render(`<form><input id="u" name="email" readonly><input id="p" type="password" readonly></form>`);
    for (const id of ["u", "p"]) byId(id).addEventListener("focus", () => byId(id).removeAttribute("readonly"));

    expect(await fillLogin(formOf("p"), { username: "ann", password: "secret" }, canFill)).toEqual([byId("u"), byId("p")]);
    expect(byId("u").value).toBe("ann");
    expect(byId("p").value).toBe("secret");
  });

  it("skips the account a password step shows read-only, with no events but focus", async () => {
    render(`<form><input id="account" name="email" readonly value="ann@example.com"><input id="p" type="password"></form>`);
    const events = recordEvents(byId("account"));

    expect(await fillLogin(formOf("p"), { username: "bob", password: "secret" }, canFill)).toEqual([byId("p")]);
    expect(byId("account").value).toBe("ann@example.com");
    expect(byId("p").value).toBe("secret");
    expect(events.map((it) => it.type)).toEqual(["focus", "blur"]);
    expect(document.activeElement).toBe(byId("p"));
  });

  it("takes a form a fill can write for the popup's fill over one that only shows the account", () => {
    render(`
      <form id="account"><input name="email" readonly value="ann@example.com"><input type="password" readonly value="********"></form>
      <form id="login"><input name="email"><input type="password"></form>`);
    const [account, login] = detectForms(document, detectOptions);
    expect(account!.root).toBe(byId("account"));
    expect(pageForm(null, () => [account!, login!], canFill)).toBe(login);
    // Even when it was focused last.
    expect(pageForm(account!, () => [account!, login!], canFill)).toBe(login);
    // Alone, it's still taken: its fields may turn writable on focus.
    expect(pageForm(null, () => [account!], canFill)).toBe(account);
  });

  it("fills only the username of a sign-up form, and the current password of a change", async () => {
    render(`
      <form id="signup"><input id="su" autocomplete="username"><input id="sp1" type="password"><input id="sp2" type="password"></form>
      <form id="change"><input id="cp" type="password" autocomplete="current-password"><input id="np" type="password" autocomplete="new-password"></form>`);
    expect(await fillLogin(formOf("sp1"), { username: "ann", password: "secret" }, canFill)).toEqual([byId("su")]);
    expect(byId("sp1").value).toBe("");
    expect(await fillLogin(formOf("cp"), { password: "old" }, canFill)).toEqual([byId("cp")]);
    expect(byId("np").value).toBe("");
  });

  it("fills a generated password into the new password and its confirmation", async () => {
    render(`<form><input id="cur" type="password" name="current"><input id="n1" type="password"><input id="n2" type="password"></form>`);
    expect(await fillNewPassword(formOf("n1"), "Gen3rated!", canFill)).toEqual([byId("n1"), byId("n2")]);
    expect(byId("n2").value).toBe("Gen3rated!");
    expect(byId("cur").value).toBe("");
  });

  it("fills a one-time code into one field, or one digit per box", async () => {
    render(`
      <form id="one"><input id="code" autocomplete="one-time-code"></form>
      <form id="boxes"><div>${[1, 2, 3, 4, 5, 6].map((i) => `<input id="b${i}" maxlength="1">`).join("")}</div></form>`);
    expect(await fillOtp(formOf("code"), "123456", canFill)).toEqual([byId("code")]);
    expect(byId("code").value).toBe("123456");

    const events = recordEvents(byId("b6"));
    await fillOtp(formOf("b1"), "654321", canFill);
    expect([1, 2, 3, 4, 5, 6].map((i) => byId(`b${i}`).value).join("")).toBe("654321");
    expect(events.map((it) => it.type)).toEqual(["focus", "keydown", "keypress", "input", "keyup", "change", "blur", "focus"]);
  });
});

describe("the password step", () => {
  /** Nothing filled in the document. */
  const none = () => undefined;

  it("is a login or password form with an empty password, whose username is missing, hidden or shows an account", () => {
    render(`
      <form><input id="p1" type="password"></form>
      <form><input name="email" style="display: none" value="ann@example.com"><input id="p2" type="password"></form>
      <form><input name="email" style="visibility: hidden" value="ann@example.com"><input id="p3" type="password"></form>
      <form><input name="email" readonly value="ann@example.com"><input id="p4" type="password"></form>
      <form><input name="email" value="ann@example.com"><input id="p5" type="password"></form>`);
    for (const id of ["p1", "p2", "p3", "p4", "p5"]) expect(isPasswordStep(formOf(id), canFill, none), id).toBe(true);
    expect(formOf("p3").kind).toBe("login");
    expect(formOf("p4").kind).toBe("login");
  });

  it("isn't one once a field Keyguard filled holds something else", () => {
    render(`<form><input id="u" name="email"><input id="p" type="password"></form>`);
    const form = formOf("p");
    const values = new Map([[byId("u"), "ann@example.com"]]);
    const filled = (input: HTMLInputElement) => values.get(input);
    byId("u").value = "ann@example.com";
    expect(isPasswordStep(form, canFill, filled)).toBe(true);
    // The user typed another account since, which the page may then show read-only.
    byId("u").value = "bob@example.com";
    expect(isPasswordStep(form, canFill, filled)).toBe(false);
    byId("u").readOnly = true;
    expect(isPasswordStep(form, canFill, filled)).toBe(false);

    // A password Keyguard filled that the page emptied, as after a wrong one.
    byId("u").value = "ann@example.com";
    values.set(byId("p"), "secret");
    expect(isPasswordStep(form, canFill, filled)).toBe(false);
  });

  it("isn't a form with a username to type, a password filled or hidden, or a new password", () => {
    render(`
      <form><input name="email"><input id="empty" type="password"></form>
      <form><input name="email" readonly><input id="trick" type="password"></form>
      <form><input name="email" readonly value="ann@example.com"><input id="filled" type="password" value="secret"></form>
      <form><input id="covered" type="password" style="visibility: hidden"></form>
      <form><input name="email" readonly value="ann@example.com"><input id="signup" type="password" autocomplete="new-password"></form>
      <form><input id="change" type="password" autocomplete="current-password"><input type="password" autocomplete="new-password"></form>`);
    for (const id of ["empty", "trick", "filled", "covered", "signup", "change"]) {
      expect(isPasswordStep(formOf(id), canFill, none), id).toBe(false);
    }
  });
});
