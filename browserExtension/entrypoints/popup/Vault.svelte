<!--
  The unlocked popup, as the app's Quick search: a search field over a list of
  the page's logins (or the search's) and the details of the selected one,
  with its actions and their shortcuts at the bottom. Below 560px the panes
  stack: the details cover the list, with a back button.
-->
<script lang="ts">
  import { untrack } from "svelte";
  import { MediaQuery } from "svelte/reactivity";
  import { browser } from "wxt/browser";
  import type { PageSuggestions } from "../../src/lib/background/api";
  import { t, tp } from "../../src/lib/i18n";
  import { itemDetailsOf, itemIcon, itemViewOf } from "../../src/lib/page/messages";
  import { SEARCH_QUERY_MAX } from "../../src/lib/protocol/messages";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import ItemDetail from "../../src/lib/ui/components/ItemDetail.svelte";
  import Snackbar from "../../src/lib/ui/components/Snackbar.svelte";
  import Wordmark from "../../src/lib/ui/components/Wordmark.svelte";
  import type { ItemCopy, UriCopy } from "../../src/lib/ui/itemDetails";
  import { callErrorCode, type PopupClient } from "../../src/lib/ui/popupClient";
  import { moveSelection, popupActions, popupKey, runsOnMac, type PopupAction } from "../../src/lib/ui/popupKeys";
  import { PopupSearch, type SearchView } from "../../src/lib/ui/popupSearch";
  import { errorText } from "../../src/lib/ui/statusText";
  import ActionStrip from "./ActionStrip.svelte";
  import { PASSWORD_GENERATOR } from "../../src/lib/ui/features";
  import Generator from "./Generator.svelte";
  import ItemList, { optionId, type Item, type ListView } from "./ItemList.svelte";

  interface Props {
    client: PopupClient;
    /** The active tab: undefined until known, null for none. */
    tabId: number | null | undefined;
  }

  let { client, tabId }: Props = $props();

  const LISTBOX_ID = "logins";
  /** Matches the layout's media queries below. */
  const NARROW_QUERY = "(max-width: 559px)";
  const mac = runsOnMac();

  let page = $state.raw<PageSuggestions | null>(null);
  let pageError = $state<string | null>(null);
  let search = $state.raw<SearchView>({ kind: "matches" });
  /** The error of the last action, and the item it was on. */
  let error = $state.raw<{ handle: string; text: string } | null>(null);
  let busy = $state.raw<{ handle: string; action: PopupAction | UriCopy } | null>(null);
  /** The row picked in a list; another list selects its first row. */
  let picked = $state.raw<{ list: Item[]; handle: string } | null>(null);
  let generatorOpen = $state(false);
  /** Whether "New login" runs, and the error of its last run. */
  let creating = $state(false);
  let createError = $state<string | null>(null);
  /** Narrow: the details cover the list. */
  let detailOpen = $state(false);
  const narrow = new MediaQuery(NARROW_QUERY);
  let field = $state<HTMLInputElement | undefined>();
  let snackbar = $state<Snackbar | undefined>();

  const searcher = new PopupSearch({
    search: (query) =>
      typeof tabId === "number" ? client.call("search", { tabId, query }) : Promise.resolve({ kind: "unsupported" }),
    onView: (view) => (search = view),
  });

  /** Counts the loads of the page's matches: only the latest one shows its answer. */
  let loads = 0;

  /** Asks for the matches of the tab's page. */
  function loadPage(id: number) {
    const load = ++loads;
    client.call("suggest", { tabId: id }).then(
      (result) => {
        if (load !== loads) return;
        page = result;
        pageError = null;
      },
      (failure: unknown) => {
        if (load === loads) pageError = errorText(callErrorCode(failure));
      },
    );
  }

  $effect(() => {
    if (tabId === undefined) return;
    if (tabId === null) {
      page = { kind: "unsupported" };
      return;
    }
    loadPage(tabId);
  });

  $effect(() => () => searcher.dispose());

  // The field has focus when the popup opens.
  $effect(() => field?.focus());

  const pageIcon = $derived(page?.kind === "items" ? page.icon : null);
  /** The origin of the page "New login" is for, if offered. */
  const createOrigin = $derived(page?.kind === "items" && page.create ? page.origin : null);
  const last = $derived(page?.kind === "items" ? page.last : null);
  const searchDisabled = $derived(page?.kind === "unsupported" || page?.kind === "blocked");

  const list = $derived.by((): ListView => {
    if (search.kind === "results") {
      if (search.error !== null) return { kind: "error", text: errorText(search.error) };
      const result = search.result;
      if (result === null) return { kind: "loading" };
      if (result.kind !== "items") {
        // The tab left the page since the popup opened.
        const text =
          result.kind === "blocked" ? "browser_extension_site_blocked_text" : "browser_extension_page_unsupported_text";
        return { kind: "empty", icon: "block", text: t(text) };
      }
      if (result.items.length === 0)
        return { kind: "empty", icon: "search_off", text: t("browser_extension_search_empty") };
      const note = result.truncated
        ? tp("browser_extension_search_truncated_plural", result.items.length, result.items.length)
        : null;
      return { kind: "items", items: result.items, note, suggested: null };
    }
    if (pageError !== null) return { kind: "error", text: pageError };
    if (page === null) return { kind: "loading" };
    switch (page.kind) {
      case "unsupported":
        return { kind: "empty", icon: "block", text: t("browser_extension_page_unsupported_text") };
      case "blocked":
        return { kind: "empty", icon: "block", text: t("browser_extension_site_blocked_text") };
      case "items": {
        if (page.items.length === 0) return { kind: "empty", icon: "search_off", text: t("items_empty_label") };
        const note = page.truncated
          ? tp("browser_extension_logins_truncated_plural", page.items.length, page.items.length)
          : null;
        // The background lists the matched logins first.
        const suggested = page.items.findIndex((item) => !item.matched);
        return { kind: "items", items: page.items, note, suggested: suggested >= 0 ? suggested : null };
      }
    }
  });

  const items = $derived(list.kind === "items" ? list.items : []);
  /** The origin of the page the list is for: fills and copies are for that page only. */
  const listOrigin = $derived.by(() => {
    const result = search.kind === "results" ? search.result : page;
    return result?.kind === "items" ? result.origin : null;
  });
  const selectedIndex = $derived.by(() => {
    if (items.length === 0) return -1;
    if (picked === null || picked.list !== items) return 0;
    return Math.max(
      0,
      items.findIndex((it) => it.handle === picked!.handle),
    );
  });
  const selected = $derived(selectedIndex >= 0 ? items[selectedIndex]! : null);
  const actions = $derived(selected === null ? [] : popupActions(selected));
  const drilled = $derived(detailOpen || generatorOpen);
  const stripShown = $derived(!generatorOpen && actions.length > 0);
  /** Narrow, the details are hidden until a row is opened: an action's error shows over the action strip. */
  const errorOverStrip = $derived(narrow.current && !drilled);
  // An action's error and progress show on its item only: typing may select another meanwhile.
  const selectedError = $derived(error !== null && error.handle === selected?.handle ? error.text : null);
  const selectedBusy = $derived(busy !== null && busy.handle === selected?.handle ? busy.action : null);

  const iconOf = (item: Item) => itemIcon(item, pageIcon);

  function select(index: number) {
    const item = items[index];
    if (item === undefined || busy !== null) return;
    if (item.handle !== selected?.handle) error = null;
    picked = { list: items, handle: item.handle };
    generatorOpen = false;
  }

  // A press on a row: the keys go on in the field, as in Quick search.
  function pick(item: Item) {
    select(items.indexOf(item));
    if (narrow.current && busy === null) detailOpen = true;
    field?.focus();
  }

  /** Scrolls the list to the row at [index]; the row takes no focus. */
  function reveal(index: number) {
    document.getElementById(optionId(LISTBOX_ID, index))?.scrollIntoView({ block: "nearest" });
  }

  function move(step: number) {
    const next = moveSelection(selectedIndex, items.length, step);
    if (next === null) return;
    select(next);
    reveal(next);
  }

  // Other items select their first row, which shows wherever the list was
  // scrolled: Enter fills a login the user sees. After the rows render.
  $effect(() => {
    void items;
    untrack(() => reveal(selectedIndex));
  });

  function back() {
    detailOpen = false;
    generatorOpen = false;
    field?.focus();
  }

  function toggleGenerator() {
    generatorOpen = !generatorOpen;
    if (!generatorOpen) detailOpen = false;
  }

  /** Whether text is selected where a key goes: Mod+C then copies it. */
  function hasTextSelection(target: EventTarget | null): boolean {
    if (target instanceof HTMLInputElement) return target.selectionStart !== target.selectionEnd;
    return (window.getSelection()?.toString() ?? "") !== "";
  }

  function onKeydown(event: KeyboardEvent) {
    // The field's Escape cleared it.
    if (event.defaultPrevented) return;
    const key = popupKey(event, { mac, inField: event.target === field, hasSelection: hasTextSelection(event.target) });
    if (key === null) return;
    if (key.kind === "move") {
      event.preventDefault();
      move(key.step);
      return;
    }
    if (generatorOpen || selected === null || !actions.includes(key.action)) return;
    event.preventDefault();
    if (busy === null) void run(selected, key.action);
  }

  // The actions, as the popup always had them.

  /** The text of a failed call, `null` for none. */
  function failureText(failure: unknown): string | null {
    const code = callErrorCode(failure);
    // The tab shows another page than the list's: the list follows it.
    if (code === "origin" && typeof tabId === "number") {
      loadPage(tabId);
      searcher.refresh();
    }
    // The tab closed or left the page while the app asked: no failure.
    return code === "cancelled" ? null : errorText(code);
  }

  async function act(item: Item, action: PopupAction | UriCopy, body: () => Promise<void>) {
    busy = { handle: item.handle, action };
    error = null;
    createError = null;
    try {
      await body();
    } catch (failure) {
      const text = failureText(failure);
      if (text !== null) error = { handle: item.handle, text };
    } finally {
      busy = null;
    }
  }

  const copied = () => snackbar?.show(t("copied_value"));

  async function copy(value: string) {
    await navigator.clipboard.writeText(value);
    copied();
  }

  function run(item: Item, action: PopupAction) {
    // One action at a time, though only its own item's buttons show it running.
    if (busy !== null || creating || typeof tabId !== "number" || listOrigin === null) return;
    const args = { tabId, handle: item.handle, origin: listOrigin };
    switch (action) {
      case "fill":
        return act(item, action, async () => {
          await client.call("fill", args);
          window.close();
        });
      case "copy_username":
        return act(item, action, () => copy(item.username ?? ""));
      // The background copies values that need an approval: the app's prompt
      // takes focus and closes the popup, which would lose its own write.
      case "copy_password":
        return act(item, action, async () => {
          await client.call("copy_password", args);
          copied();
        });
      case "copy_totp":
        return act(item, action, async () => {
          await client.call("copy_totp", args);
          copied();
        });
    }
  }

  /** The app opens its Save login window with a new login for the page; the popup closes. */
  async function create() {
    // One action at a time, as for [run].
    if (busy !== null || creating || typeof tabId !== "number" || createOrigin === null) return;
    creating = true;
    error = null;
    createError = null;
    try {
      await client.call("create", { tabId, origin: createOrigin });
      window.close();
    } catch (failure) {
      createError = failureText(failure);
    } finally {
      creating = false;
    }
  }

  /** Copies linked URI [uri] of [item], as the username: no approval. */
  function copyUri(item: Item, uri: UriCopy) {
    // As the detail pane lists them: only a page's suggestions have URIs.
    const value = "uris" in item ? itemDetailsOf(item).uris[uri.uri] : undefined;
    if (busy !== null || creating || value === undefined) return;
    return act(item, uri, () => copy(value));
  }

  const onaction = (action: PopupAction | ItemCopy) => {
    if (selected === null) return;
    if (typeof action === "string") void run(selected, action);
    else void copyUri(selected, action);
  };
