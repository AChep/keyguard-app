package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class VaultViewPasswordHistoryRoute(
    val itemId: String,
) : Route {
    @Composable
    override fun Content() {
        VaultViewPasswordHistoryScreen(
            itemId = itemId,
        )
    }
}
