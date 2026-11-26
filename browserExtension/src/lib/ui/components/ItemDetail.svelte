<!--
  The details of a login, as the app's VaultViewScreen: its header and chips,
  its fields, its passkeys, then its linked URIs, its folder and when it was
  last modified. The popup's detail pane and the card beside the inline menu
  show it. A press on a field or a linked URI copies it: the whole row is the
  copy button, which can't hold another one. The fields show the username, or
  a mask in place of the password or the one-time password, which the
  extension's pages never get.
-->
<script lang="ts">
  import type { Snippet } from "svelte";
  import { t, type I18nKey } from "../../i18n";
  import type { ItemView } from "../../page/messages";
  import type { IconName } from "../icons";
  import { detailLines, type ItemCopy, type UriCopy } from "../itemDetails";
  import { ariaKeyShortcuts, SHORTCUTS, type CopyAction, type PopupAction } from "../popupKeys";
  import Avatar from "./Avatar.svelte";
  import Code from "./Code.svelte";
  import Icon from "./Icon.svelte";
  import LoadingIndicator from "./LoadingIndicator.svelte";

  interface Props {
    item: ItemView;
    /** The action or copy in progress, on any item: the rows are disabled meanwhile. */
    busy: PopupAction | UriCopy | null;
    /** A press on a field or a linked URI, with its click: the card checks it first. */
    onaction: (copy: ItemCopy, event: MouseEvent) => void;
    /** The popup's keyboard shortcuts, which its fields name; the card has none. */
    shortcuts?: { mac: boolean };
  }

  let { item, busy, onaction, shortcuts }: Props = $props();

  /** The avatar's size in pixels: the chips line up with the name past it and the header's gap. */
  const AVATAR_SIZE = 48;
  /** The app's concealed value (concealedText of ui/Password.kt): 8 bullets. */
  const CONCEALED = "•".repeat(8);
  /** A one-time password's length, for its mask. */
  const OTP_MASK = "000000";

  /** Each field's label, and the label of its copy. */
  const FIELDS: Record<CopyAction, { label: I18nKey; copyLabel: I18nKey }> = {
    copy_username: { label: "username", copyLabel: "copy_username" },
    copy_password: { label: "password", copyLabel: "copy_password" },
    copy_totp: { label: "one_time_password", copyLabel: "copy_otp_code" },
  };

  interface Chip {
    icon: IconName;
    label: string;
    tone: "" | "chip-tertiary" | "chip-error";
  }

  const chips = $derived.by(() => {
    const result: Chip[] = [];
    if (!item.matched)
      result.push({ icon: "warning_amber", label: t("browser_extension_search_unmatched"), tone: "chip-error" });
    if (item.last) result.push({ icon: "schedule", label: t("browser_extension_last_used"), tone: "" });
    if (item.reprompt) result.push({ icon: "lock", label: t("additem_auth_reprompt_title"), tone: "chip-tertiary" });
    return result;
  });

  const lines = $derived(item.details === undefined ? null : detailLines(item.details));

  /** Whether [copy] is the one in progress. */
  const running = (copy: ItemCopy) =>
    typeof copy === "string" ? busy === copy : typeof busy === "object" && busy?.uri === copy.uri;

  type Passkey = ItemView["passkeys"][number];

  const passkeyTitle = (passkey: Passkey) =>
    passkey.user_display_name || passkey.user_name || t("passkey_unknown_username");
  const passkeyText = (passkey: Passkey) =>
    passkey.user_display_name && passkey.user_name && passkey.user_name !== passkey.user_display_name
      ? passkey.user_name
      : null;
</script>

<!--
  A row that copies [copy]: [content], then the copy icon, or the progress
  while it copies. [label] names the action; [describedby], the value it
  copies, which the label hides from assistive technologies otherwise.
