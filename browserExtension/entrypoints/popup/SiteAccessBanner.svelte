<!--
  Shown when the browser withholds Keyguard's access to the active tab's
  website: the content script doesn't run there, so no inline menu or save bar.
  The popup still works, as opening it grants activeTab. The browser's prompt
  may close the popup; the background injects the content script after a grant.
  A footer in the look of the actions' strip: where its buttons don't fit,
  they go into a popover behind a "More" button.
-->
<script lang="ts">
  import { browser } from "wxt/browser";
  import { t } from "../../src/lib/i18n";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import { observeSiteAccess, requestAllSites, requestSite, sitePattern } from "../../src/lib/options/siteAccess";

  interface Props {
    /** The active tab's URL, undefined until known. */
    url: string | undefined;
  }

  let { url }: Props = $props();

  const MENU_ID = "site-access-menu";
  const HINT_ID = "site-access-hint";
  /** How long a pointer rests on the label before its hint shows. */
  const HINT_DELAY_MS = 300;
  /** How long the hint stays once the pointer leaves, so the pointer can move onto it. */
  const HINT_HIDE_DELAY_MS = 100;
  /** The least distance of the hint to the window's sides. */
  const HINT_MARGIN = 8;

  // Undefined until a check answers, so that the banner doesn't flash.
  let granted = $state<boolean | undefined>();
  const missing = $derived(sitePattern(url) !== null && granted === false);

  /** The buttons are in the popover: the strip is narrower than [fullWidth]. */
  let collapsed = $state(false);
  /** The strip's width with its buttons, once they didn't fit. */
  let fullWidth = 0;
  // Null once their block unmounts.
  let menu = $state<HTMLElement | null>(null);
  let label = $state<HTMLElement | null>(null);
  let hint = $state<HTMLElement | null>(null);
  /** Shows or hides the hint. */
  let hintTimer: ReturnType<typeof setTimeout> | undefined;

  // A grant from the buttons comes back through `permissions.onAdded`.
  $effect(() => {
    granted = undefined;
    return observeSiteAccess(browser, url, (next) => (granted = next));
  });

  /**
   * Collapses the buttons into the popover while they don't fit, and back once
   * they do, which closes it: its button is gone.
   */
  function fit(strip: HTMLElement) {
    const observer = new ResizeObserver(() => {
      if (!collapsed && strip.scrollWidth > strip.clientWidth) {
        fullWidth = strip.scrollWidth;
        collapsed = true;
      } else if (collapsed && strip.clientWidth >= fullWidth) {
        collapsed = false;
        if (menu?.matches(":popover-open")) menu.hidePopover();
      }
    });
    observer.observe(strip);
    return () => observer.disconnect();
  }

  // The requests start in the click itself: they need the user's gesture.
  function allowSite() {
    void requestSite(browser, url).catch(() => {});
    menu?.hidePopover();
  }

  function allowAll() {
    void requestAllSites(browser).catch(() => {});
    menu?.hidePopover();
  }

  /** Shows the hint above the label after [delay] ms, as a tooltip waits out a passing pointer. */
  function showHint(delay: number) {
    clearTimeout(hintTimer);
    hintTimer = setTimeout(() => {
      if (!hint || !label || hint.matches(":popover-open")) return;
      hint.showPopover();
      // Above the label, from its start, inside the window.
      const rect = label.getBoundingClientRect();
      const width = document.documentElement.clientWidth;
      const left = Math.min(Math.max(rect.left, HINT_MARGIN), width - hint.offsetWidth - HINT_MARGIN);
      hint.style.left = `${Math.round(Math.max(left, HINT_MARGIN))}px`;
      hint.style.bottom = `${Math.round(window.innerHeight - rect.top + 4)}px`;
    }, delay);
  }

  /** Hides the hint after [delay] ms, or keeps it while the pointer moves onto it or back. */
  function hideHint(delay = 0) {
    clearTimeout(hintTimer);
    if (delay > 0) {
      hintTimer = setTimeout(() => hideHint(), delay);
    } else if (hint?.matches(":popover-open")) {
      hint.hidePopover();
    }
  }

  /** The label's timer goes with it: the block may unmount while it waits. */
  function clearHintTimer() {
    return () => clearTimeout(hintTimer);
  }

  /** Escape dismisses the hint wherever the focus is, as a tooltip should. */
  function onWindowKeydown(event: KeyboardEvent) {
    if (event.key !== "Escape" || !hint?.matches(":popover-open")) return;
    event.preventDefault();
    hideHint();
  }

  /** Places the popover above the button, at its end, before it paints. */
  function placeMenu(event: ToggleEvent, anchor: HTMLElement | null) {
    if (event.newState !== "open" || !menu || !anchor) return;
    const rect = anchor.getBoundingClientRect();
    if (getComputedStyle(anchor).direction === "rtl") {
      menu.style.left = `${Math.round(rect.left)}px`;
    } else {
      menu.style.right = `${Math.round(document.documentElement.clientWidth - rect.right)}px`;
    }
    menu.style.bottom = `${Math.round(window.innerHeight - rect.top + 4)}px`;
  }
