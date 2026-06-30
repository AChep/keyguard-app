package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class VaultListRoute(
    val args: VaultRoute.Args,
) : Route {
    override val descriptor
        get() = RouteDescriptor.VaultList(
            title = args.appBar?.title,
            filter = args.filter,
            sortId = args.sort?.id,
            main = args.main,
            searchBy = args.searchBy.name,
            trash = args.trash,
            archive = args.archive,
            preselect = args.preselect,
            canAddSecrets = args.canAddSecrets,
            stacked = true,
        )

    @Composable
    override fun Content() {
        VaultListScreen(
            args = args,
        )
    }
}
