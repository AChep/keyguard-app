package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable

/** Android configures Termux shells through the installed helper, outside the app. */
@Composable
internal actual fun rememberAgentSetupRunner(agent: SetupAgent): AgentSetupRunner? = AgentSetupRunner.Termux
