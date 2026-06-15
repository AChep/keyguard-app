package com.artemchep.keyguard.wear.feature.vault.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import kotlin.String

data class WearVaultViewRoute(
    val itemId: String,
    val accountId: String,
) : Route {
    @Composable
    override fun Content() {
        WearVaultViewScreen(
            itemId = itemId,
            accountId = accountId,
        )
    }
}
