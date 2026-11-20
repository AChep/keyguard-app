// Passkeys: relays the main world's WebAuthn requests (webauthn.content.ts) to
// the background. It runs at document_start, as the main-world script does, so
// it answers before page scripts call WebAuthn. See
// src/lib/content/webauthnBridge.ts.
import { browser } from "wxt/browser";
import { defineContentScript } from "wxt/utils/define-content-script";
import { startWebAuthnBridge, type BridgePort } from "../src/lib/content/webauthnBridge";
import { WEBAUTHN_PORT } from "../src/lib/webauthn/bridge";

export default defineContentScript({
  matches: ["http://*/*", "https://*/*"],
  allFrames: true,
  matchAboutBlank: true,
  runAt: "document_start",
  // WXT's start message would be visible to the page.
  noScriptStartedPostMessage: true,
  main(ctx) {
    const abort = new AbortController();
    ctx.onInvalidated(() => abort.abort());
    startWebAuthnBridge({
      win: window,
      connect: () => browser.runtime.connect({ name: WEBAUTHN_PORT }) as unknown as BridgePort,
      signal: abort.signal,
    });
  },
});
