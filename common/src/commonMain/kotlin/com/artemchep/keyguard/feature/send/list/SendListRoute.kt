package com.artemchep.keyguard.feature.send.list

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.send.SendRoute

data class SendListRoute(
    val args: SendRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        SendListScreen(
            args = args,
        )
    }
}
