// The background's side of passkey requests (protocol/README.md §5
// `passkey_get`, `passkey_create`): one port per request from the
// webauthn-bridge content script, see ../webauthn/bridge.ts.
//
// - The origin comes from the port's sender, never from the request.
// - A request goes to the browser (`fallback`) when the user turned passkeys
//   off, Keyguard isn't paired and connected, the app lacks `passkeys`, the
//   frame has an ancestor of another origin, the request is silent, a `create`
//   asks for a cross-platform authenticator (Keyguard is a platform one), a
//   modal request comes from a tab that isn't the active one of its window,
//   the session ends, or the app answers `not_supported` (no passkey, "Use
//   another device", a blocked page).
// - A locked vault goes to the app all the same: the app shows its unlock
//   prompt first, and closing it rejects with `NotAllowedError`.
// - Other app errors reject as protocol §5 lists: `invalid_state` with
//   `InvalidStateError`, `security` with `SecurityError`, the rest with
//   `NotAllowedError`.
// - A conditional `get` waits here until the user picks a passkey in the inline
//   menu (./pages.ts). It goes to the browser when a pick falls back, when the
//   session ends, or when the user turns passkeys off: the browser's own
//   conditional request, which the page runs alongside, then answers it.
// - Disconnecting the port cancels any request: the page aborted, the frame
//   navigated, or the tab closed. A request in flight then gets a `cancel`,
//   which closes the app's window.
// - A request of a tab in a private window says so (`private_window`).
// - Options that nest deeper than the app takes fail the request alone, in
//   the protocol session, and it goes to the browser.
import type { ExtensionOptions, OptionsStore } from "../options/store";
import type { JsonObject } from "../protocol/framing";
import { InvalidResponse, privateWindow } from "../protocol/messages";
import { ProtocolError } from "../protocol/session";
import { isObject } from "../util/json";
import { parseWebAuthnRequest, type WebAuthnRequest, type WebAuthnResult } from "../webauthn/bridge";
import { credentialProblem } from "../webauthn/credential";
import { CallFailure } from "./controller";
import { frameOf, sameDocumentLoose, type FrameRef } from "./frames";
import type { AppAccess, PagePort } from "./pages";

const FALLBACK: WebAuthnResult = { kind: "fallback" };

export interface PasskeyDeps {
  extensionId: string;
  app: AppAccess;
  options: OptionsStore;
  /** A frame's pending conditional request came or went: its inline menus change. */
  conditionalChanged(frame: FrameRef): void;
}

/** A conditional `get` that waits for the user's pick. */
export interface ConditionalRequest {
  readonly frame: FrameRef;
  readonly options: JsonObject;
  /** The RP ID whose passkeys the menu lists. */
  readonly rpId: string;
}

interface PendingConditional extends ConditionalRequest {
  port: PagePort;
  /** Aborts when the page's request ends: a pick in flight gets a `cancel`. */
  signal: AbortSignal;
}

/** What an app error means for the page, as protocol §5 lists. */
function errorResult(error: unknown): WebAuthnResult {
  if (error instanceof ProtocolError) {
    switch (error.code) {
      case "not_supported":
        return FALLBACK;
      // The page aborted; its port is gone, so this goes nowhere.
      case "cancelled":
        return { kind: "error", name: "AbortError" };
      case "invalid_state":
        return { kind: "error", name: "InvalidStateError" };
      case "security":
        return { kind: "error", name: "SecurityError" };
      default:
        return { kind: "error", name: "NotAllowedError" };
    }
  }
  // A malformed response; otherwise the session ended, and Keyguard is gone.
  return error instanceof InvalidResponse ? { kind: "error", name: "NotAllowedError" } : FALLBACK;
}

/**
 * The RP ID of a request: its `rpId`, or else the origin's host. The app
 * checks it against the origin, related origins included (protocol §5).
 */
export function rpIdOf(options: JsonObject, origin: string): string {
  return typeof options.rpId === "string" ? options.rpId : new URL(origin).hostname;
}

/** Whether a `create` asks for a roaming authenticator, as a security key: not Keyguard, which reports `platform`. */
function wantsCrossPlatform(options: JsonObject): boolean {
  const selection = options.authenticatorSelection;
  return isObject(selection) && selection.authenticatorAttachment === "cross-platform";
}

/**
 * The content script's answer, checked: a top frame has no ancestors; a
 * subframe claiming same-origin ancestors must at least share the tab's
 * origin, which its frame tells: `topOrigin` is unset only for the same
 * origin, and `null` when unknown.
 */
