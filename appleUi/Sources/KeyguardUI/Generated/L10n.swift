import Foundation

public enum L10n {
    /// access_count
    public static var accessCount: String {
        String(localized: "access_count", bundle: AppLocalization.shared.bundle)
    }
    /// account
    public static var account: String {
        String(localized: "account", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_change_color_title
    public static var accountActionChangeColorTitle: String {
        String(localized: "account_action_change_color_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_change_master_password_hint_title
    public static var accountActionChangeMasterPasswordHintTitle: String {
        String(localized: "account_action_change_master_password_hint_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_change_name_title
    public static var accountActionChangeNameTitle: String {
        String(localized: "account_action_change_name_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_email_verify_instructions_title
    public static var accountActionEmailVerifyInstructionsTitle: String {
        String(localized: "account_action_email_verify_instructions_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_export_individual_vault_title
    public static var accountActionExportIndividualVaultTitle: String {
        String(localized: "account_action_export_individual_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_export_vault_title
    public static var accountActionExportVaultTitle: String {
        String(localized: "account_action_export_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_hide_success_off_title
    public static var accountActionHideSuccessOffTitle: String {
        String(localized: "account_action_hide_success_off_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_hide_success_on_title
    public static var accountActionHideSuccessOnTitle: String {
        String(localized: "account_action_hide_success_on_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_hide_text
    public static var accountActionHideText: String {
        String(localized: "account_action_hide_text", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_hide_title
    public static var accountActionHideTitle: String {
        String(localized: "account_action_hide_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_import_credentials_title
    public static var accountActionImportCredentialsTitle: String {
        String(localized: "account_action_import_credentials_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_items_title
    public static var accountActionItemsTitle: String {
        String(localized: "account_action_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_log_out_title
    public static var accountActionLogOutTitle: String {
        String(localized: "account_action_log_out_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_premium_purchase_instructions_title
    public static var accountActionPremiumPurchaseInstructionsTitle: String {
        String(localized: "account_action_premium_purchase_instructions_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_show_title
    public static var accountActionShowTitle: String {
        String(localized: "account_action_show_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_sign_in_title
    public static var accountActionSignInTitle: String {
        String(localized: "account_action_sign_in_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_sign_out_title
    public static var accountActionSignOutTitle: String {
        String(localized: "account_action_sign_out_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_tfa_active_status
    public static var accountActionTfaActiveStatus: String {
        String(localized: "account_action_tfa_active_status", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_tfa_text
    public static var accountActionTfaText: String {
        String(localized: "account_action_tfa_text", bundle: AppLocalization.shared.bundle)
    }
    /// account_action_tfa_title
    public static var accountActionTfaTitle: String {
        String(localized: "account_action_tfa_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_actions_title
    public static var accountActionsTitle: String {
        String(localized: "account_actions_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_last_synced_at
    public static func accountLastSyncedAt(_ a1: String) -> String {
        String(format: String(localized: "account_last_synced_at", bundle: AppLocalization.shared.bundle), a1)
    }
    /// account_log_out_confirmation_text
    public static var accountLogOutConfirmationText: String {
        String(localized: "account_log_out_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// account_log_out_confirmation_title
    public static var accountLogOutConfirmationTitle: String {
        String(localized: "account_log_out_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_main_add_account_title
    public static var accountMainAddAccountTitle: String {
        String(localized: "account_main_add_account_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_main_header_title
    public static var accountMainHeaderTitle: String {
        String(localized: "account_main_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// account_name
    public static var accountName: String {
        String(localized: "account_name", bundle: AppLocalization.shared.bundle)
    }
    /// account_none
    public static var accountNone: String {
        String(localized: "account_none", bundle: AppLocalization.shared.bundle)
    }
    /// account_not_found_title
    public static var accountNotFoundTitle: String {
        String(localized: "account_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// accounts
    public static var accounts: String {
        String(localized: "accounts", bundle: AppLocalization.shared.bundle)
    }
    /// accounts_empty_label
    public static var accountsEmptyLabel: String {
        String(localized: "accounts_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// actions
    public static var actions: String {
        String(localized: "actions", bundle: AppLocalization.shared.bundle)
    }
    /// add
    public static var add: String {
        String(localized: "add", bundle: AppLocalization.shared.bundle)
    }
    /// add_integration
    public static var addIntegration: String {
        String(localized: "add_integration", bundle: AppLocalization.shared.bundle)
    }
    /// add_to_home_screen
    public static var addToHomeScreen: String {
        String(localized: "add_to_home_screen", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_email_note
    public static func addaccount2faEmailNote(_ a1: String) -> String {
        String(format: String(localized: "addaccount2fa_email_note", bundle: AppLocalization.shared.bundle), a1)
    }
    /// addaccount2fa_header_title
    public static var addaccount2faHeaderTitle: String {
        String(localized: "addaccount2fa_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_method_label
    public static var addaccount2faMethodLabel: String {
        String(localized: "addaccount2fa_method_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_method_other_title
    public static var addaccount2faMethodOtherTitle: String {
        String(localized: "addaccount2fa_method_other_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_method_unsupported_macos_text
    public static func addaccount2faMethodUnsupportedMacosText(_ a1: String) -> String {
        String(format: String(localized: "addaccount2fa_method_unsupported_macos_text", bundle: AppLocalization.shared.bundle), a1)
    }
    /// addaccount2fa_method_web_vault_note
    public static var addaccount2faMethodWebVaultNote: String {
        String(localized: "addaccount2fa_method_web_vault_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_otp_note
    public static var addaccount2faOtpNote: String {
        String(localized: "addaccount2fa_otp_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_unsupported_note
    public static var addaccount2faUnsupportedNote: String {
        String(localized: "addaccount2fa_unsupported_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_yubikey_manual_text
    public static var addaccount2faYubikeyManualText: String {
        String(localized: "addaccount2fa_yubikey_manual_text", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount2fa_yubikey_otp_note
    public static var addaccount2faYubikeyOtpNote: String {
        String(localized: "addaccount2fa_yubikey_otp_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_base_env_note
    public static var addaccountBaseEnvNote: String {
        String(localized: "addaccount_base_env_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_base_env_server_url_label
    public static var addaccountBaseEnvServerUrlLabel: String {
        String(localized: "addaccount_base_env_server_url_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_captcha_need_client_secret_label
    public static var addaccountCaptchaNeedClientSecretLabel: String {
        String(localized: "addaccount_captcha_need_client_secret_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_captcha_need_client_secret_note
    public static var addaccountCaptchaNeedClientSecretNote: String {
        String(localized: "addaccount_captcha_need_client_secret_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_create_an_account_title
    public static var addaccountCreateAnAccountTitle: String {
        String(localized: "addaccount_create_an_account_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_custom_env_api_url_label
    public static var addaccountCustomEnvApiUrlLabel: String {
        String(localized: "addaccount_custom_env_api_url_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_custom_env_icons_url_label
    public static var addaccountCustomEnvIconsUrlLabel: String {
        String(localized: "addaccount_custom_env_icons_url_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_custom_env_identity_url_label
    public static var addaccountCustomEnvIdentityUrlLabel: String {
        String(localized: "addaccount_custom_env_identity_url_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_custom_env_note
    public static var addaccountCustomEnvNote: String {
        String(localized: "addaccount_custom_env_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_custom_env_notifications_url_label
    public static var addaccountCustomEnvNotificationsUrlLabel: String {
        String(localized: "addaccount_custom_env_notifications_url_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_custom_env_section
    public static var addaccountCustomEnvSection: String {
        String(localized: "addaccount_custom_env_section", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_custom_env_web_vault_url_label
    public static var addaccountCustomEnvWebVaultUrlLabel: String {
        String(localized: "addaccount_custom_env_web_vault_url_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_description_short_bitwarden_text
    public static var addaccountDescriptionShortBitwardenText: String {
        String(localized: "addaccount_description_short_bitwarden_text", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_description_short_keepass_text
    public static var addaccountDescriptionShortKeepassText: String {
        String(localized: "addaccount_description_short_keepass_text", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_disclaimer_bitwarden_label
    public static var addaccountDisclaimerBitwardenLabel: String {
        String(localized: "addaccount_disclaimer_bitwarden_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_header_title
    public static var addaccountHeaderTitle: String {
        String(localized: "addaccount_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_http_header_add_more_button
    public static var addaccountHttpHeaderAddMoreButton: String {
        String(localized: "addaccount_http_header_add_more_button", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_http_header_key_label
    public static var addaccountHttpHeaderKeyLabel: String {
        String(localized: "addaccount_http_header_key_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_http_header_note
    public static var addaccountHttpHeaderNote: String {
        String(localized: "addaccount_http_header_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_http_header_section
    public static var addaccountHttpHeaderSection: String {
        String(localized: "addaccount_http_header_section", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_http_header_type
    public static var addaccountHttpHeaderType: String {
        String(localized: "addaccount_http_header_type", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_http_header_value_label
    public static var addaccountHttpHeaderValueLabel: String {
        String(localized: "addaccount_http_header_value_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_login_captcha_client_secret_note
    public static var addaccountLoginCaptchaClientSecretNote: String {
        String(localized: "addaccount_login_captcha_client_secret_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_login_intro_text
    public static var addaccountLoginIntroText: String {
        String(localized: "addaccount_login_intro_text", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_header_title
    public static var addaccountMethodHeaderTitle: String {
        String(localized: "addaccount_method_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_keepass_wear_note
    public static var addaccountMethodKeepassWearNote: String {
        String(localized: "addaccount_method_keepass_wear_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_manual_text
    public static var addaccountMethodManualText: String {
        String(localized: "addaccount_method_manual_text", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_manual_title
    public static var addaccountMethodManualTitle: String {
        String(localized: "addaccount_method_manual_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_phone_failed
    public static var addaccountMethodPhoneFailed: String {
        String(localized: "addaccount_method_phone_failed", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_phone_importing
    public static var addaccountMethodPhoneImporting: String {
        String(localized: "addaccount_method_phone_importing", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_phone_pick
    public static var addaccountMethodPhonePick: String {
        String(localized: "addaccount_method_phone_pick", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_phone_text
    public static var addaccountMethodPhoneText: String {
        String(localized: "addaccount_method_phone_text", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_phone_title
    public static var addaccountMethodPhoneTitle: String {
        String(localized: "addaccount_method_phone_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_phone_unavailable
    public static var addaccountMethodPhoneUnavailable: String {
        String(localized: "addaccount_method_phone_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_method_phone_waiting
    public static var addaccountMethodPhoneWaiting: String {
        String(localized: "addaccount_method_phone_waiting", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_display_name_label
    public static var addaccountMutualTlsDisplayNameLabel: String {
        String(localized: "addaccount_mutual_tls_display_name_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_expires_on_label
    public static var addaccountMutualTlsExpiresOnLabel: String {
        String(localized: "addaccount_mutual_tls_expires_on_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_file_label
    public static var addaccountMutualTlsFileLabel: String {
        String(localized: "addaccount_mutual_tls_file_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_issuer_label
    public static var addaccountMutualTlsIssuerLabel: String {
        String(localized: "addaccount_mutual_tls_issuer_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_note
    public static var addaccountMutualTlsNote: String {
        String(localized: "addaccount_mutual_tls_note", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_password_dialog_message
    public static var addaccountMutualTlsPasswordDialogMessage: String {
        String(localized: "addaccount_mutual_tls_password_dialog_message", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_password_dialog_title
    public static var addaccountMutualTlsPasswordDialogTitle: String {
        String(localized: "addaccount_mutual_tls_password_dialog_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_password_label
    public static var addaccountMutualTlsPasswordLabel: String {
        String(localized: "addaccount_mutual_tls_password_label", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_section
    public static var addaccountMutualTlsSection: String {
        String(localized: "addaccount_mutual_tls_section", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_status_failed
    public static var addaccountMutualTlsStatusFailed: String {
        String(localized: "addaccount_mutual_tls_status_failed", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_mutual_tls_status_imported
    public static var addaccountMutualTlsStatusImported: String {
        String(localized: "addaccount_mutual_tls_status_imported", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_promo_features
    public static var addaccountPromoFeatures: String {
        String(localized: "addaccount_promo_features", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_promo_title
    public static var addaccountPromoTitle: String {
        String(localized: "addaccount_promo_title", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_promo_title_no_direct_action
    public static var addaccountPromoTitleNoDirectAction: String {
        String(localized: "addaccount_promo_title_no_direct_action", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_region_custom_type
    public static var addaccountRegionCustomType: String {
        String(localized: "addaccount_region_custom_type", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_region_eu_type
    public static var addaccountRegionEuType: String {
        String(localized: "addaccount_region_eu_type", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_region_section
    public static var addaccountRegionSection: String {
        String(localized: "addaccount_region_section", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_region_us_type
    public static var addaccountRegionUsType: String {
        String(localized: "addaccount_region_us_type", bundle: AppLocalization.shared.bundle)
    }
    /// addaccount_sign_in_button
    public static var addaccountSignInButton: String {
        String(localized: "addaccount_sign_in_button", bundle: AppLocalization.shared.bundle)
    }
    /// additem_attachments_drop_here
    public static var additemAttachmentsDropHere: String {
        String(localized: "additem_attachments_drop_here", bundle: AppLocalization.shared.bundle)
    }
    /// additem_auth_reprompt_text
    public static var additemAuthRepromptText: String {
        String(localized: "additem_auth_reprompt_text", bundle: AppLocalization.shared.bundle)
    }
    /// additem_auth_reprompt_title
    public static var additemAuthRepromptTitle: String {
        String(localized: "additem_auth_reprompt_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_gpg_key_generate_title
    public static var additemGpgKeyGenerateTitle: String {
        String(localized: "additem_gpg_key_generate_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_header_edit_title
    public static var additemHeaderEditTitle: String {
        String(localized: "additem_header_edit_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_header_merge_title
    public static var additemHeaderMergeTitle: String {
        String(localized: "additem_header_merge_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_header_new_title
    public static var additemHeaderNewTitle: String {
        String(localized: "additem_header_new_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_key_generator_back_title
    public static var additemKeyGeneratorBackTitle: String {
        String(localized: "additem_key_generator_back_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_key_import_title
    public static var additemKeyImportTitle: String {
        String(localized: "additem_key_import_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_key_replace_title
    public static var additemKeyReplaceTitle: String {
        String(localized: "additem_key_replace_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_key_use_title
    public static var additemKeyUseTitle: String {
        String(localized: "additem_key_use_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_login_totp_label
    public static var additemLoginTotpLabel: String {
        String(localized: "additem_login_totp_label", bundle: AppLocalization.shared.bundle)
    }
    /// additem_login_uri_label
    public static var additemLoginUriLabel: String {
        String(localized: "additem_login_uri_label", bundle: AppLocalization.shared.bundle)
    }
    /// additem_markdown_note
    public static func additemMarkdownNote(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "additem_markdown_note", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// additem_markdown_note_bold
    public static var additemMarkdownNoteBold: String {
        String(localized: "additem_markdown_note_bold", bundle: AppLocalization.shared.bundle)
    }
    /// additem_markdown_note_italic
    public static var additemMarkdownNoteItalic: String {
        String(localized: "additem_markdown_note_italic", bundle: AppLocalization.shared.bundle)
    }
    /// additem_markdown_render_preview
    public static var additemMarkdownRenderPreview: String {
        String(localized: "additem_markdown_render_preview", bundle: AppLocalization.shared.bundle)
    }
    /// additem_merge_archive_origin_ciphers_title
    public static var additemMergeArchiveOriginCiphersTitle: String {
        String(localized: "additem_merge_archive_origin_ciphers_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_merge_attachments_note
    public static var additemMergeAttachmentsNote: String {
        String(localized: "additem_merge_attachments_note", bundle: AppLocalization.shared.bundle)
    }
    /// additem_merge_keep_origin_ciphers_title
    public static var additemMergeKeepOriginCiphersTitle: String {
        String(localized: "additem_merge_keep_origin_ciphers_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_merge_remove_origin_ciphers_title
    public static var additemMergeRemoveOriginCiphersTitle: String {
        String(localized: "additem_merge_remove_origin_ciphers_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_note_placeholder
    public static var additemNotePlaceholder: String {
        String(localized: "additem_note_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// additem_ssh_key_generate_title
    public static var additemSshKeyGenerateTitle: String {
        String(localized: "additem_ssh_key_generate_title", bundle: AppLocalization.shared.bundle)
    }
    /// additem_type_secure_note_title
    public static var additemTypeSecureNoteTitle: String {
        String(localized: "additem_type_secure_note_title", bundle: AppLocalization.shared.bundle)
    }
    /// addkeepass_header_body
    public static var addkeepassHeaderBody: String {
        String(localized: "addkeepass_header_body", bundle: AppLocalization.shared.bundle)
    }
    /// addkeepass_header_title
    public static var addkeepassHeaderTitle: String {
        String(localized: "addkeepass_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// addkeepass_use_at_your_own_risk_beta_text
    public static var addkeepassUseAtYourOwnRiskBetaText: String {
        String(localized: "addkeepass_use_at_your_own_risk_beta_text", bundle: AppLocalization.shared.bundle)
    }
    /// address
    public static var address: String {
        String(localized: "address", bundle: AppLocalization.shared.bundle)
    }
    /// address1
    public static var address1: String {
        String(localized: "address1", bundle: AppLocalization.shared.bundle)
    }
    /// address2
    public static var address2: String {
        String(localized: "address2", bundle: AppLocalization.shared.bundle)
    }
    /// address3
    public static var address3: String {
        String(localized: "address3", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_auth_emails_label
    public static var addsendAuthEmailsLabel: String {
        String(localized: "addsend_auth_emails_label", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_auth_emails_note
    public static var addsendAuthEmailsNote: String {
        String(localized: "addsend_auth_emails_note", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_auth_emails_placeholder
    public static var addsendAuthEmailsPlaceholder: String {
        String(localized: "addsend_auth_emails_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_drop_file_to_replace
    public static var addsendDropFileToReplace: String {
        String(localized: "addsend_drop_file_to_replace", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_header_edit_title
    public static var addsendHeaderEditTitle: String {
        String(localized: "addsend_header_edit_title", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_header_new_title
    public static var addsendHeaderNewTitle: String {
        String(localized: "addsend_header_new_title", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_hide_email_title
    public static var addsendHideEmailTitle: String {
        String(localized: "addsend_hide_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_hide_text_by_default_title
    public static var addsendHideTextByDefaultTitle: String {
        String(localized: "addsend_hide_text_by_default_title", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_max_access_count_note
    public static var addsendMaxAccessCountNote: String {
        String(localized: "addsend_max_access_count_note", bundle: AppLocalization.shared.bundle)
    }
    /// addsend_text_hide_by_default_title
    public static var addsendTextHideByDefaultTitle: String {
        String(localized: "addsend_text_hide_by_default_title", bundle: AppLocalization.shared.bundle)
    }
    /// advanced_options
    public static var advancedOptions: String {
        String(localized: "advanced_options", bundle: AppLocalization.shared.bundle)
    }
    /// agent_approval_expires_in_text
    public static func agentApprovalExpiresInText(_ a1: String) -> String {
        String(format: String(localized: "agent_approval_expires_in_text", bundle: AppLocalization.shared.bundle), a1)
    }
    /// agent_approval_path_label
    public static var agentApprovalPathLabel: String {
        String(localized: "agent_approval_path_label", bundle: AppLocalization.shared.bundle)
    }
    /// agent_approval_requested_by_label
    public static var agentApprovalRequestedByLabel: String {
        String(localized: "agent_approval_requested_by_label", bundle: AppLocalization.shared.bundle)
    }
    /// agent_approval_time_left_label
    public static var agentApprovalTimeLeftLabel: String {
        String(localized: "agent_approval_time_left_label", bundle: AppLocalization.shared.bundle)
    }
    /// agent_approval_untitled_key
    public static var agentApprovalUntitledKey: String {
        String(localized: "agent_approval_untitled_key", bundle: AppLocalization.shared.bundle)
    }
    /// agent_approvals_header_title
    public static var agentApprovalsHeaderTitle: String {
        String(localized: "agent_approvals_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// agent_binary_unavailable_text
    public static var agentBinaryUnavailableText: String {
        String(localized: "agent_binary_unavailable_text", bundle: AppLocalization.shared.bundle)
    }
    /// agent_history_empty_label
    public static var agentHistoryEmptyLabel: String {
        String(localized: "agent_history_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// agent_keys_header_title
    public static var agentKeysHeaderTitle: String {
        String(localized: "agent_keys_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// algorithm
    public static var algorithm: String {
        String(localized: "algorithm", bundle: AppLocalization.shared.bundle)
    }
    /// api_key
    public static var apiKey: String {
        String(localized: "api_key", bundle: AppLocalization.shared.bundle)
    }
    /// api_token
    public static var apiToken: String {
        String(localized: "api_token", bundle: AppLocalization.shared.bundle)
    }
    /// app_password
    public static var appPassword: String {
        String(localized: "app_password", bundle: AppLocalization.shared.bundle)
    }
    /// apppicker_empty_label
    public static var apppickerEmptyLabel: String {
        String(localized: "apppicker_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// apppicker_header_title
    public static var apppickerHeaderTitle: String {
        String(localized: "apppicker_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// apppicker_search_placeholder
    public static var apppickerSearchPlaceholder: String {
        String(localized: "apppicker_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// apppicker_system_app_label
    public static var apppickerSystemAppLabel: String {
        String(localized: "apppicker_system_app_label", bundle: AppLocalization.shared.bundle)
    }
    /// april
    public static var april: String {
        String(localized: "april", bundle: AppLocalization.shared.bundle)
    }
    /// archive
    public static var archive: String {
        String(localized: "archive", bundle: AppLocalization.shared.bundle)
    }
    /// attachment
    public static var attachment: String {
        String(localized: "attachment", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_action_copy_all
    public static var attachmentPreviewActionCopyAll: String {
        String(localized: "attachment_preview_action_copy_all", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_action_rendered_preview
    public static var attachmentPreviewActionRenderedPreview: String {
        String(localized: "attachment_preview_action_rendered_preview", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_action_source
    public static var attachmentPreviewActionSource: String {
        String(localized: "attachment_preview_action_source", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_action_wrap_lines
    public static var attachmentPreviewActionWrapLines: String {
        String(localized: "attachment_preview_action_wrap_lines", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_error_decryption
    public static var attachmentPreviewErrorDecryption: String {
        String(localized: "attachment_preview_error_decryption", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_error_image_decode
    public static var attachmentPreviewErrorImageDecode: String {
        String(localized: "attachment_preview_error_image_decode", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_error_network
    public static var attachmentPreviewErrorNetwork: String {
        String(localized: "attachment_preview_error_network", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_error_text_decode
    public static var attachmentPreviewErrorTextDecode: String {
        String(localized: "attachment_preview_error_text_decode", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_error_too_large
    public static var attachmentPreviewErrorTooLarge: String {
        String(localized: "attachment_preview_error_too_large", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_error_unsupported_file
    public static var attachmentPreviewErrorUnsupportedFile: String {
        String(localized: "attachment_preview_error_unsupported_file", bundle: AppLocalization.shared.bundle)
    }
    /// attachment_preview_error_unsupported_platform
    public static var attachmentPreviewErrorUnsupportedPlatform: String {
        String(localized: "attachment_preview_error_unsupported_platform", bundle: AppLocalization.shared.bundle)
    }
    /// attachments
    public static var attachments: String {
        String(localized: "attachments", bundle: AppLocalization.shared.bundle)
    }
    /// attachments_empty_label
    public static var attachmentsEmptyLabel: String {
        String(localized: "attachments_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// august
    public static var august: String {
        String(localized: "august", bundle: AppLocalization.shared.bundle)
    }
    /// autofill
    public static var autofill: String {
        String(localized: "autofill", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_and_save_uri
    public static var autofillAndSaveUri: String {
        String(localized: "autofill_and_save_uri", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_choose_passkey
    public static var autofillChoosePasskey: String {
        String(localized: "autofill_choose_passkey", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_empty
    public static var autofillEmpty: String {
        String(localized: "autofill_empty", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_no_matching_logins
    public static var autofillNoMatchingLogins: String {
        String(localized: "autofill_no_matching_logins", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_open_keyguard
    public static var autofillOpenKeyguard: String {
        String(localized: "autofill_open_keyguard", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_other_logins
    public static var autofillOtherLogins: String {
        String(localized: "autofill_other_logins", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_passwords_section
    public static var autofillPasswordsSection: String {
        String(localized: "autofill_passwords_section", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_registration_failed
    public static var autofillRegistrationFailed: String {
        String(localized: "autofill_registration_failed", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_request_failed
    public static var autofillRequestFailed: String {
        String(localized: "autofill_request_failed", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_setup_required
    public static var autofillSetupRequired: String {
        String(localized: "autofill_setup_required", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_shared_storage_unavailable
    public static var autofillSharedStorageUnavailable: String {
        String(localized: "autofill_shared_storage_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_suggested
    public static var autofillSuggested: String {
        String(localized: "autofill_suggested", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_unlock_keyguard
    public static var autofillUnlockKeyguard: String {
        String(localized: "autofill_unlock_keyguard", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_unlock_subtitle
    public static var autofillUnlockSubtitle: String {
        String(localized: "autofill_unlock_subtitle", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_unsupported_algorithm
    public static var autofillUnsupportedAlgorithm: String {
        String(localized: "autofill_unsupported_algorithm", bundle: AppLocalization.shared.bundle)
    }
    /// autofill_verification_codes_section
    public static var autofillVerificationCodesSection: String {
        String(localized: "autofill_verification_codes_section", bundle: AppLocalization.shared.bundle)
    }
    /// backup_disclaimer_text
    public static var backupDisclaimerText: String {
        String(localized: "backup_disclaimer_text", bundle: AppLocalization.shared.bundle)
    }
    /// backup_disclaimer_title
    public static var backupDisclaimerTitle: String {
        String(localized: "backup_disclaimer_title", bundle: AppLocalization.shared.bundle)
    }
    /// barcode_invalid_or_unsupported_text
    public static var barcodeInvalidOrUnsupportedText: String {
        String(localized: "barcode_invalid_or_unsupported_text", bundle: AppLocalization.shared.bundle)
    }
    /// barcodetype_action_show_in_barcode_title
    public static var barcodetypeActionShowInBarcodeTitle: String {
        String(localized: "barcodetype_action_show_in_barcode_title", bundle: AppLocalization.shared.bundle)
    }
    /// barcodetype_copy_otp_secret_code_note
    public static var barcodetypeCopyOtpSecretCodeNote: String {
        String(localized: "barcodetype_copy_otp_secret_code_note", bundle: AppLocalization.shared.bundle)
    }
    /// barcodetype_title
    public static var barcodetypeTitle: String {
        String(localized: "barcodetype_title", bundle: AppLocalization.shared.bundle)
    }
    /// biometric_error_policy_not_installed
    public static var biometricErrorPolicyNotInstalled: String {
        String(localized: "biometric_error_policy_not_installed", bundle: AppLocalization.shared.bundle)
    }
    /// biometric_error_policy_not_installed_flatpak
    public static func biometricErrorPolicyNotInstalledFlatpak(_ a1: String) -> String {
        String(format: String(localized: "biometric_error_policy_not_installed_flatpak", bundle: AppLocalization.shared.bundle), a1)
    }
    /// bitwarden_premium
    public static var bitwardenPremium: String {
        String(localized: "bitwarden_premium", bundle: AppLocalization.shared.bundle)
    }
    /// bitwarden_premium_required
    public static var bitwardenPremiumRequired: String {
        String(localized: "bitwarden_premium_required", bundle: AppLocalization.shared.bundle)
    }
    /// bitwarden_unofficial_server
    public static var bitwardenUnofficialServer: String {
        String(localized: "bitwarden_unofficial_server", bundle: AppLocalization.shared.bundle)
    }
    /// block_autofill_header_title
    public static var blockAutofillHeaderTitle: String {
        String(localized: "block_autofill_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// cancel
    public static var cancel: String {
        String(localized: "cancel", bundle: AppLocalization.shared.bundle)
    }
    /// capabilities
    public static var capabilities: String {
        String(localized: "capabilities", bundle: AppLocalization.shared.bundle)
    }
    /// card_cvv
    public static var cardCvv: String {
        String(localized: "card_cvv", bundle: AppLocalization.shared.bundle)
    }
    /// card_cvv_short_label
    public static var cardCvvShortLabel: String {
        String(localized: "card_cvv_short_label", bundle: AppLocalization.shared.bundle)
    }
    /// card_expiry_month
    public static var cardExpiryMonth: String {
        String(localized: "card_expiry_month", bundle: AppLocalization.shared.bundle)
    }
    /// card_expiry_year
    public static var cardExpiryYear: String {
        String(localized: "card_expiry_year", bundle: AppLocalization.shared.bundle)
    }
    /// card_number
    public static var cardNumber: String {
        String(localized: "card_number", bundle: AppLocalization.shared.bundle)
    }
    /// card_number_empty_label
    public static var cardNumberEmptyLabel: String {
        String(localized: "card_number_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// card_type
    public static var cardType: String {
        String(localized: "card_type", bundle: AppLocalization.shared.bundle)
    }
    /// card_valid_from
    public static var cardValidFrom: String {
        String(localized: "card_valid_from", bundle: AppLocalization.shared.bundle)
    }
    /// card_valid_to
    public static var cardValidTo: String {
        String(localized: "card_valid_to", bundle: AppLocalization.shared.bundle)
    }
    /// cardholder_name
    public static var cardholderName: String {
        String(localized: "cardholder_name", bundle: AppLocalization.shared.bundle)
    }
    /// category
    public static var category: String {
        String(localized: "category", bundle: AppLocalization.shared.bundle)
    }
    /// change
    public static var change: String {
        String(localized: "change", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_biometric_auth_checkbox
    public static var changepasswordBiometricAuthCheckbox: String {
        String(localized: "changepassword_biometric_auth_checkbox", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_biometric_auth_confirm_title
    public static var changepasswordBiometricAuthConfirmTitle: String {
        String(localized: "changepassword_biometric_auth_confirm_title", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_change_password_button
    public static var changepasswordChangePasswordButton: String {
        String(localized: "changepassword_change_password_button", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_disclaimer_abuse_note
    public static var changepasswordDisclaimerAbuseNote: String {
        String(localized: "changepassword_disclaimer_abuse_note", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_disclaimer_local_note
    public static var changepasswordDisclaimerLocalNote: String {
        String(localized: "changepassword_disclaimer_local_note", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_header_title
    public static var changepasswordHeaderTitle: String {
        String(localized: "changepassword_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_master_password_title
    public static var changepasswordMasterPasswordTitle: String {
        String(localized: "changepassword_master_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// changepassword_password_changed_successfully
    public static var changepasswordPasswordChangedSuccessfully: String {
        String(localized: "changepassword_password_changed_successfully", bundle: AppLocalization.shared.bundle)
    }
    /// choose_file
    public static var chooseFile: String {
        String(localized: "choose_file", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_link_picker_action
    public static var cipherLinkPickerAction: String {
        String(localized: "cipher_link_picker_action", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_link_picker_title
    public static var cipherLinkPickerTitle: String {
        String(localized: "cipher_link_picker_title", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_link_unavailable_title
    public static var cipherLinkUnavailableTitle: String {
        String(localized: "cipher_link_unavailable_title", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_links_incoming_title
    public static var cipherLinksIncomingTitle: String {
        String(localized: "cipher_links_incoming_title", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_links_outgoing_title
    public static var cipherLinksOutgoingTitle: String {
        String(localized: "cipher_links_outgoing_title", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_card
    public static var cipherTypeCard: String {
        String(localized: "cipher_type_card", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_gpg_key
    public static var cipherTypeGpgKey: String {
        String(localized: "cipher_type_gpg_key", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_identity
    public static var cipherTypeIdentity: String {
        String(localized: "cipher_type_identity", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_login
    public static var cipherTypeLogin: String {
        String(localized: "cipher_type_login", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_note
    public static var cipherTypeNote: String {
        String(localized: "cipher_type_note", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_passkey
    public static var cipherTypePasskey: String {
        String(localized: "cipher_type_passkey", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_ssh_key
    public static var cipherTypeSshKey: String {
        String(localized: "cipher_type_ssh_key", bundle: AppLocalization.shared.bundle)
    }
    /// cipher_type_unknown
    public static var cipherTypeUnknown: String {
        String(localized: "cipher_type_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_add_password_title
    public static var ciphersActionAddPasswordTitle: String {
        String(localized: "ciphers_action_add_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_add_passwords_title
    public static var ciphersActionAddPasswordsTitle: String {
        String(localized: "ciphers_action_add_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_add_to_favorites_title
    public static var ciphersActionAddToFavoritesTitle: String {
        String(localized: "ciphers_action_add_to_favorites_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_archive_confirmation_title
    public static var ciphersActionArchiveConfirmationTitle: String {
        String(localized: "ciphers_action_archive_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_archive_title
    public static var ciphersActionArchiveTitle: String {
        String(localized: "ciphers_action_archive_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_cascade_trash_associated_items_title
    public static var ciphersActionCascadeTrashAssociatedItemsTitle: String {
        String(localized: "ciphers_action_cascade_trash_associated_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_change_folder_title
    public static var ciphersActionChangeFolderTitle: String {
        String(localized: "ciphers_action_change_folder_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_change_gpg_key_expiry_title
    public static var ciphersActionChangeGpgKeyExpiryTitle: String {
        String(localized: "ciphers_action_change_gpg_key_expiry_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_change_name_title
    public static var ciphersActionChangeNameTitle: String {
        String(localized: "ciphers_action_change_name_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_change_names_title
    public static var ciphersActionChangeNamesTitle: String {
        String(localized: "ciphers_action_change_names_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_change_password_title
    public static var ciphersActionChangePasswordTitle: String {
        String(localized: "ciphers_action_change_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_change_passwords_title
    public static var ciphersActionChangePasswordsTitle: String {
        String(localized: "ciphers_action_change_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_change_tags_title
    public static var ciphersActionChangeTagsTitle: String {
        String(localized: "ciphers_action_change_tags_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_configure_watchtower_alerts_title
    public static var ciphersActionConfigureWatchtowerAlertsTitle: String {
        String(localized: "ciphers_action_configure_watchtower_alerts_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_copy_title
    public static var ciphersActionCopyTitle: String {
        String(localized: "ciphers_action_copy_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_delete_confirmation_title
    public static var ciphersActionDeleteConfirmationTitle: String {
        String(localized: "ciphers_action_delete_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_delete_title
    public static var ciphersActionDeleteTitle: String {
        String(localized: "ciphers_action_delete_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_disable_auth_reprompt_title
    public static var ciphersActionDisableAuthRepromptTitle: String {
        String(localized: "ciphers_action_disable_auth_reprompt_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_edit_title
    public static var ciphersActionEditTitle: String {
        String(localized: "ciphers_action_edit_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_enable_auth_reprompt_title
    public static var ciphersActionEnableAuthRepromptTitle: String {
        String(localized: "ciphers_action_enable_auth_reprompt_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_export_title
    public static var ciphersActionExportTitle: String {
        String(localized: "ciphers_action_export_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_merge_title
    public static var ciphersActionMergeTitle: String {
        String(localized: "ciphers_action_merge_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_refresh_gpg_public_key_confirmation_text
    public static var ciphersActionRefreshGpgPublicKeyConfirmationText: String {
        String(localized: "ciphers_action_refresh_gpg_public_key_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_refresh_gpg_public_key_confirmation_title
    public static var ciphersActionRefreshGpgPublicKeyConfirmationTitle: String {
        String(localized: "ciphers_action_refresh_gpg_public_key_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_refresh_gpg_public_key_title
    public static var ciphersActionRefreshGpgPublicKeyTitle: String {
        String(localized: "ciphers_action_refresh_gpg_public_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_remove_from_favorites_title
    public static var ciphersActionRemoveFromFavoritesTitle: String {
        String(localized: "ciphers_action_remove_from_favorites_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_restore_confirmation_title
    public static var ciphersActionRestoreConfirmationTitle: String {
        String(localized: "ciphers_action_restore_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_restore_title
    public static var ciphersActionRestoreTitle: String {
        String(localized: "ciphers_action_restore_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_trash_confirmation_text
    public static var ciphersActionTrashConfirmationText: String {
        String(localized: "ciphers_action_trash_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_trash_confirmation_title
    public static var ciphersActionTrashConfirmationTitle: String {
        String(localized: "ciphers_action_trash_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_trash_title
    public static var ciphersActionTrashTitle: String {
        String(localized: "ciphers_action_trash_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_unarchive_confirmation_title
    public static var ciphersActionUnarchiveConfirmationTitle: String {
        String(localized: "ciphers_action_unarchive_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_unarchive_title
    public static var ciphersActionUnarchiveTitle: String {
        String(localized: "ciphers_action_unarchive_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_upload_gpg_public_key_confirmation_text
    public static var ciphersActionUploadGpgPublicKeyConfirmationText: String {
        String(localized: "ciphers_action_upload_gpg_public_key_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_upload_gpg_public_key_confirmation_title
    public static var ciphersActionUploadGpgPublicKeyConfirmationTitle: String {
        String(localized: "ciphers_action_upload_gpg_public_key_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_upload_gpg_public_key_confirmation_verify_text
    public static var ciphersActionUploadGpgPublicKeyConfirmationVerifyText: String {
        String(localized: "ciphers_action_upload_gpg_public_key_confirmation_verify_text", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_upload_gpg_public_key_title
    public static var ciphersActionUploadGpgPublicKeyTitle: String {
        String(localized: "ciphers_action_upload_gpg_public_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_verify_gpg_public_key_title
    public static var ciphersActionVerifyGpgPublicKeyTitle: String {
        String(localized: "ciphers_action_verify_gpg_public_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_view_password_history_title
    public static var ciphersActionViewPasswordHistoryTitle: String {
        String(localized: "ciphers_action_view_password_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_action_view_ssh_agent_history_title
    public static var ciphersActionViewSshAgentHistoryTitle: String {
        String(localized: "ciphers_action_view_ssh_agent_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_history_empty
    public static var ciphersHistoryEmpty: String {
        String(localized: "ciphers_history_empty", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_often_opened
    public static var ciphersOftenOpened: String {
        String(localized: "ciphers_often_opened", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_recently_opened
    public static var ciphersRecentlyOpened: String {
        String(localized: "ciphers_recently_opened", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_save_to
    public static var ciphersSaveTo: String {
        String(localized: "ciphers_save_to", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_save_to_adds_credentials_passkey
    public static var ciphersSaveToAddsCredentialsPasskey: String {
        String(localized: "ciphers_save_to_adds_credentials_passkey", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_save_to_replaces_credentials_username_password
    public static var ciphersSaveToReplacesCredentialsUsernamePassword: String {
        String(localized: "ciphers_save_to_replaces_credentials_username_password", bundle: AppLocalization.shared.bundle)
    }
    /// ciphers_view_details
    public static var ciphersViewDetails: String {
        String(localized: "ciphers_view_details", bundle: AppLocalization.shared.bundle)
    }
    /// city
    public static var city: String {
        String(localized: "city", bundle: AppLocalization.shared.bundle)
    }
    /// clear_file
    public static var clearFile: String {
        String(localized: "clear_file", bundle: AppLocalization.shared.bundle)
    }
    /// close
    public static var close: String {
        String(localized: "close", bundle: AppLocalization.shared.bundle)
    }
    /// collection
    public static var collection: String {
        String(localized: "collection", bundle: AppLocalization.shared.bundle)
    }
    /// collection_none
    public static var collectionNone: String {
        String(localized: "collection_none", bundle: AppLocalization.shared.bundle)
    }
    /// collections
    public static var collections: String {
        String(localized: "collections", bundle: AppLocalization.shared.bundle)
    }
    /// collections_empty_label
    public static var collectionsEmptyLabel: String {
        String(localized: "collections_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// collections_empty_text
    public static var collectionsEmptyText: String {
        String(localized: "collections_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// colorpicker_title
    public static var colorpickerTitle: String {
        String(localized: "colorpicker_title", bundle: AppLocalization.shared.bundle)
    }
    /// coming_soon
    public static var comingSoon: String {
        String(localized: "coming_soon", bundle: AppLocalization.shared.bundle)
    }
    /// command
    public static var command: String {
        String(localized: "command", bundle: AppLocalization.shared.bundle)
    }
    /// companionauth_header_text
    public static var companionauthHeaderText: String {
        String(localized: "companionauth_header_text", bundle: AppLocalization.shared.bundle)
    }
    /// companionauth_header_title
    public static var companionauthHeaderTitle: String {
        String(localized: "companionauth_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// company
    public static var company: String {
        String(localized: "company", bundle: AppLocalization.shared.bundle)
    }
    /// conceal_value
    public static var concealValue: String {
        String(localized: "conceal_value", bundle: AppLocalization.shared.bundle)
    }
    /// confirm
    public static var confirm: String {
        String(localized: "confirm", bundle: AppLocalization.shared.bundle)
    }
    /// confirm_biometric_face_id_title
    public static var confirmBiometricFaceIdTitle: String {
        String(localized: "confirm_biometric_face_id_title", bundle: AppLocalization.shared.bundle)
    }
    /// confirm_biometric_title
    public static var confirmBiometricTitle: String {
        String(localized: "confirm_biometric_title", bundle: AppLocalization.shared.bundle)
    }
    /// confirm_biometric_touch_id_title
    public static var confirmBiometricTouchIdTitle: String {
        String(localized: "confirm_biometric_touch_id_title", bundle: AppLocalization.shared.bundle)
    }
    /// confirm_yubikey_title
    public static var confirmYubikeyTitle: String {
        String(localized: "confirm_yubikey_title", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_empty
    public static var connectedCryptoAppsEmpty: String {
        String(localized: "connected_crypto_apps_empty", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_last_used
    public static var connectedCryptoAppsLastUsed: String {
        String(localized: "connected_crypto_apps_last_used", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_registered_at
    public static var connectedCryptoAppsRegisteredAt: String {
        String(localized: "connected_crypto_apps_registered_at", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_revoke
    public static var connectedCryptoAppsRevoke: String {
        String(localized: "connected_crypto_apps_revoke", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_revoke_message
    public static var connectedCryptoAppsRevokeMessage: String {
        String(localized: "connected_crypto_apps_revoke_message", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_revoke_title
    public static func connectedCryptoAppsRevokeTitle(_ a1: String) -> String {
        String(format: String(localized: "connected_crypto_apps_revoke_title", bundle: AppLocalization.shared.bundle), a1)
    }
    /// connected_crypto_apps_signer
    public static var connectedCryptoAppsSigner: String {
        String(localized: "connected_crypto_apps_signer", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_status_not_installed
    public static var connectedCryptoAppsStatusNotInstalled: String {
        String(localized: "connected_crypto_apps_status_not_installed", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_status_registered
    public static var connectedCryptoAppsStatusRegistered: String {
        String(localized: "connected_crypto_apps_status_registered", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_status_signer_changed
    public static var connectedCryptoAppsStatusSignerChanged: String {
        String(localized: "connected_crypto_apps_status_signer_changed", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_summary
    public static var connectedCryptoAppsSummary: String {
        String(localized: "connected_crypto_apps_summary", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_title
    public static var connectedCryptoAppsTitle: String {
        String(localized: "connected_crypto_apps_title", bundle: AppLocalization.shared.bundle)
    }
    /// connected_crypto_apps_unknown
    public static var connectedCryptoAppsUnknown: String {
        String(localized: "connected_crypto_apps_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// contact_info
    public static var contactInfo: String {
        String(localized: "contact_info", bundle: AppLocalization.shared.bundle)
    }
    /// contactus_english_note
    public static var contactusEnglishNote: String {
        String(localized: "contactus_english_note", bundle: AppLocalization.shared.bundle)
    }
    /// contactus_header_title
    public static var contactusHeaderTitle: String {
        String(localized: "contactus_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// contactus_message_label
    public static var contactusMessageLabel: String {
        String(localized: "contactus_message_label", bundle: AppLocalization.shared.bundle)
    }
    /// contactus_thanks_note
    public static var contactusThanksNote: String {
        String(localized: "contactus_thanks_note", bundle: AppLocalization.shared.bundle)
    }
    /// context_based_suggestions
    public static var contextBasedSuggestions: String {
        String(localized: "context_based_suggestions", bundle: AppLocalization.shared.bundle)
    }
    /// continue_
    public static var `continue`: String {
        String(localized: "continue_", bundle: AppLocalization.shared.bundle)
    }
    /// copied_card_number
    public static var copiedCardNumber: String {
        String(localized: "copied_card_number", bundle: AppLocalization.shared.bundle)
    }
    /// copied_cardholder_name
    public static var copiedCardholderName: String {
        String(localized: "copied_cardholder_name", bundle: AppLocalization.shared.bundle)
    }
    /// copied_cvv_code
    public static var copiedCvvCode: String {
        String(localized: "copied_cvv_code", bundle: AppLocalization.shared.bundle)
    }
    /// copied_email
    public static var copiedEmail: String {
        String(localized: "copied_email", bundle: AppLocalization.shared.bundle)
    }
    /// copied_expiration_month
    public static var copiedExpirationMonth: String {
        String(localized: "copied_expiration_month", bundle: AppLocalization.shared.bundle)
    }
    /// copied_expiration_year
    public static var copiedExpirationYear: String {
        String(localized: "copied_expiration_year", bundle: AppLocalization.shared.bundle)
    }
    /// copied_fingerprint
    public static var copiedFingerprint: String {
        String(localized: "copied_fingerprint", bundle: AppLocalization.shared.bundle)
    }
    /// copied_key
    public static var copiedKey: String {
        String(localized: "copied_key", bundle: AppLocalization.shared.bundle)
    }
    /// copied_license_number
    public static var copiedLicenseNumber: String {
        String(localized: "copied_license_number", bundle: AppLocalization.shared.bundle)
    }
    /// copied_otp_code
    public static var copiedOtpCode: String {
        String(localized: "copied_otp_code", bundle: AppLocalization.shared.bundle)
    }
    /// copied_otp_secret_code
    public static var copiedOtpSecretCode: String {
        String(localized: "copied_otp_secret_code", bundle: AppLocalization.shared.bundle)
    }
    /// copied_package_name
    public static var copiedPackageName: String {
        String(localized: "copied_package_name", bundle: AppLocalization.shared.bundle)
    }
    /// copied_passport_number
    public static var copiedPassportNumber: String {
        String(localized: "copied_passport_number", bundle: AppLocalization.shared.bundle)
    }
    /// copied_password
    public static var copiedPassword: String {
        String(localized: "copied_password", bundle: AppLocalization.shared.bundle)
    }
    /// copied_phone_number
    public static var copiedPhoneNumber: String {
        String(localized: "copied_phone_number", bundle: AppLocalization.shared.bundle)
    }
    /// copied_tag
    public static var copiedTag: String {
        String(localized: "copied_tag", bundle: AppLocalization.shared.bundle)
    }
    /// copied_uri
    public static var copiedUri: String {
        String(localized: "copied_uri", bundle: AppLocalization.shared.bundle)
    }
    /// copied_url
    public static var copiedUrl: String {
        String(localized: "copied_url", bundle: AppLocalization.shared.bundle)
    }
    /// copied_username
    public static var copiedUsername: String {
        String(localized: "copied_username", bundle: AppLocalization.shared.bundle)
    }
    /// copied_value
    public static var copiedValue: String {
        String(localized: "copied_value", bundle: AppLocalization.shared.bundle)
    }
    /// copy
    public static var copy: String {
        String(localized: "copy", bundle: AppLocalization.shared.bundle)
    }
    /// copy_bundle_id
    public static var copyBundleId: String {
        String(localized: "copy_bundle_id", bundle: AppLocalization.shared.bundle)
    }
    /// copy_card_number
    public static var copyCardNumber: String {
        String(localized: "copy_card_number", bundle: AppLocalization.shared.bundle)
    }
    /// copy_cardholder_name
    public static var copyCardholderName: String {
        String(localized: "copy_cardholder_name", bundle: AppLocalization.shared.bundle)
    }
    /// copy_cvv_code
    public static var copyCvvCode: String {
        String(localized: "copy_cvv_code", bundle: AppLocalization.shared.bundle)
    }
    /// copy_email
    public static var copyEmail: String {
        String(localized: "copy_email", bundle: AppLocalization.shared.bundle)
    }
    /// copy_expiration_month
    public static var copyExpirationMonth: String {
        String(localized: "copy_expiration_month", bundle: AppLocalization.shared.bundle)
    }
    /// copy_expiration_year
    public static var copyExpirationYear: String {
        String(localized: "copy_expiration_year", bundle: AppLocalization.shared.bundle)
    }
    /// copy_gpg_fingerprint
    public static var copyGpgFingerprint: String {
        String(localized: "copy_gpg_fingerprint", bundle: AppLocalization.shared.bundle)
    }
    /// copy_gpg_public_key
    public static var copyGpgPublicKey: String {
        String(localized: "copy_gpg_public_key", bundle: AppLocalization.shared.bundle)
    }
    /// copy_gpg_unencrypted_private_key
    public static var copyGpgUnencryptedPrivateKey: String {
        String(localized: "copy_gpg_unencrypted_private_key", bundle: AppLocalization.shared.bundle)
    }
    /// copy_license_number
    public static var copyLicenseNumber: String {
        String(localized: "copy_license_number", bundle: AppLocalization.shared.bundle)
    }
    /// copy_otp_code
    public static var copyOtpCode: String {
        String(localized: "copy_otp_code", bundle: AppLocalization.shared.bundle)
    }
    /// copy_otp_secret_code
    public static var copyOtpSecretCode: String {
        String(localized: "copy_otp_secret_code", bundle: AppLocalization.shared.bundle)
    }
    /// copy_package_name
    public static var copyPackageName: String {
        String(localized: "copy_package_name", bundle: AppLocalization.shared.bundle)
    }
    /// copy_passport_number
    public static var copyPassportNumber: String {
        String(localized: "copy_passport_number", bundle: AppLocalization.shared.bundle)
    }
    /// copy_password
    public static var copyPassword: String {
        String(localized: "copy_password", bundle: AppLocalization.shared.bundle)
    }
    /// copy_phone_number
    public static var copyPhoneNumber: String {
        String(localized: "copy_phone_number", bundle: AppLocalization.shared.bundle)
    }
    /// copy_send
    public static var copySend: String {
        String(localized: "copy_send", bundle: AppLocalization.shared.bundle)
    }
    /// copy_ssh_fingerprint
    public static var copySshFingerprint: String {
        String(localized: "copy_ssh_fingerprint", bundle: AppLocalization.shared.bundle)
    }
    /// copy_ssh_public_key
    public static var copySshPublicKey: String {
        String(localized: "copy_ssh_public_key", bundle: AppLocalization.shared.bundle)
    }
    /// copy_ssh_unencrypted_private_key
    public static var copySshUnencryptedPrivateKey: String {
        String(localized: "copy_ssh_unencrypted_private_key", bundle: AppLocalization.shared.bundle)
    }
    /// copy_uri
    public static var copyUri: String {
        String(localized: "copy_uri", bundle: AppLocalization.shared.bundle)
    }
    /// copy_url
    public static var copyUrl: String {
        String(localized: "copy_url", bundle: AppLocalization.shared.bundle)
    }
    /// copy_username
    public static var copyUsername: String {
        String(localized: "copy_username", bundle: AppLocalization.shared.bundle)
    }
    /// copy_value
    public static var copyValue: String {
        String(localized: "copy_value", bundle: AppLocalization.shared.bundle)
    }
    /// country
    public static var country: String {
        String(localized: "country", bundle: AppLocalization.shared.bundle)
    }
    /// create_account
    public static var createAccount: String {
        String(localized: "create_account", bundle: AppLocalization.shared.bundle)
    }
    /// create_database
    public static var createDatabase: String {
        String(localized: "create_database", bundle: AppLocalization.shared.bundle)
    }
    /// created
    public static var created: String {
        String(localized: "created", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_card
    public static var credentialExchangeExportCredentialCard: String {
        String(localized: "credential_exchange_export_credential_card", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_fields
    public static var credentialExchangeExportCredentialFields: String {
        String(localized: "credential_exchange_export_credential_fields", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_identity
    public static var credentialExchangeExportCredentialIdentity: String {
        String(localized: "credential_exchange_export_credential_identity", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_note
    public static var credentialExchangeExportCredentialNote: String {
        String(localized: "credential_exchange_export_credential_note", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_passkey
    public static var credentialExchangeExportCredentialPasskey: String {
        String(localized: "credential_exchange_export_credential_passkey", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_password
    public static var credentialExchangeExportCredentialPassword: String {
        String(localized: "credential_exchange_export_credential_password", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_ssh_key
    public static var credentialExchangeExportCredentialSshKey: String {
        String(localized: "credential_exchange_export_credential_ssh_key", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_credential_totp
    public static var credentialExchangeExportCredentialTotp: String {
        String(localized: "credential_exchange_export_credential_totp", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_empty_label
    public static var credentialExchangeExportEmptyLabel: String {
        String(localized: "credential_exchange_export_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_error_account_unavailable
    public static var credentialExchangeExportErrorAccountUnavailable: String {
        String(localized: "credential_exchange_export_error_account_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_error_unknown
    public static var credentialExchangeExportErrorUnknown: String {
        String(localized: "credential_exchange_export_error_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_export_button
    public static var credentialExchangeExportExportButton: String {
        String(localized: "credential_exchange_export_export_button", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_header_text
    public static var credentialExchangeExportHeaderText: String {
        String(localized: "credential_exchange_export_header_text", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_export_header_text_named
    public static func credentialExchangeExportHeaderTextNamed(_ a1: String) -> String {
        String(format: String(localized: "credential_exchange_export_header_text_named", bundle: AppLocalization.shared.bundle), a1)
    }
    /// credential_exchange_export_header_title
    public static var credentialExchangeExportHeaderTitle: String {
        String(localized: "credential_exchange_export_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_empty_label
    public static var credentialExchangeImportEmptyLabel: String {
        String(localized: "credential_exchange_import_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_error_no_providers
    public static var credentialExchangeImportErrorNoProviders: String {
        String(localized: "credential_exchange_import_error_no_providers", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_error_parse
    public static var credentialExchangeImportErrorParse: String {
        String(localized: "credential_exchange_import_error_parse", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_error_save
    public static var credentialExchangeImportErrorSave: String {
        String(localized: "credential_exchange_import_error_save", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_error_unavailable
    public static var credentialExchangeImportErrorUnavailable: String {
        String(localized: "credential_exchange_import_error_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_error_unknown
    public static var credentialExchangeImportErrorUnknown: String {
        String(localized: "credential_exchange_import_error_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_header_text
    public static var credentialExchangeImportHeaderText: String {
        String(localized: "credential_exchange_import_header_text", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_header_title
    public static var credentialExchangeImportHeaderTitle: String {
        String(localized: "credential_exchange_import_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_import_button
    public static var credentialExchangeImportImportButton: String {
        String(localized: "credential_exchange_import_import_button", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_learn_more
    public static var credentialExchangeImportLearnMore: String {
        String(localized: "credential_exchange_import_learn_more", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_retry_button
    public static var credentialExchangeImportRetryButton: String {
        String(localized: "credential_exchange_import_retry_button", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_review_all_items_section
    public static var credentialExchangeImportReviewAllItemsSection: String {
        String(localized: "credential_exchange_import_review_all_items_section", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_review_items_section
    public static var credentialExchangeImportReviewItemsSection: String {
        String(localized: "credential_exchange_import_review_items_section", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_review_source_label
    public static var credentialExchangeImportReviewSourceLabel: String {
        String(localized: "credential_exchange_import_review_source_label", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_start_button
    public static var credentialExchangeImportStartButton: String {
        String(localized: "credential_exchange_import_start_button", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_step_1_text
    public static var credentialExchangeImportStep1Text: String {
        String(localized: "credential_exchange_import_step_1_text", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_step_1_title
    public static var credentialExchangeImportStep1Title: String {
        String(localized: "credential_exchange_import_step_1_title", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_step_2_text
    public static var credentialExchangeImportStep2Text: String {
        String(localized: "credential_exchange_import_step_2_text", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_step_2_title
    public static var credentialExchangeImportStep2Title: String {
        String(localized: "credential_exchange_import_step_2_title", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_step_3_text
    public static var credentialExchangeImportStep3Text: String {
        String(localized: "credential_exchange_import_step_3_text", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_step_3_title
    public static var credentialExchangeImportStep3Title: String {
        String(localized: "credential_exchange_import_step_3_title", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_success_title
    public static var credentialExchangeImportSuccessTitle: String {
        String(localized: "credential_exchange_import_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// credential_exchange_import_untitled
    public static var credentialExchangeImportUntitled: String {
        String(localized: "credential_exchange_import_untitled", bundle: AppLocalization.shared.bundle)
    }
    /// credential_login_create_header
    public static var credentialLoginCreateHeader: String {
        String(localized: "credential_login_create_header", bundle: AppLocalization.shared.bundle)
    }
    /// credential_password_create_header
    public static var credentialPasswordCreateHeader: String {
        String(localized: "credential_password_create_header", bundle: AppLocalization.shared.bundle)
    }
    /// current_password
    public static var currentPassword: String {
        String(localized: "current_password", bundle: AppLocalization.shared.bundle)
    }
    /// custom
    public static var custom: String {
        String(localized: "custom", bundle: AppLocalization.shared.bundle)
    }
    /// custom_field
    public static var customField: String {
        String(localized: "custom_field", bundle: AppLocalization.shared.bundle)
    }
    /// custom_field_toggle_boolean_value
    public static var customFieldToggleBooleanValue: String {
        String(localized: "custom_field_toggle_boolean_value", bundle: AppLocalization.shared.bundle)
    }
    /// custom_fields
    public static var customFields: String {
        String(localized: "custom_fields", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_add_filter_title
    public static var customfiltersAddFilterTitle: String {
        String(localized: "customfilters_add_filter_title", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_delete_many_confirmation_title
    public static var customfiltersDeleteManyConfirmationTitle: String {
        String(localized: "customfilters_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_delete_one_confirmation_title
    public static var customfiltersDeleteOneConfirmationTitle: String {
        String(localized: "customfilters_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_dynamic_shortcut_tip
    public static var customfiltersDynamicShortcutTip: String {
        String(localized: "customfilters_dynamic_shortcut_tip", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_edit_filter_title
    public static var customfiltersEditFilterTitle: String {
        String(localized: "customfilters_edit_filter_title", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_empty_hint
    public static var customfiltersEmptyHint: String {
        String(localized: "customfilters_empty_hint", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_header_title
    public static var customfiltersHeaderTitle: String {
        String(localized: "customfilters_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// customfilters_search_placeholder
    public static var customfiltersSearchPlaceholder: String {
        String(localized: "customfilters_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// database_location_local
    public static var databaseLocationLocal: String {
        String(localized: "database_location_local", bundle: AppLocalization.shared.bundle)
    }
    /// database_location_title
    public static var databaseLocationTitle: String {
        String(localized: "database_location_title", bundle: AppLocalization.shared.bundle)
    }
    /// database_location_webdav
    public static var databaseLocationWebdav: String {
        String(localized: "database_location_webdav", bundle: AppLocalization.shared.bundle)
    }
    /// database_name
    public static var databaseName: String {
        String(localized: "database_name", bundle: AppLocalization.shared.bundle)
    }
    /// database_version
    public static func databaseVersion(_ a1: String) -> String {
        String(format: String(localized: "database_version", bundle: AppLocalization.shared.bundle), a1)
    }
    /// datasafety_header_title
    public static var datasafetyHeaderTitle: String {
        String(localized: "datasafety_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_downloads_section
    public static var datasafetyLocalDownloadsSection: String {
        String(localized: "datasafety_local_downloads_section", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_encryption_algorithm_intro
    public static var datasafetyLocalEncryptionAlgorithmIntro: String {
        String(localized: "datasafety_local_encryption_algorithm_intro", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_encryption_algorithm_outro
    public static var datasafetyLocalEncryptionAlgorithmOutro: String {
        String(localized: "datasafety_local_encryption_algorithm_outro", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_kdf_versions_note
    public static var datasafetyLocalKdfVersionsNote: String {
        String(localized: "datasafety_local_kdf_versions_note", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_section
    public static var datasafetyLocalSection: String {
        String(localized: "datasafety_local_section", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_settings_no_app_encryption_note
    public static var datasafetyLocalSettingsNoAppEncryptionNote: String {
        String(localized: "datasafety_local_settings_no_app_encryption_note", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_settings_note
    public static var datasafetyLocalSettingsNote: String {
        String(localized: "datasafety_local_settings_note", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_settings_section
    public static var datasafetyLocalSettingsSection: String {
        String(localized: "datasafety_local_settings_section", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_stored_on_device_text
    public static var datasafetyLocalStoredOnDeviceText: String {
        String(localized: "datasafety_local_stored_on_device_text", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_text
    public static var datasafetyLocalText: String {
        String(localized: "datasafety_local_text", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_unlocking_vault
    public static var datasafetyLocalUnlockingVault: String {
        String(localized: "datasafety_local_unlocking_vault", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_local_vault_section
    public static var datasafetyLocalVaultSection: String {
        String(localized: "datasafety_local_vault_section", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_remote_section
    public static var datasafetyRemoteSection: String {
        String(localized: "datasafety_remote_section", bundle: AppLocalization.shared.bundle)
    }
    /// datasafety_remote_text
    public static var datasafetyRemoteText: String {
        String(localized: "datasafety_remote_text", bundle: AppLocalization.shared.bundle)
    }
    /// date
    public static var date: String {
        String(localized: "date", bundle: AppLocalization.shared.bundle)
    }
    /// datepicker_title
    public static var datepickerTitle: String {
        String(localized: "datepicker_title", bundle: AppLocalization.shared.bundle)
    }
    /// deactivated
    public static var deactivated: String {
        String(localized: "deactivated", bundle: AppLocalization.shared.bundle)
    }
    /// december
    public static var december: String {
        String(localized: "december", bundle: AppLocalization.shared.bundle)
    }
    /// decrypt
    public static var decrypt: String {
        String(localized: "decrypt", bundle: AppLocalization.shared.bundle)
    }
    /// delete
    public static var delete: String {
        String(localized: "delete", bundle: AppLocalization.shared.bundle)
    }
    /// deletion_date
    public static var deletionDate: String {
        String(localized: "deletion_date", bundle: AppLocalization.shared.bundle)
    }
    /// deletion_date_custom
    public static var deletionDateCustom: String {
        String(localized: "deletion_date_custom", bundle: AppLocalization.shared.bundle)
    }
    /// description
    public static var description: String {
        String(localized: "description", bundle: AppLocalization.shared.bundle)
    }
    /// destination_email
    public static var destinationEmail: String {
        String(localized: "destination_email", bundle: AppLocalization.shared.bundle)
    }
    /// destinationpicker_organization_ownership_note
    public static var destinationpickerOrganizationOwnershipNote: String {
        String(localized: "destinationpicker_organization_ownership_note", bundle: AppLocalization.shared.bundle)
    }
    /// disabled
    public static var disabled: String {
        String(localized: "disabled", bundle: AppLocalization.shared.bundle)
    }
    /// domain
    public static var domain: String {
        String(localized: "domain", bundle: AppLocalization.shared.bundle)
    }
    /// download
    public static var download: String {
        String(localized: "download", bundle: AppLocalization.shared.bundle)
    }
    /// downloads
    public static var downloads: String {
        String(localized: "downloads", bundle: AppLocalization.shared.bundle)
    }
    /// downloads_empty_label
    public static var downloadsEmptyLabel: String {
        String(localized: "downloads_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// duplicate
    public static var duplicate: String {
        String(localized: "duplicate", bundle: AppLocalization.shared.bundle)
    }
    /// duplicates_empty_label
    public static var duplicatesEmptyLabel: String {
        String(localized: "duplicates_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// edit
    public static var edit: String {
        String(localized: "edit", bundle: AppLocalization.shared.bundle)
    }
    /// elevatedaccess_biometric_auth_confirm_text
    public static var elevatedaccessBiometricAuthConfirmText: String {
        String(localized: "elevatedaccess_biometric_auth_confirm_text", bundle: AppLocalization.shared.bundle)
    }
    /// elevatedaccess_biometric_auth_confirm_title
    public static var elevatedaccessBiometricAuthConfirmTitle: String {
        String(localized: "elevatedaccess_biometric_auth_confirm_title", bundle: AppLocalization.shared.bundle)
    }
    /// elevatedaccess_header_text
    public static var elevatedaccessHeaderText: String {
        String(localized: "elevatedaccess_header_text", bundle: AppLocalization.shared.bundle)
    }
    /// elevatedaccess_header_title
    public static var elevatedaccessHeaderTitle: String {
        String(localized: "elevatedaccess_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// email
    public static var email: String {
        String(localized: "email", bundle: AppLocalization.shared.bundle)
    }
    /// email_action_check_data_breach_title
    public static var emailActionCheckDataBreachTitle: String {
        String(localized: "email_action_check_data_breach_title", bundle: AppLocalization.shared.bundle)
    }
    /// email_not_verified
    public static var emailNotVerified: String {
        String(localized: "email_not_verified", bundle: AppLocalization.shared.bundle)
    }
    /// email_verified
    public static var emailVerified: String {
        String(localized: "email_verified", bundle: AppLocalization.shared.bundle)
    }
    /// email_visibility
    public static var emailVisibility: String {
        String(localized: "email_visibility", bundle: AppLocalization.shared.bundle)
    }
    /// emailleak_breach_found_title
    public static var emailleakBreachFoundTitle: String {
        String(localized: "emailleak_breach_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailleak_breach_not_found_title
    public static var emailleakBreachNotFoundTitle: String {
        String(localized: "emailleak_breach_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailleak_breach_occurred_at
    public static func emailleakBreachOccurredAt(_ a1: String) -> String {
        String(format: String(localized: "emailleak_breach_occurred_at", bundle: AppLocalization.shared.bundle), a1)
    }
    /// emailleak_breach_reported_at
    public static func emailleakBreachReportedAt(_ a1: String) -> String {
        String(format: String(localized: "emailleak_breach_reported_at", bundle: AppLocalization.shared.bundle), a1)
    }
    /// emailleak_breach_section
    public static var emailleakBreachSection: String {
        String(localized: "emailleak_breach_section", bundle: AppLocalization.shared.bundle)
    }
    /// emailleak_failed_no_api_found_text
    public static var emailleakFailedNoApiFoundText: String {
        String(localized: "emailleak_failed_no_api_found_text", bundle: AppLocalization.shared.bundle)
    }
    /// emailleak_failed_to_load_status_text
    public static var emailleakFailedToLoadStatusText: String {
        String(localized: "emailleak_failed_to_load_status_text", bundle: AppLocalization.shared.bundle)
    }
    /// emailleak_note
    public static var emailleakNote: String {
        String(localized: "emailleak_note", bundle: AppLocalization.shared.bundle)
    }
    /// emailleak_title
    public static var emailleakTitle: String {
        String(localized: "emailleak_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_add_action
    public static var emailrelayAddAction: String {
        String(localized: "emailrelay_add_action", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_base_env_note
    public static var emailrelayBaseEnvNote: String {
        String(localized: "emailrelay_base_env_note", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_base_env_server_url_label
    public static var emailrelayBaseEnvServerUrlLabel: String {
        String(localized: "emailrelay_base_env_server_url_label", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_delete_many_confirmation_title
    public static var emailrelayDeleteManyConfirmationTitle: String {
        String(localized: "emailrelay_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_delete_one_confirmation_title
    public static var emailrelayDeleteOneConfirmationTitle: String {
        String(localized: "emailrelay_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_documentation_title
    public static var emailrelayDocumentationTitle: String {
        String(localized: "emailrelay_documentation_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_edit_action
    public static var emailrelayEditAction: String {
        String(localized: "emailrelay_edit_action", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_empty_label
    public static var emailrelayEmptyLabel: String {
        String(localized: "emailrelay_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_integration_title
    public static var emailrelayIntegrationTitle: String {
        String(localized: "emailrelay_integration_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_list_add_service_hint
    public static var emailrelayListAddServiceHint: String {
        String(localized: "emailrelay_list_add_service_hint", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_list_header_title
    public static var emailrelayListHeaderTitle: String {
        String(localized: "emailrelay_list_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_list_no_services_title
    public static var emailrelayListNoServicesTitle: String {
        String(localized: "emailrelay_list_no_services_title", bundle: AppLocalization.shared.bundle)
    }
    /// emailrelay_list_section_title
    public static var emailrelayListSectionTitle: String {
        String(localized: "emailrelay_list_section_title", bundle: AppLocalization.shared.bundle)
    }
    /// empty_value
    public static var emptyValue: String {
        String(localized: "empty_value", bundle: AppLocalization.shared.bundle)
    }
    /// enabled
    public static var enabled: String {
        String(localized: "enabled", bundle: AppLocalization.shared.bundle)
    }
    /// encrypt
    public static var encrypt: String {
        String(localized: "encrypt", bundle: AppLocalization.shared.bundle)
    }
    /// encryption
    public static var encryption: String {
        String(localized: "encryption", bundle: AppLocalization.shared.bundle)
    }
    /// encryption_algorithm_256bit_aes
    public static var encryptionAlgorithm256bitAes: String {
        String(localized: "encryption_algorithm_256bit_aes", bundle: AppLocalization.shared.bundle)
    }
    /// encryption_hash
    public static var encryptionHash: String {
        String(localized: "encryption_hash", bundle: AppLocalization.shared.bundle)
    }
    /// encryption_key
    public static var encryptionKey: String {
        String(localized: "encryption_key", bundle: AppLocalization.shared.bundle)
    }
    /// encryption_random_bits_data
    public static func encryptionRandomBitsData(_ a1: Int) -> String {
        String(format: String(localized: "encryption_random_bits_data", bundle: AppLocalization.shared.bundle), a1)
    }
    /// encryption_salt
    public static var encryptionSalt: String {
        String(localized: "encryption_salt", bundle: AppLocalization.shared.bundle)
    }
    /// equivalent_domains
    public static var equivalentDomains: String {
        String(localized: "equivalent_domains", bundle: AppLocalization.shared.bundle)
    }
    /// equivalent_domains_empty_label
    public static var equivalentDomainsEmptyLabel: String {
        String(localized: "equivalent_domains_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// equivalent_domains_empty_text
    public static var equivalentDomainsEmptyText: String {
        String(localized: "equivalent_domains_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// error_credential_calling_app_not_privileged
    public static var errorCredentialCallingAppNotPrivileged: String {
        String(localized: "error_credential_calling_app_not_privileged", bundle: AppLocalization.shared.bundle)
    }
    /// error_credential_calling_app_not_privileged_add_as_privileged_app_note
    public static var errorCredentialCallingAppNotPrivilegedAddAsPrivilegedAppNote: String {
        String(localized: "error_credential_calling_app_not_privileged_add_as_privileged_app_note", bundle: AppLocalization.shared.bundle)
    }
    /// error_credential_calling_app_not_privileged_add_as_privileged_app_title
    public static var errorCredentialCallingAppNotPrivilegedAddAsPrivilegedAppTitle: String {
        String(localized: "error_credential_calling_app_not_privileged_add_as_privileged_app_title", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_create_passkey
    public static var errorFailedCreatePasskey: String {
        String(localized: "error_failed_create_passkey", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_create_password
    public static var errorFailedCreatePassword: String {
        String(localized: "error_failed_create_password", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_decrypt_biometric_key
    public static var errorFailedDecryptBiometricKey: String {
        String(localized: "error_failed_decrypt_biometric_key", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_encrypt_biometric_key
    public static var errorFailedEncryptBiometricKey: String {
        String(localized: "error_failed_encrypt_biometric_key", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_file_already_exists
    public static var errorFailedFileAlreadyExists: String {
        String(localized: "error_failed_file_already_exists", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_format_placeholder
    public static var errorFailedFormatPlaceholder: String {
        String(localized: "error_failed_format_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_generate_kdf_hash_oom
    public static var errorFailedGenerateKdfHashOom: String {
        String(localized: "error_failed_generate_kdf_hash_oom", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_generate_otp_code
    public static var errorFailedGenerateOtpCode: String {
        String(localized: "error_failed_generate_otp_code", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_gpg_agent_start
    public static var errorFailedGpgAgentStart: String {
        String(localized: "error_failed_gpg_agent_start", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_open_app_for
    public static var errorFailedOpenAppFor: String {
        String(localized: "error_failed_open_app_for", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_open_link
    public static var errorFailedOpenLink: String {
        String(localized: "error_failed_open_link", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_open_uri
    public static var errorFailedOpenUri: String {
        String(localized: "error_failed_open_uri", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_power_lock_start
    public static var errorFailedPowerLockStart: String {
        String(localized: "error_failed_power_lock_start", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_ssh_agent_start
    public static var errorFailedSshAgentStart: String {
        String(localized: "error_failed_ssh_agent_start", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_unknown
    public static var errorFailedUnknown: String {
        String(localized: "error_failed_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_use_passkey
    public static var errorFailedUsePasskey: String {
        String(localized: "error_failed_use_passkey", bundle: AppLocalization.shared.bundle)
    }
    /// error_failed_use_password
    public static var errorFailedUsePassword: String {
        String(localized: "error_failed_use_password", bundle: AppLocalization.shared.bundle)
    }
    /// error_file_must_be_1_mb_or_smaller
    public static var errorFileMustBe1MbOrSmaller: String {
        String(localized: "error_file_must_be_1_mb_or_smaller", bundle: AppLocalization.shared.bundle)
    }
    /// error_file_must_be_500_mb_or_smaller
    public static var errorFileMustBe500MbOrSmaller: String {
        String(localized: "error_file_must_be_500_mb_or_smaller", bundle: AppLocalization.shared.bundle)
    }
    /// error_file_must_be_n_or_smaller
    public static func errorFileMustBeNOrSmaller(_ a1: String) -> String {
        String(format: String(localized: "error_file_must_be_n_or_smaller", bundle: AppLocalization.shared.bundle), a1)
    }
    /// error_incorrect_password
    public static var errorIncorrectPassword: String {
        String(localized: "error_incorrect_password", bundle: AppLocalization.shared.bundle)
    }
    /// error_invalid_card_number
    public static var errorInvalidCardNumber: String {
        String(localized: "error_invalid_card_number", bundle: AppLocalization.shared.bundle)
    }
    /// error_invalid_domain
    public static var errorInvalidDomain: String {
        String(localized: "error_invalid_domain", bundle: AppLocalization.shared.bundle)
    }
    /// error_invalid_email
    public static var errorInvalidEmail: String {
        String(localized: "error_invalid_email", bundle: AppLocalization.shared.bundle)
    }
    /// error_invalid_key
    public static var errorInvalidKey: String {
        String(localized: "error_invalid_key", bundle: AppLocalization.shared.bundle)
    }
    /// error_invalid_regex
    public static var errorInvalidRegex: String {
        String(localized: "error_invalid_regex", bundle: AppLocalization.shared.bundle)
    }
    /// error_invalid_uri
    public static var errorInvalidUri: String {
        String(localized: "error_invalid_uri", bundle: AppLocalization.shared.bundle)
    }
    /// error_invalid_url
    public static var errorInvalidUrl: String {
        String(localized: "error_invalid_url", bundle: AppLocalization.shared.bundle)
    }
    /// error_must_be_custom_domain
    public static var errorMustBeCustomDomain: String {
        String(localized: "error_must_be_custom_domain", bundle: AppLocalization.shared.bundle)
    }
    /// error_must_be_less_than_days
    public static func errorMustBeLessThanDays(_ a1: Int) -> String {
        String(format: String(localized: "error_must_be_less_than_days", bundle: AppLocalization.shared.bundle), a1)
    }
    /// error_must_be_number
    public static var errorMustBeNumber: String {
        String(localized: "error_must_be_number", bundle: AppLocalization.shared.bundle)
    }
    /// error_must_contain_only_us_ascii_chars
    public static var errorMustContainOnlyUsAsciiChars: String {
        String(localized: "error_must_contain_only_us_ascii_chars", bundle: AppLocalization.shared.bundle)
    }
    /// error_must_have_at_least_n_symbols
    public static func errorMustHaveAtLeastNSymbols(_ a1: Int) -> String {
        String(format: String(localized: "error_must_have_at_least_n_symbols", bundle: AppLocalization.shared.bundle), a1)
    }
    /// error_must_have_exactly_n_symbols
    public static func errorMustHaveExactlyNSymbols(_ a1: Int) -> String {
        String(format: String(localized: "error_must_have_exactly_n_symbols", bundle: AppLocalization.shared.bundle), a1)
    }
    /// error_must_not_be_blank
    public static var errorMustNotBeBlank: String {
        String(localized: "error_must_not_be_blank", bundle: AppLocalization.shared.bundle)
    }
    /// error_must_not_be_empty
    public static var errorMustNotBeEmpty: String {
        String(localized: "error_must_not_be_empty", bundle: AppLocalization.shared.bundle)
    }
    /// error_not_found
    public static var errorNotFound: String {
        String(localized: "error_not_found", bundle: AppLocalization.shared.bundle)
    }
    /// error_otp_key_is_invalid
    public static var errorOtpKeyIsInvalid: String {
        String(localized: "error_otp_key_is_invalid", bundle: AppLocalization.shared.bundle)
    }
    /// error_otp_key_must_not_be_empty
    public static var errorOtpKeyMustNotBeEmpty: String {
        String(localized: "error_otp_key_must_not_be_empty", bundle: AppLocalization.shared.bundle)
    }
    /// error_webdav_file_url_required
    public static var errorWebdavFileUrlRequired: String {
        String(localized: "error_webdav_file_url_required", bundle: AppLocalization.shared.bundle)
    }
    /// error_webdav_url_required
    public static var errorWebdavUrlRequired: String {
        String(localized: "error_webdav_url_required", bundle: AppLocalization.shared.bundle)
    }
    /// execute_command
    public static var executeCommand: String {
        String(localized: "execute_command", bundle: AppLocalization.shared.bundle)
    }
    /// expiration_date
    public static var expirationDate: String {
        String(localized: "expiration_date", bundle: AppLocalization.shared.bundle)
    }
    /// expiration_date_custom
    public static var expirationDateCustom: String {
        String(localized: "expiration_date_custom", bundle: AppLocalization.shared.bundle)
    }
    /// expiration_date_never
    public static var expirationDateNever: String {
        String(localized: "expiration_date_never", bundle: AppLocalization.shared.bundle)
    }
    /// expired
    public static var expired: String {
        String(localized: "expired", bundle: AppLocalization.shared.bundle)
    }
    /// expires
    public static var expires: String {
        String(localized: "expires", bundle: AppLocalization.shared.bundle)
    }
    /// expiring_soon
    public static var expiringSoon: String {
        String(localized: "expiring_soon", bundle: AppLocalization.shared.bundle)
    }
    /// expiry_date
    public static var expiryDate: String {
        String(localized: "expiry_date", bundle: AppLocalization.shared.bundle)
    }
    /// expiry_tips_card_line1
    public static func expiryTipsCardLine1(_ a1: String) -> String {
        String(format: String(localized: "expiry_tips_card_line1", bundle: AppLocalization.shared.bundle), a1)
    }
    /// expiry_tips_card_line2
    public static var expiryTipsCardLine2: String {
        String(localized: "expiry_tips_card_line2", bundle: AppLocalization.shared.bundle)
    }
    /// expiry_tips_card_line3
    public static var expiryTipsCardLine3: String {
        String(localized: "expiry_tips_card_line3", bundle: AppLocalization.shared.bundle)
    }
    /// expiry_tips_card_line4
    public static var expiryTipsCardLine4: String {
        String(localized: "expiry_tips_card_line4", bundle: AppLocalization.shared.bundle)
    }
    /// expiry_tips_item_line1
    public static func expiryTipsItemLine1(_ a1: String) -> String {
        String(format: String(localized: "expiry_tips_item_line1", bundle: AppLocalization.shared.bundle), a1)
    }
    /// exportaccount_attachments_note
    public static var exportaccountAttachmentsNote: String {
        String(localized: "exportaccount_attachments_note", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_export_button
    public static var exportaccountExportButton: String {
        String(localized: "exportaccount_export_button", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_export_failure
    public static var exportaccountExportFailure: String {
        String(localized: "exportaccount_export_failure", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_export_started
    public static var exportaccountExportStarted: String {
        String(localized: "exportaccount_export_started", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_export_success
    public static var exportaccountExportSuccess: String {
        String(localized: "exportaccount_export_success", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_format_note
    public static var exportaccountFormatNote: String {
        String(localized: "exportaccount_format_note", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_header_title
    public static var exportaccountHeaderTitle: String {
        String(localized: "exportaccount_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_include_attachments_title
    public static var exportaccountIncludeAttachmentsTitle: String {
        String(localized: "exportaccount_include_attachments_title", bundle: AppLocalization.shared.bundle)
    }
    /// exportaccount_password_label
    public static var exportaccountPasswordLabel: String {
        String(localized: "exportaccount_password_label", bundle: AppLocalization.shared.bundle)
    }
    /// feat_header_title
    public static var featHeaderTitle: String {
        String(localized: "feat_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_duplicate_items_text
    public static var featItemDuplicateItemsText: String {
        String(localized: "feat_item_duplicate_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_duplicate_items_title
    public static var featItemDuplicateItemsTitle: String {
        String(localized: "feat_item_duplicate_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_expiring_items_text
    public static var featItemExpiringItemsText: String {
        String(localized: "feat_item_expiring_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_expiring_items_title
    public static var featItemExpiringItemsTitle: String {
        String(localized: "feat_item_expiring_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_export_text
    public static var featItemExportText: String {
        String(localized: "feat_item_export_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_export_title
    public static var featItemExportTitle: String {
        String(localized: "feat_item_export_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_filter_text
    public static var featItemFilterText: String {
        String(localized: "feat_item_filter_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_filter_title
    public static var featItemFilterTitle: String {
        String(localized: "feat_item_filter_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_generator_text
    public static var featItemGeneratorText: String {
        String(localized: "feat_item_generator_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_generator_title
    public static var featItemGeneratorTitle: String {
        String(localized: "feat_item_generator_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_inactive_totp_text
    public static var featItemInactiveTotpText: String {
        String(localized: "feat_item_inactive_totp_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_inactive_totp_title
    public static var featItemInactiveTotpTitle: String {
        String(localized: "feat_item_inactive_totp_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_incomplete_items_text
    public static var featItemIncompleteItemsText: String {
        String(localized: "feat_item_incomplete_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_incomplete_items_title
    public static var featItemIncompleteItemsTitle: String {
        String(localized: "feat_item_incomplete_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_multi_selection_text
    public static var featItemMultiSelectionText: String {
        String(localized: "feat_item_multi_selection_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_multi_selection_title
    public static var featItemMultiSelectionTitle: String {
        String(localized: "feat_item_multi_selection_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_multiple_accounts_text
    public static var featItemMultipleAccountsText: String {
        String(localized: "feat_item_multiple_accounts_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_multiple_accounts_title
    public static var featItemMultipleAccountsTitle: String {
        String(localized: "feat_item_multiple_accounts_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_multiple_keywords_text
    public static var featItemMultipleKeywordsText: String {
        String(localized: "feat_item_multiple_keywords_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_multiple_keywords_title
    public static var featItemMultipleKeywordsTitle: String {
        String(localized: "feat_item_multiple_keywords_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_offline_editing_text
    public static var featItemOfflineEditingText: String {
        String(localized: "feat_item_offline_editing_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_offline_editing_title
    public static var featItemOfflineEditingTitle: String {
        String(localized: "feat_item_offline_editing_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_password_strength_text
    public static var featItemPasswordStrengthText: String {
        String(localized: "feat_item_password_strength_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_password_strength_title
    public static var featItemPasswordStrengthTitle: String {
        String(localized: "feat_item_password_strength_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_pwned_passwords_text
    public static var featItemPwnedPasswordsText: String {
        String(localized: "feat_item_pwned_passwords_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_pwned_passwords_title
    public static var featItemPwnedPasswordsTitle: String {
        String(localized: "feat_item_pwned_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_reused_passwords_text
    public static var featItemReusedPasswordsText: String {
        String(localized: "feat_item_reused_passwords_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_reused_passwords_title
    public static var featItemReusedPasswordsTitle: String {
        String(localized: "feat_item_reused_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_search_by_anything_text
    public static var featItemSearchByAnythingText: String {
        String(localized: "feat_item_search_by_anything_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_search_by_anything_title
    public static var featItemSearchByAnythingTitle: String {
        String(localized: "feat_item_search_by_anything_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_show_barcode_text
    public static var featItemShowBarcodeText: String {
        String(localized: "feat_item_show_barcode_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_show_barcode_title
    public static var featItemShowBarcodeTitle: String {
        String(localized: "feat_item_show_barcode_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_two_way_sync_text
    public static var featItemTwoWaySyncText: String {
        String(localized: "feat_item_two_way_sync_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_two_way_sync_title
    public static var featItemTwoWaySyncTitle: String {
        String(localized: "feat_item_two_way_sync_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_unsecure_websites_text
    public static var featItemUnsecureWebsitesText: String {
        String(localized: "feat_item_unsecure_websites_text", bundle: AppLocalization.shared.bundle)
    }
    /// feat_item_unsecure_websites_title
    public static var featItemUnsecureWebsitesTitle: String {
        String(localized: "feat_item_unsecure_websites_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_keyguard_premium_label
    public static var featKeyguardPremiumLabel: String {
        String(localized: "feat_keyguard_premium_label", bundle: AppLocalization.shared.bundle)
    }
    /// feat_section_misc_title
    public static var featSectionMiscTitle: String {
        String(localized: "feat_section_misc_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_section_search_title
    public static var featSectionSearchTitle: String {
        String(localized: "feat_section_search_title", bundle: AppLocalization.shared.bundle)
    }
    /// feat_section_watchtower_title
    public static var featSectionWatchtowerTitle: String {
        String(localized: "feat_section_watchtower_title", bundle: AppLocalization.shared.bundle)
    }
    /// february
    public static var february: String {
        String(localized: "february", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_error_invalid_pin
    public static var fido2ErrorInvalidPin: String {
        String(localized: "fido2_error_invalid_pin", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_error_pin_blocked
    public static var fido2ErrorPinBlocked: String {
        String(localized: "fido2_error_pin_blocked", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_error_pin_not_set
    public static var fido2ErrorPinNotSet: String {
        String(localized: "fido2_error_pin_not_set", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_error_retry
    public static var fido2ErrorRetry: String {
        String(localized: "fido2_error_retry", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_error_timeout
    public static var fido2ErrorTimeout: String {
        String(localized: "fido2_error_timeout", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_error_title
    public static var fido2ErrorTitle: String {
        String(localized: "fido2_error_title", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_error_unsupported
    public static var fido2ErrorUnsupported: String {
        String(localized: "fido2_error_unsupported", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_pin_label
    public static var fido2PinLabel: String {
        String(localized: "fido2_pin_label", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_pin_prompt
    public static var fido2PinPrompt: String {
        String(localized: "fido2_pin_prompt", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_touch_prompt
    public static var fido2TouchPrompt: String {
        String(localized: "fido2_touch_prompt", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_unlock_description
    public static var fido2UnlockDescription: String {
        String(localized: "fido2_unlock_description", bundle: AppLocalization.shared.bundle)
    }
    /// fido2_unlock_title
    public static var fido2UnlockTitle: String {
        String(localized: "fido2_unlock_title", bundle: AppLocalization.shared.bundle)
    }
    /// fido2webauthn_action_go_title
    public static var fido2webauthnActionGoTitle: String {
        String(localized: "fido2webauthn_action_go_title", bundle: AppLocalization.shared.bundle)
    }
    /// fido2webauthn_action_return_title
    public static var fido2webauthnActionReturnTitle: String {
        String(localized: "fido2webauthn_action_return_title", bundle: AppLocalization.shared.bundle)
    }
    /// fido2webauthn_bitwarden_web_vault_version_warning_note
    public static func fido2webauthnBitwardenWebVaultVersionWarningNote(_ a1: String) -> String {
        String(format: String(localized: "fido2webauthn_bitwarden_web_vault_version_warning_note", bundle: AppLocalization.shared.bundle), a1)
    }
    /// fido2webauthn_web_title
    public static var fido2webauthnWebTitle: String {
        String(localized: "fido2webauthn_web_title", bundle: AppLocalization.shared.bundle)
    }
    /// field_label
    public static var fieldLabel: String {
        String(localized: "field_label", bundle: AppLocalization.shared.bundle)
    }
    /// field_linked_to_card_brand
    public static func fieldLinkedToCardBrand(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_card_brand", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_card_cardholdername
    public static func fieldLinkedToCardCardholdername(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_card_cardholdername", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_card_code
    public static func fieldLinkedToCardCode(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_card_code", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_card_expmonth
    public static func fieldLinkedToCardExpmonth(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_card_expmonth", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_card_expyear
    public static func fieldLinkedToCardExpyear(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_card_expyear", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_card_number
    public static func fieldLinkedToCardNumber(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_card_number", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_address1
    public static func fieldLinkedToIdentityAddress1(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_address1", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_address2
    public static func fieldLinkedToIdentityAddress2(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_address2", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_address3
    public static func fieldLinkedToIdentityAddress3(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_address3", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_city
    public static func fieldLinkedToIdentityCity(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_city", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_company
    public static func fieldLinkedToIdentityCompany(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_company", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_country
    public static func fieldLinkedToIdentityCountry(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_country", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_email
    public static func fieldLinkedToIdentityEmail(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_email", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_firstname
    public static func fieldLinkedToIdentityFirstname(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_firstname", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_fullname
    public static func fieldLinkedToIdentityFullname(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_fullname", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_lastname
    public static func fieldLinkedToIdentityLastname(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_lastname", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_licensenumber
    public static func fieldLinkedToIdentityLicensenumber(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_licensenumber", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_middlename
    public static func fieldLinkedToIdentityMiddlename(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_middlename", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_passportnumber
    public static func fieldLinkedToIdentityPassportnumber(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_passportnumber", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_phone
    public static func fieldLinkedToIdentityPhone(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_phone", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_postalcode
    public static func fieldLinkedToIdentityPostalcode(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_postalcode", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_ssn
    public static func fieldLinkedToIdentitySsn(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_ssn", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_state
    public static func fieldLinkedToIdentityState(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_state", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_title
    public static func fieldLinkedToIdentityTitle(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_title", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_identity_username
    public static func fieldLinkedToIdentityUsername(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_identity_username", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_password
    public static func fieldLinkedToPassword(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_password", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_unknown_field
    public static func fieldLinkedToUnknownField(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_unknown_field", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_linked_to_username
    public static func fieldLinkedToUsername(_ a1: String) -> String {
        String(format: String(localized: "field_linked_to_username", bundle: AppLocalization.shared.bundle), a1)
    }
    /// field_type_boolean
    public static var fieldTypeBoolean: String {
        String(localized: "field_type_boolean", bundle: AppLocalization.shared.bundle)
    }
    /// field_type_linked
    public static var fieldTypeLinked: String {
        String(localized: "field_type_linked", bundle: AppLocalization.shared.bundle)
    }
    /// field_type_text
    public static var fieldTypeText: String {
        String(localized: "field_type_text", bundle: AppLocalization.shared.bundle)
    }
    /// field_type_text_concealed
    public static var fieldTypeTextConcealed: String {
        String(localized: "field_type_text_concealed", bundle: AppLocalization.shared.bundle)
    }
    /// field_value
    public static var fieldValue: String {
        String(localized: "field_value", bundle: AppLocalization.shared.bundle)
    }
    /// file
    public static var file: String {
        String(localized: "file", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_delete_local_many_title
    public static var fileActionDeleteLocalManyTitle: String {
        String(localized: "file_action_delete_local_many_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_delete_local_title
    public static var fileActionDeleteLocalTitle: String {
        String(localized: "file_action_delete_local_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_open_in_file_manager_title
    public static var fileActionOpenInFileManagerTitle: String {
        String(localized: "file_action_open_in_file_manager_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_open_with_title
    public static var fileActionOpenWithTitle: String {
        String(localized: "file_action_open_with_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_preview_title
    public static var fileActionPreviewTitle: String {
        String(localized: "file_action_preview_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_reveal_title
    public static var fileActionRevealTitle: String {
        String(localized: "file_action_reveal_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_send_with_title
    public static var fileActionSendWithTitle: String {
        String(localized: "file_action_send_with_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_action_view_cipher_title
    public static var fileActionViewCipherTitle: String {
        String(localized: "file_action_view_cipher_title", bundle: AppLocalization.shared.bundle)
    }
    /// file_name_placeholder
    public static var fileNamePlaceholder: String {
        String(localized: "file_name_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// file_picker_selected_file_label
    public static var filePickerSelectedFileLabel: String {
        String(localized: "file_picker_selected_file_label", bundle: AppLocalization.shared.bundle)
    }
    /// file_status_auto_resume
    public static var fileStatusAutoResume: String {
        String(localized: "file_status_auto_resume", bundle: AppLocalization.shared.bundle)
    }
    /// file_status_download_failed_auto_resuming
    public static var fileStatusDownloadFailedAutoResuming: String {
        String(localized: "file_status_download_failed_auto_resuming", bundle: AppLocalization.shared.bundle)
    }
    /// file_status_downloaded
    public static var fileStatusDownloaded: String {
        String(localized: "file_status_downloaded", bundle: AppLocalization.shared.bundle)
    }
    /// file_status_downloading_failed
    public static var fileStatusDownloadingFailed: String {
        String(localized: "file_status_downloading_failed", bundle: AppLocalization.shared.bundle)
    }
    /// file_status_pending_upload
    public static var fileStatusPendingUpload: String {
        String(localized: "file_status_pending_upload", bundle: AppLocalization.shared.bundle)
    }
    /// filter_auth_reprompt_items
    public static var filterAuthRepromptItems: String {
        String(localized: "filter_auth_reprompt_items", bundle: AppLocalization.shared.bundle)
    }
    /// filter_clear_action
    public static var filterClearAction: String {
        String(localized: "filter_clear_action", bundle: AppLocalization.shared.bundle)
    }
    /// filter_collapse_named_action
    public static func filterCollapseNamedAction(_ a1: String) -> String {
        String(format: String(localized: "filter_collapse_named_action", bundle: AppLocalization.shared.bundle), a1)
    }
    /// filter_empty_label
    public static var filterEmptyLabel: String {
        String(localized: "filter_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// filter_expand_named_action
    public static func filterExpandNamedAction(_ a1: String) -> String {
        String(format: String(localized: "filter_expand_named_action", bundle: AppLocalization.shared.bundle), a1)
    }
    /// filter_failed_items
    public static var filterFailedItems: String {
        String(localized: "filter_failed_items", bundle: AppLocalization.shared.bundle)
    }
    /// filter_header_title
    public static var filterHeaderTitle: String {
        String(localized: "filter_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// filter_list_title
    public static var filterListTitle: String {
        String(localized: "filter_list_title", bundle: AppLocalization.shared.bundle)
    }
    /// filter_pending_items
    public static var filterPendingItems: String {
        String(localized: "filter_pending_items", bundle: AppLocalization.shared.bundle)
    }
    /// fingerprint
    public static var fingerprint: String {
        String(localized: "fingerprint", bundle: AppLocalization.shared.bundle)
    }
    /// fingerprint_phrase
    public static var fingerprintPhrase: String {
        String(localized: "fingerprint_phrase", bundle: AppLocalization.shared.bundle)
    }
    /// fingerprint_phrase_help_title
    public static var fingerprintPhraseHelpTitle: String {
        String(localized: "fingerprint_phrase_help_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder
    public static var folder: String {
        String(localized: "folder", bundle: AppLocalization.shared.bundle)
    }
    /// folder_action_change_name_title
    public static var folderActionChangeNameTitle: String {
        String(localized: "folder_action_change_name_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder_action_change_names_title
    public static var folderActionChangeNamesTitle: String {
        String(localized: "folder_action_change_names_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder_action_create_title
    public static var folderActionCreateTitle: String {
        String(localized: "folder_action_create_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder_action_merge_confirmation_title
    public static var folderActionMergeConfirmationTitle: String {
        String(localized: "folder_action_merge_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder_action_merge_title
    public static var folderActionMergeTitle: String {
        String(localized: "folder_action_merge_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder_add_action
    public static var folderAddAction: String {
        String(localized: "folder_add_action", bundle: AppLocalization.shared.bundle)
    }
    /// folder_delete_confirmation_text
    public static var folderDeleteConfirmationText: String {
        String(localized: "folder_delete_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// folder_delete_many_confirmation_title
    public static var folderDeleteManyConfirmationTitle: String {
        String(localized: "folder_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder_delete_one_confirmation_title
    public static var folderDeleteOneConfirmationTitle: String {
        String(localized: "folder_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// folder_new
    public static var folderNew: String {
        String(localized: "folder_new", bundle: AppLocalization.shared.bundle)
    }
    /// folder_none
    public static var folderNone: String {
        String(localized: "folder_none", bundle: AppLocalization.shared.bundle)
    }
    /// folderpicker_create_new_folder
    public static var folderpickerCreateNewFolder: String {
        String(localized: "folderpicker_create_new_folder", bundle: AppLocalization.shared.bundle)
    }
    /// folderpicker_header_title
    public static var folderpickerHeaderTitle: String {
        String(localized: "folderpicker_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// folders
    public static var folders: String {
        String(localized: "folders", bundle: AppLocalization.shared.bundle)
    }
    /// folders_empty_label
    public static var foldersEmptyLabel: String {
        String(localized: "folders_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// folders_empty_text
    public static var foldersEmptyText: String {
        String(localized: "folders_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// follow_system_settings
    public static var followSystemSettings: String {
        String(localized: "follow_system_settings", bundle: AppLocalization.shared.bundle)
    }
    /// font_atkinson_hyperlegible_text
    public static var fontAtkinsonHyperlegibleText: String {
        String(localized: "font_atkinson_hyperlegible_text", bundle: AppLocalization.shared.bundle)
    }
    /// found_otp_code
    public static var foundOtpCode: String {
        String(localized: "found_otp_code", bundle: AppLocalization.shared.bundle)
    }
    /// found_value
    public static var foundValue: String {
        String(localized: "found_value", bundle: AppLocalization.shared.bundle)
    }
    /// generator_create_item_with_gpg_key_title
    public static var generatorCreateItemWithGpgKeyTitle: String {
        String(localized: "generator_create_item_with_gpg_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_create_item_with_password_title
    public static var generatorCreateItemWithPasswordTitle: String {
        String(localized: "generator_create_item_with_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_create_item_with_ssh_key_title
    public static var generatorCreateItemWithSshKeyTitle: String {
        String(localized: "generator_create_item_with_ssh_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_create_item_with_username_title
    public static var generatorCreateItemWithUsernameTitle: String {
        String(localized: "generator_create_item_with_username_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_catch_all_domain_title
    public static var generatorEmailCatchAllDomainTitle: String {
        String(localized: "generator_email_catch_all_domain_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_catch_all_note
    public static var generatorEmailCatchAllNote: String {
        String(localized: "generator_email_catch_all_note", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_catch_all_type
    public static var generatorEmailCatchAllType: String {
        String(localized: "generator_email_catch_all_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_forward_alias_type
    public static var generatorEmailForwardAliasType: String {
        String(localized: "generator_email_forward_alias_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_plus_addressing_email_title
    public static var generatorEmailPlusAddressingEmailTitle: String {
        String(localized: "generator_email_plus_addressing_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_plus_addressing_note
    public static func generatorEmailPlusAddressingNote(_ a1: String) -> String {
        String(format: String(localized: "generator_email_plus_addressing_note", bundle: AppLocalization.shared.bundle), a1)
    }
    /// generator_email_plus_addressing_type
    public static var generatorEmailPlusAddressingType: String {
        String(localized: "generator_email_plus_addressing_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_subdomain_addressing_email_title
    public static var generatorEmailSubdomainAddressingEmailTitle: String {
        String(localized: "generator_email_subdomain_addressing_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_email_subdomain_addressing_note
    public static func generatorEmailSubdomainAddressingNote(_ a1: String) -> String {
        String(format: String(localized: "generator_email_subdomain_addressing_note", bundle: AppLocalization.shared.bundle), a1)
    }
    /// generator_email_subdomain_addressing_type
    public static var generatorEmailSubdomainAddressingType: String {
        String(localized: "generator_email_subdomain_addressing_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_generate_button
    public static var generatorGenerateButton: String {
        String(localized: "generator_generate_button", bundle: AppLocalization.shared.bundle)
    }
    /// generator_gpg_key_email_title
    public static var generatorGpgKeyEmailTitle: String {
        String(localized: "generator_gpg_key_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_gpg_key_modern_note
    public static var generatorGpgKeyModernNote: String {
        String(localized: "generator_gpg_key_modern_note", bundle: AppLocalization.shared.bundle)
    }
    /// generator_gpg_key_modern_text
    public static var generatorGpgKeyModernText: String {
        String(localized: "generator_gpg_key_modern_text", bundle: AppLocalization.shared.bundle)
    }
    /// generator_gpg_key_name_title
    public static var generatorGpgKeyNameTitle: String {
        String(localized: "generator_gpg_key_name_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_gpg_key_rsa_note
    public static var generatorGpgKeyRsaNote: String {
        String(localized: "generator_gpg_key_rsa_note", bundle: AppLocalization.shared.bundle)
    }
    /// generator_header_gpg_key_title
    public static var generatorHeaderGpgKeyTitle: String {
        String(localized: "generator_header_gpg_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_header_password_title
    public static var generatorHeaderPasswordTitle: String {
        String(localized: "generator_header_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_header_ssh_key_title
    public static var generatorHeaderSshKeyTitle: String {
        String(localized: "generator_header_ssh_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_header_title
    public static var generatorHeaderTitle: String {
        String(localized: "generator_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_header_username_title
    public static var generatorHeaderUsernameTitle: String {
        String(localized: "generator_header_username_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_key_ed25519_note
    public static var generatorKeyEd25519Note: String {
        String(localized: "generator_key_ed25519_note", bundle: AppLocalization.shared.bundle)
    }
    /// generator_key_ed25519_text
    public static var generatorKeyEd25519Text: String {
        String(localized: "generator_key_ed25519_text", bundle: AppLocalization.shared.bundle)
    }
    /// generator_key_length_item
    public static func generatorKeyLengthItem(_ a1: String) -> String {
        String(format: String(localized: "generator_key_length_item", bundle: AppLocalization.shared.bundle), a1)
    }
    /// generator_key_length_title
    public static var generatorKeyLengthTitle: String {
        String(localized: "generator_key_length_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_key_rsa_note
    public static var generatorKeyRsaNote: String {
        String(localized: "generator_key_rsa_note", bundle: AppLocalization.shared.bundle)
    }
    /// generator_key_rsa_text
    public static var generatorKeyRsaText: String {
        String(localized: "generator_key_rsa_text", bundle: AppLocalization.shared.bundle)
    }
    /// generator_passphrase_capitalize_title
    public static var generatorPassphraseCapitalizeTitle: String {
        String(localized: "generator_passphrase_capitalize_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_passphrase_delimiter_title
    public static var generatorPassphraseDelimiterTitle: String {
        String(localized: "generator_passphrase_delimiter_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_passphrase_note
    public static var generatorPassphraseNote: String {
        String(localized: "generator_passphrase_note", bundle: AppLocalization.shared.bundle)
    }
    /// generator_passphrase_number_title
    public static var generatorPassphraseNumberTitle: String {
        String(localized: "generator_passphrase_number_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_passphrase_type
    public static var generatorPassphraseType: String {
        String(localized: "generator_passphrase_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_passphrase_wordlist_title
    public static var generatorPassphraseWordlistTitle: String {
        String(localized: "generator_passphrase_wordlist_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_exclude_ambiguous_symbols_title
    public static var generatorPasswordExcludeAmbiguousSymbolsTitle: String {
        String(localized: "generator_password_exclude_ambiguous_symbols_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_exclude_similar_symbols_title
    public static var generatorPasswordExcludeSimilarSymbolsTitle: String {
        String(localized: "generator_password_exclude_similar_symbols_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_history_title
    public static var generatorPasswordHistoryTitle: String {
        String(localized: "generator_password_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_lowercase_letters_title
    public static var generatorPasswordLowercaseLettersTitle: String {
        String(localized: "generator_password_lowercase_letters_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_note
    public static func generatorPasswordNote(_ a1: Int) -> String {
        String(format: String(localized: "generator_password_note", bundle: AppLocalization.shared.bundle), a1)
    }
    /// generator_password_numbers_title
    public static var generatorPasswordNumbersTitle: String {
        String(localized: "generator_password_numbers_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_symbols_title
    public static var generatorPasswordSymbolsTitle: String {
        String(localized: "generator_password_symbols_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_type
    public static var generatorPasswordType: String {
        String(localized: "generator_password_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_password_uppercase_letters_title
    public static var generatorPasswordUppercaseLettersTitle: String {
        String(localized: "generator_password_uppercase_letters_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_pin_code_note
    public static var generatorPinCodeNote: String {
        String(localized: "generator_pin_code_note", bundle: AppLocalization.shared.bundle)
    }
    /// generator_pin_code_type
    public static var generatorPinCodeType: String {
        String(localized: "generator_pin_code_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_regenerate_button
    public static var generatorRegenerateButton: String {
        String(localized: "generator_regenerate_button", bundle: AppLocalization.shared.bundle)
    }
    /// generator_show_tips_title
    public static var generatorShowTipsTitle: String {
        String(localized: "generator_show_tips_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_suggestions_title
    public static var generatorSuggestionsTitle: String {
        String(localized: "generator_suggestions_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_tools_header_title
    public static var generatorToolsHeaderTitle: String {
        String(localized: "generator_tools_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_use_button
    public static var generatorUseButton: String {
        String(localized: "generator_use_button", bundle: AppLocalization.shared.bundle)
    }
    /// generator_username_capitalize_title
    public static var generatorUsernameCapitalizeTitle: String {
        String(localized: "generator_username_capitalize_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_username_custom_word_title
    public static var generatorUsernameCustomWordTitle: String {
        String(localized: "generator_username_custom_word_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_username_delimiter_title
    public static var generatorUsernameDelimiterTitle: String {
        String(localized: "generator_username_delimiter_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_username_number_title
    public static var generatorUsernameNumberTitle: String {
        String(localized: "generator_username_number_title", bundle: AppLocalization.shared.bundle)
    }
    /// generator_username_type
    public static var generatorUsernameType: String {
        String(localized: "generator_username_type", bundle: AppLocalization.shared.bundle)
    }
    /// generator_username_wordlist_title
    public static var generatorUsernameWordlistTitle: String {
        String(localized: "generator_username_wordlist_title", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_clear_history_confirmation_text
    public static var generatorhistoryClearHistoryConfirmationText: String {
        String(localized: "generatorhistory_clear_history_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_clear_history_confirmation_title
    public static var generatorhistoryClearHistoryConfirmationTitle: String {
        String(localized: "generatorhistory_clear_history_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_clear_history_title
    public static var generatorhistoryClearHistoryTitle: String {
        String(localized: "generatorhistory_clear_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_delete_many_confirmation_title
    public static var generatorhistoryDeleteManyConfirmationTitle: String {
        String(localized: "generatorhistory_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_delete_one_confirmation_title
    public static var generatorhistoryDeleteOneConfirmationTitle: String {
        String(localized: "generatorhistory_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_empty_label
    public static var generatorhistoryEmptyLabel: String {
        String(localized: "generatorhistory_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_empty_text
    public static var generatorhistoryEmptyText: String {
        String(localized: "generatorhistory_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// generatorhistory_header_title
    public static var generatorhistoryHeaderTitle: String {
        String(localized: "generatorhistory_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// generic_name
    public static var genericName: String {
        String(localized: "generic_name", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent
    public static var gpgAgent: String {
        String(localized: "gpg_agent", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_filters_header_title
    public static var gpgAgentFiltersHeaderTitle: String {
        String(localized: "gpg_agent_filters_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_filters_note_save_to_apply
    public static var gpgAgentFiltersNoteSaveToApply: String {
        String(localized: "gpg_agent_filters_note_save_to_apply", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_clear_history_confirmation_text
    public static var gpgAgentHistoryClearHistoryConfirmationText: String {
        String(localized: "gpg_agent_history_clear_history_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_clear_history_confirmation_title
    public static var gpgAgentHistoryClearHistoryConfirmationTitle: String {
        String(localized: "gpg_agent_history_clear_history_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_clear_history_title
    public static var gpgAgentHistoryClearHistoryTitle: String {
        String(localized: "gpg_agent_history_clear_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_header_title
    public static var gpgAgentHistoryHeaderTitle: String {
        String(localized: "gpg_agent_history_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_request_decrypt
    public static var gpgAgentHistoryRequestDecrypt: String {
        String(localized: "gpg_agent_history_request_decrypt", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_request_list_keys
    public static var gpgAgentHistoryRequestListKeys: String {
        String(localized: "gpg_agent_history_request_list_keys", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_request_sign_hash
    public static var gpgAgentHistoryRequestSignHash: String {
        String(localized: "gpg_agent_history_request_sign_hash", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_response_failure
    public static var gpgAgentHistoryResponseFailure: String {
        String(localized: "gpg_agent_history_response_failure", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_response_key_not_found
    public static var gpgAgentHistoryResponseKeyNotFound: String {
        String(localized: "gpg_agent_history_response_key_not_found", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_response_success
    public static var gpgAgentHistoryResponseSuccess: String {
        String(localized: "gpg_agent_history_response_success", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_response_unsupported
    public static var gpgAgentHistoryResponseUnsupported: String {
        String(localized: "gpg_agent_history_response_unsupported", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_response_user_denied
    public static var gpgAgentHistoryResponseUserDenied: String {
        String(localized: "gpg_agent_history_response_user_denied", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_response_vault_locked
    public static var gpgAgentHistoryResponseVaultLocked: String {
        String(localized: "gpg_agent_history_response_vault_locked", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_unknown_caller
    public static var gpgAgentHistoryUnknownCaller: String {
        String(localized: "gpg_agent_history_unknown_caller", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_history_unknown_key
    public static var gpgAgentHistoryUnknownKey: String {
        String(localized: "gpg_agent_history_unknown_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_request_approval_decrypt_message_known_app
    public static func gpgAgentRequestApprovalDecryptMessageKnownApp(_ a1: String) -> String {
        String(format: String(localized: "gpg_agent_request_approval_decrypt_message_known_app", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_agent_request_approval_decrypt_message_unknown_app
    public static var gpgAgentRequestApprovalDecryptMessageUnknownApp: String {
        String(localized: "gpg_agent_request_approval_decrypt_message_unknown_app", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_request_approval_decrypt_title
    public static var gpgAgentRequestApprovalDecryptTitle: String {
        String(localized: "gpg_agent_request_approval_decrypt_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_request_approval_sign_message_known_app
    public static func gpgAgentRequestApprovalSignMessageKnownApp(_ a1: String) -> String {
        String(format: String(localized: "gpg_agent_request_approval_sign_message_known_app", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_agent_request_approval_sign_message_unknown_app
    public static var gpgAgentRequestApprovalSignMessageUnknownApp: String {
        String(localized: "gpg_agent_request_approval_sign_message_unknown_app", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_request_approval_sign_title
    public static var gpgAgentRequestApprovalSignTitle: String {
        String(localized: "gpg_agent_request_approval_sign_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_header_title
    public static var gpgAgentSetupHeaderTitle: String {
        String(localized: "gpg_agent_setup_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_intro
    public static var gpgAgentSetupIntro: String {
        String(localized: "gpg_agent_setup_intro", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_macos_step_2_text
    public static var gpgAgentSetupMacosStep2Text: String {
        String(localized: "gpg_agent_setup_macos_step_2_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_1_text
    public static var gpgAgentSetupStep1Text: String {
        String(localized: "gpg_agent_setup_step_1_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_1_title
    public static var gpgAgentSetupStep1Title: String {
        String(localized: "gpg_agent_setup_step_1_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_2_text
    public static var gpgAgentSetupStep2Text: String {
        String(localized: "gpg_agent_setup_step_2_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_2_title
    public static var gpgAgentSetupStep2Title: String {
        String(localized: "gpg_agent_setup_step_2_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_3_text
    public static var gpgAgentSetupStep3Text: String {
        String(localized: "gpg_agent_setup_step_3_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_3_title
    public static var gpgAgentSetupStep3Title: String {
        String(localized: "gpg_agent_setup_step_3_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_4_text
    public static var gpgAgentSetupStep4Text: String {
        String(localized: "gpg_agent_setup_step_4_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_4_title
    public static var gpgAgentSetupStep4Title: String {
        String(localized: "gpg_agent_setup_step_4_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_5_text
    public static var gpgAgentSetupStep5Text: String {
        String(localized: "gpg_agent_setup_step_5_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_step_5_title
    public static var gpgAgentSetupStep5Title: String {
        String(localized: "gpg_agent_setup_step_5_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_unsupported_text
    public static var gpgAgentSetupUnsupportedText: String {
        String(localized: "gpg_agent_setup_unsupported_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_agent_setup_windows_native_gnupg_note
    public static var gpgAgentSetupWindowsNativeGnupgNote: String {
        String(localized: "gpg_agent_setup_windows_native_gnupg_note", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_client_request
    public static var gpgClientRequest: String {
        String(localized: "gpg_client_request", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_action_save_public_key_saved_downloads_success_title
    public static var gpgKeyActionSavePublicKeySavedDownloadsSuccessTitle: String {
        String(localized: "gpg_key_action_save_public_key_saved_downloads_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_action_save_public_key_title
    public static var gpgKeyActionSavePublicKeyTitle: String {
        String(localized: "gpg_key_action_save_public_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_action_save_unencrypted_keys_saved_downloads_success_title
    public static var gpgKeyActionSaveUnencryptedKeysSavedDownloadsSuccessTitle: String {
        String(localized: "gpg_key_action_save_unencrypted_keys_saved_downloads_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_action_save_unencrypted_keys_title
    public static var gpgKeyActionSaveUnencryptedKeysTitle: String {
        String(localized: "gpg_key_action_save_unencrypted_keys_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_action_save_unencrypted_private_key_saved_downloads_success_title
    public static var gpgKeyActionSaveUnencryptedPrivateKeySavedDownloadsSuccessTitle: String {
        String(localized: "gpg_key_action_save_unencrypted_private_key_saved_downloads_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_action_save_unencrypted_private_key_title
    public static var gpgKeyActionSaveUnencryptedPrivateKeyTitle: String {
        String(localized: "gpg_key_action_save_unencrypted_private_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_capability_encrypt_decrypt
    public static var gpgKeyCapabilityEncryptDecrypt: String {
        String(localized: "gpg_key_capability_encrypt_decrypt", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_does_not_expire
    public static var gpgKeyDoesNotExpire: String {
        String(localized: "gpg_key_does_not_expire", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_after_primary_message
    public static var gpgKeyExpiryAfterPrimaryMessage: String {
        String(localized: "gpg_key_expiry_after_primary_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_capability_encrypt
    public static var gpgKeyExpiryCapabilityEncrypt: String {
        String(localized: "gpg_key_expiry_capability_encrypt", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_capability_sign
    public static var gpgKeyExpiryCapabilitySign: String {
        String(localized: "gpg_key_expiry_capability_sign", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_cipher_success_message
    public static var gpgKeyExpiryCipherSuccessMessage: String {
        String(localized: "gpg_key_expiry_cipher_success_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_dialog_message
    public static var gpgKeyExpiryDialogMessage: String {
        String(localized: "gpg_key_expiry_dialog_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_dialog_title
    public static var gpgKeyExpiryDialogTitle: String {
        String(localized: "gpg_key_expiry_dialog_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_failed_message
    public static var gpgKeyExpiryFailedMessage: String {
        String(localized: "gpg_key_expiry_failed_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_failed_title
    public static var gpgKeyExpiryFailedTitle: String {
        String(localized: "gpg_key_expiry_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_invalid_message
    public static var gpgKeyExpiryInvalidMessage: String {
        String(localized: "gpg_key_expiry_invalid_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_key_unreadable
    public static var gpgKeyExpiryKeyUnreadable: String {
        String(localized: "gpg_key_expiry_key_unreadable", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_missing_self_signature_message
    public static var gpgKeyExpiryMissingSelfSignatureMessage: String {
        String(localized: "gpg_key_expiry_missing_self_signature_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_never
    public static var gpgKeyExpiryNever: String {
        String(localized: "gpg_key_expiry_never", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_no_components_message
    public static var gpgKeyExpiryNoComponentsMessage: String {
        String(localized: "gpg_key_expiry_no_components_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_no_explicit_expiry
    public static var gpgKeyExpiryNoExplicitExpiry: String {
        String(localized: "gpg_key_expiry_no_explicit_expiry", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_preset_custom
    public static var gpgKeyExpiryPresetCustom: String {
        String(localized: "gpg_key_expiry_preset_custom", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_preset_five_years
    public static var gpgKeyExpiryPresetFiveYears: String {
        String(localized: "gpg_key_expiry_preset_five_years", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_preset_one_year
    public static var gpgKeyExpiryPresetOneYear: String {
        String(localized: "gpg_key_expiry_preset_one_year", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_preset_two_years
    public static var gpgKeyExpiryPresetTwoYears: String {
        String(localized: "gpg_key_expiry_preset_two_years", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_primary
    public static var gpgKeyExpiryPrimary: String {
        String(localized: "gpg_key_expiry_primary", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_private_key_required
    public static var gpgKeyExpiryPrivateKeyRequired: String {
        String(localized: "gpg_key_expiry_private_key_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_revoked_message
    public static var gpgKeyExpiryRevokedMessage: String {
        String(localized: "gpg_key_expiry_revoked_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_same_as_primary
    public static func gpgKeyExpirySameAsPrimary(_ a1: String) -> String {
        String(format: String(localized: "gpg_key_expiry_same_as_primary", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_key_expiry_subkey
    public static func gpgKeyExpirySubkey(_ a1: String) -> String {
        String(format: String(localized: "gpg_key_expiry_subkey", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_key_expiry_subkey_capabilities
    public static func gpgKeyExpirySubkeyCapabilities(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "gpg_key_expiry_subkey_capabilities", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// gpg_key_expiry_subkey_weak_self_signature
    public static var gpgKeyExpirySubkeyWeakSelfSignature: String {
        String(localized: "gpg_key_expiry_subkey_weak_self_signature", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_success_message
    public static var gpgKeyExpirySuccessMessage: String {
        String(localized: "gpg_key_expiry_success_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_success_title
    public static var gpgKeyExpirySuccessTitle: String {
        String(localized: "gpg_key_expiry_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_title
    public static var gpgKeyExpiryTitle: String {
        String(localized: "gpg_key_expiry_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_unavailable
    public static var gpgKeyExpiryUnavailable: String {
        String(localized: "gpg_key_expiry_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_unknown
    public static var gpgKeyExpiryUnknown: String {
        String(localized: "gpg_key_expiry_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_unresolved_revocation_message
    public static var gpgKeyExpiryUnresolvedRevocationMessage: String {
        String(localized: "gpg_key_expiry_unresolved_revocation_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_expiry_unsupported_signing_hash_message
    public static var gpgKeyExpiryUnsupportedSigningHashMessage: String {
        String(localized: "gpg_key_expiry_unsupported_signing_hash_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_drop_here
    public static var gpgKeyImportDropHere: String {
        String(localized: "gpg_key_import_drop_here", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_empty
    public static var gpgKeyImportErrorEmpty: String {
        String(localized: "gpg_key_import_error_empty", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_existing_malformed
    public static var gpgKeyImportErrorExistingMalformed: String {
        String(localized: "gpg_key_import_error_existing_malformed", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_invalid_passphrase
    public static var gpgKeyImportErrorInvalidPassphrase: String {
        String(localized: "gpg_key_import_error_invalid_passphrase", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_key_changed
    public static var gpgKeyImportErrorKeyChanged: String {
        String(localized: "gpg_key_import_error_key_changed", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_malformed_key
    public static var gpgKeyImportErrorMalformedKey: String {
        String(localized: "gpg_key_import_error_malformed_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_mismatched_key
    public static var gpgKeyImportErrorMismatchedKey: String {
        String(localized: "gpg_key_import_error_mismatched_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_multiple_keys
    public static var gpgKeyImportErrorMultipleKeys: String {
        String(localized: "gpg_key_import_error_multiple_keys", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_passphrase_required
    public static var gpgKeyImportErrorPassphraseRequired: String {
        String(localized: "gpg_key_import_error_passphrase_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_read
    public static var gpgKeyImportErrorRead: String {
        String(localized: "gpg_key_import_error_read", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_reconcile
    public static var gpgKeyImportErrorReconcile: String {
        String(localized: "gpg_key_import_error_reconcile", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_resource_limit
    public static var gpgKeyImportErrorResourceLimit: String {
        String(localized: "gpg_key_import_error_resource_limit", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_secret_conflict
    public static var gpgKeyImportErrorSecretConflict: String {
        String(localized: "gpg_key_import_error_secret_conflict", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_unsupported_format
    public static var gpgKeyImportErrorUnsupportedFormat: String {
        String(localized: "gpg_key_import_error_unsupported_format", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_error_unsupported_platform
    public static var gpgKeyImportErrorUnsupportedPlatform: String {
        String(localized: "gpg_key_import_error_unsupported_platform", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_failed_title
    public static var gpgKeyImportFailedTitle: String {
        String(localized: "gpg_key_import_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_passphrase_dialog_message
    public static func gpgKeyImportPassphraseDialogMessage(_ a1: String) -> String {
        String(format: String(localized: "gpg_key_import_passphrase_dialog_message", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_key_import_passphrase_dialog_title
    public static var gpgKeyImportPassphraseDialogTitle: String {
        String(localized: "gpg_key_import_passphrase_dialog_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_passphrase_hint
    public static var gpgKeyImportPassphraseHint: String {
        String(localized: "gpg_key_import_passphrase_hint", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_passphrase_title
    public static var gpgKeyImportPassphraseTitle: String {
        String(localized: "gpg_key_import_passphrase_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_success_title
    public static var gpgKeyImportSuccessTitle: String {
        String(localized: "gpg_key_import_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_import_title
    public static var gpgKeyImportTitle: String {
        String(localized: "gpg_key_import_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_sec_pub
    public static var gpgKeySecPub: String {
        String(localized: "gpg_key_sec_pub", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_secret_key
    public static var gpgKeySecretKey: String {
        String(localized: "gpg_key_secret_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_status
    public static var gpgKeyStatus: String {
        String(localized: "gpg_key_status", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_status_expired_text
    public static var gpgKeyStatusExpiredText: String {
        String(localized: "gpg_key_status_expired_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_status_revoked_text
    public static var gpgKeyStatusRevokedText: String {
        String(localized: "gpg_key_status_revoked_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_status_unauthenticated_self_signature_text
    public static var gpgKeyStatusUnauthenticatedSelfSignatureText: String {
        String(localized: "gpg_key_status_unauthenticated_self_signature_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_status_unauthenticated_self_signature_title
    public static var gpgKeyStatusUnauthenticatedSelfSignatureTitle: String {
        String(localized: "gpg_key_status_unauthenticated_self_signature_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_status_weak_self_signature_text
    public static var gpgKeyStatusWeakSelfSignatureText: String {
        String(localized: "gpg_key_status_weak_self_signature_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_status_weak_self_signature_title
    public static var gpgKeyStatusWeakSelfSignatureTitle: String {
        String(localized: "gpg_key_status_weak_self_signature_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_subkey_n
    public static func gpgKeySubkeyN(_ a1: Int) -> String {
        String(format: String(localized: "gpg_key_subkey_n", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_key_user_ids
    public static var gpgKeyUserIds: String {
        String(localized: "gpg_key_user_ids", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_version_title
    public static var gpgKeyVersionTitle: String {
        String(localized: "gpg_key_version_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_version_v4
    public static var gpgKeyVersionV4: String {
        String(localized: "gpg_key_version_v4", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_version_v4_note
    public static var gpgKeyVersionV4Note: String {
        String(localized: "gpg_key_version_v4_note", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_version_v6
    public static var gpgKeyVersionV6: String {
        String(localized: "gpg_key_version_v6", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_key_version_v6_note
    public static var gpgKeyVersionV6Note: String {
        String(localized: "gpg_key_version_v6_note", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keys_capability_sign
    public static var gpgKeysCapabilitySign: String {
        String(localized: "gpg_keys_capability_sign", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_refresh_failed_title
    public static var gpgKeyserverRefreshFailedTitle: String {
        String(localized: "gpg_keyserver_refresh_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_refresh_not_found_title
    public static var gpgKeyserverRefreshNotFoundTitle: String {
        String(localized: "gpg_keyserver_refresh_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_refresh_skipped_title
    public static var gpgKeyserverRefreshSkippedTitle: String {
        String(localized: "gpg_keyserver_refresh_skipped_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_refresh_success_title
    public static var gpgKeyserverRefreshSuccessTitle: String {
        String(localized: "gpg_keyserver_refresh_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_empty_label
    public static var gpgKeyserverSearchEmptyLabel: String {
        String(localized: "gpg_keyserver_search_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_empty_query_label
    public static var gpgKeyserverSearchEmptyQueryLabel: String {
        String(localized: "gpg_keyserver_search_empty_query_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_error_text
    public static var gpgKeyserverSearchErrorText: String {
        String(localized: "gpg_keyserver_search_error_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_header_title
    public static var gpgKeyserverSearchHeaderTitle: String {
        String(localized: "gpg_keyserver_search_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_index_label
    public static var gpgKeyserverSearchIndexLabel: String {
        String(localized: "gpg_keyserver_search_index_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_placeholder
    public static var gpgKeyserverSearchPlaceholder: String {
        String(localized: "gpg_keyserver_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_public_key_not_found_title
    public static var gpgKeyserverSearchPublicKeyNotFoundTitle: String {
        String(localized: "gpg_keyserver_search_public_key_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_search_revoked_label
    public static var gpgKeyserverSearchRevokedLabel: String {
        String(localized: "gpg_keyserver_search_revoked_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_status_checked_at
    public static func gpgKeyserverStatusCheckedAt(_ a1: String) -> String {
        String(format: String(localized: "gpg_keyserver_status_checked_at", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_keyserver_status_not_checked
    public static var gpgKeyserverStatusNotChecked: String {
        String(localized: "gpg_keyserver_status_not_checked", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_status_title
    public static var gpgKeyserverStatusTitle: String {
        String(localized: "gpg_keyserver_status_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_upload_success_title
    public static var gpgKeyserverUploadSuccessTitle: String {
        String(localized: "gpg_keyserver_upload_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_upload_verify_already_published_text
    public static var gpgKeyserverUploadVerifyAlreadyPublishedText: String {
        String(localized: "gpg_keyserver_upload_verify_already_published_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_verify_not_found_title
    public static var gpgKeyserverVerifyNotFoundTitle: String {
        String(localized: "gpg_keyserver_verify_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_verify_revoked_title
    public static var gpgKeyserverVerifyRevokedTitle: String {
        String(localized: "gpg_keyserver_verify_revoked_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_verify_unknown_title
    public static var gpgKeyserverVerifyUnknownTitle: String {
        String(localized: "gpg_keyserver_verify_unknown_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_verify_unverified_title
    public static var gpgKeyserverVerifyUnverifiedTitle: String {
        String(localized: "gpg_keyserver_verify_unverified_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_keyserver_verify_verified_title
    public static var gpgKeyserverVerifyVerifiedTitle: String {
        String(localized: "gpg_keyserver_verify_verified_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_add_recipients
    public static var gpgToolsAddRecipients: String {
        String(localized: "gpg_tools_add_recipients", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_additional_public_keys
    public static var gpgToolsAdditionalPublicKeys: String {
        String(localized: "gpg_tools_additional_public_keys", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_armor_label
    public static var gpgToolsArmorLabel: String {
        String(localized: "gpg_tools_armor_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_check_public_key
    public static var gpgToolsCheckPublicKey: String {
        String(localized: "gpg_tools_check_public_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_choose_recipients
    public static var gpgToolsChooseRecipients: String {
        String(localized: "gpg_tools_choose_recipients", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_choose_signing_key
    public static var gpgToolsChooseSigningKey: String {
        String(localized: "gpg_tools_choose_signing_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_clear_file
    public static var gpgToolsClearFile: String {
        String(localized: "gpg_tools_clear_file", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_decrypt_keys_note
    public static var gpgToolsDecryptKeysNote: String {
        String(localized: "gpg_tools_decrypt_keys_note", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_encrypted_text_label
    public static var gpgToolsEncryptedTextLabel: String {
        String(localized: "gpg_tools_encrypted_text_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_export_failed
    public static var gpgToolsExportFailed: String {
        String(localized: "gpg_tools_export_failed", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_exporting
    public static var gpgToolsExporting: String {
        String(localized: "gpg_tools_exporting", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_file_required
    public static var gpgToolsFileRequired: String {
        String(localized: "gpg_tools_file_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_format_label
    public static var gpgToolsFormatLabel: String {
        String(localized: "gpg_tools_format_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_header_title
    public static var gpgToolsHeaderTitle: String {
        String(localized: "gpg_tools_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_importing
    public static var gpgToolsImporting: String {
        String(localized: "gpg_tools_importing", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_invalid_input
    public static var gpgToolsInvalidInput: String {
        String(localized: "gpg_tools_invalid_input", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_invalid_signature
    public static var gpgToolsInvalidSignature: String {
        String(localized: "gpg_tools_invalid_signature", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_message_label
    public static var gpgToolsMessageLabel: String {
        String(localized: "gpg_tools_message_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_missing_private_key
    public static var gpgToolsMissingPrivateKey: String {
        String(localized: "gpg_tools_missing_private_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_missing_public_key
    public static var gpgToolsMissingPublicKey: String {
        String(localized: "gpg_tools_missing_public_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_mode_cleartext
    public static var gpgToolsModeCleartext: String {
        String(localized: "gpg_tools_mode_cleartext", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_mode_detached
    public static var gpgToolsModeDetached: String {
        String(localized: "gpg_tools_mode_detached", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_mode_inline
    public static var gpgToolsModeInline: String {
        String(localized: "gpg_tools_mode_inline", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_no_gpg_keys
    public static var gpgToolsNoGpgKeys: String {
        String(localized: "gpg_tools_no_gpg_keys", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_no_matching_keys
    public static var gpgToolsNoMatchingKeys: String {
        String(localized: "gpg_tools_no_matching_keys", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_no_recipient
    public static var gpgToolsNoRecipient: String {
        String(localized: "gpg_tools_no_recipient", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_no_signing_key
    public static var gpgToolsNoSigningKey: String {
        String(localized: "gpg_tools_no_signing_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_operation_decrypt_text
    public static var gpgToolsOperationDecryptText: String {
        String(localized: "gpg_tools_operation_decrypt_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_operation_encrypt_text
    public static var gpgToolsOperationEncryptText: String {
        String(localized: "gpg_tools_operation_encrypt_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_operation_sign_text
    public static var gpgToolsOperationSignText: String {
        String(localized: "gpg_tools_operation_sign_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_operation_verify_text
    public static var gpgToolsOperationVerifyText: String {
        String(localized: "gpg_tools_operation_verify_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_pasted_public_key_label
    public static var gpgToolsPastedPublicKeyLabel: String {
        String(localized: "gpg_tools_pasted_public_key_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_pasted_public_key_placeholder
    public static var gpgToolsPastedPublicKeyPlaceholder: String {
        String(localized: "gpg_tools_pasted_public_key_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_public_key_add
    public static var gpgToolsPublicKeyAdd: String {
        String(localized: "gpg_tools_public_key_add", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_public_key_description
    public static var gpgToolsPublicKeyDescription: String {
        String(localized: "gpg_tools_public_key_description", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_public_key_invalid
    public static var gpgToolsPublicKeyInvalid: String {
        String(localized: "gpg_tools_public_key_invalid", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_public_key_not_encryptable
    public static var gpgToolsPublicKeyNotEncryptable: String {
        String(localized: "gpg_tools_public_key_not_encryptable", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_public_key_private
    public static var gpgToolsPublicKeyPrivate: String {
        String(localized: "gpg_tools_public_key_private", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_public_key_title
    public static var gpgToolsPublicKeyTitle: String {
        String(localized: "gpg_tools_public_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_ready_to_save
    public static var gpgToolsReadyToSave: String {
        String(localized: "gpg_tools_ready_to_save", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_run_success
    public static var gpgToolsRunSuccess: String {
        String(localized: "gpg_tools_run_success", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_saved
    public static var gpgToolsSaved: String {
        String(localized: "gpg_tools_saved", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_scope_file
    public static var gpgToolsScopeFile: String {
        String(localized: "gpg_tools_scope_file", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_scope_text
    public static var gpgToolsScopeText: String {
        String(localized: "gpg_tools_scope_text", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_search_keys
    public static var gpgToolsSearchKeys: String {
        String(localized: "gpg_tools_search_keys", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_select_input_file
    public static var gpgToolsSelectInputFile: String {
        String(localized: "gpg_tools_select_input_file", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_select_recipients
    public static var gpgToolsSelectRecipients: String {
        String(localized: "gpg_tools_select_recipients", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_select_signature_file
    public static var gpgToolsSelectSignatureFile: String {
        String(localized: "gpg_tools_select_signature_file", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_select_signing_key
    public static var gpgToolsSelectSigningKey: String {
        String(localized: "gpg_tools_select_signing_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_selection_done
    public static var gpgToolsSelectionDone: String {
        String(localized: "gpg_tools_selection_done", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_sign_encrypted_message
    public static var gpgToolsSignEncryptedMessage: String {
        String(localized: "gpg_tools_sign_encrypted_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_sign_none
    public static var gpgToolsSignNone: String {
        String(localized: "gpg_tools_sign_none", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_sign_with
    public static var gpgToolsSignWith: String {
        String(localized: "gpg_tools_sign_with", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_signature_by
    public static func gpgToolsSignatureBy(_ a1: String) -> String {
        String(format: String(localized: "gpg_tools_signature_by", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_tools_signature_created_at
    public static func gpgToolsSignatureCreatedAt(_ a1: String) -> String {
        String(format: String(localized: "gpg_tools_signature_created_at", bundle: AppLocalization.shared.bundle), a1)
    }
    /// gpg_tools_signature_file_required
    public static var gpgToolsSignatureFileRequired: String {
        String(localized: "gpg_tools_signature_file_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_signature_label
    public static var gpgToolsSignatureLabel: String {
        String(localized: "gpg_tools_signature_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_signature_required
    public static var gpgToolsSignatureRequired: String {
        String(localized: "gpg_tools_signature_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_signed_text_label
    public static var gpgToolsSignedTextLabel: String {
        String(localized: "gpg_tools_signed_text_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_stored_public_keys_note
    public static var gpgToolsStoredPublicKeysNote: String {
        String(localized: "gpg_tools_stored_public_keys_note", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_text_required
    public static var gpgToolsTextRequired: String {
        String(localized: "gpg_tools_text_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_valid_signature
    public static var gpgToolsValidSignature: String {
        String(localized: "gpg_tools_valid_signature", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_warning_elgamal_decryption_key
    public static var gpgToolsWarningElgamalDecryptionKey: String {
        String(localized: "gpg_tools_warning_elgamal_decryption_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_warning_key_expired
    public static var gpgToolsWarningKeyExpired: String {
        String(localized: "gpg_tools_warning_key_expired", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_warning_key_revoked
    public static var gpgToolsWarningKeyRevoked: String {
        String(localized: "gpg_tools_warning_key_revoked", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_warning_policy_conflict
    public static var gpgToolsWarningPolicyConflict: String {
        String(localized: "gpg_tools_warning_policy_conflict", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_warning_signature_expired
    public static var gpgToolsWarningSignatureExpired: String {
        String(localized: "gpg_tools_warning_signature_expired", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_warning_weak_digest
    public static var gpgToolsWarningWeakDigest: String {
        String(localized: "gpg_tools_warning_weak_digest", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_tools_warning_weak_rsa_decryption_key
    public static var gpgToolsWarningWeakRsaDecryptionKey: String {
        String(localized: "gpg_tools_warning_weak_rsa_decryption_key", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_mutation_key_revoked_message
    public static var gpgUserIdMutationKeyRevokedMessage: String {
        String(localized: "gpg_user_id_mutation_key_revoked_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_already_done_message
    public static var gpgUserIdReplacementAlreadyDoneMessage: String {
        String(localized: "gpg_user_id_replacement_already_done_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_confirm_next
    public static var gpgUserIdReplacementConfirmNext: String {
        String(localized: "gpg_user_id_replacement_confirm_next", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_dialog_message
    public static var gpgUserIdReplacementDialogMessage: String {
        String(localized: "gpg_user_id_replacement_dialog_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_dialog_title
    public static var gpgUserIdReplacementDialogTitle: String {
        String(localized: "gpg_user_id_replacement_dialog_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_duplicate_message
    public static var gpgUserIdReplacementDuplicateMessage: String {
        String(localized: "gpg_user_id_replacement_duplicate_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_failed_message
    public static var gpgUserIdReplacementFailedMessage: String {
        String(localized: "gpg_user_id_replacement_failed_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_failed_title
    public static var gpgUserIdReplacementFailedTitle: String {
        String(localized: "gpg_user_id_replacement_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_invalid_message
    public static var gpgUserIdReplacementInvalidMessage: String {
        String(localized: "gpg_user_id_replacement_invalid_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_no_identity_message
    public static var gpgUserIdReplacementNoIdentityMessage: String {
        String(localized: "gpg_user_id_replacement_no_identity_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_private_key_required
    public static var gpgUserIdReplacementPrivateKeyRequired: String {
        String(localized: "gpg_user_id_replacement_private_key_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_retired_message
    public static var gpgUserIdReplacementRetiredMessage: String {
        String(localized: "gpg_user_id_replacement_retired_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_same_identity_message
    public static var gpgUserIdReplacementSameIdentityMessage: String {
        String(localized: "gpg_user_id_replacement_same_identity_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_success_message
    public static var gpgUserIdReplacementSuccessMessage: String {
        String(localized: "gpg_user_id_replacement_success_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_success_title
    public static var gpgUserIdReplacementSuccessTitle: String {
        String(localized: "gpg_user_id_replacement_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_target_missing
    public static var gpgUserIdReplacementTargetMissing: String {
        String(localized: "gpg_user_id_replacement_target_missing", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_title
    public static var gpgUserIdReplacementTitle: String {
        String(localized: "gpg_user_id_replacement_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_unavailable
    public static var gpgUserIdReplacementUnavailable: String {
        String(localized: "gpg_user_id_replacement_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_value_confirm
    public static var gpgUserIdReplacementValueConfirm: String {
        String(localized: "gpg_user_id_replacement_value_confirm", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_value_hint
    public static var gpgUserIdReplacementValueHint: String {
        String(localized: "gpg_user_id_replacement_value_hint", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_value_label
    public static var gpgUserIdReplacementValueLabel: String {
        String(localized: "gpg_user_id_replacement_value_label", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_value_message
    public static var gpgUserIdReplacementValueMessage: String {
        String(localized: "gpg_user_id_replacement_value_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_replacement_value_title
    public static var gpgUserIdReplacementValueTitle: String {
        String(localized: "gpg_user_id_replacement_value_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_already_done_message
    public static var gpgUserIdRevocationAlreadyDoneMessage: String {
        String(localized: "gpg_user_id_revocation_already_done_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_confirm
    public static var gpgUserIdRevocationConfirm: String {
        String(localized: "gpg_user_id_revocation_confirm", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_dialog_message
    public static var gpgUserIdRevocationDialogMessage: String {
        String(localized: "gpg_user_id_revocation_dialog_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_dialog_title
    public static var gpgUserIdRevocationDialogTitle: String {
        String(localized: "gpg_user_id_revocation_dialog_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_failed_message
    public static var gpgUserIdRevocationFailedMessage: String {
        String(localized: "gpg_user_id_revocation_failed_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_failed_title
    public static var gpgUserIdRevocationFailedTitle: String {
        String(localized: "gpg_user_id_revocation_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_last_identity_message
    public static var gpgUserIdRevocationLastIdentityMessage: String {
        String(localized: "gpg_user_id_revocation_last_identity_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_no_identity_message
    public static var gpgUserIdRevocationNoIdentityMessage: String {
        String(localized: "gpg_user_id_revocation_no_identity_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_private_key_required
    public static var gpgUserIdRevocationPrivateKeyRequired: String {
        String(localized: "gpg_user_id_revocation_private_key_required", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_success_message
    public static var gpgUserIdRevocationSuccessMessage: String {
        String(localized: "gpg_user_id_revocation_success_message", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_success_title
    public static var gpgUserIdRevocationSuccessTitle: String {
        String(localized: "gpg_user_id_revocation_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_target_missing
    public static var gpgUserIdRevocationTargetMissing: String {
        String(localized: "gpg_user_id_revocation_target_missing", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_title
    public static var gpgUserIdRevocationTitle: String {
        String(localized: "gpg_user_id_revocation_title", bundle: AppLocalization.shared.bundle)
    }
    /// gpg_user_id_revocation_unavailable
    public static var gpgUserIdRevocationUnavailable: String {
        String(localized: "gpg_user_id_revocation_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// grant_permission
    public static var grantPermission: String {
        String(localized: "grant_permission", bundle: AppLocalization.shared.bundle)
    }
    /// grouping_view_items_action
    public static var groupingViewItemsAction: String {
        String(localized: "grouping_view_items_action", bundle: AppLocalization.shared.bundle)
    }
    /// hidden
    public static var hidden: String {
        String(localized: "hidden", bundle: AppLocalization.shared.bundle)
    }
    /// hide
    public static var hide: String {
        String(localized: "hide", bundle: AppLocalization.shared.bundle)
    }
    /// hide_secure_note
    public static var hideSecureNote: String {
        String(localized: "hide_secure_note", bundle: AppLocalization.shared.bundle)
    }
    /// home_favorites_label
    public static var homeFavoritesLabel: String {
        String(localized: "home_favorites_label", bundle: AppLocalization.shared.bundle)
    }
    /// home_generator_label
    public static var homeGeneratorLabel: String {
        String(localized: "home_generator_label", bundle: AppLocalization.shared.bundle)
    }
    /// home_other_label
    public static var homeOtherLabel: String {
        String(localized: "home_other_label", bundle: AppLocalization.shared.bundle)
    }
    /// home_send_label
    public static var homeSendLabel: String {
        String(localized: "home_send_label", bundle: AppLocalization.shared.bundle)
    }
    /// home_settings_label
    public static var homeSettingsLabel: String {
        String(localized: "home_settings_label", bundle: AppLocalization.shared.bundle)
    }
    /// home_vault_label
    public static var homeVaultLabel: String {
        String(localized: "home_vault_label", bundle: AppLocalization.shared.bundle)
    }
    /// home_watchtower_label
    public static var homeWatchtowerLabel: String {
        String(localized: "home_watchtower_label", bundle: AppLocalization.shared.bundle)
    }
    /// identity_first_name
    public static var identityFirstName: String {
        String(localized: "identity_first_name", bundle: AppLocalization.shared.bundle)
    }
    /// identity_full_name
    public static var identityFullName: String {
        String(localized: "identity_full_name", bundle: AppLocalization.shared.bundle)
    }
    /// identity_last_name
    public static var identityLastName: String {
        String(localized: "identity_last_name", bundle: AppLocalization.shared.bundle)
    }
    /// identity_middle_name
    public static var identityMiddleName: String {
        String(localized: "identity_middle_name", bundle: AppLocalization.shared.bundle)
    }
    /// identity_title
    public static var identityTitle: String {
        String(localized: "identity_title", bundle: AppLocalization.shared.bundle)
    }
    /// ignored_alerts
    public static var ignoredAlerts: String {
        String(localized: "ignored_alerts", bundle: AppLocalization.shared.bundle)
    }
    /// info
    public static var info: String {
        String(localized: "info", bundle: AppLocalization.shared.bundle)
    }
    /// info_dialog_empty_text
    public static var infoDialogEmptyText: String {
        String(localized: "info_dialog_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// input
    public static var input: String {
        String(localized: "input", bundle: AppLocalization.shared.bundle)
    }
    /// instance_service_configuration_text
    public static var instanceServiceConfigurationText: String {
        String(localized: "instance_service_configuration_text", bundle: AppLocalization.shared.bundle)
    }
    /// instance_service_error_text
    public static var instanceServiceErrorText: String {
        String(localized: "instance_service_error_text", bundle: AppLocalization.shared.bundle)
    }
    /// instance_service_error_title
    public static var instanceServiceErrorTitle: String {
        String(localized: "instance_service_error_title", bundle: AppLocalization.shared.bundle)
    }
    /// instance_service_permission_text
    public static var instanceServicePermissionText: String {
        String(localized: "instance_service_permission_text", bundle: AppLocalization.shared.bundle)
    }
    /// instance_service_stopped_text
    public static var instanceServiceStoppedText: String {
        String(localized: "instance_service_stopped_text", bundle: AppLocalization.shared.bundle)
    }
    /// instance_service_timeout_text
    public static var instanceServiceTimeoutText: String {
        String(localized: "instance_service_timeout_text", bundle: AppLocalization.shared.bundle)
    }
    /// instance_service_unavailable_text
    public static var instanceServiceUnavailableText: String {
        String(localized: "instance_service_unavailable_text", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_approval_approve
    public static var ipcApprovalApprove: String {
        String(localized: "ipc_approval_approve", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_approval_auth_reason
    public static var ipcApprovalAuthReason: String {
        String(localized: "ipc_approval_auth_reason", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_approval_deny
    public static var ipcApprovalDeny: String {
        String(localized: "ipc_approval_deny", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_approval_message
    public static func ipcApprovalMessage(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "ipc_approval_message", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// ipc_approval_no_keys
    public static var ipcApprovalNoKeys: String {
        String(localized: "ipc_approval_no_keys", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_approval_no_keys_continue
    public static var ipcApprovalNoKeysContinue: String {
        String(localized: "ipc_approval_no_keys_continue", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_approval_operation
    public static func ipcApprovalOperation(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "ipc_approval_operation", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// ipc_approval_operation_with_registration
    public static func ipcApprovalOperationWithRegistration(_ a1: String) -> String {
        String(format: String(localized: "ipc_approval_operation_with_registration", bundle: AppLocalization.shared.bundle), a1)
    }
    /// ipc_approval_title
    public static var ipcApprovalTitle: String {
        String(localized: "ipc_approval_title", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_approval_unavailable
    public static var ipcApprovalUnavailable: String {
        String(localized: "ipc_approval_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_autocrypt_status
    public static var ipcOperationOpenpgpAutocryptStatus: String {
        String(localized: "ipc_operation_openpgp_autocrypt_status", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_check_permission
    public static var ipcOperationOpenpgpCheckPermission: String {
        String(localized: "ipc_operation_openpgp_check_permission", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_clear_sign
    public static var ipcOperationOpenpgpClearSign: String {
        String(localized: "ipc_operation_openpgp_clear_sign", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_decrypt_metadata
    public static var ipcOperationOpenpgpDecryptMetadata: String {
        String(localized: "ipc_operation_openpgp_decrypt_metadata", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_decrypt_verify
    public static var ipcOperationOpenpgpDecryptVerify: String {
        String(localized: "ipc_operation_openpgp_decrypt_verify", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_detached_sign
    public static var ipcOperationOpenpgpDetachedSign: String {
        String(localized: "ipc_operation_openpgp_detached_sign", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_encrypt
    public static var ipcOperationOpenpgpEncrypt: String {
        String(localized: "ipc_operation_openpgp_encrypt", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_get_key
    public static var ipcOperationOpenpgpGetKey: String {
        String(localized: "ipc_operation_openpgp_get_key", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_get_key_ids
    public static var ipcOperationOpenpgpGetKeyIds: String {
        String(localized: "ipc_operation_openpgp_get_key_ids", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_get_sign_key
    public static var ipcOperationOpenpgpGetSignKey: String {
        String(localized: "ipc_operation_openpgp_get_sign_key", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_other
    public static var ipcOperationOpenpgpOther: String {
        String(localized: "ipc_operation_openpgp_other", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_openpgp_sign_and_encrypt
    public static var ipcOperationOpenpgpSignAndEncrypt: String {
        String(localized: "ipc_operation_openpgp_sign_and_encrypt", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_ssh_get_public_key
    public static var ipcOperationSshGetPublicKey: String {
        String(localized: "ipc_operation_ssh_get_public_key", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_ssh_get_ssh_public_key
    public static var ipcOperationSshGetSshPublicKey: String {
        String(localized: "ipc_operation_ssh_get_ssh_public_key", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_ssh_other
    public static var ipcOperationSshOther: String {
        String(localized: "ipc_operation_ssh_other", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_ssh_select_key
    public static var ipcOperationSshSelectKey: String {
        String(localized: "ipc_operation_ssh_select_key", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_operation_ssh_sign
    public static var ipcOperationSshSign: String {
        String(localized: "ipc_operation_ssh_sign", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_protocol_openpgp
    public static var ipcProtocolOpenpgp: String {
        String(localized: "ipc_protocol_openpgp", bundle: AppLocalization.shared.bundle)
    }
    /// ipc_protocol_ssh
    public static var ipcProtocolSsh: String {
        String(localized: "ipc_protocol_ssh", bundle: AppLocalization.shared.bundle)
    }
    /// item
    public static var item: String {
        String(localized: "item", bundle: AppLocalization.shared.bundle)
    }
    /// item_incomplete_text
    public static var itemIncompleteText: String {
        String(localized: "item_incomplete_text", bundle: AppLocalization.shared.bundle)
    }
    /// item_incomplete_title
    public static var itemIncompleteTitle: String {
        String(localized: "item_incomplete_title", bundle: AppLocalization.shared.bundle)
    }
    /// item_not_found
    public static var itemNotFound: String {
        String(localized: "item_not_found", bundle: AppLocalization.shared.bundle)
    }
    /// items
    public static var items: String {
        String(localized: "items", bundle: AppLocalization.shared.bundle)
    }
    /// items_all
    public static var itemsAll: String {
        String(localized: "items_all", bundle: AppLocalization.shared.bundle)
    }
    /// items_empty_label
    public static var itemsEmptyLabel: String {
        String(localized: "items_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// items_n
    public static func itemsN(_ a1: String) -> String {
        String(format: String(localized: "items_n", bundle: AppLocalization.shared.bundle), a1)
    }
    /// january
    public static var january: String {
        String(localized: "january", bundle: AppLocalization.shared.bundle)
    }
    /// july
    public static var july: String {
        String(localized: "july", bundle: AppLocalization.shared.bundle)
    }
    /// june
    public static var june: String {
        String(localized: "june", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_difficulty_easy_label
    public static var justdeletemeDifficultyEasyLabel: String {
        String(localized: "justdeleteme_difficulty_easy_label", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_difficulty_hard_label
    public static var justdeletemeDifficultyHardLabel: String {
        String(localized: "justdeleteme_difficulty_hard_label", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_difficulty_impossible_label
    public static var justdeletemeDifficultyImpossibleLabel: String {
        String(localized: "justdeleteme_difficulty_impossible_label", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_difficulty_limited_availability_label
    public static var justdeletemeDifficultyLimitedAvailabilityLabel: String {
        String(localized: "justdeleteme_difficulty_limited_availability_label", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_difficulty_medium_label
    public static var justdeletemeDifficultyMediumLabel: String {
        String(localized: "justdeleteme_difficulty_medium_label", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_empty_label
    public static var justdeletemeEmptyLabel: String {
        String(localized: "justdeleteme_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_search_placeholder
    public static var justdeletemeSearchPlaceholder: String {
        String(localized: "justdeleteme_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_send_email_title
    public static var justdeletemeSendEmailTitle: String {
        String(localized: "justdeleteme_send_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_subtitle
    public static var justdeletemeSubtitle: String {
        String(localized: "justdeleteme_subtitle", bundle: AppLocalization.shared.bundle)
    }
    /// justdeleteme_title
    public static var justdeletemeTitle: String {
        String(localized: "justdeleteme_title", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_difficulty_easy_label
    public static var justgetmydataDifficultyEasyLabel: String {
        String(localized: "justgetmydata_difficulty_easy_label", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_difficulty_hard_label
    public static var justgetmydataDifficultyHardLabel: String {
        String(localized: "justgetmydata_difficulty_hard_label", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_difficulty_impossible_label
    public static var justgetmydataDifficultyImpossibleLabel: String {
        String(localized: "justgetmydata_difficulty_impossible_label", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_difficulty_limited_availability_label
    public static var justgetmydataDifficultyLimitedAvailabilityLabel: String {
        String(localized: "justgetmydata_difficulty_limited_availability_label", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_difficulty_medium_label
    public static var justgetmydataDifficultyMediumLabel: String {
        String(localized: "justgetmydata_difficulty_medium_label", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_empty_label
    public static var justgetmydataEmptyLabel: String {
        String(localized: "justgetmydata_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_search_placeholder
    public static var justgetmydataSearchPlaceholder: String {
        String(localized: "justgetmydata_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_send_email_title
    public static var justgetmydataSendEmailTitle: String {
        String(localized: "justgetmydata_send_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_subtitle
    public static var justgetmydataSubtitle: String {
        String(localized: "justgetmydata_subtitle", bundle: AppLocalization.shared.bundle)
    }
    /// justgetmydata_title
    public static var justgetmydataTitle: String {
        String(localized: "justgetmydata_title", bundle: AppLocalization.shared.bundle)
    }
    /// key_gpg
    public static var keyGpg: String {
        String(localized: "key_gpg", bundle: AppLocalization.shared.bundle)
    }
    /// key_gpg_value_placeholder
    public static var keyGpgValuePlaceholder: String {
        String(localized: "key_gpg_value_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// key_id
    public static var keyId: String {
        String(localized: "key_id", bundle: AppLocalization.shared.bundle)
    }
    /// key_pair
    public static var keyPair: String {
        String(localized: "key_pair", bundle: AppLocalization.shared.bundle)
    }
    /// key_ssh
    public static var keySsh: String {
        String(localized: "key_ssh", bundle: AppLocalization.shared.bundle)
    }
    /// key_ssh_value_placeholder
    public static var keySshValuePlaceholder: String {
        String(localized: "key_ssh_value_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// key_type
    public static var keyType: String {
        String(localized: "key_type", bundle: AppLocalization.shared.bundle)
    }
    /// largetype_action_show_in_large_type_and_lock_title
    public static var largetypeActionShowInLargeTypeAndLockTitle: String {
        String(localized: "largetype_action_show_in_large_type_and_lock_title", bundle: AppLocalization.shared.bundle)
    }
    /// largetype_action_show_in_large_type_title
    public static var largetypeActionShowInLargeTypeTitle: String {
        String(localized: "largetype_action_show_in_large_type_title", bundle: AppLocalization.shared.bundle)
    }
    /// largetype_character_index_label
    public static func largetypeCharacterIndexLabel(_ a1: Int, _ a2: String) -> String {
        String(format: String(localized: "largetype_character_index_label", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// largetype_title
    public static var largetypeTitle: String {
        String(localized: "largetype_title", bundle: AppLocalization.shared.bundle)
    }
    /// largetype_unicode_surrogate_note
    public static var largetypeUnicodeSurrogateNote: String {
        String(localized: "largetype_unicode_surrogate_note", bundle: AppLocalization.shared.bundle)
    }
    /// launch_web_vault
    public static var launchWebVault: String {
        String(localized: "launch_web_vault", bundle: AppLocalization.shared.bundle)
    }
    /// learn_more
    public static var learnMore: String {
        String(localized: "learn_more", bundle: AppLocalization.shared.bundle)
    }
    /// length
    public static var length: String {
        String(localized: "length", bundle: AppLocalization.shared.bundle)
    }
    /// license_number
    public static var licenseNumber: String {
        String(localized: "license_number", bundle: AppLocalization.shared.bundle)
    }
    /// license_open_project_website_action
    public static var licenseOpenProjectWebsiteAction: String {
        String(localized: "license_open_project_website_action", bundle: AppLocalization.shared.bundle)
    }
    /// licenses_empty_label
    public static var licensesEmptyLabel: String {
        String(localized: "licenses_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// linked_app
    public static var linkedApp: String {
        String(localized: "linked_app", bundle: AppLocalization.shared.bundle)
    }
    /// linked_apps
    public static var linkedApps: String {
        String(localized: "linked_apps", bundle: AppLocalization.shared.bundle)
    }
    /// linked_uri
    public static var linkedUri: String {
        String(localized: "linked_uri", bundle: AppLocalization.shared.bundle)
    }
    /// linked_uris
    public static var linkedUris: String {
        String(localized: "linked_uris", bundle: AppLocalization.shared.bundle)
    }
    /// list_add
    public static var listAdd: String {
        String(localized: "list_add", bundle: AppLocalization.shared.bundle)
    }
    /// list_move_down
    public static var listMoveDown: String {
        String(localized: "list_move_down", bundle: AppLocalization.shared.bundle)
    }
    /// list_move_up
    public static var listMoveUp: String {
        String(localized: "list_move_up", bundle: AppLocalization.shared.bundle)
    }
    /// list_remove
    public static var listRemove: String {
        String(localized: "list_remove", bundle: AppLocalization.shared.bundle)
    }
    /// list_remove_confirmation_title
    public static var listRemoveConfirmationTitle: String {
        String(localized: "list_remove_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// loading
    public static var loading: String {
        String(localized: "loading", bundle: AppLocalization.shared.bundle)
    }
    /// local_network_permission_banner_text
    public static var localNetworkPermissionBannerText: String {
        String(localized: "local_network_permission_banner_text", bundle: AppLocalization.shared.bundle)
    }
    /// local_network_permission_banner_title
    public static var localNetworkPermissionBannerTitle: String {
        String(localized: "local_network_permission_banner_title", bundle: AppLocalization.shared.bundle)
    }
    /// local_network_permission_open_settings_action
    public static var localNetworkPermissionOpenSettingsAction: String {
        String(localized: "local_network_permission_open_settings_action", bundle: AppLocalization.shared.bundle)
    }
    /// local_vault
    public static var localVault: String {
        String(localized: "local_vault", bundle: AppLocalization.shared.bundle)
    }
    /// localization_contributors_empty_label
    public static var localizationContributorsEmptyLabel: String {
        String(localized: "localization_contributors_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// localization_contributors_search_placeholder
    public static var localizationContributorsSearchPlaceholder: String {
        String(localized: "localization_contributors_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// localization_contributors_title
    public static var localizationContributorsTitle: String {
        String(localized: "localization_contributors_title", bundle: AppLocalization.shared.bundle)
    }
    /// lock_reason_inactivity
    public static var lockReasonInactivity: String {
        String(localized: "lock_reason_inactivity", bundle: AppLocalization.shared.bundle)
    }
    /// lock_reason_manually
    public static var lockReasonManually: String {
        String(localized: "lock_reason_manually", bundle: AppLocalization.shared.bundle)
    }
    /// lock_reason_screen_off
    public static var lockReasonScreenOff: String {
        String(localized: "lock_reason_screen_off", bundle: AppLocalization.shared.bundle)
    }
    /// lock_reason_system_sleep
    public static var lockReasonSystemSleep: String {
        String(localized: "lock_reason_system_sleep", bundle: AppLocalization.shared.bundle)
    }
    /// logs_empty_note
    public static var logsEmptyNote: String {
        String(localized: "logs_empty_note", bundle: AppLocalization.shared.bundle)
    }
    /// logs_empty_title
    public static var logsEmptyTitle: String {
        String(localized: "logs_empty_title", bundle: AppLocalization.shared.bundle)
    }
    /// logs_export_button
    public static var logsExportButton: String {
        String(localized: "logs_export_button", bundle: AppLocalization.shared.bundle)
    }
    /// logs_export_success
    public static var logsExportSuccess: String {
        String(localized: "logs_export_success", bundle: AppLocalization.shared.bundle)
    }
    /// logs_header_title
    public static var logsHeaderTitle: String {
        String(localized: "logs_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// logs_start_recording_fab_title
    public static var logsStartRecordingFabTitle: String {
        String(localized: "logs_start_recording_fab_title", bundle: AppLocalization.shared.bundle)
    }
    /// logs_stop_recording_fab_title
    public static var logsStopRecordingFabTitle: String {
        String(localized: "logs_stop_recording_fab_title", bundle: AppLocalization.shared.bundle)
    }
    /// march
    public static var march: String {
        String(localized: "march", bundle: AppLocalization.shared.bundle)
    }
    /// master_password_hint
    public static var masterPasswordHint: String {
        String(localized: "master_password_hint", bundle: AppLocalization.shared.bundle)
    }
    /// max_access_count
    public static var maxAccessCount: String {
        String(localized: "max_access_count", bundle: AppLocalization.shared.bundle)
    }
    /// may
    public static var may: String {
        String(localized: "may", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_generate_password_hint
    public static var menuBarGeneratePasswordHint: String {
        String(localized: "menu_bar_generate_password_hint", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_lock_action
    public static var menuBarLockAction: String {
        String(localized: "menu_bar_lock_action", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_lock_vault_hint
    public static var menuBarLockVaultHint: String {
        String(localized: "menu_bar_lock_vault_hint", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_open_main_window_action
    public static var menuBarOpenMainWindowAction: String {
        String(localized: "menu_bar_open_main_window_action", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_search_hint
    public static var menuBarSearchHint: String {
        String(localized: "menu_bar_search_hint", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_search_no_matches_text
    public static func menuBarSearchNoMatchesText(_ a1: String) -> String {
        String(format: String(localized: "menu_bar_search_no_matches_text", bundle: AppLocalization.shared.bundle), a1)
    }
    /// menu_bar_status_locked
    public static var menuBarStatusLocked: String {
        String(localized: "menu_bar_status_locked", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_status_set_up
    public static var menuBarStatusSetUp: String {
        String(localized: "menu_bar_status_set_up", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_status_unlocked
    public static var menuBarStatusUnlocked: String {
        String(localized: "menu_bar_status_unlocked", bundle: AppLocalization.shared.bundle)
    }
    /// menu_bar_unlock_vault_hint
    public static var menuBarUnlockVaultHint: String {
        String(localized: "menu_bar_unlock_vault_hint", bundle: AppLocalization.shared.bundle)
    }
    /// misc
    public static var misc: String {
        String(localized: "misc", bundle: AppLocalization.shared.bundle)
    }
    /// more
    public static var more: String {
        String(localized: "more", bundle: AppLocalization.shared.bundle)
    }
    /// more_actions
    public static var moreActions: String {
        String(localized: "more_actions", bundle: AppLocalization.shared.bundle)
    }
    /// nav_animation_crossfade
    public static var navAnimationCrossfade: String {
        String(localized: "nav_animation_crossfade", bundle: AppLocalization.shared.bundle)
    }
    /// nav_animation_disabled
    public static var navAnimationDisabled: String {
        String(localized: "nav_animation_disabled", bundle: AppLocalization.shared.bundle)
    }
    /// nav_animation_dynamic
    public static var navAnimationDynamic: String {
        String(localized: "nav_animation_dynamic", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_add_item_title
    public static var navigationItemsAddItemTitle: String {
        String(localized: "navigation_items_add_item_title", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_built_in_text
    public static var navigationItemsBuiltInText: String {
        String(localized: "navigation_items_built_in_text", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_custom_filter_text
    public static var navigationItemsCustomFilterText: String {
        String(localized: "navigation_items_custom_filter_text", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_missing_filter_text
    public static var navigationItemsMissingFilterText: String {
        String(localized: "navigation_items_missing_filter_text", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_missing_filter_title
    public static var navigationItemsMissingFilterTitle: String {
        String(localized: "navigation_items_missing_filter_title", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_predefined_route_text
    public static var navigationItemsPredefinedRouteText: String {
        String(localized: "navigation_items_predefined_route_text", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_reset_confirmation_text
    public static var navigationItemsResetConfirmationText: String {
        String(localized: "navigation_items_reset_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// navigation_items_reset_confirmation_title
    public static var navigationItemsResetConfirmationTitle: String {
        String(localized: "navigation_items_reset_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// new_password
    public static var newPassword: String {
        String(localized: "new_password", bundle: AppLocalization.shared.bundle)
    }
    /// no
    public static var no: String {
        String(localized: "no", bundle: AppLocalization.shared.bundle)
    }
    /// no_password
    public static var noPassword: String {
        String(localized: "no_password", bundle: AppLocalization.shared.bundle)
    }
    /// none
    public static var `none`: String {
        String(localized: "none", bundle: AppLocalization.shared.bundle)
    }
    /// note
    public static var note: String {
        String(localized: "note", bundle: AppLocalization.shared.bundle)
    }
    /// notes
    public static var notes: String {
        String(localized: "notes", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_progress_bytes
    public static func notificationVaultBackupProgressBytes(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "notification_vault_backup_progress_bytes", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// notification_vault_backup_progress_items
    public static func notificationVaultBackupProgressItems(_ a1: Int, _ a2: Int) -> String {
        String(format: String(localized: "notification_vault_backup_progress_items", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// notification_vault_backup_step_applying_retention
    public static var notificationVaultBackupStepApplyingRetention: String {
        String(localized: "notification_vault_backup_step_applying_retention", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_step_backing_up_attachments
    public static var notificationVaultBackupStepBackingUpAttachments: String {
        String(localized: "notification_vault_backup_step_backing_up_attachments", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_step_exporting_vault
    public static var notificationVaultBackupStepExportingVault: String {
        String(localized: "notification_vault_backup_step_exporting_vault", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_step_opening_repository
    public static var notificationVaultBackupStepOpeningRepository: String {
        String(localized: "notification_vault_backup_step_opening_repository", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_step_preparing
    public static var notificationVaultBackupStepPreparing: String {
        String(localized: "notification_vault_backup_step_preparing", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_step_scanning_attachments
    public static var notificationVaultBackupStepScanningAttachments: String {
        String(localized: "notification_vault_backup_step_scanning_attachments", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_step_writing_index
    public static var notificationVaultBackupStepWritingIndex: String {
        String(localized: "notification_vault_backup_step_writing_index", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_step_writing_snapshot
    public static var notificationVaultBackupStepWritingSnapshot: String {
        String(localized: "notification_vault_backup_step_writing_snapshot", bundle: AppLocalization.shared.bundle)
    }
    /// notification_vault_backup_title
    public static var notificationVaultBackupTitle: String {
        String(localized: "notification_vault_backup_title", bundle: AppLocalization.shared.bundle)
    }
    /// november
    public static var november: String {
        String(localized: "november", bundle: AppLocalization.shared.bundle)
    }
    /// october
    public static var october: String {
        String(localized: "october", bundle: AppLocalization.shared.bundle)
    }
    /// ok
    public static var ok: String {
        String(localized: "ok", bundle: AppLocalization.shared.bundle)
    }
    /// one_time_password
    public static var oneTimePassword: String {
        String(localized: "one_time_password", bundle: AppLocalization.shared.bundle)
    }
    /// one_time_password_authenticator_key
    public static var oneTimePasswordAuthenticatorKey: String {
        String(localized: "one_time_password_authenticator_key", bundle: AppLocalization.shared.bundle)
    }
    /// open_action
    public static var openAction: String {
        String(localized: "open_action", bundle: AppLocalization.shared.bundle)
    }
    /// open_database
    public static var openDatabase: String {
        String(localized: "open_database", bundle: AppLocalization.shared.bundle)
    }
    /// open_in_keyguard_action
    public static var openInKeyguardAction: String {
        String(localized: "open_in_keyguard_action", bundle: AppLocalization.shared.bundle)
    }
    /// options
    public static var options: String {
        String(localized: "options", bundle: AppLocalization.shared.bundle)
    }
    /// organization
    public static var organization: String {
        String(localized: "organization", bundle: AppLocalization.shared.bundle)
    }
    /// organization_none
    public static var organizationNone: String {
        String(localized: "organization_none", bundle: AppLocalization.shared.bundle)
    }
    /// organizations
    public static var organizations: String {
        String(localized: "organizations", bundle: AppLocalization.shared.bundle)
    }
    /// organizations_empty_label
    public static var organizationsEmptyLabel: String {
        String(localized: "organizations_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// organizations_empty_text
    public static var organizationsEmptyText: String {
        String(localized: "organizations_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// output
    public static var output: String {
        String(localized: "output", bundle: AppLocalization.shared.bundle)
    }
    /// package_name
    public static var packageName: String {
        String(localized: "package_name", bundle: AppLocalization.shared.bundle)
    }
    /// passkey
    public static var passkey: String {
        String(localized: "passkey", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_auth_via_header
    public static var passkeyAuthViaHeader: String {
        String(localized: "passkey_auth_via_header", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_available
    public static var passkeyAvailable: String {
        String(localized: "passkey_available", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_create_header
    public static var passkeyCreateHeader: String {
        String(localized: "passkey_create_header", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_credential_id_label
    public static var passkeyCredentialIdLabel: String {
        String(localized: "passkey_credential_id_label", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_credential_load_failed_title
    public static var passkeyCredentialLoadFailedTitle: String {
        String(localized: "passkey_credential_load_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_discoverable
    public static var passkeyDiscoverable: String {
        String(localized: "passkey_discoverable", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_relying_party
    public static var passkeyRelyingParty: String {
        String(localized: "passkey_relying_party", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_save
    public static var passkeySave: String {
        String(localized: "passkey_save", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_signature_counter
    public static var passkeySignatureCounter: String {
        String(localized: "passkey_signature_counter", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_unlock_header
    public static var passkeyUnlockHeader: String {
        String(localized: "passkey_unlock_header", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_use_short
    public static var passkeyUseShort: String {
        String(localized: "passkey_use_short", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_user_display_name
    public static var passkeyUserDisplayName: String {
        String(localized: "passkey_user_display_name", bundle: AppLocalization.shared.bundle)
    }
    /// passkey_user_username
    public static var passkeyUserUsername: String {
        String(localized: "passkey_user_username", bundle: AppLocalization.shared.bundle)
    }
    /// passkeys
    public static var passkeys: String {
        String(localized: "passkeys", bundle: AppLocalization.shared.bundle)
    }
    /// passkeys_directory_search_placeholder
    public static var passkeysDirectorySearchPlaceholder: String {
        String(localized: "passkeys_directory_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// passkeys_directory_setup_title
    public static var passkeysDirectorySetupTitle: String {
        String(localized: "passkeys_directory_setup_title", bundle: AppLocalization.shared.bundle)
    }
    /// passkeys_directory_text
    public static var passkeysDirectoryText: String {
        String(localized: "passkeys_directory_text", bundle: AppLocalization.shared.bundle)
    }
    /// passkeys_directory_title
    public static var passkeysDirectoryTitle: String {
        String(localized: "passkeys_directory_title", bundle: AppLocalization.shared.bundle)
    }
    /// passport_number
    public static var passportNumber: String {
        String(localized: "passport_number", bundle: AppLocalization.shared.bundle)
    }
    /// password
    public static var password: String {
        String(localized: "password", bundle: AppLocalization.shared.bundle)
    }
    /// password_action_check_data_breach_title
    public static var passwordActionCheckDataBreachTitle: String {
        String(localized: "password_action_check_data_breach_title", bundle: AppLocalization.shared.bundle)
    }
    /// password_action_test_memory_title
    public static var passwordActionTestMemoryTitle: String {
        String(localized: "password_action_test_memory_title", bundle: AppLocalization.shared.bundle)
    }
    /// password_memory_test_note
    public static var passwordMemoryTestNote: String {
        String(localized: "password_memory_test_note", bundle: AppLocalization.shared.bundle)
    }
    /// password_memory_test_success
    public static var passwordMemoryTestSuccess: String {
        String(localized: "password_memory_test_success", bundle: AppLocalization.shared.bundle)
    }
    /// password_pwned_label
    public static var passwordPwnedLabel: String {
        String(localized: "password_pwned_label", bundle: AppLocalization.shared.bundle)
    }
    /// password_save
    public static var passwordSave: String {
        String(localized: "password_save", bundle: AppLocalization.shared.bundle)
    }
    /// password_strength_fair_label
    public static var passwordStrengthFairLabel: String {
        String(localized: "password_strength_fair_label", bundle: AppLocalization.shared.bundle)
    }
    /// password_strength_good_label
    public static var passwordStrengthGoodLabel: String {
        String(localized: "password_strength_good_label", bundle: AppLocalization.shared.bundle)
    }
    /// password_strength_strong_label
    public static var passwordStrengthStrongLabel: String {
        String(localized: "password_strength_strong_label", bundle: AppLocalization.shared.bundle)
    }
    /// password_strength_very_strong_label
    public static var passwordStrengthVeryStrongLabel: String {
        String(localized: "password_strength_very_strong_label", bundle: AppLocalization.shared.bundle)
    }
    /// password_strength_weak_label
    public static var passwordStrengthWeakLabel: String {
        String(localized: "password_strength_weak_label", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_clear_history_confirmation_text
    public static var passwordhistoryClearHistoryConfirmationText: String {
        String(localized: "passwordhistory_clear_history_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_clear_history_confirmation_title
    public static var passwordhistoryClearHistoryConfirmationTitle: String {
        String(localized: "passwordhistory_clear_history_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_clear_history_title
    public static var passwordhistoryClearHistoryTitle: String {
        String(localized: "passwordhistory_clear_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_delete_many_confirmation_title
    public static var passwordhistoryDeleteManyConfirmationTitle: String {
        String(localized: "passwordhistory_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_delete_one_confirmation_title
    public static var passwordhistoryDeleteOneConfirmationTitle: String {
        String(localized: "passwordhistory_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_empty_text
    public static var passwordhistoryEmptyText: String {
        String(localized: "passwordhistory_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_empty_title
    public static var passwordhistoryEmptyTitle: String {
        String(localized: "passwordhistory_empty_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordhistory_header_title
    public static var passwordhistoryHeaderTitle: String {
        String(localized: "passwordhistory_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordleak_failed_to_load_status_text
    public static var passwordleakFailedToLoadStatusText: String {
        String(localized: "passwordleak_failed_to_load_status_text", bundle: AppLocalization.shared.bundle)
    }
    /// passwordleak_note
    public static var passwordleakNote: String {
        String(localized: "passwordleak_note", bundle: AppLocalization.shared.bundle)
    }
    /// passwordleak_occurrences_found_text
    public static var passwordleakOccurrencesFoundText: String {
        String(localized: "passwordleak_occurrences_found_text", bundle: AppLocalization.shared.bundle)
    }
    /// passwordleak_occurrences_found_title
    public static var passwordleakOccurrencesFoundTitle: String {
        String(localized: "passwordleak_occurrences_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordleak_occurrences_not_found_title
    public static var passwordleakOccurrencesNotFoundTitle: String {
        String(localized: "passwordleak_occurrences_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwordleak_title
    public static var passwordleakTitle: String {
        String(localized: "passwordleak_title", bundle: AppLocalization.shared.bundle)
    }
    /// passwords_fair_label
    public static var passwordsFairLabel: String {
        String(localized: "passwords_fair_label", bundle: AppLocalization.shared.bundle)
    }
    /// passwords_good_label
    public static var passwordsGoodLabel: String {
        String(localized: "passwords_good_label", bundle: AppLocalization.shared.bundle)
    }
    /// passwords_strong_label
    public static var passwordsStrongLabel: String {
        String(localized: "passwords_strong_label", bundle: AppLocalization.shared.bundle)
    }
    /// passwords_very_strong_label
    public static var passwordsVeryStrongLabel: String {
        String(localized: "passwords_very_strong_label", bundle: AppLocalization.shared.bundle)
    }
    /// passwords_weak_label
    public static var passwordsWeakLabel: String {
        String(localized: "passwords_weak_label", bundle: AppLocalization.shared.bundle)
    }
    /// phone
    public static var phone: String {
        String(localized: "phone", bundle: AppLocalization.shared.bundle)
    }
    /// phone_number
    public static var phoneNumber: String {
        String(localized: "phone_number", bundle: AppLocalization.shared.bundle)
    }
    /// pkcs12_password
    public static var pkcs12Password: String {
        String(localized: "pkcs12_password", bundle: AppLocalization.shared.bundle)
    }
    /// pkcs12_password_note
    public static var pkcs12PasswordNote: String {
        String(localized: "pkcs12_password_note", bundle: AppLocalization.shared.bundle)
    }
    /// pkcs12_password_title
    public static var pkcs12PasswordTitle: String {
        String(localized: "pkcs12_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// post_notifications_permission_banner_text
    public static var postNotificationsPermissionBannerText: String {
        String(localized: "post_notifications_permission_banner_text", bundle: AppLocalization.shared.bundle)
    }
    /// post_notifications_permission_banner_title
    public static var postNotificationsPermissionBannerTitle: String {
        String(localized: "post_notifications_permission_banner_title", bundle: AppLocalization.shared.bundle)
    }
    /// postal_code
    public static var postalCode: String {
        String(localized: "postal_code", bundle: AppLocalization.shared.bundle)
    }
    /// powered_by
    public static var poweredBy: String {
        String(localized: "powered_by", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_accounts_text
    public static var prefItemAccountsText: String {
        String(localized: "pref_item_accounts_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_accounts_title
    public static var prefItemAccountsTitle: String {
        String(localized: "pref_item_accounts_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_application
    public static var prefItemAgentApprovalScopeApplication: String {
        String(localized: "pref_item_agent_approval_scope_application", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_application_and_terminal_session
    public static var prefItemAgentApprovalScopeApplicationAndTerminalSession: String {
        String(localized: "pref_item_agent_approval_scope_application_and_terminal_session", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_connection
    public static var prefItemAgentApprovalScopeConnection: String {
        String(localized: "pref_item_agent_approval_scope_connection", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_process
    public static var prefItemAgentApprovalScopeProcess: String {
        String(localized: "pref_item_agent_approval_scope_process", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_android_application_and_terminal_session_reuse
    public static var prefItemAgentApprovalScopeTableAndroidApplicationAndTerminalSessionReuse: String {
        String(localized: "pref_item_agent_approval_scope_table_android_application_and_terminal_session_reuse", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_android_process_reuse
    public static var prefItemAgentApprovalScopeTableAndroidProcessReuse: String {
        String(localized: "pref_item_agent_approval_scope_table_android_process_reuse", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_application_and_terminal_session_reuse
    public static var prefItemAgentApprovalScopeTableApplicationAndTerminalSessionReuse: String {
        String(localized: "pref_item_agent_approval_scope_table_application_and_terminal_session_reuse", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_application_reuse
    public static var prefItemAgentApprovalScopeTableApplicationReuse: String {
        String(localized: "pref_item_agent_approval_scope_table_application_reuse", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_connection_reuse
    public static var prefItemAgentApprovalScopeTableConnectionReuse: String {
        String(localized: "pref_item_agent_approval_scope_table_connection_reuse", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_current_connection_only
    public static var prefItemAgentApprovalScopeTableCurrentConnectionOnly: String {
        String(localized: "pref_item_agent_approval_scope_table_current_connection_only", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_not_shared
    public static var prefItemAgentApprovalScopeTableNotShared: String {
        String(localized: "pref_item_agent_approval_scope_table_not_shared", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_other_terminal
    public static var prefItemAgentApprovalScopeTableOtherTerminal: String {
        String(localized: "pref_item_agent_approval_scope_table_other_terminal", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_process_reuse
    public static var prefItemAgentApprovalScopeTableProcessReuse: String {
        String(localized: "pref_item_agent_approval_scope_table_process_reuse", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_reuse_boundary
    public static var prefItemAgentApprovalScopeTableReuseBoundary: String {
        String(localized: "pref_item_agent_approval_scope_table_reuse_boundary", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_same_process_only
    public static var prefItemAgentApprovalScopeTableSameProcessOnly: String {
        String(localized: "pref_item_agent_approval_scope_table_same_process_only", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_same_terminal
    public static var prefItemAgentApprovalScopeTableSameTerminal: String {
        String(localized: "pref_item_agent_approval_scope_table_same_terminal", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_table_shared
    public static var prefItemAgentApprovalScopeTableShared: String {
        String(localized: "pref_item_agent_approval_scope_table_shared", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_agent_approval_scope_title
    public static var prefItemAgentApprovalScopeTitle: String {
        String(localized: "pref_item_agent_approval_scope_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_badge
    public static var prefItemAllowScreenshotsBadge: String {
        String(localized: "pref_item_allow_screenshots_badge", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_disabled_notification
    public static var prefItemAllowScreenshotsDisabledNotification: String {
        String(localized: "pref_item_allow_screenshots_disabled_notification", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_mode_disabled_title
    public static var prefItemAllowScreenshotsModeDisabledTitle: String {
        String(localized: "pref_item_allow_screenshots_mode_disabled_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_mode_full_title
    public static var prefItemAllowScreenshotsModeFullTitle: String {
        String(localized: "pref_item_allow_screenshots_mode_full_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_mode_limited_title
    public static var prefItemAllowScreenshotsModeLimitedTitle: String {
        String(localized: "pref_item_allow_screenshots_mode_limited_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_text_off
    public static var prefItemAllowScreenshotsTextOff: String {
        String(localized: "pref_item_allow_screenshots_text_off", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_text_on
    public static var prefItemAllowScreenshotsTextOn: String {
        String(localized: "pref_item_allow_screenshots_text_on", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_screenshots_title
    public static var prefItemAllowScreenshotsTitle: String {
        String(localized: "pref_item_allow_screenshots_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_two_panel_layout_in_landscape_title
    public static var prefItemAllowTwoPanelLayoutInLandscapeTitle: String {
        String(localized: "pref_item_allow_two_panel_layout_in_landscape_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_allow_two_panel_layout_in_portrait_title
    public static var prefItemAllowTwoPanelLayoutInPortraitTitle: String {
        String(localized: "pref_item_allow_two_panel_layout_in_portrait_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_app_build_date_title
    public static var prefItemAppBuildDateTitle: String {
        String(localized: "pref_item_app_build_date_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_app_build_ref_title
    public static var prefItemAppBuildRefTitle: String {
        String(localized: "pref_item_app_build_ref_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_app_changelog_title
    public static var prefItemAppChangelogTitle: String {
        String(localized: "pref_item_app_changelog_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_app_team_title
    public static var prefItemAppTeamTitle: String {
        String(localized: "pref_item_app_team_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_app_version_title
    public static var prefItemAppVersionTitle: String {
        String(localized: "pref_item_app_version_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_appearance_restart_note
    public static var prefItemAppearanceRestartNote: String {
        String(localized: "pref_item_appearance_restart_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_appearance_text
    public static var prefItemAppearanceText: String {
        String(localized: "pref_item_appearance_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_appearance_title
    public static var prefItemAppearanceTitle: String {
        String(localized: "pref_item_appearance_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_auto_copy_otp_text
    public static var prefItemAutofillAutoCopyOtpText: String {
        String(localized: "pref_item_autofill_auto_copy_otp_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_auto_copy_otp_title
    public static var prefItemAutofillAutoCopyOtpTitle: String {
        String(localized: "pref_item_autofill_auto_copy_otp_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_auto_save_source_title
    public static var prefItemAutofillAutoSaveSourceTitle: String {
        String(localized: "pref_item_autofill_auto_save_source_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_block_uri_text
    public static var prefItemAutofillBlockUriText: String {
        String(localized: "pref_item_autofill_block_uri_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_block_uri_title
    public static var prefItemAutofillBlockUriTitle: String {
        String(localized: "pref_item_autofill_block_uri_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_default_match_detection_title
    public static var prefItemAutofillDefaultMatchDetectionTitle: String {
        String(localized: "pref_item_autofill_default_match_detection_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_inline_suggestions_text
    public static var prefItemAutofillInlineSuggestionsText: String {
        String(localized: "pref_item_autofill_inline_suggestions_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_inline_suggestions_title
    public static var prefItemAutofillInlineSuggestionsTitle: String {
        String(localized: "pref_item_autofill_inline_suggestions_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_manual_selection_text
    public static var prefItemAutofillManualSelectionText: String {
        String(localized: "pref_item_autofill_manual_selection_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_manual_selection_title
    public static var prefItemAutofillManualSelectionTitle: String {
        String(localized: "pref_item_autofill_manual_selection_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_passkeys_enabled_text
    public static var prefItemAutofillPasskeysEnabledText: String {
        String(localized: "pref_item_autofill_passkeys_enabled_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_passkeys_enabled_title
    public static var prefItemAutofillPasskeysEnabledTitle: String {
        String(localized: "pref_item_autofill_passkeys_enabled_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_passwords_enabled_text
    public static var prefItemAutofillPasswordsEnabledText: String {
        String(localized: "pref_item_autofill_passwords_enabled_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_passwords_enabled_title
    public static var prefItemAutofillPasswordsEnabledTitle: String {
        String(localized: "pref_item_autofill_passwords_enabled_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_privileged_apps_text
    public static var prefItemAutofillPrivilegedAppsText: String {
        String(localized: "pref_item_autofill_privileged_apps_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_privileged_apps_title
    public static var prefItemAutofillPrivilegedAppsTitle: String {
        String(localized: "pref_item_autofill_privileged_apps_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_respect_autofill_disabled_note
    public static var prefItemAutofillRespectAutofillDisabledNote: String {
        String(localized: "pref_item_autofill_respect_autofill_disabled_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_respect_autofill_disabled_text
    public static var prefItemAutofillRespectAutofillDisabledText: String {
        String(localized: "pref_item_autofill_respect_autofill_disabled_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_respect_autofill_disabled_title
    public static var prefItemAutofillRespectAutofillDisabledTitle: String {
        String(localized: "pref_item_autofill_respect_autofill_disabled_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_save_request_text
    public static var prefItemAutofillSaveRequestText: String {
        String(localized: "pref_item_autofill_save_request_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_save_request_title
    public static var prefItemAutofillSaveRequestTitle: String {
        String(localized: "pref_item_autofill_save_request_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_browser_autofill_settings_error
    public static var prefItemAutofillServiceBrowserAutofillSettingsError: String {
        String(localized: "pref_item_autofill_service_browser_autofill_settings_error", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_browser_autofill_v2_text
    public static var prefItemAutofillServiceBrowserAutofillV2Text: String {
        String(localized: "pref_item_autofill_service_browser_autofill_v2_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_browser_autofill_v2_title
    public static var prefItemAutofillServiceBrowserAutofillV2Title: String {
        String(localized: "pref_item_autofill_service_browser_autofill_v2_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_current_text
    public static var prefItemAutofillServiceCurrentText: String {
        String(localized: "pref_item_autofill_service_current_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_disabled_text
    public static var prefItemAutofillServiceDisabledText: String {
        String(localized: "pref_item_autofill_service_disabled_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_enable_action
    public static var prefItemAutofillServiceEnableAction: String {
        String(localized: "pref_item_autofill_service_enable_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_enabled_text
    public static var prefItemAutofillServiceEnabledText: String {
        String(localized: "pref_item_autofill_service_enabled_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_index_failed_text
    public static var prefItemAutofillServiceIndexFailedText: String {
        String(localized: "pref_item_autofill_service_index_failed_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_locked_text
    public static var prefItemAutofillServiceLockedText: String {
        String(localized: "pref_item_autofill_service_locked_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_open_settings_action
    public static var prefItemAutofillServiceOpenSettingsAction: String {
        String(localized: "pref_item_autofill_service_open_settings_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_refresh_action
    public static var prefItemAutofillServiceRefreshAction: String {
        String(localized: "pref_item_autofill_service_refresh_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_text
    public static var prefItemAutofillServiceText: String {
        String(localized: "pref_item_autofill_service_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_title
    public static var prefItemAutofillServiceTitle: String {
        String(localized: "pref_item_autofill_service_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_updating_text
    public static var prefItemAutofillServiceUpdatingText: String {
        String(localized: "pref_item_autofill_service_updating_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_service_xiaomi_permission_note
    public static var prefItemAutofillServiceXiaomiPermissionNote: String {
        String(localized: "pref_item_autofill_service_xiaomi_permission_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_text
    public static var prefItemAutofillText: String {
        String(localized: "pref_item_autofill_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_autofill_title
    public static var prefItemAutofillTitle: String {
        String(localized: "pref_item_autofill_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_change_configuration_action
    public static var prefItemAutomaticBackupsChangeConfigurationAction: String {
        String(localized: "pref_item_automatic_backups_change_configuration_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_choose_folder_action
    public static var prefItemAutomaticBackupsChooseFolderAction: String {
        String(localized: "pref_item_automatic_backups_choose_folder_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_321_one
    public static var prefItemAutomaticBackupsDetails321One: String {
        String(localized: "pref_item_automatic_backups_details_321_one", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_321_text
    public static var prefItemAutomaticBackupsDetails321Text: String {
        String(localized: "pref_item_automatic_backups_details_321_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_321_three
    public static var prefItemAutomaticBackupsDetails321Three: String {
        String(localized: "pref_item_automatic_backups_details_321_three", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_321_title
    public static var prefItemAutomaticBackupsDetails321Title: String {
        String(localized: "pref_item_automatic_backups_details_321_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_321_two
    public static var prefItemAutomaticBackupsDetails321Two: String {
        String(localized: "pref_item_automatic_backups_details_321_two", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_repository_structure_text
    public static var prefItemAutomaticBackupsDetailsRepositoryStructureText: String {
        String(localized: "pref_item_automatic_backups_details_repository_structure_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_repository_structure_title
    public static var prefItemAutomaticBackupsDetailsRepositoryStructureTitle: String {
        String(localized: "pref_item_automatic_backups_details_repository_structure_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_retention_text
    public static var prefItemAutomaticBackupsDetailsRetentionText: String {
        String(localized: "pref_item_automatic_backups_details_retention_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_retention_title
    public static var prefItemAutomaticBackupsDetailsRetentionTitle: String {
        String(localized: "pref_item_automatic_backups_details_retention_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_sync_text
    public static var prefItemAutomaticBackupsDetailsSyncText: String {
        String(localized: "pref_item_automatic_backups_details_sync_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_details_sync_title
    public static var prefItemAutomaticBackupsDetailsSyncTitle: String {
        String(localized: "pref_item_automatic_backups_details_sync_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_disable_action
    public static var prefItemAutomaticBackupsDisableAction: String {
        String(localized: "pref_item_automatic_backups_disable_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_disable_confirmation_text
    public static var prefItemAutomaticBackupsDisableConfirmationText: String {
        String(localized: "pref_item_automatic_backups_disable_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_disable_message
    public static var prefItemAutomaticBackupsDisableMessage: String {
        String(localized: "pref_item_automatic_backups_disable_message", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_disable_title
    public static var prefItemAutomaticBackupsDisableTitle: String {
        String(localized: "pref_item_automatic_backups_disable_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_enable_button
    public static var prefItemAutomaticBackupsEnableButton: String {
        String(localized: "pref_item_automatic_backups_enable_button", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_enabled_title
    public static var prefItemAutomaticBackupsEnabledTitle: String {
        String(localized: "pref_item_automatic_backups_enabled_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_folder_access_help
    public static var prefItemAutomaticBackupsFolderAccessHelp: String {
        String(localized: "pref_item_automatic_backups_folder_access_help", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_include_attachments_disabled_summary
    public static var prefItemAutomaticBackupsIncludeAttachmentsDisabledSummary: String {
        String(localized: "pref_item_automatic_backups_include_attachments_disabled_summary", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_include_attachments_enabled_summary
    public static var prefItemAutomaticBackupsIncludeAttachmentsEnabledSummary: String {
        String(localized: "pref_item_automatic_backups_include_attachments_enabled_summary", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_include_attachments_title
    public static var prefItemAutomaticBackupsIncludeAttachmentsTitle: String {
        String(localized: "pref_item_automatic_backups_include_attachments_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_initialization_error
    public static var prefItemAutomaticBackupsInitializationError: String {
        String(localized: "pref_item_automatic_backups_initialization_error", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_local_folder_title
    public static var prefItemAutomaticBackupsLocalFolderTitle: String {
        String(localized: "pref_item_automatic_backups_local_folder_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_location_title
    public static var prefItemAutomaticBackupsLocationTitle: String {
        String(localized: "pref_item_automatic_backups_location_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_not_configured
    public static var prefItemAutomaticBackupsNotConfigured: String {
        String(localized: "pref_item_automatic_backups_not_configured", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_attachments_label
    public static var prefItemAutomaticBackupsPanelAttachmentsLabel: String {
        String(localized: "pref_item_automatic_backups_panel_attachments_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_blobs_label
    public static var prefItemAutomaticBackupsPanelBlobsLabel: String {
        String(localized: "pref_item_automatic_backups_panel_blobs_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_blobs_value
    public static func prefItemAutomaticBackupsPanelBlobsValue(_ a1: Int, _ a2: Int) -> String {
        String(format: String(localized: "pref_item_automatic_backups_panel_blobs_value", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// pref_item_automatic_backups_panel_error_label
    public static var prefItemAutomaticBackupsPanelErrorLabel: String {
        String(localized: "pref_item_automatic_backups_panel_error_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_finished_label
    public static var prefItemAutomaticBackupsPanelFinishedLabel: String {
        String(localized: "pref_item_automatic_backups_panel_finished_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_last_change_label
    public static var prefItemAutomaticBackupsPanelLastChangeLabel: String {
        String(localized: "pref_item_automatic_backups_panel_last_change_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_last_sync_title
    public static var prefItemAutomaticBackupsPanelLastSyncTitle: String {
        String(localized: "pref_item_automatic_backups_panel_last_sync_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_pending_changes_label
    public static var prefItemAutomaticBackupsPanelPendingChangesLabel: String {
        String(localized: "pref_item_automatic_backups_panel_pending_changes_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_progress_label
    public static var prefItemAutomaticBackupsPanelProgressLabel: String {
        String(localized: "pref_item_automatic_backups_panel_progress_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_repository_title
    public static var prefItemAutomaticBackupsPanelRepositoryTitle: String {
        String(localized: "pref_item_automatic_backups_panel_repository_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_skipped_label
    public static var prefItemAutomaticBackupsPanelSkippedLabel: String {
        String(localized: "pref_item_automatic_backups_panel_skipped_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_snapshot_label
    public static var prefItemAutomaticBackupsPanelSnapshotLabel: String {
        String(localized: "pref_item_automatic_backups_panel_snapshot_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_started_label
    public static var prefItemAutomaticBackupsPanelStartedLabel: String {
        String(localized: "pref_item_automatic_backups_panel_started_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_status_label
    public static var prefItemAutomaticBackupsPanelStatusLabel: String {
        String(localized: "pref_item_automatic_backups_panel_status_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_panel_vault_items_label
    public static var prefItemAutomaticBackupsPanelVaultItemsLabel: String {
        String(localized: "pref_item_automatic_backups_panel_vault_items_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_password_message
    public static var prefItemAutomaticBackupsPasswordMessage: String {
        String(localized: "pref_item_automatic_backups_password_message", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_password_not_set
    public static var prefItemAutomaticBackupsPasswordNotSet: String {
        String(localized: "pref_item_automatic_backups_password_not_set", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_password_optional_label
    public static var prefItemAutomaticBackupsPasswordOptionalLabel: String {
        String(localized: "pref_item_automatic_backups_password_optional_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_password_set
    public static var prefItemAutomaticBackupsPasswordSet: String {
        String(localized: "pref_item_automatic_backups_password_set", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_password_set_summary
    public static var prefItemAutomaticBackupsPasswordSetSummary: String {
        String(localized: "pref_item_automatic_backups_password_set_summary", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_password_title
    public static var prefItemAutomaticBackupsPasswordTitle: String {
        String(localized: "pref_item_automatic_backups_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_pending_changes_text
    public static var prefItemAutomaticBackupsPendingChangesText: String {
        String(localized: "pref_item_automatic_backups_pending_changes_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_progress_bytes
    public static func prefItemAutomaticBackupsProgressBytes(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "pref_item_automatic_backups_progress_bytes", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// pref_item_automatic_backups_progress_items
    public static func prefItemAutomaticBackupsProgressItems(_ a1: Int, _ a2: Int) -> String {
        String(format: String(localized: "pref_item_automatic_backups_progress_items", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// pref_item_automatic_backups_retention_keep_all
    public static var prefItemAutomaticBackupsRetentionKeepAll: String {
        String(localized: "pref_item_automatic_backups_retention_keep_all", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_retention_label
    public static var prefItemAutomaticBackupsRetentionLabel: String {
        String(localized: "pref_item_automatic_backups_retention_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_retention_never_clear_value
    public static var prefItemAutomaticBackupsRetentionNeverClearValue: String {
        String(localized: "pref_item_automatic_backups_retention_never_clear_value", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_retention_prune_note
    public static var prefItemAutomaticBackupsRetentionPruneNote: String {
        String(localized: "pref_item_automatic_backups_retention_prune_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_retention_title
    public static var prefItemAutomaticBackupsRetentionTitle: String {
        String(localized: "pref_item_automatic_backups_retention_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_retention_value
    public static func prefItemAutomaticBackupsRetentionValue(_ a1: Int) -> String {
        String(format: String(localized: "pref_item_automatic_backups_retention_value", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_automatic_backups_run_now_title
    public static var prefItemAutomaticBackupsRunNowTitle: String {
        String(localized: "pref_item_automatic_backups_run_now_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_save_verify_action
    public static var prefItemAutomaticBackupsSaveVerifyAction: String {
        String(localized: "pref_item_automatic_backups_save_verify_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_setup_intro
    public static var prefItemAutomaticBackupsSetupIntro: String {
        String(localized: "pref_item_automatic_backups_setup_intro", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_setup_logic
    public static var prefItemAutomaticBackupsSetupLogic: String {
        String(localized: "pref_item_automatic_backups_setup_logic", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_error_text
    public static func prefItemAutomaticBackupsStatusErrorText(_ a1: String) -> String {
        String(format: String(localized: "pref_item_automatic_backups_status_error_text", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_automatic_backups_status_error_title
    public static var prefItemAutomaticBackupsStatusErrorTitle: String {
        String(localized: "pref_item_automatic_backups_status_error_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_finished_text
    public static func prefItemAutomaticBackupsStatusFinishedText(_ a1: String) -> String {
        String(format: String(localized: "pref_item_automatic_backups_status_finished_text", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_automatic_backups_status_never_text
    public static var prefItemAutomaticBackupsStatusNeverText: String {
        String(localized: "pref_item_automatic_backups_status_never_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_never_title
    public static var prefItemAutomaticBackupsStatusNeverTitle: String {
        String(localized: "pref_item_automatic_backups_status_never_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_reason_not_configured
    public static var prefItemAutomaticBackupsStatusReasonNotConfigured: String {
        String(localized: "pref_item_automatic_backups_status_reason_not_configured", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_reason_unknown
    public static var prefItemAutomaticBackupsStatusReasonUnknown: String {
        String(localized: "pref_item_automatic_backups_status_reason_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_reason_vault_locked
    public static var prefItemAutomaticBackupsStatusReasonVaultLocked: String {
        String(localized: "pref_item_automatic_backups_status_reason_vault_locked", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_running_text_with_progress
    public static func prefItemAutomaticBackupsStatusRunningTextWithProgress(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "pref_item_automatic_backups_status_running_text_with_progress", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// pref_item_automatic_backups_status_running_title
    public static var prefItemAutomaticBackupsStatusRunningTitle: String {
        String(localized: "pref_item_automatic_backups_status_running_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_skipped_text
    public static func prefItemAutomaticBackupsStatusSkippedText(_ a1: String) -> String {
        String(format: String(localized: "pref_item_automatic_backups_status_skipped_text", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_automatic_backups_status_skipped_title
    public static var prefItemAutomaticBackupsStatusSkippedTitle: String {
        String(localized: "pref_item_automatic_backups_status_skipped_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_status_success_text
    public static func prefItemAutomaticBackupsStatusSuccessText(_ a1: String, _ a2: Int, _ a3: Int, _ a4: Int, _ a5: Int) -> String {
        String(format: String(localized: "pref_item_automatic_backups_status_success_text", bundle: AppLocalization.shared.bundle), a1, a2, a3, a4, a5)
    }
    /// pref_item_automatic_backups_status_success_title
    public static var prefItemAutomaticBackupsStatusSuccessTitle: String {
        String(localized: "pref_item_automatic_backups_status_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_applying_retention
    public static var prefItemAutomaticBackupsStepApplyingRetention: String {
        String(localized: "pref_item_automatic_backups_step_applying_retention", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_backing_up_attachments
    public static var prefItemAutomaticBackupsStepBackingUpAttachments: String {
        String(localized: "pref_item_automatic_backups_step_backing_up_attachments", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_exporting_vault
    public static var prefItemAutomaticBackupsStepExportingVault: String {
        String(localized: "pref_item_automatic_backups_step_exporting_vault", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_opening_repository
    public static var prefItemAutomaticBackupsStepOpeningRepository: String {
        String(localized: "pref_item_automatic_backups_step_opening_repository", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_preparing
    public static var prefItemAutomaticBackupsStepPreparing: String {
        String(localized: "pref_item_automatic_backups_step_preparing", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_scanning_attachments
    public static var prefItemAutomaticBackupsStepScanningAttachments: String {
        String(localized: "pref_item_automatic_backups_step_scanning_attachments", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_writing_index
    public static var prefItemAutomaticBackupsStepWritingIndex: String {
        String(localized: "pref_item_automatic_backups_step_writing_index", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_step_writing_snapshot
    public static var prefItemAutomaticBackupsStepWritingSnapshot: String {
        String(localized: "pref_item_automatic_backups_step_writing_snapshot", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_store_folder_title
    public static var prefItemAutomaticBackupsStoreFolderTitle: String {
        String(localized: "pref_item_automatic_backups_store_folder_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_store_webdav_title
    public static var prefItemAutomaticBackupsStoreWebdavTitle: String {
        String(localized: "pref_item_automatic_backups_store_webdav_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_text
    public static var prefItemAutomaticBackupsText: String {
        String(localized: "pref_item_automatic_backups_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_title
    public static var prefItemAutomaticBackupsTitle: String {
        String(localized: "pref_item_automatic_backups_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_unavailable_title
    public static var prefItemAutomaticBackupsUnavailableTitle: String {
        String(localized: "pref_item_automatic_backups_unavailable_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_unsupported_text
    public static var prefItemAutomaticBackupsUnsupportedText: String {
        String(localized: "pref_item_automatic_backups_unsupported_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_unsupported_title
    public static var prefItemAutomaticBackupsUnsupportedTitle: String {
        String(localized: "pref_item_automatic_backups_unsupported_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_webdav_keep_password
    public static var prefItemAutomaticBackupsWebdavKeepPassword: String {
        String(localized: "pref_item_automatic_backups_webdav_keep_password", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_webdav_server_title
    public static var prefItemAutomaticBackupsWebdavServerTitle: String {
        String(localized: "pref_item_automatic_backups_webdav_server_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_confirm_password_label
    public static var prefItemAutomaticBackupsWizardConfirmPasswordLabel: String {
        String(localized: "pref_item_automatic_backups_wizard_confirm_password_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_contents_detail
    public static var prefItemAutomaticBackupsWizardContentsDetail: String {
        String(localized: "pref_item_automatic_backups_wizard_contents_detail", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_contents_title
    public static var prefItemAutomaticBackupsWizardContentsTitle: String {
        String(localized: "pref_item_automatic_backups_wizard_contents_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_destination_detail
    public static var prefItemAutomaticBackupsWizardDestinationDetail: String {
        String(localized: "pref_item_automatic_backups_wizard_destination_detail", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_destination_title
    public static var prefItemAutomaticBackupsWizardDestinationTitle: String {
        String(localized: "pref_item_automatic_backups_wizard_destination_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_discard_action
    public static var prefItemAutomaticBackupsWizardDiscardAction: String {
        String(localized: "pref_item_automatic_backups_wizard_discard_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_discard_message
    public static var prefItemAutomaticBackupsWizardDiscardMessage: String {
        String(localized: "pref_item_automatic_backups_wizard_discard_message", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_discard_title
    public static var prefItemAutomaticBackupsWizardDiscardTitle: String {
        String(localized: "pref_item_automatic_backups_wizard_discard_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_folder_detail
    public static var prefItemAutomaticBackupsWizardFolderDetail: String {
        String(localized: "pref_item_automatic_backups_wizard_folder_detail", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_invalid_url_error
    public static var prefItemAutomaticBackupsWizardInvalidUrlError: String {
        String(localized: "pref_item_automatic_backups_wizard_invalid_url_error", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_password_mismatch_error
    public static var prefItemAutomaticBackupsWizardPasswordMismatchError: String {
        String(localized: "pref_item_automatic_backups_wizard_password_mismatch_error", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_protection_detail
    public static var prefItemAutomaticBackupsWizardProtectionDetail: String {
        String(localized: "pref_item_automatic_backups_wizard_protection_detail", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_protection_title
    public static var prefItemAutomaticBackupsWizardProtectionTitle: String {
        String(localized: "pref_item_automatic_backups_wizard_protection_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_ready_detail
    public static var prefItemAutomaticBackupsWizardReadyDetail: String {
        String(localized: "pref_item_automatic_backups_wizard_ready_detail", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_ready_title
    public static var prefItemAutomaticBackupsWizardReadyTitle: String {
        String(localized: "pref_item_automatic_backups_wizard_ready_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_replace_password_action
    public static var prefItemAutomaticBackupsWizardReplacePasswordAction: String {
        String(localized: "pref_item_automatic_backups_wizard_replace_password_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_review_detail
    public static var prefItemAutomaticBackupsWizardReviewDetail: String {
        String(localized: "pref_item_automatic_backups_wizard_review_detail", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_review_title
    public static var prefItemAutomaticBackupsWizardReviewTitle: String {
        String(localized: "pref_item_automatic_backups_wizard_review_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_step_label
    public static func prefItemAutomaticBackupsWizardStepLabel(_ a1: Int, _ a2: Int) -> String {
        String(format: String(localized: "pref_item_automatic_backups_wizard_step_label", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// pref_item_automatic_backups_wizard_title
    public static var prefItemAutomaticBackupsWizardTitle: String {
        String(localized: "pref_item_automatic_backups_wizard_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_verifying_status
    public static var prefItemAutomaticBackupsWizardVerifyingStatus: String {
        String(localized: "pref_item_automatic_backups_wizard_verifying_status", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_automatic_backups_wizard_webdav_detail
    public static var prefItemAutomaticBackupsWizardWebdavDetail: String {
        String(localized: "pref_item_automatic_backups_wizard_webdav_detail", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_biometric_unlock_confirm_title
    public static var prefItemBiometricUnlockConfirmTitle: String {
        String(localized: "pref_item_biometric_unlock_confirm_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_biometric_unlock_description
    public static var prefItemBiometricUnlockDescription: String {
        String(localized: "pref_item_biometric_unlock_description", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_biometric_unlock_require_confirmation_text
    public static var prefItemBiometricUnlockRequireConfirmationText: String {
        String(localized: "pref_item_biometric_unlock_require_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_biometric_unlock_require_confirmation_title
    public static var prefItemBiometricUnlockRequireConfirmationTitle: String {
        String(localized: "pref_item_biometric_unlock_require_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_biometric_unlock_timeout_title
    public static var prefItemBiometricUnlockTimeoutTitle: String {
        String(localized: "pref_item_biometric_unlock_timeout_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_biometric_unlock_title
    public static var prefItemBiometricUnlockTitle: String {
        String(localized: "pref_item_biometric_unlock_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_change_app_password_title
    public static var prefItemChangeAppPasswordTitle: String {
        String(localized: "pref_item_change_app_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_change_master_password_action
    public static var prefItemChangeMasterPasswordAction: String {
        String(localized: "pref_item_change_master_password_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_check_inactive_2fa_title
    public static var prefItemCheckInactive2faTitle: String {
        String(localized: "pref_item_check_inactive_2fa_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_check_inactive_passkeys_title
    public static var prefItemCheckInactivePasskeysTitle: String {
        String(localized: "pref_item_check_inactive_passkeys_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_check_pwned_passwords_title
    public static var prefItemCheckPwnedPasswordsTitle: String {
        String(localized: "pref_item_check_pwned_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_check_pwned_services_title
    public static var prefItemCheckPwnedServicesTitle: String {
        String(localized: "pref_item_check_pwned_services_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clear_vault_title
    public static var prefItemClearVaultTitle: String {
        String(localized: "pref_item_clear_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clipboard_auto_clear_immediately_text
    public static var prefItemClipboardAutoClearImmediatelyText: String {
        String(localized: "pref_item_clipboard_auto_clear_immediately_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clipboard_auto_clear_never_text
    public static var prefItemClipboardAutoClearNeverText: String {
        String(localized: "pref_item_clipboard_auto_clear_never_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clipboard_auto_clear_title
    public static var prefItemClipboardAutoClearTitle: String {
        String(localized: "pref_item_clipboard_auto_clear_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clipboard_auto_refresh_otp_duration_never_text
    public static var prefItemClipboardAutoRefreshOtpDurationNeverText: String {
        String(localized: "pref_item_clipboard_auto_refresh_otp_duration_never_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clipboard_auto_refresh_otp_duration_note
    public static var prefItemClipboardAutoRefreshOtpDurationNote: String {
        String(localized: "pref_item_clipboard_auto_refresh_otp_duration_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clipboard_auto_refresh_otp_duration_title
    public static var prefItemClipboardAutoRefreshOtpDurationTitle: String {
        String(localized: "pref_item_clipboard_auto_refresh_otp_duration_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_clipboard_notification_settings_title
    public static var prefItemClipboardNotificationSettingsTitle: String {
        String(localized: "pref_item_clipboard_notification_settings_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_close_to_menu_bar_title
    public static var prefItemCloseToMenuBarTitle: String {
        String(localized: "pref_item_close_to_menu_bar_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_close_to_tray_title
    public static var prefItemCloseToTrayTitle: String {
        String(localized: "pref_item_close_to_tray_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_color_accent_title
    public static var prefItemColorAccentTitle: String {
        String(localized: "pref_item_color_accent_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_color_scheme_amoled_dark_title
    public static var prefItemColorSchemeAmoledDarkTitle: String {
        String(localized: "pref_item_color_scheme_amoled_dark_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_color_scheme_expressive_title
    public static var prefItemColorSchemeExpressiveTitle: String {
        String(localized: "pref_item_color_scheme_expressive_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_color_scheme_selection_title
    public static var prefItemColorSchemeSelectionTitle: String {
        String(localized: "pref_item_color_scheme_selection_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_color_scheme_title
    public static var prefItemColorSchemeTitle: String {
        String(localized: "pref_item_color_scheme_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_conceal_fields_text
    public static var prefItemConcealFieldsText: String {
        String(localized: "pref_item_conceal_fields_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_conceal_fields_title
    public static var prefItemConcealFieldsTitle: String {
        String(localized: "pref_item_conceal_fields_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_contact_us_title
    public static var prefItemContactUsTitle: String {
        String(localized: "pref_item_contact_us_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_crash_title
    public static var prefItemCrashTitle: String {
        String(localized: "pref_item_crash_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_credential_provider_no_feature_note
    public static var prefItemCredentialProviderNoFeatureNote: String {
        String(localized: "pref_item_credential_provider_no_feature_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_credential_provider_text
    public static var prefItemCredentialProviderText: String {
        String(localized: "pref_item_credential_provider_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_credential_provider_title
    public static var prefItemCredentialProviderTitle: String {
        String(localized: "pref_item_credential_provider_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_crowdin_title
    public static var prefItemCrowdinTitle: String {
        String(localized: "pref_item_crowdin_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_crowdin_view_all_contributors_title
    public static var prefItemCrowdinViewAllContributorsTitle: String {
        String(localized: "pref_item_crowdin_view_all_contributors_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_data_safety_title
    public static var prefItemDataSafetyTitle: String {
        String(localized: "pref_item_data_safety_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_debug_premium_text
    public static var prefItemDebugPremiumText: String {
        String(localized: "pref_item_debug_premium_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_debug_premium_title
    public static var prefItemDebugPremiumTitle: String {
        String(localized: "pref_item_debug_premium_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_dev_text
    public static var prefItemDevText: String {
        String(localized: "pref_item_dev_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_dev_title
    public static var prefItemDevTitle: String {
        String(localized: "pref_item_dev_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_developer_text
    public static var prefItemDeveloperText: String {
        String(localized: "pref_item_developer_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_developer_title
    public static var prefItemDeveloperTitle: String {
        String(localized: "pref_item_developer_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_download_apk_title
    public static var prefItemDownloadApkTitle: String {
        String(localized: "pref_item_download_apk_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_erase_data_text
    public static var prefItemEraseDataText: String {
        String(localized: "pref_item_erase_data_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_erase_data_title
    public static var prefItemEraseDataTitle: String {
        String(localized: "pref_item_erase_data_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_experimental_title
    public static var prefItemExperimentalTitle: String {
        String(localized: "pref_item_experimental_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_features_overview_title
    public static var prefItemFeaturesOverviewTitle: String {
        String(localized: "pref_item_features_overview_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_font_title
    public static var prefItemFontTitle: String {
        String(localized: "pref_item_font_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_github_title
    public static var prefItemGithubTitle: String {
        String(localized: "pref_item_github_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_approval_window_always_ask
    public static var prefItemGpgAgentApprovalWindowAlwaysAsk: String {
        String(localized: "pref_item_gpg_agent_approval_window_always_ask", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_approval_window_title
    public static var prefItemGpgAgentApprovalWindowTitle: String {
        String(localized: "pref_item_gpg_agent_approval_window_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_approval_window_until_lock
    public static var prefItemGpgAgentApprovalWindowUntilLock: String {
        String(localized: "pref_item_gpg_agent_approval_window_until_lock", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_approvals_note
    public static var prefItemGpgAgentApprovalsNote: String {
        String(localized: "pref_item_gpg_agent_approvals_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_display_key_names_note
    public static var prefItemGpgAgentDisplayKeyNamesNote: String {
        String(localized: "pref_item_gpg_agent_display_key_names_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_display_key_names_title
    public static var prefItemGpgAgentDisplayKeyNamesTitle: String {
        String(localized: "pref_item_gpg_agent_display_key_names_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_filters_summary_active
    public static var prefItemGpgAgentFiltersSummaryActive: String {
        String(localized: "pref_item_gpg_agent_filters_summary_active", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_filters_summary_all
    public static var prefItemGpgAgentFiltersSummaryAll: String {
        String(localized: "pref_item_gpg_agent_filters_summary_all", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_filters_text
    public static var prefItemGpgAgentFiltersText: String {
        String(localized: "pref_item_gpg_agent_filters_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_filters_title
    public static var prefItemGpgAgentFiltersTitle: String {
        String(localized: "pref_item_gpg_agent_filters_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_history_title
    public static var prefItemGpgAgentHistoryTitle: String {
        String(localized: "pref_item_gpg_agent_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_local_storage_text
    public static var prefItemGpgAgentLocalStorageText: String {
        String(localized: "pref_item_gpg_agent_local_storage_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_local_storage_title
    public static var prefItemGpgAgentLocalStorageTitle: String {
        String(localized: "pref_item_gpg_agent_local_storage_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_setup_title
    public static var prefItemGpgAgentSetupTitle: String {
        String(localized: "pref_item_gpg_agent_setup_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_status_failed
    public static var prefItemGpgAgentStatusFailed: String {
        String(localized: "pref_item_gpg_agent_status_failed", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_status_ready
    public static var prefItemGpgAgentStatusReady: String {
        String(localized: "pref_item_gpg_agent_status_ready", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_status_starting
    public static var prefItemGpgAgentStatusStarting: String {
        String(localized: "pref_item_gpg_agent_status_starting", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_status_stopped
    public static var prefItemGpgAgentStatusStopped: String {
        String(localized: "pref_item_gpg_agent_status_stopped", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_status_unsupported
    public static var prefItemGpgAgentStatusUnsupported: String {
        String(localized: "pref_item_gpg_agent_status_unsupported", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_agent_title
    public static var prefItemGpgAgentTitle: String {
        String(localized: "pref_item_gpg_agent_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_auto_refresh_note
    public static var prefItemGpgKeyserverAutoRefreshNote: String {
        String(localized: "pref_item_gpg_keyserver_auto_refresh_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_auto_refresh_title
    public static var prefItemGpgKeyserverAutoRefreshTitle: String {
        String(localized: "pref_item_gpg_keyserver_auto_refresh_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_protocol_title
    public static var prefItemGpgKeyserverProtocolTitle: String {
        String(localized: "pref_item_gpg_keyserver_protocol_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_refresh_interval_title
    public static var prefItemGpgKeyserverRefreshIntervalTitle: String {
        String(localized: "pref_item_gpg_keyserver_refresh_interval_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_search_text
    public static var prefItemGpgKeyserverSearchText: String {
        String(localized: "pref_item_gpg_keyserver_search_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_search_title
    public static var prefItemGpgKeyserverSearchTitle: String {
        String(localized: "pref_item_gpg_keyserver_search_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_url_text
    public static var prefItemGpgKeyserverUrlText: String {
        String(localized: "pref_item_gpg_keyserver_url_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_gpg_keyserver_url_title
    public static var prefItemGpgKeyserverUrlTitle: String {
        String(localized: "pref_item_gpg_keyserver_url_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_check_failed
    public static var prefItemHibpApiTokenCheckFailed: String {
        String(localized: "pref_item_hibp_api_token_check_failed", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_error
    public static var prefItemHibpApiTokenError: String {
        String(localized: "pref_item_hibp_api_token_error", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_field_label
    public static var prefItemHibpApiTokenFieldLabel: String {
        String(localized: "pref_item_hibp_api_token_field_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_input_note
    public static var prefItemHibpApiTokenInputNote: String {
        String(localized: "pref_item_hibp_api_token_input_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_note
    public static var prefItemHibpApiTokenNote: String {
        String(localized: "pref_item_hibp_api_token_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_placeholder
    public static var prefItemHibpApiTokenPlaceholder: String {
        String(localized: "pref_item_hibp_api_token_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_status_checking
    public static var prefItemHibpApiTokenStatusChecking: String {
        String(localized: "pref_item_hibp_api_token_status_checking", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_status_failed
    public static var prefItemHibpApiTokenStatusFailed: String {
        String(localized: "pref_item_hibp_api_token_status_failed", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_status_rejected
    public static var prefItemHibpApiTokenStatusRejected: String {
        String(localized: "pref_item_hibp_api_token_status_rejected", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_status_verified
    public static var prefItemHibpApiTokenStatusVerified: String {
        String(localized: "pref_item_hibp_api_token_status_verified", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_text
    public static var prefItemHibpApiTokenText: String {
        String(localized: "pref_item_hibp_api_token_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_title
    public static var prefItemHibpApiTokenTitle: String {
        String(localized: "pref_item_hibp_api_token_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_api_token_validation_error
    public static var prefItemHibpApiTokenValidationError: String {
        String(localized: "pref_item_hibp_api_token_validation_error", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_hibp_header_title
    public static var prefItemHibpHeaderTitle: String {
        String(localized: "pref_item_hibp_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_keep_screen_on_title
    public static var prefItemKeepScreenOnTitle: String {
        String(localized: "pref_item_keep_screen_on_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_launch_at_login_approval_note
    public static var prefItemLaunchAtLoginApprovalNote: String {
        String(localized: "pref_item_launch_at_login_approval_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_launch_at_login_title
    public static var prefItemLaunchAtLoginTitle: String {
        String(localized: "pref_item_launch_at_login_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_launch_at_login_unavailable_text
    public static var prefItemLaunchAtLoginUnavailableText: String {
        String(localized: "pref_item_launch_at_login_unavailable_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_copy_token_action
    public static var prefItemLicenseKeyCopyTokenAction: String {
        String(localized: "pref_item_license_key_copy_token_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_field_label
    public static var prefItemLicenseKeyFieldLabel: String {
        String(localized: "pref_item_license_key_field_label", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_hide_token_action
    public static var prefItemLicenseKeyHideTokenAction: String {
        String(localized: "pref_item_license_key_hide_token_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_link_action
    public static var prefItemLicenseKeyLinkAction: String {
        String(localized: "pref_item_license_key_link_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_linked_token_title
    public static var prefItemLicenseKeyLinkedTokenTitle: String {
        String(localized: "pref_item_license_key_linked_token_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_message_invalid
    public static var prefItemLicenseKeyMessageInvalid: String {
        String(localized: "pref_item_license_key_message_invalid", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_message_redeemed
    public static var prefItemLicenseKeyMessageRedeemed: String {
        String(localized: "pref_item_license_key_message_redeemed", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_purchase_token_title
    public static var prefItemLicenseKeyPurchaseTokenTitle: String {
        String(localized: "pref_item_license_key_purchase_token_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_refresh_action
    public static var prefItemLicenseKeyRefreshAction: String {
        String(localized: "pref_item_license_key_refresh_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_refreshed_text
    public static var prefItemLicenseKeyRefreshedText: String {
        String(localized: "pref_item_license_key_refreshed_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_remove_action
    public static var prefItemLicenseKeyRemoveAction: String {
        String(localized: "pref_item_license_key_remove_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_removed_text
    public static var prefItemLicenseKeyRemovedText: String {
        String(localized: "pref_item_license_key_removed_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_show_token_action
    public static var prefItemLicenseKeyShowTokenAction: String {
        String(localized: "pref_item_license_key_show_token_action", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_active
    public static var prefItemLicenseKeyStatusActive: String {
        String(localized: "pref_item_license_key_status_active", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_available
    public static var prefItemLicenseKeyStatusAvailable: String {
        String(localized: "pref_item_license_key_status_available", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_expired
    public static var prefItemLicenseKeyStatusExpired: String {
        String(localized: "pref_item_license_key_status_expired", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_grace
    public static var prefItemLicenseKeyStatusGrace: String {
        String(localized: "pref_item_license_key_status_grace", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_invalid
    public static var prefItemLicenseKeyStatusInvalid: String {
        String(localized: "pref_item_license_key_status_invalid", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_pending
    public static var prefItemLicenseKeyStatusPending: String {
        String(localized: "pref_item_license_key_status_pending", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_refunded
    public static var prefItemLicenseKeyStatusRefunded: String {
        String(localized: "pref_item_license_key_status_refunded", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_status_revoked
    public static var prefItemLicenseKeyStatusRevoked: String {
        String(localized: "pref_item_license_key_status_revoked", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_title
    public static var prefItemLicenseKeyTitle: String {
        String(localized: "pref_item_license_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_key_token_status_text
    public static func prefItemLicenseKeyTokenStatusText(_ a1: String) -> String {
        String(format: String(localized: "pref_item_license_key_token_status_text", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_license_key_unlock_note
    public static var prefItemLicenseKeyUnlockNote: String {
        String(localized: "pref_item_license_key_unlock_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_sync_already
    public static var prefItemLicenseSyncAlready: String {
        String(localized: "pref_item_license_sync_already", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_sync_failed
    public static var prefItemLicenseSyncFailed: String {
        String(localized: "pref_item_license_sync_failed", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_sync_no_purchases
    public static var prefItemLicenseSyncNoPurchases: String {
        String(localized: "pref_item_license_sync_no_purchases", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_sync_requested
    public static var prefItemLicenseSyncRequested: String {
        String(localized: "pref_item_license_sync_requested", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_sync_success
    public static var prefItemLicenseSyncSuccess: String {
        String(localized: "pref_item_license_sync_success", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_sync_text
    public static var prefItemLicenseSyncText: String {
        String(localized: "pref_item_license_sync_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_license_sync_title
    public static var prefItemLicenseSyncTitle: String {
        String(localized: "pref_item_license_sync_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_load_app_icons_title
    public static var prefItemLoadAppIconsTitle: String {
        String(localized: "pref_item_load_app_icons_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_load_gravatar_icons_title
    public static var prefItemLoadGravatarIconsTitle: String {
        String(localized: "pref_item_load_gravatar_icons_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_load_website_icons_text
    public static var prefItemLoadWebsiteIconsText: String {
        String(localized: "pref_item_load_website_icons_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_load_website_icons_title
    public static var prefItemLoadWebsiteIconsTitle: String {
        String(localized: "pref_item_load_website_icons_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_locale_help_translation_button
    public static var prefItemLocaleHelpTranslationButton: String {
        String(localized: "pref_item_locale_help_translation_button", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_locale_title
    public static var prefItemLocaleTitle: String {
        String(localized: "pref_item_locale_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_delay_immediately_text
    public static var prefItemLockVaultAfterDelayImmediatelyText: String {
        String(localized: "pref_item_lock_vault_after_delay_immediately_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_delay_never_text
    public static var prefItemLockVaultAfterDelayNeverText: String {
        String(localized: "pref_item_lock_vault_after_delay_never_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_delay_title
    public static var prefItemLockVaultAfterDelayTitle: String {
        String(localized: "pref_item_lock_vault_after_delay_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_reboot_text
    public static var prefItemLockVaultAfterRebootText: String {
        String(localized: "pref_item_lock_vault_after_reboot_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_reboot_title
    public static var prefItemLockVaultAfterRebootTitle: String {
        String(localized: "pref_item_lock_vault_after_reboot_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_screen_off_desktop_text
    public static var prefItemLockVaultAfterScreenOffDesktopText: String {
        String(localized: "pref_item_lock_vault_after_screen_off_desktop_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_screen_off_desktop_title
    public static var prefItemLockVaultAfterScreenOffDesktopTitle: String {
        String(localized: "pref_item_lock_vault_after_screen_off_desktop_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_screen_off_text
    public static var prefItemLockVaultAfterScreenOffText: String {
        String(localized: "pref_item_lock_vault_after_screen_off_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_after_screen_off_title
    public static var prefItemLockVaultAfterScreenOffTitle: String {
        String(localized: "pref_item_lock_vault_after_screen_off_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_lock_vault_title
    public static var prefItemLockVaultTitle: String {
        String(localized: "pref_item_lock_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_logs_title
    public static var prefItemLogsTitle: String {
        String(localized: "pref_item_logs_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_markdown_text
    public static var prefItemMarkdownText: String {
        String(localized: "pref_item_markdown_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_markdown_title
    public static var prefItemMarkdownTitle: String {
        String(localized: "pref_item_markdown_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_menu_bar_only_text
    public static var prefItemMenuBarOnlyText: String {
        String(localized: "pref_item_menu_bar_only_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_menu_bar_only_title
    public static var prefItemMenuBarOnlyTitle: String {
        String(localized: "pref_item_menu_bar_only_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_minimize_on_copy_title
    public static var prefItemMinimizeOnCopyTitle: String {
        String(localized: "pref_item_minimize_on_copy_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_nav_animation_title
    public static var prefItemNavAnimationTitle: String {
        String(localized: "pref_item_nav_animation_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_nav_items_title
    public static var prefItemNavItemsTitle: String {
        String(localized: "pref_item_nav_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_nav_label_short_title
    public static var prefItemNavLabelShortTitle: String {
        String(localized: "pref_item_nav_label_short_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_nav_label_title
    public static var prefItemNavLabelTitle: String {
        String(localized: "pref_item_nav_label_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_notifications_text
    public static var prefItemNotificationsText: String {
        String(localized: "pref_item_notifications_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_notifications_title
    public static var prefItemNotificationsTitle: String {
        String(localized: "pref_item_notifications_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_open_links_in_external_browser_title
    public static var prefItemOpenLinksInExternalBrowserTitle: String {
        String(localized: "pref_item_open_links_in_external_browser_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_open_source_licenses_title
    public static var prefItemOpenSourceLicensesTitle: String {
        String(localized: "pref_item_open_source_licenses_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_other_text
    public static var prefItemOtherText: String {
        String(localized: "pref_item_other_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_other_title
    public static var prefItemOtherTitle: String {
        String(localized: "pref_item_other_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_camera_text
    public static var prefItemPermissionCameraText: String {
        String(localized: "pref_item_permission_camera_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_camera_title
    public static var prefItemPermissionCameraTitle: String {
        String(localized: "pref_item_permission_camera_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_local_network_text
    public static var prefItemPermissionLocalNetworkText: String {
        String(localized: "pref_item_permission_local_network_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_local_network_title
    public static var prefItemPermissionLocalNetworkTitle: String {
        String(localized: "pref_item_permission_local_network_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_post_notifications_text
    public static var prefItemPermissionPostNotificationsText: String {
        String(localized: "pref_item_permission_post_notifications_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_post_notifications_title
    public static var prefItemPermissionPostNotificationsTitle: String {
        String(localized: "pref_item_permission_post_notifications_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_write_external_storage_grant
    public static var prefItemPermissionWriteExternalStorageGrant: String {
        String(localized: "pref_item_permission_write_external_storage_grant", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_write_external_storage_text
    public static var prefItemPermissionWriteExternalStorageText: String {
        String(localized: "pref_item_permission_write_external_storage_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permission_write_external_storage_title
    public static var prefItemPermissionWriteExternalStorageTitle: String {
        String(localized: "pref_item_permission_write_external_storage_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_permissions_title
    public static var prefItemPermissionsTitle: String {
        String(localized: "pref_item_permissions_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_persist_vault_key_note
    public static var prefItemPersistVaultKeyNote: String {
        String(localized: "pref_item_persist_vault_key_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_persist_vault_key_text_off
    public static var prefItemPersistVaultKeyTextOff: String {
        String(localized: "pref_item_persist_vault_key_text_off", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_persist_vault_key_text_on
    public static var prefItemPersistVaultKeyTextOn: String {
        String(localized: "pref_item_persist_vault_key_text_on", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_persist_vault_key_title
    public static var prefItemPersistVaultKeyTitle: String {
        String(localized: "pref_item_persist_vault_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_manage_subscription_on_play_store_title
    public static var prefItemPremiumManageSubscriptionOnPlayStoreTitle: String {
        String(localized: "pref_item_premium_manage_subscription_on_play_store_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_active_title
    public static var prefItemPremiumMembershipActiveTitle: String {
        String(localized: "pref_item_premium_membership_active_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_benefit_create_edit_items
    public static var prefItemPremiumMembershipBenefitCreateEditItems: String {
        String(localized: "pref_item_premium_membership_benefit_create_edit_items", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_benefit_multiple_accounts
    public static var prefItemPremiumMembershipBenefitMultipleAccounts: String {
        String(localized: "pref_item_premium_membership_benefit_multiple_accounts", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_benefit_support_development
    public static var prefItemPremiumMembershipBenefitSupportDevelopment: String {
        String(localized: "pref_item_premium_membership_benefit_support_development", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_direct_note
    public static var prefItemPremiumMembershipDirectNote: String {
        String(localized: "pref_item_premium_membership_direct_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_failed_to_load_products
    public static var prefItemPremiumMembershipFailedToLoadProducts: String {
        String(localized: "pref_item_premium_membership_failed_to_load_products", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_failed_to_load_subscriptions
    public static var prefItemPremiumMembershipFailedToLoadSubscriptions: String {
        String(localized: "pref_item_premium_membership_failed_to_load_subscriptions", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_footer
    public static var prefItemPremiumMembershipFooter: String {
        String(localized: "pref_item_premium_membership_footer", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_section_products_title
    public static var prefItemPremiumMembershipSectionProductsTitle: String {
        String(localized: "pref_item_premium_membership_section_products_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_section_subscriptions_note
    public static var prefItemPremiumMembershipSectionSubscriptionsNote: String {
        String(localized: "pref_item_premium_membership_section_subscriptions_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_section_subscriptions_title
    public static var prefItemPremiumMembershipSectionSubscriptionsTitle: String {
        String(localized: "pref_item_premium_membership_section_subscriptions_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_text
    public static var prefItemPremiumMembershipText: String {
        String(localized: "pref_item_premium_membership_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_membership_title
    public static var prefItemPremiumMembershipTitle: String {
        String(localized: "pref_item_premium_membership_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_status_active
    public static var prefItemPremiumStatusActive: String {
        String(localized: "pref_item_premium_status_active", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_premium_status_free_trial_n
    public static func prefItemPremiumStatusFreeTrialN(_ a1: String) -> String {
        String(format: String(localized: "pref_item_premium_status_free_trial_n", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_premium_status_will_not_renew
    public static var prefItemPremiumStatusWillNotRenew: String {
        String(localized: "pref_item_premium_status_will_not_renew", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_privacy_policy_title
    public static var prefItemPrivacyPolicyTitle: String {
        String(localized: "pref_item_privacy_policy_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_rate_on_play_store_title
    public static var prefItemRateOnPlayStoreTitle: String {
        String(localized: "pref_item_rate_on_play_store_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_reddit_community_title
    public static var prefItemRedditCommunityTitle: String {
        String(localized: "pref_item_reddit_community_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_render_markdown_title
    public static var prefItemRenderMarkdownTitle: String {
        String(localized: "pref_item_render_markdown_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_require_app_password_immediately_text
    public static var prefItemRequireAppPasswordImmediatelyText: String {
        String(localized: "pref_item_require_app_password_immediately_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_require_app_password_never_text
    public static var prefItemRequireAppPasswordNeverText: String {
        String(localized: "pref_item_require_app_password_never_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_require_app_password_note
    public static var prefItemRequireAppPasswordNote: String {
        String(localized: "pref_item_require_app_password_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_require_app_password_title
    public static var prefItemRequireAppPasswordTitle: String {
        String(localized: "pref_item_require_app_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_security_text
    public static var prefItemSecurityText: String {
        String(localized: "pref_item_security_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_security_title
    public static var prefItemSecurityTitle: String {
        String(localized: "pref_item_security_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_send_crash_reports_title
    public static var prefItemSendCrashReportsTitle: String {
        String(localized: "pref_item_send_crash_reports_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_approval_remember_note
    public static var prefItemSshAgentApprovalRememberNote: String {
        String(localized: "pref_item_ssh_agent_approval_remember_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_approval_window_always_ask
    public static var prefItemSshAgentApprovalWindowAlwaysAsk: String {
        String(localized: "pref_item_ssh_agent_approval_window_always_ask", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_approval_window_title
    public static var prefItemSshAgentApprovalWindowTitle: String {
        String(localized: "pref_item_ssh_agent_approval_window_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_approval_window_until_lock
    public static var prefItemSshAgentApprovalWindowUntilLock: String {
        String(localized: "pref_item_ssh_agent_approval_window_until_lock", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_description
    public static var prefItemSshAgentDescription: String {
        String(localized: "pref_item_ssh_agent_description", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_display_key_names_note
    public static var prefItemSshAgentDisplayKeyNamesNote: String {
        String(localized: "pref_item_ssh_agent_display_key_names_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_display_key_names_title
    public static var prefItemSshAgentDisplayKeyNamesTitle: String {
        String(localized: "pref_item_ssh_agent_display_key_names_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_enable_title
    public static var prefItemSshAgentEnableTitle: String {
        String(localized: "pref_item_ssh_agent_enable_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_filters_summary_active
    public static var prefItemSshAgentFiltersSummaryActive: String {
        String(localized: "pref_item_ssh_agent_filters_summary_active", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_filters_summary_all
    public static var prefItemSshAgentFiltersSummaryAll: String {
        String(localized: "pref_item_ssh_agent_filters_summary_all", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_filters_text
    public static var prefItemSshAgentFiltersText: String {
        String(localized: "pref_item_ssh_agent_filters_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_filters_title
    public static var prefItemSshAgentFiltersTitle: String {
        String(localized: "pref_item_ssh_agent_filters_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_history_title
    public static var prefItemSshAgentHistoryTitle: String {
        String(localized: "pref_item_ssh_agent_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_local_storage_text
    public static var prefItemSshAgentLocalStorageText: String {
        String(localized: "pref_item_ssh_agent_local_storage_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_local_storage_title
    public static var prefItemSshAgentLocalStorageTitle: String {
        String(localized: "pref_item_ssh_agent_local_storage_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_setup_title
    public static var prefItemSshAgentSetupTitle: String {
        String(localized: "pref_item_ssh_agent_setup_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_status_failed
    public static var prefItemSshAgentStatusFailed: String {
        String(localized: "pref_item_ssh_agent_status_failed", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_status_ready
    public static var prefItemSshAgentStatusReady: String {
        String(localized: "pref_item_ssh_agent_status_ready", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_status_starting
    public static var prefItemSshAgentStatusStarting: String {
        String(localized: "pref_item_ssh_agent_status_starting", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_status_stopped
    public static var prefItemSshAgentStatusStopped: String {
        String(localized: "pref_item_ssh_agent_status_stopped", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_status_unsupported
    public static var prefItemSshAgentStatusUnsupported: String {
        String(localized: "pref_item_ssh_agent_status_unsupported", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_text
    public static var prefItemSshAgentText: String {
        String(localized: "pref_item_ssh_agent_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_ssh_agent_title
    public static var prefItemSshAgentTitle: String {
        String(localized: "pref_item_ssh_agent_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_subscription_text
    public static var prefItemSubscriptionText: String {
        String(localized: "pref_item_subscription_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_subscription_title
    public static var prefItemSubscriptionTitle: String {
        String(localized: "pref_item_subscription_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_system_auth_unlock_title
    public static var prefItemSystemAuthUnlockTitle: String {
        String(localized: "pref_item_system_auth_unlock_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_url_override_title
    public static var prefItemUrlOverrideTitle: String {
        String(localized: "pref_item_url_override_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_vault_lock_timeout_never_title
    public static var prefItemVaultLockTimeoutNeverTitle: String {
        String(localized: "pref_item_vault_lock_timeout_never_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_vault_lock_timeout_title
    public static var prefItemVaultLockTimeoutTitle: String {
        String(localized: "pref_item_vault_lock_timeout_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_watchtower_text
    public static var prefItemWatchtowerText: String {
        String(localized: "pref_item_watchtower_text", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_watchtower_title
    public static var prefItemWatchtowerTitle: String {
        String(localized: "pref_item_watchtower_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_windows_hello_unlock_title
    public static var prefItemWindowsHelloUnlockTitle: String {
        String(localized: "pref_item_windows_hello_unlock_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_yubikey_unlock_confirm_message_configured
    public static func prefItemYubikeyUnlockConfirmMessageConfigured(_ a1: String) -> String {
        String(format: String(localized: "pref_item_yubikey_unlock_confirm_message_configured", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_yubikey_unlock_confirm_message_empty
    public static func prefItemYubikeyUnlockConfirmMessageEmpty(_ a1: String) -> String {
        String(format: String(localized: "pref_item_yubikey_unlock_confirm_message_empty", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_yubikey_unlock_confirm_title
    public static var prefItemYubikeyUnlockConfirmTitle: String {
        String(localized: "pref_item_yubikey_unlock_confirm_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_yubikey_unlock_disable_title
    public static var prefItemYubikeyUnlockDisableTitle: String {
        String(localized: "pref_item_yubikey_unlock_disable_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_yubikey_unlock_note
    public static var prefItemYubikeyUnlockNote: String {
        String(localized: "pref_item_yubikey_unlock_note", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_yubikey_unlock_provision_slot_title
    public static var prefItemYubikeyUnlockProvisionSlotTitle: String {
        String(localized: "pref_item_yubikey_unlock_provision_slot_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_item_yubikey_unlock_text_on_slot_n
    public static func prefItemYubikeyUnlockTextOnSlotN(_ a1: String) -> String {
        String(format: String(localized: "pref_item_yubikey_unlock_text_on_slot_n", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pref_item_yubikey_unlock_title
    public static var prefItemYubikeyUnlockTitle: String {
        String(localized: "pref_item_yubikey_unlock_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_section_automatic_backups_automation_title
    public static var prefSectionAutomaticBackupsAutomationTitle: String {
        String(localized: "pref_section_automatic_backups_automation_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_section_automatic_backups_management_title
    public static var prefSectionAutomaticBackupsManagementTitle: String {
        String(localized: "pref_section_automatic_backups_management_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_section_automatic_backups_setup_title
    public static var prefSectionAutomaticBackupsSetupTitle: String {
        String(localized: "pref_section_automatic_backups_setup_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_section_options_title
    public static var prefSectionOptionsTitle: String {
        String(localized: "pref_section_options_title", bundle: AppLocalization.shared.bundle)
    }
    /// pref_section_premium_title
    public static var prefSectionPremiumTitle: String {
        String(localized: "pref_section_premium_title", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_failed_text
    public static var premiumPurchaseFailedText: String {
        String(localized: "premium_purchase_failed_text", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_manage_failed_text
    public static var premiumPurchaseManageFailedText: String {
        String(localized: "premium_purchase_manage_failed_text", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_manage_subscription_action
    public static var premiumPurchaseManageSubscriptionAction: String {
        String(localized: "premium_purchase_manage_subscription_action", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_one_time_title
    public static var premiumPurchaseOneTimeTitle: String {
        String(localized: "premium_purchase_one_time_title", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_pending_text
    public static var premiumPurchasePendingText: String {
        String(localized: "premium_purchase_pending_text", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_price_period
    public static func premiumPurchasePricePeriod(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "premium_purchase_price_period", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// premium_purchase_product_unavailable_text
    public static var premiumPurchaseProductUnavailableText: String {
        String(localized: "premium_purchase_product_unavailable_text", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_restore_action
    public static var premiumPurchaseRestoreAction: String {
        String(localized: "premium_purchase_restore_action", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_restore_empty_text
    public static var premiumPurchaseRestoreEmptyText: String {
        String(localized: "premium_purchase_restore_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_restore_note
    public static var premiumPurchaseRestoreNote: String {
        String(localized: "premium_purchase_restore_note", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_restore_success_text
    public static var premiumPurchaseRestoreSuccessText: String {
        String(localized: "premium_purchase_restore_success_text", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_storekit_renewal_note
    public static var premiumPurchaseStorekitRenewalNote: String {
        String(localized: "premium_purchase_storekit_renewal_note", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_success_text
    public static var premiumPurchaseSuccessText: String {
        String(localized: "premium_purchase_success_text", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_terms_title
    public static var premiumPurchaseTermsTitle: String {
        String(localized: "premium_purchase_terms_title", bundle: AppLocalization.shared.bundle)
    }
    /// premium_purchase_unverified_text
    public static var premiumPurchaseUnverifiedText: String {
        String(localized: "premium_purchase_unverified_text", bundle: AppLocalization.shared.bundle)
    }
    /// private_key
    public static var privateKey: String {
        String(localized: "private_key", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_community_apps
    public static var privilegedappsCommunityApps: String {
        String(localized: "privilegedapps_community_apps", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_delete_many_confirmation_title
    public static var privilegedappsDeleteManyConfirmationTitle: String {
        String(localized: "privilegedapps_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_delete_one_confirmation_title
    public static var privilegedappsDeleteOneConfirmationTitle: String {
        String(localized: "privilegedapps_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_empty_label
    public static var privilegedappsEmptyLabel: String {
        String(localized: "privilegedapps_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_header_title
    public static var privilegedappsHeaderTitle: String {
        String(localized: "privilegedapps_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_list_header_title
    public static var privilegedappsListHeaderTitle: String {
        String(localized: "privilegedapps_list_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_list_section_title
    public static var privilegedappsListSectionTitle: String {
        String(localized: "privilegedapps_list_section_title", bundle: AppLocalization.shared.bundle)
    }
    /// privilegedapps_user_apps
    public static var privilegedappsUserApps: String {
        String(localized: "privilegedapps_user_apps", bundle: AppLocalization.shared.bundle)
    }
    /// pro_tip
    public static func proTip(_ a1: String) -> String {
        String(format: String(localized: "pro_tip", bundle: AppLocalization.shared.bundle), a1)
    }
    /// pro_tip_generate_email_relay_title
    public static var proTipGenerateEmailRelayTitle: String {
        String(localized: "pro_tip_generate_email_relay_title", bundle: AppLocalization.shared.bundle)
    }
    /// provider_2fa_authenticator
    public static var provider2faAuthenticator: String {
        String(localized: "provider_2fa_authenticator", bundle: AppLocalization.shared.bundle)
    }
    /// provider_2fa_duo
    public static var provider2faDuo: String {
        String(localized: "provider_2fa_duo", bundle: AppLocalization.shared.bundle)
    }
    /// provider_2fa_duo_organization
    public static var provider2faDuoOrganization: String {
        String(localized: "provider_2fa_duo_organization", bundle: AppLocalization.shared.bundle)
    }
    /// provider_2fa_email
    public static var provider2faEmail: String {
        String(localized: "provider_2fa_email", bundle: AppLocalization.shared.bundle)
    }
    /// provider_2fa_fido2_webauthn
    public static var provider2faFido2Webauthn: String {
        String(localized: "provider_2fa_fido2_webauthn", bundle: AppLocalization.shared.bundle)
    }
    /// provider_2fa_fido_u2f
    public static var provider2faFidoU2f: String {
        String(localized: "provider_2fa_fido_u2f", bundle: AppLocalization.shared.bundle)
    }
    /// provider_2fa_yubikey
    public static var provider2faYubikey: String {
        String(localized: "provider_2fa_yubikey", bundle: AppLocalization.shared.bundle)
    }
    /// public_key
    public static var publicKey: String {
        String(localized: "public_key", bundle: AppLocalization.shared.bundle)
    }
    /// public_url
    public static var publicUrl: String {
        String(localized: "public_url", bundle: AppLocalization.shared.bundle)
    }
    /// pull_to_search
    public static var pullToSearch: String {
        String(localized: "pull_to_search", bundle: AppLocalization.shared.bundle)
    }
    /// qr_generate_button
    public static var qrGenerateButton: String {
        String(localized: "qr_generate_button", bundle: AppLocalization.shared.bundle)
    }
    /// qr_wifi_description_text
    public static var qrWifiDescriptionText: String {
        String(localized: "qr_wifi_description_text", bundle: AppLocalization.shared.bundle)
    }
    /// quick_search_empty_hint
    public static var quickSearchEmptyHint: String {
        String(localized: "quick_search_empty_hint", bundle: AppLocalization.shared.bundle)
    }
    /// quick_search_no_matches_title
    public static var quickSearchNoMatchesTitle: String {
        String(localized: "quick_search_no_matches_title", bundle: AppLocalization.shared.bundle)
    }
    /// quick_search_select_result_hint
    public static var quickSearchSelectResultHint: String {
        String(localized: "quick_search_select_result_hint", bundle: AppLocalization.shared.bundle)
    }
    /// quit
    public static var quit: String {
        String(localized: "quit", bundle: AppLocalization.shared.bundle)
    }
    /// ready_status_alpha
    public static var readyStatusAlpha: String {
        String(localized: "ready_status_alpha", bundle: AppLocalization.shared.bundle)
    }
    /// ready_status_beta
    public static var readyStatusBeta: String {
        String(localized: "ready_status_beta", bundle: AppLocalization.shared.bundle)
    }
    /// regex
    public static var regex: String {
        String(localized: "regex", bundle: AppLocalization.shared.bundle)
    }
    /// relative_time_day_short
    public static func relativeTimeDayShort(_ a1: String) -> String {
        String(format: String(localized: "relative_time_day_short", bundle: AppLocalization.shared.bundle), a1)
    }
    /// relative_time_hour_short
    public static func relativeTimeHourShort(_ a1: String) -> String {
        String(format: String(localized: "relative_time_hour_short", bundle: AppLocalization.shared.bundle), a1)
    }
    /// relative_time_just_now_short
    public static var relativeTimeJustNowShort: String {
        String(localized: "relative_time_just_now_short", bundle: AppLocalization.shared.bundle)
    }
    /// relative_time_minute_short
    public static func relativeTimeMinuteShort(_ a1: String) -> String {
        String(format: String(localized: "relative_time_minute_short", bundle: AppLocalization.shared.bundle), a1)
    }
    /// relative_time_week_short
    public static func relativeTimeWeekShort(_ a1: String) -> String {
        String(format: String(localized: "relative_time_week_short", bundle: AppLocalization.shared.bundle), a1)
    }
    /// remember_me
    public static var rememberMe: String {
        String(localized: "remember_me", bundle: AppLocalization.shared.bundle)
    }
    /// remove
    public static var remove: String {
        String(localized: "remove", bundle: AppLocalization.shared.bundle)
    }
    /// remove_from_history
    public static var removeFromHistory: String {
        String(localized: "remove_from_history", bundle: AppLocalization.shared.bundle)
    }
    /// rename
    public static var rename: String {
        String(localized: "rename", bundle: AppLocalization.shared.bundle)
    }
    /// replace_database_file
    public static var replaceDatabaseFile: String {
        String(localized: "replace_database_file", bundle: AppLocalization.shared.bundle)
    }
    /// replace_key_file
    public static var replaceKeyFile: String {
        String(localized: "replace_key_file", bundle: AppLocalization.shared.bundle)
    }
    /// requested_verification_email
    public static var requestedVerificationEmail: String {
        String(localized: "requested_verification_email", bundle: AppLocalization.shared.bundle)
    }
    /// resend_verification_code
    public static var resendVerificationCode: String {
        String(localized: "resend_verification_code", bundle: AppLocalization.shared.bundle)
    }
    /// reset
    public static var reset: String {
        String(localized: "reset", bundle: AppLocalization.shared.bundle)
    }
    /// result
    public static var result: String {
        String(localized: "result", bundle: AppLocalization.shared.bundle)
    }
    /// retry
    public static var retry: String {
        String(localized: "retry", bundle: AppLocalization.shared.bundle)
    }
    /// reused_password
    public static var reusedPassword: String {
        String(localized: "reused_password", bundle: AppLocalization.shared.bundle)
    }
    /// reused_passwords
    public static var reusedPasswords: String {
        String(localized: "reused_passwords", bundle: AppLocalization.shared.bundle)
    }
    /// reveal_secure_note
    public static var revealSecureNote: String {
        String(localized: "reveal_secure_note", bundle: AppLocalization.shared.bundle)
    }
    /// revoked
    public static var revoked: String {
        String(localized: "revoked", bundle: AppLocalization.shared.bundle)
    }
    /// run_action
    public static var runAction: String {
        String(localized: "run_action", bundle: AppLocalization.shared.bundle)
    }
    /// save
    public static var save: String {
        String(localized: "save", bundle: AppLocalization.shared.bundle)
    }
    /// save_to
    public static var saveTo: String {
        String(localized: "save_to", bundle: AppLocalization.shared.bundle)
    }
    /// scanqr_camera_permission_required_text
    public static var scanqrCameraPermissionRequiredText: String {
        String(localized: "scanqr_camera_permission_required_text", bundle: AppLocalization.shared.bundle)
    }
    /// scanqr_camera_unavailable_text
    public static var scanqrCameraUnavailableText: String {
        String(localized: "scanqr_camera_unavailable_text", bundle: AppLocalization.shared.bundle)
    }
    /// scanqr_load_from_image
    public static var scanqrLoadFromImage: String {
        String(localized: "scanqr_load_from_image", bundle: AppLocalization.shared.bundle)
    }
    /// scanqr_load_from_image_note
    public static var scanqrLoadFromImageNote: String {
        String(localized: "scanqr_load_from_image_note", bundle: AppLocalization.shared.bundle)
    }
    /// scanqr_title
    public static var scanqrTitle: String {
        String(localized: "scanqr_title", bundle: AppLocalization.shared.bundle)
    }
    /// search_clear_action
    public static var searchClearAction: String {
        String(localized: "search_clear_action", bundle: AppLocalization.shared.bundle)
    }
    /// security
    public static var security: String {
        String(localized: "security", bundle: AppLocalization.shared.bundle)
    }
    /// select
    public static var select: String {
        String(localized: "select", bundle: AppLocalization.shared.bundle)
    }
    /// select_database_file
    public static var selectDatabaseFile: String {
        String(localized: "select_database_file", bundle: AppLocalization.shared.bundle)
    }
    /// select_file
    public static var selectFile: String {
        String(localized: "select_file", bundle: AppLocalization.shared.bundle)
    }
    /// select_key_file
    public static var selectKeyFile: String {
        String(localized: "select_key_file", bundle: AppLocalization.shared.bundle)
    }
    /// select_linked_type
    public static var selectLinkedType: String {
        String(localized: "select_linked_type", bundle: AppLocalization.shared.bundle)
    }
    /// selection_actions_title
    public static var selectionActionsTitle: String {
        String(localized: "selection_actions_title", bundle: AppLocalization.shared.bundle)
    }
    /// selection_clear_action
    public static var selectionClearAction: String {
        String(localized: "selection_clear_action", bundle: AppLocalization.shared.bundle)
    }
    /// selection_n_selected
    public static func selectionNSelected(_ a1: Int) -> String {
        String(format: String(localized: "selection_n_selected", bundle: AppLocalization.shared.bundle), a1)
    }
    /// selection_select_all_action
    public static var selectionSelectAllAction: String {
        String(localized: "selection_select_all_action", bundle: AppLocalization.shared.bundle)
    }
    /// send
    public static var send: String {
        String(localized: "send", bundle: AppLocalization.shared.bundle)
    }
    /// send_action_copy_link_title
    public static var sendActionCopyLinkTitle: String {
        String(localized: "send_action_copy_link_title", bundle: AppLocalization.shared.bundle)
    }
    /// send_auth_title
    public static var sendAuthTitle: String {
        String(localized: "send_auth_title", bundle: AppLocalization.shared.bundle)
    }
    /// send_auth_type_email
    public static var sendAuthTypeEmail: String {
        String(localized: "send_auth_type_email", bundle: AppLocalization.shared.bundle)
    }
    /// send_auth_type_none
    public static var sendAuthTypeNone: String {
        String(localized: "send_auth_type_none", bundle: AppLocalization.shared.bundle)
    }
    /// send_auth_type_password
    public static var sendAuthTypePassword: String {
        String(localized: "send_auth_type_password", bundle: AppLocalization.shared.bundle)
    }
    /// send_email_is_required_to_access_label
    public static var sendEmailIsRequiredToAccessLabel: String {
        String(localized: "send_email_is_required_to_access_label", bundle: AppLocalization.shared.bundle)
    }
    /// send_main_drop_file_to_create
    public static var sendMainDropFileToCreate: String {
        String(localized: "send_main_drop_file_to_create", bundle: AppLocalization.shared.bundle)
    }
    /// send_main_empty_add_account_text
    public static var sendMainEmptyAddAccountText: String {
        String(localized: "send_main_empty_add_account_text", bundle: AppLocalization.shared.bundle)
    }
    /// send_main_empty_title
    public static var sendMainEmptyTitle: String {
        String(localized: "send_main_empty_title", bundle: AppLocalization.shared.bundle)
    }
    /// send_main_header_title
    public static var sendMainHeaderTitle: String {
        String(localized: "send_main_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// send_main_new_item_button
    public static var sendMainNewItemButton: String {
        String(localized: "send_main_new_item_button", bundle: AppLocalization.shared.bundle)
    }
    /// send_main_search_placeholder
    public static var sendMainSearchPlaceholder: String {
        String(localized: "send_main_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// send_password_is_required_to_access_label
    public static var sendPasswordIsRequiredToAccessLabel: String {
        String(localized: "send_password_is_required_to_access_label", bundle: AppLocalization.shared.bundle)
    }
    /// send_type_file
    public static var sendTypeFile: String {
        String(localized: "send_type_file", bundle: AppLocalization.shared.bundle)
    }
    /// send_type_text
    public static var sendTypeText: String {
        String(localized: "send_type_text", bundle: AppLocalization.shared.bundle)
    }
    /// send_type_unknown
    public static var sendTypeUnknown: String {
        String(localized: "send_type_unknown", bundle: AppLocalization.shared.bundle)
    }
    /// send_view_no_selection_text
    public static var sendViewNoSelectionText: String {
        String(localized: "send_view_no_selection_text", bundle: AppLocalization.shared.bundle)
    }
    /// send_view_no_selection_title
    public static var sendViewNoSelectionTitle: String {
        String(localized: "send_view_no_selection_title", bundle: AppLocalization.shared.bundle)
    }
    /// send_view_not_found_title
    public static var sendViewNotFoundTitle: String {
        String(localized: "send_view_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends
    public static var sends: String {
        String(localized: "sends", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_change_filename_title
    public static var sendsActionChangeFilenameTitle: String {
        String(localized: "sends_action_change_filename_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_change_filenames_title
    public static var sendsActionChangeFilenamesTitle: String {
        String(localized: "sends_action_change_filenames_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_change_name_title
    public static var sendsActionChangeNameTitle: String {
        String(localized: "sends_action_change_name_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_change_names_title
    public static var sendsActionChangeNamesTitle: String {
        String(localized: "sends_action_change_names_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_change_password_title
    public static var sendsActionChangePasswordTitle: String {
        String(localized: "sends_action_change_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_change_passwords_title
    public static var sendsActionChangePasswordsTitle: String {
        String(localized: "sends_action_change_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_delete_confirmation_title
    public static var sendsActionDeleteConfirmationTitle: String {
        String(localized: "sends_action_delete_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_delete_title
    public static var sendsActionDeleteTitle: String {
        String(localized: "sends_action_delete_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_disable_confirmation_title
    public static var sendsActionDisableConfirmationTitle: String {
        String(localized: "sends_action_disable_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_disable_text
    public static var sendsActionDisableText: String {
        String(localized: "sends_action_disable_text", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_disable_title
    public static var sendsActionDisableTitle: String {
        String(localized: "sends_action_disable_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_disabled_note
    public static var sendsActionDisabledNote: String {
        String(localized: "sends_action_disabled_note", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_enable_confirmation_title
    public static var sendsActionEnableConfirmationTitle: String {
        String(localized: "sends_action_enable_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_enable_text
    public static var sendsActionEnableText: String {
        String(localized: "sends_action_enable_text", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_enable_title
    public static var sendsActionEnableTitle: String {
        String(localized: "sends_action_enable_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_hide_email_title
    public static var sendsActionHideEmailTitle: String {
        String(localized: "sends_action_hide_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_remove_password_confirmation_message
    public static var sendsActionRemovePasswordConfirmationMessage: String {
        String(localized: "sends_action_remove_password_confirmation_message", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_remove_password_confirmation_title
    public static var sendsActionRemovePasswordConfirmationTitle: String {
        String(localized: "sends_action_remove_password_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_remove_password_title
    public static var sendsActionRemovePasswordTitle: String {
        String(localized: "sends_action_remove_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_remove_passwords_confirmation_title
    public static var sendsActionRemovePasswordsConfirmationTitle: String {
        String(localized: "sends_action_remove_passwords_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_remove_passwords_title
    public static var sendsActionRemovePasswordsTitle: String {
        String(localized: "sends_action_remove_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_set_password_confirmation_message
    public static var sendsActionSetPasswordConfirmationMessage: String {
        String(localized: "sends_action_set_password_confirmation_message", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_set_password_title
    public static var sendsActionSetPasswordTitle: String {
        String(localized: "sends_action_set_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_set_passwords_title
    public static var sendsActionSetPasswordsTitle: String {
        String(localized: "sends_action_set_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// sends_action_show_email_title
    public static var sendsActionShowEmailTitle: String {
        String(localized: "sends_action_show_email_title", bundle: AppLocalization.shared.bundle)
    }
    /// september
    public static var september: String {
        String(localized: "september", bundle: AppLocalization.shared.bundle)
    }
    /// server
    public static var server: String {
        String(localized: "server", bundle: AppLocalization.shared.bundle)
    }
    /// server_version
    public static func serverVersion(_ a1: String) -> String {
        String(format: String(localized: "server_version", bundle: AppLocalization.shared.bundle), a1)
    }
    /// settings_appearance_header_title
    public static var settingsAppearanceHeaderTitle: String {
        String(localized: "settings_appearance_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_autofill_header_title
    public static var settingsAutofillHeaderTitle: String {
        String(localized: "settings_autofill_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_automatic_backups_header_title
    public static var settingsAutomaticBackupsHeaderTitle: String {
        String(localized: "settings_automatic_backups_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_clipboard_header_title
    public static var settingsClipboardHeaderTitle: String {
        String(localized: "settings_clipboard_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_dev_header_title
    public static var settingsDevHeaderTitle: String {
        String(localized: "settings_dev_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_developer_header_title
    public static var settingsDeveloperHeaderTitle: String {
        String(localized: "settings_developer_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_diagnostics_header_title
    public static var settingsDiagnosticsHeaderTitle: String {
        String(localized: "settings_diagnostics_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_experience_header_title
    public static var settingsExperienceHeaderTitle: String {
        String(localized: "settings_experience_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_experimental_header_title
    public static var settingsExperimentalHeaderTitle: String {
        String(localized: "settings_experimental_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_general_header_title
    public static var settingsGeneralHeaderTitle: String {
        String(localized: "settings_general_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_gpg_agent_header_title
    public static var settingsGpgAgentHeaderTitle: String {
        String(localized: "settings_gpg_agent_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_icons_header_title
    public static var settingsIconsHeaderTitle: String {
        String(localized: "settings_icons_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_localization_header_title
    public static var settingsLocalizationHeaderTitle: String {
        String(localized: "settings_localization_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_main_header_title
    public static var settingsMainHeaderTitle: String {
        String(localized: "settings_main_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_menu_bar_header_title
    public static var settingsMenuBarHeaderTitle: String {
        String(localized: "settings_menu_bar_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_navigation_header_title
    public static var settingsNavigationHeaderTitle: String {
        String(localized: "settings_navigation_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_navigation_items_header_title
    public static var settingsNavigationItemsHeaderTitle: String {
        String(localized: "settings_navigation_items_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_no_selection_text
    public static var settingsNoSelectionText: String {
        String(localized: "settings_no_selection_text", bundle: AppLocalization.shared.bundle)
    }
    /// settings_no_selection_title
    public static var settingsNoSelectionTitle: String {
        String(localized: "settings_no_selection_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_notifications_header_title
    public static var settingsNotificationsHeaderTitle: String {
        String(localized: "settings_notifications_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_open_source_licenses_header_title
    public static var settingsOpenSourceLicensesHeaderTitle: String {
        String(localized: "settings_open_source_licenses_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_other_header_title
    public static var settingsOtherHeaderTitle: String {
        String(localized: "settings_other_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_permissions_header_title
    public static var settingsPermissionsHeaderTitle: String {
        String(localized: "settings_permissions_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_privacy_header_title
    public static var settingsPrivacyHeaderTitle: String {
        String(localized: "settings_privacy_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_security_header_title
    public static var settingsSecurityHeaderTitle: String {
        String(localized: "settings_security_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_ssh_agent_header_title
    public static var settingsSshAgentHeaderTitle: String {
        String(localized: "settings_ssh_agent_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_startup_header_title
    public static var settingsStartupHeaderTitle: String {
        String(localized: "settings_startup_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_subscriptions_header_title
    public static var settingsSubscriptionsHeaderTitle: String {
        String(localized: "settings_subscriptions_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_team_header_title
    public static var settingsTeamHeaderTitle: String {
        String(localized: "settings_team_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settings_watchtower_header_title
    public static var settingsWatchtowerHeaderTitle: String {
        String(localized: "settings_watchtower_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_autofill_index_status_title
    public static var settingssearchAutofillIndexStatusTitle: String {
        String(localized: "settingssearch_autofill_index_status_title", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_autofill_keywords
    public static var settingssearchAutofillKeywords: String {
        String(localized: "settingssearch_autofill_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_backup_keywords
    public static var settingssearchBackupKeywords: String {
        String(localized: "settingssearch_backup_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_backup_location_keywords
    public static var settingssearchBackupLocationKeywords: String {
        String(localized: "settingssearch_backup_location_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_backup_password_keywords
    public static var settingssearchBackupPasswordKeywords: String {
        String(localized: "settingssearch_backup_password_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_biometric_keywords
    public static var settingssearchBiometricKeywords: String {
        String(localized: "settingssearch_biometric_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_breach_keywords
    public static var settingssearchBreachKeywords: String {
        String(localized: "settingssearch_breach_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_build_ref_keywords
    public static var settingssearchBuildRefKeywords: String {
        String(localized: "settingssearch_build_ref_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_clipboard_keywords
    public static var settingssearchClipboardKeywords: String {
        String(localized: "settingssearch_clipboard_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_header_subtitle
    public static var settingssearchHeaderSubtitle: String {
        String(localized: "settingssearch_header_subtitle", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_header_title
    public static var settingssearchHeaderTitle: String {
        String(localized: "settingssearch_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_language_keywords
    public static var settingssearchLanguageKeywords: String {
        String(localized: "settingssearch_language_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_license_keywords
    public static var settingssearchLicenseKeywords: String {
        String(localized: "settingssearch_license_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_lock_keywords
    public static var settingssearchLockKeywords: String {
        String(localized: "settingssearch_lock_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_membership_inactive_text
    public static var settingssearchMembershipInactiveText: String {
        String(localized: "settingssearch_membership_inactive_text", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_membership_status_title
    public static var settingssearchMembershipStatusTitle: String {
        String(localized: "settingssearch_membership_status_title", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_navigation_keywords
    public static var settingssearchNavigationKeywords: String {
        String(localized: "settingssearch_navigation_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_search_placeholder
    public static var settingssearchSearchPlaceholder: String {
        String(localized: "settingssearch_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_ssh_socket_keywords
    public static var settingssearchSshSocketKeywords: String {
        String(localized: "settingssearch_ssh_socket_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_ssh_socket_title
    public static var settingssearchSshSocketTitle: String {
        String(localized: "settingssearch_ssh_socket_title", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_startup_keywords
    public static var settingssearchStartupKeywords: String {
        String(localized: "settingssearch_startup_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// settingssearch_theme_keywords
    public static var settingssearchThemeKeywords: String {
        String(localized: "settingssearch_theme_keywords", bundle: AppLocalization.shared.bundle)
    }
    /// setup_action_erase_data_text
    public static var setupActionEraseDataText: String {
        String(localized: "setup_action_erase_data_text", bundle: AppLocalization.shared.bundle)
    }
    /// setup_action_erase_data_title
    public static var setupActionEraseDataTitle: String {
        String(localized: "setup_action_erase_data_title", bundle: AppLocalization.shared.bundle)
    }
    /// setup_biometric_auth_confirm_title
    public static var setupBiometricAuthConfirmTitle: String {
        String(localized: "setup_biometric_auth_confirm_title", bundle: AppLocalization.shared.bundle)
    }
    /// setup_button_create_vault
    public static var setupButtonCreateVault: String {
        String(localized: "setup_button_create_vault", bundle: AppLocalization.shared.bundle)
    }
    /// setup_button_send_crash_reports
    public static var setupButtonSendCrashReports: String {
        String(localized: "setup_button_send_crash_reports", bundle: AppLocalization.shared.bundle)
    }
    /// setup_checkbox_biometric_auth
    public static var setupCheckboxBiometricAuth: String {
        String(localized: "setup_checkbox_biometric_auth", bundle: AppLocalization.shared.bundle)
    }
    /// setup_erase_vault_confirmation_text
    public static var setupEraseVaultConfirmationText: String {
        String(localized: "setup_erase_vault_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// setup_field_app_password_label
    public static var setupFieldAppPasswordLabel: String {
        String(localized: "setup_field_app_password_label", bundle: AppLocalization.shared.bundle)
    }
    /// setup_free_text
    public static var setupFreeText: String {
        String(localized: "setup_free_text", bundle: AppLocalization.shared.bundle)
    }
    /// setup_header_text
    public static var setupHeaderText: String {
        String(localized: "setup_header_text", bundle: AppLocalization.shared.bundle)
    }
    /// share
    public static var share: String {
        String(localized: "share", bundle: AppLocalization.shared.bundle)
    }
    /// show_keyguard
    public static var showKeyguard: String {
        String(localized: "show_keyguard", bundle: AppLocalization.shared.bundle)
    }
    /// sign
    public static var sign: String {
        String(localized: "sign", bundle: AppLocalization.shared.bundle)
    }
    /// skipped_items_text
    public static var skippedItemsText: String {
        String(localized: "skipped_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// sort_action
    public static var sortAction: String {
        String(localized: "sort_action", bundle: AppLocalization.shared.bundle)
    }
    /// sort_default_order_title
    public static var sortDefaultOrderTitle: String {
        String(localized: "sort_default_order_title", bundle: AppLocalization.shared.bundle)
    }
    /// sort_empty_label
    public static var sortEmptyLabel: String {
        String(localized: "sort_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// sort_header_title
    public static var sortHeaderTitle: String {
        String(localized: "sort_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_access_count_normal_mode
    public static var sortbyAccessCountNormalMode: String {
        String(localized: "sortby_access_count_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_access_count_reverse_mode
    public static var sortbyAccessCountReverseMode: String {
        String(localized: "sortby_access_count_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_access_count_title
    public static var sortbyAccessCountTitle: String {
        String(localized: "sortby_access_count_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_deletion_date_normal_mode
    public static var sortbyDeletionDateNormalMode: String {
        String(localized: "sortby_deletion_date_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_deletion_date_reverse_mode
    public static var sortbyDeletionDateReverseMode: String {
        String(localized: "sortby_deletion_date_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_deletion_date_title
    public static var sortbyDeletionDateTitle: String {
        String(localized: "sortby_deletion_date_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_expiration_date_normal_mode
    public static var sortbyExpirationDateNormalMode: String {
        String(localized: "sortby_expiration_date_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_expiration_date_reverse_mode
    public static var sortbyExpirationDateReverseMode: String {
        String(localized: "sortby_expiration_date_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_expiration_date_title
    public static var sortbyExpirationDateTitle: String {
        String(localized: "sortby_expiration_date_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_installation_date_normal_mode
    public static var sortbyInstallationDateNormalMode: String {
        String(localized: "sortby_installation_date_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_installation_date_reverse_mode
    public static var sortbyInstallationDateReverseMode: String {
        String(localized: "sortby_installation_date_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_installation_date_title
    public static var sortbyInstallationDateTitle: String {
        String(localized: "sortby_installation_date_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_modification_date_normal_mode
    public static var sortbyModificationDateNormalMode: String {
        String(localized: "sortby_modification_date_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_modification_date_reverse_mode
    public static var sortbyModificationDateReverseMode: String {
        String(localized: "sortby_modification_date_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_modification_date_title
    public static var sortbyModificationDateTitle: String {
        String(localized: "sortby_modification_date_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_modification_date_normal_mode
    public static var sortbyPasswordModificationDateNormalMode: String {
        String(localized: "sortby_password_modification_date_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_modification_date_reverse_mode
    public static var sortbyPasswordModificationDateReverseMode: String {
        String(localized: "sortby_password_modification_date_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_modification_date_title
    public static var sortbyPasswordModificationDateTitle: String {
        String(localized: "sortby_password_modification_date_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_normal_mode
    public static var sortbyPasswordNormalMode: String {
        String(localized: "sortby_password_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_reverse_mode
    public static var sortbyPasswordReverseMode: String {
        String(localized: "sortby_password_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_strength_normal_mode
    public static var sortbyPasswordStrengthNormalMode: String {
        String(localized: "sortby_password_strength_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_strength_reverse_mode
    public static var sortbyPasswordStrengthReverseMode: String {
        String(localized: "sortby_password_strength_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_strength_title
    public static var sortbyPasswordStrengthTitle: String {
        String(localized: "sortby_password_strength_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_password_title
    public static var sortbyPasswordTitle: String {
        String(localized: "sortby_password_title", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_title_normal_mode
    public static var sortbyTitleNormalMode: String {
        String(localized: "sortby_title_normal_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_title_reverse_mode
    public static var sortbyTitleReverseMode: String {
        String(localized: "sortby_title_reverse_mode", bundle: AppLocalization.shared.bundle)
    }
    /// sortby_title_title
    public static var sortbyTitleTitle: String {
        String(localized: "sortby_title_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent
    public static var sshAgent: String {
        String(localized: "ssh_agent", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_approval_approve_hint
    public static var sshAgentApprovalApproveHint: String {
        String(localized: "ssh_agent_approval_approve_hint", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_approval_sign_text
    public static var sshAgentApprovalSignText: String {
        String(localized: "ssh_agent_approval_sign_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_filters_header_title
    public static var sshAgentFiltersHeaderTitle: String {
        String(localized: "ssh_agent_filters_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_filters_locked_text
    public static var sshAgentFiltersLockedText: String {
        String(localized: "ssh_agent_filters_locked_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_filters_note_save_to_apply
    public static var sshAgentFiltersNoteSaveToApply: String {
        String(localized: "ssh_agent_filters_note_save_to_apply", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_all_accessible_keys
    public static var sshAgentHistoryAllAccessibleKeys: String {
        String(localized: "ssh_agent_history_all_accessible_keys", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_clear_history_confirmation_text
    public static var sshAgentHistoryClearHistoryConfirmationText: String {
        String(localized: "ssh_agent_history_clear_history_confirmation_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_clear_history_confirmation_title
    public static var sshAgentHistoryClearHistoryConfirmationTitle: String {
        String(localized: "ssh_agent_history_clear_history_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_clear_history_title
    public static var sshAgentHistoryClearHistoryTitle: String {
        String(localized: "ssh_agent_history_clear_history_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_empty_text
    public static var sshAgentHistoryEmptyText: String {
        String(localized: "ssh_agent_history_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_header_title
    public static var sshAgentHistoryHeaderTitle: String {
        String(localized: "ssh_agent_history_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_request_list_keys
    public static var sshAgentHistoryRequestListKeys: String {
        String(localized: "ssh_agent_history_request_list_keys", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_request_sign_data
    public static var sshAgentHistoryRequestSignData: String {
        String(localized: "ssh_agent_history_request_sign_data", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_response_failure
    public static var sshAgentHistoryResponseFailure: String {
        String(localized: "ssh_agent_history_response_failure", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_response_key_not_found
    public static var sshAgentHistoryResponseKeyNotFound: String {
        String(localized: "ssh_agent_history_response_key_not_found", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_response_success
    public static var sshAgentHistoryResponseSuccess: String {
        String(localized: "ssh_agent_history_response_success", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_response_user_denied
    public static var sshAgentHistoryResponseUserDenied: String {
        String(localized: "ssh_agent_history_response_user_denied", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_response_vault_locked
    public static var sshAgentHistoryResponseVaultLocked: String {
        String(localized: "ssh_agent_history_response_vault_locked", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_unknown_caller
    public static var sshAgentHistoryUnknownCaller: String {
        String(localized: "ssh_agent_history_unknown_caller", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_history_unknown_key
    public static var sshAgentHistoryUnknownKey: String {
        String(localized: "ssh_agent_history_unknown_key", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_request_approval_sign_message_known_app
    public static func sshAgentRequestApprovalSignMessageKnownApp(_ a1: String) -> String {
        String(format: String(localized: "ssh_agent_request_approval_sign_message_known_app", bundle: AppLocalization.shared.bundle), a1)
    }
    /// ssh_agent_request_approval_sign_message_unknown_app
    public static var sshAgentRequestApprovalSignMessageUnknownApp: String {
        String(localized: "ssh_agent_request_approval_sign_message_unknown_app", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_request_approval_sign_title
    public static var sshAgentRequestApprovalSignTitle: String {
        String(localized: "ssh_agent_request_approval_sign_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_how_does_it_work_1
    public static var sshAgentSetupAndroidHowDoesItWork1: String {
        String(localized: "ssh_agent_setup_android_how_does_it_work_1", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_how_does_it_work_2
    public static var sshAgentSetupAndroidHowDoesItWork2: String {
        String(localized: "ssh_agent_setup_android_how_does_it_work_2", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_how_does_it_work_title
    public static var sshAgentSetupAndroidHowDoesItWorkTitle: String {
        String(localized: "ssh_agent_setup_android_how_does_it_work_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_1_text
    public static var sshAgentSetupAndroidTermuxStep1Text: String {
        String(localized: "ssh_agent_setup_android_termux_step_1_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_1_title
    public static var sshAgentSetupAndroidTermuxStep1Title: String {
        String(localized: "ssh_agent_setup_android_termux_step_1_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_2_text
    public static var sshAgentSetupAndroidTermuxStep2Text: String {
        String(localized: "ssh_agent_setup_android_termux_step_2_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_2_title
    public static var sshAgentSetupAndroidTermuxStep2Title: String {
        String(localized: "ssh_agent_setup_android_termux_step_2_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_3_shell_current
    public static var sshAgentSetupAndroidTermuxStep3ShellCurrent: String {
        String(localized: "ssh_agent_setup_android_termux_step_3_shell_current", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_3_shell_startup
    public static var sshAgentSetupAndroidTermuxStep3ShellStartup: String {
        String(localized: "ssh_agent_setup_android_termux_step_3_shell_startup", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_3_title
    public static var sshAgentSetupAndroidTermuxStep3Title: String {
        String(localized: "ssh_agent_setup_android_termux_step_3_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_4_text
    public static var sshAgentSetupAndroidTermuxStep4Text: String {
        String(localized: "ssh_agent_setup_android_termux_step_4_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_step_4_title
    public static var sshAgentSetupAndroidTermuxStep4Title: String {
        String(localized: "ssh_agent_setup_android_termux_step_4_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_text
    public static var sshAgentSetupAndroidTermuxText: String {
        String(localized: "ssh_agent_setup_android_termux_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_android_termux_title
    public static var sshAgentSetupAndroidTermuxTitle: String {
        String(localized: "ssh_agent_setup_android_termux_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_client_socket_note
    public static var sshAgentSetupClientSocketNote: String {
        String(localized: "ssh_agent_setup_client_socket_note", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_enable_step_text
    public static var sshAgentSetupEnableStepText: String {
        String(localized: "ssh_agent_setup_enable_step_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_environment_variable_title
    public static var sshAgentSetupEnvironmentVariableTitle: String {
        String(localized: "ssh_agent_setup_environment_variable_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_header_title
    public static var sshAgentSetupHeaderTitle: String {
        String(localized: "ssh_agent_setup_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_intro
    public static var sshAgentSetupIntro: String {
        String(localized: "ssh_agent_setup_intro", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_intro_text
    public static var sshAgentSetupIntroText: String {
        String(localized: "ssh_agent_setup_intro_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_linux_socket_fallback_note
    public static var sshAgentSetupLinuxSocketFallbackNote: String {
        String(localized: "ssh_agent_setup_linux_socket_fallback_note", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_option_env_label
    public static var sshAgentSetupOptionEnvLabel: String {
        String(localized: "ssh_agent_setup_option_env_label", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_option_env_tab
    public static var sshAgentSetupOptionEnvTab: String {
        String(localized: "ssh_agent_setup_option_env_tab", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_option_identityagent_label
    public static var sshAgentSetupOptionIdentityagentLabel: String {
        String(localized: "ssh_agent_setup_option_identityagent_label", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_option_identityagent_tab
    public static var sshAgentSetupOptionIdentityagentTab: String {
        String(localized: "ssh_agent_setup_option_identityagent_tab", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_shell_profile_hint
    public static var sshAgentSetupShellProfileHint: String {
        String(localized: "ssh_agent_setup_shell_profile_hint", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_ssh_config_hint
    public static var sshAgentSetupSshConfigHint: String {
        String(localized: "ssh_agent_setup_ssh_config_hint", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_ssh_config_title
    public static var sshAgentSetupSshConfigTitle: String {
        String(localized: "ssh_agent_setup_ssh_config_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_step_1_text
    public static var sshAgentSetupStep1Text: String {
        String(localized: "ssh_agent_setup_step_1_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_step_1_title
    public static var sshAgentSetupStep1Title: String {
        String(localized: "ssh_agent_setup_step_1_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_step_2_text
    public static var sshAgentSetupStep2Text: String {
        String(localized: "ssh_agent_setup_step_2_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_step_2_title
    public static var sshAgentSetupStep2Title: String {
        String(localized: "ssh_agent_setup_step_2_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_step_3_text
    public static var sshAgentSetupStep3Text: String {
        String(localized: "ssh_agent_setup_step_3_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_step_3_title
    public static var sshAgentSetupStep3Title: String {
        String(localized: "ssh_agent_setup_step_3_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_test_connection_hint
    public static var sshAgentSetupTestConnectionHint: String {
        String(localized: "ssh_agent_setup_test_connection_hint", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_windows_text
    public static var sshAgentSetupWindowsText: String {
        String(localized: "ssh_agent_setup_windows_text", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_agent_setup_windows_title
    public static var sshAgentSetupWindowsTitle: String {
        String(localized: "ssh_agent_setup_windows_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_client
    public static var sshClient: String {
        String(localized: "ssh_client", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_client_request
    public static var sshClientRequest: String {
        String(localized: "ssh_client_request", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_add_key_title
    public static var sshKeyActionAddKeyTitle: String {
        String(localized: "ssh_key_action_add_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_replace_key_title
    public static var sshKeyActionReplaceKeyTitle: String {
        String(localized: "ssh_key_action_replace_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_save_public_key_saved_downloads_success_title
    public static var sshKeyActionSavePublicKeySavedDownloadsSuccessTitle: String {
        String(localized: "ssh_key_action_save_public_key_saved_downloads_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_save_public_key_title
    public static var sshKeyActionSavePublicKeyTitle: String {
        String(localized: "ssh_key_action_save_public_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_save_unencrypted_keys_saved_downloads_success_title
    public static var sshKeyActionSaveUnencryptedKeysSavedDownloadsSuccessTitle: String {
        String(localized: "ssh_key_action_save_unencrypted_keys_saved_downloads_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_save_unencrypted_keys_title
    public static var sshKeyActionSaveUnencryptedKeysTitle: String {
        String(localized: "ssh_key_action_save_unencrypted_keys_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_save_unencrypted_private_key_saved_downloads_success_title
    public static var sshKeyActionSaveUnencryptedPrivateKeySavedDownloadsSuccessTitle: String {
        String(localized: "ssh_key_action_save_unencrypted_private_key_saved_downloads_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_action_save_unencrypted_private_key_title
    public static var sshKeyActionSaveUnencryptedPrivateKeyTitle: String {
        String(localized: "ssh_key_action_save_unencrypted_private_key_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_drop_here
    public static var sshKeyImportDropHere: String {
        String(localized: "ssh_key_import_drop_here", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_error_invalid_passphrase
    public static var sshKeyImportErrorInvalidPassphrase: String {
        String(localized: "ssh_key_import_error_invalid_passphrase", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_error_malformed_key
    public static var sshKeyImportErrorMalformedKey: String {
        String(localized: "ssh_key_import_error_malformed_key", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_error_passphrase_required
    public static var sshKeyImportErrorPassphraseRequired: String {
        String(localized: "ssh_key_import_error_passphrase_required", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_error_read
    public static var sshKeyImportErrorRead: String {
        String(localized: "ssh_key_import_error_read", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_error_unsupported_algorithm
    public static var sshKeyImportErrorUnsupportedAlgorithm: String {
        String(localized: "ssh_key_import_error_unsupported_algorithm", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_error_unsupported_format
    public static var sshKeyImportErrorUnsupportedFormat: String {
        String(localized: "ssh_key_import_error_unsupported_format", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_failed_title
    public static var sshKeyImportFailedTitle: String {
        String(localized: "ssh_key_import_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_passphrase_dialog_message
    public static func sshKeyImportPassphraseDialogMessage(_ a1: String) -> String {
        String(format: String(localized: "ssh_key_import_passphrase_dialog_message", bundle: AppLocalization.shared.bundle), a1)
    }
    /// ssh_key_import_passphrase_dialog_title
    public static var sshKeyImportPassphraseDialogTitle: String {
        String(localized: "ssh_key_import_passphrase_dialog_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_passphrase_hint
    public static var sshKeyImportPassphraseHint: String {
        String(localized: "ssh_key_import_passphrase_hint", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_passphrase_title
    public static var sshKeyImportPassphraseTitle: String {
        String(localized: "ssh_key_import_passphrase_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_success_title
    public static var sshKeyImportSuccessTitle: String {
        String(localized: "ssh_key_import_success_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssh_key_import_title
    public static var sshKeyImportTitle: String {
        String(localized: "ssh_key_import_title", bundle: AppLocalization.shared.bundle)
    }
    /// ssid
    public static var ssid: String {
        String(localized: "ssid", bundle: AppLocalization.shared.bundle)
    }
    /// ssn
    public static var ssn: String {
        String(localized: "ssn", bundle: AppLocalization.shared.bundle)
    }
    /// state
    public static var state: String {
        String(localized: "state", bundle: AppLocalization.shared.bundle)
    }
    /// status
    public static var status: String {
        String(localized: "status", bundle: AppLocalization.shared.bundle)
    }
    /// status_failed_to_start
    public static var statusFailedToStart: String {
        String(localized: "status_failed_to_start", bundle: AppLocalization.shared.bundle)
    }
    /// status_running
    public static var statusRunning: String {
        String(localized: "status_running", bundle: AppLocalization.shared.bundle)
    }
    /// subkeys
    public static var subkeys: String {
        String(localized: "subkeys", bundle: AppLocalization.shared.bundle)
    }
    /// sync
    public static var sync: String {
        String(localized: "sync", bundle: AppLocalization.shared.bundle)
    }
    /// sync_requested
    public static var syncRequested: String {
        String(localized: "sync_requested", bundle: AppLocalization.shared.bundle)
    }
    /// syncstatus_header_title
    public static var syncstatusHeaderTitle: String {
        String(localized: "syncstatus_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// syncstatus_status_failed
    public static var syncstatusStatusFailed: String {
        String(localized: "syncstatus_status_failed", bundle: AppLocalization.shared.bundle)
    }
    /// syncstatus_status_pending
    public static var syncstatusStatusPending: String {
        String(localized: "syncstatus_status_pending", bundle: AppLocalization.shared.bundle)
    }
    /// syncstatus_status_syncing
    public static var syncstatusStatusSyncing: String {
        String(localized: "syncstatus_status_syncing", bundle: AppLocalization.shared.bundle)
    }
    /// syncstatus_status_up_to_date
    public static var syncstatusStatusUpToDate: String {
        String(localized: "syncstatus_status_up_to_date", bundle: AppLocalization.shared.bundle)
    }
    /// tag
    public static var tag: String {
        String(localized: "tag", bundle: AppLocalization.shared.bundle)
    }
    /// tag_none
    public static var tagNone: String {
        String(localized: "tag_none", bundle: AppLocalization.shared.bundle)
    }
    /// tag_value
    public static var tagValue: String {
        String(localized: "tag_value", bundle: AppLocalization.shared.bundle)
    }
    /// tags
    public static var tags: String {
        String(localized: "tags", bundle: AppLocalization.shared.bundle)
    }
    /// tags_empty_label
    public static var tagsEmptyLabel: String {
        String(localized: "tags_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// team_artem_whoami_text
    public static var teamArtemWhoamiText: String {
        String(localized: "team_artem_whoami_text", bundle: AppLocalization.shared.bundle)
    }
    /// team_follow_me_section
    public static var teamFollowMeSection: String {
        String(localized: "team_follow_me_section", bundle: AppLocalization.shared.bundle)
    }
    /// team_header_title
    public static var teamHeaderTitle: String {
        String(localized: "team_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// text
    public static var text: String {
        String(localized: "text", bundle: AppLocalization.shared.bundle)
    }
    /// text_action_send_title
    public static var textActionSendTitle: String {
        String(localized: "text_action_send_title", bundle: AppLocalization.shared.bundle)
    }
    /// text_action_share_with_title
    public static var textActionShareWithTitle: String {
        String(localized: "text_action_share_with_title", bundle: AppLocalization.shared.bundle)
    }
    /// text_clear_action
    public static var textClearAction: String {
        String(localized: "text_clear_action", bundle: AppLocalization.shared.bundle)
    }
    /// tfa_directory_search_placeholder
    public static var tfaDirectorySearchPlaceholder: String {
        String(localized: "tfa_directory_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// tfa_directory_text
    public static var tfaDirectoryText: String {
        String(localized: "tfa_directory_text", bundle: AppLocalization.shared.bundle)
    }
    /// tfa_directory_title
    public static var tfaDirectoryTitle: String {
        String(localized: "tfa_directory_title", bundle: AppLocalization.shared.bundle)
    }
    /// theme_dark
    public static var themeDark: String {
        String(localized: "theme_dark", bundle: AppLocalization.shared.bundle)
    }
    /// theme_light
    public static var themeLight: String {
        String(localized: "theme_light", bundle: AppLocalization.shared.bundle)
    }
    /// time
    public static var time: String {
        String(localized: "time", bundle: AppLocalization.shared.bundle)
    }
    /// tolerance
    public static var tolerance: String {
        String(localized: "tolerance", bundle: AppLocalization.shared.bundle)
    }
    /// tolerance_high
    public static var toleranceHigh: String {
        String(localized: "tolerance_high", bundle: AppLocalization.shared.bundle)
    }
    /// tolerance_low
    public static var toleranceLow: String {
        String(localized: "tolerance_low", bundle: AppLocalization.shared.bundle)
    }
    /// tolerance_max
    public static var toleranceMax: String {
        String(localized: "tolerance_max", bundle: AppLocalization.shared.bundle)
    }
    /// tolerance_min
    public static var toleranceMin: String {
        String(localized: "tolerance_min", bundle: AppLocalization.shared.bundle)
    }
    /// tolerance_normal
    public static var toleranceNormal: String {
        String(localized: "tolerance_normal", bundle: AppLocalization.shared.bundle)
    }
    /// trash
    public static var trash: String {
        String(localized: "trash", bundle: AppLocalization.shared.bundle)
    }
    /// twofa_available
    public static var twofaAvailable: String {
        String(localized: "twofa_available", bundle: AppLocalization.shared.bundle)
    }
    /// type
    public static var type: String {
        String(localized: "type", bundle: AppLocalization.shared.bundle)
    }
    /// unknown
    public static var unknown: String {
        String(localized: "unknown", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_biometric_auth_confirm_text
    public static var unlockBiometricAuthConfirmText: String {
        String(localized: "unlock_biometric_auth_confirm_text", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_biometric_auth_confirm_title
    public static var unlockBiometricAuthConfirmTitle: String {
        String(localized: "unlock_biometric_auth_confirm_title", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_biometric_auth_failed
    public static var unlockBiometricAuthFailed: String {
        String(localized: "unlock_biometric_auth_failed", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_biometric_face_id_title
    public static var unlockBiometricFaceIdTitle: String {
        String(localized: "unlock_biometric_face_id_title", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_biometric_key_unavailable
    public static var unlockBiometricKeyUnavailable: String {
        String(localized: "unlock_biometric_key_unavailable", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_biometric_title
    public static var unlockBiometricTitle: String {
        String(localized: "unlock_biometric_title", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_biometric_touch_id_title
    public static var unlockBiometricTouchIdTitle: String {
        String(localized: "unlock_biometric_touch_id_title", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_button_unlock
    public static var unlockButtonUnlock: String {
        String(localized: "unlock_button_unlock", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_header_text
    public static var unlockHeaderText: String {
        String(localized: "unlock_header_text", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_vault_title
    public static var unlockVaultTitle: String {
        String(localized: "unlock_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// unlock_yubikey_title
    public static var unlockYubikeyTitle: String {
        String(localized: "unlock_yubikey_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri
    public static var uri: String {
        String(localized: "uri", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_autofix_unsecure_text
    public static var uriActionAutofixUnsecureText: String {
        String(localized: "uri_action_autofix_unsecure_text", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_autofix_unsecure_title
    public static var uriActionAutofixUnsecureTitle: String {
        String(localized: "uri_action_autofix_unsecure_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_get_my_data_account_title
    public static var uriActionGetMyDataAccountTitle: String {
        String(localized: "uri_action_get_my_data_account_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_how_to_delete_account_title
    public static var uriActionHowToDeleteAccountTitle: String {
        String(localized: "uri_action_how_to_delete_account_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_launch_app_title
    public static var uriActionLaunchAppTitle: String {
        String(localized: "uri_action_launch_app_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_launch_browser_main_page_title
    public static var uriActionLaunchBrowserMainPageTitle: String {
        String(localized: "uri_action_launch_browser_main_page_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_launch_browser_title
    public static var uriActionLaunchBrowserTitle: String {
        String(localized: "uri_action_launch_browser_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_launch_docs_title
    public static var uriActionLaunchDocsTitle: String {
        String(localized: "uri_action_launch_docs_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_launch_in_app_title
    public static func uriActionLaunchInAppTitle(_ a1: String) -> String {
        String(format: String(localized: "uri_action_launch_in_app_title", bundle: AppLocalization.shared.bundle), a1)
    }
    /// uri_action_launch_in_smth_title
    public static var uriActionLaunchInSmthTitle: String {
        String(localized: "uri_action_launch_in_smth_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_launch_play_store_title
    public static var uriActionLaunchPlayStoreTitle: String {
        String(localized: "uri_action_launch_play_store_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_action_launch_website_title
    public static var uriActionLaunchWebsiteTitle: String {
        String(localized: "uri_action_launch_website_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_app_title
    public static var uriMatchAppTitle: String {
        String(localized: "uri_match_app_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_default_note
    public static var uriMatchDetectionDefaultNote: String {
        String(localized: "uri_match_detection_default_note", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_default_title
    public static var uriMatchDetectionDefaultTitle: String {
        String(localized: "uri_match_detection_default_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_domain_note
    public static var uriMatchDetectionDomainNote: String {
        String(localized: "uri_match_detection_domain_note", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_domain_title
    public static var uriMatchDetectionDomainTitle: String {
        String(localized: "uri_match_detection_domain_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_exact_note
    public static var uriMatchDetectionExactNote: String {
        String(localized: "uri_match_detection_exact_note", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_exact_title
    public static var uriMatchDetectionExactTitle: String {
        String(localized: "uri_match_detection_exact_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_host_note
    public static var uriMatchDetectionHostNote: String {
        String(localized: "uri_match_detection_host_note", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_host_title
    public static var uriMatchDetectionHostTitle: String {
        String(localized: "uri_match_detection_host_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_never_note
    public static var uriMatchDetectionNeverNote: String {
        String(localized: "uri_match_detection_never_note", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_never_title
    public static var uriMatchDetectionNeverTitle: String {
        String(localized: "uri_match_detection_never_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_regex_note
    public static var uriMatchDetectionRegexNote: String {
        String(localized: "uri_match_detection_regex_note", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_regex_title
    public static var uriMatchDetectionRegexTitle: String {
        String(localized: "uri_match_detection_regex_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_startswith_note
    public static var uriMatchDetectionStartswithNote: String {
        String(localized: "uri_match_detection_startswith_note", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_startswith_title
    public static var uriMatchDetectionStartswithTitle: String {
        String(localized: "uri_match_detection_startswith_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_match_detection_title
    public static var uriMatchDetectionTitle: String {
        String(localized: "uri_match_detection_title", bundle: AppLocalization.shared.bundle)
    }
    /// uri_unsecure
    public static var uriUnsecure: String {
        String(localized: "uri_unsecure", bundle: AppLocalization.shared.bundle)
    }
    /// uris
    public static var uris: String {
        String(localized: "uris", bundle: AppLocalization.shared.bundle)
    }
    /// url
    public static var url: String {
        String(localized: "url", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_delete_many_confirmation_title
    public static var urlblockDeleteManyConfirmationTitle: String {
        String(localized: "urlblock_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_delete_one_confirmation_title
    public static var urlblockDeleteOneConfirmationTitle: String {
        String(localized: "urlblock_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_empty_label
    public static var urlblockEmptyLabel: String {
        String(localized: "urlblock_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_expose_text
    public static var urlblockExposeText: String {
        String(localized: "urlblock_expose_text", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_expose_title
    public static var urlblockExposeTitle: String {
        String(localized: "urlblock_expose_title", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_header_title
    public static var urlblockHeaderTitle: String {
        String(localized: "urlblock_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_list_header_title
    public static var urlblockListHeaderTitle: String {
        String(localized: "urlblock_list_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// urlblock_list_section_title
    public static var urlblockListSectionTitle: String {
        String(localized: "urlblock_list_section_title", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_delete_many_confirmation_title
    public static var urloverrideDeleteManyConfirmationTitle: String {
        String(localized: "urloverride_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_delete_one_confirmation_title
    public static var urloverrideDeleteOneConfirmationTitle: String {
        String(localized: "urloverride_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_empty_label
    public static var urloverrideEmptyLabel: String {
        String(localized: "urloverride_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_header_title
    public static var urloverrideHeaderTitle: String {
        String(localized: "urloverride_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_list_empty_text
    public static var urloverrideListEmptyText: String {
        String(localized: "urloverride_list_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_list_header_title
    public static var urloverrideListHeaderTitle: String {
        String(localized: "urloverride_list_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_list_section_title
    public static var urloverrideListSectionTitle: String {
        String(localized: "urloverride_list_section_title", bundle: AppLocalization.shared.bundle)
    }
    /// urloverride_regex_note
    public static var urloverrideRegexNote: String {
        String(localized: "urloverride_regex_note", bundle: AppLocalization.shared.bundle)
    }
    /// urlrule_excluded_label
    public static var urlruleExcludedLabel: String {
        String(localized: "urlrule_excluded_label", bundle: AppLocalization.shared.bundle)
    }
    /// urlrule_global_label
    public static var urlruleGlobalLabel: String {
        String(localized: "urlrule_global_label", bundle: AppLocalization.shared.bundle)
    }
    /// username
    public static var username: String {
        String(localized: "username", bundle: AppLocalization.shared.bundle)
    }
    /// username_action_check_data_breach_title
    public static var usernameActionCheckDataBreachTitle: String {
        String(localized: "username_action_check_data_breach_title", bundle: AppLocalization.shared.bundle)
    }
    /// userverification_button_go
    public static var userverificationButtonGo: String {
        String(localized: "userverification_button_go", bundle: AppLocalization.shared.bundle)
    }
    /// userverification_header_text
    public static var userverificationHeaderText: String {
        String(localized: "userverification_header_text", bundle: AppLocalization.shared.bundle)
    }
    /// valid_from
    public static var validFrom: String {
        String(localized: "valid_from", bundle: AppLocalization.shared.bundle)
    }
    /// vault_action_always_show_keyboard_title
    public static var vaultActionAlwaysShowKeyboardTitle: String {
        String(localized: "vault_action_always_show_keyboard_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_action_lock_vault_title
    public static var vaultActionLockVaultTitle: String {
        String(localized: "vault_action_lock_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_action_remember_sorting_title
    public static var vaultActionRememberSortingTitle: String {
        String(localized: "vault_action_remember_sorting_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_action_rename_folder_title
    public static var vaultActionRenameFolderTitle: String {
        String(localized: "vault_action_rename_folder_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_action_sync_vault_title
    public static var vaultActionSyncVaultTitle: String {
        String(localized: "vault_action_sync_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_duplicates_empty_text
    public static var vaultDuplicatesEmptyText: String {
        String(localized: "vault_duplicates_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// vault_item_sync_failed_text
    public static var vaultItemSyncFailedText: String {
        String(localized: "vault_item_sync_failed_text", bundle: AppLocalization.shared.bundle)
    }
    /// vault_loading_text
    public static var vaultLoadingText: String {
        String(localized: "vault_loading_text", bundle: AppLocalization.shared.bundle)
    }
    /// vault_main_create_item_action
    public static var vaultMainCreateItemAction: String {
        String(localized: "vault_main_create_item_action", bundle: AppLocalization.shared.bundle)
    }
    /// vault_main_empty_add_account_text
    public static var vaultMainEmptyAddAccountText: String {
        String(localized: "vault_main_empty_add_account_text", bundle: AppLocalization.shared.bundle)
    }
    /// vault_main_empty_title
    public static var vaultMainEmptyTitle: String {
        String(localized: "vault_main_empty_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_main_new_item_button
    public static var vaultMainNewItemButton: String {
        String(localized: "vault_main_new_item_button", bundle: AppLocalization.shared.bundle)
    }
    /// vault_main_no_suggested_items
    public static var vaultMainNoSuggestedItems: String {
        String(localized: "vault_main_no_suggested_items", bundle: AppLocalization.shared.bundle)
    }
    /// vault_main_search_placeholder
    public static var vaultMainSearchPlaceholder: String {
        String(localized: "vault_main_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// vault_recents_description
    public static var vaultRecentsDescription: String {
        String(localized: "vault_recents_description", bundle: AppLocalization.shared.bundle)
    }
    /// vault_recents_empty_text
    public static var vaultRecentsEmptyText: String {
        String(localized: "vault_recents_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// vault_recents_empty_title
    public static var vaultRecentsEmptyTitle: String {
        String(localized: "vault_recents_empty_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_recents_locked_text
    public static var vaultRecentsLockedText: String {
        String(localized: "vault_recents_locked_text", bundle: AppLocalization.shared.bundle)
    }
    /// vault_recents_reveal_action
    public static var vaultRecentsRevealAction: String {
        String(localized: "vault_recents_reveal_action", bundle: AppLocalization.shared.bundle)
    }
    /// vault_recents_title
    public static var vaultRecentsTitle: String {
        String(localized: "vault_recents_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_setup_open_keyguard_hint
    public static var vaultSetupOpenKeyguardHint: String {
        String(localized: "vault_setup_open_keyguard_hint", bundle: AppLocalization.shared.bundle)
    }
    /// vault_status_no_vault_title
    public static var vaultStatusNoVaultTitle: String {
        String(localized: "vault_status_no_vault_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_archived_at_label
    public static func vaultViewArchivedAtLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_archived_at_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_call_phone_action
    public static var vaultViewCallPhoneAction: String {
        String(localized: "vault_view_call_phone_action", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_card_cvv_label
    public static var vaultViewCardCvvLabel: String {
        String(localized: "vault_view_card_cvv_label", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_created_at_label
    public static func vaultViewCreatedAtLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_created_at_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_deleted_at_label
    public static func vaultViewDeletedAtLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_deleted_at_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_deletion_scheduled_at_label
    public static func vaultViewDeletionScheduledAtLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_deletion_scheduled_at_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_email_action
    public static var vaultViewEmailAction: String {
        String(localized: "vault_view_email_action", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_expiration_scheduled_at_label
    public static func vaultViewExpirationScheduledAtLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_expiration_scheduled_at_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_expired_at_label
    public static func vaultViewExpiredAtLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_expired_at_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_navigate_action
    public static var vaultViewNavigateAction: String {
        String(localized: "vault_view_navigate_action", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_no_item_selected_title
    public static var vaultViewNoItemSelectedTitle: String {
        String(localized: "vault_view_no_item_selected_title", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_open_in_browser_action
    public static var vaultViewOpenInBrowserAction: String {
        String(localized: "vault_view_open_in_browser_action", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_passkey_created_at_label
    public static func vaultViewPasskeyCreatedAtLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_passkey_created_at_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_password_revision_label
    public static func vaultViewPasswordRevisionLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_password_revision_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_revision_label
    public static func vaultViewRevisionLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_revision_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_saved_to_account_name_label
    public static func vaultViewSavedToAccountNameLabel(_ a1: String) -> String {
        String(format: String(localized: "vault_view_saved_to_account_name_label", bundle: AppLocalization.shared.bundle), a1)
    }
    /// vault_view_saved_to_label
    public static func vaultViewSavedToLabel(_ a1: String, _ a2: String) -> String {
        String(format: String(localized: "vault_view_saved_to_label", bundle: AppLocalization.shared.bundle), a1, a2)
    }
    /// vault_view_select_item_hint
    public static var vaultViewSelectItemHint: String {
        String(localized: "vault_view_select_item_hint", bundle: AppLocalization.shared.bundle)
    }
    /// vault_view_text_phone_action
    public static var vaultViewTextPhoneAction: String {
        String(localized: "vault_view_text_phone_action", bundle: AppLocalization.shared.bundle)
    }
    /// verification_code
    public static var verificationCode: String {
        String(localized: "verification_code", bundle: AppLocalization.shared.bundle)
    }
    /// verify
    public static var verify: String {
        String(localized: "verify", bundle: AppLocalization.shared.bundle)
    }
    /// visible
    public static var visible: String {
        String(localized: "visible", bundle: AppLocalization.shared.bundle)
    }
    /// warning
    public static var warning: String {
        String(localized: "warning", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_2fa_directory_attribution_text
    public static var watchtower2faDirectoryAttributionText: String {
        String(localized: "watchtower_2fa_directory_attribution_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_alerts_empty_text
    public static var watchtowerAlertsEmptyText: String {
        String(localized: "watchtower_alerts_empty_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_alerts_empty_title
    public static var watchtowerAlertsEmptyTitle: String {
        String(localized: "watchtower_alerts_empty_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_alerts_header_title
    public static var watchtowerAlertsHeaderTitle: String {
        String(localized: "watchtower_alerts_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_alerts_new_title
    public static var watchtowerAlertsNewTitle: String {
        String(localized: "watchtower_alerts_new_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_alerts_unread_label
    public static var watchtowerAlertsUnreadLabel: String {
        String(localized: "watchtower_alerts_unread_label", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_header_title
    public static var watchtowerHeaderTitle: String {
        String(localized: "watchtower_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_hibp_attribution_text
    public static var watchtowerHibpAttributionText: String {
        String(localized: "watchtower_hibp_attribution_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_broad_websites_text
    public static var watchtowerItemBroadWebsitesText: String {
        String(localized: "watchtower_item_broad_websites_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_broad_websites_title
    public static var watchtowerItemBroadWebsitesTitle: String {
        String(localized: "watchtower_item_broad_websites_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_compromised_accounts_text
    public static var watchtowerItemCompromisedAccountsText: String {
        String(localized: "watchtower_item_compromised_accounts_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_compromised_accounts_title
    public static var watchtowerItemCompromisedAccountsTitle: String {
        String(localized: "watchtower_item_compromised_accounts_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_duplicate_items_text
    public static var watchtowerItemDuplicateItemsText: String {
        String(localized: "watchtower_item_duplicate_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_duplicate_items_title
    public static var watchtowerItemDuplicateItemsTitle: String {
        String(localized: "watchtower_item_duplicate_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_duplicate_websites_text
    public static var watchtowerItemDuplicateWebsitesText: String {
        String(localized: "watchtower_item_duplicate_websites_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_duplicate_websites_title
    public static var watchtowerItemDuplicateWebsitesTitle: String {
        String(localized: "watchtower_item_duplicate_websites_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_empty_folders_text
    public static var watchtowerItemEmptyFoldersText: String {
        String(localized: "watchtower_item_empty_folders_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_empty_folders_title
    public static var watchtowerItemEmptyFoldersTitle: String {
        String(localized: "watchtower_item_empty_folders_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_expiring_items_text
    public static var watchtowerItemExpiringItemsText: String {
        String(localized: "watchtower_item_expiring_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_expiring_items_title
    public static var watchtowerItemExpiringItemsTitle: String {
        String(localized: "watchtower_item_expiring_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_gpg_key_publishing_text
    public static var watchtowerItemGpgKeyPublishingText: String {
        String(localized: "watchtower_item_gpg_key_publishing_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_gpg_key_publishing_title
    public static var watchtowerItemGpgKeyPublishingTitle: String {
        String(localized: "watchtower_item_gpg_key_publishing_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_inactive_2fa_text
    public static var watchtowerItemInactive2faText: String {
        String(localized: "watchtower_item_inactive_2fa_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_inactive_2fa_title
    public static var watchtowerItemInactive2faTitle: String {
        String(localized: "watchtower_item_inactive_2fa_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_inactive_passkey_text
    public static var watchtowerItemInactivePasskeyText: String {
        String(localized: "watchtower_item_inactive_passkey_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_inactive_passkey_title
    public static var watchtowerItemInactivePasskeyTitle: String {
        String(localized: "watchtower_item_inactive_passkey_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_incomplete_items_text
    public static var watchtowerItemIncompleteItemsText: String {
        String(localized: "watchtower_item_incomplete_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_incomplete_items_title
    public static var watchtowerItemIncompleteItemsTitle: String {
        String(localized: "watchtower_item_incomplete_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_new_alerts_title
    public static var watchtowerItemNewAlertsTitle: String {
        String(localized: "watchtower_item_new_alerts_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_pwned_passwords_text
    public static var watchtowerItemPwnedPasswordsText: String {
        String(localized: "watchtower_item_pwned_passwords_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_pwned_passwords_title
    public static var watchtowerItemPwnedPasswordsTitle: String {
        String(localized: "watchtower_item_pwned_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_reused_passwords_text
    public static var watchtowerItemReusedPasswordsText: String {
        String(localized: "watchtower_item_reused_passwords_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_reused_passwords_title
    public static var watchtowerItemReusedPasswordsTitle: String {
        String(localized: "watchtower_item_reused_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_trashed_items_text
    public static var watchtowerItemTrashedItemsText: String {
        String(localized: "watchtower_item_trashed_items_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_trashed_items_title
    public static var watchtowerItemTrashedItemsTitle: String {
        String(localized: "watchtower_item_trashed_items_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_unsecure_websites_text
    public static var watchtowerItemUnsecureWebsitesText: String {
        String(localized: "watchtower_item_unsecure_websites_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_unsecure_websites_title
    public static var watchtowerItemUnsecureWebsitesTitle: String {
        String(localized: "watchtower_item_unsecure_websites_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_unusable_gpg_keys_text
    public static var watchtowerItemUnusableGpgKeysText: String {
        String(localized: "watchtower_item_unusable_gpg_keys_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_unusable_gpg_keys_title
    public static var watchtowerItemUnusableGpgKeysTitle: String {
        String(localized: "watchtower_item_unusable_gpg_keys_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_vulnerable_accounts_text
    public static var watchtowerItemVulnerableAccountsText: String {
        String(localized: "watchtower_item_vulnerable_accounts_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_vulnerable_accounts_title
    public static var watchtowerItemVulnerableAccountsTitle: String {
        String(localized: "watchtower_item_vulnerable_accounts_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_weak_gpg_keys_text
    public static var watchtowerItemWeakGpgKeysText: String {
        String(localized: "watchtower_item_weak_gpg_keys_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_weak_gpg_keys_title
    public static var watchtowerItemWeakGpgKeysTitle: String {
        String(localized: "watchtower_item_weak_gpg_keys_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_weak_passwords_title
    public static var watchtowerItemWeakPasswordsTitle: String {
        String(localized: "watchtower_item_weak_passwords_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_weak_ssh_keys_text
    public static var watchtowerItemWeakSshKeysText: String {
        String(localized: "watchtower_item_weak_ssh_keys_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_item_weak_ssh_keys_title
    public static var watchtowerItemWeakSshKeysTitle: String {
        String(localized: "watchtower_item_weak_ssh_keys_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_mark_all_as_read_title
    public static var watchtowerMarkAllAsReadTitle: String {
        String(localized: "watchtower_mark_all_as_read_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_notification_new_alerts_title
    public static var watchtowerNotificationNewAlertsTitle: String {
        String(localized: "watchtower_notification_new_alerts_title", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_passkeys_directory_attribution_text
    public static var watchtowerPasskeysDirectoryAttributionText: String {
        String(localized: "watchtower_passkeys_directory_attribution_text", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_section_maintenance_label
    public static var watchtowerSectionMaintenanceLabel: String {
        String(localized: "watchtower_section_maintenance_label", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_section_password_strength_label
    public static var watchtowerSectionPasswordStrengthLabel: String {
        String(localized: "watchtower_section_password_strength_label", bundle: AppLocalization.shared.bundle)
    }
    /// watchtower_section_security_label
    public static var watchtowerSectionSecurityLabel: String {
        String(localized: "watchtower_section_security_label", bundle: AppLocalization.shared.bundle)
    }
    /// web_vault
    public static var webVault: String {
        String(localized: "web_vault", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_choose_folder
    public static var webdavPickerChooseFolder: String {
        String(localized: "webdav_picker_choose_folder", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_empty
    public static var webdavPickerEmpty: String {
        String(localized: "webdav_picker_empty", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_error
    public static var webdavPickerError: String {
        String(localized: "webdav_picker_error", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_filename_exists
    public static var webdavPickerFilenameExists: String {
        String(localized: "webdav_picker_filename_exists", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_filename_extension
    public static func webdavPickerFilenameExtension(_ a1: String) -> String {
        String(format: String(localized: "webdav_picker_filename_extension", bundle: AppLocalization.shared.bundle), a1)
    }
    /// webdav_picker_filename_invalid
    public static var webdavPickerFilenameInvalid: String {
        String(localized: "webdav_picker_filename_invalid", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_filename_required
    public static var webdavPickerFilenameRequired: String {
        String(localized: "webdav_picker_filename_required", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_header_title
    public static var webdavPickerHeaderTitle: String {
        String(localized: "webdav_picker_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_picker_refresh
    public static var webdavPickerRefresh: String {
        String(localized: "webdav_picker_refresh", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_auth_note
    public static var webdavSettingsAuthNote: String {
        String(localized: "webdav_settings_auth_note", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_browse_title
    public static var webdavSettingsBrowseTitle: String {
        String(localized: "webdav_settings_browse_title", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_header_title
    public static var webdavSettingsHeaderTitle: String {
        String(localized: "webdav_settings_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_password_requires_username_error
    public static var webdavSettingsPasswordRequiresUsernameError: String {
        String(localized: "webdav_settings_password_requires_username_error", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_test_success
    public static var webdavSettingsTestSuccess: String {
        String(localized: "webdav_settings_test_success", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_test_text
    public static var webdavSettingsTestText: String {
        String(localized: "webdav_settings_test_text", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_test_text_read_only
    public static var webdavSettingsTestTextReadOnly: String {
        String(localized: "webdav_settings_test_text_read_only", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_test_title
    public static var webdavSettingsTestTitle: String {
        String(localized: "webdav_settings_test_title", bundle: AppLocalization.shared.bundle)
    }
    /// webdav_settings_url_title
    public static var webdavSettingsUrlTitle: String {
        String(localized: "webdav_settings_url_title", bundle: AppLocalization.shared.bundle)
    }
    /// website
    public static var website: String {
        String(localized: "website", bundle: AppLocalization.shared.bundle)
    }
    /// website_action_check_data_breach_title
    public static var websiteActionCheckDataBreachTitle: String {
        String(localized: "website_action_check_data_breach_title", bundle: AppLocalization.shared.bundle)
    }
    /// websiteleak_breach_found_title
    public static var websiteleakBreachFoundTitle: String {
        String(localized: "websiteleak_breach_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// websiteleak_breach_not_found_title
    public static var websiteleakBreachNotFoundTitle: String {
        String(localized: "websiteleak_breach_not_found_title", bundle: AppLocalization.shared.bundle)
    }
    /// websiteleak_breach_occurred_at
    public static func websiteleakBreachOccurredAt(_ a1: String) -> String {
        String(format: String(localized: "websiteleak_breach_occurred_at", bundle: AppLocalization.shared.bundle), a1)
    }
    /// websiteleak_breach_reported_at
    public static func websiteleakBreachReportedAt(_ a1: String) -> String {
        String(format: String(localized: "websiteleak_breach_reported_at", bundle: AppLocalization.shared.bundle), a1)
    }
    /// websiteleak_breach_section
    public static var websiteleakBreachSection: String {
        String(localized: "websiteleak_breach_section", bundle: AppLocalization.shared.bundle)
    }
    /// websiteleak_note
    public static var websiteleakNote: String {
        String(localized: "websiteleak_note", bundle: AppLocalization.shared.bundle)
    }
    /// websiteleak_title
    public static var websiteleakTitle: String {
        String(localized: "websiteleak_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist
    public static var wordlist: String {
        String(localized: "wordlist", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_add_wordlist_title
    public static var wordlistAddWordlistTitle: String {
        String(localized: "wordlist_add_wordlist_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_add_wordlist_via_file_title
    public static var wordlistAddWordlistViaFileTitle: String {
        String(localized: "wordlist_add_wordlist_via_file_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_add_wordlist_via_url_title
    public static var wordlistAddWordlistViaUrlTitle: String {
        String(localized: "wordlist_add_wordlist_via_url_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_delete_many_confirmation_title
    public static var wordlistDeleteManyConfirmationTitle: String {
        String(localized: "wordlist_delete_many_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_delete_one_confirmation_title
    public static var wordlistDeleteOneConfirmationTitle: String {
        String(localized: "wordlist_delete_one_confirmation_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_delete_permanent_warning
    public static func wordlistDeletePermanentWarning(_ a1: String) -> String {
        String(format: String(localized: "wordlist_delete_permanent_warning", bundle: AppLocalization.shared.bundle), a1)
    }
    /// wordlist_edit_wordlist_title
    public static var wordlistEditWordlistTitle: String {
        String(localized: "wordlist_edit_wordlist_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_empty_label
    public static var wordlistEmptyLabel: String {
        String(localized: "wordlist_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_list_header_title
    public static var wordlistListHeaderTitle: String {
        String(localized: "wordlist_list_header_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_list_import_hint
    public static var wordlistListImportHint: String {
        String(localized: "wordlist_list_import_hint", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_list_section_title
    public static var wordlistListSectionTitle: String {
        String(localized: "wordlist_list_section_title", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_rename_action
    public static var wordlistRenameAction: String {
        String(localized: "wordlist_rename_action", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_word_search_placeholder
    public static var wordlistWordSearchPlaceholder: String {
        String(localized: "wordlist_word_search_placeholder", bundle: AppLocalization.shared.bundle)
    }
    /// wordlist_words_empty_label
    public static var wordlistWordsEmptyLabel: String {
        String(localized: "wordlist_words_empty_label", bundle: AppLocalization.shared.bundle)
    }
    /// yes
    public static var yes: String {
        String(localized: "yes", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_error_busy
    public static var yubikeyErrorBusy: String {
        String(localized: "yubikey_error_busy", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_error_failed_to_read
    public static var yubikeyErrorFailedToRead: String {
        String(localized: "yubikey_error_failed_to_read", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_error_multiple_devices
    public static var yubikeyErrorMultipleDevices: String {
        String(localized: "yubikey_error_multiple_devices", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_error_no_device
    public static var yubikeyErrorNoDevice: String {
        String(localized: "yubikey_error_no_device", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_error_title
    public static var yubikeyErrorTitle: String {
        String(localized: "yubikey_error_title", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_nfc_text
    public static var yubikeyNfcText: String {
        String(localized: "yubikey_nfc_text", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_nfc_title
    public static var yubikeyNfcTitle: String {
        String(localized: "yubikey_nfc_title", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_slot_1_label
    public static var yubikeySlot1Label: String {
        String(localized: "yubikey_slot_1_label", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_slot_2_label
    public static var yubikeySlot2Label: String {
        String(localized: "yubikey_slot_2_label", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_slot_configured_warning
    public static func yubikeySlotConfiguredWarning(_ a1: Int) -> String {
        String(format: String(localized: "yubikey_slot_configured_warning", bundle: AppLocalization.shared.bundle), a1)
    }
    /// yubikey_slot_overwrite_action
    public static var yubikeySlotOverwriteAction: String {
        String(localized: "yubikey_slot_overwrite_action", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_slot_overwrite_warning
    public static var yubikeySlotOverwriteWarning: String {
        String(localized: "yubikey_slot_overwrite_warning", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_slot_picker_note
    public static var yubikeySlotPickerNote: String {
        String(localized: "yubikey_slot_picker_note", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_slot_picker_title
    public static var yubikeySlotPickerTitle: String {
        String(localized: "yubikey_slot_picker_title", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_slot_use_existing_action
    public static var yubikeySlotUseExistingAction: String {
        String(localized: "yubikey_slot_use_existing_action", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_unlock_error_failed_text
    public static var yubikeyUnlockErrorFailedText: String {
        String(localized: "yubikey_unlock_error_failed_text", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_unlock_error_failed_title
    public static var yubikeyUnlockErrorFailedTitle: String {
        String(localized: "yubikey_unlock_error_failed_title", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_unlock_error_unsupported_text
    public static var yubikeyUnlockErrorUnsupportedText: String {
        String(localized: "yubikey_unlock_error_unsupported_text", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_unlock_error_unsupported_title
    public static var yubikeyUnlockErrorUnsupportedTitle: String {
        String(localized: "yubikey_unlock_error_unsupported_title", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_usb_text
    public static var yubikeyUsbText: String {
        String(localized: "yubikey_usb_text", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_usb_title
    public static var yubikeyUsbTitle: String {
        String(localized: "yubikey_usb_title", bundle: AppLocalization.shared.bundle)
    }
    /// yubikey_usb_touch_the_gold_sensor_note
    public static var yubikeyUsbTouchTheGoldSensorNote: String {
        String(localized: "yubikey_usb_touch_the_gold_sensor_note", bundle: AppLocalization.shared.bundle)
    }
    /// zone_id
    public static var zoneId: String {
        String(localized: "zone_id", bundle: AppLocalization.shared.bundle)
    }
    /// character_count_plural (plural)
    public static func characterCountPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("character_count_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_accounts_note (plural)
    public static func credentialExchangeImportReviewAccountsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_accounts_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_cards_note (plural)
    public static func credentialExchangeImportReviewCardsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_cards_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_folders_note (plural)
    public static func credentialExchangeImportReviewFoldersNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_folders_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_identities_note (plural)
    public static func credentialExchangeImportReviewIdentitiesNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_identities_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_logins_note (plural)
    public static func credentialExchangeImportReviewLoginsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_logins_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_notes_note (plural)
    public static func credentialExchangeImportReviewNotesNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_notes_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_otp_note (plural)
    public static func credentialExchangeImportReviewOtpNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_otp_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_passkeys_note (plural)
    public static func credentialExchangeImportReviewPasskeysNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_passkeys_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_review_ssh_keys_note (plural)
    public static func credentialExchangeImportReviewSshKeysNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_review_ssh_keys_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// credential_exchange_import_success_text (plural)
    public static func credentialExchangeImportSuccessText(_ count: Int) -> String {
        String(
            format: NSLocalizedString("credential_exchange_import_success_text", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// days_plural (plural)
    public static func daysPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("days_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// emailleak_breach_accounts_count_plural (plural)
    public static func emailleakBreachAccountsCountPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("emailleak_breach_accounts_count_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// gpg_keyserver_upload_verify_requested_plural (plural)
    public static func gpgKeyserverUploadVerifyRequestedPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("gpg_keyserver_upload_verify_requested_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// hours_plural (plural)
    public static func hoursPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("hours_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// minutes_plural (plural)
    public static func minutesPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("minutes_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// months_plural (plural)
    public static func monthsPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("months_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// passwordleak_occurrences_count_plural (plural)
    public static func passwordleakOccurrencesCountPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("passwordleak_occurrences_count_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// pref_item_automatic_backups_retention_keep_snapshot_count (plural)
    public static func prefItemAutomaticBackupsRetentionKeepSnapshotCount(_ count: Int) -> String {
        String(
            format: NSLocalizedString("pref_item_automatic_backups_retention_keep_snapshot_count", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// result_count_plural (plural)
    public static func resultCountPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("result_count_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// reused_password_items_count_plural (plural)
    public static func reusedPasswordItemsCountPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("reused_password_items_count_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// seconds_plural (plural)
    public static func secondsPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("seconds_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_accounts_note (plural)
    public static func skippedAccountsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_accounts_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_and_more_note (plural)
    public static func skippedAndMoreNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_and_more_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_archived_items_note (plural)
    public static func skippedArchivedItemsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_archived_items_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_attachments_note (plural)
    public static func skippedAttachmentsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_attachments_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_duplicate_credentials_note (plural)
    public static func skippedDuplicateCredentialsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_duplicate_credentials_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_folders_note (plural)
    public static func skippedFoldersNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_folders_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_gpg_keys_note (plural)
    public static func skippedGpgKeysNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_gpg_keys_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_items_note (plural)
    public static func skippedItemsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_items_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_otp_note (plural)
    public static func skippedOtpNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_otp_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_passkeys_note (plural)
    public static func skippedPasskeysNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_passkeys_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_password_history_note (plural)
    public static func skippedPasswordHistoryNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_password_history_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_ssh_keys_note (plural)
    public static func skippedSshKeysNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_ssh_keys_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// skipped_unsupported_credentials_note (plural)
    public static func skippedUnsupportedCredentialsNote(_ count: Int) -> String {
        String(
            format: NSLocalizedString("skipped_unsupported_credentials_note", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// weeks_plural (plural)
    public static func weeksPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("weeks_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// word_count_plural (plural)
    public static func wordCountPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("word_count_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
    /// years_plural (plural)
    public static func yearsPlural(_ count: Int) -> String {
        String(
            format: NSLocalizedString("years_plural", bundle: AppLocalization.shared.bundle, comment: ""),
            locale: AppLocalization.shared.locale,
            count
        )
    }
}
