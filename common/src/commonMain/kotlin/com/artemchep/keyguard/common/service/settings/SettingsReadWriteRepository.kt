package com.artemchep.keyguard.common.service.settings

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.model.AppVersionLog
import com.artemchep.keyguard.common.model.NavAnimation
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * @author Artem Chepurnyi
 */
interface SettingsReadWriteRepository : SettingsReadRepository {
    fun setAutofillInlineSuggestions(
        inlineSuggestions: Boolean,
    ): IO<Unit>

    fun setAutofillManualSelection(
        manualSelection: Boolean,
    ): IO<Unit>

    fun setAutofillRespectAutofillOff(
        respectAutofillOff: Boolean,
    ): IO<Unit>

    fun setAutofillSaveRequest(
        saveRequest: Boolean,
    ): IO<Unit>

    fun setAutofillSaveUri(
        saveUri: Boolean,
    ): IO<Unit>

    fun setAutofillCopyTotp(
        copyTotp: Boolean,
    ): IO<Unit>

    fun setVaultPersist(
        enabled: Boolean,
    ): IO<Unit>

    fun setVaultLockAfterReboot(
        enabled: Boolean,
    ): IO<Unit>

    fun setVaultTimeout(
        duration: Duration?,
    ): IO<Unit>

    fun setVaultScreenLock(
        screenLock: Boolean,
    ): IO<Unit>

    fun setBiometricTimeout(
        duration: Duration?,
    ): IO<Unit>

    fun setClipboardClearDelay(
        duration: Duration?,
    ): IO<Unit>

    fun setClipboardUpdateDuration(
        duration: Duration?,
    ): IO<Unit>

    fun setConcealFields(
        concealFields: Boolean,
    ): IO<Unit>

    fun setAllowScreenshots(
        allowScreenshots: Boolean,
    ): IO<Unit>

    fun setCheckPwnedPasswords(
        checkPwnedPasswords: Boolean,
    ): IO<Unit>

    fun setCheckPwnedServices(
        checkPwnedServices: Boolean,
    ): IO<Unit>

    fun setCheckTwoFA(
        checkTwoFA: Boolean,
    ): IO<Unit>

    fun setWriteAccess(
        writeAccess: Boolean,
    ): IO<Unit>

    fun setDebugPremium(
        premium: Boolean,
    ): IO<Unit>

    fun setDebugScreenDelay(
        screenDelay: Boolean,
    ): IO<Unit>

    fun setCachePremium(
        premium: Boolean,
    ): IO<Unit>

    fun setAppIcons(
        appIcons: Boolean,
    ): IO<Unit>

    fun setWebsiteIcons(
        websiteIcons: Boolean,
    ): IO<Unit>

    fun setMarkdown(
        markdown: Boolean,
    ): IO<Unit>

    fun setAppVersionLog(
        log: List<AppVersionLog>,
    ): IO<Unit>

    fun setOnboardingLastVisitInstant(
        instant: Instant,
    ): IO<Unit>

    fun setNavAnimation(
        navAnimation: NavAnimation?,
    ): IO<Unit>

    fun setNavLabel(
        visible: Boolean,
    ): IO<Unit>

    fun setFont(
        font: AppFont?,
    ): IO<Unit>

    fun setTheme(
        theme: AppTheme?,
    ): IO<Unit>

    fun setThemeUseAmoledDark(
        useAmoledDark: Boolean,
    ): IO<Unit>

    fun setKeepScreenOn(
        keepScreenOn: Boolean,
    ): IO<Unit>

    fun setAllowTwoPanelLayoutInPortrait(
        allow: Boolean,
    ): IO<Unit>

    fun setAllowTwoPanelLayoutInLandscape(
        allow: Boolean,
    ): IO<Unit>

    fun setUseExternalBrowser(
        useExternalBrowser: Boolean,
    ): IO<Unit>

    fun setCloseToTray(
        closeToTray: Boolean,
    ): IO<Unit>

    fun setColors(
        colors: AppColors?,
    ): IO<Unit>

    fun setLocale(
        locale: String?,
    ): IO<Unit>
}
