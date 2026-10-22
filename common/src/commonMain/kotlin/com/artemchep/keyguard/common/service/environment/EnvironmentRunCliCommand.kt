package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.cli.CliCallContext
import com.artemchep.keyguard.common.service.cli.CliCommand
import com.artemchep.keyguard.common.service.cli.CliCommandHandler
import com.artemchep.keyguard.common.service.cli.CliOperation
import com.artemchep.keyguard.common.service.cli.CliServiceException
import com.artemchep.keyguard.common.service.cli.CliServiceImpl
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * `keyguard run`: answers with the values of an environment. The agent
 * accepts this command only from the bundled CLI, which starts the
 * command with the values.
 */
class EnvironmentRunCliCommand(
    private val cli: CliServiceImpl,
    private val vault: EnvironmentVault,
    private val repository: EnvironmentRepositoryImpl,
    private val access: EnvironmentAccessImpl,
) : CliCommandHandler<EnvironmentRunCliRequest, EnvironmentRunCliResult> {
    override val command = CliCommand.Run
    override val requestSerializer: KSerializer<EnvironmentRunCliRequest> = EnvironmentRunCliRequest.serializer()
    override val resultSerializer: KSerializer<EnvironmentRunCliResult> = EnvironmentRunCliResult.serializer()

    override suspend fun handle(
        context: CliCallContext,
        request: EnvironmentRunCliRequest,
    ): EnvironmentRunCliResult {
        return try {
            run(context, request)
        } catch (e: EnvironmentServiceException) {
            // The error codes of both services are stable and shared with the CLI.
            throw CliServiceException(e.code, e)
        }
    }

    @Suppress("ThrowsCount") // Each rejection has a stable, non-sensitive error code.
    private suspend fun run(
        context: CliCallContext,
        request: EnvironmentRunCliRequest,
    ): EnvironmentRunCliResult {
        val state = repository.state.value
        if (!state.unlocked) throw EnvironmentServiceException("vault_locked")
        val command = request.command
            .takeIf { it.isNotEmpty() }
            ?: throw EnvironmentServiceException("invalid_request")
        val workingDirectory = request.workingDirectory
        val accountId = request.account?.let { state.accountId(it) }
        val environmentId = state.environmentId(request.environment, accountId)
        val identity = vault.sessionIdentity()
        val epoch = access.generation.value
        val delivery = access.deliveryGeneration(environmentId)
        val challenge = access.elevationChallenge(environmentId)
        cli.approve(
            context = context,
            // Every revocation of the environments' grants replaces the
            // generation, and a change of the environment or of a referenced
            // item its delivery generation, so the approval of a terminal
            // tab ends with either.
            grant = RunGrant(environmentId, epoch, delivery),
            elevationPending = challenge != null,
            confirmIdentity = { verify -> access.ensureElevatedAccess(environmentId, verify = verify) },
            check = {
                vault.checkSession(identity)
                // Revoked in this session: MCP, the local .env files, permissions, or the environment changed.
                if (access.generation.value != epoch || access.deliveryGeneration(environmentId) != delivery) {
                    throw EnvironmentServiceException("approval_revoked")
                }
            },
        ) {
            val current = repository.state.value
            val environmentAccountId = current.environments
                .firstOrNull { it.id == environmentId }
                ?.accountId
            CliOperation.Run(
                environmentId = environmentId,
                environmentName = repository.environmentName(environmentId),
                accountName = current.accounts
                    .firstOrNull { it.id == environmentAccountId }
                    ?.name,
                command = command,
                workingDirectory = workingDirectory,
            )
        }
        val variables = repository.resolveForDelivery(environmentId).variables
        val size = variables.entries.sumOf { (name, value) ->
            name.encodeToByteArray().size + value.encodeToByteArray().size
        }
        if (size > EnvironmentFormat.MAX_PAYLOAD_BYTES) throw EnvironmentServiceException("environment_too_large")
        vault.checkSession(identity)
        return EnvironmentRunCliResult(variables)
    }
}

/**
 * The request of `keyguard run`: `RunRequest` in `commonCli`. The
 * [command] and [workingDirectory] are display data: the CLI starts the
 * command.
 */
@Serializable
data class EnvironmentRunCliRequest(
    val environment: String,
    /** `null` to look the environment up in every account. */
    val account: String? = null,
    val command: List<String>,
    val workingDirectory: String,
)

/** The values of an environment, by variable name. */
@Serializable
class EnvironmentRunCliResult(
    val variables: Map<String, String>,
) {
    override fun toString(): String = "EnvironmentRunCliResult([redacted])"
}

/** A `keyguard run` approval of one environment, until the environments' grants are revoked. */
private data class RunGrant(
    val environmentId: String,
    val generation: String,
    val delivery: String,
)
