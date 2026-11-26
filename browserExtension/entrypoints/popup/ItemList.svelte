<!--
  The list pane, as the app's Quick search: the logins as options of a listbox
  that the search field controls (Vault.svelte), or skeleton rows, or an empty
  state. The page's suggested logins follow the
  matched ones in a group of the same listbox, under their own header. The
  rows take no focus: a press selects one and leaves focus in the field.
-->
<script module lang="ts">
  import type { IconName } from "../../src/lib/ui/icons";
  import type { SearchItem, SuggestItem } from "../../src/lib/protocol/messages";

  export type Item = SuggestItem | SearchItem;

  export type ListView =
    | { kind: "loading" }
    | { kind: "error"; text: string }
    | { kind: "empty"; icon: IconName; text: string }
    | {
        kind: "items";
        items: Item[];
        note: string | null;
        /** The index of the first suggested login, which the "Suggested" header precedes; `null` for none. */
        suggested: number | null;
      };

  /** The id of the option at [index] of the listbox [listboxId]. */
  export const optionId = (listboxId: string, index: number) => `${listboxId}-${index}`;
</script>

<script lang="ts">
  import { t } from "../../src/lib/i18n";
  import Avatar from "../../src/lib/ui/components/Avatar.svelte";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import { itemMarkers } from "../../src/lib/ui/itemDetails";

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

  const suggestedId = $derived(`${listboxId}-suggested`);
</script>

{#snippet row(item: Item, index: number)}
  {@const markers = itemMarkers(item)}
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
{/snippet}

{#if view.kind === "error"}
  <p class="banner" role="alert"><Icon name="error_outline" />{view.text}</p>
{:else if view.kind === "loading"}
  <div class="skeletons" role="progressbar" aria-label={t("loading")}>
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
  aria-label={t("vault_main_search_placeholder")}
>
  {#if view.kind === "items"}
    {@const split = view.suggested ?? view.items.length}
    {#if split > 0}
      <!-- The rows on the container, as segmented groups: theme.css's .group. -->
      <div class="group" role="presentation">
        {#each view.items.slice(0, split) as item, index (item.handle)}
          {@render row(item, index)}
        {/each}
      </div>
    {/if}
    {#if split < view.items.length}
      <!-- Not an option: the arrow keys and the options' ids skip it. -->
      <div class="section" role="group" aria-labelledby={suggestedId}>
        <div class="section-header in-list" id={suggestedId} aria-hidden="true">{t("autofill_suggested")}</div>
        <div class="group" role="presentation">
          {#each view.items.slice(split) as item, offset (item.handle)}
            {@render row(item, split + offset)}
          {/each}
        </div>
      </div>
    {/if}
  {/if}
</div>

{#if view.kind === "items" && view.note !== null}
  <p class="note body-small">{view.note}</p>
{/if}

<style>
  .banner {
    margin: 8px;
  }

  /* Quick search's placeholder: blocks 6px apart, inset as the rows. */
  .skeletons {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 8px;
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
    padding: 8px;
  }

  /* The "Suggested" label and its .group: the label sits on the list's surface. */
  .section {
    display: flex;
    flex-direction: column;
    gap: 2px;
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
