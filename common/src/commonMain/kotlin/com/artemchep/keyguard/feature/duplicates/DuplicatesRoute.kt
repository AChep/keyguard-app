package com.artemchep.keyguard.feature.duplicates

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class DuplicatesRoute(
    val args: Args,
) : Route {
    companion object {
        const val ROUTER_NAME = "duplicates"
    }

    data class Args(
        val filter: DFilter? = null,
    )

    override val descriptor get() = RouteDescriptor.Duplicates(args.filter)

    @Composable
    override fun Content() {
        DuplicatesScreen(args)
    }
}
