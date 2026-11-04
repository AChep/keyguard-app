package com.artemchep.keyguard.feature.agent.history

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute

/** The details of one request of an agent request log. */
data class AgentHistoryDetailsRoute(
    val details: AgentHistoryDetails,
) : DialogRoute {
    @Composable
    override fun Content() {
        AgentHistoryDetailsScreen(
            details = details,
        )
    }
}
