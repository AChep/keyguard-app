package com.artemchep.keyguard.feature.home.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.util.flow.foldAsList
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.settings.component.SettingComponent
import com.artemchep.keyguard.feature.home.settings.component.settingAboutAppBuildDateProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAboutAppProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAboutTeamProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAboutTelegramProvider
import com.artemchep.keyguard.feature.home.settings.component.settingApkProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAppIconsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAutofillCopyTotpProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAutofillInlineSuggestionsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAutofillManualSelectionProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAutofillProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAutofillRespectAutofillOffProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAutofillSaveRequestProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAutofillSaveUriProvider
import com.artemchep.keyguard.feature.home.settings.component.settingBiometricsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingCheckPwnedPasswordsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingCheckPwnedServicesProvider
import com.artemchep.keyguard.feature.home.settings.component.settingCheckTwoFAProvider
import com.artemchep.keyguard.feature.home.settings.component.settingClearCache
import com.artemchep.keyguard.feature.home.settings.component.settingClipboardAutoClearProvider
import com.artemchep.keyguard.feature.home.settings.component.settingClipboardAutoRefreshProvider
import com.artemchep.keyguard.feature.home.settings.component.settingColorAccentProvider
import com.artemchep.keyguard.feature.home.settings.component.settingColorSchemeProvider
import com.artemchep.keyguard.feature.home.settings.component.settingConcealFieldsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingCrashProvider
import com.artemchep.keyguard.feature.home.settings.component.settingCrashlyticsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingCredentialProviderProvider
import com.artemchep.keyguard.feature.home.settings.component.settingDataSafetyProvider
import com.artemchep.keyguard.feature.home.settings.component.settingEmitMessageProvider
import com.artemchep.keyguard.feature.home.settings.component.settingExperimentalProvider
import com.artemchep.keyguard.feature.home.settings.component.settingFeaturesOverviewProvider
import com.artemchep.keyguard.feature.home.settings.component.settingFeedbackAppProvider
import com.artemchep.keyguard.feature.home.settings.component.settingFontProvider
import com.artemchep.keyguard.feature.home.settings.component.settingGitHubProvider
import com.artemchep.keyguard.feature.home.settings.component.settingKeepScreenOnProvider
import com.artemchep.keyguard.feature.home.settings.component.settingLaunchAppPicker
import com.artemchep.keyguard.feature.home.settings.component.settingLaunchYubiKey
import com.artemchep.keyguard.feature.home.settings.component.settingLocalizationProvider
import com.artemchep.keyguard.feature.home.settings.component.settingMarkdownProvider
import com.artemchep.keyguard.feature.home.settings.component.settingMasterPasswordProvider
import com.artemchep.keyguard.feature.home.settings.component.settingNavAnimationProvider
import com.artemchep.keyguard.feature.home.settings.component.settingNavLabelProvider
import com.artemchep.keyguard.feature.home.settings.component.settingOpenSourceLicensesProvider
import com.artemchep.keyguard.feature.home.settings.component.settingPermissionCameraProvider
import com.artemchep.keyguard.feature.home.settings.component.settingPermissionDetailsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingPermissionOtherProvider
import com.artemchep.keyguard.feature.home.settings.component.settingPermissionPostNotificationsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingPrivacyPolicyProvider
import com.artemchep.keyguard.feature.home.settings.component.settingRateAppProvider
import com.artemchep.keyguard.feature.home.settings.component.settingRequireMasterPasswordProvider
import com.artemchep.keyguard.feature.home.settings.component.settingRotateDeviceId
import com.artemchep.keyguard.feature.home.settings.component.settingScreenDelay
import com.artemchep.keyguard.feature.home.settings.component.settingScreenshotsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingSectionProvider
import com.artemchep.keyguard.feature.home.settings.component.settingSelectLocaleProvider
import com.artemchep.keyguard.feature.home.settings.component.settingSubscriptionsDebug
import com.artemchep.keyguard.feature.home.settings.component.settingSubscriptionsPlayStoreProvider
import com.artemchep.keyguard.feature.home.settings.component.settingSubscriptionsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingThemeUseAmoledDarkProvider
import com.artemchep.keyguard.feature.home.settings.component.settingTwoPanelLayoutLandscapeProvider
import com.artemchep.keyguard.feature.home.settings.component.settingTwoPanelLayoutPortraitProvider
import com.artemchep.keyguard.feature.home.settings.component.settingUseExternalBrowserProvider
import com.artemchep.keyguard.feature.home.settings.component.settingVaultLockAfterRebootProvider
import com.artemchep.keyguard.feature.home.settings.component.settingVaultLockAfterScreenOffProvider
import com.artemchep.keyguard.feature.home.settings.component.settingVaultLockAfterTimeoutProvider
import com.artemchep.keyguard.feature.home.settings.component.settingVaultLockProvider
import com.artemchep.keyguard.feature.home.settings.component.settingVaultPersistProvider
import com.artemchep.keyguard.feature.home.settings.component.settingWebsiteIconsProvider
import com.artemchep.keyguard.feature.home.settings.component.settingWriteAccessProvider
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.items.FlatItemSkeleton
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct

