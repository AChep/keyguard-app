<!--
  The options page, in the look of the app's settings: the switches in the
  untitled segmented groups of the app's Autofill settings (general, save,
  other), with a note on where "Never for this site" goes under the save group.
  Chromium embeds it in the dialog of chrome://extensions, about 400px wide;
  Firefox, in the add-on's page of about:addons. Opened in a tab, it's a
  centered column.
-->
<script lang="ts">
  import { onMount } from "svelte";
  import { browser } from "wxt/browser";
  import { t, type I18nKey } from "../../src/lib/i18n";
  import type { IconName } from "../../src/lib/ui/icons";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import Logo from "../../src/lib/ui/components/Logo.svelte";
  import Wordmark from "../../src/lib/ui/components/Wordmark.svelte";
  import {
    browserSavingNote,
    browserSavingState,
    restoreBrowserSaving,
    turnOffBrowserSaving,
    type BrowserSavingState,
    type PrivacyBrowser,
  } from "../../src/lib/options/browserSaving";
  import { observeSiteAccess, requestAllSites } from "../../src/lib/options/siteAccess";
  import {
    DEFAULT_OPTIONS,
    OptionsStore,
    watchOptions,
    type ExtensionOptions,
    type ObservableStorageArea,
  } from "../../src/lib/options/store";

  const storage = browser.storage.local as unknown as ObservableStorageArea;
  const store = new OptionsStore(storage);

  let options = $state<ExtensionOptions>({ ...DEFAULT_OPTIONS });

  const privacy = browser as unknown as PrivacyBrowser;
  let browserSaving = $state<BrowserSavingState | "loading" | "error">("loading");
  let browserSavingBusy = $state(false);
  const browserSavingNoteKey = $derived(
    browserSaving === "loading" || browserSaving === "error" ? undefined : browserSavingNote(browserSaving),
  );
  const browserSavingUnavailable = $derived(
    browserSaving === "loading" || browserSaving === "error" || browserSavingNoteKey !== undefined,
  );

  // Access to every website. Undefined until the first check answers, so that
  // the row doesn't show a wrong text.
  let allSites = $state<boolean | undefined>();

  type Toggle = { key: keyof ExtensionOptions; icon: IconName; title: I18nKey; text: I18nKey };

  // Each switch sits in the group of its counterpart in the app's Autofill settings.
  const generalToggles: Toggle[] = [
    {
      key: "inlineMenu",
      icon: "list",
      title: "pref_item_autofill_inline_suggestions_title",
      text: "browser_extension_options_inline_menu_text",
    },
    {
      key: "fillPasswordStep",
      icon: "password",
      title: "browser_extension_options_password_step_title",
      text: "browser_extension_options_password_step_text",
    },
    {
      key: "submitAfterFill",
      icon: "keyboard_return",
      title: "browser_extension_options_submit_title",
      text: "browser_extension_options_submit_text",
    },
  ];

  const saveToggles: Toggle[] = [
    {
      key: "savePrompts",
      icon: "save",
      title: "pref_item_autofill_save_request_title",
      text: "browser_extension_options_save_text",
    },
  ];

  const otherToggles: Toggle[] = [
    {
      key: "passkeys",
      icon: "key",
      title: "pref_item_autofill_passkeys_enabled_title",
      text: "browser_extension_options_passkeys_text",
    },
  ];

  $effect(() => watchOptions(storage, (next) => (options = next)));
  // A grant from the button comes back through `permissions.onAdded`.
  $effect(() => observeSiteAccess(browser, undefined, (granted) => (allSites = granted)));
  onMount(() => void loadBrowserSaving());

  async function loadBrowserSaving() {
    browserSaving = await browserSavingState(privacy).catch(() => "error" as const);
  }

  async function retryBrowserSaving() {
    if (browserSavingBusy) return;
    browserSavingBusy = true;
    await loadBrowserSaving();
    browserSavingBusy = false;
  }

  function onToggleChange(input: HTMLInputElement, key: keyof ExtensionOptions) {
    // A failed write leaves the options as they were, so Svelte wouldn't reset the input.
    store.setOptions({ [key]: input.checked }).catch(() => (input.checked = options[key]));
  }

  async function onBrowserSavingChange(input: HTMLInputElement) {
    // Starts the permission request before anything is awaited.
    const task = input.checked ? turnOffBrowserSaving(privacy) : restoreBrowserSaving(privacy);
    browserSavingBusy = true;
    try {
      browserSaving = await task;
    } catch {
      await loadBrowserSaving();
    }
    // A denied request leaves the state as it was, so Svelte wouldn't reset the input.
    input.checked = browserSaving === "on";
    browserSavingBusy = false;
  }
</script>

