package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal data class AgentAutomaticSetupRoute(
    val agent: SetupAgent,
    val mode: AgentSetupMode = AgentSetupMode.CONFIGURE,
) : Route {
    @Composable
    override fun Content() {
        AgentAutomaticSetupScreen(agent, mode)
    }
}
