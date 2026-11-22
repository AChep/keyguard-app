// Installs the extension's own updates. The background holds the native port
// for good, and a connected port keeps the service worker (Chromium) or the
// event page (Firefox) running. Chromium installs a downloaded update only
// once the worker stops, and Firefox, with an `onUpdateAvailable` listener,
// only on `runtime.reload()`: both would wait for the browser to restart. So
// the background reloads itself once a reload cuts nothing the user is doing,
// or at a deadline. What decides when a version installs is the code of the
// version before it.
import type { ExtensionStatus } from "./api";

/** How often a downloaded update checks whether the extension is idle. */
export const IDLE_CHECK_MS = 5_000;

/**
 * How long a downloaded update waits for the extension to be idle before it
 * reloads anyway. What ends by itself ends within minutes: the app's approval
 * prompt times out after a minute by default, a save offer after SAVE_TTL_MS,
 * a menu's token after seconds. What may never end, as the popup or an inline
 * menu left open, or the conditional passkey request of a sign-in page, must
 * not hold the update until the browser restarts. Ten minutes waits out the
 * first with room to spare, and still updates within the session.
 */
export const UPDATE_DEADLINE_MS = 10 * 60_000;

/** The answer of `runtime.requestUpdateCheck()`. */
export interface UpdateCheck {
  status: "throttled" | "no_update" | "update_available";
}

export interface UpdaterDeps {
  /**
   * Whether the extension's state holds nothing a reload would cut: no popup
   * open, no request the app is answering, and nothing of the pages or the
   * passkeys going on. Not the calls and messages being answered:
   * [ExtensionUpdater.track] counts those.
   */
  idle(): boolean;
  /** `runtime.reload()`, which installs the downloaded update. */
  reload(): void;
  /** `runtime.requestUpdateCheck()`; `undefined` where the browser has none, as Firefox. */
  requestUpdateCheck?: () => Promise<UpdateCheck>;
}

/**
 * Installs a downloaded update once the extension is idle: no call or message
 * being answered, see [track], and [UpdaterDeps.idle].
 */
export class ExtensionUpdater {
  /** The timers of a downloaded update that waits; `null` when none does. */
  private waiting: { poll: ReturnType<typeof setInterval>; deadline: ReturnType<typeof setTimeout> } | null = null;
  private checked = false;
  /** The work of [track] that has not settled. */
  private inFlight = 0;

  constructor(private readonly deps: UpdaterDeps) {}

  /**
   * Counts [work], a popup's call or a content script's message being
   * answered, as busy until it settles. Between the app's requests it may
   * still write what a reload would lose, as the pin of a pairing or a save
   * offer. Returns [work] as it settles.
   */
  track<T>(work: Promise<T>): Promise<T> {
    this.inFlight++;
    return work.finally(() => void this.inFlight--);
  }

  /** `runtime.onUpdateAvailable`: installs the update once the extension is idle, or at the deadline. */
  updateAvailable(): void {
    if (this.waiting !== null) return;
    if (this.idle()) {
      this.install();
      return;
    }
    this.waiting = {
      poll: setInterval(() => {
        if (this.idle()) this.install();
      }, IDLE_CHECK_MS),
      deadline: setTimeout(() => this.install(), UPDATE_DEADLINE_MS),
    };
  }

  /**
   * `runtime.onSuspend`: the background stops, which ends what it holds and
   * its timers. A waiting update installs now: Firefox would keep it until the
   * browser restarts.
   */
  suspending(): void {
    if (this.waiting !== null) this.install();
  }

  /**
   * The app's status. When the app speaks none of this version's protocols,
   * asks the browser for an update now rather than at its next check, once per
   * background: Chromium throttles checks asked for too often. An update it
   * finds comes in through [updateAvailable].
   */
  statusChanged(status: ExtensionStatus): void {
    const check = this.deps.requestUpdateCheck;
    if (status.kind !== "extension_update_required" || check === undefined || this.checked) return;
    this.checked = true;
    // A check that throws at once fails as one that rejects.
    void new Promise<UpdateCheck>((resolve) => resolve(check())).then(
      ({ status }) => {
        // `throttled` or `no_update`: the browser's own checks find the update later.
        if (status !== "update_available")
          console.warn(`Keyguard: the app needs a newer extension; the update check says ${status}`);
      },
      (error: unknown) => console.warn("Keyguard: the update check failed", error),
    );
  }

  private idle(): boolean {
    return this.inFlight === 0 && this.deps.idle();
  }

  private install() {
    if (this.waiting !== null) {
      clearInterval(this.waiting.poll);
      clearTimeout(this.waiting.deadline);
      this.waiting = null;
    }
    this.deps.reload();
  }
}

/** A content script of the manifest, as `runtime.getManifest()` tells. */
export interface ManifestContentScript {
  matches?: string[];
  js?: string[];
  all_frames?: boolean;
  world?: string;
}

export interface ReinjectDeps {
  /** The manifest's content scripts. */
  contentScripts: readonly ManifestContentScript[];
  /** `tabs.query`: the IDs of the tabs a content script matches. */
  tabs(query: { url: string[]; discarded: false }): Promise<{ id?: number }[]>;
  /** `scripting.executeScript` of [files] into tab [tabId], in its frames the extension can access when [allFrames]. */
  inject(tabId: number, files: string[], allFrames: boolean): Promise<unknown>;
}

/**
 * After an update, starts the manifest's isolated content scripts of this
 * version in the open tabs they match: the update orphaned their copies
 * there, which then tear down as the new copy in their frame starts (WXT's
 * ContentScriptContext). Pages work again without a reload. Not the scripts of
 * the main world: they have no extension APIs to lose, and a second copy would
 * wrap WebAuthn twice. A discarded tab loads its page again with the
 * manifest's scripts; a tab the extension can't access, or that closed, is
 * skipped.
 */
export async function reinjectContentScripts(deps: ReinjectDeps): Promise<void> {
  const scripts = deps.contentScripts.filter(
    ({ world, js, matches }) => world !== "MAIN" && js?.length && matches?.length,
  );
  await Promise.all(
    scripts.map(async ({ matches = [], js = [], all_frames }) => {
      const tabs = await deps.tabs({ url: matches, discarded: false });
      await Promise.all(
        tabs.map(async ({ id }) => {
          if (id === undefined) return;
          try {
            await deps.inject(id, js, all_frames === true);
          } catch {
            // A page the user withheld access to, or the tab closed.
          }
        }),
      );
    }),
  );
}
