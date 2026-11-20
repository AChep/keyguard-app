// @vitest-environment happy-dom
// Form and field detection on fixture forms.
import { afterEach, describe, expect, it } from "vitest";
import {
  classifyForm,
  collectInputs,
  describe as describeField,
  detectFormOf,
  detectForms,
  formRootOf,
  roleIn,
  type DetectedForm,
  type DetectOptions,
} from "../src/lib/content/detect";
import { byId, detectOptions, render } from "./support/dom";

afterEach(() => {
  document.body.innerHTML = "";
});

function formOf(id: string, root: Document | ShadowRoot = document): DetectedForm {
  const form = detectFormOf(byId(id, root), detectOptions);
  if (form === null) throw new Error(`No form for #${id}`);
  return form;
}

describe("detect", () => {
  it("finds a classic login", () => {
    render(`
      <form action="/session">
        <label for="email">Email</label><input id="email" name="email" type="email">
        <input id="password" name="password" type="password">
        <button>Sign in</button>
      </form>`);
    const form = formOf("password");
    expect(form.kind).toBe("login");
    expect(form.username).toBe(byId("email"));
    expect(form.currentPassword).toBe(byId("password"));
    expect(roleIn(form, byId("email"))).toBe("username");
    expect(roleIn(form, byId("password"))).toBe("current-password");
  });

  it("takes the text field right before the password as its username", () => {
    render(`
      <form>
        <input id="search" name="q" type="search">
        <input id="nick" name="nick" type="text">
        <input id="pass" type="password">
      </form>`);
    expect(formOf("pass").username).toBe(byId("nick"));
  });

  it("reads autocomplete tokens, including section names and webauthn", () => {
    render(`
      <form>
        <input id="user" name="field1" autocomplete="section-login username webauthn">
        <input id="other" name="field2">
        <input id="pass" name="field3" type="password" autocomplete="current-password">
      </form>`);
    const form = formOf("pass");
    expect(form.kind).toBe("login");
    expect(form.username).toBe(byId("user"));
    expect(form.webauthn).toEqual([byId("user")]);
  });

  it("finds the username step of a username-first sign-in", () => {
    render(`
      <form>
        <input id="identifier" name="identifier" type="text" autocomplete="username">
        <button>Next</button>
      </form>`);
    const form = formOf("identifier");
    expect(form.kind).toBe("username");
    expect(roleIn(form, byId("identifier"))).toBe("username");
  });

  it("finds the password step that follows, with a password only", () => {
    render(`<form><input id="pass" type="password" autocomplete="current-password"></form>`);
    const form = formOf("pass");
    expect(form.kind).toBe("password");
    expect(form.username).toBeNull();
  });

  it("ignores a newsletter and long forms that happen to ask for an email", () => {
    render(`
      <form id="news"><input id="subscribe" name="email" type="email" placeholder="Subscribe to our newsletter"></form>
      <form id="contact">
        <input id="name" name="name"><input id="mail" name="email" type="email">
        <input id="company" name="company"><input id="phone" name="phone" type="tel">
      </form>`);
    expect(detectFormOf(byId("subscribe"), detectOptions)).toBeNull();
    expect(detectFormOf(byId("mail"), detectOptions)).toBeNull();
  });

  it("finds a sign-up form with a confirmation", () => {
    render(`
      <form>
        <input id="username" name="username" autocomplete="username">
        <input id="email" name="email" type="email" autocomplete="email">
        <input id="password" name="password" type="password">
        <input id="confirm" name="password_confirmation" type="password">
      </form>`);
    const form = formOf("password");
    expect(form.kind).toBe("new-password");
    expect(form.username).toBe(byId("username"));
    expect(form.currentPassword).toBeNull();
    expect(form.newPasswords).toEqual([byId("password"), byId("confirm")]);
    expect(roleIn(form, byId("confirm"))).toBe("new-password");
  });

  it("finds a single new password by its hint", () => {
    render(`<form><input id="u" autocomplete="email" type="email"><input id="p" type="password" autocomplete="new-password"></form>`);
    const form = formOf("p");
    expect(form.kind).toBe("new-password");
    expect(form.newPasswords).toEqual([byId("p")]);
  });

  it("finds change-password forms by hints, by words and by count", () => {
    render(`
      <form id="hints">
        <input id="a1" type="password" autocomplete="new-password">
        <input id="a2" type="password" autocomplete="current-password">
        <input id="a3" type="password" autocomplete="new-password">
      </form>
      <form id="words">
        <input id="b1" type="password" name="oldPassword">
        <input id="b2" type="password" name="newPassword">
      </form>
      <form id="count">
        <input id="c1" type="password"><input id="c2" type="password"><input id="c3" type="password">
      </form>`);
    const hints = formOf("a1");
    expect(hints.kind).toBe("change-password");
    expect(hints.currentPassword).toBe(byId("a2"));
    expect(hints.newPasswords).toEqual([byId("a1"), byId("a3")]);
    const words = formOf("b1");
    expect(words.kind).toBe("change-password");
    expect(words.currentPassword).toBe(byId("b1"));
    expect(words.newPasswords).toEqual([byId("b2")]);
    const count = formOf("c1");
    expect(count.currentPassword).toBe(byId("c1"));
    expect(count.newPasswords).toEqual([byId("c2"), byId("c3")]);
  });

  it("finds a one-time code in one field", () => {
    render(`
      <form id="a"><input id="code" name="code" inputmode="numeric" maxlength="6"></form>
      <form id="b"><input id="otc" autocomplete="one-time-code"></form>
      <form id="c"><input id="totp" name="totp_token"></form>
      <form id="d"><input id="promo" name="promo_code"></form>`);
    for (const id of ["code", "otc", "totp"]) {
      const form = formOf(id);
      expect(form.kind, id).toBe("otp");
      expect(roleIn(form, byId(id))).toBe("otp");
    }
    expect(detectFormOf(byId("promo"), detectOptions)).toBeNull();
  });

  it("finds a one-time code entered in six boxes", () => {
    render(`
      <form>
        <div class="boxes">
          ${[1, 2, 3, 4, 5, 6].map((i) => `<span><input id="d${i}" maxlength="1" inputmode="numeric"></span>`).join("")}
        </div>
        <input id="remember" type="checkbox">
      </form>`);
    const form = formOf("d3");
    expect(form.kind).toBe("otp");
    expect(form.otp).toEqual([1, 2, 3, 4, 5, 6].map((i) => byId(`d${i}`)));
  });

  it("skips hidden, disabled and non-text fields", () => {
    render(`
      <form>
        <input id="honeypot" name="username" style="display:none">
        <input id="locked" name="login" disabled>
        <input id="user" name="user">
        <input id="token" type="hidden" name="csrf">
        <input id="pass" type="password">
        <input id="ghost" type="password" style="display:none">
      </form>`);
    const form = formOf("pass");
    expect(form.kind).toBe("login");
    expect(form.username).toBe(byId("user"));
    expect(form.currentPassword).toBe(byId("pass"));
  });

  it("keeps a shown password, whose type became text, by its hint", () => {
    render(`<form><input id="u" name="email"><input id="p" type="text" autocomplete="current-password"></form>`);
    const form = formOf("p");
    expect(form.kind).toBe("login");
    expect(form.currentPassword).toBe(byId("p"));
  });

  it("groups fields outside a form by their document", () => {
    render(`<div><input id="u" name="login"></div><div><input id="p" type="password"></div>`);
    const form = formOf("p");
    expect(form.root).toBe(document);
    expect(form.username).toBe(byId("u"));
  });

  it("finds forms in open shadow roots, and closed ones where reachable", () => {
    render(`<login-widget id="open"></login-widget><secret-widget id="closed"></secret-widget>`);
    const open = byId<HTMLElement>("open").attachShadow({ mode: "open" });
    open.innerHTML = `<form><input id="u" name="username"><input id="p" type="password"></form>`;
    const closedHost = byId<HTMLElement>("closed");
    const closed = closedHost.attachShadow({ mode: "closed" });
    closed.innerHTML = `<form><input id="cu" autocomplete="username"><input id="cp" type="password"></form>`;

    const inShadow = formOf("p", open);
    expect(inShadow.kind).toBe("login");
    expect(inShadow.username).toBe(byId("u", open));

    expect(detectForms(document, detectOptions)).toHaveLength(1);
    const reachable = { ...detectOptions, shadowRootOf: (e: Element) => e.shadowRoot ?? (e === closedHost ? closed : null) };
    const forms = detectForms(document, reachable);
    expect(forms.map((it) => it.kind)).toEqual(["login", "login"]);
    expect(forms[1]!.currentPassword).toBe(byId("cp", closed));
  });

  it("takes the fields outside a form from their own tree, as walking every shadow root and filtering did", () => {
    render(`
      <input id="u" name="login"><input id="p" type="password">
      <form id="f"><input id="fu" name="username"><input id="fp" type="password"></form>
      <input id="linked" name="email" form="f">
      <x-login id="host"></x-login>`);
    const open = byId<HTMLElement>("host").attachShadow({ mode: "open" });
    open.innerHTML = `<input id="su" autocomplete="username"><x-inner id="inner"></x-inner><input id="sp" type="password">`;
    const innerHost = byId<HTMLElement>("inner", open);
    const closed = innerHost.attachShadow({ mode: "closed" });
    closed.innerHTML = `<input id="cu" name="email"><input id="cp" type="password" autocomplete="new-password"><input id="cc" type="password" autocomplete="new-password">`;
    const options: DetectOptions = { ...detectOptions, shadowRootOf: (e) => e.shadowRoot ?? (e === innerHost ? closed : null) };
    // The walk it replaces: every input under the root, through shadow roots, then those of its own tree.
    const walked = (input: HTMLInputElement) => {
      const root = formRootOf(input) as Document | ShadowRoot;
      const inputs = collectInputs(root, options).filter((it) => it.form === null && it.getRootNode() === root);
      return classifyForm(root, inputs, options);
    };
    const ids = (form: DetectedForm | null) =>
      form && {
        kind: form.kind,
        username: form.username?.id,
        currentPassword: form.currentPassword?.id,
        newPasswords: form.newPasswords.map((it) => it.id),
        otp: form.otp.map((it) => it.id),
        webauthn: form.webauthn.map((it) => it.id),
      };

    const fields: [string, Document | ShadowRoot][] = [
      ["u", document],
      ["p", document],
      ["su", open],
      ["sp", open],
      ["cu", closed],
      ["cp", closed],
      ["cc", closed],
    ];
    for (const [id, root] of fields) {
      const input = byId(id, root);
      const form = detectFormOf(input, options);
      expect(form?.root, id).toBe(root);
      expect(ids(form), id).toEqual(ids(walked(input)));
    }
    expect(ids(detectFormOf(byId("p"), options))).toMatchObject({ kind: "login", username: "u", currentPassword: "p" });
    expect(ids(detectFormOf(byId("sp", open), options))).toMatchObject({ kind: "login", username: "su", currentPassword: "sp" });
    expect(ids(detectFormOf(byId("cp", closed), options))).toMatchObject({ kind: "new-password", username: "cu", newPasswords: ["cp", "cc"] });
  });

  it("skips the extension's own UI", () => {
    render(`<div id="ui"></div><input id="p" type="password">`);
    const ui = byId<HTMLElement>("ui");
    const root = ui.attachShadow({ mode: "open" });
    root.innerHTML = `<input type="password">`;
    expect(collectInputs(document, { ignore: ui })).toEqual([byId("p")]);
  });

  it("leaves fields and forms the page opts out of password managers alone", () => {
    render(`
      <form id="a"><input id="u1" name="email" data-1p-ignore><input id="p1" type="password" data-lpignore="true"></form>
      <form id="b" data-keyguard-ignore><input id="u2" name="username"><input id="p2" type="password"></form>
      <form id="c"><input id="u3" name="email" data-bwignore=""><input id="p3" type="password"></form>
      <form id="d"><input id="u4" name="email"><input id="p4" type="password" data-protonpass-ignore="true"></form>
      <form id="e"><input id="u5" name="email" data-lpignore="false"><input id="p5" type="password" data-1p-ignore="FALSE"></form>`);
    for (const id of ["u1", "p1", "u2", "p2"]) expect(detectFormOf(byId(id), detectOptions), id).toBeNull();
    // An opted-out username isn't taken, nor is an opted-out password.
    expect(formOf("p3").username).toBeNull();
    expect(roleIn(formOf("p3"), byId("u3"))).toBeNull();
    expect(formOf("u4").kind).toBe("username");
    // "false" opts nothing out.
    expect(formOf("p5").kind).toBe("login");
    expect(formOf("p5").username).toBe(byId("u5"));
  });

  it("takes read-only fields, which pages make writable on focus", () => {
    render(`
      <form id="trick"><input id="u" name="email" readonly><input id="p" type="password" readonly></form>
      <form id="step"><input id="account" name="email" readonly value="ann@example.com"><input id="sp" type="password"></form>`);
    const trick = formOf("p");
    expect(trick.kind).toBe("login");
    expect(trick.username).toBe(byId("u"));
    expect(trick.currentPassword).toBe(byId("p"));
    // The account a password step shows; fills skip it once it stays read-only.
    expect(formOf("sp").username).toBe(byId("account"));
  });

  it("takes a read-only field for a role only when no writable one is there", () => {
    render(`
      <form id="a"><input id="user" name="user"><input id="domain" readonly value="CORP"><input id="p" type="password"></form>
      <form id="b"><input id="shown" type="password" readonly value="********"><input id="n1" type="password"><input id="n2" type="password"></form>
      <form id="c"><input id="secret" name="totp_secret" readonly value="JBSWY3DP"><input id="code" name="code" inputmode="numeric" maxlength="6"></form>`);
    expect(formOf("p").username).toBe(byId("user"));
    expect(formOf("n1").kind).toBe("new-password");
    expect(formOf("n1").newPasswords).toEqual([byId("n1"), byId("n2")]);
    expect(formOf("code").otp).toEqual([byId("code")]);
  });

  it("takes no read-only username step: it shows the account, as on a profile page", () => {
    render(`<form><input id="email" name="email" type="email" readonly value="ann@example.com"><button>Save</button></form>`);
    expect(detectFormOf(byId("email"), detectOptions)).toBeNull();
  });

  it("reads the text right before a field without a label", () => {
    render(`<div>Email</div><input id="f1" name="f1">`);
    const form = formOf("f1");
    expect(form.kind).toBe("username");
    expect(form.username).toBe(byId("f1"));

    render(`
      <form><div class="row"><span>Username</span><div class="control"><input id="wrapped" name="f2"></div></div></form>
      <form><label>Email address</label><input id="unlinked" name="f3"></form>`);
    expect(formOf("wrapped").kind).toBe("username");
    expect(formOf("unlinked").kind).toBe("username");
  });

  it("keeps a search box next to Search and a newsletter box next to Subscribe out", () => {
    render(`
      <form role="search"><span>Search</span><input id="search" name="user"></form>
      <form><p>Subscribe</p><input id="news" type="email" name="email"></form>`);
    expect(detectFormOf(byId("search"), detectOptions)).toBeNull();
    expect(detectFormOf(byId("news"), detectOptions)).toBeNull();
  });

  it("takes no text from another field, a link, a label of another field, prose, or the field itself", () => {
    render(`
      <form><label for="x">Email</label><input id="x" name="x"><input id="f1"></form>
      <form><span>Email</span><a href="/help">Forgot your login?</a><input id="f2"></form>
      <form><label for="elsewhere">Email</label><input id="f3"><input id="elsewhere" type="checkbox"></form>
      <form><p>We will send a link to the email address of your account to reset your password</p><input id="f4"></form>
      <form><span>ann@example.com</span><input id="f5" value="ann@example.com"></form>
      <form><span>Email</span><input id="f6" placeholder="Email"></form>
      <form><div><span>Email</span><div><input id="f7"><input id="f8"></div></div></form>`);
    expect(describeField(byId("f1"))).toBe("f1");
    expect(describeField(byId("f2"))).toBe("f2");
    expect(describeField(byId("f3"))).toBe("f3");
    expect(describeField(byId("f4"))).toBe("f4");
    expect(describeField(byId("f5"))).toBe("f5");
    expect(describeField(byId("f6"))).toBe("f6 Email");
    // Out of a wrapper only when it holds the field alone.
    expect(describeField(byId("f7"))).toBe("f7");
  });

  it("finds the login of a frame in that frame's document", () => {
    const frame = document.implementation.createHTMLDocument("frame");
    frame.body.innerHTML = `<form><input id="u" autocomplete="username"><input id="p" type="password"></form>`;
    const forms = detectForms(frame, { rendered: () => true });
    expect(forms).toHaveLength(1);
    expect(forms[0]!.username).toBe(frame.getElementById("u"));
  });
});
