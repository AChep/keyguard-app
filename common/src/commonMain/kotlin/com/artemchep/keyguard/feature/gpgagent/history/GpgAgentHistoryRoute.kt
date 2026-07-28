package com.artemchep.keyguard.feature.gpgagent.history

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class GpgAgentHistoryRoute(
    val cipherId: String? = null,
) : Route {
    @Composable
    override fun Content() {
        GpgAgentHistoryScreen(
            cipherId = cipherId,
        )
    }
}
