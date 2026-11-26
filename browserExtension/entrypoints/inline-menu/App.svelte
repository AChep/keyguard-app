<script lang="ts">
  import { browser } from "wxt/browser";
  import { t, tp } from "../../src/lib/i18n";
  import {
    itemIcon,
    type DetailsSide,
    type MenuEvent,
    type MenuItem,
    type MenuRequest,
    type MenuView,
  } from "../../src/lib/page/messages";
  import type { ClickGate } from "../../src/lib/ui/clickGate";
  import Avatar from "../../src/lib/ui/components/Avatar.svelte";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import Logo from "../../src/lib/ui/components/Logo.svelte";
  import { contentHeight, reportHeight, type EmbeddedPort } from "../../src/lib/ui/embedded";
  import { PASSWORD_GENERATOR } from "../../src/lib/ui/features";
  import { itemMarkers, joined } from "../../src/lib/ui/itemDetails";
  import { MenuFocus, onMenuKeydown, onMenuKeyup } from "../../src/lib/ui/menuKeyboard";
  import { errorText, statusTitle } from "../../src/lib/ui/statusText";

  interface Props {
    port: EmbeddedPort<MenuRequest, MenuEvent>;
    gate: ClickGate;
  }

  let { port, gate }: Props = $props();

  let view = $state.raw<MenuView>({ kind: "loading" });
  let busy = $state(false);
  let error = $state<string | null>(null);
  let root = $state<HTMLElement | undefined>();
  const menuFocus = new MenuFocus();
  const rtl = document.documentElement.dir === "rtl";
  /** The login whose card shows beside the menu: Space on its row toggles it, see onMenuKeydown. */
  let selected = $state<string | null>(null);
  /** The side of the menu that the card beside it shows on, once the content script told. */
  let detailsSide: DetailsSide | null = null;

  $effect(() =>
    port.onEvent((event) => {
      switch (event.kind) {
        case "view":
          view = event.view;
          error = null;
          return;
        case "busy":
          busy = event.busy;
          return;
        case "focus":
          if (root !== undefined) menuFocus.request(root);
          return;
        case "focus_row": {
          // Back from the card: the row of its login, or the first.
          if (root === undefined) return;
          const row = rowOf(root, event.handle);
          menuFocus.request(root, row instanceof HTMLButtonElement ? row : undefined);
          return;
        }
        case "shown":
          gate.shown();
          return;
        case "details_side":
          detailsSide = event.side;
          return;
        case "deselect":
          selected = null;
          return;
        case "error":
          error = errorText(event.error);
          return;
      }
    }),
  );

  /** The height last sent; 0 before the first. */
  let sentHeight = 0;

  /** The placeholder shows while the first matches load, unless that failed. */
  const placeholder = $derived(view.kind === "loading" && error === null);

  /**
   * The content script sizes the frame to the content, and shows the frame
   * at the first height it gets: not the first placeholder's, which the rows
   * would replace with a jump.
   */
  function sendHeight(height: number) {
    if ((placeholder && sentHeight === 0) || height === sentHeight) return;
    sentHeight = height;
    port.send({ kind: "resize", height });
  }

  // Each view and error at once: the hidden frame may get no ResizeObserver callbacks until it shows.
  $effect(() => {
    if (!placeholder && root !== undefined) sendHeight(contentHeight(root));
  });

  /** The login's row in the menu's page. */
  function rowOf(element: HTMLElement, handle: string): HTMLElement | null {
    return element.querySelector<HTMLElement>(`[data-handle="${CSS.escape(handle)}"]`);
  }

  /** The selected row's box sent last, or `null` for a `deselect`: the background gets each change once. */
  let sentSelection: { handle: string; top: number; height: number } | null = null;
  /** The frame request of [reportSelection], once one waits. */
  let selectionFrame = 0;

  /**
   * The card beside the menu shows the selected login's details, level with
   * its row: the background hears of the row's box, or of no selection. A
   * view without the row drops the selection. Once a frame, however often the
   * selection, the view or the frame's scroll and size change; the background
   * hides the card while an action runs.
   */
  function reportSelection() {
    if (selectionFrame !== 0) return;
    selectionFrame = requestAnimationFrame(() => {
      selectionFrame = 0;
      const handle = view.kind === "items" ? selected : null;
      const box = handle === null || root === undefined ? undefined : rowOf(root, handle)?.getBoundingClientRect();
      if (box === undefined) selected = null;
      const next =
        handle === null || box === undefined
          ? null
          : { handle, top: Math.round(box.top), height: Math.round(box.height) };
      const sent = sentSelection;
      const same =
        next === null || sent === null
          ? next === sent
          : next.handle === sent.handle && next.top === sent.top && next.height === sent.height;
      if (same) return;
      sentSelection = next;
      port.send(next === null ? { kind: "deselect" } : { kind: "select", ...next });
    });
  }

  $effect(() => {
    void view;
    void selected;
    reportSelection();
  });

  $effect(() => () => cancelAnimationFrame(selectionFrame));

  // A requested focus goes to the first row once there is one; and a row keeps
  // it after an action, which disables the rows. See MenuFocus.
  $effect(() => {
    if (root !== undefined && view.kind !== "loading" && !busy) menuFocus.settle(root);
  });

  /** Acts on trusted clicks only, once the menu was visible for a while. Enter clicks too, and Space but on a login's row. */
  function act(event: MouseEvent, request: MenuRequest) {
    if (busy || !gate.allows(event)) return;
    error = null;
    port.send(request);
  }

  /**
   * Opens the popup from the click itself: Firefox before 149 opens it only
   * while it handles the user's input, which a message to the background
   * isn't any more.
   */
  function openPopup(event: MouseEvent) {
    if (busy || !gate.allows(event)) return;
    error = null;
    browser.action.openPopup().catch(() => undefined);
  }

  function pick(event: MouseEvent, item: MenuItem) {
    if (view.kind !== "items") return;
    act(event, view.role === "otp" ? { kind: "totp", handle: item.handle } : { kind: "fill", handle: item.handle });
  }

  function onKeydown(event: KeyboardEvent) {
    if (root === undefined) return;
    onMenuKeydown(event, root, {
      close: () => port.send({ kind: "close" }),
      details: selected,
      // Not while an action runs, which hides the card; hiding it always works.
      select: (handle) => {
        if (handle === null || !busy) selected = handle;
      },
      focusDetails: (handle) => port.send({ kind: "details_focus", handle }),
      rtl,
      side: detailsSide,
    });
  }

  function onKeyup(event: KeyboardEvent) {
    if (root !== undefined) onMenuKeyup(event, root);
  }

  // The frame got focus: only a request that came before it moves focus to a row.
  function onFocus() {
    if (root !== undefined) menuFocus.settle(root);
  }

  // The card stays while focus went into it: the background tells once it left both, for the page or the window.
  function onBlur(event: FocusEvent) {
    if (event.target !== window) return;
    menuFocus.blur();
    port.send({ kind: "blurred" });
  }
