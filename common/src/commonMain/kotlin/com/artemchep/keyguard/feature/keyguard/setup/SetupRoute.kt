package com.artemchep.keyguard.feature.keyguard.setup

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.feature.navigation.Route

data class SetupRoute(
    /**
     * Creates a vault with the key derived from a
     * given password.
     */
    val createVaultWithMasterPassword: VaultState.Create.WithPassword,
    val createVaultWithMasterPasswordAndBiometric: VaultState.Create.WithBiometric?,
) : Route {
    @Composable
    override fun Content() {
        SetupScreen(
            createVaultWithMasterPassword = createVaultWithMasterPassword,
            createVaultWithMasterPasswordAndBiometric = createVaultWithMasterPasswordAndBiometric,
        )
    }
}
