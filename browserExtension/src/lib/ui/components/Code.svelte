<!--
  A code in groups of three, split by small dots, as the app shows one-time
  passwords (VaultViewTotpItem.kt). Monospace; the text size comes from the
  class, such as `headline-small` or `body-large`. Masked, each symbol shows
  as a bullet.
-->
<script lang="ts">
  const GROUP_SIZE = 3;

  interface Props {
    code: string;
    masked?: boolean;
    class?: string;
  }

  let { code, masked = false, class: className = "" }: Props = $props();

  // Groups of UTF-16 units, as Kotlin's `windowed(3, 3, partialWindows = true)`.
  const groups = $derived.by(() => {
    const result: string[] = [];
    for (let i = 0; i < code.length; i += GROUP_SIZE) {
      const group = code.slice(i, i + GROUP_SIZE);
      result.push(masked ? "•".repeat(Array.from(group).length) : group);
    }
    return result;
  });
</script>

<span class="code {className}">
  {#each groups as group, index (index)}
    {#if index > 0}
      <span class="dot" aria-hidden="true"></span>
    {/if}
    <span>{group}</span>
  {/each}
</span>

<style>
  /* Left to right in every language: the groups read in the order the app shows them. */
  .code {
    display: inline-flex;
    align-items: center;
    direction: ltr;
    unicode-bidi: isolate;
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    letter-spacing: 0;
    white-space: nowrap;
  }

  /* The app's 3px dot at 38%, 5px on each side. */
  .dot {
    flex-shrink: 0;
    width: 3px;
    height: 3px;
    margin: 0 5px;
    border-radius: 50%;
    background: color-mix(in srgb, currentColor 38%, transparent);
  }
</style>