</script>

{#snippet login(item: MenuItem, lastHandle: string | null, icon: string | null)}
  {@const markers = itemMarkers(item)}
  {@const last = item.handle === lastHandle}
  <button class="row" role="menuitem" data-handle={item.handle} disabled={busy} onclick={(event) => pick(event, item)}>
    <!-- A suggested login may be for another site: not the page's icon. -->
    <Avatar name={item.name} icon={itemIcon(item, icon)} size={32} />
    <span class="row-content">
      <span class="row-title">{item.name}</span>
      <span class="row-text">{joined(item.username, item.account)}</span>
    </span>
    {#if markers.length > 0 || last}
      <span class="trailing">
        {#each markers as marker (marker.icon)}
          <span class="marker" title={marker.label}
            ><Icon name={marker.icon} size={16} /><span class="sr-only">{marker.label}</span></span
          >
        {/each}
        {#if last}
          <span class="chip">{t("browser_extension_last_used")}</span>
        {/if}
      </span>
    {/if}
  </button>
{/snippet}

<svelte:window
  onkeydown={onKeydown}
  onkeyup={onKeyup}
  onfocus={onFocus}
  onblur={onBlur}
  onscroll={reportSelection}
  onresize={reportSelection}
/>

<div
  class="menu"
  bind:this={root}
  {@attach (element) => reportHeight(element, sendHeight)}
  role="menu"
  aria-label={t("browser_extension_name")}
  aria-busy={busy}
  onfocusin={(event) => menuFocus.focusIn(event.target)}
>
  {#if busy}
    <!-- Over the frame's top edge: the rows don't move. -->
    <div class="linear-progress busy" role="progressbar" aria-label={t("loading")}></div>
  {/if}
  {#if view.kind === "loading"}
    {#if placeholder}
      <div class="skeleton placeholder" role="progressbar" aria-label={t("loading")}></div>
    {/if}
  {:else if view.kind === "status"}
    <div class="state">
      <Logo size={32} />
      <p class="state-text title-small">{statusTitle(view.status)}</p>
    </div>
    <div class="actions">
      <button class="btn btn-text btn-xs" onclick={openPopup}>{t("autofill_open_keyguard")}</button>
    </div>
  {:else if view.kind === "locked"}
    <div class="state">
      <span class="tile secondary"><Icon name="lock" size={20} /></span>
      <p class="state-text body-medium">
        {t(view.unlocking ? "browser_extension_unlock_pending_text" : "browser_extension_locked_text")}
      </p>
    </div>
    <div class="actions">
      <button
        class="btn btn-filled btn-xs"
        disabled={busy || view.unlocking}
        onclick={(event) => act(event, { kind: "unlock" })}
      >
        <Icon name="lock_open" />
        {t("autofill_unlock_keyguard")}
      </button>
    </div>
  {:else if view.kind === "blocked"}
    <div class="state">
      <span class="tile neutral"><Icon name="block" size={20} /></span>
      <div class="state-text">
        <p class="host title-small">{view.host}</p>
        <p class="body-small medium">{t("browser_extension_site_blocked_text")}</p>
      </div>
    </div>
  {:else if view.kind === "items"}
    {@const generate = PASSWORD_GENERATOR && view.role === "new-password"}
    {@const actions = view.passkeys.length > 0 || view.totp !== null || generate}
    {@const split = view.items.findIndex((item) => !item.matched)}
    {@const matched = split < 0 ? view.items : view.items.slice(0, split)}
    {@const suggested = split < 0 ? [] : view.items.slice(split)}
    {#each view.passkeys as passkey (passkey.handle)}
      <button
        class="row"
        role="menuitem"
        disabled={busy}
        onclick={(event) => act(event, { kind: "passkey", handle: passkey.handle })}
      >
        <span class="tile tertiary"><Icon name="key" size={20} /></span>
        <span class="row-content">
          <span class="row-title"
            >{passkey.user_display_name || passkey.user_name || t("passkey_unknown_username")}</span
          >
          <span class="row-text">{joined(passkey.item, passkey.account)}</span>
        </span>
      </button>
    {/each}
    {#if view.totp}
      {@const remembered = view.totp}
      <button
        class="row"
        role="menuitem"
        disabled={busy}
        onclick={(event) => act(event, { kind: "totp", handle: remembered.handle })}
      >
        <span class="tile primary"><Icon name="two_fa" size={20} /></span>
        <span class="row-content">
          <span class="row-title">{t("browser_extension_menu_fill_totp")}</span>
          <span class="row-text">{remembered.name}</span>
        </span>
      </button>
    {/if}
    <!-- The flag itself, not the derived `generate`: the build drops the row's code while it's off. -->
    {#if PASSWORD_GENERATOR && generate}
      <button class="row" role="menuitem" disabled={busy} onclick={(event) => act(event, { kind: "generate" })}>
        <span class="tile primary"><Icon name="password" size={20} /></span>
        <span class="row-content">
          <span class="row-title">{t("browser_extension_menu_generate_password")}</span>
        </span>
      </button>
    {/if}
    {#if actions && matched.length > 0}
      <div class="divider" role="separator"></div>
    {/if}
    {#each matched as item (item.handle)}
      {@render login(item, view.last, view.icon)}
    {/each}
    {#if suggested.length > 0}
      {#if actions || matched.length > 0}
        <div class="divider" role="separator"></div>
      {/if}
      <!-- The label isn't a button: the arrow keys skip it. -->
      <div class="section" role="group" aria-labelledby="suggested">
        <p class="section-header in-list" id="suggested" aria-hidden="true">{t("autofill_suggested")}</p>
        {#each suggested as item (item.handle)}
          {@render login(item, view.last, view.icon)}
        {/each}
      </div>
    {/if}
    {#if view.items.length === 0 && view.passkeys.length === 0 && !view.totp && !generate}
      <p class="empty body-medium">{t("autofill_no_matching_logins")}</p>
    {/if}
    {#if view.truncated}
      <p class="note body-small">
        {tp("browser_extension_logins_truncated_plural", view.items.length, view.items.length)}
      </p>
    {/if}
  {/if}
  {#if error}
    <p class="banner" role="alert"><Icon name="error_outline" />{error}</p>
  {/if}
</div>

<style>
  .menu {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 4px;
  }

  /* Indeterminate, over the frame's top edge, which rounds its ends. */
  .busy {
    position: fixed;
    top: 0;
    right: 0;
    left: 0;
    z-index: 2;
    height: 3px;
    border-radius: 0;
  }

  .placeholder {
    height: 52px;
    border-radius: var(--shape-md);
  }

  /*
   * Rows: 12px corners, the app's 8 by 12 padding. A row focused by the
   * keyboard takes the secondary container and an inset ring: an outer one
   * would be cut by the frame.
   */
  .row {
    padding: 8px 12px;
    border-radius: var(--shape-md);
  }

  .row:focus-visible {
    outline: 2px solid var(--secondary);
    outline-offset: -2px;
    background: var(--secondary-container);
    color: var(--on-secondary-container);
  }

  /* Rows are disabled only while an action runs: the progress bar tells it, the rows stay as they are. */
  .row:disabled > :global(*) {
    opacity: 1;
  }

  .row-title {
    font: var(--type-title-small);
    letter-spacing: var(--tracking-title-small);
  }

  .row-text {
    font: var(--type-body-small);
    letter-spacing: var(--tracking-body-small);
  }

  /* An action's or a passkey's icon, in a tile of an avatar's size and shape. */
  .tile {
    --tile-size: 32px;
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: var(--tile-size);
    height: var(--tile-size);
    border-radius: calc(var(--tile-size) / 3);
  }

  .primary {
    background: var(--primary-container);
    color: var(--on-primary-container);
  }

  .secondary {
    background: var(--secondary-container);
    color: var(--on-secondary-container);
  }

  .tertiary {
    background: var(--tertiary-container);
    color: var(--on-tertiary-container);
  }

  .neutral {
    background: var(--surface-container-highest);
    color: var(--on-surface-variant);
  }

  .trailing {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    gap: 6px;
  }

  .marker {
    display: flex;
    color: color-mix(in srgb, currentColor var(--text-medium-alpha), transparent);
  }

  /* Not the segmented .group of theme.css: its rows sit on the menu's surface. */
  .section {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .divider {
    flex-shrink: 0;
    height: 1px;
    margin: 4px 12px;
    background: var(--outline-variant);
  }

  /* Locked, blocked and other states: a tile and text, actions below at the end. */
  .state {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 12px 4px;
  }

  .state-text {
    flex: 1;
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
  }

  .state-text p {
    margin: 0;
  }

  .host {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .medium {
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
    padding: 4px 8px 8px;
  }

  .empty,
  .note {
    margin: 0;
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
  }

  .empty {
    padding: 14px 12px;
  }

  .note {
    padding: 4px 12px 8px;
  }

  .banner {
    margin: 4px;
  }

  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
</style>
