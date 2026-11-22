<script lang="ts">
  import type { CallMethod, ExtensionStatus } from "../../src/lib/background/api";
  import { t, type I18nKey } from "../../src/lib/i18n";
  import Code from "../../src/lib/ui/components/Code.svelte";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import LoadingIndicator from "../../src/lib/ui/components/LoadingIndicator.svelte";
  import Logo from "../../src/lib/ui/components/Logo.svelte";
  import Wordmark from "../../src/lib/ui/components/Wordmark.svelte";
  import type { IconName } from "../../src/lib/ui/icons";
  import { callErrorCode, type PopupClient } from "../../src/lib/ui/popupClient";
  import { errorText, statusTitle } from "../../src/lib/ui/statusText";

  const DOCS_URL = "https://keyguard.dev/docs/browser-extension/";

  interface Props {
    client: PopupClient;
    status: ExtensionStatus;
  }

  let { client, status }: Props = $props();

  let busy = $state(false);
  /** The error of the last call: about the status it was made in. */
  let error = $state<string | null>(null);
  /** Counts the screens, one per kind of status the popup showed: a call's error is for its own. */
  let screens = 0;

  // Another status, another screen: the last call's error goes.
  const kind = $derived(status.kind);
  $effect.pre(() => {
    void kind;
    screens++;
    error = null;
  });

  interface Content {
    text?: I18nKey;
    action?: { label: I18nKey; method: CallMethod; icon: IconName };
    docs?: boolean;
    progress?: boolean;
    danger?: boolean;
  }

  const RETRY = { label: "retry", method: "retry", icon: "refresh" } as const;
  const PAIR = { label: "browser_extension_pair_button", method: "pair", icon: "link" } as const;

  function content(status: ExtensionStatus): Content {
    switch (status.kind) {
      case "starting":
      case "connecting":
        return { progress: true };
      case "connector_missing":
        return { text: "browser_extension_connector_missing_text", action: RETRY, docs: true };
      case "app_unavailable":
        return { text: "browser_extension_app_unavailable_text", docs: true };
      case "integration_disabled":
        return { text: "browser_extension_integration_disabled_text", docs: true };
      case "update_required":
        return { text: "browser_extension_update_required_text", action: RETRY, docs: true };
      case "extension_update_required":
        return { text: "browser_extension_extension_update_required_text", action: RETRY, docs: true };
      case "app_update_required":
        return { text: "browser_extension_app_update_required_text", action: RETRY };
      case "unverified_host":
        return { text: "browser_extension_unverified_host_text", docs: true, danger: true };
      case "too_many_sessions":
        return { text: "browser_extension_too_many_sessions_text", action: RETRY };
      case "protocol_error":
        return { action: RETRY };
      case "not_paired":
        switch (status.pairing.phase) {
          case "starting":
            return { progress: true };
          case "confirm":
            return { text: "browser_extension_pairing_code_text" };
          case "failed":
            // The app pairs only while its vault is unlocked (protocol §8).
            if (status.pairing.error === "vault_locked") {
              return { text: "browser_extension_pair_locked_text", action: { ...RETRY, method: "pair" } };
            }
            return { text: "browser_extension_not_paired_text", action: PAIR };
          default:
            return { text: "browser_extension_not_paired_text", action: PAIR };
        }
      case "pinned_key_mismatch":
        return {
          text: "browser_extension_key_mismatch_text",
          action: { label: "browser_extension_pair_again_button", method: "repair", icon: "refresh" },
          danger: true,
        };
      case "locked":
        return status.unlocking
          ? { text: "browser_extension_unlock_pending_text", progress: true }
          : {
              text: "browser_extension_locked_text",
              action: { label: "unlock_button_unlock", method: "unlock", icon: "lock_open" },
            };
      case "unlocked":
        return {};
    }
  }

  const view = $derived(content(status));
  const code = $derived(
    status.kind === "not_paired" && status.pairing.phase === "confirm" ? status.pairing.code : null,
  );
  const title = $derived(code !== null ? t("browser_extension_pairing_code_title") : statusTitle(status));
  // An error the background keeps in the status, or one of the last call.
  const shownError = $derived.by(() => {
    if (status.kind === "not_paired" && status.pairing.phase === "failed") {
      return status.pairing.error === "vault_locked" ? null : errorText(status.pairing.error);
    }
    if (status.kind === "locked" && status.error !== undefined) return errorText(status.error);
    return error;
  });

  async function run(method: CallMethod) {
    const screen = screens;
    busy = true;
    error = null;
    try {
      await client.call(method, {});
    } catch (failure) {
      // Not once the status moved on, as when the app needs an update while Unlock waits.
      if (screen === screens) error = errorText(callErrorCode(failure));
    } finally {
      busy = false;
    }
  }
</script>

<section class="status">
  {#if view.progress && !view.text}
    <!-- Starting or connecting: no more to say. -->
    <div class="column">
      <LoadingIndicator label={t("loading")} />
    </div>
  {:else}
    <div class="column">
      {#if view.danger}
        <span class="tile danger"><Icon name="warning_amber" size={32} /></span>
      {:else if view.progress}
        <LoadingIndicator label={t("loading")} size={56} />
      {:else}
        <Logo size={56} />
        <Wordmark class="title-large brand" />
      {/if}
      <h1 class="headline-small">{title}</h1>
      {#if code !== null}
        <div class="code" aria-live="polite">
          <Code {code} class="headline-small" />
        </div>
      {/if}
      {#if view.text}
        <p class="message body-medium">{t(view.text)}</p>
      {/if}
      {#if shownError}
        <p class="banner" role="alert"><Icon name="error_outline" />{shownError}</p>
      {/if}
      {#if view.action || view.docs}
        <div class="actions">
          {#if view.action}
            <button class="btn btn-filled" disabled={busy} onclick={() => run(view.action!.method)}>
              <Icon name={view.action.icon} />
              {t(view.action.label)}
            </button>
          {/if}
          {#if view.docs}
            <a class="btn btn-text" href={DOCS_URL} target="_blank" rel="noreferrer">
              {t("learn_more")}
              <Icon name="open_in_new" />
            </a>
          {/if}
        </div>
      {/if}
    </div>
  {/if}
</section>

<style>
  .status {
    display: flex;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 24px 16px;
  }

  /* Centered, and scrolls from its top when taller than the popup. */
  .column {
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 100%;
    max-width: 320px;
    margin: auto;
    text-align: center;
  }

  .column :global(.brand) {
    margin-top: 12px;
  }

  .tile {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 56px;
    height: 56px;
    border-radius: var(--shape-lg);
  }

  .danger {
    background: var(--error-container);
    color: var(--error);
  }

  h1 {
    margin: 24px 0 0;
    text-wrap: balance;
  }

  .code {
    margin-top: 16px;
    padding: 12px 24px;
    border-radius: var(--shape-full);
    background: var(--surface-container-high);
  }

  .message {
    margin: 8px 0 0;
    color: var(--on-surface-variant);
    text-wrap: pretty;
  }

  .code + .message {
    margin-top: 16px;
  }

  .banner {
    width: 100%;
    margin-top: 16px;
    text-align: start;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 8px;
    margin-top: 24px;
  }
</style>