</script>

<svelte:window onkeydown={onKeydown} />

<div class={["vault", { raised: stripShown }]}>
  <header class="search-row">
    <Icon name="search" class="search-icon" />
    <!-- Not in a form: Enter fills the selected login. -->
    <input
      bind:this={field}
      class="search-field body-large"
      type="search"
      role="combobox"
      aria-expanded={!(narrow.current && drilled)}
      aria-controls={LISTBOX_ID}
      aria-autocomplete="list"
      aria-activedescendant={selected !== null && !(narrow.current && drilled)
        ? optionId(LISTBOX_ID, selectedIndex)
        : undefined}
      placeholder={t("vault_main_search_placeholder")}
      aria-label={t("vault_main_search_placeholder")}
      maxlength={SEARCH_QUERY_MAX}
      autocomplete="off"
      spellcheck="false"
      disabled={searchDisabled}
      oninput={(event) => {
        // Narrow: the list shows again, for what is typed.
        detailOpen = false;
        searcher.input(event.currentTarget.value);
      }}
      onkeydown={(event) => {
        searcher.keydown(event);
        // Escape cleared the field, as for oninput.
        if (event.defaultPrevented) detailOpen = false;
      }}
    />
    {#if createOrigin !== null}
      <button
        class="icon-btn"
        aria-label={t("browser_extension_new_login")}
        title={t("browser_extension_new_login")}
        disabled={busy !== null || creating}
        onclick={() => void create()}
      >
        <Icon name="add" />
      </button>
    {/if}
    {#if PASSWORD_GENERATOR}
      <button
        class="icon-btn"
        aria-pressed={generatorOpen}
        aria-label={t("generator_header_password_title")}
        title={t("generator_header_password_title")}
        onclick={toggleGenerator}
      >
        <Icon name="password" />
      </button>
    {/if}
    <button
      class="icon-btn"
      aria-label={t("settings_main_header_title")}
      title={t("settings_main_header_title")}
      onclick={() => void browser.runtime.openOptionsPage()}
    >
      <Icon name="settings" />
    </button>
  </header>

  <div class={["panes", { drilled }]}>
    <div class="list-pane">
      <ItemList view={list} listboxId={LISTBOX_ID} selected={selected?.handle ?? null} {last} {iconOf} onpick={pick} />
    </div>

    <section class="detail-pane" aria-label={generatorOpen ? t("generator_header_password_title") : selected?.name}>
      <div class="back-bar">
        <button
          class="icon-btn"
          aria-label={t("browser_extension_back")}
          title={t("browser_extension_back")}
          onclick={back}
        >
          <Icon name="arrow_back" />
        </button>
      </div>
      <!-- The flag first: while it's off, the build drops the generator's code (its styles stay, with the import). -->
      {#if PASSWORD_GENERATOR && generatorOpen}
        <div class="detail-content">
          <Generator {client} {tabId} oncopied={copied} />
        </div>
      {:else if selected !== null}
        {#if selectedError && !errorOverStrip}
          <p class="banner" role="alert"><Icon name="error_outline" />{selectedError}</p>
        {/if}
        {#key selected.handle}
          <div class="detail-content">
            <ItemDetail
              item={itemViewOf(selected, pageIcon, selected.handle === last)}
              busy={selectedBusy}
              {onaction}
              shortcuts={{ mac }}
            />
          </div>
        {/key}
      {:else}
        <!-- EmptyKeyguardBox of the app's two-pane layouts. -->
        <div class="watermark" aria-hidden="true">
          <Icon name="lock" size={48} />
          <Wordmark class="watermark-text" />
        </div>
      {/if}
    </section>
  </div>

  {#if createError}
    <p class="banner strip-banner" role="alert"><Icon name="error_outline" />{createError}</p>
  {/if}
  {#if selectedError && errorOverStrip}
    <p class="banner strip-banner" role="alert"><Icon name="error_outline" />{selectedError}</p>
  {/if}
  {#if stripShown}
    <ActionStrip {actions} {mac} busy={selectedBusy} {onaction} />
  {/if}

  <Snackbar bind:this={snackbar} />
</div>

<style>
  .vault {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
  }

  /* The snackbar over the action strip. */
  .raised {
    --snackbar-bottom: 60px;
  }

  /* Quick search's field: no box; the icon turns primary while it has focus. */
  .search-row {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    gap: 4px;
    height: 56px;
    padding: 0 8px 0 20px;
    border-bottom: 1px solid var(--outline-variant);
  }

  .search-row :global(.search-icon) {
    margin-inline-end: 20px;
    color: var(--on-surface);
    transition: color var(--duration-short) var(--ease-standard);
  }

  .search-row:has(.search-field:focus) :global(.search-icon) {
    color: var(--primary);
  }

  .search-row:has(.search-field:disabled) :global(.search-icon) {
    opacity: var(--disabled-content);
  }

  .search-field {
    flex: 1;
    min-width: 0;
    height: 100%;
    padding: 0;
    border: none;
    outline: none;
    background: transparent;
    color: inherit;
    appearance: none;
  }

  .search-field::placeholder {
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
  }

  .search-field:disabled::placeholder {
    color: color-mix(in srgb, var(--on-surface) var(--disabled-content), transparent);
  }

  /* Escape clears the field. */
  .search-field::-webkit-search-cancel-button {
    appearance: none;
  }

  .panes {
    display: flex;
    flex: 1;
    min-height: 0;
  }

  .list-pane {
    flex-shrink: 0;
    width: 240px;
    overflow-y: auto;
    border-inline-end: 1px solid var(--outline-variant);
    background: var(--surface-container-low);
  }

  .detail-pane {
    position: relative;
    flex: 1;
    min-width: 0;
    overflow-y: auto;
    padding: 16px;
    background: var(--surface);
  }

  .back-bar {
    display: none;
  }

  /* Back points to the start of the line: right, in a right-to-left page. */
  :global([dir="rtl"]) .back-bar :global(.icon) {
    transform: scaleX(-1);
  }

  .detail-pane > .banner {
    margin-bottom: 16px;
  }

  .strip-banner {
    flex-shrink: 0;
    margin: 8px 16px;
  }

  .detail-content {
    animation: detail-enter 180ms var(--ease-emphasized-decelerate) both;
  }

  @keyframes detail-enter {
    from {
      opacity: 0;
      translate: 0 8px;
    }
  }

  .watermark {
    position: absolute;
    inset: 0;
  }

  .watermark :global(.watermark-text) {
    font: 400 45px/52px var(--font-sans);
    letter-spacing: 0;
  }

  /* One pane: the details cover the list. */
  @media (max-width: 559px) {
    .list-pane {
      width: 100%;
      border: none;
    }

    .drilled .list-pane,
    .panes:not(.drilled) .detail-pane {
      display: none;
    }

    .drilled .detail-pane {
      padding-top: 0;
      animation: drill-in var(--duration-long) var(--ease-emphasized-decelerate) both;
    }

    .back-bar {
      position: sticky;
      top: 0;
      z-index: 1;
      display: flex;
      margin: 0 -16px 8px;
      padding: 4px 8px;
      background: var(--surface);
    }
  }

  @keyframes drill-in {
    from {
      opacity: 0;
      translate: 32px 0;
    }
  }
</style>
