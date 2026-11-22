<!--
  The password generator of the details pane: generates once it opens. The
  password is colored as the app colors one (colorizePassword of ui/Password.kt):
  digits blue, symbols red, letters as the text.
-->
<script lang="ts">
  import { t } from "../../src/lib/i18n";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import LoadingIndicator from "../../src/lib/ui/components/LoadingIndicator.svelte";
  import { callErrorCode, type PopupClient } from "../../src/lib/ui/popupClient";
  import { errorText } from "../../src/lib/ui/statusText";

  interface Props {
    client: PopupClient;
    tabId: number | null | undefined;
    oncopied: () => void;
  }

  let { client, tabId, oncopied }: Props = $props();

  let password = $state<string | null>(null);
  let busy = $state(false);
  let error = $state<string | null>(null);

  type Kind = "letter" | "digit" | "symbol";

  /** Runs of symbols of one kind, as `Char.isLetter` and `isDigit` tell them; a surrogate pair is a letter there. */
  const runs = $derived.by(() => {
    const result: { kind: Kind; text: string }[] = [];
    for (const char of Array.from(password ?? "")) {
      const kind: Kind = char.length > 1 || /\p{L}/u.test(char) ? "letter" : /\p{Nd}/u.test(char) ? "digit" : "symbol";
      const previous = result.at(-1);
      if (previous?.kind === kind) previous.text += char;
      else result.push({ kind, text: char });
    }
    return result;
  });

  async function generate() {
    busy = true;
    error = null;
    try {
      password = (await client.call("generate", typeof tabId === "number" ? { tabId } : {})).password;
    } catch (failure) {
      error = errorText(callErrorCode(failure));
    } finally {
      busy = false;
    }
  }

  async function copy() {
    if (password === null) return;
    try {
      await navigator.clipboard.writeText(password);
      oncopied();
    } catch (failure) {
      error = errorText(callErrorCode(failure));
    }
  }

  void generate();
</script>

<h2 class="title-large title">{t("generator_header_password_title")}</h2>

{#if error}
  <p class="banner" role="alert"><Icon name="error_outline" />{error}</p>
{/if}

<div class="password" class:stale={busy && password !== null}>
  {#if password !== null}
    <output aria-live="polite"
      >{#each runs as run, index (index)}<span class={run.kind}>{run.text}</span>{/each}</output
    >
  {:else if busy}
    <LoadingIndicator label={t("loading")} />
  {/if}
</div>

<div class="actions">
  <button class="btn btn-tonal" disabled={busy} onclick={generate}>
    <Icon name="refresh" />
    {t(password === null ? "generator_generate_button" : "generator_regenerate_button")}
  </button>
  <button class="btn btn-filled" disabled={password === null || busy} onclick={copy}>
    <Icon name="content_copy" />
    {t("copy")}
  </button>
</div>

<style>
  .password {
    --digit: hsl(210 72% 42%);
    --symbol: hsl(0 72% 62%);
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 96px;
    margin-top: 16px;
    padding: 16px;
    border-radius: var(--shape-lg);
    background: var(--surface-container-high);
    transition: opacity var(--duration-short) var(--ease-standard);
  }

  /* The app's colors on a dark background. */
  @media (prefers-color-scheme: dark) {
    .password {
      --digit: hsl(210 52% 66%);
      --symbol: hsl(0 52% 56%);
    }
  }

  .stale {
    opacity: var(--disabled-content);
  }

  output {
    width: 100%;
    font: 400 20px/28px var(--font-mono);
    letter-spacing: 0;
    overflow-wrap: anywhere;
    user-select: all;
  }

  .digit {
    color: var(--digit);
  }

  .symbol {
    color: var(--symbol);
  }

  .title {
    margin: 0;
  }

  .banner {
    margin-top: 16px;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 16px;
  }
</style>
