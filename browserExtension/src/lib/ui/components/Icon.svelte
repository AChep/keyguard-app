<!-- A Material icon of icons.ts, in the current color. Decorative: label its control instead. -->
<script lang="ts">
  import { ICONS, type IconName } from "../icons";

  interface Props {
    name: IconName;
    /** The size in pixels, unless a class of theme.css sizes `.icon` in its container. */
    size?: number;
    class?: string;
  }

  let { name, size = 24, class: className = "" }: Props = $props();
</script>

<svg class="icon {className}" width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
  <path d={ICONS[name]} />
</svg>

<style>
  svg {
    display: block;
    flex-shrink: 0;
  }
</style>
