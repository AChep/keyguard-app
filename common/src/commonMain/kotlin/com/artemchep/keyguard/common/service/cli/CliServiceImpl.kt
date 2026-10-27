package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheIdentity
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.agent.fixedAgentApprovalCacheConfig
import com.artemchep.keyguard.common.service.agent.limitedTo
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CliServiceImpl(
    private val getVaultSession: GetVaultSession,
    private val cryptoGenerator: CryptoGenerator,
    storeFactory: KeyValueStoreFactory,
    scope: CoroutineScope,
    timeSource: TimeSource = TimeSource.Monotonic,
    /** How long a terminal tab keeps an approval. */
    approvalCacheConfig: AgentApprovalCacheConfigProvider<AgentApprovalCachePolicy> =
        fixedAgentApprovalCacheConfig(Duration.INFINITE, AgentApprovalCachePolicy.Default),
) : CliRuntimeHost {
    private val store = storeFactory.get(Files.SETTINGS)
    private val enabledPreference = store.getBoolean("cli.enabled", false)
    private val mutableState = MutableStateFlow(CliServiceState())
    override val state = mutableState.asStateFlow()
    private val approvalBroker = AgentApprovalBroker<CliApprovalRequest>(idOf = { it.id })
    override val approvals = approvalBroker.pending

    /**
     * Approvals that a terminal tab may reuse, for the CLI approval window.
     * The cache policy of the configuration is unused: the grants are keyed
     * by the verified terminal session, not the caller.
     */
    private val grants = AgentApprovalWindowMemory<CliGrant, AgentApprovalCachePolicy>(
        approvalCacheConfig = approvalCacheConfig.limitedTo(GRANT_LIMIT),
        getVaultSession = getVaultSession,
        scope = scope,
        idleTimeout = GRANT_IDLE,
        timeSource = timeSource,
    )

    /** Replaced whenever the grants are revoked; an approval given meanwhile is never used. */
    private val generation = MutableStateFlow(cryptoGenerator.uuid())

    /** How the user confirms their identity for each pending prompt, by its id. */
    private val identityConfirmations = MutableStateFlow<Map<String, CliIdentityConfirmation>>(emptyMap())

    init {
        scope.launch {
            enabledPreference.collect { enabled ->
                val previous = mutableState.value.enabled
                mutableState.update { it.copy(enabled = enabled) }
                if (previous != enabled) invalidate()
            }
        }
        scope.launch { approvalBroker.denyWhenSessionEnds(getVaultSession()) }
        scope.launch {
            getVaultSession().collectLatest { session ->
                invalidate()
                val key = session as? MasterSession.Key ?: return@collectLatest
                key.session.active.collect { active ->
                    if (!active) invalidate()
                }
            }
        }
    }

    override fun configureRuntime(path: String?, capabilities: Set<CliPlatformCapability>, socketPath: String?) {
        mutableState.update { it.copy(path = path, capabilities = capabilities, socketPath = socketPath) }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        enabledPreference.setAndCommit(enabled).bind()
        mutableState.update { it.copy(enabled = enabled) }
        invalidate()
    }

    override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean {
        val confirm = identityConfirmations.value[id] ?: return false
        // The prompt may end while the user confirms their identity.
        return confirm { verify() && approvals.value.any { it.id == id } }
    }

    override fun respondToApproval(id: String, approved: Boolean): Boolean =
        approvalBroker.respond(id, approved)

    /** Revokes the grants of every terminal tab. */
    private suspend fun invalidate() {
        grants.invalidateAll()
        generation.value = cryptoGenerator.uuid()
    }

    private fun currentKey(): MasterSession.Key =
        (getVaultSession.valueOrNull as? MasterSession.Key)
            ?.takeIf { it.session.active.value }
            ?: throw CliServiceException("vault_locked")

    internal fun isUnlocked(): Boolean =
        (getVaultSession.valueOrNull as? MasterSession.Key)?.session?.active?.value == true

    /**
     * Confirms an operation of the CLI, prompting the user unless the
     * terminal tab of the [context] has a remembered approval of an equal
     * [grant]. With a verified terminal session, approving also covers
     * later equal grants of that tab, until the approval is unused for
     * [GRANT_IDLE], [GRANT_LIMIT] passes, or the grants are revoked. Marks
     * the [context] as [approved][CliCallContext.approved] once it returns.
     *
     * @param grant What the approval allows; a command includes everything
     *   whose change must revoke it.
     * @param elevationPending Whether the user must confirm their identity;
     *   a remembered approval never skips it.
     * @param confirmIdentity How the user confirms their identity before
     *   approving the prompt, if the operation requires it.
     * @param check The command's own checks, after the prompt and before
     *   the approval is remembered.
     * @param operation What the prompt shows; built only when prompting.
     */
    @Suppress("ThrowsCount", "LongParameterList") // Denial, expiry, lock, and revocation have separate codes.
    internal suspend fun approve(
        context: CliCallContext,
        grant: Any,
        elevationPending: Boolean,
        confirmIdentity: CliIdentityConfirmation,
        check: suspend () -> Unit = {},
        operation: suspend () -> CliOperation,
    ) {
        val key = currentKey()
        val identity = key.session.id
        val epoch = generation.value
        val access = context.terminalSession?.let { terminal ->
            grants.getOrGenerateSession(key).access { CliGrant(grant, terminal) }
        }
        if (access?.isRemembered == true && !elevationPending) {
            context.approved = true
            return
        }
        val prompt = operation()
        val id = cryptoGenerator.uuid()
        identityConfirmations.update { it + (id to confirmIdentity) }
        val result = try {
            approvalBroker.request { expiresAt ->
                CliApprovalRequest(
                    id = id,
                    caller = context.caller,
                    terminalSession = context.terminalSession != null,
                    expiresAt = expiresAt,
                    operation = prompt,
                )
            }
        } finally {
            identityConfirmations.update { it - id }
        }
        when (result) {
            AgentApprovalBroker.Result.Approved -> Unit
            AgentApprovalBroker.Result.Denied -> throw CliServiceException("user_denied")
            AgentApprovalBroker.Result.Expired -> throw CliServiceException("approval_timeout")
        }
        currentCoroutineContext().ensureActive()
        val session = currentKey().session.id
        if (!state.value.enabled) throw CliServiceException("integration_disabled")
        // The vault is unlocked, but the approval belongs to a session or
        // to grants that were replaced while the user was asked.
        if (session != identity || generation.value != epoch) {
            throw CliServiceException("approval_revoked")
        }
        check()
        access?.remember()
        context.approved = true
    }
}

/**
 * Confirms the user's identity with `verify`, if the operation of a prompt
 * requires it. Returns `false` if the user did not confirm it.
 */
internal typealias CliIdentityConfirmation = suspend (verify: suspend () -> Boolean) -> Boolean

private val GRANT_IDLE = 10.minutes
private val GRANT_LIMIT = 12.hours

/** An approval of one command's [grant] for one verified terminal tab. */
private data class CliGrant(
    val grant: Any,
    val terminal: AgentApprovalCacheIdentity,
)
