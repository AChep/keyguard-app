package com.artemchep.keyguard.feature.home.vault.folders

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route

data class FoldersRoute(
    val args: Args,
) : Route {
    data class Args(
        val filter: DFilter? = null,
        val empty: Boolean = false,
    )

    @Composable
    override fun Content() {
        FoldersScreen(
            args = args,
        )
    }
}
