package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheIdentity
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.flowBackedAgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.toApprovalCacheIdentity
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicyNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope

/**
 * Remembers granted GPG approvals for the duration of the user's approval
 * window, so repeated operations with the same key and caller do not
 * re-prompt. Approvals are scoped to the currently unlocked vault session and
 * invalidated when the window setting changes, when it is set to zero, or
 * when the vault locks.
 *
 * Processors that serve one agent share an instance, so an approval outlives
 * the connection that granted it.
 */
class GpgAgentApprovalWindowMemory(
    getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow,
    getVaultSession: GetVaultSession,
    scope: CoroutineScope,
    getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy =
        GetGpgAgentApprovalCachePolicyNoOp,
) {
    private val approvalCacheConfig = getGpgAgentApprovalCachePolicy.approvalCacheConfig
        ?: flowBackedAgentApprovalCacheConfigProvider(
            approvalWindow = getGpgAgentApprovalWindow(),
            cachePolicy = getGpgAgentApprovalCachePolicy(),
            scope = scope,
        )

    private val memory =
        AgentApprovalWindowMemory<GpgApprovalCacheKey, AgentApprovalCachePolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = getVaultSession,
            scope = scope,
        )

    suspend fun clearSession() {
        memory.clearSession()
    }

    suspend fun getOrGenerateSession(
        session: MasterSession.Key,
    ): Session = Session(memory.getOrGenerateSession(session))

    inner class Session internal constructor(
        private val session: AgentApprovalWindowMemory<GpgApprovalCacheKey, AgentApprovalCachePolicy>.Session,
    ) {
        suspend fun access(
            operation: GpgAgentOperation,
            keygrip: String,
            caller: CallerIdentity?,
        ): Access = Access(
            session.access { policy -> gpgApprovalCacheKey(operation, keygrip, caller, policy) },
        )
    }

    inner class Access internal constructor(
        private val access: AgentApprovalWindowMemory<GpgApprovalCacheKey, AgentApprovalCachePolicy>.Access,
    ) {
        /** The original lookup result; use [canReuseNow] after suspending. */
        val isRemembered: Boolean
            get() = access.isRemembered

        fun canReuseNow(): Boolean = access.canReuseNow()

        suspend fun remember() {
            access.remember()
        }
    }
}

internal fun gpgApprovalCacheKey(
    operation: GpgAgentOperation,
    keygrip: String,
    caller: CallerIdentity?,
    policy: AgentApprovalCachePolicy = AgentApprovalCachePolicy.Default,
): GpgApprovalCacheKey? {
    val callerIdentity = caller.toApprovalCacheIdentity(policy)
        ?: return null
    return GpgApprovalCacheKey(
        operation = operation,
        // The keygrip is already normalized by the caller (runKeyOperation); normalizing
        // once there keeps a single source of truth for the cache-key identity.
        keygrip = keygrip,
        callerIdentity = callerIdentity,
    )
}

internal data class GpgApprovalCacheKey(
    val operation: GpgAgentOperation,
    val keygrip: String,
    val callerIdentity: AgentApprovalCacheIdentity,
)
