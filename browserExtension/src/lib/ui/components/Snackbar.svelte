<!--
  A snackbar at the bottom of the page: `show(text)` shows a message for 2.5
  seconds, and a new one replaces it. Corners of 8px, not Material's 4px, to sit
  with the rounder Expressive shapes. It slides up and fades in, and fades out;
  under reduced motion it just shows and hides. `--snackbar-bottom` on an
  ancestor lifts it over a bottom bar.
-->
<script module lang="ts">
  /** How long a message shows. */
  export const SNACKBAR_DURATION_MS = 2500;
</script>

<script lang="ts">
  const EXIT_MS = 150;

  interface Props {
    class?: string;
  }

  let { class: className = "" }: Props = $props();

  let message = $state<string | null>(null);
  let leaving = $state(false);
  // Restarts the entry when a message replaces another, even the same text.
  // Counted outside the state: `show` reads no state, so an effect can call it.
  let count = 0;
  let shown = $state(0);
  let timer: ReturnType<typeof setTimeout> | undefined;

  export function show(text: string) {
    clearTimeout(timer);
    message = text;
    leaving = false;
    shown = ++count;
    timer = setTimeout(() => {
      leaving = true;
      timer = setTimeout(() => (message = null), EXIT_MS);
    }, SNACKBAR_DURATION_MS);
  }

  $effect(() => () => clearTimeout(timer));
</script>

<!-- The live region stays, so that each message inserted into it is announced. -->
<div class="region {className}" role="status" aria-live="polite">
  {#if message !== null}
    {#key shown}
      <div class={["snackbar", { leaving }]}>{message}</div>
    {/key}
  {/if}
</div>

<style>
  .region {
    position: fixed;
    inset: auto 16px var(--snackbar-bottom, 16px);
    z-index: 10;
    display: flex;
    justify-content: center;
    pointer-events: none;
  }

  .snackbar {
    max-width: 100%;
    padding: 14px 16px;
    border-radius: var(--shape-sm);
    background: var(--inverse-surface);
    box-shadow: var(--elevation-3);
    color: var(--inverse-on-surface);
    font: var(--type-body-medium);
    letter-spacing: var(--tracking-body-medium);
    overflow-wrap: anywhere;
    animation: snackbar-enter var(--duration-long) var(--ease-emphasized-decelerate) both;
  }

  .leaving {
    animation: snackbar-exit var(--duration-short) var(--ease-emphasized-accelerate) both;
  }

  @keyframes snackbar-enter {
    from {
      opacity: 0;
      translate: 0 16px;
    }
  }

  @keyframes snackbar-exit {
    to {
      opacity: 0;
    }
  }
</style>