function sameOriginWithAncestors(frame: FrameRef, claimed: boolean): boolean {
  return frame.frameId === 0 || (claimed && frame.topOrigin === undefined);
}

export class PasskeyController {
  /** The conditional requests that wait: a request leaves when it ends. */
  private readonly conditionals = new Set<PendingConditional>();
  /**
   * The passkeys option as the last change set it, on before any: a read of
   * the options that started before a change returns the old value.
   */
  private enabled = true;

  constructor(private readonly deps: PasskeyDeps) {}

  /** A port of the webauthn-bridge content script. */
  connect(port: PagePort): void {
    const frame = frameOf(port.sender, this.deps.extensionId);
    // As the browser tells; the content script checks the document's focus too.
    const background = port.sender?.tab?.active === false;
    // Aborts when the port disconnects: the request in flight gets a `cancel`.
    const abort = new AbortController();
    const signal = abort.signal;
    let conditional: PendingConditional | null = null;
    let received = false;
    port.onDisconnect.addListener(() => {
      abort.abort();
      if (conditional !== null) this.drop(conditional);
    });
    port.onMessage.addListener((raw) => {
      // One request per port.
      if (received) return;
      received = true;
      const request = parseWebAuthnRequest(raw);
      if (request === null) {
        this.reply(port, FALLBACK);
        return;
      }
      void this.serve(frame, request, signal, background).then(
        (result) => {
          if (signal.aborted) return;
          if (result === "conditional") {
            if (frame === null || request.kind !== "get") return;
            // The session ended, or the user turned passkeys off, while the request was checked.
            if (!this.serves() || !this.enabled) {
              this.reply(port, FALLBACK);
              return;
            }
            conditional = {
              port,
              frame,
              options: request.options,
              rpId: rpIdOf(request.options, frame.origin),
              signal,
            };
            this.conditionals.add(conditional);
            this.deps.conditionalChanged(frame);
            return;
          }
          this.reply(port, result);
        },
        (error: unknown) => {
          console.warn("Keyguard: a passkey request failed", error);
          if (!signal.aborted) this.reply(port, FALLBACK);
        },
      );
    });
  }

  /** Whether a conditional `get` waits for the user's pick: a reload of the extension would hand it to the browser. */
  get busy(): boolean {
    return this.conditionals.size > 0;
  }

  /** The latest conditional `get` of a frame's document. */
  conditionalFor(frame: FrameRef): ConditionalRequest | null {
    return this.pending(frame);
  }

  /**
   * The app's status changed. Without a session that serves passkeys, the
   * waiting conditional requests go to the browser: Keyguard can't answer them.
   */
  statusChanged(): void {
    if (!this.serves()) this.fallBackAll();
  }

  /**
   * The user changed the options to [options], as `storage.onChanged` tells:
   * with passkeys off, the waiting conditional requests go to the browser at
   * once, before a pick can send one to the app. A request still checked
   * then goes to the browser too, see [available].
   */
  optionsChanged(options: ExtensionOptions): void {
    this.enabled = options.passkeys;
    if (!this.enabled) this.fallBackAll();
  }

  /**
   * The user picked a passkey in the inline menu of [frame]: answers its
   * conditional `get`. Rejects with a [CallFailure] or a protocol error for the
   * menu to show. The request then stays pending, unless the error hands it to
   * the browser, as `not_supported` ("Use another device") or the session's
   * end do.
   *
   * When [signal] aborts, as when the menu that asked closes, the app gets a
   * `cancel` and the request stays pending for another pick.
   */
  async pick(frame: FrameRef, handle: string, signal?: AbortSignal): Promise<void> {
    const conditional = this.pending(frame);
    if (conditional === null) throw new CallFailure("invalid_request");
    const stop = new AbortController();
    const abort = () => stop.abort();
    const signals = signal === undefined ? [conditional.signal] : [conditional.signal, signal];
    for (const it of signals) {
      if (it.aborted) abort();
      else it.addEventListener("abort", abort, { once: true });
    }
    let credential: JsonObject;
    try {
      ({ credential } = await this.deps.app.request(
        "passkey_get",
        {
          origin: conditional.frame.origin,
          options: conditional.options,
          mediation: "conditional",
          same_origin_with_ancestors: true,
          handle,
          ...privateWindow(conditional.frame.privateWindow),
        },
        stop.signal,
      ));
    } catch (error) {
      // The page aborted, or the frame navigated, while the app asked.
      if (!this.conditionals.has(conditional)) throw new CallFailure("origin");
      if (errorResult(error).kind === "fallback") this.fallBack(conditional);
      throw error;
    } finally {
      for (const it of signals) it.removeEventListener("abort", abort);
    }
    const problem = credentialProblem(credential, {
      kind: "get",
      origin: conditional.frame.origin,
      challenge: conditional.options.challenge,
    });
    if (problem !== null) {
      console.warn(`Keyguard: the app's passkey doesn't fit the request: ${problem}`);
      throw new CallFailure("invalid_response");
    }
    // The page aborted, or the frame navigated, while the app asked.
    if (!this.conditionals.has(conditional)) throw new CallFailure("origin");
    this.reply(conditional.port, { kind: "credential", credential });
    this.drop(conditional);
  }

