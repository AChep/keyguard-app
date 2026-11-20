<!--
  The "Keyguard" wordmark, with "Key" in bold: keyguardSpan() of
  common/.../feature/keyguard/setup/SetupScreen.kt. The text size comes from the
  class; title-large by default.
-->
<script lang="ts">
  import { t } from "../../i18n";

  interface Props {
    class?: string;
  }

  let { class: className = "title-large" }: Props = $props();
</script>

<span class="wordmark {className}" role="img" aria-label={t("browser_extension_name")}><b>Key</b>guard</span>

<style>
  .wordmark {
    white-space: nowrap;
  }

  b {
    font-weight: 700;
  }
</style>
