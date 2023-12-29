package com.artemchep.keyguard.feature.send

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSendFilter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.send.search.SendSort

object SendRoute : Route {
    data class Args(
        val appBar: AppBar? = null,
        val filter: DSendFilter? = null,
        val sort: SendSort? = null,
        val main: Boolean = false,
        val searchBy: SearchBy = SearchBy.ALL,
        /**
         * `true` to show only trashed items, `false` to show
         * only active items, `null` to show both.
         */
        val trash: Boolean? = false,
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
            val subtitle: String? = null,
        )
    }

    @Composable
    override fun Content() {
        SendScreen(Args())
    }
}
