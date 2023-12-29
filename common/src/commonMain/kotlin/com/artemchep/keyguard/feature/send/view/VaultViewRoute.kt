package com.artemchep.keyguard.feature.send.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class SendViewRoute(
    val sendId: String,
    val accountId: String,
) : Route {
    @Composable
    override fun Content() {
        SendViewScreen(
            sendId = sendId,
            accountId = accountId,
        )
    }
}
