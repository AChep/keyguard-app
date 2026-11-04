package com.artemchep.keyguard.feature.agent.history

import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope

/** Opens the details dialog of one request of an agent request log. */
fun RememberStateFlowScope.navigateToAgentHistoryDetails(
    details: AgentHistoryDetails,
) {
    val route = AgentHistoryDetailsRoute(details)
    navigate(NavigationIntent.NavigateToRoute(route))
}
