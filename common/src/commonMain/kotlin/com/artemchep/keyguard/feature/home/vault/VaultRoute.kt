package com.artemchep.keyguard.feature.home.vault

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class VaultRoute(
    val args: Args = Args(),
) : Route {
    data class Args(
        val appBar: AppBar? = null,
        val filter: DFilter? = null,
        val sort: Sort? = null,
        val main: Boolean = false,
        val searchBy: SearchBy = SearchBy.ALL,
        /**
         * `true` to show only trashed items, `false` to show
         * only active items, `null` to show both.
         */
        val trash: Boolean? = false,
        /**
         * `true` to show only archived items, `false` to show
         * only active items, `null` to show both.
         */
        val archive: Boolean? = false,
        val preselect: Boolean = true,
        val canAddSecrets: Boolean = true,
    ) {
        enum class SearchBy {
            ALL,
            PASSWORD,
        }

        val canAlwaysShowKeyboard: Boolean get() = appBar?.title == null

        val canQuickFilter: Boolean get() = appBar?.title == null

        data class AppBar(
            val title: String? = null,
            val subtitle: TextHolder? = null,
        )
    }

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
            stacked = false,
        )

    @Composable
    override fun Content() {
        VaultScreen(
            args = args,
        )
    }
}
