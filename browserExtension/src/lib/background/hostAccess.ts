// Chromium's toolbar request for site access. Where the user withheld access
// to a site, the content script doesn't run there, and nothing says why the
// inline menu is missing. So when a tab's top frame navigates to such a site
// and the vault has logins for it, the background asks the browser to show its
// "request access" prompt in the toolbar and the extensions menu
// (`permissions.addHostAccessRequest`, Chrome 133). The browser drops it as the
// tab leaves the origin.
//
// - Only with logins that match the page by a URI: a request on every site
//   would nag. A page without such logins gets its request withdrawn.
// - The check is a `suggest`, which prompts nothing; the app doesn't log it.
// - Navigations arrive from `tabs.onUpdated`, heard only while the vault is
//   unlocked and some site lacks access: a listener wakes the worker on every
//   tab update. The open native port keeps the worker running meanwhile.
//   Unlocking, or losing access, checks the open tabs again, so a page loaded
//   in between still asks. Two at a time: the app answers a few requests at
//   once, and the user's own wait behind the rest.
// - A lock or a disconnect cancels the checks and withdraws the requests: the
//   logins behind them are out of reach until the next unlock.
//
// Firefox has no such API: the popup and the options page ask there.
import { privateWindow } from "../protocol/messages";
import { webUrl, type TabInfo } from "./frames";
import type { AppAccess } from "./pages";

/** How many open tabs a check after an unlock, or a loss of access, asks about at once. */
export const SCAN_CONCURRENCY = 2;

/** `permissions.addHostAccessRequest` and `removeHostAccessRequest` of a tab. */
export interface HostAccessRequestApi {
  add(tabId: number): unknown;
  remove(tabId: number): unknown;
}

export interface HostAccessDeps {
  api: HostAccessRequestApi;
  /** Whether the extension may run on [url]'s site. */
  hasAccess(url: string): Promise<boolean>;
  /** Whether the extension may run on every website. */
  allSites(): Promise<boolean>;
  app: Pick<AppAccess, "snapshot" | "request">;
  /** The open web tabs, for the check after an unlock; not the discarded ones, which load again as they show. */
  tabs(): Promise<readonly (TabInfo & { id?: number })[]>;
  /** `tabs.get`: tab [tabId] as it is now. */
  tab(tabId: number): Promise<{ url?: string }>;
  /**
   * Hears `true` as navigations start to matter, with the vault unlocked and
   * some site without access, and `false` as they stop.
   */
  watch(active: boolean): void;
}

export class HostAccessRequests {
  /** The latest check of each tab, while it runs: an older one's answer is dropped. */
  private readonly checks = new Map<number, number>();
  private sequence = 0;
  private unlocked = false;
  /** Aborts as the vault leaves `unlocked`, which cancels the checks of the unlock. */
  private unlock: AbortController | null = null;
  /** The tabs that got a request during this unlock, to withdraw as it ends. */
  private readonly added = new Set<number>();
  /** Access to every website, as last heard; until then, assumed missing. */
  private allSites = false;
  private watching = false;
  /** The latest check of [allSites]: an older one's answer is dropped. */
  private accessChecks = 0;
  /** A check of [accessChanged] asked to check the open tabs again. */
  private recheck = false;

  constructor(private readonly deps: HostAccessDeps) {}

