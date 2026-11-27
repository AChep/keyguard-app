package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.keyguard.setup.SetupState
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState

/**
 * Form fields are owned by a presentation; authentication activity is shared.
 *
 * [kdf] is the key derivation of a new vault, `null` while unlocking; [kdfExpanded] tells
 * whether its section is shown.
 */
data class MasterPasswordSnapshot(
    val loaded: Boolean,
    val password: String,
    val passwordError: String?,
    /** A translated error, shown while the key derivation is not valid. */
    val kdfError: String?,
    val isLoading: Boolean,
    val canSubmit: Boolean,
    val hasBiometric: Boolean,
    val biometricEnabled: Boolean,
    val crashlyticsEnabled: Boolean,
    val kdf: MasterKdfSnapshot?,
    val kdfExpanded: Boolean,
) {
    companion object {
        val empty = MasterPasswordSnapshot(
            false, "", null, null, false, false, false, false, false,
            kdf = null,
            kdfExpanded = false,
        )
    }
}

internal fun UnlockState?.toMasterPasswordSnapshot(busy: Boolean): MasterPasswordSnapshot =
    if (this == null) MasterPasswordSnapshot.empty else MasterPasswordSnapshot(
        loaded = true,
        password = password.text,
        passwordError = password.error,
        kdfError = null,
        isLoading = isLoading || busy,
        canSubmit = unlockVaultByMasterPassword != null && !busy,
        hasBiometric = false,
        biometricEnabled = false,
        crashlyticsEnabled = false,
        kdf = null,
        kdfExpanded = false,
    )

internal fun SetupState?.toMasterPasswordSnapshot(busy: Boolean): MasterPasswordSnapshot =
    if (this == null) MasterPasswordSnapshot.empty else MasterPasswordSnapshot(
        loaded = true,
        password = password.text,
        passwordError = password.error,
        kdfError = kdfError,
        isLoading = isLoading || busy,
        canSubmit = onCreateVault != null && !busy,
        hasBiometric = biometric != null,
        biometricEnabled = biometric?.checked == true,
        crashlyticsEnabled = crashlytics.checked,
        kdf = kdf.toMasterKdfSnapshot(),
        kdfExpanded = kdfExpanded,
    )
