package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentRequest
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlin.time.Instant

data class GpgAgentApprovalRequest(
    override val id: String,
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
    override val caller: CallerIdentity?,
    /** The notification that represents the request, if any. */
    val notificationTag: String? = null,
    override val expiresAt: Instant,
) : AgentRequest

internal fun GpgAgentApprovalPrompt.toApprovalRequest(
    id: String,
    expiresAt: Instant,
    notificationTag: String? = null,
): GpgAgentApprovalRequest = GpgAgentApprovalRequest(
    id = id,
    operation = operation,
    keyName = keyName,
    keyFingerprint = keyFingerprint,
    keygrip = keygrip,
    accountId = accountId,
    cipherId = cipherId,
    caller = caller,
    notificationTag = notificationTag,
    expiresAt = expiresAt,
)
