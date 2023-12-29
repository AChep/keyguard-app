package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.navigation.Route

data class VaultListRoute(
    val args: VaultRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        VaultListScreen(
            args = args,
        )
    }
}
