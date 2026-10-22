package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.agent.fixedAgentApprovalCacheConfig
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the grants are revoked, and what else a revocation retires. */
internal enum class EnvironmentRevocation(
    /** Also forget the access the user confirmed. */
    val elevation: Boolean,
    /** Also retire the readers of every local `.env` file. */
    val delivery: Boolean,
) {
    /** MCP or the local `.env` files changed; the access the user confirmed stays. */
    ConfigurationChanged(elevation = false, delivery = true),

    /** The access to an environment or to an item it references changed. */
    PermissionsChanged(elevation = true, delivery = false),

    /**
     * The vault unlocked. A reader that requested access while
     * locked is waiting for this unlock, so it keeps its place.
     */
    Unlocked(elevation = true, delivery = false),

    /** The vault locked, or its session was replaced. */
    SessionEnded(elevation = true, delivery = true),
}

/**
 * Decides who may access the environments right now: the approval prompts,
 * the approvals of the callers, the access the user confirmed, and the
 * directory grants. A [revoke] retires them together, so one class keeps them.
 */
@Suppress("TooManyFunctions") // Every access decision shares one revocation.
class EnvironmentAccessImpl(
    private val vault: EnvironmentVault,
    private val getVaultSession: GetVaultSession,
    private val cryptoGenerator: CryptoGenerator,
    scope: CoroutineScope,
    timeSource: TimeSource = TimeSource.Monotonic,
    /** How long an MCP client, or a reader of a local `.env` file, keeps an approval. */
    mcpApprovalCacheConfig: AgentApprovalCacheConfigProvider<AgentApprovalCachePolicy> =
        fixedAgentApprovalCacheConfig(Duration.INFINITE, AgentApprovalCachePolicy.Default),
) : EnvironmentAccess {
    private val approvalBroker = AgentApprovalBroker<EnvironmentApprovalRequest>(idOf = { it.id })
    override val approvals = approvalBroker.pending

    /**
     * Approvals of an MCP client or of a local `.env` file, for the MCP
     * approval window, until the vault locks or the grants are revoked. The
     * approval of a local `.env` file also ends when its environment changes.
     * The cache policy of the configuration is unused: the grants are keyed
     * by the broker's connection, not the caller.
     */
    private val grants = AgentApprovalWindowMemory<CallerGrant, AgentApprovalCachePolicy>(
        approvalCacheConfig = mcpApprovalCacheConfig,
        getVaultSession = getVaultSession,
        scope = scope,
        timeSource = timeSource,
    )
    private val elevations = MutableStateFlow<Set<String>>(emptySet())
    private val directoryGrants = MutableStateFlow<Map<String, String>>(emptyMap())

    private val mutableGeneration = MutableStateFlow(cryptoGenerator.uuid())

    /**
     * Replaced on every [revoke]. Callers that remember approvals
     * elsewhere, such as the CLI, include it in theirs.
     */
    internal val generation: StateFlow<String> = mutableGeneration.asStateFlow()

    /**
     * Elevated access confirms the user, not a caller, so it outlives the
     * configuration changes that revoke the grants of the callers.
     */
    private val elevationGeneration = MutableStateFlow(cryptoGenerator.uuid())
    private val deliveryGenerations = MutableStateFlow(DeliveryGenerations(cryptoGenerator.uuid()))

    init {
        scope.launch { approvalBroker.denyWhenSessionEnds(getVaultSession()) }
    }

    /**
     * The generation of the readers of the local `.env` files of the
     * environment, replaced when the environment or a referenced item
     * changes. The approvals that hand out its values include it.
     */
    internal fun deliveryGeneration(environmentId: String): String = deliveryGenerations.value[environmentId]

    /** Revokes the grants of every caller, and what else the [reason] requires. */
    internal suspend fun revoke(reason: EnvironmentRevocation) {
        grants.invalidateAll()
        if (reason.elevation) {
            elevations.value = emptySet()
            elevationGeneration.value = cryptoGenerator.uuid()
        }
        directoryGrants.value = emptyMap()
        mutableGeneration.value = cryptoGenerator.uuid()
        if (reason.delivery) deliveryGenerations.value = DeliveryGenerations(cryptoGenerator.uuid())
    }

    /**
     * Retires the readers of the [changed] environments only, and forgets
     * the generations of the environments that are no longer [available].
     */
    internal fun retireDelivery(available: Set<String>, changed: Set<String>) {
        val retired = changed.associateWith { cryptoGenerator.uuid() }
        deliveryGenerations.update { generations ->
            generations.copy(environments = generations.environments.filterKeys { it in available } + retired)
        }
    }

    /** Keeps the names and the challenges of the pending prompts up to date. */
    internal fun refreshApprovals(identity: String, secrets: List<DSecret>) {
        approvalBroker.update { request ->
            val secret = secrets.firstOrNull { it.id == request.environmentId }
            if (secret == null) request else request.copy(
                environmentName = secret.name,
                elevationChallenge = elevationKey(secret.id, identity).takeIf {
                    secret.reprompt && it !in elevations.value
                },
            )
        }
    }

    private fun elevationKey(id: String, identity: String = vault.sessionIdentity()): String =
        "$identity\u0000${elevationGeneration.value}\u0000$id"

    override suspend fun elevationChallenge(id: String): String? = vault.withVault { dependencies ->
        val secret = dependencies.environmentSecret(id)
        elevationKey(id).takeIf { secret.reprompt && it !in elevations.value }
    }

    override fun grantElevatedAccess(id: String, challenge: String) {
        if (elevationKey(id) != challenge) throw EnvironmentServiceException("vault_locked")
        elevations.update { it + challenge }
    }

    /** Throws unless the user may read the [secret] now. */
    internal fun checkReadAccess(secret: DSecret, permissions: EnvironmentPermissions) {
        if (!permissions.canRead) throw EnvironmentServiceException("permission_denied")
        checkElevatedAccess(secret)
    }

    internal fun checkElevatedAccess(secret: DSecret) {
        if (secret.reprompt && elevationKey(secret.id) !in elevations.value) {
            throw EnvironmentServiceException("reauthentication_required")
        }
    }

    override fun provideDirectoryGrant(path: String, bookmark: String) {
        directoryGrants.update { it + (path to bookmark) }
    }

    internal fun directoryGrant(path: String): String? = directoryGrants.value[path]

    override fun respondToApproval(id: String, approved: Boolean): Boolean =
        approvalBroker.respond(id, approved)

    /** The deadline of a prompt shown now; the prompts of one request share it. */
    internal fun approvalDeadline(): Instant = approvalBroker.deadline()

    /**
     * Confirms that the caller may use [capability], prompting the user
     * unless the approval is remembered. A prompt is denied at [deadline].
     *
     * @param accountId The account of a new environment.
     */
    internal suspend fun approve(
        callerId: String,
        caller: EnvironmentCaller?,
        capability: EnvironmentCapability,
        environmentId: String? = null,
        path: String? = null,
        deadline: Instant = approvalDeadline(),
        accountId: String? = null,
    ) {
        val key = (getVaultSession.valueOrNull as? MasterSession.Key)
            ?.takeIf { it.session.active.value }
        val identity = key?.session?.id
        val epoch = generation.value
        // A reader of a local .env file receives the values, so a change
        // of the environment or of a referenced item ends its approval.
        val delivery = environmentId
            ?.takeIf { capability == EnvironmentCapability.ReadLocalEnvFile }
            ?.let(::deliveryGeneration)
        val grant = CallerGrant(callerId, capability, environmentId, path, accountId, delivery)
        val challenge = if (identity != null && environmentId != null) elevationChallenge(environmentId) else null
        val access = key?.let { grants.getOrGenerateSession(it).access { grant } }
        if (access?.isRemembered == true && challenge == null) return
        val environmentName = if (identity != null && environmentId != null) {
            vault.withVault { it.environmentSecret(environmentId).name }
        } else {
            null
        }
        requestApproval(deadline, identity, epoch) { expiresAt ->
            EnvironmentApprovalRequest(
                cryptoGenerator.uuid(), caller, environmentName, capability, path, expiresAt,
                environmentId = environmentId, elevationChallenge = challenge, accountId = accountId,
            )
        }
        // An approval given while locked belongs to the session it unlocked.
        (access ?: grants.getOrGenerateSession(vault.currentKey()).access { grant }).remember()
    }

    /**
     * Shows the prompt that [build] creates and waits for the user. The
     * approval belongs to the session [identity] and grant [epoch]; an
     * unlocked vault is required.
     */
    @Suppress("ThrowsCount") // Denial, expiry, and session change deliberately have separate failure codes.
    private suspend fun requestApproval(
        deadline: Instant,
        identity: String?,
        epoch: String,
        build: (expiresAt: Instant) -> EnvironmentApprovalRequest,
    ) {
        when (approvalBroker.request(deadline, build)) {
            AgentApprovalBroker.Result.Approved -> Unit
            AgentApprovalBroker.Result.Denied -> throw EnvironmentServiceException("user_denied")
            AgentApprovalBroker.Result.Expired -> throw EnvironmentServiceException("approval_timeout")
        }
        currentCoroutineContext().ensureActive()
        vault.sessionIdentity()
        if (identity != null) {
            vault.checkSession(identity)
            if (generation.value != epoch) throw EnvironmentServiceException("vault_locked")
        }
    }
}

/** What one approval of a broker caller allows. */
private data class CallerGrant(
    val callerId: String,
    val capability: EnvironmentCapability,
    val environmentId: String?,
    val path: String?,
    val accountId: String?,
    /** The delivery generation of the environment, if the approval hands out its values. */
    val delivery: String?,
)

/** Opaque tokens only: global revocation replaces the fallback and every environment override atomically. */
private data class DeliveryGenerations(
    val fallback: String,
    val environments: Map<String, String> = emptyMap(),
) {
    operator fun get(environmentId: String): String = environments[environmentId] ?: fallback
}
