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
    val unlockVaultByYubiKey: VaultState.Unlock.WithYubiKey?,
    val lockInfo: VaultState.Unlock.LockInfo?,
    val unlockVaultByFido2: VaultState.Unlock.WithFido2? = null,
) : Route {
    @Composable
    override fun Content() {
        UnlockScreen(
            unlockVaultByMasterPassword = unlockVaultByMasterPassword,
            unlockVaultByBiometric = unlockVaultByBiometric,
            unlockVaultByYubiKey = unlockVaultByYubiKey,
            lockInfo = lockInfo,
            unlockVaultByFido2 = unlockVaultByFido2,
        )
    }
}
