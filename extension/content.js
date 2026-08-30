function clog(level, msg, extra) {
  try {
    chrome.runtime.sendMessage({
      __kgLog: {
        ts: new Date().toISOString(),
        level: level,
        msg: String(msg),
        extra: extra === undefined ? undefined : String(extra),
      },
      __kgFrom: "content",
    });
  } catch (e) {}
}

function setValue(el, value) {
  // Use the native value setter so frameworks (React/Vue/Svelte) notice the
  // change — assigning .value directly bypasses their tracked state.
  const proto =
    el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, "value");
  if (setter && setter.set) setter.set.call(el, value);
  else el.value = value;
  // Fire the full sequence of events a real keystroke would, so any
  // validation / CSRF / framework handler (React, Vue, Svelte, jQuery…)
  // runs exactly as if the user typed the value.
  const seq = ["focus", "keydown", "keypress", "beforeinput", "input", "keyup", "change", "blur"];
  for (const type of seq) {
    el.dispatchEvent(new Event(type, { bubbles: true, composed: true, cancelable: true }));
  }
}

function submitForm(field) {
  if (!field || !field.form) return;
  const form = field.form;
  // Prefer clicking a real submit button so any JS submit handler runs;
  // otherwise use requestSubmit() / form.submit() as fallbacks.
  const submitBtn = form.querySelector('button[type="submit"], input[type="submit"]');
  if (submitBtn) {
    clog("info", "submit: clicking submit button", submitBtn.name || submitBtn.id || "(unnamed)");
    submitBtn.click();
    return;
  }
  if (typeof form.requestSubmit === "function") {
    clog("info", "submit: requestSubmit()");
    try {
      form.requestSubmit();
      return;
    } catch (e) {}
  }
  clog("info", "submit: form.submit()");
  try {
    form.submit();
  } catch (e) {
    clog("warn", "submit failed", String(e));
  }
}

function fillForm(creds) {
  const inputs = Array.from(document.querySelectorAll("input"));
  // Only visible, text-like inputs are fillable. Never touch hidden inputs
  // (CSRF tokens such as WHMCS `token`, Laravel `_token`, etc.) or buttons.
  const NON_FILLABLE = new Set([
    "hidden", "submit", "button", "reset", "image",
    "checkbox", "radio", "file", "range", "color",
  ]);
  const isTextInput = (i) => i && !NON_FILLABLE.has(i.type);
  const looksLikeCsrf = (i) => {
    const n = (i.name + " " + i.id).toLowerCase();
    return /\bcsrf\b|\bxsrf\b|authenticity_token|_token\b/.test(n) || n.trim() === "token";
  };
  const isCaptcha = (i) => {
    let el = i;
    while (el) {
      if (/captcha/i.test((el.className || "") + " " + (el.id || ""))) return true;
      el = el.parentElement;
    }
    return false;
  };
  const pick = (re) =>
    inputs.find(
      (i) => isTextInput(i) && re.test((i.name + i.id + i.autocomplete).toLowerCase()),
    );
  const userInput = pick(/user|email|login|mail|nombre/);
  const passInput = inputs.find((i) => i.type === "password");
  let otp = null;
  if (creds.totp != null) {
    otp = inputs.filter(isTextInput).find(
      (i) =>
        !looksLikeCsrf(i) &&
        !isCaptcha(i) &&
        /otp|totp|code|verification/.test(
          (i.name + i.id + i.autocomplete).toLowerCase(),
        ),
    );
  }

  clog("debug", "fill: fields", `inputs=${inputs.length} user=${!!userInput} pass=${!!passInput} otp=${!!otp}`);

  if (userInput && creds.username != null) {
    setValue(userInput, creds.username);
    clog("info", "fill: username set", userInput.name || userInput.id || "(unnamed)");
  }
  if (passInput && creds.password != null) {
    setValue(passInput, creds.password);
    clog("info", "fill: password set", passInput.name || passInput.id || "(unnamed)");
  }
  if (otp && creds.totp != null) {
    setValue(otp, creds.totp);
    clog("info", "fill: totp set", otp.name || otp.id || "(unnamed)");
  }
  clog("info", "fill: done", `user=${!!(userInput && creds.username != null)} pass=${!!(passInput && creds.password != null)} totp=${!!(otp && creds.totp != null)}`);

  if (creds.autoSubmit) {
    clog("info", "fill: autoSubmit requested");
    submitForm(passInput || userInput);
  }
}

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === "fill") {
    clog("info", "fill received", "from background");
    fillForm(msg);
  }
});
