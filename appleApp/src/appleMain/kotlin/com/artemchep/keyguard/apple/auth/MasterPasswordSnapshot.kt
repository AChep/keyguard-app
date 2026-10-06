package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.keyguard.setup.SetupState
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState

/** Form fields are owned by a presentation; authentication activity is shared. */
data class MasterPasswordSnapshot(
    val loaded: Boolean,
    val password: String,
    val passwordError: String?,
    val isLoading: Boolean,
    val canSubmit: Boolean,
    val hasBiometric: Boolean,
    val biometricEnabled: Boolean,
    val crashlyticsEnabled: Boolean,
) {
    companion object {
        val empty = MasterPasswordSnapshot(false, "", null, false, false, false, false, false)
    }
}

internal fun UnlockState?.toMasterPasswordSnapshot(busy: Boolean): MasterPasswordSnapshot =
    if (this == null) MasterPasswordSnapshot.empty else MasterPasswordSnapshot(
        loaded = true,
        password = password.text,
        passwordError = password.error,
        isLoading = isLoading || busy,
        canSubmit = unlockVaultByMasterPassword != null && !busy,
        hasBiometric = false,
        biometricEnabled = false,
        crashlyticsEnabled = false,
    )

internal fun SetupState?.toMasterPasswordSnapshot(busy: Boolean): MasterPasswordSnapshot =
    if (this == null) MasterPasswordSnapshot.empty else MasterPasswordSnapshot(
        loaded = true,
        password = password.text,
        passwordError = password.error,
        isLoading = isLoading || busy,
        canSubmit = onCreateVault != null && !busy,
        hasBiometric = biometric != null,
        biometricEnabled = biometric?.checked == true,
        crashlyticsEnabled = crashlytics.checked,
    )
