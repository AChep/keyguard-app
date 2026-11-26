<!--
  The card beside the inline menu: the selected login as the popup's detail
  pane shows it (ItemDetail). A press on a field or a linked URI copies it,
  past the click gate: the background checks that the page shows the card,
  has the app release a secret, and writes the clipboard itself. This page
  never gets a secret. The menu and the card stay open after a copy; the
  card hides once the window loses focus, as to the app's prompt, and the
  copy still ends.
-->
<script lang="ts">
  import { t } from "../../src/lib/i18n";
  import type { DetailsSide, ItemView, MenuDetailsEvent, MenuDetailsRequest } from "../../src/lib/page/messages";
  import type { ClickGate } from "../../src/lib/ui/clickGate";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import ItemDetail from "../../src/lib/ui/components/ItemDetail.svelte";
  import Snackbar from "../../src/lib/ui/components/Snackbar.svelte";
  import { contentHeight, reportHeight, type EmbeddedPort } from "../../src/lib/ui/embedded";
  import type { ItemCopy } from "../../src/lib/ui/itemDetails";
  import { MenuFocus, onDetailsKeydown } from "../../src/lib/ui/menuKeyboard";
  import { errorText } from "../../src/lib/ui/statusText";

  interface Props {
    port: EmbeddedPort<MenuDetailsRequest, MenuDetailsEvent>;
    gate: ClickGate;
  }

  let { port, gate }: Props = $props();

  let view = $state.raw<{ seq: number; handle: string; item: ItemView } | null>(null);
  /** The copy in progress. */
  let busy = $state.raw<ItemCopy | null>(null);
  let error = $state<string | null>(null);
  let root = $state<HTMLElement | undefined>();
  let snackbar = $state<Snackbar | undefined>();
  const menuFocus = new MenuFocus();
  const rtl = document.documentElement.dir === "rtl";
  /** The side of the menu that the card shows on, as the content script told: the arrow away from it goes back. */
  let side: DetailsSide | null = null;
  /** The copy asked for last: what a `busy` event is about. */
  let requested: ItemCopy | null = null;

  $effect(() =>
    port.onEvent((event) => {
      switch (event.kind) {
        case "view":
          view = { seq: event.seq, handle: event.handle, item: event.item };
          error = null;
          // A click aimed at the login shown before doesn't act on this one.
          gate.restart();
          return;
        case "shown":
          side = event.side ?? side;
          gate.shown();
          return;
        case "hidden":
          gate.hidden();
          return;
        case "focus":
          if (root !== undefined) menuFocus.request(root);
          return;
        case "busy":
          busy = event.busy ? requested : null;
          return;
        case "copied":
          snackbar?.show(t("copied_value"));
          return;
        case "error":
          error = errorText(event.error);
          return;
      }
    }),
  );

  /** The height last sent, with its view; the card shows at the height of what it shows. */
  let sent = { seq: 0, height: 0 };

  function sendHeight(height: number) {
    if (view === null || (view.seq === sent.seq && height === sent.height)) return;
    sent = { seq: view.seq, height };
    port.send({ kind: "resize", height, seq: view.seq });
  }

  // Each view and error at once: the hidden frame may get no ResizeObserver callbacks.
  $effect(() => {
    void error;
    if (view !== null && root !== undefined) sendHeight(contentHeight(root));
  });

  // A field keeps focus after a copy, which disables them meanwhile. See MenuFocus.
  $effect(() => {
    if (root !== undefined && view !== null && busy === null) menuFocus.settle(root);
  });

  /** Acts on trusted clicks only, once the card was visible for a while. Enter and Space click too. */
  function onaction(copy: ItemCopy, event: MouseEvent) {
    if (view === null || busy !== null || !gate.allows(event)) return;
    requested = copy;
    error = null;
    port.send(
      typeof copy === "string"
        ? { kind: "copy", action: copy, handle: view.handle, seq: view.seq }
        : { kind: "copy_uri", handle: view.handle, index: copy.uri, seq: view.seq },
    );
  }

  function onKeydown(event: KeyboardEvent) {
    if (root === undefined) return;
    onDetailsKeydown(event, root, {
      back: () => port.send({ kind: "back" }),
      close: () => port.send({ kind: "close" }),
      rtl,
      side,
    });
  }

  // The frame got focus: only the background's request moves focus to a field, see MenuFocus.
  function onFocus(event: FocusEvent) {
    if (event.target === window && root !== undefined) menuFocus.settle(root);
  }

  // As in the menu: focus may have gone back into it. The background tells once it left both.
  function onBlur(event: FocusEvent) {
    if (event.target !== window) return;
    menuFocus.blur();
    port.send({ kind: "blurred" });
  }
</script>

<svelte:window onkeydown={onKeydown} onfocus={onFocus} onblur={onBlur} />

<div
  class="card"
  bind:this={root}
  {@attach (element) => reportHeight(element, sendHeight)}
  aria-busy={busy !== null}
  onfocusin={(event) => menuFocus.focusIn(event.target)}
>
  {#if view !== null}
    {#if error}
      <p class="banner" role="alert"><Icon name="error_outline" />{error}</p>
    {/if}
    <ItemDetail item={view.item} {busy} {onaction} />
  {/if}
</div>

<Snackbar bind:this={snackbar} />

<style>
  /*
   * On the surface, as an item's details. The frame is no taller than the
   * window: past it, the page scrolls down to the lower fields, never across.
   * The card's own height is its content's, which it reports in full.
   */
  :global(html) {
    overflow-x: hidden;
    overflow-y: auto;
  }

  :global(html),
  :global(body) {
    background: var(--surface);
  }

  .card {
    display: flex;
    flex-direction: column;
    padding: 12px;
  }

  .banner {
    margin-bottom: 12px;
  }

  /* The card's own frame: the snackbar fits its width, near its bottom edge. */
  :global(:root) {
    --snackbar-bottom: 8px;
  }
</style>
