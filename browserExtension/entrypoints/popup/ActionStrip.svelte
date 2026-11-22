<!--
  The actions of the selected login with their shortcuts, as the app's Quick
  search footer (QuickSearchActionStrip): SmartBadge chips in a bar that
  scrolls sideways.
-->
<script lang="ts">
  import { on } from "svelte/events";
  import { t, type I18nKey } from "../../src/lib/i18n";
  import LoadingIndicator from "../../src/lib/ui/components/LoadingIndicator.svelte";
  import { ariaKeyShortcuts, SHORTCUTS, shortcutKeys, type PopupAction } from "../../src/lib/ui/popupKeys";

  const LABELS: Record<PopupAction, I18nKey> = {
    fill: "browser_extension_fill_button",
    copy_username: "copy_username",
    copy_password: "copy_password",
    copy_totp: "copy_otp_code",
  };

  interface Props {
    actions: PopupAction[];
    mac: boolean;
    /** The action in progress. */
    busy: PopupAction | null;
    onaction: (action: PopupAction) => void;
  }

  let { actions, mac, busy, onaction }: Props = $props();

  // A mouse wheel scrolls the strip sideways: it shows no scrollbar. Down
  // goes toward its end, which is to the left in a right-to-left page, where
  // `scrollLeft` counts down from 0.
  function scrollWithWheel(element: HTMLElement) {
    return on(
      element,
      "wheel",
      (event) => {
        if (Math.abs(event.deltaY) <= Math.abs(event.deltaX) || element.scrollWidth <= element.clientWidth) return;
        event.preventDefault();
        const rtl = getComputedStyle(element).direction === "rtl";
        element.scrollLeft += rtl ? -event.deltaY : event.deltaY;
      },
      { passive: false },
    );
  }
</script>

<div class="strip" {@attach scrollWithWheel}>
  {#each actions as action (action)}
    <!-- Fill, the default action, comes first in the primary color. -->
    <button
      class={["badge state-layer", { primary: action === "fill", busy: busy === action }]}
      disabled={busy !== null}
      aria-keyshortcuts={ariaKeyShortcuts(SHORTCUTS[action], mac)}
      onclick={() => onaction(action)}
    >
      <span class="title">{t(LABELS[action])}</span>
      {#if busy === action}
        <LoadingIndicator label={t("loading")} size={16} contained={false} />
      {:else}
        <!-- On one line: no spaces around the "+". -->
        <span class="shortcut body-small" aria-hidden="true"
          >{#each shortcutKeys(SHORTCUTS[action], mac) as key, index (index)}{#if index > 0}+{/if}<b>{key}</b
            >{/each}</span
        >
      {/if}
    </button>
  {/each}
</div>

<style>
  .strip {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
    padding: 8px;
    overflow-x: auto;
    background: var(--surface-container-high);
    scrollbar-width: none;
  }

  /* SmartBadge, clickable: an 8px-round tint, 8px padding. */
  .badge {
    display: inline-flex;
    flex-shrink: 0;
    align-items: baseline;
    gap: 8px;
    padding: 8px;
    border-radius: var(--shape-sm);
    background: color-mix(in srgb, var(--surface-tint) 5%, transparent);
    font: var(--type-body-medium);
    letter-spacing: var(--tracking-body-medium);
    white-space: nowrap;
  }

  .badge:disabled {
    color: color-mix(in srgb, var(--on-surface) var(--disabled-content), transparent);
  }

  .badge.primary {
    background: var(--primary);
    color: var(--on-primary);
  }

  /* While another action runs; while Fill runs, its chip keeps its color for the indicator. */
  .badge.primary:disabled:not(.busy) {
    background: color-mix(in srgb, var(--on-surface) var(--disabled-container), transparent);
    color: color-mix(in srgb, var(--on-surface) var(--disabled-content), transparent);
  }

  .badge.primary :global(.indicator) {
    color: var(--on-primary);
  }

  .badge :global(.indicator) {
    align-self: center;
  }

  .shortcut {
    color: color-mix(in srgb, currentColor var(--text-medium-alpha), transparent);
  }

  .shortcut b {
    font-weight: 700;
  }
</style>
