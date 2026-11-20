<script lang="ts">
  import { browser } from "wxt/browser";
  import { t, tp } from "../../src/lib/i18n";
  import type { MenuEvent, MenuItem, MenuRequest, MenuView } from "../../src/lib/page/messages";
  import type { ClickGate } from "../../src/lib/ui/clickGate";
  import Avatar from "../../src/lib/ui/components/Avatar.svelte";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import Logo from "../../src/lib/ui/components/Logo.svelte";
  import type { EmbeddedPort } from "../../src/lib/ui/embedded";
  import { PASSWORD_GENERATOR } from "../../src/lib/ui/features";
  import type { IconName } from "../../src/lib/ui/icons";
  import { MenuFocus, onMenuKeydown } from "../../src/lib/ui/menuKeyboard";
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
        case "shown":
          gate.shown();
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
  function sendHeight() {
    if (root === undefined || (placeholder && sentHeight === 0)) return;
    const height = Math.ceil(root.scrollHeight);
    if (height === sentHeight) return;
    sentHeight = height;
    port.send({ kind: "resize", height });
  }

  $effect(() => {
    if (root === undefined) return;
    const observer = new ResizeObserver(sendHeight);
    observer.observe(root);
    return () => observer.disconnect();
  });

  // Each view and error at once: the hidden frame may get no ResizeObserver callbacks until it shows.
  $effect(() => {
    if (!placeholder) sendHeight();
  });

  // A requested focus goes to the first row once there is one; and a row keeps
  // it after an action, which disables the rows. See MenuFocus.
  $effect(() => {
    if (root !== undefined && view.kind !== "loading" && !busy) menuFocus.settle(root);
  });

  /** Acts on trusted clicks only, once the menu was visible for a while. Enter and Space click too. */
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
    if (root !== undefined) onMenuKeydown(event, root, () => port.send({ kind: "close" }));
  }

  // The frame got focus: only a request that came before it moves focus to a row.
  function onFocus() {
    if (root !== undefined) menuFocus.settle(root);
  }

  function onBlur(event: FocusEvent) {
    if (event.target === window) menuFocus.blur();
  }

  interface Marker {
    icon: IconName;
    label: string;
  }

  /** The small icons at the end of a login's row: what else it has. */
  function markersOf(item: MenuItem): Marker[] {
    const markers: Marker[] = [];
    if (item.has_totp) markers.push({ icon: "two_fa", label: t("one_time_password") });
    if (item.reprompt) markers.push({ icon: "lock", label: t("additem_auth_reprompt_title") });
    return markers;
  }

  /** The second line of a row: its parts in one line. */
  const joined = (...parts: (string | undefined)[]) => parts.filter((part) => part !== undefined && part !== "").join(" · ");
</script>

<svelte:window onkeydown={onKeydown} onfocus={onFocus} onblur={onBlur} />

<div
  class="menu"
  bind:this={root}
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
      <button class="btn btn-filled btn-xs" disabled={busy || view.unlocking} onclick={(event) => act(event, { kind: "unlock" })}>
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
    {#each view.passkeys as passkey (passkey.handle)}
      <button class="row" role="menuitem" disabled={busy} onclick={(event) => act(event, { kind: "passkey", handle: passkey.handle })}>
        <span class="tile tertiary"><Icon name="key" size={20} /></span>
        <span class="row-content">
          <span class="row-title">{passkey.user_display_name || passkey.user_name || t("passkey_unknown_username")}</span>
          <span class="row-text">{joined(passkey.item, passkey.account)}</span>
        </span>
      </button>
    {/each}
    {#if view.totp}
      {@const remembered = view.totp}
      <button class="row" role="menuitem" disabled={busy} onclick={(event) => act(event, { kind: "totp", handle: remembered.handle })}>
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
    {#if actions && view.items.length > 0}
      <div class="divider" role="separator"></div>
    {/if}
    {#each view.items as item (item.handle)}
      {@const markers = markersOf(item)}
      {@const last = item.handle === view.last}
      <button class="row" role="menuitem" disabled={busy} onclick={(event) => pick(event, item)}>
        <Avatar name={item.name} icon={view.icon} size={32} />
        <span class="row-content">
          <span class="row-title">{item.name}</span>
          <span class="row-text">{joined(item.username, item.account)}</span>
        </span>
        {#if markers.length > 0 || last}
          <span class="trailing">
            {#each markers as marker (marker.icon)}
              <span class="marker" title={marker.label}><Icon name={marker.icon} size={16} /><span class="sr-only">{marker.label}</span></span>
            {/each}
            {#if last}
              <span class="chip">{t("browser_extension_last_used")}</span>
            {/if}
          </span>
        {/if}
      </button>
    {/each}
    {#if view.items.length === 0 && view.passkeys.length === 0 && !view.totp && !generate}
      <p class="empty body-medium">{t("autofill_no_matching_logins")}</p>
    {/if}
    {#if view.truncated}
      <p class="note body-small">{tp("browser_extension_logins_truncated_plural", view.items.length, view.items.length)}</p>
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
