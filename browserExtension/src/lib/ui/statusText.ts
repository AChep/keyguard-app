// The localized texts of the extension's statuses and call errors, shared by
// the popup and the toolbar button's title.
import type { CallError, ExtensionStatus } from "../background/api";
import { t, type I18nKey } from "../i18n";

const TITLES: Record<ExtensionStatus["kind"], I18nKey> = {
  starting: "loading",
  connecting: "loading",
  connector_missing: "browser_extension_connector_missing_title",
  app_unavailable: "browser_extension_app_unavailable_title",
  integration_disabled: "browser_extension_integration_disabled_title",
  update_required: "browser_extension_update_required_title",
  extension_update_required: "browser_extension_extension_update_required_title",
  app_update_required: "browser_extension_app_update_required_title",
  unverified_host: "browser_extension_unverified_host_title",
  too_many_sessions: "browser_extension_too_many_sessions_title",
  protocol_error: "browser_extension_error_disconnected",
  not_paired: "browser_extension_not_paired_title",
  pinned_key_mismatch: "browser_extension_key_mismatch_title",
  locked: "autofill_unlock_keyguard",
  unlocked: "browser_extension_name",
};

export function statusTitle(status: ExtensionStatus): string {
  return t(TITLES[status.kind]);
}

/** Statuses that need the user, marked on the toolbar button. */
export function needsAttention(status: ExtensionStatus): boolean {
  switch (status.kind) {
    case "starting":
    case "connecting":
    case "app_unavailable":
    case "locked":
    case "unlocked":
      return false;
    default:
      return true;
  }
}

const ERRORS: Partial<Record<CallError, I18nKey>> = {
  user_denied: "browser_extension_error_user_denied",
  approval_timeout: "browser_extension_error_approval_timeout",
  vault_locked: "browser_extension_error_vault_locked",
  not_supported: "browser_extension_error_not_supported",
  not_allowed: "browser_extension_error_not_allowed",
  busy: "browser_extension_error_busy",
  disconnected: "browser_extension_error_disconnected",
  unsupported_page: "browser_extension_page_unsupported_text",
  no_form: "browser_extension_error_no_form",
  obscured: "browser_extension_error_obscured",
  origin: "browser_extension_error_page_changed",
};

export function errorText(error: CallError): string {
  return t(ERRORS[error] ?? "error_failed_unknown");
}