object Setting {
    const val DIVIDER = "divider"

    const val CREDENTIAL_PROVIDER = "credential_provider"
    const val AUTOFILL = "autofill"
    const val AUTOFILL_INLINE_SUGGESTIONS = "autofill_inline_suggestions"
    const val AUTOFILL_MANUAL_SELECTION = "autofill_manual_selection"
    const val AUTOFILL_RESPECT_AUTOFILL_OFF = "autofill_respect_autofill_off"
    const val AUTOFILL_SAVE_REQUEST = "autofill_save_request"
    const val AUTOFILL_SAVE_URI = "autofill_save_uri"
    const val AUTOFILL_COPY_TOTP = "autofill_copy_totp"
    const val NAV_ANIMATION = "nav_animation"
    const val NAV_LABEL = "nav_label"
    const val FONT = "font"
    const val LOCALE = "locale"
    const val COLOR_SCHEME = "color_scheme"
    const val COLOR_SCHEME_AMOLED_DARK = "color_scheme_amoled_dark"
    const val COLOR_ACCENT = "color_accent"
    const val MASTER_PASSWORD = "master_password"
    const val PERMISSION_DETAILS = "permission_details" // screen
    const val PERMISSION_OTHER = "permission_other"
    const val PERMISSION_CAMERA = "permission_camera"
    const val PERMISSION_POST_NOTIFICATION = "permission_post_notification"
    const val BIOMETRIC = "biometric"
    const val VAULT_PERSIST = "vault_persist"
    const val VAULT_LOCK = "vault_lock"
    const val VAULT_LOCK_AFTER_REBOOT = "vault_lock_after_reboot"
    const val VAULT_LOCK_AFTER_SCREEN_OFF = "vault_screen_lock"
    const val VAULT_LOCK_AFTER_TIMEOUT = "vault_timeout"
    const val ROTATE_DEVICE_ID = "rotate_device_id"
    const val CLIPBOARD_AUTO_CLEAR = "clipboard_auto_clear"
    const val CLIPBOARD_AUTO_REFRESH = "clipboard_auto_refresh"
    const val CRASH = "test_crash_reports"
    const val CRASHLYTICS = "automatic_crash_reports"
    const val REQUIRE_MASTER_PASSWORD = "require_master_password"
    const val EMIT_MESSAGE = "emit_message"
    const val FEEDBACK_APP = "feedback_app"
    const val REDDIT = "reddit"
    const val CROWDIN = "crowdin"
    const val GITHUB = "github"
    const val PRIVACY_POLICY = "privacy_policy"
    const val OPEN_SOURCE_LICENSES = "open_source_licenses"
    const val ABOUT_APP = "about_app"
    const val ABOUT_APP_BUILD_DATE = "about_app_build_date"
    const val ABOUT_TEAM = "about_team"
    const val EXPERIMENTAL = "experimental"
    const val LAUNCH_APP_PICKER = "launch_app_picker"
    const val LAUNCH_YUBIKEY = "launch_yubikey"
    const val DATA_SAFETY = "data_safety"
    const val FEATURES_OVERVIEW = "features_overview"
    const val RATE_APP = "rate_app"
    const val CONCEAL = "conceal"
    const val MARKDOWN = "markdown"
    const val WRITE_ACCESS = "write_access"
    const val SCREENSHOTS = "screenshots"
    const val TWO_PANEL_LAYOUT_LANDSCAPE = "two_panel_layout_landscape"
    const val TWO_PANEL_LAYOUT_PORTRAIT = "two_panel_layout_portrait"
    const val USE_EXTERNAL_BROWSER = "use_external_browser"
    const val APP_ICONS = "app_icons"
    const val WEBSITE_ICONS = "website_icons"
    const val CHECK_PWNED_PASSWORDS = "check_pwned_passwords"
    const val CHECK_PWNED_SERVICES = "check_pwned_services"
    const val CHECK_TWO_FA = "check_two_fa"
    const val CLEAR_CACHE = "clear_cache"
    const val APK = "apk"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SUBSCRIPTIONS_IN_STORE = "subscriptions_in_store"
    const val SUBSCRIPTIONS_DEBUG = "subscriptions_debug"
    const val SCREEN_DELAY = "screen_delay"
    const val KEEP_SCREEN_ON = "keep_screen_on"
}

