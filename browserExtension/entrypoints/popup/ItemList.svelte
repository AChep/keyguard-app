<!--
  The list pane, as the app's Quick search: a section header, then the logins
  as options of a listbox that the search field controls (Vault.svelte), or
  skeleton rows, or an empty state. The rows take no focus: a press selects
  one and leaves focus in the field.
-->
<script module lang="ts">
  import type { IconName } from "../../src/lib/ui/icons";
  import type { SearchItem, SuggestItem } from "../../src/lib/protocol/messages";

  export type Item = SuggestItem | SearchItem;

  export interface ListHeader {
    host?: string;
    count?: string;
  }

  export type ListView =
    | { kind: "loading" }
    | { kind: "error"; text: string }
    | { kind: "empty"; icon: IconName; text: string; header?: ListHeader }
    | { kind: "items"; items: Item[]; header: ListHeader; note: string | null };

  /** The id of the option at [index] of the listbox [listboxId]. */
  export const optionId = (listboxId: string, index: number) => `${listboxId}-${index}`;
</script>

<script lang="ts">
  import { t } from "../../src/lib/i18n";
  import Avatar from "../../src/lib/ui/components/Avatar.svelte";
  import Icon from "../../src/lib/ui/components/Icon.svelte";

  const SKELETON_ROWS = 5;

  interface Props {
    view: ListView;
    listboxId: string;
    /** The handle of the selected item. */
    selected: string | null;
    /** The handle of the login filled last on the tab. */
    last: string | null;
    iconOf: (item: Item) => string | null;
    onpick: (item: Item) => void;
  }

  let { view, listboxId, selected, last, iconOf, onpick }: Props = $props();

  interface Marker {
    icon: IconName;
    label: string;
  }

  /** The small icons after a row's title: what else the login has. */
  function markersOf(item: Item): Marker[] {
    const markers: Marker[] = [];
    if ("matched" in item && !item.matched)
      markers.push({ icon: "warning_amber", label: t("browser_extension_search_unmatched") });
    if (item.has_totp) markers.push({ icon: "two_fa", label: t("one_time_password") });
    if (item.reprompt) markers.push({ icon: "lock", label: t("additem_auth_reprompt_title") });
    if ("passkeys" in item && item.passkeys?.length) markers.push({ icon: "key", label: t("passkeys") });
    return markers;
  }

  const header = $derived(view.kind === "items" || view.kind === "empty" ? view.header : undefined);
  const headerId = $derived(`${listboxId}-header`);
</script>

{#if header !== undefined}
  <h2 class="section-header header" id={headerId}>
    {#if header.host !== undefined}
      <span class="host">{header.host}</span>
    {/if}
    {#if header.host !== undefined && header.count !== undefined}
      <span aria-hidden="true">·</span>
    {/if}
    {#if header.count !== undefined}
      <span class="count">{header.count}</span>
    {/if}
  </h2>
{/if}

{#if view.kind === "error"}
  <p class="banner" role="alert"><Icon name="error_outline" />{view.text}</p>
{:else if view.kind === "loading"}
  <div class="skeletons" role="progressbar" aria-label={t("loading")}>
    <div class="skeleton skeleton-header"></div>
    {#each { length: SKELETON_ROWS } as _, index (index)}
      <div class="skeleton skeleton-row"></div>
    {/each}
  </div>
{:else if view.kind === "empty"}
  <div class="empty">
    <Icon name={view.icon} />
    <p class="label-large">{view.text}</p>
  </div>
{/if}

<!-- Always there: the search field's aria-controls names it. -->
<div
  class={["options", { populated: view.kind === "items" }]}
  role="listbox"
  id={listboxId}
  aria-labelledby={header !== undefined ? headerId : undefined}
  aria-label={header === undefined ? t("vault_main_search_placeholder") : undefined}
>
  {#if view.kind === "items"}
    {#each view.items as item, index (item.handle)}
      {@const markers = markersOf(item)}
      <!-- A press selects without taking focus from the search field. -->
      <div
        class="row"
        role="option"
        id={optionId(listboxId, index)}
        aria-selected={item.handle === selected}
        tabindex="-1"
        onmousedown={(event) => {
          if (event.button !== 0) return;
          event.preventDefault();
          onpick(item);
        }}
      >
        <Avatar name={item.name} icon={iconOf(item)} />
        <div class="row-content">
          <div class="line">
            <span class="row-title">{item.name}</span>
            {#if markers.length > 0}
              <span class="markers">
                {#each markers as marker (marker.icon)}
                  <span title={marker.label}
                    ><Icon name={marker.icon} size={16} /><span class="sr-only">{marker.label}</span></span
                  >
                {/each}
              </span>
            {/if}
          </div>
          {#if item.username}
            <span class="row-text">{item.username}</span>
          {/if}
          {#if item.handle === last}
            <span class="chip last">{t("browser_extension_last_used")}</span>
          {/if}
        </div>
      </div>
    {/each}
  {/if}
</div>

{#if view.kind === "items" && view.note !== null}
  <p class="note body-small">{view.note}</p>
{/if}

<style>
  .header {
    display: flex;
    gap: 4px;
  }

  .host {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .count {
    flex-shrink: 0;
  }

  .banner {
    margin: 8px;
  }

  /* Quick search's placeholder: blocks 6px apart, inset as the rows, under a header's. */
  .skeletons {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 0 8px 8px;
  }

  /* On the header's line: 16px down, a 16px line, 8px to the rows. */
  .skeleton-header {
    width: 128px;
    height: 12px;
    margin: 18px 12px 4px;
    border-radius: var(--shape-xs);
  }

  .skeleton-row {
    height: 60px;
  }

  /* EmptySearchView: an icon and a label at medium emphasis, centered. */
  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 48px 24px 24px;
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
    text-align: center;
  }

  .empty p {
    max-width: 196px;
    margin: 0;
    text-wrap: balance;
  }

  .options {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .options.populated {
    padding: 0 8px 8px;
  }

  .row {
    min-height: 56px;
    padding: 8px 12px;
  }

  .line {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }

  .line .row-title {
    flex: 1;
  }

  .markers {
    display: flex;
    flex-shrink: 0;
    gap: 4px;
    color: color-mix(in srgb, currentColor var(--text-medium-alpha), transparent);
  }

  .last {
    align-self: flex-start;
    margin-top: 4px;
  }

  .note {
    margin: 0;
    padding: 0 20px 16px;
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
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