{#snippet toggleRow(toggle: Toggle)}
  <label class="row setting">
    <span class="leading"><Icon name={toggle.icon} /></span>
    <span class="row-content">
      <span class="body-large">{t(toggle.title)}</span>
      <span class="body-medium medium">{t(toggle.text)}</span>
    </span>
    <input
      class="switch"
      type="checkbox"
      role="switch"
      checked={options[toggle.key]}
      onchange={(event) => onToggleChange(event.currentTarget, toggle.key)}
    />
  </label>
{/snippet}

<main>
  <header>
    <div class="brand">
      <Logo size={40} />
      <Wordmark />
    </div>
    <h1 class="headline-small">{t("settings_main_header_title")}</h1>
  </header>

  <div class="groups">
    <div class="group">
      <!-- Without it the content script doesn't run, so the inline menu and save prompts depend on it. -->
      <div class="row setting" aria-busy={allSites === undefined}>
        <span class="leading"><Icon name="public" /></span>
        <span class="row-content">
          <span class="body-large">{t("browser_extension_site_access_title")}</span>
          <!-- Announced as a grant changes it: the focused button goes away. -->
          <span class="body-medium medium" role="status">
            {#if allSites}
              {t("browser_extension_site_access_all_text")}
            {:else if allSites === false}
              {t("browser_extension_site_access_missing_text")}
            {/if}
          </span>
          {#if allSites === false}
            <span class="note body-small">{t("browser_extension_site_access_guide")}</span>
            <span class="row-actions">
              <!-- The request starts in the click itself: it needs the user's gesture. -->
              <button
                class="btn btn-filled btn-xs"
                type="button"
                onclick={() => void requestAllSites(browser).catch(() => {})}
              >
                {t("browser_extension_site_access_allow_all_button")}
              </button>
            </span>
          {/if}
        </span>
      </div>
      {#each generalToggles as toggle (toggle.key)}
        {@render toggleRow(toggle)}
      {/each}
    </div>

    <div>
      <div class="group">
        {#each saveToggles as toggle (toggle.key)}
          {@render toggleRow(toggle)}
        {/each}
        <!-- A note explains when someone else controls the setting. -->
        <label class={["row setting", { disabled: browserSavingUnavailable }]}>
          <span class="leading"><Icon name="password" /></span>
          <span class="row-content">
            <span class="body-large">{t("browser_extension_options_browser_saving_title")}</span>
            <span class="body-medium medium">{t("browser_extension_options_browser_saving_text")}</span>
            {#if browserSavingNoteKey}
              <span class="note body-small">{t(browserSavingNoteKey)}</span>
            {/if}
          </span>
          <input
            class="switch"
            type="checkbox"
            role="switch"
            checked={browserSaving === "on"}
            disabled={browserSavingBusy || browserSavingUnavailable}
            aria-busy={browserSavingBusy || browserSaving === "loading"}
            onchange={(event) => void onBrowserSavingChange(event.currentTarget)}
          />
        </label>
      </div>

      {#if browserSaving === "error"}
        <div class="banner">
          <Icon name="error_outline" />
          <span role="alert">{t("error_failed_unknown")}</span>
          <!-- Keep the button focusable during a retry so a failed read leaves focus in place. -->
          <button class="btn btn-text" type="button" aria-disabled={browserSavingBusy} onclick={retryBrowserSaving}>
            {t("retry")}
          </button>
        </div>
      {/if}

      <p class="footnote body-medium medium">{t("browser_extension_options_never_save_text")}</p>
    </div>

    <div class="group">
      {#each otherToggles as toggle (toggle.key)}
        {@render toggleRow(toggle)}
      {/each}
    </div>
  </div>

  <!-- The licenses of the bundled third-party code, see scripts/third-party-notices.ts. -->
  <a class="btn btn-text licenses" href="/THIRD_PARTY_NOTICES.txt" target="_blank" rel="noreferrer">
    {t("pref_item_open_source_licenses_title")}
    <Icon name="open_in_new" />
  </a>
</main>

<style>
  main {
    width: 100%;
    max-width: calc(600px + 2 * var(--space-24));
    margin: 0 auto;
    padding: var(--space-24);
  }

  /* Narrow, as in the dialog of chrome://extensions: the app's 16px gutters. */
  @media (max-width: 480px) {
    main {
      padding: var(--space-16);
    }
  }

  header {
    display: flex;
    flex-direction: column;
    gap: var(--space-16);
    margin-bottom: var(--space-24);
  }

  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-12);
  }

  h1 {
    margin: 0;
  }

  /* Untitled groups 24px apart, as the app's settings separate theirs. */
  .groups {
    display: flex;
    flex-direction: column;
    gap: var(--space-24);
  }

  /* The app's settings rows: 16px in, a leading 24px icon and a trailing switch 16px from the text. */
  .setting {
    gap: var(--space-16);
    min-height: 56px;
    padding: var(--space-12) var(--space-16);
  }

  /* The leading icons, in a 36px column. */
  .leading {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 36px;
    color: var(--on-surface-variant);
  }

  .setting.disabled {
    cursor: default;
  }

  .setting.disabled::before {
    background: transparent;
  }

  /* Disabled content at 38%, the supporting text too rather than 38% of its 60%. */
  .setting.disabled > .leading,
  .setting.disabled .row-content > :not(.note) {
    color: var(--on-surface);
    opacity: var(--disabled-content);
  }

  .note {
    margin-top: var(--space-4);
    color: var(--on-surface-variant);
  }

  .row-actions {
    display: flex;
    margin-top: var(--space-8);
  }

  /* A note under the group, 16px in like its rows. */
  .footnote {
    margin: 0;
    padding: var(--space-12) var(--space-16) 0;
  }

  /* A text button, its label 16px in like the note's. */
  .licenses {
    margin-top: var(--space-8);
  }
</style>
