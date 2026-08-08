package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

sealed interface GpgAgentRequest : AgentRequest {
    override val caller: GpgAgentMessages.CallerIdentity?

    override val notificationTag: String?

    override val expiresAt: Instant

    override val deferred: CompletableDeferred<Boolean>

    override val logType: String
        get() = when (this) {
            is GpgAgentApprovalRequest -> "approval"
        }
}

data class GpgAgentApprovalRequest(
    val operation: GpgAgentOperation,
    val keyName: String,
    val keyFingerprint: String,
    val keygrip: String,
    /**
     * Identity of the vault entry holding the key, when known; lets
     * the approval surface link the request to the entry.
     */
    val accountId: String?,
    val cipherId: String?,
    override val caller: GpgAgentMessages.CallerIdentity?,
    override val notificationTag: String? = null,
    override val expiresAt: Instant,
    override val deferred: CompletableDeferred<Boolean>,
) : GpgAgentRequest
