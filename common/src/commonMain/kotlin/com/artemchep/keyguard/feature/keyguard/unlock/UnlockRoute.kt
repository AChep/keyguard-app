package com.artemchep.keyguard.feature.keyguard.unlock

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.feature.navigation.Route

class UnlockRoute(
    /**
     * Unlocks a vault with the key derived from a
     * given password.
     */
    val unlockVaultByMasterPassword: VaultState.Unlock.WithPassword,
    val unlockVaultByBiometric: VaultState.Unlock.WithBiometric?,
    val lockReason: String?,
) : Route {
    @Composable
    override fun Content() {
        UnlockScreen(
            unlockVaultByMasterPassword = unlockVaultByMasterPassword,
            unlockVaultByBiometric = unlockVaultByBiometric,
            lockReason = lockReason,
        )
    }
}