val LocalSettingItemArgs = staticCompositionLocalOf<Any?> {
    null
}

val hub = mapOf<String, (DirectDI) -> SettingComponent>(
    Setting.CREDENTIAL_PROVIDER to ::settingCredentialProviderProvider,
    Setting.AUTOFILL to ::settingAutofillProvider,
    Setting.AUTOFILL_INLINE_SUGGESTIONS to ::settingAutofillInlineSuggestionsProvider,
    Setting.AUTOFILL_MANUAL_SELECTION to ::settingAutofillManualSelectionProvider,
    Setting.AUTOFILL_RESPECT_AUTOFILL_OFF to ::settingAutofillRespectAutofillOffProvider,
    Setting.AUTOFILL_SAVE_REQUEST to ::settingAutofillSaveRequestProvider,
    Setting.AUTOFILL_SAVE_URI to ::settingAutofillSaveUriProvider,
    Setting.AUTOFILL_COPY_TOTP to ::settingAutofillCopyTotpProvider,
    Setting.NAV_ANIMATION to ::settingNavAnimationProvider,
    Setting.NAV_LABEL to ::settingNavLabelProvider,
    Setting.FONT to ::settingFontProvider,
    Setting.LOCALE to ::settingSelectLocaleProvider,
    Setting.COLOR_SCHEME to ::settingColorSchemeProvider,
    Setting.COLOR_SCHEME_AMOLED_DARK to ::settingThemeUseAmoledDarkProvider,
    Setting.COLOR_ACCENT to ::settingColorAccentProvider,
    Setting.MASTER_PASSWORD to ::settingMasterPasswordProvider,
    Setting.PERMISSION_DETAILS to ::settingPermissionDetailsProvider,
    Setting.PERMISSION_OTHER to ::settingPermissionOtherProvider,
    Setting.PERMISSION_CAMERA to ::settingPermissionCameraProvider,
    Setting.PERMISSION_POST_NOTIFICATION to ::settingPermissionPostNotificationsProvider,
    Setting.BIOMETRIC to ::settingBiometricsProvider,
    Setting.VAULT_PERSIST to ::settingVaultPersistProvider,
    Setting.VAULT_LOCK to ::settingVaultLockProvider,
    Setting.VAULT_LOCK_AFTER_REBOOT to ::settingVaultLockAfterRebootProvider,
    Setting.VAULT_LOCK_AFTER_SCREEN_OFF to ::settingVaultLockAfterScreenOffProvider,
    Setting.VAULT_LOCK_AFTER_TIMEOUT to ::settingVaultLockAfterTimeoutProvider,
    Setting.ROTATE_DEVICE_ID to ::settingRotateDeviceId,
    Setting.CLIPBOARD_AUTO_CLEAR to ::settingClipboardAutoClearProvider,
    Setting.CLIPBOARD_AUTO_REFRESH to ::settingClipboardAutoRefreshProvider,
    Setting.CRASH to ::settingCrashProvider,
    Setting.CRASHLYTICS to ::settingCrashlyticsProvider,
    Setting.REQUIRE_MASTER_PASSWORD to ::settingRequireMasterPasswordProvider,
    Setting.EMIT_MESSAGE to ::settingEmitMessageProvider,
    Setting.FEEDBACK_APP to ::settingFeedbackAppProvider,
    Setting.ABOUT_APP to ::settingAboutAppProvider,
    Setting.ABOUT_APP_BUILD_DATE to ::settingAboutAppBuildDateProvider,
    Setting.ABOUT_TEAM to ::settingAboutTeamProvider,
    Setting.REDDIT to ::settingAboutTelegramProvider,
    Setting.CROWDIN to ::settingLocalizationProvider,
    Setting.GITHUB to ::settingGitHubProvider,
    Setting.PRIVACY_POLICY to ::settingPrivacyPolicyProvider,
    Setting.OPEN_SOURCE_LICENSES to ::settingOpenSourceLicensesProvider,
    Setting.EXPERIMENTAL to ::settingExperimentalProvider,
    Setting.LAUNCH_YUBIKEY to ::settingLaunchYubiKey,
    Setting.LAUNCH_APP_PICKER to ::settingLaunchAppPicker,
    Setting.DATA_SAFETY to ::settingDataSafetyProvider,
    Setting.FEATURES_OVERVIEW to ::settingFeaturesOverviewProvider,
    Setting.RATE_APP to ::settingRateAppProvider,
    Setting.DIVIDER to ::settingSectionProvider,
    Setting.CONCEAL to ::settingConcealFieldsProvider,
    Setting.MARKDOWN to ::settingMarkdownProvider,
    Setting.WRITE_ACCESS to ::settingWriteAccessProvider,
    Setting.SCREENSHOTS to ::settingScreenshotsProvider,
    Setting.TWO_PANEL_LAYOUT_LANDSCAPE to ::settingTwoPanelLayoutLandscapeProvider,
    Setting.TWO_PANEL_LAYOUT_PORTRAIT to ::settingTwoPanelLayoutPortraitProvider,
    Setting.USE_EXTERNAL_BROWSER to ::settingUseExternalBrowserProvider,
    Setting.APP_ICONS to ::settingAppIconsProvider,
    Setting.WEBSITE_ICONS to ::settingWebsiteIconsProvider,
    Setting.CHECK_PWNED_PASSWORDS to ::settingCheckPwnedPasswordsProvider,
    Setting.CHECK_PWNED_SERVICES to ::settingCheckPwnedServicesProvider,
    Setting.CHECK_TWO_FA to ::settingCheckTwoFAProvider,
    Setting.CLEAR_CACHE to ::settingClearCache,
    Setting.APK to ::settingApkProvider,
    Setting.SUBSCRIPTIONS to ::settingSubscriptionsProvider,
    Setting.SUBSCRIPTIONS_IN_STORE to ::settingSubscriptionsPlayStoreProvider,
    Setting.SUBSCRIPTIONS_DEBUG to ::settingSubscriptionsDebug,
    Setting.SCREEN_DELAY to ::settingScreenDelay,
    Setting.KEEP_SCREEN_ON to ::settingKeepScreenOnProvider,
)

