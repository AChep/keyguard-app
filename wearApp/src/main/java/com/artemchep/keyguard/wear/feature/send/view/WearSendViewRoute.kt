package com.artemchep.keyguard.wear.feature.send.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class WearSendViewRoute(
    val sendId: String,
    val accountId: String,
) : Route {
    @Composable
    override fun Content() {
        WearSendViewScreen(
            sendId = sendId,
            accountId = accountId,
        )
    }
}
