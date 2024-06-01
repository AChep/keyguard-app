package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class VaultViewRoute(
    val itemId: String,
    val accountId: String,
    val tag: String? = null,
) : Route {
    @Composable
    override fun Content() {
        VaultViewScreen(
            itemId = itemId,
            accountId = accountId,
        )
    }
}
