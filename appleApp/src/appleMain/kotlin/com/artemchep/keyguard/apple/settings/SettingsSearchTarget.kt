package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.feature.home.settings.SettingsDestination
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * Stable native row anchors; search only navigates and never invokes their actions.
 * Declaration order is the catalog order, so ties in search results keep it.
 */
enum class SettingsSearchTarget(
    internal val categoryId: String,
    internal val title: StringResource,
    internal val section: StringResource? = null,
    internal val description: StringResource? = null,
    internal val keywords: List<StringResource> = emptyList(),
    internal val availability: Availability = Availability.ALL,
) {
    LAUNCH_AT_LOGIN(
        GENERAL_CATEGORY_ID,
        Res.string.pref_item_launch_at_login_title,
        section = Res.string.settings_startup_header_title,
        keywords = listOf(Res.string.settingssearch_startup_keywords),
    ),
    MENU_BAR(
        GENERAL_CATEGORY_ID,
        Res.string.pref_item_menu_bar_only_title,
        section = Res.string.settings_menu_bar_header_title,
        description = Res.string.pref_item_menu_bar_only_text,
    ),
    BIOMETRIC(
        SettingsDestination.SECURITY.id,
        Res.string.unlock_biometric_title,
        section = Res.string.home_vault_label,
        description = Res.string.pref_item_biometric_unlock_description,
        keywords = listOf(Res.string.settingssearch_biometric_keywords),
        availability = Availability.BIOMETRIC,
    ),
    BIOMETRIC_TIMEOUT(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_biometric_unlock_timeout_title,
        section = Res.string.home_vault_label,
        description = Res.string.pref_item_biometric_unlock_description,
        keywords = listOf(Res.string.settingssearch_lock_keywords, Res.string.unlock_biometric_title),
        availability = Availability.BIOMETRIC,
    ),
    FIDO2(
        SettingsDestination.SECURITY.id,
        Res.string.fido2_unlock_title,
        section = Res.string.home_vault_label,
        description = Res.string.fido2_unlock_description,
        availability = Availability.FIDO2,
    ),
    YUBIKEY(
        SettingsDestination.SECURITY.id,
        Res.string.unlock_yubikey_title,
        section = Res.string.home_vault_label,
        description = Res.string.pref_item_yubikey_unlock_note,
        availability = Availability.YUBIKEY,
    ),
    PERSIST(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_vault_lock_timeout_never_title,
        section = Res.string.home_vault_label,
        description = Res.string.pref_item_persist_vault_key_note,
    ),
    LOCK_TIMEOUT(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_vault_lock_timeout_title,
        section = Res.string.home_vault_label,
        keywords = listOf(Res.string.settingssearch_lock_keywords),
    ),
    LOCK_REBOOT(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_lock_vault_after_reboot_text,
        section = Res.string.home_vault_label,
    ),
    LOCK(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_lock_vault_title,
        section = Res.string.home_vault_label,
    ),
    CLIPBOARD(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_clipboard_auto_clear_title,
        section = Res.string.settings_clipboard_header_title,
        keywords = listOf(Res.string.settingssearch_clipboard_keywords),
    ),
    CONCEAL(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_conceal_fields_title,
        section = Res.string.settings_privacy_header_title,
    ),
    CHANGE_PASSWORD(
        SettingsDestination.SECURITY.id,
        Res.string.pref_item_change_master_password_action,
        section = Res.string.password,
    ),
    LOCALE(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_locale_title,
        keywords = listOf(Res.string.settingssearch_language_keywords),
    ),
    THEME(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_color_scheme_title,
        section = Res.string.pref_item_color_scheme_selection_title,
        keywords = listOf(Res.string.settingssearch_theme_keywords),
    ),
    ACCENT(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_color_accent_title,
        section = Res.string.pref_item_color_scheme_selection_title,
    ),
    NAVIGATION(
        SettingsDestination.DISPLAY.id,
        Res.string.settings_navigation_items_header_title,
        section = Res.string.settings_navigation_header_title,
        keywords = listOf(Res.string.settingssearch_navigation_keywords),
    ),
    NAVIGATION_LABELS(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_nav_label_short_title,
        section = Res.string.settings_navigation_header_title,
    ),
    MARKDOWN(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_render_markdown_title,
        section = Res.string.settings_navigation_header_title,
    ),
    WEBSITE_ICONS(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_load_website_icons_title,
        section = Res.string.settings_icons_header_title,
    ),
    GRAVATAR(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_load_gravatar_icons_title,
        section = Res.string.settings_icons_header_title,
    ),
    EXTERNAL_BROWSER(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_open_links_in_external_browser_title,
        section = Res.string.settings_experience_header_title,
        availability = Availability.IOS,
    ),
    KEEP_AWAKE(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_keep_screen_on_title,
        section = Res.string.settings_experience_header_title,
        availability = Availability.IOS,
    ),
    MINIMIZE(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_minimize_on_copy_title,
        section = Res.string.settings_experience_header_title,
        availability = Availability.MACOS,
    ),
    CLOSE_TO_TRAY(
        SettingsDestination.DISPLAY.id,
        Res.string.pref_item_close_to_menu_bar_title,
        section = Res.string.settings_experience_header_title,
        availability = Availability.MACOS,
    ),
    AUTOFILL_STATUS(
        SettingsDestination.AUTOFILL.id,
        Res.string.status,
        section = Res.string.pref_item_autofill_service_title,
    ),
    AUTOFILL_INDEX_STATUS(
        SettingsDestination.AUTOFILL.id,
        Res.string.settingssearch_autofill_index_status_title,
    ),
    AUTOFILL_ENABLE(
        SettingsDestination.AUTOFILL.id,
        Res.string.pref_item_autofill_service_enable_action,
        keywords = listOf(Res.string.settingssearch_autofill_keywords),
    ),
    AUTOFILL(
        SettingsDestination.AUTOFILL.id,
        Res.string.pref_item_autofill_service_open_settings_action,
        keywords = listOf(Res.string.settingssearch_autofill_keywords),
    ),
    AUTOFILL_REFRESH(
        SettingsDestination.AUTOFILL.id,
        Res.string.pref_item_autofill_service_refresh_action,
    ),
    AUTOFILL_DEFAULT_MATCH_DETECTION(
        SettingsDestination.AUTOFILL.id,
        Res.string.pref_item_autofill_default_match_detection_title,
        keywords = listOf(Res.string.settingssearch_autofill_keywords, Res.string.uri, Res.string.url),
    ),
    BACKUP_STATUS(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_panel_status_label,
    ),
    BACKUP_LAST_SUCCESS(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_panel_last_sync_title,
    ),
    BACKUP_LOCATION(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_location_title,
        keywords = listOf(Res.string.settingssearch_backup_location_keywords),
    ),
    BACKUP_PASSWORD(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_password_title,
        keywords = listOf(Res.string.settingssearch_backup_password_keywords),
    ),
    BACKUP_SETUP(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_wizard_title,
        description = Res.string.pref_item_automatic_backups_setup_intro,
        keywords = listOf(Res.string.settingssearch_backup_keywords),
    ),
    BACKUP_RUN(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_run_now_title,
    ),
    BACKUP_CONFIG(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_change_configuration_action,
        keywords = listOf(Res.string.settingssearch_backup_keywords),
    ),
    BACKUP_ATTACHMENTS(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_include_attachments_title,
    ),
    BACKUP_RETENTION(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_retention_label,
    ),
    BACKUP_DISABLE(
        SettingsDestination.AUTOMATIC_BACKUPS.id,
        Res.string.pref_item_automatic_backups_disable_action,
    ),
    SSH_STATUS(
        SettingsDestination.DEVELOPER.id,
        Res.string.status,
        section = Res.string.ssh_agent,
    ),
    SSH_SOCKET(
        SettingsDestination.DEVELOPER.id,
        Res.string.settingssearch_ssh_socket_title,
        section = Res.string.ssh_agent,
        keywords = listOf(Res.string.settingssearch_ssh_socket_keywords),
    ),
    SSH_ENABLE(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_ssh_agent_enable_title,
        section = Res.string.ssh_agent,
    ),
    SSH_SETUP(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_ssh_agent_setup_title,
        section = Res.string.ssh_agent,
    ),
    SSH_APPROVAL(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_ssh_agent_approval_window_title,
        section = Res.string.ssh_agent,
    ),
    SSH_NAMES(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_ssh_agent_display_key_names_title,
        section = Res.string.ssh_agent,
        description = Res.string.pref_item_ssh_agent_display_key_names_note,
    ),
    SSH_FILTERS(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_ssh_agent_filters_title,
        section = Res.string.ssh_agent,
    ),
    SSH_HISTORY(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_ssh_agent_history_title,
        section = Res.string.ssh_agent,
    ),
    GPG_STATUS(
        SettingsDestination.DEVELOPER.id,
        Res.string.status,
        section = Res.string.gpg_agent,
    ),
    GPG_ENABLE(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_gpg_agent_title,
        section = Res.string.gpg_agent,
    ),
    GPG_SETUP(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_gpg_agent_setup_title,
        section = Res.string.gpg_agent,
    ),
    GPG_APPROVAL(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_gpg_agent_approval_window_title,
        section = Res.string.gpg_agent,
    ),
    GPG_SCOPE(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_agent_approval_scope_title,
        section = Res.string.gpg_agent,
    ),
    GPG_NAMES(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_gpg_agent_display_key_names_title,
        section = Res.string.gpg_agent,
        description = Res.string.pref_item_gpg_agent_display_key_names_note,
    ),
    GPG_FILTERS(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_gpg_agent_filters_title,
        section = Res.string.gpg_agent,
    ),
    GPG_HISTORY(
        SettingsDestination.DEVELOPER.id,
        Res.string.pref_item_gpg_agent_history_title,
        section = Res.string.gpg_agent,
    ),
    PWNED_PASSWORDS(
        SettingsDestination.WATCHTOWER.id,
        Res.string.pref_item_check_pwned_passwords_title,
        section = Res.string.pref_item_hibp_header_title,
        keywords = listOf(Res.string.settingssearch_breach_keywords),
    ),
    PWNED_SERVICES(
        SettingsDestination.WATCHTOWER.id,
        Res.string.pref_item_check_pwned_services_title,
        section = Res.string.pref_item_hibp_header_title,
        keywords = listOf(Res.string.settingssearch_breach_keywords),
    ),
    HIBP_TOKEN(
        SettingsDestination.WATCHTOWER.id,
        Res.string.api_key,
        section = Res.string.pref_item_hibp_header_title,
    ),
    TWO_FA(
        SettingsDestination.WATCHTOWER.id,
        Res.string.pref_item_check_inactive_2fa_title,
        section = Res.string.tfa_directory_title,
    ),
    PASSKEYS(
        SettingsDestination.WATCHTOWER.id,
        Res.string.pref_item_check_inactive_passkeys_title,
        section = Res.string.passkeys_directory_title,
    ),
    MEMBERSHIP_STATUS(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.settingssearch_membership_status_title,
    ),
    RESTORE_PURCHASES(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.premium_purchase_restore_action,
        availability = Availability.STORE,
    ),
    MANAGE_PURCHASES(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.premium_purchase_manage_subscription_action,
        availability = Availability.STORE,
    ),
    PURCHASE_TERMS(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.premium_purchase_terms_title,
        availability = Availability.STORE,
    ),
    LICENSE_ENTRY(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.pref_item_license_key_field_label,
        keywords = listOf(Res.string.settingssearch_license_keywords),
    ),
    LICENSE_SYNC(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.pref_item_license_sync_title,
        availability = Availability.STORE,
    ),
    LICENSE_PURCHASE_TOKEN(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.pref_item_license_key_purchase_token_title,
    ),
    LICENSE_LINKED_TOKEN(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.pref_item_license_key_linked_token_title,
    ),
    LICENSE_LINK(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.pref_item_license_key_link_action,
    ),
    LICENSE_REFRESH(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.pref_item_license_key_refresh_action,
    ),
    LICENSE_REMOVE(
        SettingsDestination.SUBSCRIPTION.id,
        Res.string.pref_item_license_key_remove_action,
    ),
    DEBUG_PREMIUM(
        SettingsDestination.DEBUG.id,
        Res.string.pref_item_debug_premium_title,
        description = Res.string.pref_item_debug_premium_text,
    ),
    APP_VERSION(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_app_version_title,
    ),
    BUILD_DATE(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_app_build_date_title,
        availability = Availability.BUILD_DATE,
    ),
    BUILD_REF(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_app_build_ref_title,
        keywords = listOf(Res.string.settingssearch_build_ref_keywords),
        availability = Availability.BUILD_REF,
    ),
    CHANGELOG(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_app_changelog_title,
        availability = Availability.CHANGELOG,
    ),
    FEEDBACK(
        SettingsDestination.ABOUT.id,
        Res.string.contactus_header_title,
    ),
    TEAM(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_app_team_title,
    ),
    COMMUNITY(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_reddit_community_title,
    ),
    SOURCE(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_github_title,
    ),
    LICENSES(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_open_source_licenses_title,
    ),
    TRANSLATIONS(
        SettingsDestination.ABOUT.id,
        Res.string.settings_localization_header_title,
    ),
    DATA_SAFETY(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_data_safety_title,
    ),
    PRIVACY_POLICY(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_privacy_policy_title,
    ),
    LOGS(
        SettingsDestination.ABOUT.id,
        Res.string.logs_header_title,
    ),
    URL_RULES(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_url_override_title,
    ),
    WEBDAV_TRANSACTIONS(
        SettingsDestination.ABOUT.id,
        Res.string.pref_item_webdav_transactions_title,
    );

    internal enum class Availability {
        ALL, MACOS, IOS, BIOMETRIC, FIDO2, YUBIKEY, STORE, BUILD_DATE, BUILD_REF, CHANGELOG;

        fun supported(capabilities: SettingsSearchCapabilities): Boolean = when (this) {
            ALL -> true
            MACOS -> capabilities.macOS
            IOS -> !capabilities.macOS
            BIOMETRIC -> capabilities.biometric
            FIDO2 -> capabilities.fido2
            YUBIKEY -> capabilities.yubiKey
            STORE -> capabilities.store
            BUILD_DATE -> capabilities.appInformation.buildDate.isNotEmpty()
            BUILD_REF -> capabilities.appInformation.buildRefUrl != null
            CHANGELOG -> capabilities.appInformation.changelogUrl != null
        }
    }
}
