package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

internal data class AgentAutomaticSetupRoute(
    val agent: SetupAgent,
    val mode: AgentSetupMode = AgentSetupMode.CONFIGURE,
    /** Includes the SSH configuration of bookmarked hosts even while it is off. */
    val includeSshBookmarks: Boolean = false,
) : Route {
    @Composable
    override fun Content() {
        AgentAutomaticSetupScreen(agent, mode, includeSshBookmarks)
    }
}