</script>

<svelte:window onkeydown={onWindowKeydown} />

{#if missing}
  <div class={["action-strip site-access", { collapsed }]} {@attach fit}>
    <!-- A button for its hint: keyboards focus it, and touch taps it, as neither hovers. -->
    <button
      bind:this={label}
      {@attach clearHintTimer}
      class="message"
      type="button"
      aria-describedby={HINT_ID}
      onpointerenter={() => showHint(HINT_DELAY_MS)}
      onpointerleave={() => hideHint(HINT_HIDE_DELAY_MS)}
      onfocus={() => showHint(0)}
      onblur={() => hideHint()}
      onclick={() => showHint(0)}
      ><Icon name="public" size={20} /><span class="text">{t("browser_extension_site_access_popup_text")}</span></button
    >
    {#if collapsed}
      <button
        id="{MENU_ID}-button"
        class="icon-btn"
        type="button"
        popovertarget={MENU_ID}
        aria-label={t("more")}
        title={t("more")}
      >
        <Icon name="more_vert" />
      </button>
    {:else}
      <button class="action-badge state-layer" type="button" onclick={allowSite}>
        {t("browser_extension_site_access_allow_site_button")}
      </button>
      <button class="action-badge state-layer primary" type="button" onclick={allowAll}>
        {t("browser_extension_site_access_allow_all_button")}
      </button>
    {/if}
  </div>
  <!-- The pointer may rest on it, as on the label: it stays to be read. -->
  <div
    bind:this={hint}
    id={HINT_ID}
    class="hint"
    role="tooltip"
    popover="manual"
    onpointerenter={() => clearTimeout(hintTimer)}
    onpointerleave={() => hideHint(HINT_HIDE_DELAY_MS)}
  >
    {t("browser_extension_site_access_popup_hint")}
  </div>
  <div
    bind:this={menu}
    id={MENU_ID}
    class="menu"
    popover="auto"
    onbeforetoggle={(event) => placeMenu(event, document.getElementById(`${MENU_ID}-button`))}
    ontoggle={(event) => {
      if (event.newState === "open") menu?.querySelector("button")?.focus();
    }}
  >
    <button class="row" type="button" onclick={allowAll}>
      {t("browser_extension_site_access_allow_all_button")}
    </button>
    <button class="row" type="button" onclick={allowSite}>
      {t("browser_extension_site_access_allow_site_button")}
    </button>
  </div>
{/if}

<style>
  /* Information, not an error: the tonal colors. It never scrolls: the buttons collapse. */
  .site-access {
    align-items: center;
    overflow: hidden;
    background: var(--secondary-container);
    color: var(--on-secondary-container);
  }

  /* The buttons go to the end. */
  .message {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    gap: 8px;
    margin-inline-end: auto;
    padding: 0 4px;
    border: none;
    border-radius: var(--shape-xs);
    background: none;
    color: inherit;
    font: var(--type-body-medium);
    letter-spacing: var(--tracking-body-medium);
    white-space: nowrap;
    cursor: help;
  }

  /* With the menu, the text gives way before the button does. */
  .collapsed .message {
    flex-shrink: 1;
    min-width: 0;
  }

  .text {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .message :global(.icon) {
    flex-shrink: 0;
  }

  /* The height of the buttons it stands in for: the strip keeps its height. */
  .icon-btn {
    --icon-btn-size: 36px;
    color: inherit;
  }

  /* The default action, as Fill in the actions' strip. */
  .primary {
    background: var(--primary);
    color: var(--on-primary);
  }

  .message:focus-visible {
    outline: 2px solid var(--primary);
    outline-offset: 2px;
  }

  /* A plain tooltip, in the snackbar's colors, in the top layer above the strip. */
  .hint {
    position: fixed;
    inset: auto;
    max-width: min(320px, calc(100vw - 16px));
    margin: 0;
    padding: 8px 12px;
    border: none;
    border-radius: var(--shape-xs);
    background: var(--inverse-surface);
    color: var(--inverse-on-surface);
    box-shadow: var(--elevation-3);
    font: var(--type-body-small);
    letter-spacing: var(--tracking-body-small);
  }

  /* In the look of a Material menu, in the top layer above the strip. */
  .menu {
    position: fixed;
    inset: auto;
    min-width: 200px;
    margin: 0;
    padding: 8px 0;
    border: none;
    border-radius: var(--shape-xs);
    background: var(--surface-container);
    color: var(--on-surface);
    box-shadow: var(--elevation-3);
  }

  .menu .row {
    width: 100%;
    min-height: 48px;
    padding: 0 12px;
    font: var(--type-body-large);
    letter-spacing: var(--tracking-body-large);
    text-align: start;
  }
</style>
