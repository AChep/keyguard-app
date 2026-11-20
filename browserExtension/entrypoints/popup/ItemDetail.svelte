<!--
  The details of a login: its header and chips, and its fields, as the app's
  VaultViewScreen; Fill is the first action of the strip below. A press on a field copies it: the
  whole row is the copy button, which can't hold another one.
-->
<script lang="ts">
  import { t } from "../../src/lib/i18n";
  import { copiesPassword, type SuggestPasskey } from "../../src/lib/protocol/messages";
  import Avatar from "../../src/lib/ui/components/Avatar.svelte";
  import Code from "../../src/lib/ui/components/Code.svelte";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import LoadingIndicator from "../../src/lib/ui/components/LoadingIndicator.svelte";
  import type { IconName } from "../../src/lib/ui/icons";
  import { ariaKeyShortcuts, SHORTCUTS, type PopupAction } from "../../src/lib/ui/popupKeys";
  import type { Item } from "./ItemList.svelte";

  /** The app's concealed value (concealedText of ui/Password.kt): 8 bullets. */
  const CONCEALED = "•".repeat(8);
  /** A one-time password's length, for its mask. */
  const OTP_MASK = "000000";

  interface Props {
    item: Item;
    icon: string | null;
    /** Filled last on the tab. */
    last: boolean;
    mac: boolean;
    /** The action in progress, on any item. */
    busy: PopupAction | null;
    onaction: (action: PopupAction) => void;
  }

  let { item, icon, last, mac, busy, onaction }: Props = $props();

  const unmatched = $derived("matched" in item && !item.matched);
  const passkeys = $derived<SuggestPasskey[]>("passkeys" in item ? (item.passkeys ?? []) : []);

  interface Field {
    action: PopupAction;
    label: string;
    copyLabel: string;
  }

  const fields = $derived.by(() => {
    const result: Field[] = [];
    if (item.username) result.push({ action: "copy_username", label: t("username"), copyLabel: t("copy_username") });
    // Not when the item's collections hide the password.
    if (copiesPassword(item)) result.push({ action: "copy_password", label: t("password"), copyLabel: t("copy_password") });
    if (item.has_totp) result.push({ action: "copy_totp", label: t("one_time_password"), copyLabel: t("copy_otp_code") });
    return result;
  });

  interface Chip {
    icon: IconName;
    label: string;
    tone: "" | "chip-tertiary" | "chip-error";
  }

  const chips = $derived.by(() => {
    const result: Chip[] = [];
    if (unmatched) result.push({ icon: "warning_amber", label: t("browser_extension_search_unmatched"), tone: "chip-error" });
    if (last) result.push({ icon: "schedule", label: t("browser_extension_last_used"), tone: "" });
    if (item.reprompt) result.push({ icon: "lock", label: t("additem_auth_reprompt_title"), tone: "chip-tertiary" });
    return result;
  });

  const passkeyTitle = (passkey: SuggestPasskey) =>
    passkey.user_display_name || passkey.user_name || t("passkey_unknown_username");
  const passkeyText = (passkey: SuggestPasskey) =>
    passkey.user_display_name && passkey.user_name && passkey.user_name !== passkey.user_display_name ? passkey.user_name : null;
</script>

<header class="header">
  <Avatar name={item.name} {icon} size={48} />
  <div class="titles">
    <h2 class="title-large name">{item.name}</h2>
    <p class="body-medium account">{item.account}</p>
  </div>
</header>

{#if chips.length > 0}
  <div class="chips">
    {#each chips as chip (chip.icon)}
      <span class="chip {chip.tone}"><Icon name={chip.icon} />{chip.label}</span>
    {/each}
  </div>
{/if}

{#if fields.length > 0}
  <div class="group fields">
    {#each fields as field (field.action)}
      <button
        class="field state-layer"
        disabled={busy !== null}
        aria-label={field.copyLabel}
        aria-describedby={field.action === "copy_username" ? "detail-username" : undefined}
        aria-keyshortcuts={ariaKeyShortcuts(SHORTCUTS[field.action], mac)}
        title={field.copyLabel}
        onclick={() => onaction(field.action)}
      >
        <span class="field-text">
          <span class="label-medium field-label">{field.label}</span>
          {#if field.action === "copy_username"}
            <span class="body-large value" id="detail-username">{item.username}</span>
          {:else if field.action === "copy_password"}
            <span class="body-large value concealed">{CONCEALED}</span>
          {:else}
            <Code code={OTP_MASK} masked class="body-large value" />
          {/if}
        </span>
        <span class="field-icon">
          {#if busy === field.action}
            <LoadingIndicator label={t("loading")} size={24} contained={false} />
          {:else}
            <Icon name="content_copy" />
          {/if}
        </span>
      </button>
    {/each}
  </div>
{/if}

{#if passkeys.length > 0}
  <h3 class="section-header passkeys-header">{t("passkeys")}</h3>
  <ul class="group">
    {#each passkeys as passkey (passkey.handle)}
      {@const text = passkeyText(passkey)}
      <li class="passkey">
        <Icon name="key" />
        <span class="field-text">
          <span class="body-large value">{passkeyTitle(passkey)}</span>
          {#if text !== null}
            <span class="body-medium medium value">{text}</span>
          {/if}
        </span>
      </li>
    {/each}
  </ul>
{/if}

<style>
  .header {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .titles {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }

  .name {
    display: -webkit-box;
    margin: 0;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    overflow-wrap: anywhere;
  }

  .account {
    margin: 0;
    overflow: hidden;
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
    padding-inline-start: 64px;
  }

  .fields {
    margin-top: 16px;
  }

  .field {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    min-height: 60px;
    padding: 8px 8px 8px 16px;
    text-align: start;
  }

  .field:disabled {
    cursor: default;
  }

  .field-text {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }

  .field-label {
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
  }

  .field-text :global(.value) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .concealed {
    font-family: var(--font-mono);
    letter-spacing: 0.1em;
  }

  /* The look of an icon button: the row's state layer shows on it too. */
  .field-icon {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: 50%;
    color: var(--on-surface-variant);
    transition: background-color var(--duration-short) var(--ease-standard);
  }

  .field:enabled:hover .field-icon {
    background: color-mix(in srgb, currentColor var(--state-hover), transparent);
  }

  .field:disabled .field-icon {
    color: color-mix(in srgb, var(--on-surface) var(--disabled-content), transparent);
  }

  .passkeys-header {
    padding: 24px 16px 8px;
  }

  .passkey {
    display: flex;
    align-items: center;
    gap: 16px;
    min-height: 56px;
    padding: 8px 16px;
    color: var(--on-surface);
  }

  .passkey > :global(.icon) {
    color: var(--on-surface-variant);
  }
</style>
