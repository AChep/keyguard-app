<!--
  The avatar of a login, as the app's AvatarBuilder (PasswordFilterItem.kt) and
  VaultItemIcon2 (VaultListItem.kt): a rounded square with the site's icon in a
  circle, or a monogram when there is no icon or it fails to load. Decorative.
-->
<script lang="ts">
  import { initials, monogramAccent } from "../avatar";

  interface Props {
    /** The item's name: the monogram's initials and color. */
    name: string;
    /** The URL of the site's icon. */
    icon?: string | null;
    /** The size in pixels. */
    size?: number;
  }

  let { name, icon = null, size = 36 }: Props = $props();

  // The icon that failed to load, so that a new one gets its chance.
  let failed = $state<string | null>(null);
  const showIcon = $derived(icon !== null && icon !== "" && failed !== icon);
</script>

{#if showIcon}
  <span class="avatar site" style:--avatar-size="{size}px" aria-hidden="true">
    <img
      src={icon}
      alt=""
      referrerpolicy="no-referrer"
      decoding="async"
      draggable="false"
      onerror={() => (failed = icon)}
    />
  </span>
{:else}
  <span class="avatar monogram" style:--avatar-size="{size}px" style:--avatar-accent={monogramAccent(name)} aria-hidden="true">
    <span class="circle">{initials(name)}</span>
  </span>
{/if}

<style>
  .avatar {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: var(--avatar-size);
    height: var(--avatar-size);
    overflow: hidden;
    border-radius: calc(var(--avatar-size) / 3);
    user-select: none;
  }

  /* The app's 8dp tonal elevation: the surface tint at about 12%. */
  .site {
    background: color-mix(in srgb, var(--surface-tint) 12%, transparent);
  }

  img {
    display: block;
    width: calc(var(--avatar-size) * 10 / 12);
    height: calc(var(--avatar-size) * 10 / 12);
    border-radius: 50%;
    object-fit: cover;
  }

  /* The accent under the background at 25%. */
  .monogram {
    background: color-mix(in srgb, var(--avatar-accent) 75%, var(--background));
    color: var(--on-surface);
  }

  .circle {
    display: flex;
    align-items: center;
    justify-content: center;
    width: calc(var(--avatar-size) * 10 / 12);
    height: calc(var(--avatar-size) * 10 / 12);
    overflow: hidden;
    border-radius: 50%;
    background: rgb(255 255 255 / 0.1);
    font-family: var(--font-sans);
    font-size: calc(var(--avatar-size) * 0.39);
    font-weight: 700;
    line-height: 1;
    letter-spacing: 0;
    white-space: nowrap;
  }
</style>
