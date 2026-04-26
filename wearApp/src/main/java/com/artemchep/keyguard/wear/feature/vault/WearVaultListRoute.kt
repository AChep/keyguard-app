package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.navigation.Route

data class WearVaultListRoute(
    val args: VaultRoute.Args = VaultRoute.Args(),
) : Route {
    @Composable
    override fun Content() {
        WearVaultListScreen(
            args = args,
        )
    }
}
