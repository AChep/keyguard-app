package com.artemchep.keyguard.feature.send.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class SendViewRoute(
    val sendId: String,
    val accountId: String,
) : Route {
    override val descriptor get() = RouteDescriptor.SendView(sendId, accountId)

    @Composable
    override fun Content() {
        SendViewScreen(
            sendId = sendId,
            accountId = accountId,
        )
    }
}
