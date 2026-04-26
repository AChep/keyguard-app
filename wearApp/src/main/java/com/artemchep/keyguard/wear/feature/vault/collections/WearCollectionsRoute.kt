package com.artemchep.keyguard.wear.feature.vault.collections

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.navigation.Route

data class WearCollectionsRoute(
    val args: CollectionsRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        WearCollectionsScreen(
            args = args,
        )
    }
}
