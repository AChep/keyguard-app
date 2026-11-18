package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.cli.CliJson
import com.artemchep.keyguard.common.service.environment.EnvironmentRunCliRequest
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The response to [request], which opens no stream. */
internal suspend fun CliAgentRequestHandler.respond(
    request: CliAgentMessages.Request.Kind,
): CliAgentMessages.Response.Kind {
    val answer = handle(request)
    assertNull(answer.upgrade)
    return answer.response
}

/** The error code of a failed answer. */
internal fun CliAgentMessages.Response.Kind.error(): String =
    assertIs<CliAgentMessages.Response.Kind.Error>(this).value.code

/** The result of an answered management request. */
internal fun CliAgentMessages.Response.Kind.manageResult(): CliAgentMessages.ManageResponse.Result =
    assertIs<CliAgentMessages.Response.Kind.Manage>(
        this,
        (this as? CliAgentMessages.Response.Kind.Error)?.value?.code,
    ).value.result

/** A management request of an MCP client, as the agent sends it. */
internal fun manageRequest(
    tool: String,
    arguments: Map<String, String> = emptyMap(),
    variables: List<CliAgentMessages.VariableUpdate> = emptyList(),
    caller: CallerIdentity = CallerIdentity(processName = "test-agent"),
    connectionId: ByteArray = ByteArray(32) { 1 },
    clientName: String = "",
) = CliAgentMessages.Request.Kind.Manage(
    CliAgentMessages.ManageRequest(
        caller = caller,
        connectionId = connectionId,
        clientName = clientName,
        tool = tool,
        arguments = arguments,
        variables = variables,
    ),
)

/** The public success body of an answered command, as JSON. */
internal fun CliAgentMessages.Response.Kind.cliResult(): String =
    assertIs<CliAgentMessages.Response.Kind.Cli>(this).value.result.decodeToString()

/** A command of the CLI with the [request] JSON, as the agent sends it. */
internal fun cliRequest(
    command: String,
    request: String,
    caller: CallerIdentity? = CallerIdentity(pid = 42, processName = "keyguard"),
) = CliAgentMessages.CliRequest(
    caller = caller,
    command = command,
    request = request.encodeToByteArray(),
)

/** A request of the browser connector to open the stream of its extension, as the agent sends it. */
internal fun browserOpenRequest() = CliAgentMessages.Request.Kind.BrowserOpen(
    CliAgentMessages.BrowserOpenRequest(
        caller = CallerIdentity(pid = 42, processName = "chrome"),
        origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
        verifiedHost = true,
        hostRevision = 1,
    ),
)

/** A `keyguard run` request, as the agent sends it. */
internal fun runRequest(
    environment: String = "Production",
    command: List<String> = listOf("env"),
    account: String? = null,
    workingDirectory: String = "/work",
    caller: CallerIdentity? = CallerIdentity(pid = 42, processName = "keyguard"),
    name: String = "run",
) = cliRequest(
    command = name,
    request = CliJson.encodeToString(
        EnvironmentRunCliRequest.serializer(),
        EnvironmentRunCliRequest(
            environment = environment,
            account = account,
            command = command,
            workingDirectory = workingDirectory,
        ),
    ),
    caller = caller,
)
