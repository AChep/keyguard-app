package com.artemchep.keyguard.common.service.settings

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.model.AppVersionLog
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.backup.KeyValueBackupState
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.time.Duration

/**
 * @author Artem Chepurnyi
 */
interface SettingsReadRepository {
    /**
     * Returns all the key-value keys used in the
     * repository.
     */
    fun getPrefs(
        includeInternalPrefs: Boolean = false,
    ): List<KeyValuePreference<*>>

    fun backup(): IO<KeyValueBackupState>

    //
    // Actual settings
    //

    fun getAutofillDefaultMatchDetection(): Flow<String>

    fun getAutofillInlineSuggestions(): Flow<Boolean>

    fun getAutofillManualSelection(): Flow<Boolean>

    fun getAutofillRespectAutofillOff(): Flow<Boolean>

    fun getAutofillSaveRequest(): Flow<Boolean>

    fun getAutofillSaveUri(): Flow<Boolean>

    fun getAdvertisePasskeysSupport(): Flow<Boolean>

    fun getAutofillCopyTotp(): Flow<Boolean>

    fun getVaultPersist(): Flow<Boolean>

    fun getVaultLockAfterReboot(): Flow<Boolean>

    fun getVaultTimeout(): Flow<Duration?>

    fun getVaultScreenLock(): Flow<Boolean>

    fun getBiometricTimeout(): Flow<Duration?>

    fun getBiometricRequireConfirmation(): Flow<Boolean>

    fun getClipboardClearDelay(): Flow<Duration?>

    fun getClipboardUpdateDuration(): Flow<Duration?>

    fun getConcealFields(): Flow<Boolean>

    fun getAllowScreenshots(): Flow<Boolean>

    fun getOnboardingLastVisitInstant(): Flow<Instant?>

    fun getCheckPwnedPasswords(): Flow<Boolean>

    fun getCheckPwnedServices(): Flow<Boolean>

    fun getCheckTwoFA(): Flow<Boolean>

    fun getCheckPasskeys(): Flow<Boolean>

    fun getWriteAccess(): Flow<Boolean>

    fun getDebugPremium(): Flow<Boolean>

    fun getDebugScreenDelay(): Flow<Boolean>

    fun getCachePremium(): Flow<Boolean>

    fun getAppIcons(): Flow<Boolean>

    fun getWebsiteIcons(): Flow<Boolean>

    fun getMarkdown(): Flow<Boolean>

    fun getAppVersionLog(): Flow<List<AppVersionLog>>

    fun getNavAnimation(): Flow<NavAnimation?>

    fun getNavLabel(): Flow<Boolean>

    fun getFont(): Flow<AppFont?>

    fun getTheme(): Flow<AppTheme?>

    fun getThemeUseAmoledDark(): Flow<Boolean>

    fun getThemeM3Expressive(): Flow<Boolean>

    fun getKeepScreenOn(): Flow<Boolean>

    fun getGravatar(): Flow<Boolean>

    fun getAllowTwoPanelLayoutInPortrait(): Flow<Boolean>

    fun getAllowTwoPanelLayoutInLandscape(): Flow<Boolean>

    fun getUseExternalBrowser(): Flow<Boolean>

    fun getCloseToTray(): Flow<Boolean>

    fun getColors(): Flow<AppColors?>

    fun getLocale(): Flow<String?>

    fun getExposedDatabaseKey(): Flow<ByteArray?>
}
