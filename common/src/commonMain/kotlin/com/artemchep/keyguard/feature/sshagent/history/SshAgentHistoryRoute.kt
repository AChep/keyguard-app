package com.artemchep.keyguard.feature.sshagent.history

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class SshAgentHistoryRoute(
    val cipherId: String? = null,
) : Route {
    override val descriptor get() = RouteDescriptor.SshAgentHistory(cipherId)

    @Composable
    override fun Content() {
        SshAgentHistoryScreen(
            cipherId = cipherId,
        )
    }
}
