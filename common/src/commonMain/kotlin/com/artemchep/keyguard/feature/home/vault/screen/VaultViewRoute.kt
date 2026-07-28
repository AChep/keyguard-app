package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class VaultViewRoute(
    val itemId: String,
    val accountId: String,
    val tag: String? = null,
) : Route {
    override val descriptor get() = RouteDescriptor.VaultCipherView(itemId, accountId)

    @Composable
    override fun Content() {
        VaultViewScreen(
            itemId = itemId,
            accountId = accountId,
        )
    }
}
