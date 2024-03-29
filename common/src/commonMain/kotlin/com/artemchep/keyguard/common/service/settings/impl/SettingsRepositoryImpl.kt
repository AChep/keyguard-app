package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.model.AppVersionLog
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.asDuration
import com.artemchep.keyguard.common.service.keyvalue.getEnumNullable
import com.artemchep.keyguard.common.service.keyvalue.getObject
import com.artemchep.keyguard.common.service.keyvalue.getSerializable
import com.artemchep.keyguard.common.service.keyvalue.setAndCommit
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.entity.VersionLogEntity
import com.artemchep.keyguard.common.service.settings.entity.of
import com.artemchep.keyguard.common.service.settings.entity.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

/**
 * @author Artem Chepurnyi
 */
class SettingsRepositoryImpl(
    private val store: KeyValueStore,
    private val json: Json,
) : SettingsReadWriteRepository {
    companion object {
        private const val KEY_AUTOFILL_INLINE_SUGGESTIONS = "autofill.inline_suggestions"
        private const val KEY_AUTOFILL_MANUAL_SELECTION = "autofill.manual_selection"
        private const val KEY_AUTOFILL_RESPECT_AUTOFILL_OFF = "autofill.respect_autofill_off"
        private const val KEY_AUTOFILL_SAVE_REQUEST = "autofill.save_request"
        private const val KEY_AUTOFILL_SAVE_URI = "autofill.save_uri"
        private const val KEY_AUTOFILL_COPY_TOTP = "autofill.copy_totp"
        private const val KEY_VAULT_PERSIST = "vault_persist"
        private const val KEY_VAULT_REBOOT = "vault_reboot"
        private const val KEY_VAULT_TIMEOUT = "vault_timeout"
        private const val KEY_VAULT_SCREEN_LOCK = "vault_screen_lock"
        private const val KEY_BIOMETRIC_TIMEOUT = "biometric_timeout"
        private const val KEY_BIOMETRIC_REQUIRE_CONFIRMATION = "biometric_require_confirmation"
        private const val KEY_CLIPBOARD_CLEAR_DELAY = "clipboard_clear_delay"
        private const val KEY_CLIPBOARD_UPDATE_DURATION = "clipboard_update_duration"
        private const val KEY_CONCEAL_FIELDS = "conceal_fields"
        private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        private const val KEY_CHECK_PWNED_PASSWORDS = "check_pwned_passwords"
        private const val KEY_CHECK_PWNED_SERVICES = "check_pwned_services"
        private const val KEY_CHECK_TWO_FA = "check_two_fa"
        private const val KEY_WRITE_ACCESS = "exp.22.write_access"
        private const val KEY_DEBUG_PREMIUM = "debug_premium"
        private const val KEY_DEBUG_SCREEN_DELAY = "debug_screen_delay"
        private const val KEY_CACHE_PREMIUM = "cache_premium"
        private const val KEY_APP_ICONS = "app_icons"
        private const val KEY_WEBSITE_ICONS = "website_icons"
        private const val KEY_MARKDOWN = "markdown"
        private const val KEY_VERSION_LOG = "version_log"
        private const val KEY_NAV_ANIMATION = "nav_animation"
        private const val KEY_NAV_LABEL = "nav_label"
        private const val KEY_TWO_PANEL_LAYOUT_LANDSCAPE = "two_panel_layout_landscape"
        private const val KEY_TWO_PANEL_LAYOUT_PORTRAIT = "two_panel_layout_portrait"
        private const val KEY_USE_EXTERNAL_BROWSER = "use_external_browser"
        private const val KEY_CLOSE_TO_TRAY = "close_to_tray"
        private const val KEY_FONT = "font"
        private const val KEY_THEME = "theme"
        private const val KEY_THEME_USE_AMOLED_DARK = "theme_use_amoled_dark"
        private const val KEY_ONBOARDING_LAST_VISIT = "onboarding_last_visit"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_COLORS = "colors"
        private const val KEY_LOCALE = "locale"

        private const val NONE_DURATION = -1L
    }

    private val autofillInlineSuggestionsPref =
        store.getBoolean(KEY_AUTOFILL_INLINE_SUGGESTIONS, true)

    private val autofillManualSelectionPref =
        store.getBoolean(KEY_AUTOFILL_MANUAL_SELECTION, true)

    private val autofillRespectAutofillOffPref =
        store.getBoolean(KEY_AUTOFILL_RESPECT_AUTOFILL_OFF, false)

    private val autofillSaveRequestPref =
        store.getBoolean(KEY_AUTOFILL_SAVE_REQUEST, true)

    private val autofillSaveUriPref =
        store.getBoolean(KEY_AUTOFILL_SAVE_URI, false)

    private val autofillCopyTotpPref =
        store.getBoolean(KEY_AUTOFILL_COPY_TOTP, true)

    private val vaultPersistPref = store.getBoolean(KEY_VAULT_PERSIST, false)

    private val vaultLockAfterRebootPref = store.getBoolean(KEY_VAULT_REBOOT, false)

    private val vaultTimeoutPref = store.getLong(KEY_VAULT_TIMEOUT, NONE_DURATION)

    private val vaultScreenLockPref = store.getBoolean(KEY_VAULT_SCREEN_LOCK, true)

    private val biometricTimeoutPref = store.getLong(KEY_BIOMETRIC_TIMEOUT, NONE_DURATION)

    private val biometricRequireConfirmationPref = store.getBoolean(KEY_BIOMETRIC_REQUIRE_CONFIRMATION, true)

    private val clipboardClearDelayPref =
        store.getLong(KEY_CLIPBOARD_CLEAR_DELAY, NONE_DURATION)

    private val clipboardUpdateDurationPref =
        store.getLong(KEY_CLIPBOARD_UPDATE_DURATION, NONE_DURATION)

    private val concealFieldsPref =
        store.getBoolean(KEY_CONCEAL_FIELDS, true)

    private val allowScreenshotsPref =
        store.getBoolean(KEY_ALLOW_SCREENSHOTS, false)

    private val checkPwnedPasswordsPref =
        store.getBoolean(KEY_CHECK_PWNED_PASSWORDS, false)

    private val checkPwnedServicesPref =
        store.getBoolean(KEY_CHECK_PWNED_SERVICES, false)

    private val checkTwoFAPref =
        store.getBoolean(KEY_CHECK_TWO_FA, false)

    private val writeAccessPref =
        store.getBoolean(KEY_WRITE_ACCESS, true)

    private val debugPremiumPref =
        store.getBoolean(KEY_DEBUG_PREMIUM, false)

    private val debugScreenDelayPref =
        store.getBoolean(KEY_DEBUG_SCREEN_DELAY, false)

    private val cachePremiumPref =
        store.getBoolean(KEY_CACHE_PREMIUM, false)

    private val appIconsPref =
        store.getBoolean(KEY_APP_ICONS, true)

    private val websiteIconsPref =
        store.getBoolean(KEY_WEBSITE_ICONS, true)

    private val markdownPref =
        store.getBoolean(KEY_MARKDOWN, true)

    private val themeUseAmoledDarkPref =
        store.getBoolean(KEY_THEME_USE_AMOLED_DARK, false)

    private val keepScreenOnPref =
        store.getBoolean(KEY_KEEP_SCREEN_ON, true)

    private val navLabelPref =
        store.getBoolean(KEY_NAV_LABEL, true)

    private val allowTwoPanelLayoutInLandscapePref =
        store.getBoolean(KEY_TWO_PANEL_LAYOUT_LANDSCAPE, true)

    private val allowTwoPanelLayoutInPortraitPref =
        store.getBoolean(KEY_TWO_PANEL_LAYOUT_PORTRAIT, true)

    private val useExternalBrowserPref =
        store.getBoolean(KEY_USE_EXTERNAL_BROWSER, false)

    private val closeToTrayPref =
        store.getBoolean(KEY_CLOSE_TO_TRAY, false)

    private val navAnimationPref =
        store.getEnumNullable(KEY_NAV_ANIMATION, lens = NavAnimation::key)

    private val fontPref =
        store.getEnumNullable(KEY_FONT, lens = AppFont::key)

    private val themePref =
        store.getEnumNullable(KEY_THEME, lens = AppTheme::key)

    private val colorsPref =
        store.getEnumNullable(KEY_COLORS, lens = AppColors::key)

    private val localePref =
        store.getString(KEY_LOCALE, "")

    private val onboardingLastVisitInstantPref =
        store.getObject<Instant?>(
            KEY_ONBOARDING_LAST_VISIT,
            defaultValue = null,
            serialize = { instant ->
                val millis = instant?.toEpochMilliseconds()
                millis?.toString().orEmpty()
            },
            deserialize = { millis ->
                millis
                    .toLongOrNull()
                    ?.let(Instant::fromEpochMilliseconds)
            },
        )

    private val versionLogPref =
        store.getSerializable<VersionLogEntity?>(
            json,
            KEY_VERSION_LOG,
            defaultValue = null,
        )

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.SETTINGS),
        json = directDI.instance(),
    )

    override fun setAutofillInlineSuggestions(inlineSuggestions: Boolean) =
        autofillInlineSuggestionsPref
            .setAndCommit(inlineSuggestions)

    override fun getAutofillInlineSuggestions() = autofillInlineSuggestionsPref

    override fun setAutofillManualSelection(manualSelection: Boolean) = autofillManualSelectionPref
        .setAndCommit(manualSelection)

    override fun getAutofillManualSelection() = autofillManualSelectionPref

    override fun setAutofillRespectAutofillOff(respectAutofillOff: Boolean) =
        autofillRespectAutofillOffPref
            .setAndCommit(respectAutofillOff)

    override fun getAutofillRespectAutofillOff() = autofillRespectAutofillOffPref

    override fun setAutofillSaveRequest(saveRequest: Boolean) = autofillSaveRequestPref
        .setAndCommit(saveRequest)

    override fun getAutofillSaveRequest() = autofillSaveRequestPref

    override fun setAutofillSaveUri(saveUri: Boolean) = autofillSaveUriPref
        .setAndCommit(saveUri)

    override fun getAutofillSaveUri() = autofillSaveUriPref

    override fun setOnboardingLastVisitInstant(instant: Instant): IO<Unit> =
        onboardingLastVisitInstantPref
            .setAndCommit(instant)

    override fun getOnboardingLastVisitInstant(): Flow<Instant?> = onboardingLastVisitInstantPref

    override fun setAutofillCopyTotp(copyTotp: Boolean) = autofillCopyTotpPref
        .setAndCommit(copyTotp)

    override fun getAutofillCopyTotp() = autofillCopyTotpPref

    override fun setVaultPersist(enabled: Boolean): IO<Unit> = vaultPersistPref
        .setAndCommit(enabled)

    override fun getVaultPersist(): Flow<Boolean> = vaultPersistPref

    override fun setVaultLockAfterReboot(enabled: Boolean): IO<Unit> = vaultLockAfterRebootPref
        .setAndCommit(enabled)

    override fun getVaultLockAfterReboot(): Flow<Boolean> = vaultLockAfterRebootPref

    override fun setVaultTimeout(duration: Duration?) = vaultTimeoutPref
        .setAndCommit(duration)

    override fun getVaultTimeout(): Flow<Duration?> = vaultTimeoutPref
        .asDuration()

    override fun setVaultScreenLock(screenLock: Boolean) = vaultScreenLockPref
        .setAndCommit(screenLock)

    override fun getVaultScreenLock() = vaultScreenLockPref

    override fun setBiometricTimeout(duration: Duration?) = biometricTimeoutPref
        .setAndCommit(duration)

    override fun getBiometricTimeout() = biometricTimeoutPref
        .asDuration()

    override fun setBiometricRequireConfirmation(
        requireConfirmation: Boolean,
    ) = biometricRequireConfirmationPref
        .setAndCommit(requireConfirmation)

    override fun getBiometricRequireConfirmation() = biometricRequireConfirmationPref

    override fun setClipboardClearDelay(duration: Duration?) = clipboardClearDelayPref
        .setAndCommit(duration)

    override fun getClipboardClearDelay() = clipboardClearDelayPref
        .asDuration()

    override fun setClipboardUpdateDuration(duration: Duration?) = clipboardUpdateDurationPref
        .setAndCommit(duration)

    override fun getClipboardUpdateDuration() = clipboardUpdateDurationPref
        .asDuration()

    override fun setConcealFields(concealFields: Boolean) = concealFieldsPref
        .setAndCommit(concealFields)

    override fun getConcealFields() = concealFieldsPref

    override fun setAllowScreenshots(allowScreenshots: Boolean) = allowScreenshotsPref
        .setAndCommit(allowScreenshots)

    override fun getAllowScreenshots() = allowScreenshotsPref

    override fun setCheckPwnedPasswords(checkPwnedPasswords: Boolean) = checkPwnedPasswordsPref
        .setAndCommit(checkPwnedPasswords)

    override fun getCheckPwnedPasswords() = checkPwnedPasswordsPref

    override fun setCheckPwnedServices(checkPwnedServices: Boolean) = checkPwnedServicesPref
        .setAndCommit(checkPwnedServices)

    override fun getCheckPwnedServices() = checkPwnedServicesPref

    override fun setCheckTwoFA(checkTwoFA: Boolean) = checkTwoFAPref
        .setAndCommit(checkTwoFA)

    override fun getCheckTwoFA() = checkTwoFAPref

    override fun setDebugPremium(premium: Boolean) = debugPremiumPref
        .setAndCommit(premium)

    override fun getDebugPremium() = debugPremiumPref

    override fun setWriteAccess(writeAccess: Boolean) = writeAccessPref
        .setAndCommit(writeAccess)

    override fun getWriteAccess() = writeAccessPref

    override fun setDebugScreenDelay(screenDelay: Boolean) = debugScreenDelayPref
        .setAndCommit(screenDelay)

    override fun getDebugScreenDelay() = debugScreenDelayPref

    override fun setCachePremium(premium: Boolean) = cachePremiumPref
        .setAndCommit(premium)

    override fun getCachePremium() = cachePremiumPref

    override fun setAppIcons(appIcons: Boolean) = appIconsPref
        .setAndCommit(appIcons)

    override fun getAppIcons() = appIconsPref

    override fun setWebsiteIcons(websiteIcons: Boolean) = websiteIconsPref
        .setAndCommit(websiteIcons)

    override fun getWebsiteIcons() = websiteIconsPref

    override fun setMarkdown(markdown: Boolean) = markdownPref
        .setAndCommit(markdown)

    override fun getMarkdown() = markdownPref

    override fun setAppVersionLog(log: List<AppVersionLog>) =
        ioEffect {
            val entity = log
                .takeIf { it.isNotEmpty() }
                // convert to an entity
                ?.let {
                    VersionLogEntity.of(it)
                }
            entity
        }.flatMap { entity ->
            versionLogPref
                .setAndCommit(entity)
        }

    override fun getAppVersionLog() = versionLogPref
        .map { entity ->
            entity?.toDomain()
                .orEmpty()
        }

    override fun setNavAnimation(navAnimation: NavAnimation?) = navAnimationPref
        .setAndCommit(navAnimation)

    override fun getNavAnimation() = navAnimationPref

    override fun setNavLabel(visible: Boolean) = navLabelPref
        .setAndCommit(visible)

    override fun getNavLabel() = navLabelPref

    override fun setFont(font: AppFont?) = fontPref
        .setAndCommit(font)

    override fun getFont() = fontPref

    override fun setTheme(theme: AppTheme?) = themePref
        .setAndCommit(theme)

    override fun getTheme() = themePref

    override fun setThemeUseAmoledDark(useAmoledDark: Boolean) = themeUseAmoledDarkPref
        .setAndCommit(useAmoledDark)

    override fun getThemeUseAmoledDark() = themeUseAmoledDarkPref

    override fun setKeepScreenOn(keepScreenOn: Boolean) = keepScreenOnPref
        .setAndCommit(keepScreenOn)

    override fun getKeepScreenOn() = keepScreenOnPref

    override fun setAllowTwoPanelLayoutInLandscape(allow: Boolean) =
        allowTwoPanelLayoutInLandscapePref
            .setAndCommit(allow)

    override fun getAllowTwoPanelLayoutInLandscape() = allowTwoPanelLayoutInLandscapePref

    override fun setAllowTwoPanelLayoutInPortrait(allow: Boolean) =
        allowTwoPanelLayoutInPortraitPref
            .setAndCommit(allow)

    override fun getAllowTwoPanelLayoutInPortrait() = allowTwoPanelLayoutInPortraitPref

    override fun getUseExternalBrowser() = useExternalBrowserPref

    override fun setUseExternalBrowser(useExternalBrowser: Boolean) = useExternalBrowserPref
        .setAndCommit(useExternalBrowser)

    override fun setCloseToTray(
        closeToTray: Boolean,
    ) = closeToTrayPref
        .setAndCommit(closeToTray)

    override fun getCloseToTray() = closeToTrayPref

    override fun setColors(colors: AppColors?) = colorsPref
        .setAndCommit(colors)

    override fun getColors() = colorsPref

    override fun setLocale(locale: String?) = localePref
        .setAndCommit(locale.orEmpty())

    override fun getLocale() = localePref
        .map { locale ->
            locale.takeUnless { it.isEmpty() }
        }
}
