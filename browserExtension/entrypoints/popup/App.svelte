<script lang="ts">
  import { browser, type Browser } from "wxt/browser";
  import type { ExtensionStatus } from "../../src/lib/background/api";
  import { PopupClient } from "../../src/lib/ui/popupClient";
  import SiteAccessBanner from "./SiteAccessBanner.svelte";
  import StatusView from "./StatusView.svelte";
  import Vault from "./Vault.svelte";

  let status = $state.raw<ExtensionStatus>({ kind: "starting" });
  // A call's error belongs to the status screen that started it.
  let statusRevision = $state(0);
  // The active tab: undefined until the query answers, null for none.
  let tabId = $state<number | null | undefined>();
  // Its URL, which opening the popup lets it read (activeTab), kept current as
  // the tab navigates: the banner asks for the site it shows.
  let tabUrl = $state<string | undefined>();
  let footerHeight = $state(0);

  const client = new PopupClient((next) => {
    if (status.kind !== next.kind) statusRevision++;
    status = next;
  });

  void browser.tabs.query({ active: true, currentWindow: true }).then(([tab]) => {
    tabId = tab?.id ?? null;
    tabUrl = tab?.url;
  });

  $effect(() => {
    const onUpdated = (id: number, change: Browser.tabs.OnUpdatedInfo) => {
      if (id === tabId && change.url !== undefined) tabUrl = change.url;
    };
    browser.tabs.onUpdated.addListener(onUpdated);
    return () => browser.tabs.onUpdated.removeListener(onUpdated);
  });
</script>

<!-- The snackbar, fixed to the window, stays above the footer. -->
<div class="shell" style:--popup-footer="{footerHeight}px">
  {#if status.kind === "unlocked"}
    <Vault {client} {tabId} />
  {:else}
    <StatusView {client} {status} {statusRevision} />
  {/if}
  <div class="footer" bind:clientHeight={footerHeight}>
    <SiteAccessBanner url={tabUrl} />
  </div>
</div>

<style>
  /* Fills the viewport: see style.css. */
  .shell {
    position: fixed;
    inset: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: var(--surface);
  }

  .footer {
    flex-shrink: 0;
  }
</style>
