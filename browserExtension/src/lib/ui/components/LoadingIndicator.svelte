<!--
  The Material 3 Expressive loading indicator: a shape that morphs every
  650ms while it turns, in a primary-container circle when `contained`. Still
  under reduced motion.
-->
<script lang="ts">
  interface Props {
    /** The accessible name, such as "Loading". */
    label: string;
    /** The size of the container in pixels; the shape is 38/48 of it. */
    size?: number;
    contained?: boolean;
  }

  let { label, size = 48, contained = true }: Props = $props();
</script>

<span class={["indicator", { contained }]} style:--indicator-size="{size}px" role="progressbar" aria-label={label}>
  <span class="shape"></span>
</span>

<style>
  .indicator {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: var(--indicator-size);
    height: var(--indicator-size);
    border-radius: 50%;
    color: var(--primary);
  }

  .contained {
    background: var(--primary-container);
    color: var(--on-primary-container);
  }

  .shape {
    width: calc(var(--indicator-size) * 38 / 48);
    height: calc(var(--indicator-size) * 38 / 48);
    border-radius: 42% 58% 46% 54% / 50% 44% 56% 50%;
    background: currentColor;
    animation:
      indicator-morph 3.25s infinite,
      indicator-spin 4.7s linear infinite;
  }

  /*
   * Five shapes, 650ms each: a blob, a soft square, an egg, another blob and a
   * pill. Each morphs into the next in about 350ms on the fast spring, then holds.
   */
  @keyframes indicator-morph {
    0%,
    91% {
      border-radius: 42% 58% 46% 54% / 50% 44% 56% 50%;
      scale: 1;
      animation-timing-function: var(--spring-fast);
    }
    11%,
    20% {
      border-radius: 30%;
      scale: 0.86;
      animation-timing-function: var(--spring-fast);
    }
    31%,
    40% {
      border-radius: 50% 50% 42% 42% / 60% 60% 40% 40%;
      scale: 0.92;
      animation-timing-function: var(--spring-fast);
    }
    51%,
    60% {
      border-radius: 62% 38% 54% 46% / 38% 58% 42% 62%;
      scale: 0.96;
      animation-timing-function: var(--spring-fast);
    }
    71%,
    80% {
      border-radius: 50% / 34%;
      scale: 0.84;
      animation-timing-function: var(--spring-fast);
    }
    100% {
      border-radius: 42% 58% 46% 54% / 50% 44% 56% 50%;
      scale: 1;
    }
  }

  @keyframes indicator-spin {
    to {
      rotate: 360deg;
    }
  }
</style>
