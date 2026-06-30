package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class VaultViewPasswordHistoryRoute(
    val itemId: String,
) : Route {
    override val descriptor get() = RouteDescriptor.PasswordHistory(itemId)

    @Composable
    override fun Content() {
        VaultViewPasswordHistoryScreen(
            itemId = itemId,
        )
    }
}
