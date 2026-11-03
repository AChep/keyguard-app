package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.cli.CliCommand
import com.artemchep.keyguard.feature.cli.MAX_CLI_COMMAND_DISPLAY_LENGTH

/**
 * One request of the CLI agent, as the request log records it. The
 * layers that serve the request fill in what they resolve. The command
 * and the path are bounded and escaped for display before they are
 * stored, and are never values of an environment.
 */
class CliAgentUsageRecord(
    val client: CliAgentUsageHistoryClient,
    val request: CliAgentUsageHistoryRequestType,
    val caller: CallerIdentity?,
    /** The name that an MCP client reports for itself; a hint only. */
    clientName: String? = null,
    /**
     * Requests that share a non-null key replace each other while the
     * vault is locked, so routine requests can not evict the rest.
     */
    val coalescenceKey: String? = null,
) {
    val clientName: String? = clientName.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)

    /** The environment that the request reached. */
    var cipherId: String? = null

    /** The command line that `keyguard run` starts. */
    var command: String? = null
        set(value) {
            field = value.sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)
        }

    /** The working directory of `keyguard run`, or the path of a local `.env` file. */
    var path: String? = null
        set(value) {
            field = value.sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)
        }
}

/**
 * The record of a request that the request log shows, or `null` for the
 * agent's own housekeeping.
 */
internal fun CliAgentMessages.Request.Kind.toUsageRecord(): CliAgentUsageRecord? = when (this) {
    is CliAgentMessages.Request.Kind.Cli -> CliAgentUsageRecord(
        client = CliAgentUsageHistoryClient.CLI,
        request = when (value.command) {
            CliCommand.Run.id -> CliAgentUsageHistoryRequestType.RUN
            else -> CliAgentUsageHistoryRequestType.UNKNOWN
        },
        caller = value.caller,
    )

    is CliAgentMessages.Request.Kind.Manage -> {
        val request = mcpToolRequestType(value.tool)
        CliAgentUsageRecord(
            client = CliAgentUsageHistoryClient.MCP,
            request = request,
            caller = value.caller,
            clientName = value.clientName,
            coalescenceKey = if (request in MCP_READ_ONLY_REQUESTS) {
                "CLI_AGENT|MCP|$request|${value.caller?.processName.orEmpty()}"
            } else {
                null
            },
        )
    }

    // A FIFO can not identify the program that reads it.
    is CliAgentMessages.Request.Kind.ResolveMount -> CliAgentUsageRecord(
        client = CliAgentUsageHistoryClient.ENV,
        request = CliAgentUsageHistoryRequestType.READ_LOCAL_ENV_FILE,
        caller = null,
        coalescenceKey = "CLI_AGENT|ENV|${value.mountId}",
    )

    is CliAgentMessages.Request.Kind.Authenticate,
    is CliAgentMessages.Request.Kind.Mounts,
    is CliAgentMessages.Request.Kind.WatchMounts,
    is CliAgentMessages.Request.Kind.MountStatus,
    -> null
}

/** The tools of `commonCli/src/manage.rs`; the client chooses the name, so it is never stored. */
private fun mcpToolRequestType(tool: String): CliAgentUsageHistoryRequestType = when (tool) {
    "authenticate" -> CliAgentUsageHistoryRequestType.MCP_AUTHENTICATE
    "list_environments" -> CliAgentUsageHistoryRequestType.MCP_LIST_ENVIRONMENTS
    "create_environment" -> CliAgentUsageHistoryRequestType.MCP_CREATE_ENVIRONMENT
    "rename_environment" -> CliAgentUsageHistoryRequestType.MCP_RENAME_ENVIRONMENT
    "list_variables" -> CliAgentUsageHistoryRequestType.MCP_LIST_VARIABLES
    "append_variables" -> CliAgentUsageHistoryRequestType.MCP_APPEND_VARIABLES
    "create_local_env_file" -> CliAgentUsageHistoryRequestType.MCP_CREATE_LOCAL_ENV_FILE
    "list_local_env_files" -> CliAgentUsageHistoryRequestType.MCP_LIST_LOCAL_ENV_FILES
    else -> CliAgentUsageHistoryRequestType.UNKNOWN
}

private val MCP_READ_ONLY_REQUESTS = setOf(
    CliAgentUsageHistoryRequestType.MCP_AUTHENTICATE,
    CliAgentUsageHistoryRequestType.MCP_LIST_ENVIRONMENTS,
    CliAgentUsageHistoryRequestType.MCP_LIST_VARIABLES,
    CliAgentUsageHistoryRequestType.MCP_LIST_LOCAL_ENV_FILES,
)
