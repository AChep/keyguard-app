package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheIdentity
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.toApprovalCacheIdentity
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.cliAgentError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Answers the private `cli` requests of the agent with the handlers of
 * their commands. The CLI must be bundled and enabled, and the build and
 * the vault must meet the requirements of the command.
 */
class CliCommandDispatcher(
    private val service: CliServiceImpl,
    handlers: List<CliCommandHandler<*, *>>,
) {
    private val handlers = handlers.associateBy { it.command.id }

    init {
        require(this.handlers.size == handlers.size) { "Each command must have one handler" }
    }

    // The approval broker bounds the time spent waiting on the user, and
    // the agent's own transport timeout bounds the request as a whole.
    @Suppress("ThrowsCount") // Each rejection has a stable, non-sensitive error code.
    suspend fun handle(request: CliAgentMessages.CliRequest): CliAgentMessages.Response.Kind = try {
        val state = service.state.value
        if (state.path == null) throw CliServiceException("unsupported_platform")
        if (!state.enabled) throw CliServiceException("integration_disabled")
        val handler = handlers[request.command] ?: throw CliServiceException("unsupported_command")
        state.checkRequirements(handler.command.requirements, unlocked = service.isUnlocked())
        val caller = request.caller ?: throw CliServiceException("invalid_request")
        val context = CliCallContext(
            caller = caller.toCliCaller(),
            terminalSession = caller.terminalSession(),
        )
        val result = handler.answer(context, request.request)
        // The CLI may have been turned off while the user was asked.
        if (!service.state.value.enabled) {
            result.fill(0)
            throw CliServiceException("integration_disabled")
        }
        CliAgentMessages.Response.Kind.Cli(CliAgentMessages.CliResponse(result))
    } catch (e: CancellationException) {
        throw e
    } catch (e: CliServiceException) {
        cliAgentError(e.code)
    } catch (_: Exception) {
        // Exception messages can contain values or paths.
        cliAgentError("operation_failed")
    }
}

/** The caller attached by the agent, for display. The prompt bounds every value. */
private fun CallerIdentity.toCliCaller() = CliCaller(
    pid = pid,
    processName = processName,
    executablePath = executablePath,
    appName = appName,
    appBundlePath = appBundlePath,
)

/**
 * The verified terminal session of the caller, if the agent established
 * one. The same validation as for the SSH and GPG agents selects the
 * subject; malformed evidence never authorizes reuse.
 */
private fun CallerIdentity.terminalSession(): AgentApprovalCacheIdentity? = authorization
    .toApprovalCacheIdentity(AgentApprovalCachePolicy.ApplicationAndTerminalSession)
    ?.takeIf { it.cacheScope == AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession }

/**
 * Checks that this build and the vault meet the [requirements] of a
 * command, before its handler runs.
 */
internal fun CliServiceState.checkRequirements(
    requirements: CliCommandRequirements,
    unlocked: Boolean,
) {
    if (!supports(requirements)) throw CliServiceException("unsupported_platform")
    if (requirements.session == CliCommandRequirements.Session.Unlocked && !unlocked) {
        throw CliServiceException("vault_locked")
    }
}

/** Checks that a command that requires an approval got one, before it answers. */
internal fun CliCallContext.checkAuthorized(requirements: CliCommandRequirements) {
    check(requirements.authorization == CliCommandRequirements.Authorization.None || approved) {
        "The command answered without an approval"
    }
}

/**
 * Mirrors `commonCli`: an unknown field is rejected, and an absent optional
 * field is omitted.
 */
internal val CliJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private const val OK = "ok"

/**
 * Decodes the [request], and answers with the public success body of the
 * command. Only a request that does not decode is invalid: any other
 * failure is the command's.
 */
private suspend fun <Request : Any, Result : Any> CliCommandHandler<Request, Result>.answer(
    context: CliCallContext,
    request: ByteArray,
): ByteArray {
    val decoded = try {
        CliJson.decodeFromString(requestSerializer, request.decodeToString())
    } catch (_: IllegalArgumentException) {
        // Includes SerializationException, and a request that fails its own checks.
        throw CliServiceException("invalid_request")
    }
    val result = handle(context, decoded)
    context.checkAuthorized(command.requirements)
    return cliSuccessBody(resultSerializer, result)
}

/** The public success body of a command: `ok`, then the fields of its [result]. */
internal fun <Result : Any> cliSuccessBody(
    serializer: KSerializer<Result>,
    result: Result,
): ByteArray {
    val fields = CliJson.encodeToJsonElement(serializer, result) as? JsonObject
    checkNotNull(fields) { "The result of a command must be an object" }
    check(OK !in fields) { "The dispatcher adds $OK" }
    val body = JsonObject(mapOf(OK to JsonPrimitive(true)) + fields)
    return CliJson.encodeToString(JsonObject.serializer(), body).encodeToByteArray()
}
