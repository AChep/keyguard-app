<!-- The brand tile: the two-tone lock on the logo's rounded gradient tile (../logo.ts). Decorative. -->
<script lang="ts">
  import { LOGO_GRADIENT_CSS, LOGO_PATH_COLORS, LOGO_PATHS, LOGO_TILE_RADIUS, LOGO_VIEW_BOX } from "../logo";

  interface Props {
    /** The size in pixels. */
    size?: number;
    class?: string;
  }

  let { size = 40, class: className = "" }: Props = $props();
</script>

<!-- The tile is a CSS background: the ids of SVG gradients would clash between logos on a page. -->
<span
  class="logo {className}"
  style:width="{size}px"
  style:height="{size}px"
  style:border-radius="{size * LOGO_TILE_RADIUS}px"
  style:background-image={LOGO_GRADIENT_CSS}
  aria-hidden="true"
>
  <svg viewBox={LOGO_VIEW_BOX} focusable="false">
    {#each LOGO_PATHS as d, index (d)}
      <path fill-rule="evenodd" fill={LOGO_PATH_COLORS[index]} {d} />
    {/each}
  </svg>
</span>

<style>
  .logo {
    display: block;
    flex-shrink: 0;
  }

  svg {
    display: block;
    width: 100%;
    height: 100%;
  }
</style>
