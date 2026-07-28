package com.artemchep.keyguard.feature.filter.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class CipherFilterViewFullRoute(
    val args: CipherFilterViewDialogRoute.Args,
) : Route {
    override val descriptor get() = RouteDescriptor.CipherFilterView(args.model.id, args.model.name)

    @Composable
    override fun Content() {
        CipherFilterViewFullScreen(
            args = args,
        )
    }
}
