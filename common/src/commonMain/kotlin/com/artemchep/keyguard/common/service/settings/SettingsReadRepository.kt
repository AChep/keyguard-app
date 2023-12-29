package com.artemchep.keyguard.common.service.settings

import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.model.NavAnimation
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * @author Artem Chepurnyi
 */
interface SettingsReadRepository {
    fun getAutofillInlineSuggestions(): Flow<Boolean>

    fun getAutofillManualSelection(): Flow<Boolean>

    fun getAutofillRespectAutofillOff(): Flow<Boolean>

    fun getAutofillSaveRequest(): Flow<Boolean>

    fun getAutofillSaveUri(): Flow<Boolean>

    fun getAutofillCopyTotp(): Flow<Boolean>

    fun getVaultPersist(): Flow<Boolean>

    fun getVaultLockAfterReboot(): Flow<Boolean>

    fun getVaultTimeout(): Flow<Duration?>

    fun getVaultScreenLock(): Flow<Boolean>

    fun getBiometricTimeout(): Flow<Duration?>

    fun getClipboardClearDelay(): Flow<Duration?>

    fun getClipboardUpdateDuration(): Flow<Duration?>

    fun getConcealFields(): Flow<Boolean>

    fun getAllowScreenshots(): Flow<Boolean>

    fun getOnboardingLastVisitInstant(): Flow<Instant?>

    fun getCheckPwnedPasswords(): Flow<Boolean>

    fun getCheckPwnedServices(): Flow<Boolean>

    fun getCheckTwoFA(): Flow<Boolean>

    fun getWriteAccess(): Flow<Boolean>

    fun getDebugPremium(): Flow<Boolean>

    fun getDebugScreenDelay(): Flow<Boolean>

    fun getCachePremium(): Flow<Boolean>

    fun getAppIcons(): Flow<Boolean>

    fun getWebsiteIcons(): Flow<Boolean>

    fun getMarkdown(): Flow<Boolean>

    fun getNavAnimation(): Flow<NavAnimation?>

    fun getNavLabel(): Flow<Boolean>

    fun getFont(): Flow<AppFont?>

    fun getTheme(): Flow<AppTheme?>

    fun getThemeUseAmoledDark(): Flow<Boolean>

    fun getKeepScreenOn(): Flow<Boolean>

    fun getAllowTwoPanelLayoutInPortrait(): Flow<Boolean>

    fun getAllowTwoPanelLayoutInLandscape(): Flow<Boolean>

    fun getUseExternalBrowser(): Flow<Boolean>

    fun getColors(): Flow<AppColors?>

    fun getLocale(): Flow<String?>
}
