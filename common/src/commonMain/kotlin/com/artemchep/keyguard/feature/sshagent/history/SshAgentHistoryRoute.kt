package com.artemchep.keyguard.feature.sshagent.history

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class SshAgentHistoryRoute(
    val cipherId: String? = null,
) : Route {
    @Composable
    override fun Content() {
        SshAgentHistoryScreen(
            cipherId = cipherId,
        )
    }
}
