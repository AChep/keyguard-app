package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.ManageResponse
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Request
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Response
import com.artemchep.keyguard.common.service.cli.agent.cliAgentError
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentDotenv
import com.artemchep.keyguard.feature.environments.EnvironmentDotenvException
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Private MCP management and local `.env` file operations of the agent;
 * never use this dispatcher for a public socket.
 */
class EnvironmentBrokerHandler(
    private val mcp: EnvironmentMcpBrokerHandler,
    private val mounts: EnvironmentMountBrokerHandler,
) {
    // The approval broker bounds the time spent waiting on the user, and
    // the agent's own transport timeout bounds the request as a whole.
    suspend fun handle(request: Request.Kind): Response.Kind = try {
        when (request) {
            is Request.Kind.Manage -> mcp.manage(request.value)
            is Request.Kind.Mounts -> mounts.mounts()
            is Request.Kind.WatchMounts -> mounts.watchMounts(request.value.revision)
            is Request.Kind.MountStatus -> mounts.mountStatus(request.value)
            is Request.Kind.ResolveMount -> mounts.resolveMount(request.value)
            is Request.Kind.Authenticate,
            is Request.Kind.Cli,
            -> cliAgentError("invalid_request")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EnvironmentServiceException) {
        // The agent decodes the requests; each operation names what is
        // invalid about one. Any other failure is not the request's.
        cliAgentError(e.code)
    } catch (_: Exception) {
        // Provider/network/codec exception messages can contain values or paths.
        cliAgentError("operation_failed")
    }
}

/** The local `.env` file operations of the agent. */
class EnvironmentMountBrokerHandler(
    private val vault: EnvironmentVault,
    private val repository: EnvironmentRepositoryImpl,
    private val access: EnvironmentAccessImpl,
    private val mounts: EnvironmentMountsImpl,
    private val directoryAccess: EnvironmentDirectoryAccess = EnvironmentDirectoryAccess.ByPath,
) {
    internal suspend fun mounts(): Response.Kind {
        val revision = mounts.revision
        val served = directoryAccess.grant(mounts.served(repository.state.value)).map { (mount, bookmark) ->
            CliAgentMessages.Mount(
                id = mount.id,
                path = mount.path,
                generation = access.deliveryGeneration(mount.environmentId),
                bookmark = bookmark,
            )
        }
        return Response.Kind.Mounts(CliAgentMessages.MountsResponse(revision, served))
    }

    internal suspend fun watchMounts(previous: String): Response.Kind {
        withTimeoutOrNull(WATCH_TIMEOUT_MS) { mounts.awaitRevision(previous) }
        return mounts()
    }

    internal fun mountStatus(request: CliAgentMessages.MountStatusRequest): Response.Kind {
        mounts.setStatus(request.mountId, request.status.toMountStatus())
        return Response.Kind.MountStatus(CliAgentMessages.Empty)
    }

    @Suppress("ThrowsCount") // Each delivery rejection has a stable, non-sensitive error code.
    internal suspend fun resolveMount(request: CliAgentMessages.ResolveMountRequest): Response.Kind {
        val generation = request.generation
        val mount = mounts.state.value.mounts.firstOrNull { it.id == request.mountId && it.enabled }
            ?: throw EnvironmentServiceException("not_found")
        if (access.deliveryGeneration(mount.environmentId) != generation) {
            throw EnvironmentServiceException("vault_locked")
        }
        // FIFOs cannot establish a reader process identity. Approval is for this
        // environment/path and never inherits the initiating MCP client's grant.
        access.approve(
            "mount:${mount.id}", null, EnvironmentCapability.ReadLocalEnvFile, mount.environmentId, mount.path,
        )
        if (access.deliveryGeneration(mount.environmentId) != generation) {
            throw EnvironmentServiceException("vault_locked")
        }
        val identity = vault.sessionIdentity()
        val snapshot = repository.resolveForDelivery(mount.environmentId)
        val content = try {
            EnvironmentDotenv.format(snapshot).encodeToByteArray()
        } catch (_: EnvironmentDotenvException) {
            throw EnvironmentServiceException("unresolved_environment")
        }
        if (content.size > EnvironmentFormat.MAX_PAYLOAD_BYTES) {
            throw EnvironmentServiceException("environment_too_large")
        }
        vault.checkSession(identity)
        if (
            access.deliveryGeneration(mount.environmentId) != generation ||
            mounts.state.value.mounts.none { it == mount }
        ) {
            throw EnvironmentServiceException("vault_locked")
        }
        return Response.Kind.ResolveMount(CliAgentMessages.ResolveMountResponse(generation, content))
    }

    private companion object {
        const val WATCH_TIMEOUT_MS = 25_000L
    }
}

/** The MCP management of the agent. */
class EnvironmentMcpBrokerHandler(
    private val vault: EnvironmentVault,
    private val repository: EnvironmentRepositoryImpl,
    private val access: EnvironmentAccessImpl,
    private val mounts: EnvironmentMountsImpl,
    private val mcp: EnvironmentMcpImpl,
) {
    @Suppress("ThrowsCount") // Validate broker identity and session authorization before dispatch.
    internal suspend fun manage(request: CliAgentMessages.ManageRequest): Response.Kind {
        if (!mcp.state.value.enabled) throw EnvironmentServiceException("integration_disabled")
        val caller = request.caller ?: throw EnvironmentServiceException("invalid_request")
        // The trusted, kernel-verified child generates this identity for each connection.
        if (request.connectionId.size != CONNECTION_ID_SIZE) throw EnvironmentServiceException("invalid_request")
        val callerId = request.connectionId.toHex()
        val callerIdentity = caller.toEnvironmentCaller(request.clientName)
        val tool = request.tool
        // Every prompt of this request shares one deadline, so the request
        // ends before the agent's transport gives up on it.
        val deadline = access.approvalDeadline()
        access.approve(callerId, callerIdentity, EnvironmentCapability.Authenticate, deadline = deadline)
        val identity = vault.sessionIdentity()
        val epoch = access.generation.value
        val guard = EnvironmentOperationGuard(currentCoroutineContext()) {
            if (!mcp.state.value.enabled) throw EnvironmentServiceException("integration_disabled")
            vault.checkSession(identity)
            if (access.generation.value != epoch) throw EnvironmentServiceException("authorization_expired")
        }
        suspend fun approve(
            environmentId: String?,
            path: String?,
            capability: EnvironmentCapability,
            accountId: String?,
        ) {
            access.approve(callerId, callerIdentity, capability, environmentId, path, deadline, accountId)
            guard.check()
        }
        val result = withContext(guard) {
            guard.check()
            EnvironmentManagementOperation(repository, mounts, request, ::approve).execute(tool)
        }
        vault.checkSession(identity)
        return Response.Kind.Manage(ManageResponse(result))
    }

    private companion object {
        /** The size of the identity the agent generates for a public connection, in bytes. */
        const val CONNECTION_ID_SIZE = 32
    }
}

private class EnvironmentManagementOperation(
    private val repository: EnvironmentRepositoryImpl,
    private val mounts: EnvironmentMountsImpl,
    private val request: CliAgentMessages.ManageRequest,
    /** Confirms an environment, a path, a capability, and the account of a new environment. */
    private val approve: suspend (String?, String?, EnvironmentCapability, String?) -> Unit,
) {
    suspend fun execute(tool: String): ManageResponse.Result = when (tool) {
        "authenticate" -> ManageResponse.Result.Accounts(
            CliAgentMessages.AccountList(
                accounts = repository.state.value.accounts.map { account ->
                    CliAgentMessages.Account(id = account.externalId, name = account.name)
                },
            ),
        )
        "list_environments" -> listEnvironments()
        "create_environment" -> {
            val state = repository.state.value
            val accountId = state.accountId(argument("account_id"))
            approve(null, null, EnvironmentCapability.CreateEnvironment, accountId)
            val id = repository.save(null, accountId, argument("name"), EnvironmentDefinition())
            environmentMetadata(id, accountId)
        }
        "rename_environment" -> {
            val environment = environment(argument("environment_id"))
            approve(environment.id, null, EnvironmentCapability.RenameEnvironment, null)
            repository.rename(environment.id, argument("name"))
            environmentMetadata(environment.id, environment.accountId)
        }
        "list_variables" -> listVariables()
        "append_variables" -> appendVariables()
        "create_local_env_file" -> {
            val id = environment(argument("environment_id")).id
            if (!mounts.state.value.supported) throw EnvironmentServiceException("unsupported_platform")
            // Approve the path in the form it is stored.
            val path = normalizeEnvironmentMountPath(argument("path"))
                ?: throw EnvironmentServiceException("invalid_path")
            approve(id, path, EnvironmentCapability.CreateLocalEnvFile, null)
            ManageResponse.Result.LocalEnvFile(mountMetadata(mounts.create(id, path)))
        }
        "list_local_env_files" -> listLocalEnvFiles()
        else -> throw EnvironmentServiceException("unknown_tool")
    }

    private fun argument(name: String): String = request.arguments[name]
        ?: throw EnvironmentServiceException("invalid_request")

    /** Resolve within the requested account before approving or operating on the local ID. */
    private fun environment(environment: String): EnvironmentSummary {
        val state = repository.state.value
        val accountId = request.arguments["account_id"]?.let { state.accountId(it) }
        val id = state.environmentId(environment, accountId)
        return state.environments.first { it.id == id }
    }

    /** The ID a client sees for an environment, given its local ID. */
    private fun externalId(id: String): String = repository.state.value.environments
        .firstOrNull { it.id == id }
        ?.externalId
        ?: id

    private fun externalAccountId(id: String): String = repository.state.value.accounts
        .firstOrNull { it.id == id }
        ?.externalId
        ?: id

    private fun environmentMetadata(id: String, accountId: String) = ManageResponse.Result.Environment(
        CliAgentMessages.EnvironmentId(id = externalId(id), accountId = externalAccountId(accountId)),
    )

    private fun listEnvironments(): ManageResponse.Result {
        val state = repository.state.value
        val accountId = request.arguments["account_id"]?.let { state.accountId(it) }
        val accounts = state.accounts.associateBy { it.id }
        val environments = state.environments
            .filter { accountId == null || it.accountId == accountId }
            .map { environment ->
                CliAgentMessages.Environment(
                    id = environment.externalId,
                    accountId = accounts[environment.accountId]?.externalId ?: environment.accountId,
                    name = environment.name,
                    variableCount = environment.variableCount,
                    editable = environment.editable,
                )
            }
        return ManageResponse.Result.Environments(CliAgentMessages.EnvironmentList(environments))
    }

    private suspend fun listVariables(): ManageResponse.Result {
        val id = environment(argument("environment_id")).id
        approve(id, null, EnvironmentCapability.ListVariables, null)
        val document = repository.load(id)
        val definition = document.definition ?: throw EnvironmentServiceException("unsupported_environment")
        val variables = definition.variables.map { variable ->
            CliAgentMessages.Variable(
                name = variable.name,
                source = variable.source.mcpKind(),
                isSet = variable.source is EnvironmentValueSource.Literal ||
                    variable.source is EnvironmentValueSource.Reference,
                supported = variable.source !is EnvironmentValueSource.Unsupported,
            )
        }
        return ManageResponse.Result.Variables(CliAgentMessages.VariableList(variables))
    }

    private suspend fun appendVariables(): ManageResponse.Result {
        val environment = environment(argument("environment_id"))
        val updates = request.variables.map { variable ->
            variable.name to variable.value?.let(::text)
        }
        if (updates.isEmpty() || updates.map { it.first }.distinct().size != updates.size) {
            throw EnvironmentServiceException("invalid_request")
        }
        approve(environment.id, null, EnvironmentCapability.AppendVariables, null)
        repository.appendVariables(environment.id, updates)
        return environmentMetadata(environment.id, environment.accountId)
    }

    private suspend fun listLocalEnvFiles(): ManageResponse.Result {
        val state = repository.state.value
        val accountId = request.arguments["account_id"]?.let { state.accountId(it) }
        val id = request.arguments["environment_id"]?.let { state.environmentId(it, accountId) }
        approve(id, null, EnvironmentCapability.ListLocalEnvFiles, null)
        val current = repository.state.value
        val accountEnvironments = accountId?.let { account ->
            current.environments.filter { it.accountId == account }.map { it.id }.toSet()
        }
        return ManageResponse.Result.LocalEnvFiles(
            CliAgentMessages.LocalEnvFileList(
                localEnvFiles = mounts.state.value.mounts
                    .filter { id == null || it.environmentId == id }
                    .filter { accountEnvironments == null || it.environmentId in accountEnvironments }
                    .map(::mountMetadata),
            ),
        )
    }

    private fun mountMetadata(mount: EnvironmentMount) = CliAgentMessages.LocalEnvFile(
        id = mount.id,
        environmentId = externalId(mount.environmentId),
        accountId = repository.state.value.environments
            .firstOrNull { it.id == mount.environmentId }
            ?.accountId
            ?.let(::externalAccountId),
        path = mount.path,
        enabled = mount.enabled,
        status = (mounts.state.value.statuses[mount.id] ?: EnvironmentMountStatus.Pending).toMessage(),
    )
}

private fun text(value: ByteArray): String = try {
    value.decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    throw EnvironmentServiceException("invalid_request")
}

/** How a variable is sourced, never which item or field it references. */
private fun EnvironmentValueSource.mcpKind(): CliAgentMessages.VariableSource = when (this) {
    is EnvironmentValueSource.Literal -> CliAgentMessages.VariableSource.VALUE
    is EnvironmentValueSource.Reference -> CliAgentMessages.VariableSource.REFERENCE
    EnvironmentValueSource.Unset -> CliAgentMessages.VariableSource.UNSET
    is EnvironmentValueSource.Unsupported -> CliAgentMessages.VariableSource.UNSUPPORTED
}

private fun EnvironmentMountStatus.toMessage(): CliAgentMessages.MountStatus = when (this) {
    EnvironmentMountStatus.Pending -> CliAgentMessages.MountStatus.PENDING
    EnvironmentMountStatus.Ready -> CliAgentMessages.MountStatus.READY
    EnvironmentMountStatus.Error -> CliAgentMessages.MountStatus.ERROR
}

private fun CliAgentMessages.MountStatus.toMountStatus(): EnvironmentMountStatus = when (this) {
    CliAgentMessages.MountStatus.PENDING -> EnvironmentMountStatus.Pending
    CliAgentMessages.MountStatus.READY -> EnvironmentMountStatus.Ready
    CliAgentMessages.MountStatus.ERROR -> EnvironmentMountStatus.Error
    CliAgentMessages.MountStatus.UNSPECIFIED -> throw EnvironmentServiceException("invalid_request")
}

/**
 * The caller attached by the agent, for display. The prompt bounds every
 * value; the client name is reported by the MCP client itself.
 */
private fun CallerIdentity.toEnvironmentCaller(clientName: String) = EnvironmentCaller(
    pid = pid,
    processName = processName,
    executablePath = executablePath,
    appName = appName,
    appBundlePath = appBundlePath,
    clientName = clientName
        .take(MAX_AGENT_CALLER_NAME_LENGTH)
        .takeIf(String::isNotBlank),
)
