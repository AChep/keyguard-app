package com.artemchep.keyguard.wear.feature.vault.folders

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.navigation.Route

data class WearFoldersRoute(
    val args: FoldersRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        WearFoldersScreen(
            args = args,
        )
    }
}