  /**
   * The top frame of tab [tabId] has a new URL, from `tabs.onUpdated`. Asks
   * for access to its site when the vault has logins for it. Never fails: the
   * app may go away and the tab may close meanwhile.
   */
  async navigated(tabId: number, url: string, incognito?: boolean): Promise<void> {
    const check = ++this.sequence;
    this.checks.set(tabId, check);
    try {
      const page = webUrl(url);
      const { app, api } = this.deps;
      if (page === null || app.snapshot.kind !== "unlocked") return;
      const { signal } = (this.unlock ??= new AbortController());
      const current = () => this.checks.get(tabId) === check && !signal.aborted;
      if ((await this.deps.hasAccess(page.href)) || !current()) return;
      const response = await app.request("suggest", { url: page.href, ...privateWindow(incognito) }, signal);
      if (!current()) return;
      if (response.blocked || !response.items.some((item) => item.matched)) {
        this.added.delete(tabId);
        await api.remove(tabId);
        return;
      }
      // The request is for the tab's page now: a check of the open tabs may
      // have listed it before it left [url].
      const tab = await this.deps.tab(tabId);
      if (webUrl(tab.url)?.origin !== page.origin || !current()) return;
      await api.add(tabId);
      // Locked while the browser added it.
      if (signal.aborted) await api.remove(tabId);
      else this.added.add(tabId);
    } catch {
      // The app disconnected, the vault locked, or the tab closed.
    } finally {
      if (this.checks.get(tabId) === check) this.checks.delete(tabId);
    }
  }

  /**
   * The app's status changed: on an unlock, starts watching navigations and
   * checks every open tab, unless every website has access; on a lock or a
   * disconnect, stops watching, cancels the checks, and withdraws the
   * requests.
   */
  statusChanged(): void {
    const unlocked = this.deps.app.snapshot.kind === "unlocked";
    if (!unlocked) this.endUnlock();
    if (unlocked === this.unlocked) return;
    this.unlocked = unlocked;
    this.updateWatch();
    if (unlocked && !this.allSites) void this.checkTabs();
  }

  /**
   * The extension's site access may have changed, from `permissions.onAdded`
   * or `onRemoved`, or isn't known yet, at the start. With every website
   * allowed, stops watching navigations; without, starts again and checks
   * every open tab while unlocked. With [recheck], as a site's access goes,
   * checks them while unlocked even when every website was already without
   * access. Never fails.
   */
  async accessChanged({ recheck = false }: { recheck?: boolean } = {}): Promise<void> {
    const check = ++this.accessChecks;
    // Kept for the check that wins, should a later one drop this one.
    if (recheck) this.recheck = true;
    let allSites: boolean;
    try {
      allSites = await this.deps.allSites();
    } catch {
      return;
    }
    if (check !== this.accessChecks) return;
    const changed = allSites !== this.allSites;
    const checkTabs = changed || this.recheck;
    this.recheck = false;
    if (!checkTabs) return;
    this.allSites = allSites;
    this.updateWatch();
    if (!allSites && this.unlocked) await this.checkTabs();
  }

  /** Tells [HostAccessDeps.watch] when its answer changes. */
  private updateWatch(): void {
    const watching = this.unlocked && !this.allSites;
    if (watching === this.watching) return;
    this.watching = watching;
    this.deps.watch(watching);
  }

  /** Cancels the checks of the unlock, and withdraws the requests they added. */
  private endUnlock(): void {
    this.unlock?.abort();
    this.unlock = null;
    for (const tabId of this.added) void this.withdraw(tabId);
    this.added.clear();
  }

  private async withdraw(tabId: number): Promise<void> {
    try {
      await this.deps.api.remove(tabId);
    } catch {
      // The tab closed.
    }
  }

  /** Checks the open tabs, [SCAN_CONCURRENCY] at a time, until the vault locks. */
  private async checkTabs(): Promise<void> {
    const { signal } = (this.unlock ??= new AbortController());
    let tabs: readonly (TabInfo & { id?: number })[];
    try {
      tabs = await this.deps.tabs();
    } catch {
      // The browser couldn't list the tabs.
      return;
    }
    const queue = tabs.flatMap(({ id, url, incognito }) =>
      id === undefined || url === undefined ? [] : [{ id, url, incognito }],
    );
    const next = async () => {
      for (let tab = queue.shift(); tab !== undefined && !signal.aborted; tab = queue.shift()) {
        await this.navigated(tab.id, tab.url, tab.incognito);
      }
    };
    await Promise.all(Array.from({ length: SCAN_CONCURRENCY }, next));
  }
}
