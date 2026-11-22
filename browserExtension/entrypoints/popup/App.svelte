<script lang="ts">
  import { browser } from "wxt/browser";
  import type { ExtensionStatus } from "../../src/lib/background/api";
  import { PopupClient } from "../../src/lib/ui/popupClient";
  import StatusView from "./StatusView.svelte";
  import Vault from "./Vault.svelte";

  let status = $state.raw<ExtensionStatus>({ kind: "starting" });
  // A call's error belongs to the status screen that started it.
  let statusRevision = $state(0);
  // The active tab: undefined until the query answers, null for none.
  let tabId = $state<number | null | undefined>();

  const client = new PopupClient((next) => {
    if (status.kind !== next.kind) statusRevision++;
    status = next;
  });

  void browser.tabs.query({ active: true, currentWindow: true }).then(([tab]) => (tabId = tab?.id ?? null));
</script>

<div class="shell">
  {#if status.kind === "unlocked"}
    <Vault {client} {tabId} />
  {:else}
    <StatusView {client} {status} {statusRevision} />
  {/if}
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
</style>