-->
{#snippet copyRow(copy: ItemCopy, label: string, content: Snippet, describedby?: string)}
  <button
    class={["field state-layer", { uri: typeof copy === "object" }]}
    disabled={busy !== null}
    aria-label={label}
    aria-describedby={describedby}
    aria-keyshortcuts={shortcuts === undefined || typeof copy === "object"
      ? undefined
      : ariaKeyShortcuts(SHORTCUTS[copy], shortcuts.mac)}
    title={label}
    onclick={(event) => onaction(copy, event)}
  >
    {@render content()}
    <span class="field-icon">
      {#if running(copy)}
        <LoadingIndicator label={t("loading")} size={24} contained={false} />
      {:else}
        <Icon name="content_copy" />
      {/if}
    </span>
  </button>
{/snippet}

<header class="header">
  <Avatar name={item.name} icon={item.icon} size={AVATAR_SIZE} />
  <div class="titles">
    <h2 class="title-large name">{item.name}</h2>
    <p class="body-medium account">{item.account}</p>
  </div>
</header>

{#if chips.length > 0}
  <div class="chips" style:padding-inline-start="{AVATAR_SIZE + 16}px">
    {#each chips as chip (chip.icon)}
      <span class="chip {chip.tone}"><Icon name={chip.icon} />{chip.label}</span>
    {/each}
  </div>
{/if}

{#if item.copies.length > 0}
  <div class="group fields">
    {#each item.copies as action (action)}
      {#snippet text()}
        <span class="row-item-text">
          <span class="label-medium field-label">{t(FIELDS[action].label)}</span>
          {#if action === "copy_username"}
            <span class="body-large value" id="detail-username">{item.username ?? ""}</span>
          {:else if action === "copy_password"}
            <span class="body-large value concealed">{CONCEALED}</span>
          {:else}
            <Code code={OTP_MASK} masked class="body-large value" />
          {/if}
        </span>
      {/snippet}
      {@render copyRow(
        action,
        t(FIELDS[action].copyLabel),
        text,
        action === "copy_username" ? "detail-username" : undefined,
      )}
    {/each}
  </div>
{/if}

{#if item.passkeys.length > 0}
  <h3 class="section-header">{t("passkeys")}</h3>
  <ul class="group">
    {#each item.passkeys as passkey, index (index)}
      {@const text = passkeyText(passkey)}
      <li class="row-item">
        <Icon name="key" />
        <span class="row-item-text">
          <span class="body-large value">{passkeyTitle(passkey)}</span>
          {#if text !== null}
            <span class="body-medium medium value">{text}</span>
          {/if}
        </span>
      </li>
    {/each}
  </ul>
{/if}

{#if lines !== null}
  {#if lines.urisHeader !== null}
    <h3 class="section-header">{lines.urisHeader}</h3>
    <ul class="group">
      {#each lines.uris as uri, index (index)}
        {#snippet text()}
          <span class="uri-content">
            <Icon name="public" />
            <!-- URIs read left to right in any language. -->
            <span class="row-item-text">
              <span class="body-large value" dir="ltr" id={`detail-uri-${index}`}>{uri}</span>
            </span>
          </span>
        {/snippet}
        <li>{@render copyRow({ uri: index }, t("copy_uri"), text, `detail-uri-${index}`)}</li>
      {/each}
      {#if lines.more !== null}
        <li class="row-item more">
          <span class="row-item-text"><span class="body-medium medium value">{lines.more}</span></span>
        </li>
      {/if}
    </ul>
  {/if}

  {#if lines.folder !== null}
    <h3 class="section-header">{t("folder")}</h3>
    <div class="group">
      <div class="row-item">
        <Icon name="folder" />
        <span class="row-item-text"><span class="body-large value">{lines.folder}</span></span>
      </div>
    </div>
  {/if}

  {#if lines.modified !== null}
    <!-- The app's VaultViewLabelItem. -->
    <p class="modified label-medium medium">{lines.modified}</p>
  {/if}
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

  /* In its list item, which the group shapes. */
  .field.uri {
    min-height: 56px;
    border-radius: inherit;
  }

  .field-label {
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
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

  /* In the inset of the fields, not of the list pane. */
  .section-header {
    padding: 24px 16px 8px;
  }

  .row-item {
    display: flex;
    align-items: center;
    gap: 16px;
    min-width: 0;
    min-height: 56px;
    padding: 8px 16px;
    color: var(--on-surface);
  }

  .row-item > :global(.icon),
  .uri-content > :global(.icon) {
    color: var(--on-surface-variant);
  }

  /* A URI's icon and text, as a row's, before its copy icon. */
  .uri-content {
    display: flex;
    flex: 1;
    align-items: center;
    gap: 16px;
    min-width: 0;
  }

  /* Lined up with the text of the rows above, past their icon. */
  .more {
    min-height: 0;
    padding-inline-start: 56px;
  }

  /* The text column of a row or a field: its values on one line each. */
  .row-item-text {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }

  /* Global: the one-time password's mask is a Code. */
  .row-item-text :global(.value) {
    overflow: hidden;
    text-align: start;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .modified {
    margin: 0;
    padding: 16px 16px 0;
  }
</style>
