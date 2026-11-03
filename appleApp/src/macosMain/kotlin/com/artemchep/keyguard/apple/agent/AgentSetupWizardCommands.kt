package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.feature.agent.help.SetupAgent
import com.artemchep.keyguard.feature.agent.help.SetupShell
import com.artemchep.keyguard.feature.agent.help.externalCheckCommand
import com.artemchep.keyguard.feature.agent.help.hostCliSetupCommand
import com.artemchep.keyguard.feature.agent.help.hostCliUndoCommand
import com.artemchep.keyguard.feature.agent.help.isAgentSetupHostValid

// The native wizard can't edit host files from the sandbox, so it shows the
// commands of the bundled CLI, like the Flatpak one. Agents and shells are
// named by their CLI arguments.

fun agentSetupWizardCommand(
    executable: String,
    agent: String,
    endpoint: String,
    shells: Set<String>,
    host: String,
): String = hostCliSetupCommand(
    cli = listOf(executable),
    agent = setupAgent(agent),
    endpoint = endpoint,
    shells = SetupShell.entries.filter { it.argument in shells },
    host = host.trim(),
)

fun agentSetupWizardUndoCommand(executable: String, agent: String): String =
    hostCliUndoCommand(listOf(executable), setupAgent(agent))

fun agentSetupWizardCheckCommand(agent: String, endpoint: String): String =
    externalCheckCommand(setupAgent(agent), endpoint)

fun isAgentSetupWizardHostValid(host: String): Boolean = isAgentSetupHostValid(host)

private fun setupAgent(argument: String) = SetupAgent.entries.first { it.argument == argument }
