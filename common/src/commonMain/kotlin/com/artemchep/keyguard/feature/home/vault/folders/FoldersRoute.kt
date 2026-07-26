package com.artemchep.keyguard.feature.home.vault.folders

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class FoldersRoute(
    val args: Args,
) : Route {
    data class Args(
        val filter: DFilter? = null,
        val empty: Boolean = false,
        val parent: Parent? = null,
    ) {
        data class Parent(
            val accountId: String,
            val folderId: String,
        )
    }

    override val descriptor get() = RouteDescriptor.Folders(args.filter, args.empty)

    @Composable
    override fun Content() {
        FoldersScreen(
            args = args,
        )
    }
}
