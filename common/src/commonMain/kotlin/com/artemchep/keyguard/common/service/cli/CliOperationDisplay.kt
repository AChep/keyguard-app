package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.feature.cli.MAX_CLI_COMMAND_DISPLAY_LENGTH
import com.artemchep.keyguard.feature.cli.cliCommandLine

// The CLI reports the command it starts and its directory; never show them unescaped.

/** The command that `keyguard run` starts, escaped for a prompt. */
fun CliOperation.Run.displayCommand(): String? = cliCommandLine(command)
    .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)

/** The directory that `keyguard run` starts the command in, escaped for a prompt. */
fun CliOperation.Run.displayWorkingDirectory(): String? = workingDirectory
    .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)
