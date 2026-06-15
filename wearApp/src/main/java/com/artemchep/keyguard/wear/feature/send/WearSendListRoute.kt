package com.artemchep.keyguard.wear.feature.send

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.wear.feature.send.WearSendListScreen

data class WearSendListRoute(
    val args: SendRoute.Args = SendRoute.Args(),
) : Route {
    @Composable
    override fun Content() {
        WearSendListScreen(
            args = args,
        )
    }
}