@Composable
fun SettingPaneContent(
    title: String,
    items: List<SettingPaneItem>,
) {
    val di = localDI()
    val state by remember(items, di, hub) {
        fun create(
            item: SettingPaneItem.Item,
            group: String = "",
            args: Any? = null,
        ) = hub[item.key]
            ?.invoke(di.direct)
            ?.map { content ->
                val compositeKey = group + ":" + item.key + ":" + item.suffix
                SettingPaneState.Component(
                    compositeKey = compositeKey,
                    itemKey = item.key,
                    groupKey = group,
                    args = args,
                    content = content?.content,
                )
            }

        val componentDivider = "divider"
        val componentFlows = mutableListOf<Flow<SettingPaneState.Component>>()
        items.forEach { item ->
            when (item) {
                is SettingPaneItem.Group -> {
                    val shouldAddDivider =
                    // Do not add a divider on top of the
                        // blank list.
                        componentFlows.isNotEmpty() || !item.title.isNullOrBlank()
                    if (shouldAddDivider) {
                        val divider = create(
                            item = SettingPaneItem.Item(componentDivider),
                            group = item.key,
                            args = item.toSectionArgs(),
                        )
                        if (divider != null) componentFlows.add(divider)
                    }
                    item.list.forEach { childItem ->
                        val e = create(childItem, group = item.key)
                        if (e != null) componentFlows.add(e)
                    }
                }

                is SettingPaneItem.Item -> {
                    val e = create(item)
                    if (e != null) componentFlows.add(e)
                }
            }
        }
        componentFlows
            .foldAsList()
            .map { components ->
                val result = mutableListOf<SettingPaneState.Component>()
                val filteredComponents = components
                    .filter { it.content != null }
                filteredComponents
                    .forEachIndexed { index, component ->
                        val isDivider = component.itemKey == componentDivider
                        if (isDivider) {
                            val hasItemsFromTheSameGroup =
                                component.groupKey == filteredComponents.getOrNull(index + 1)?.groupKey
                            if (result.isNotEmpty() && hasItemsFromTheSameGroup) {
                                result.add(component)
                            }
                        } else {
                            result.add(component)
                        }
                    }
                SettingPaneState(
                    list = Loadable.Ok(result.toImmutableList()),
                )
            }
    }.collectAsState(initial = SettingPaneState())

    SettingPaneContent2(
        title = title,
        state = state,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterialApi::class,
)
@Composable
fun SettingPaneContent2(
    title: String,
    state: SettingPaneState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(title)
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        when (val contentState = state.list) {
            is Loadable.Loading -> {
                item("skeleton") {
                    FlatItemSkeleton()
                }
            }

            is Loadable.Ok -> {
                val list = contentState.value
                if (list.isEmpty()) {
                    item("empty") {
                        EmptyView()
                    }
                }

                items(
                    items = list,
                    key = { model -> model.compositeKey },
                ) { model ->
                    CompositionLocalProvider(
                        LocalSettingItemArgs provides model.args,
                    ) {
                        model.content?.invoke()
                    }
                }
            }
        }
    }
}
