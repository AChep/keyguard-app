package com.artemchep.keyguard.feature.agent.help

import com.artemchep.keyguard.feature.cli.cliCommandLine

enum class SetupAgent(val argument: String) {
    SSH("ssh"),
    GPG("gpg"),
}

enum class SetupShell(val argument: String, val title: String) {
    BASH("bash", "Bash"),
    ZSH("zsh", "Zsh"),
    FISH("fish", "Fish"),
    POWERSHELL("powershell", "PowerShell 7"),
    WINDOWS_POWERSHELL("windows-powershell", "Windows PowerShell 5.1"),
}

const val SSH_AGENT_SETUP_VERIFY_CMD_LIST = "ssh-add -L"

private const val GPG_AGENT_SETUP_VERIFY_CMD_LIST = "gpg-connect-agent --no-autostart 'KEYINFO --list' /bye"

private val HOST_REGEX = Regex("[A-Za-z0-9._-]+")

/** Whether [host] names one SSH host: no patterns, spaces or line breaks. */
fun isAgentSetupHostValid(host: String): Boolean = host.trim().matches(HOST_REGEX)

/** The options that the CLI's `agents plan`, `setup` and `verify` share. */
fun agentSetupOptions(
    agent: SetupAgent,
    endpoint: String,
    shells: Collection<SetupShell> = emptyList(),
    host: String = "",
): List<String> {
    val option = when (agent) {
        SetupAgent.SSH -> "--socket"
        SetupAgent.GPG -> "--gpg-home"
    }
    return listOf("--agent", agent.argument, option, endpoint) +
        (if (shells.isEmpty()) emptyList() else listOf("--shells", shells.sorted().joinToString(",") { it.argument })) +
        (if (agent == SetupAgent.SSH && host.isNotEmpty()) listOf("--host", host) else emptyList())
}

/**
 * Configures the agent with the bundled CLI that [cli] starts from a host
 * terminal. Sandboxed apps that can't edit host files show it instead.
 */
fun hostCliSetupCommand(
    cli: List<String>,
    agent: SetupAgent,
    endpoint: String,
    shells: Collection<SetupShell>,
    host: String,
): String = hostCliCommandLine(cli, listOf("agents", "setup") + agentSetupOptions(agent, endpoint, shells, host))

/** Reviews the removal of what [hostCliSetupCommand] configured. */
fun hostCliUndoCommand(cli: List<String>, agent: SetupAgent): String =
    hostCliCommandLine(cli, listOf("agents", "undo", agent.argument, "--review"))

/** `env` starts the CLI, so PowerShell runs a quoted path too. */
private fun hostCliCommandLine(cli: List<String>, arguments: List<String>) =
    cliCommandLine(listOf("env") + cli + arguments)

/**
 * Checks the connection from a new terminal. With [endpoint], names it, so
 * the check works without the shell settings too. `env` sets the variable
 * in any shell, PowerShell included.
 */
fun externalCheckCommand(agent: SetupAgent, endpoint: String?): String {
    val (variable, command) = when (agent) {
        SetupAgent.SSH -> "SSH_AUTH_SOCK" to SSH_AGENT_SETUP_VERIFY_CMD_LIST
        SetupAgent.GPG -> "GNUPGHOME" to GPG_AGENT_SETUP_VERIFY_CMD_LIST
    }
    return if (endpoint == null) command else "env $variable=${cliCommandLine(listOf(endpoint))} $command"
}
