package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheIdentity
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.agent.flowBackedAgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.toApprovalCacheIdentity
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicyNoOp
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.toHex
import kotlinx.coroutines.CoroutineScope

/**
 * Remembers granted signing approvals for the duration of the user's
 * "Remember key approvals" window, so repeated signatures with the same key
 * and caller do not re-prompt. Approvals are scoped to the currently unlocked
 * vault session and invalidated when the window setting changes, when it is
 * set to zero, or when the vault locks.
 */
class SshAgentApprovalWindowMemory(
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    getVaultSession: GetVaultSession,
    scope: CoroutineScope,
    getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy =
        GetSshAgentApprovalCachePolicyNoOp,
) {
    private val approvalCacheConfig = getSshAgentApprovalCachePolicy.approvalCacheConfig
        ?: flowBackedAgentApprovalCacheConfigProvider(
            approvalWindow = getSshAgentApprovalWindow(),
            cachePolicy = getSshAgentApprovalCachePolicy(),
            scope = scope,
        )

    private val memory =
        AgentApprovalWindowMemory<SshApprovalCacheKey, AgentApprovalCachePolicy>(
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

    private fun SshAgentMessages.SignDataRequest.toApprovalCacheKey(
        policy: AgentApprovalCachePolicy,
    ): SshApprovalCacheKey? {
        val callerIdentity = caller.toApprovalCacheIdentity(policy)
            ?: return null
        val publicKeyToken = decodeSshPublicKeyBlob(publicKey)
            ?.toHex()
            ?: publicKey.trim()
        return SshApprovalCacheKey(
            publicKeyToken = publicKeyToken,
            callerIdentity = callerIdentity,
        )
    }

    inner class Session internal constructor(
        private val session: AgentApprovalWindowMemory<SshApprovalCacheKey, AgentApprovalCachePolicy>.Session,
    ) {
        val generation: Long
            get() = session.generation

        suspend fun access(
            request: SshAgentMessages.SignDataRequest,
        ): Access = Access(
            session.access { policy -> request.toApprovalCacheKey(policy) },
        )
    }

    inner class Access internal constructor(
        private val access: AgentApprovalWindowMemory<SshApprovalCacheKey, AgentApprovalCachePolicy>.Access,
    ) {
        val isRemembered: Boolean
            get() = access.isRemembered

        suspend fun remember() {
            access.remember()
        }
    }

    internal data class SshApprovalCacheKey(
        val publicKeyToken: String,
        val callerIdentity: AgentApprovalCacheIdentity,
    )
}
