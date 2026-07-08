package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
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
) {
    private val memory = AgentApprovalWindowMemory<SshApprovalCacheKey>(
        approvalWindow = getSshAgentApprovalWindow(),
        getVaultSession = getVaultSession,
        scope = scope,
    )

    suspend fun clearSession() {
        memory.clearSession()
    }

    suspend fun getOrGenerateSession(
        session: MasterSession.Key,
    ): Session = Session(memory.getOrGenerateSession(session))

    private fun SshAgentMessages.SignDataRequest.toApprovalCacheKey(): SshApprovalCacheKey {
        val publicKeyToken = decodeSshPublicKeyBlob(publicKey)
            ?.toHex()
            ?: publicKey.trim()
        return SshApprovalCacheKey(
            publicKeyToken = publicKeyToken,
            callerToken = caller.toApprovalCacheToken(),
        )
    }

    private fun SshAgentMessages.CallerIdentity?.toApprovalCacheToken(): String {
        // At this moment we are not precise at all with the caller
        // identity, so for now just use a generic name of the app.
        return "generic-caller=${this?.appName.orEmpty()}"
    }

    inner class Session internal constructor(
        private val session: AgentApprovalWindowMemory<SshApprovalCacheKey>.Session,
    ) {
        val generation: Long
            get() = session.generation

        suspend fun isRemembered(
            request: SshAgentMessages.SignDataRequest,
        ): Boolean = session.isRemembered(request.toApprovalCacheKey())

        suspend fun remember(
            request: SshAgentMessages.SignDataRequest,
        ) {
            session.remember(request.toApprovalCacheKey())
        }
    }

    internal data class SshApprovalCacheKey(
        val publicKeyToken: String,
        val callerToken: String,
    )
}
