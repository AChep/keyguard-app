// Passkeys: wraps the page's WebAuthn calls in its main world, before any page
// script runs. It has no extension APIs; it talks to the isolated
// webauthn-bridge content script through `window.postMessage`. See
// src/lib/webauthn/page.ts.
//
// Manifest content scripts run in the main world from Firefox 128 (the
// extension needs 140) and Chromium 111, so nothing injects the script by
// hand. An older Chromium runs it in the isolated world, where it wraps
// nothing the page calls.
import { defineContentScript } from "wxt/utils/define-content-script";
import { installWebAuthn } from "../src/lib/webauthn/page";

export default defineContentScript({
  matches: ["http://*/*", "https://*/*"],
  world: "MAIN",
  runAt: "document_start",
  allFrames: true,
  matchAboutBlank: true,
  main() {
    try {
      installWebAuthn(window);
    } catch {
      // The page keeps the browser's own WebAuthn.
    }
  },
});
