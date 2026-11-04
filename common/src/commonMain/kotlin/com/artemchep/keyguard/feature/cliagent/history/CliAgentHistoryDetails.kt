package com.artemchep.keyguard.feature.cliagent.history

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetails
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetailsIcon
import com.artemchep.keyguard.feature.agent.history.AgentHistoryOutcome
import com.artemchep.keyguard.feature.agent.history.agentHistoryCallerSection
import com.artemchep.keyguard.feature.agent.history.agentHistoryErrorSection
import com.artemchep.keyguard.feature.agent.history.agentHistoryReferenceRow
import com.artemchep.keyguard.feature.agent.history.agentHistorySectionOrNull
import com.artemchep.keyguard.feature.cli.MAX_CLI_COMMAND_DISPLAY_LENGTH
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.serialization.json.Json

internal suspend fun TranslatorScope.cliAgentHistoryDetails(
    id: String,
    event: DCliAgentUsageHistory,
    cipher: DSecret?,
    requestText: String,
    responseText: String,
    formattedDate: String,
    json: Json,
): AgentHistoryDetails {
    // The item speaks for itself.
    val environmentSection = agentHistorySectionOrNull(
        id = "environment",
        title = null,
        rows = listOf(
            agentHistoryReferenceRow(
                cipherId = event.cipherId,
                cipher = cipher,
                label = translate(Res.string.cipher_type_environment),
            ),
        ),
    )
    return AgentHistoryDetails(
        id = id,
        icon = event.request.detailsIcon(),
        title = requestText,
        outcome = event.response.outcome(),
        outcomeText = responseText,
        formattedDate = formattedDate,
        sections = listOfNotNull(
            agentHistoryErrorSection(event.errorCode),
            environmentSection,
            commandSection(event),
            callerSection(event, json),
        ),
    )
}

/** The command line and its directory, or the path of a local `.env` file. */
private suspend fun TranslatorScope.commandSection(
    event: DCliAgentUsageHistory,
): AgentHistoryDetails.Section? {
    val commandRow = event.command
        .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)
        ?.let { command ->
            AgentHistoryDetails.Row.Code(
                id = "command.line",
                icon = AgentHistoryDetailsIcon.RUN,
                label = translate(Res.string.command),
                value = command,
            )
        }
    val run = event.request == CliAgentUsageHistoryRequestType.RUN
    val pathRow = event.path
        .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)
        ?.let { path ->
            AgentHistoryDetails.Row.Text(
                id = "command.path",
                // It sits under the command, which has no icon either.
                icon = null,
                label = if (run) {
                    translate(Res.string.agent_history_details_working_directory)
                } else {
                    translate(Res.string.agent_approval_path_label)
                },
                value = path,
                monospace = true,
            )
        }
    return agentHistorySectionOrNull(
        id = "command",
        title = translate(Res.string.command),
        rows = listOf(commandRow, pathRow),
    )
}

/** The caller, and the client it reached Keyguard through. */
private suspend fun TranslatorScope.callerSection(
    event: DCliAgentUsageHistory,
    json: Json,
): AgentHistoryDetails.Section {
    val clientRow = AgentHistoryDetails.Row.Text(
        id = "caller.client",
        icon = AgentHistoryDetailsIcon.CLIENT,
        label = translate(Res.string.agent_history_details_client),
        value = title(event.client),
    )
    val clientNameRow = event.clientName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
        ?.let { name ->
            AgentHistoryDetails.Row.Text(
                id = "caller.client_name",
                icon = AgentHistoryDetailsIcon.MCP,
                label = translate(Res.string.agent_history_details_client_name),
                value = name,
            )
        }
    return agentHistoryCallerSection(
        caller = event.caller,
        json = json,
        fallback = when (event.client) {
            // A FIFO can not identify the program that reads it.
            CliAgentUsageHistoryClient.ENV -> translate(Res.string.environments_local_process)
            else -> translate(Res.string.agent_history_unknown_caller)
        },
        extraRows = listOfNotNull(
            clientRow,
            clientNameRow,
        ),
    )
}

private fun CliAgentUsageHistoryRequestType.detailsIcon() = when (this) {
    CliAgentUsageHistoryRequestType.RUN -> AgentHistoryDetailsIcon.RUN
    CliAgentUsageHistoryRequestType.MCP_AUTHENTICATE,
    CliAgentUsageHistoryRequestType.MCP_LIST_ENVIRONMENTS,
    CliAgentUsageHistoryRequestType.MCP_CREATE_ENVIRONMENT,
    CliAgentUsageHistoryRequestType.MCP_RENAME_ENVIRONMENT,
    CliAgentUsageHistoryRequestType.MCP_LIST_VARIABLES,
    CliAgentUsageHistoryRequestType.MCP_APPEND_VARIABLES,
    CliAgentUsageHistoryRequestType.MCP_CREATE_LOCAL_ENV_FILE,
    CliAgentUsageHistoryRequestType.MCP_LIST_LOCAL_ENV_FILES,
    -> AgentHistoryDetailsIcon.MCP

    CliAgentUsageHistoryRequestType.READ_LOCAL_ENV_FILE -> AgentHistoryDetailsIcon.ENV_FILE
    CliAgentUsageHistoryRequestType.UNKNOWN -> AgentHistoryDetailsIcon.UNKNOWN
}

internal fun CliAgentUsageHistoryResponseType.outcome() = when (this) {
    CliAgentUsageHistoryResponseType.SUCCESS -> AgentHistoryOutcome.SUCCESS
    CliAgentUsageHistoryResponseType.USER_DENIED,
    CliAgentUsageHistoryResponseType.NOT_FOUND,
    CliAgentUsageHistoryResponseType.VAULT_LOCKED,
    CliAgentUsageHistoryResponseType.TIMED_OUT,
    CliAgentUsageHistoryResponseType.FAILURE,
    -> AgentHistoryOutcome.FAILURE

    CliAgentUsageHistoryResponseType.CANCELLED,
    CliAgentUsageHistoryResponseType.UNKNOWN,
    -> AgentHistoryOutcome.NEUTRAL
}
