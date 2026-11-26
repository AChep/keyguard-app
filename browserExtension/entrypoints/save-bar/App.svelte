<script lang="ts">
  import { t } from "../../src/lib/i18n";
  import type { SaveBarEvent, SaveBarRequest } from "../../src/lib/page/messages";
  import type { ClickGate } from "../../src/lib/ui/clickGate";
  import Avatar from "../../src/lib/ui/components/Avatar.svelte";
  import Icon from "../../src/lib/ui/components/Icon.svelte";
  import Logo from "../../src/lib/ui/components/Logo.svelte";
  import { contentHeight, reportHeight, type EmbeddedPort } from "../../src/lib/ui/embedded";
  import { errorText } from "../../src/lib/ui/statusText";

  interface Props {
    port: EmbeddedPort<SaveBarRequest, SaveBarEvent>;
    gate: ClickGate;
  }

  let { port, gate }: Props = $props();

  /** The offer the bar shows: its actions are for that one. */
  let login = $state.raw<{ offer: number; host: string; username: string | null; icon: string | null } | null>(null);
  let busy = $state(false);
  let error = $state<string | null>(null);
  let root = $state<HTMLElement | undefined>();

  /** The content script sizes the frame to the content; the background drops what comes before the page binds. */
  function sendHeight(height: number) {
    port.send({ kind: "resize", height });
  }

  $effect(() =>
    port.onEvent((event) => {
      switch (event.kind) {
        case "bound":
          if (root !== undefined) sendHeight(contentHeight(root));
          return;
        case "view":
          // Another offer: a click aimed at what showed before doesn't take it.
          if (login?.offer !== event.offer) gate.restart();
          login = { offer: event.offer, host: event.host, username: event.username, icon: event.icon };
          error = null;
          return;
        case "busy":
          busy = event.busy;
          return;
        case "error":
          error = errorText(event.error);
          return;
      }
    }),
  );

  type Action = "save" | "dismiss" | "never";

  /** An action on the offer shown, on a trusted click once the bar was visible for a while; none before it shows one. */
  function act(event: MouseEvent, kind: Action) {
    if (busy || login === null || !gate.allows(event)) return;
    error = null;
    port.send({ kind, offer: login.offer });
  }

  // Escape is Not now, which is disabled while Save or Never runs.
  function onKeydown(event: KeyboardEvent) {
    if (event.key === "Escape" && !busy && login !== null) port.send({ kind: "dismiss", offer: login.offer });
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div
  class="bar"
  bind:this={root}
  {@attach (element) => reportHeight(element, sendHeight)}
  role="dialog"
  aria-labelledby="title"
  aria-busy={busy}
>
  {#if busy}
    <!-- Over the frame's top edge: the content doesn't move. -->
    <div class="linear-progress busy" role="progressbar" aria-label={t("loading")}></div>
  {/if}
  <header>
    <Logo size={40} />
    <h1 id="title" class="title-medium">{t("browser_extension_save_title")}</h1>
    <button
      class="icon-btn close"
      aria-label={t("close")}
      title={t("close")}
      disabled={busy || login === null}
      onclick={(event) => act(event, "dismiss")}
    >
      <Icon name="close" />
    </button>
  </header>
  <!-- Empty until the background tells the login: the bar keeps its size. -->
  <div class="login">
    {#if login}
      <Avatar name={login.host} icon={login.icon} size={40} />
      <div class="login-text">
        <span class="title-small">{login.username || login.host}</span>
        {#if login.username}
          <span class="host body-small">{login.host}</span>
        {/if}
      </div>
    {/if}
  </div>
  {#if error}
    <p class="banner" role="alert"><Icon name="error_outline" />{error}</p>
  {/if}
  <div class="actions">
    <button class="btn btn-text" disabled={busy || login === null} onclick={(event) => act(event, "never")}>
      {t("browser_extension_save_never")}
    </button>
    <button class="btn btn-text" disabled={busy || login === null} onclick={(event) => act(event, "dismiss")}>
      {t("browser_extension_save_not_now")}
    </button>
    <button class="btn btn-filled" disabled={busy || login === null} onclick={(event) => act(event, "save")}>
      {t("save")}
    </button>
  </div>
</div>

<style>
  /* The frame's corners: Material 3's extra large, as the app's dialogs. */
  :global(body) {
    --frame-radius: var(--shape-xl);
  }

  .bar {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 16px 20px;
    animation: enter var(--duration-long) var(--ease-emphasized-decelerate);
  }

  /* In from the right, inside the frame: the frame itself stays, for the click gate. */
  @keyframes enter {
    from {
      opacity: 0;
      translate: 24px 0;
    }
  }

  .busy {
    position: fixed;
    top: 0;
    right: 0;
    left: 0;
    z-index: 2;
    height: 3px;
    border-radius: 0;
  }

  /* A title of one line centers on the logo; a longer one runs down from its top. */
  header {
    display: flex;
    align-items: flex-start;
    gap: 16px;
  }

  h1 {
    display: flex;
    flex: 1;
    align-items: center;
    min-width: 0;
    min-height: 40px;
    margin: 0;
    overflow-wrap: anywhere;
  }

  /* The icon lines up with the content's edge, its touch target past it. */
  .close {
    margin-inline-end: -8px;
  }

  .login {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 64px;
    padding: 12px;
    border-radius: var(--shape-lg);
    background: var(--surface-container-high);
  }

  .login-text {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }

  .login-text span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .host {
    color: color-mix(in srgb, var(--on-surface) var(--text-medium-alpha), transparent);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 4px;
  }

  /* Long labels wrap in their buttons, which then grow. */
  .actions .btn {
    flex-shrink: 1;
    min-width: 0;
    max-width: 100%;
    height: auto;
    min-height: var(--btn-height);
    padding-block: 8px;
    white-space: normal;
    text-align: center;
  }
</style>
