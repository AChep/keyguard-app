package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class JustDeleteMeServiceViewFullRoute(
    val args: JustDeleteMeServiceViewDialogRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        JustDeleteMeFullScreen(
            args = args,
        )
    }
}