  /** The latest conditional `get` that waits in a frame's document. */
  private pending(frame: FrameRef): PendingConditional | null {
    let found: PendingConditional | null = null;
    for (const conditional of this.conditionals) if (sameDocumentLoose(conditional.frame, frame)) found = conditional;
    return found;
  }

  /** Whether the session serves passkey requests now: paired and connected, with `passkeys`. */
  private serves(): boolean {
    const { app } = this.deps;
    const status = app.snapshot.kind;
    return (status === "locked" || status === "unlocked") && app.supports("passkeys");
  }

  /** Whether Keyguard answers passkey requests now. */
  private async available(): Promise<boolean> {
    if (!this.serves()) return false;
    const { passkeys } = await this.deps.options.options();
    // The user may have turned passkeys off while the read took.
    return passkeys && this.enabled;
  }

  /** [background]: the request comes from a tab that isn't the active one of its window. */
  private async serve(
    frame: FrameRef | null,
    request: WebAuthnRequest,
    signal: AbortSignal,
    background: boolean,
  ): Promise<WebAuthnResult | "conditional"> {
    if (request.kind === "available")
      return { kind: "available", available: frame !== null && (await this.available()) };
    if (frame === null || !(await this.available())) return FALLBACK;
    // Version 1 serves no cross-origin frames (protocol §5), so they don't prompt.
    if (!sameOriginWithAncestors(frame, request.same_origin_with_ancestors)) return FALLBACK;
    if (request.kind === "get" && request.mediation === "conditional") return "conditional";
    // A silent request must show nothing, not even an unlock prompt; the app doesn't serve it (protocol §5).
    if (request.kind === "get" && request.mediation === "silent") return FALLBACK;
    // Keyguard's window doesn't pop up for a page in the background; the browser refuses it.
    if (background) return FALLBACK;
    if (request.kind === "create" && wantsCrossPlatform(request.options)) return FALLBACK;

    // Locked or not: the app prompts to unlock first.
    const { app } = this.deps;
    let credential: JsonObject;
    try {
      credential =
        request.kind === "get"
          ? (
              await app.request(
                "passkey_get",
                {
                  origin: frame.origin,
                  options: request.options,
                  mediation: request.mediation,
                  same_origin_with_ancestors: true,
                  ...privateWindow(frame.privateWindow),
                },
                signal,
              )
            ).credential
          : (
              await app.request(
                "passkey_create",
                {
                  origin: frame.origin,
                  options: request.options,
                  same_origin_with_ancestors: true,
                  ...privateWindow(frame.privateWindow),
                },
                signal,
              )
            ).credential;
    } catch (error) {
      return errorResult(error);
    }
    const problem = credentialProblem(credential, {
      kind: request.kind,
      origin: frame.origin,
      challenge: request.options.challenge,
    });
    if (problem !== null) {
      console.warn(`Keyguard: the app's passkey doesn't fit the request: ${problem}`);
      return { kind: "error", name: "NotAllowedError" };
    }
    return { kind: "credential", credential };
  }

  private drop(conditional: PendingConditional) {
    if (!this.conditionals.delete(conditional)) return;
    this.deps.conditionalChanged(conditional.frame);
  }

  /** Hands a conditional `get` to the browser's own request, which the page runs alongside. */
  private fallBack(conditional: PendingConditional) {
    this.reply(conditional.port, FALLBACK);
    this.drop(conditional);
  }

  private fallBackAll() {
    for (const conditional of [...this.conditionals]) this.fallBack(conditional);
  }

  private reply(port: PagePort, result: WebAuthnResult) {
    try {
      port.postMessage(result);
    } catch {
      // The frame went away.
    }
  }
}
